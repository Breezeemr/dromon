(ns com.breezeehr.fhir-xml
  "FHIR XML parse/unparse driven by the malli schemas generated from FHIR
  StructureDefinitions.

  FHIR XML is a far narrower format than general XSD, so this is a direct
  schema walk rather than a content-model compiler: every child is a named
  element, child order is fixed by the StructureDefinition, primitives carry
  their value in a `value` attribute, and the only mixed content in a resource
  is the Narrative div.

  Parsing produces the WIRE shape: every primitive is the lexical string as it
  appeared in the `value` attribute. Typed values (BigDecimal, LocalDate,
  OffsetDateTime) are a separate `m/decode` step, mirroring how the JSON side
  layers `com.breezeehr.fhir-json-transform` over a wire-shaped value. Keeping
  the lexical form is what makes XML round-trip exact: `OffsetDateTime/toString`
  drops `:00` seconds, which FHIR requires, and normalizes `+00:00` to `Z`.

  Reader contract, held by every parse fn below: entered with the reader ON the
  element's START_ELEMENT, returns with the reader ON the matching END_ELEMENT.
  The caller owns advancing past it."
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [com.breezeehr.fhir-json-transform :as fjt])
  (:import (java.io Reader StringReader StringWriter Writer)
           (javax.xml.stream XMLInputFactory XMLOutputFactory
                             XMLStreamReader XMLStreamWriter)))

(set! *warn-on-reflection* true)

(def fhir-ns "http://hl7.org/fhir")
(def xhtml-ns "http://www.w3.org/1999/xhtml")

;; ---------------------------------------------------------------------------
;; schema walk
;; ---------------------------------------------------------------------------

(defn- sprops [s] (or (m/properties s) {}))

(defn- companion-key
  "JSON-side sibling that carries a primitive's id/extension: :family -> :_family."
  [k]
  (keyword (str "_" (name k))))

(defn- json-only-key?
  "Keys that exist for the JSON encoding and have no FHIR XML element."
  [k]
  (or (= :resourceType k)
      (.startsWith (name k) "_")))

(declare compile-node)

(defn- any-resource-map?
  "The polymorphic Resource slot: an entryless map marked resourceType Resource."
  [s]
  (and (= :map (m/type s))
       (= "Resource" (:resourceType (sprops s)))
       (empty? (m/children s))))

(defn- compile-complex [s ctx]
  (let [children (m/children s)
        by-key (into {} (map (fn [[k p c]] [k [p c]])) children)
        declared (:fhir/element-order (sprops s))
        declared-set (set declared)
        ;; The snapshot order says WHERE elements go, but it is not the
        ;; authority on membership: profile schemas promote extension slices to
        ;; map keys that the base StructureDefinition's order never mentions,
        ;; and treating the vector as a whitelist would silently drop them.
        ;; :resourceType is the one key that needs the order vector to decide --
        ;; it is synthetic at a resource root, but ExampleScenario.instance
        ;; really does have a `resourceType` code element.
        xml-key? (fn [k]
                   (cond
                     (.startsWith (name k) "_") false
                     (= :resourceType k) (contains? declared-set k)
                     :else true))
        attr-keys (into {}
                        (keep (fn [[k p _]]
                                (when (:xml/attr p) [(name k) k])))
                        children)
        element-children
        (into {}
              (keep (fn [[k p c]]
                      (when (and (xml-key? k) (not (:xml/attr p)))
                        (let [seq? (= :sequential (m/type c))
                              inner (if seq? (first (m/children c)) c)
                              comp-entry (get by-key (companion-key k))
                              comp-schema (when comp-entry
                                            (let [cs (second comp-entry)]
                                              (if (= :sequential (m/type cs))
                                                (first (m/children cs))
                                                cs)))]
                          [(name k) {:key k
                                     :repeats? seq?
                                     :companion-key (when comp-entry (companion-key k))
                                     :companion (when comp-schema (compile-node comp-schema ctx))
                                     :node (compile-node inner ctx)}]))))
              children)
        in-order (into [] (filter #(contains? element-children (name %))) declared)
        ;; Anything the order vector does not mention still has to be written;
        ;; it goes after the declared elements rather than being dropped.
        extra (into [] (comp (map first)
                             (filter #(contains? element-children (name %)))
                             (remove declared-set))
                    children)]
    {:kind :complex
     :attrs attr-keys
     :children element-children
     :order (into in-order extra)
     :attr-order (into [] (comp (map first) (filter #(contains? attr-keys (name %)))) children)}))

(defn- compile-node
  "Returns a delay of a node. Refs go through a cache so cyclic schemas
  (Element -> Extension -> ... -> Element) terminate."
  [s ctx]
  (let [t (m/type s)]
    (cond
      (= :ref t)
      (let [k (first (m/children s))]
        (or (get @(:cache ctx) k)
            (let [d (delay @(compile-node (m/deref s) ctx))]
              (swap! (:cache ctx) assoc k d)
              d)))

      (#{:schema :malli.core/schema} t)
      (compile-node (m/deref s) ctx)

      (any-resource-map? s)
      (delay {:kind :any-resource})

      (= "xhtml" (:fhir/primitive (sprops s)))
      (delay {:kind :xhtml})

      (= :map t)
      (delay (compile-complex s ctx))

      (= :multi t)
      ;; Slice dispatch is a validation concern; every branch serializes under
      ;; the same element name, so parse with the first branch.
      (compile-node (last (first (m/children s))) ctx)

      :else
      (delay {:kind :primitive}))))

(defn compile-schema
  "Compile a generated FHIR malli schema into a node tree for parse/unparse.
  `resources` maps a resource type name to its schema, for the polymorphic
  Resource slot (contained, Bundle.entry.resource, Parameters.parameter.resource)."
  ([schema] (compile-schema schema {}))
  ([schema resources]
   (let [ctx {:cache (atom {}) :resources resources}
         s (m/schema schema)]
     {:root @(compile-node s ctx)
      :resource-type (:resourceType (sprops s))
      :ctx ctx})))

(defn- resource-node
  "Compile (and cache) the node for a resource type named in a Resource slot."
  [ctx ^String type-name]
  (let [k [::resource type-name]]
    (if-let [d (get @(:cache ctx) k)]
      @d
      (when-let [sch (let [rs (:resources ctx)]
                       (if (fn? rs) (rs type-name) (get rs type-name)))]
        (let [d (delay @(compile-node (m/schema sch) ctx))]
          (swap! (:cache ctx) assoc k d)
          @d)))))


;; ---------------------------------------------------------------------------
;; emitter
;; ---------------------------------------------------------------------------
;;
;; The JDK XMLStreamWriter writes a literal newline inside an attribute value,
;; and XML attribute-value normalization then collapses it to a space on the
;; way back in. FHIR content really does carry newlines in attributes (the
;; corpus has 4258 `&#xA;`), so we escape them ourselves.

(defn- esc-text ^String [^String s]
  (-> s (.replace "&" "&amp;") (.replace "<" "&lt;") (.replace ">" "&gt;")))

(defn- esc-attr ^String [^String s]
  (-> s (.replace "&" "&amp;") (.replace "<" "&lt;") (.replace ">" "&gt;")
      (.replace "\"" "&quot;")
      (.replace "\t" "&#x9;") (.replace "\n" "&#xA;") (.replace "\r" "&#xD;")))

(defn- out [] {:sb (StringBuilder.) :open (volatile! false)})

(defn- close-tag! [o]
  (when @(:open o)
    (.append ^StringBuilder (:sb o) ">")
    (vreset! (:open o) false)))

(defn- start! [o ^String tag]
  (close-tag! o)
  (doto ^StringBuilder (:sb o) (.append "<") (.append tag))
  (vreset! (:open o) true))

(defn- attr! [o ^String k ^String v]
  (doto ^StringBuilder (:sb o)
    (.append " ") (.append k) (.append "=\"") (.append (esc-attr v)) (.append "\"")))

(defn- text! [o ^String t]
  (close-tag! o)
  (.append ^StringBuilder (:sb o) (esc-text t)))

(defn- end! [o ^String tag]
  (if @(:open o)
    (do (.append ^StringBuilder (:sb o) "/>")
        (vreset! (:open o) false))
    (doto ^StringBuilder (:sb o) (.append "</") (.append tag) (.append ">"))))

(defn- out-str ^String [o] (close-tag! o) (str (:sb o)))


;; ---------------------------------------------------------------------------
;; parse
;; ---------------------------------------------------------------------------

(defn- skip-element
  "Reader is on a START_ELEMENT we have no schema for; leave it on the matching
  END_ELEMENT."
  [^XMLStreamReader r]
  (loop [depth 0]
    (case (.next r)
      1 (recur (inc depth))
      2 (when (pos? depth) (recur (dec depth)))
      (recur depth))))

(defn- copy-subtree
  "Serialize the current element (reader on START_ELEMENT) to a string, leaving
  the reader on the matching END_ELEMENT. Used for the Narrative div, whose
  content is XHTML markup rather than FHIR structure."
  [^XMLStreamReader r]
  (let [o (out)
        emit-start (fn [first?]
                     (start! o (.getLocalName r))
                     (let [uri (.getNamespaceURI r)]
                       (when (and first? uri (not (.isEmpty ^String uri)))
                         (attr! o "xmlns" uri)))
                     (dotimes [i (.getAttributeCount r)]
                       (attr! o (.getAttributeLocalName r i) (.getAttributeValue r i))))
        root-tag (.getLocalName r)]
    (emit-start true)
    (loop [depth 0 stack (list root-tag)]
      (case (.next r)
        1 (let [t (.getLocalName r)]
            (emit-start false)
            (recur (inc depth) (conj stack t)))
        2 (do (end! o (first stack))
              (when (pos? depth) (recur (dec depth) (rest stack))))
        (4 12) (do (text! o (.getText r)) (recur depth stack))
        (recur depth stack)))
    (out-str o)))

(declare parse-node)

(defn- parse-primitive
  "Returns [lexical-value companion]. The value lives in the `value` attribute;
  `id` and `extension` children may accompany it, and a primitive may legally
  carry extensions with NO value. `companion` is the schema for the JSON-side
  _foo sibling, which is what gives us a parser for those extensions."
  [^XMLStreamReader r ctx companion-node]
  (let [n (.getAttributeCount r)
        find-attr (fn [nm] (loop [i 0]
                             (cond (>= i n) nil
                                   (= nm (.getAttributeLocalName r i)) (.getAttributeValue r i)
                                   :else (recur (inc i)))))
        v (find-attr "value")
        id (find-attr "id")
        ext-child (get-in companion-node [:children "extension"])]
    (loop [exts nil]
      (case (.next r)
        1 (if (and ext-child (= "extension" (.getLocalName r)))
            (recur (conj (or exts []) (parse-node @(:node ext-child) r ctx)))
            (do (skip-element r) (recur exts)))
        2 [v (when (or id exts)
               (cond-> {} id (assoc :id id) exts (assoc :extension exts)))]
        (recur exts)))))

(defn- conj-at [m k v]
  (assoc m k (conj (or (get m k) []) v)))

(defn- drop-empty-companions
  "A companion vector of all nils means no repeat carried id/extension."
  [m]
  (reduce-kv (fn [acc k v]
               (if (and (vector? v) (every? nil? v) (.startsWith (name k) "_"))
                 (dissoc acc k)
                 acc))
             m m))

(defn- parse-complex [node ^XMLStreamReader r ctx]
  (let [attrs (:attrs node)
        children (:children node)
        init (reduce (fn [acc i]
                       (if-let [k (get attrs (.getAttributeLocalName r i))]
                         (assoc acc k (.getAttributeValue r i))
                         acc))
                     {}
                     (range (.getAttributeCount r)))]
    (loop [acc init]
      (case (.next r)
        1 (if-let [{ck :key repeats? :repeats? cnode :node
                    comp :companion comp-key :companion-key} (get children (.getLocalName r))]
            (let [n @cnode
                  companion (when comp @comp)
                  [v c] (if (= :primitive (:kind n))
                          (parse-primitive r ctx companion)
                          [(parse-node n r ctx) nil])]
              (recur
               (if repeats?
                 (let [acc (conj-at acc ck v)
                       cnt (count (get acc ck))]
                   (if comp-key
                     (assoc acc comp-key
                            (conj (into [] (take (dec cnt))
                                        (concat (get acc comp-key []) (repeat nil)))
                                  c))
                     acc))
                 (cond-> (assoc acc ck v)
                   c (assoc comp-key c)))))
            (do (skip-element r) (recur acc)))
        2 (drop-empty-companions acc)
        (recur acc)))))

(defn- parse-any-resource
  "Reader is on the wrapper (<contained>, <resource>); its single child element
  is named for the concrete resource type, which is what JSON spells as
  resourceType."
  [^XMLStreamReader r ctx]
  (loop [result nil]
    (case (.next r)
      1 (let [tn (.getLocalName r)]
          (if-let [n (resource-node ctx tn)]
            (recur (assoc (parse-complex n r ctx) :resourceType tn))
            (do (skip-element r) (recur result))))
      2 result
      (recur result))))

(defn- parse-node [node ^XMLStreamReader r ctx]
  (case (:kind node)
    :complex (parse-complex node r ctx)
    :any-resource (parse-any-resource r ctx)
    :xhtml (copy-subtree r)
    :primitive (first (parse-primitive r ctx nil))))

;; ---------------------------------------------------------------------------
;; unparse
;; ---------------------------------------------------------------------------

(declare write-node)

(defn- write-xml-string
  "Re-emit a serialized XHTML fragment (the Narrative div) as markup."
  [o ^String xml]
  (let [f (XMLInputFactory/newInstance)
        _ (.setProperty f XMLInputFactory/IS_COALESCING true)
        _ (.setProperty f XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
        _ (.setProperty f XMLInputFactory/SUPPORT_DTD false)
        ^XMLStreamReader r (.createXMLStreamReader f (StringReader. xml))]
    (loop [depth -1 stack ()]
      (when (.hasNext r)
        (case (.next r)
          1 (let [t (.getLocalName r)]
              (start! o t)
              (let [uri (.getNamespaceURI r)]
                (when (and (neg? depth) uri (not (.isEmpty ^String uri)))
                  (attr! o "xmlns" uri)))
              (dotimes [i (.getAttributeCount r)]
                (attr! o (.getAttributeLocalName r i) (.getAttributeValue r i)))
              (recur (inc depth) (conj stack t)))
          2 (do (end! o (first stack))
                (when (pos? depth) (recur (dec depth) (rest stack))))
          (4 12) (do (text! o (.getText r)) (recur depth stack))
          (recur depth stack))))))

(defn- write-primitive [o ^String tag value companion companion-node ctx]
  (start! o tag)
  (when-let [id (:id companion)] (attr! o "id" (str id)))
  (when (some? value) (attr! o "value" (str value)))
  (when-let [exts (seq (:extension companion))]
    (when-let [ext-child (get-in companion-node [:children "extension"])]
      (doseq [e exts]
        (write-node o "extension" @(:node ext-child) e ctx))))
  (end! o tag))

(defn- write-complex [o node data ctx]
  (doseq [k (:attr-order node)
          :let [v (get data k)]
          :when (some? v)]
    (attr! o (name k) (str v)))
  (doseq [k (:order node)
          :let [{ck :key repeats? :repeats? cnode :node
                 comp :companion comp-key :companion-key} (get (:children node) (name k))
                v (get data ck)
                comps (when comp-key (get data comp-key))]
          :when (or (some? v) (some? comps))]
    (let [n @cnode
          companion (when comp @comp)
          tag (name ck)]
      (if repeats?
        (let [vs (if (sequential? v) (vec v) (if (some? v) [v] []))
              cs (if (sequential? comps) (vec comps) [])
              cnt (max (count vs) (count cs))]
          (dotimes [i cnt]
            (let [vi (nth vs i nil) ci (nth cs i nil)]
              (if (= :primitive (:kind n))
                (write-primitive o tag vi ci companion ctx)
                (write-node o tag n vi ctx)))))
        (if (= :primitive (:kind n))
          (write-primitive o tag v comps companion ctx)
          (write-node o tag n v ctx))))))

(defn- write-node [o ^String tag node data ctx]
  (case (:kind node)
    :xhtml (write-xml-string o data)
    :any-resource
    (do (start! o tag)
        (when-let [tn (:resourceType data)]
          (when-let [n (resource-node ctx tn)]
            (start! o (str tn))
            (write-complex o n data ctx)
            (end! o (str tn))))
        (end! o tag))
    :primitive (write-primitive o tag data nil nil ctx)
    (do (start! o tag)
        (write-complex o node data ctx)
        (end! o tag))))

;; ---------------------------------------------------------------------------
;; public API
;; ---------------------------------------------------------------------------

(defn- stream-reader ^XMLStreamReader [source]
  (let [f (XMLInputFactory/newInstance)]
    (.setProperty f XMLInputFactory/IS_COALESCING true)
    (.setProperty f XMLInputFactory/IS_NAMESPACE_AWARE true)
    ;; FHIR forbids DTD references in resources outright (XXE).
    (.setProperty f XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
    (.setProperty f XMLInputFactory/SUPPORT_DTD false)
    (if (instance? Reader source)
      (.createXMLStreamReader f ^Reader source)
      (.createXMLStreamReader f (StringReader. (str source))))))

(defn parser
  "Compile `schema` into (fn [source] data). `resources` resolves a resource type
  name to its schema, for the polymorphic Resource slot (contained,
  Bundle.entry.resource, Parameters.parameter.resource). It may be a map or a fn."
  ([schema] (parser schema {}))
  ([schema resources]
   (let [{:keys [root ctx resource-type]} (compile-schema schema resources)]
     (fn [source]
       (let [r (stream-reader source)]
         (loop []
           (if (= 1 (.getEventType r))
             (cond-> (parse-complex root r ctx)
               resource-type (assoc :resourceType resource-type))
             (do (.next r) (recur)))))))))

(defn unparser
  "Compile `schema` into (fn [data] xml-string)."
  ([schema] (unparser schema {}))
  ([schema resources]
   (let [{:keys [root ctx resource-type]} (compile-schema schema resources)]
     (fn [data]
       (let [o (out)
             tag (str (or (:resourceType data) resource-type))]
         (.append ^StringBuilder (:sb o) "<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
         (start! o tag)
         (attr! o "xmlns" fhir-ns)
         (write-complex o root data ctx)
         (end! o tag)
         (out-str o))))))

(defn parse
  "One-shot parse. Prefer `parser` when reading many documents."
  ([schema source] (parse schema source {}))
  ([schema source resources] ((parser schema resources) source)))

(defn unparse
  "One-shot unparse. Prefer `unparser` when writing many documents."
  ([schema data] (unparse schema data {}))
  ([schema data resources] ((unparser schema resources) data)))

;; ---------------------------------------------------------------------------
;; typed values
;; ---------------------------------------------------------------------------

(def xml-transformer
  "Wire shape <-> typed shape. `parse` yields the wire shape (every primitive a
  lexical string, which is what makes XML round-trip exact); decoding with this
  gives the typed shape the schemas validate as numbers, booleans and
  java.time values. `mt/string-transformer` covers boolean/int/decimal, and the
  FHIR JSON transformer supplies FHIR's partial-precision temporals
  (:time/year, :time/year-month) that plain malli time decoding does not."
  (mt/transformer mt/string-transformer (fjt/fhir-json-transformer)))

(defn decode-typed
  "Wire-shape data -> typed data validating against `schema`."
  [schema data]
  (m/decode schema data xml-transformer))

(defn encode-wire
  "Typed data -> wire shape suitable for `unparse`."
  [schema data]
  (m/encode schema data xml-transformer))
