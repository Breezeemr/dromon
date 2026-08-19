(ns com.breezeehr.fhir-xml-canonical
  "Canonical XML equivalence for the FHIR XML round-trip gate.

  Both sides are parsed with a namespace-aware StAX reader into a canonical
  tree, then compared. Normalized away (on both sides): the XML declaration,
  comments, processing instructions, attribute order, self-closing vs empty
  tag pairs, namespace prefix choice, and whitespace-only text between FHIR
  elements. Compared exactly: element order, qualified names, attribute
  values, and all text inside the XHTML narrative."
  (:import (javax.xml.stream XMLInputFactory XMLStreamReader)
           (java.io StringReader)))

(def fhir-ns "http://hl7.org/fhir")
(def xhtml-ns "http://www.w3.org/1999/xhtml")

(defn- reader ^XMLStreamReader [^String s]
  (let [f (XMLInputFactory/newInstance)]
    (.setProperty f XMLInputFactory/IS_COALESCING true)
    (.setProperty f XMLInputFactory/IS_NAMESPACE_AWARE true)
    (.setProperty f XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
    (.setProperty f XMLInputFactory/SUPPORT_DTD false)
    (.createXMLStreamReader f (StringReader. s))))

(defn- qname [^XMLStreamReader r]
  [(.getNamespaceURI r) (.getLocalName r)])

(defn- attrs [^XMLStreamReader r]
  (into {}
        (map (fn [i]
               [[(not-empty (.getAttributeNamespace r i)) (.getAttributeLocalName r i)]
                (.getAttributeValue r i)]))
        (range (.getAttributeCount r))))

(defn- read-element
  "Reader is on START_ELEMENT; returns the canonical node and leaves the reader
  on the matching END_ELEMENT."
  [^XMLStreamReader r]
  (let [[ns-uri local] (qname r)
        xhtml? (= xhtml-ns ns-uri)
        a (attrs r)]
    (loop [children []]
      (case (.next r)
        1 (recur (conj children (read-element r)))
        2 {:tag [ns-uri local] :attrs a :children children}
        (4 12) (let [t (.getText r)]
                 ;; Text is significant only inside the narrative. FHIR elements
                 ;; never carry text, so anything here is layout whitespace.
                 (recur (if (and xhtml? (not= "" t))
                          (conj children t)
                          children)))
        ;; comments, PIs, space, entity refs, DTD
        (recur children)))))

(defn canonical
  "Parse an XML string into its canonical comparison tree."
  [^String s]
  (let [r (reader s)]
    (loop []
      (if (= 1 (.getEventType r))
        (read-element r)
        (do (.next r) (recur))))))

(def ^:private exponent? #(re-find #"[eE]" ^String %))

(defn- value=
  "Attribute values compare by string identity, with one exception: a decimal
  written in exponent notation is the same number as its plain form, and
  BigDecimal normalizes the exponent on the way out. Scale is still
  significant, so 1.0 and 1.00 remain different."
  [a b]
  (or (= a b)
      (and (or (exponent? a) (exponent? b))
           (try (= (BigDecimal. ^String a) (BigDecimal. ^String b))
                (catch NumberFormatException _ false)))))

(declare node=)

(defn- children= [as bs path]
  (if (not= (count as) (count bs))
    [{:path path :why :child-count :a (count as) :b (count bs)
      :a-tags (mapv #(if (string? %) :text (second (:tag %))) as)
      :b-tags (mapv #(if (string? %) :text (second (:tag %))) bs)}]
    (into [] (comp (map-indexed (fn [i [x y]] (node= x y (conj path i)))) cat)
          (map vector as bs))))

(defn- node= [a b path]
  (cond
    (and (string? a) (string? b))
    (if (= a b) [] [{:path path :why :text :a a :b b}])

    (or (string? a) (string? b))
    [{:path path :why :text-vs-element :a (str a) :b (str b)}]

    (not= (:tag a) (:tag b))
    [{:path path :why :tag :a (:tag a) :b (:tag b)}]

    :else
    (let [ka (set (keys (:attrs a))) kb (set (keys (:attrs b)))]
      (into (if (= ka kb)
              (into [] (keep (fn [k]
                               (let [va (get (:attrs a) k) vb (get (:attrs b) k)]
                                 (when-not (value= va vb)
                                   {:path path :why :attr-value :attr k :a va :b vb}))))
                    ka)
              [{:path path :why :attr-keys :a ka :b kb}])
            (children= (:children a) (:children b) path)))))

(defn diff
  "Returns a vector of differences between two XML strings; empty means the
  round-trip is clean."
  [a b]
  (node= (canonical a) (canonical b) []))

(defn equivalent? [a b] (empty? (diff a b)))
