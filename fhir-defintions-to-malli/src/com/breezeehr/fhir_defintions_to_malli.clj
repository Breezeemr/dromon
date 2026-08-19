(ns com.breezeehr.fhir-defintions-to-malli
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [com.breezeehr.fhir-primitives :refer [external-registry staging-fhir-registry]]
            [fipp.edn :refer [pprint] :rename {pprint fipp}]
            [net.cgrand.xforms :as xforms]
            [malli.util :as mu]
            [malli.registry :as mr]
            [com.breezeehr.fhir-shape :as shape])
  (:import (java.net URL)
           (java.nio.file Files OpenOption Paths)
           (java.nio.file.attribute FileAttribute)))

(set! *print-namespace-maps* false)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- dedup-thread
  "Remove consecutive duplicate steps in a -> thread form.
   (-> x m/deref m/deref (mu/assoc ...)) → (-> x m/deref (mu/assoc ...))"
  [form]
  (if (and (seq? form) (= '-> (first form)))
    (let [steps (rest form)
          deduped (reduce (fn [acc step]
                            (if (= step (peek acc))
                              acc
                              (conj acc step)))
                          [] steps)]
      (apply list '-> deduped))
    form))

(defn- dedup-forms
  "Walk a form tree and dedup -> threads."
  [form]
  (cond
    (and (seq? form) (= '-> (first form)))
    (dedup-thread (apply list (first form) (map dedup-forms (rest form))))

    (seq? form)
    (apply list (map dedup-forms form))

    (vector? form)
    (mapv dedup-forms form)

    :else form))

(defn make-into-schema-form [form]
  `(~'reify ~'m/IntoSchema
            (~'-into-schema [~'_ ~'_ ~'_ ~'options] ~(dedup-forms form))))

(def java-reserved-words
  #{"abstract" "continue" "for" "new" "switch"
    "assert" "default" "goto" "package" "synchronized"
    "boolean" "do" "if" "private" "this"
    "break" "double" "implements" "protected" "throw"
    "byte" "else" "import" "public" "throws"
    "case" "enum" "instanceof" "return" "transient"
    "catch" "extends" "int" "short" "try"
    "char" "final" "interface" "static" "void"
    "class" "finally" "long" "strictfp" "volatile"
    "const" "float" "native" "super" "while"})

(defn munge-ns [s]
  (when s
    (let [parts (str/split s #"\.")]
      (str/join "."
                (map (fn [p]
                       (let [p' (str/replace p "_" "-")]
                         (if (xforms/some (filter #(.startsWith p' %)) java-reserved-words)
                           (str "-" p')
                           p')))
                     parts)))))

(def ^:private base-ns-prefix
  "Base namespace prefix for FHIR StructureDefinitions (without version)."
  "org.hl7.fhir.StructureDefinition")

(defn kw->type-name
  "Extract the type name from a schema keyword.
   :org.hl7.fhir.StructureDefinition.Bundle/v4-3-0 => \"Bundle\""
  [k]
  (last (str/split (namespace k) #"\.")))

(defn discover-versions
  "Given a schema keyword (or its namespace prefix + type name), use the classloader
   to discover which versions are available on the classpath.
   Returns a seq of version strings like (\"v4-3-0\" \"v8-0-1\")."
  [ns-prefix type-name]
  (let [dir-path (str (str/replace (str ns-prefix "." type-name) "." "/") "/")
        cl (.getContextClassLoader (Thread/currentThread))
        url (.getResource cl dir-path)]
    (when url
      (let [dir (io/file url)]
        (when (.isDirectory dir)
          (into []
                (comp (filter #(.endsWith (.getName ^java.io.File %) ".cljc"))
                      (map #(str/replace (.getName ^java.io.File %) ".cljc" ""))
                      (map #(str/replace % "_" "-")))
                (.listFiles dir)))))))

(defn strip-canonical-version
  "Drop a `|version` suffix from a canonical URL. The pipe is illegal in a URL
   path; FHIR uses it to pin a canonical to a specific published version."
  ^String [^String u]
  (let [idx (.indexOf u "|")]
    (if (pos? idx) (.substring u 0 idx) u)))

(defn canonical-version
  "The `|version` suffix of a canonical URL, or nil when it carries no pin."
  [^String u]
  (let [idx (.indexOf u "|")]
    (when (pos? idx) (.substring u (inc idx)))))

(defn uri->kw2 [^String x version]
  (let [;; Strip |version suffix (e.g. "...artifact-versionAlgorithm|5.3.0-ballot-tc1")
        ;; The pipe is illegal in a URL path and the version is passed separately.
        x     (let [idx (.indexOf x "|")]
                (if (pos? idx) (.substring x 0 idx) x))
        url   ^URL (io/as-url x)
        base  (reverse (str/split (.getHost url) #"\."))
        path  (into [] (remove empty?) (str/split (.getPath url) #"\/"))
        ppath (into [] (butlast path))
        l     (last path)
        ;; Replace dots in the name with hyphens so they don't become
        ;; Clojure namespace segments (e.g. xver extensions like
        ;; extension-Questionnaire.item.answerConstraint)
        type-name (munge-ns (str/replace l "." "-"))
        ver  (str "v" (str/replace version "." "-"))]
    ;; Layout: :org.hl7...StructureDefinition.<type-name>/<version>
    ;; This puts the type name as a directory so versions can be discovered
    ;; via classloader getResource on the parent path.
    (keyword (munge-ns (str/join "." (-> []
                                         (into base)
                                         (into ppath)
                                         (conj type-name))))
             ver)))

(defn- lookup-kw
  "Look up a keyword in a registry map by type name (last segment of namespace).
   When `base-prefix` and `version` are provided, falls back to constructing a keyword."
  ([registry-map m-code]
   (first (filter #(= (kw->type-name %) m-code) (keys registry-map))))
  ([registry-map m-code base-prefix version]
   (or (first (filter #(= (kw->type-name %) m-code) (keys registry-map)))
       (keyword (str base-prefix "." m-code)
                (str "v" (str/replace version "." "-"))))))

(defn underscore-attr [k]
  (keyword (namespace k) (str "_" (name k))))

(defn kw->ns-sym
  "Converts a keyword like :org.hl7.fhir.../Foo to the symbol org.hl7.fhir...Foo."
  [k]
  (symbol (str (namespace k) "." (name k))))

(defn kw->sch-sym
  "Converts a keyword to a symbol referencing its `sch` var."
  [k]
  (symbol (str (namespace k) "." (name k)) "sch"))

(defn kw->full-sch-sym
  "Converts a keyword to a symbol referencing its `full-sch` var."
  [k]
  (symbol (str (namespace k) "." (name k)) "full-sch"))

(defn kw->lazy-sch-form
  "Generate a form that lazily resolves a schema keyword's sch var at runtime.
   Produces: @(requiring-resolve 'some.ns/sch)"
  [k]
  `(~'deref (~'requiring-resolve '~(kw->sch-sym k))))

;; ---------------------------------------------------------------------------
;; Dynamic state
;; ---------------------------------------------------------------------------

(def ^:dynamic *schema-atom* nil)
(def ^:dynamic *references-atom* nil)
(def ^:dynamic *local-registry* nil)
(def ^:dynamic *recursive-references* nil)
(def ^:dynamic *base-refs* nil)

(def ^:dynamic *element-order*
  "Snapshot-derived XML child order for the StructureDefinition being compiled,
   as {dotted-path -> [child-key ...]}. FHIR XML requires children in
   StructureDefinition order; malli entry order does not preserve it."
  nil)

(def ^:dynamic *canonical-index*
  "Canonical StructureDefinition URL (no `|version` pin) -> ordered vector of
   {:pkg-id :pkg-version :version :kw}, built by `gen/canonical-index` from a
   pre-scan of every plan in the run.

   Nil when no driver supplies one; resolution then falls back to the by-name
   scan of the schema atom."
  nil)

(def ^:dynamic *current-package*
  "The package whose definitions are being generated, as
   {:id :version :dependencies}. FHIR resolves a canonical against the
   *referencing* package's declared dependencies, so this is what makes a
   `|version` pin interpretable."
  nil)

(def ^:dynamic *known-canonical-kws*
  "Every schema keyword the canonical index promises will be generated in this
   run. A forward reference resolved through the index is not in the schema atom
   yet, so this is what tells the last-resort guard that the namespace is coming."
  #{})

(def ^:dynamic *defer-unready-base*
  "When true, a baseDefinition the canonical index says this run will generate,
   but which is not resolvable yet, raises ::base-not-ready instead of being
   patched against nil.

   A nil base is not a partial failure -- the walk has no structure to descend
   into, so the differential's elements are never visited and the definition is
   emitted with almost nothing. It does not throw, so a driver that retries
   failures never revisits it. Only enable this where reprocessing is safe:
   the later attempt must be able to replace the earlier one everywhere it was
   recorded, aliases included."
  false)

(def ^:dynamic *unresolved-profiles*
  "Optional atom collecting profile canonicals no package in the run defines, so
   the driver can report them instead of degrading them silently."
  nil)

(def ^:dynamic *current-definition*
  "URL of the StructureDefinition being processed, for diagnostics."
  nil)

;; ---------------------------------------------------------------------------
;; Canonical resolution
;;
;; FHIR does not define a normative algorithm for this; the one validators and
;; servers converge on is: find the package holding the referencing resource,
;; take its transitive dependency closure, collect every resource in that closure
;; whose url matches, and pick one deterministically. HL7's own guidance is that
;; leaving this to runtime "has proven too difficult for implementers" and that
;; authors should pin instead -- which is what the index does, at plan time.
;; ---------------------------------------------------------------------------

(defn- version-segments
  "Split a version string for comparison, inferring the scheme the way the FHIR
   tooling does when a resource declares no versionAlgorithm (all R4 content):
   numeric segments compare numerically, everything else lexically.

   A pre-release suffix orders below the release it qualifies, so 5.3.0 beats
   5.3.0-ballot-tc1, matching semver."
  [^String v]
  (let [[core pre] (str/split (or v "") #"-" 2)]
    [(mapv (fn [seg] (if (re-matches #"\d+" seg) [0 (parse-long seg)] [1 seg]))
           (str/split core #"[.]"))
     ;; absent pre-release sorts after any present one
     (if pre [0 pre] [1 ""])]))

(defn compare-canonical-versions
  "Order two resource versions, newest last. Used only to break a tie the
   reference itself does not resolve, where R4B's guidance is to take the latest."
  [a b]
  (compare (version-segments a) (version-segments b)))

(defn- pin-target-package
  "Which package does a `|version` pin name, when no candidate publishes that
   version outright?

   The pin is a Resource.version, and for an HL7 IG that is normally the package
   version, so the referencing package's declared dependency at that version
   identifies the package meant. This run may have supplied a different version
   of it than was declared -- xver declares hl7.fhir.uv.extensions.r4 5.2.0 while
   the pipeline builds 5.3.0-ballot-tc1 -- and resolving to the substitute is the
   whole point: no resource anywhere publishes 5.2.0, so an exact match can never
   succeed."
  [pin]
  (when (and pin *current-package*)
    (some (fn [[dep-id dep-version]] (when (= pin dep-version) dep-id))
          (:dependencies *current-package*))))

(defn resolve-canonical-kw
  "Resolve a canonical URL to the schema keyword of the StructureDefinition that
   defines it.

   In order:
     1. a candidate publishing exactly the pinned version -- the reference says
        which one it means and the run has it;
     2. the candidate from the package that pin names, found through the
        referencing package's declared dependencies, which covers a pin the run
        cannot satisfy verbatim because it substituted a different version;
     3. the newest candidate already generated, keeping the reference inside
        packages the referencing one has actually seen;
     4. the newest candidate in the run, covering a forward reference.

   Steps 3 and 4 replace a first-match over hash-map key order, which returned an
   arbitrary version and changed answer as the schema atom grew."
  [^String canonical]
  (when (and *canonical-index* *schema-atom*)
    (let [base    (strip-canonical-version canonical)
          pin     (canonical-version canonical)
          entries (get *canonical-index* base)]
      (when (seq entries)
        (let [newest    #(last (sort-by :version compare-canonical-versions %))
              generated (filterv #(contains? @*schema-atom* (:kw %)) entries)
              from-pkg  (when-let [pkg (pin-target-package pin)]
                          (seq (filterv #(= pkg (:pkg-id %)) entries)))]
          (:kw (or (when pin (first (filter #(= pin (:version %)) entries)))
                   (when from-pkg (newest from-pkg))
                   (when (seq generated) (newest generated))
                   (newest entries))))))))

(defn kw->base-fn-name
  "Derive a base function name from a schema keyword.
   E.g. :org.hl7.fhir.StructureDefinition.Element/v4-3-0 → \"base-Element\""
  [k]
  (str "base-" (last (str/split (namespace k) #"\."))))

(defn kw->base-fn-form
  "Generate a (base-Type) call form for a schema keyword, registering it
   in *base-refs* for later emission as a defn."
  [k]
  (let [fn-name (kw->base-fn-name k)]
    (when *base-refs*
      (swap! *base-refs* assoc fn-name k))
    (list (symbol fn-name))))

(defn- url-type-code?
  "True when element type code is a full StructureDefinition (or similar) URL."
  [code]
  (and (string? code)
       (or (str/starts-with? code "http://")
           (str/starts-with? code "https://"))))

(defn- lookup-schema-kw
  "Look up a type code in the schema atom, returning the keyword.

  Short FHIR codes (e.g. \"CodeableConcept\") resolve via type-name match under
  the FHIR StructureDefinition prefix. Full URLs (CDA / external SDs) resolve
  via uri->kw2 so registry keys match StructureDefinition :url conversion."
  [code version]
  (if (url-type-code? code)
    (let [kw (uri->kw2 code version)]
      (or (when (contains? @*schema-atom* kw) kw)
          (first (filter #(= (kw->type-name %) (kw->type-name kw)) (keys @*schema-atom*)))
          kw))
    (lookup-kw @*schema-atom* (munge-ns (str/replace code "." "-")) base-ns-prefix version)))

(def ^:private xml-choice-group-url
  "http://hl7.org/fhir/tools/StructureDefinition/xml-choice-group")

(defn representation-props
  "Map ElementDefinition.representation codes to malli entry properties.

  FHIR and CDA use the same codes on ElementDefinition:
    xmlAttr  — attribute on the parent element
    xmlText  — character data / simple content
    typeAttr — xsi:type (or equivalent) type discriminator
    xhtml    — xhtml content (Narrative)

  Also detects the tools xml-choice-group extension (CDA AD.item / PN.item)."
  [{:keys [representation extension] :as _main-attr}]
  (let [reps (set (map name (or representation [])))
        choice? (some (fn [x]
                        (and (= xml-choice-group-url (:url x))
                             (true? (or (:valueBoolean x)
                                        (get-in x [:valueBoolean])
                                        (:value x)))))
                      (or extension []))]
    (cond-> {}
      (contains? reps "xmlAttr") (assoc :xml/attr true)
      (contains? reps "xmlText") (assoc :xml/text true)
      (contains? reps "typeAttr") (assoc :xml/type-attr true)
      (contains? reps "xhtml") (assoc :xml/xhtml true)
      ;; Preserve raw codes for tooling that wants the FHIR set
      (seq reps) (assoc :fhir/representation (vec (sort reps)))
      choice? (assoc :xml/choice-group true))))

(defn- requiring-resolve-registry
  "A malli registry that resolves schema keywords by requiring-resolve of
   their staging namespace sch var, falling back to the staging FHIR primitives
   registry (which uses lazy-ref for :ref to tolerate forward references)."
  []
  {:registry (mr/composite-registry
              (mr/registry staging-fhir-registry)
              (reify mr/Registry
                (-schema [_ kw]
                  (when (qualified-keyword? kw)
                    (some-> (kw->sch-sym kw) requiring-resolve var-get)))
                (-schemas [_] {})))})

(defn- lr-key->def-name
  "Convert a local-registry key like \"#Bundle.link\" to a def name like \"Bundle-link\".
   Mirrors com.breezeehr.fhir-schema-gen/lr-key->def-name; duplicated here to avoid
   a circular require."
  [lr-key]
  (str/replace lr-key #"[#.]" {"#" "" "." "-"}))

(defn- requiring-resolve-local-def
  "Look up the var that backs a local-registry entry by requiring-resolving the
   parent namespace's emitted def. Returns the dereffed value, or nil if the var
   is not (yet) available."
  [source-kw lr-key]
  (try
    (let [def-sym (symbol (str (kw->ns-sym source-kw)) (lr-key->def-name lr-key))]
      (some-> (requiring-resolve def-sym) var-get))
    (catch Exception _ nil)))

(defn- resolve-local-registry-schemas
  "Build a malli-compatible registry map from local-registry entries.
   Prefers a live `requiring-resolve` of the parent namespace's emitted def, since
   the in-memory `:sch` snapshot in a local-registry entry is unreliable -- the
   patcher accumulates field changes onto `:form` without mirroring them onto
   `:sch`, so the cached schema can be stale (e.g., bare BackboneElement instead
   of a fully populated Questionnaire.item). Falls back to the stored `:sch` when
   the parent file hasn't been written yet."
  [local-reg]
  (into {}
        (map (fn [[k entry]]
               [k (case (:type entry)
                    :own (:sch entry)
                    :ref (or (requiring-resolve-local-def (:source-kw entry) k)
                             (let [src-lr (:local-registry (get @*schema-atom* (:source-kw entry)))]
                               (:sch (get src-lr k)))))]))
        local-reg))

(defn resolve-malli-sch
  "Resolve a schema keyword to its compiled malli schema.
   Tries requiring-resolve first (gets the fully-compiled schema from staging).
   Falls back to the in-memory schema-atom :sch for forward references that
   haven't been staged yet (e.g. within the same processing wave)."
  [kw]
  (or (when-let [v (try (requiring-resolve (kw->sch-sym kw)) (catch Exception _ nil))]
        (let [local-reg (some-> *schema-atom* deref (get kw) :local-registry)
              opts (cond-> (requiring-resolve-registry)
                     local-reg
                     (update :registry
                             #(mr/composite-registry % (mr/registry (resolve-local-registry-schemas local-reg)))))]
          (-> (var-get v)
              (m/schema opts)
              m/deref)))
      (some-> *schema-atom* deref (get kw) :sch)))

;; ---------------------------------------------------------------------------
;; FHIR primitives map
;; ---------------------------------------------------------------------------

(def fhir-primitives
  {"string"       [:string {:fhir/primitive "string"}]
   "markdown"     [:string {:fhir/primitive "markdown"}]
   ;; FHIR date/dateTime allow partial precision (YYYY, YYYY-MM). The :or branches
   ;; are ordered shortest-precision-first so decode resolves year -> year-month ->
   ;; date (-> dateTime), preserving the original precision as Year/YearMonth/etc.
   "date"         [:or {:fhir/primitive "date"} :time/year :time/year-month :time/local-date]
   "url"          [:string {:fhir/primitive "url"}]
   "integer"      [:int {:fhir/primitive "integer"}]
   "base64Binary" [:string {:fhir/primitive "base64Binary"}]
   "instant"      [:or {:fhir/primitive "instant"} :time/offset-date-time :time/instant]
   "xhtml"        [:string {:fhir/primitive "xhtml"}]
   "uuid"         [:uuid {:fhir/primitive "uuid"}]
   "id"           [:string {:fhir/primitive "id"}]
   "unsignedInt"  [:int {:fhir/primitive "unsignedInt" :min 0}]
   "canonical"    [:string {:fhir/primitive "canonical"}]
   "code"         [:string {:fhir/primitive "code"}]
   "oid"          [:string {:fhir/primitive "oid"}]
   "boolean"      [:boolean {:fhir/primitive "boolean"}]
   "time"         [:time/local-time {:fhir/primitive "time"}]
   "dateTime"     [:or {:fhir/primitive "dateTime"} :time/year :time/year-month :time/local-date :time/offset-date-time :time/instant]
   "uri"          [:string {:fhir/primitive "uri"}]
   "decimal"      [:decimal {:fhir/primitive "decimal"}]
   "positiveInt"  [:int {:fhir/primitive "positiveInt" :min 1}]})

;; ---------------------------------------------------------------------------
;; Schema resolution
;; ---------------------------------------------------------------------------

(defn prim-or-ref
  [acc {:keys [code]} version]
  (if (nil? code)
    acc
    (let [[sch primitive?]
          (if-some [prim-sch (fhir-primitives code)]
            [prim-sch true]
            [(case code
               "http://hl7.org/fhirpath/System.String" :string
               "Resource" [:map {:short "Any Resource" :resourceType "Resource"}]
               (let [kw (lookup-schema-kw code version)]
                 (swap! *references-atom* conj kw)
                 [:lazy-ref kw]))
             false])]
      (cond-> (assoc acc
                     :sch sch
                     :form [(m/form sch external-registry)])
        primitive? (assoc :primitive? true)))))


(declare element-definition->attribute)

(defn unwrap-sequential
  "Strip the collection wrapper off a repeating field to get the element schema.
   A repeating primitive is [:sequential [:maybe prim]] -- the :maybe holds the
   place of an occurrence that carries only id/extensions -- so step through it
   too and callers keep seeing the primitive itself."
  [sch]
  (if sch
    (case (m/type sch)
      :sequential (let [inner (mu/get sch 0)]
                    (if (and inner (= :maybe (m/type inner)))
                      (mu/get inner 0)
                      inner))
      sch)
    sch))


;; ---------------------------------------------------------------------------
;; Element patch dispatch
;; ---------------------------------------------------------------------------

(declare compute-element-patch)

(defn- group-sub-elements
  "Group sub-elements one level below main-path into [group-path items] pairs.
   Groups are ordered so a base field is processed before its slices, matching
   structure-definition->patch. Slices depend on the base having registered
   :pending-slicing first, and map iteration order does not guarantee that."
  [main-path sub-elements]
  (let [depth (inc (count main-path))
        base-name (fn [group-path]
                    (let [seg (or (peek group-path) "")
                          idx (str/index-of seg ":")]
                      (if idx (subs seg 0 idx) seg)))
        slice? (fn [group-path] (if (str/index-of (or (peek group-path) "") ":") 1 0))
        ordered (into []
                      (comp (remove #(-> % :path (= main-path)))
                            (map (fn [{:keys [path]}] (into [] (take depth) path)))
                            (distinct))
                      sub-elements)
        ;; Declaration order, except a base always precedes its own slices.
        rank (into {} (map-indexed (fn [i k] [k i])) ordered)
        base-rank (reduce (fn [m k] (update m (base-name k) (fnil min Long/MAX_VALUE) (rank k)))
                          {} ordered)
        grouped (reduce (fn [m {:keys [path] :as element}]
                          (update m (into [] (take depth) path) (fnil conj []) element))
                        {}
                        (remove #(-> % :path (= main-path)) sub-elements))]
    (mapv (fn [k] [k (grouped k)])
          (sort-by (juxt #(base-rank (base-name %)) slice? rank) ordered))))

;; ---------------------------------------------------------------------------
;; Slicing helpers
;; ---------------------------------------------------------------------------

(defn- ref-kw-from-sch
  "Extract the ref keyword from a schema, whether it's a vector like [:ref kw] or a schema object."
  [sch]
  (cond
    (and (vector? sch) (#{:ref :lazy-ref} (first sch)))
    (second sch)

    (and sch (not (vector? sch))
         (#{:ref :lazy-ref} (try (m/type sch) (catch Exception _ nil))))
    (first (m/children sch))

    :else nil))

(defn- resolve-ref-kw
  "Resolve a ref keyword to its compiled malli schema via requiring-resolve.

   A ref already names exactly one schema, so resolve that. Re-deriving it from
   the type name discards that -- `lookup-kw` is a first-match over hash-map key
   order, which returns an arbitrary entry among everything sharing the last
   namespace segment and changes answer as the atom grows. It stays as a fallback
   for a ref whose own version is not present, which is how the CDA driver's
   cross-version aliases were being found."
  [ref-kw]
  (when (keyword? ref-kw)
    (or (resolve-malli-sch ref-kw)
        (when-let [base (lookup-kw @*schema-atom* (kw->type-name ref-kw))]
          (resolve-malli-sch base)))))

(defn- resolve-sch-through-refs
  "Resolve a schema through refs to get the underlying schema. Returns sch unchanged if not a ref."
  [sch]
  (when sch
    (if-let [ref-kw (ref-kw-from-sch sch)]
      (resolve-ref-kw ref-kw)
      sch)))

(defn- discriminator-path->get-in-path
  [base-sch fhir-path]
  (when (and fhir-path (not= fhir-path "$this"))
    (let [segments (str/split fhir-path #"\.")]
      (loop [segs segments
             sch base-sch
             path []]
        (if (empty? segs)
          path
          (let [seg (first segs)
                k (keyword seg)
                raw-child (when sch
                            (try (mu/get sch k) (catch Exception _ nil)))
                child-sch (or raw-child
                              (when-let [resolved (resolve-sch-through-refs sch)]
                                (try (mu/get resolved k) (catch Exception _ nil))))
                is-seq? (and child-sch
                             (try (= :sequential (m/type child-sch))
                                  (catch Exception _ false)))
                inner-raw (when is-seq?
                            (try (mu/get child-sch 0) (catch Exception _ nil)))
                seq-inner (when is-seq?
                            (or (resolve-sch-through-refs inner-raw) inner-raw))
                next-sch (if is-seq?
                           seq-inner
                           (or (resolve-sch-through-refs child-sch) child-sch))]
            (recur (rest segs)
                   next-sch
                   (if is-seq?
                     (conj path k 0)
                     (conj path k)))))))))

(defn- resolve-this-discriminator-path
  "For $this discriminators, determine the dispatch path from a slice's pattern.
   e.g. patternIdentifier: {system: ...} => [:system]"
  [slices]
  (when-let [first-slice (first slices)]
    ;; slices are maps with :this-path from extract-dispatch-value
    (:this-path first-slice)))

(defn- make-dispatch-form
  ([discriminators base-sch]
   (make-dispatch-form discriminators base-sch nil))
  ([discriminators base-sch slices]
   (let [paths (keep (fn [{:keys [path]}]
                       (if (= path "$this")
                         (resolve-this-discriminator-path slices)
                         (discriminator-path->get-in-path base-sch path)))
                     discriminators)]
     (when (seq paths)
       (if (= (count paths) 1)
         `(~'fn [~'m] (~'get-in ~'m ~(vec (first paths))))
         `(~'fn [~'m] ~(vec (map (fn [p] `(~'get-in ~'m ~(vec p))) paths))))))))

(defn- extract-pattern-value
  "Extract a discriminating value from a pattern* or fixed* field on an element.
   Returns [value field-kw] where field-kw is the key within a complex pattern
   (e.g. :system for patternIdentifier), or nil if no pattern found."
  [elem]
  (let [pattern-keys (filter #(or (str/starts-with? (name %) "pattern")
                                  (str/starts-with? (name %) "fixed"))
                             (keys elem))]
    (some (fn [k]
            (let [v (get elem k)]
              (cond
                (string? v) [v nil]
                (map? v) (let [[fk fv] (first (sort-by key v))]
                           [fv (keyword (name fk))])
                :else [v nil])))
          pattern-keys)))

(defn- extract-fixed-value
  "Extract the discriminating scalar value from an element's pattern/fixed fields."
  [elem]
  (first (extract-pattern-value elem)))

(defn- clean-profile-url
  "Strip |version from a FHIR profile canonical."
  [p]
  (when (string? p)
    (let [idx (.indexOf ^String p "|")]
      (if (pos? idx) (.substring ^String p 0 idx) p))))

(defn- extract-profile-url
  "First type.profile canonical on an ElementDefinition, if any.
   Used for slicing discriminators with type=profile."
  [elem]
  (when elem
    (some (fn [t]
            (when-let [ps (:profile t)]
              (some clean-profile-url (if (sequential? ps) ps [ps]))))
          (:type elem))))

(defn- extract-type-code
  "First type.code on an ElementDefinition (for type discriminators)."
  [elem]
  (when elem
    (some :code (:type elem))))

(defn- extract-this-discriminator-path
  "For $this discriminators, determine the get-in path from the slice root's pattern.
   e.g. patternIdentifier: {system: ...} => [:system]"
  [elem]
  (when-let [[_ field-kw] (extract-pattern-value elem)]
    (when field-kw [field-kw])))

(defn- clean-path-segment [seg]
  (let [idx (str/index-of seg ":")]
    (if idx (subs seg 0 idx) seg)))

(defn- find-discriminator-match
  "Find the sub-element matching a relative discriminator path under slice-path."
  [sub-elements slice-path disc-path]
  (let [disc-segs (str/split disc-path #"\.")]
    (first
     (filter
      (fn [elem]
        (let [suffix (when (> (count (:path elem)) (count slice-path))
                       (subvec (:path elem) (count slice-path)))
              cleaned (when suffix (mapv clean-path-segment suffix))]
          (= cleaned (vec disc-segs))))
      sub-elements))))

(defn- discriminator-match-value
  "Value for one discriminator against a matched element.
   profile → type.profile URL; type → type.code; else fixed/pattern."
  [disc-type match]
  (case disc-type
    "profile" (extract-profile-url match)
    "type" (extract-type-code match)
    ;; value, exists, and unknown — use fixed/pattern scalars
    (extract-fixed-value match)))

(defn- finalize-dispatch-value
  "Ensure multi arm keys are unique and usable.
   All-nil vectors (failed profile extraction historically) fall back to slice-name."
  [dispatch-value slice-name]
  (cond
    (nil? dispatch-value)
    (keyword slice-name)

    (and (vector? dispatch-value) (every? nil? dispatch-value))
    (keyword slice-name)

    :else dispatch-value))

(defn- extract-dispatch-value
  "Extract the dispatch value for a slice. Returns a map with :dispatch-value and optionally
   :this-path (for $this discriminators, the get-in path to the discriminating field).

   Honors discriminator :type:
   - profile: type.profile on the element at path
   - type: type.code on the element at path
   - value / default: fixed*/pattern* scalars
   - $this path: fixed/pattern on the slice root"
  ([discriminators sub-elements slice-path]
   (extract-dispatch-value discriminators sub-elements slice-path nil))
  ([discriminators sub-elements slice-path slice-name]
   (let [results (mapv
                  (fn [{disc-path :path disc-type :type}]
                    (if (= disc-path "$this")
                      (let [slice-root (first (filter #(= (count (:path %)) (count slice-path))
                                                      sub-elements))]
                        {:value (discriminator-match-value disc-type slice-root)
                         :this-path (when-not (#{"type" "profile"} disc-type)
                                      (extract-this-discriminator-path slice-root))})
                      (let [match (find-discriminator-match sub-elements slice-path disc-path)]
                        {:value (discriminator-match-value disc-type match)})))
                  discriminators)
         vals (mapv :value results)
         this-path (some :this-path results)
         raw (if (= (count vals) 1) (first vals) vals)]
     {:dispatch-value (finalize-dispatch-value raw slice-name)
      :this-path this-path})))

(defn- standalone-dispatch-value
  "When a slice is applied without pending-slicing (profile-on-profile), derive a
   unique multi key from the slice body: prefer a single profile URL, then fixed/
   pattern values, else the slice name."
  [sub-elements slice-path slice-name]
  (let [deeper (filter #(> (count (:path %)) (count slice-path)) sub-elements)
        profiles (into [] (comp (map extract-profile-url) (filter some?) (distinct)) deeper)
        fixeds (into [] (comp (map extract-fixed-value) (filter some?)) deeper)]
    (finalize-dispatch-value
     (cond
       (= 1 (count profiles)) (first profiles)
       (seq profiles) profiles
       (= 1 (count fixeds)) (first fixeds)
       (seq fixeds) fixeds
       :else nil)
     slice-name)))

(defn- as-sequential-multi
  "If sch is :multi or [:sequential multi], return [sequential? multi-sch], else nil."
  [sch]
  (when sch
    (let [t (try (m/type sch) (catch Exception _ nil))]
      (cond
        (= t :multi) [false sch]
        (= t :sequential)
        (let [inner (first (m/children sch))
              it (when inner (try (m/type inner) (catch Exception _ nil)))]
          (when (= it :multi) [true inner]))
        :else nil))))

(defn- find-and-remove-base-form
  [form-vec base-k]
  (let [idx (some (fn [[i f]]
                    (when (and (seq? f)
                               (= 'mu/update (first f))
                               (= base-k (second f)))
                      i))
                  (map-indexed vector form-vec))]
    (if idx
      [(into (subvec form-vec 0 idx) (subvec form-vec (inc idx)))
       (nth form-vec idx)]
      [form-vec nil])))

(defn- extract-inner-element-form
  "Returns [inner-form sequential?] where sequential? indicates whether the
   base form was wrapped in [:sequential ...]."
  [base-form]
  (when base-form
    (let [inner-fn (when (and (seq? base-form) (= 'mu/update (first base-form)))
                     (nth base-form 2 nil))
          body (when (and (seq? inner-fn) (= 'fn (first inner-fn)))
                 (last inner-fn))]
      (if (and (vector? body) (= :sequential (first body)))
        [(second body) true]
        [body false]))))

(defn- flush-pending-slicing
  [acc]
  (if-let [pending (:pending-slicing acc)]
    (reduce-kv
     (fn [acc base-k {:keys [discriminators rules base-form base-sch field-is-sequential? slices]}]
       (if (empty? slices)
         acc
         (let [dispatch-form (make-dispatch-form discriminators base-sch slices)]
          (if (nil? dispatch-form)
           ;; No valid discriminator paths — skip :multi wrapping, just use base-form
           (if base-form
             (update acc :form conj base-form)
             acc)
           (let [[raw-base-form _form-sequential?] (extract-inner-element-form base-form)
               ;; Use field-is-sequential? (known at generation time) to emit the
               ;; correct unwrap form directly, avoiding a runtime type check.
               unwrap-form `(~'-> (~'mu/get ~'sch 0) (~'m/schema ~'options) ~'m/deref)
               inner-elem (if field-is-sequential? unwrap-form 'sch)
               fix-base (fn [f]
                          (cond
                            (nil? f) inner-elem
                            (= 'sch f) inner-elem
                            (and (seq? f) (= '-> (first f)) (= 'sch (second f)))
                            (let [steps (drop 2 f)
                                  steps (if (and (seq steps) (= 'm/deref (first steps)))
                                          (rest steps) steps)]
                              `(~'-> (~'mu/get ~'sch 0) (~'m/schema ~'options) ~'m/deref ~@steps))
                            ;; (-> SomeType/sch (m/schema options) m/deref ...) — replace base with inner-elem
                            (and (seq? f) (= '-> (first f)) (symbol? (second f))
                                 (some-> (second f) str (.contains "/")))
                            (let [steps (drop 2 f)
                                  ;; Skip (m/schema options) and any m/deref — inner-elem already has them
                                  steps (if (and (seq steps) (seq? (first steps)) (= 'm/schema (ffirst steps)))
                                          (rest steps) steps)
                                  steps (if (and (seq steps) (= 'm/deref (first steps)))
                                          (rest steps) steps)]
                              (if (seq steps)
                                `(~'-> ~@(rest unwrap-form) ~@steps)
                                inner-elem))
                            (and (seq? f) (= 'mu/update (first f))
                                 (or (and (= 'sch (second f)) (= 0 (nth f 2 nil)))
                                     (= 0 (second f))))
                            inner-elem
                            ;; (mu/get sch 0) — max=1 unwrap, redundant inside :multi
                            (and (seq? f) (= 'mu/get (first f))
                                 (or (and (= 'sch (second f)) (= 0 (nth f 2 nil)))
                                     (= 0 (second f))))
                            inner-elem
                            :else f))
               base-element-form (fix-base raw-base-form)
               entries (mapv
                        (fn [{:keys [dispatch-value slice-name form]}]
                          (let [unwrap-mu-update-0 (fn [f]
                                                     (when (and (seq? f) (= 'mu/update (first f)))
                                                       (cond
                                                         (and (= 'sch (second f)) (= 0 (nth f 2 nil)))
                                                         (let [inner-fn (nth f 3 nil)]
                                                           (when (and (seq? inner-fn) (= 'fn (first inner-fn)))
                                                             (last inner-fn)))
                                                         (= 0 (second f))
                                                         (let [inner-fn (nth f 2 nil)]
                                                           (when (and (seq? inner-fn) (= 'fn (first inner-fn)))
                                                             (last inner-fn))))))
                                fix-sch-ref (fn [f]
                                              (cond
                                                (and (seq? f) (= '-> (first f)) (= 'sch (second f)))
                                                (let [steps (drop 2 f)
                                                      steps (if (and (seq steps) (= 'm/deref (first steps)))
                                                              (rest steps) steps)]
                                                  `(~'-> (~'mu/get ~'sch 0) (~'m/schema ~'options) ~'m/deref ~@steps))
                                                ;; (-> Type/sch (m/schema options) m/deref ...) → replace with inner-elem + steps
                                                (and (seq? f) (= '-> (first f)) (symbol? (second f))
                                                     (some-> (second f) str (.contains "/")))
                                                (let [steps (drop 2 f)
                                                      steps (if (and (seq steps) (seq? (first steps)) (= 'm/schema (ffirst steps)))
                                                              (drop 2 steps) steps)]
                                                  (if (seq steps)
                                                    `(~'-> ~@(rest unwrap-form) ~@steps)
                                                    inner-elem))
                                                (and (seq? f) (= '-> (first f)) (seq? (second f)))
                                                (if-let [body (unwrap-mu-update-0 (second f))]
                                                  `(~'-> ~body ~@(drop 2 f))
                                                  f)
                                                (unwrap-mu-update-0 f) nil
                                                ;; (mu/get sch 0) — max=1 unwrap, drop inside :multi
                                                (and (seq? f) (= 'mu/get (first f))) nil
                                                :else f))
                                fixed-forms (into [] (keep fix-sch-ref) form)
                                constrained
                                (cond
                                  (and (= (count fixed-forms) 1) (seq? (first fixed-forms)) (= '-> (first (first fixed-forms))))
                                  (first fixed-forms)
                                  ;; A single non-seq form (e.g. [:ref kw]) replaces the schema directly —
                                  ;; threading it with -> would call the vector as a function, producing nil.
                                  (and (= (count fixed-forms) 1) (not (seq? (first fixed-forms))))
                                  (first fixed-forms)
                                  (seq fixed-forms)
                                  (if (and (seq? base-element-form) (= '-> (first base-element-form)))
                                    `(~'-> ~@(rest base-element-form) ~@fixed-forms)
                                    `(~'-> ~base-element-form ~@fixed-forms))
                                  :else base-element-form)
                                dv (finalize-dispatch-value dispatch-value slice-name)]
                            [dv constrained]))
                        slices)
               ;; A source profile can carry pairwise-identical slices whose
               ;; finalized dispatch values collide (RiskConcernAct declares
               ;; the same REFR entryRelationship twice under two slice
               ;; names); duplicate :multi keys fail schema compilation.
               ;; Keep the last arm per dispatch value, at the position of
               ;; its first occurrence.
               entries (reduce (fn [out [dv _ :as entry]]
                                 (let [idx (first (keep-indexed
                                                   (fn [i [dv2 _]] (when (= dv dv2) i))
                                                   out))]
                                   (if idx (assoc out idx entry) (conj out entry))))
                               []
                               entries)
               default-entry (when (not= rules "closed")
                               [:malli.core/default base-element-form])
               all-entries (cond-> entries default-entry (conj default-entry))
               multi-form `[:sequential
                            [:multi {:dispatch ~dispatch-form}
                             ~@all-entries]]
               update-form `(~'mu/update ~base-k (~'fn [~'sch] ~multi-form))]
           (-> acc (update :form conj update-form)))))))
     (dissoc acc :pending-slicing)
     pending)
    acc))

;; ---------------------------------------------------------------------------
;; compute-element-patch
;; ---------------------------------------------------------------------------

(defn- patch-with-sub-elements
  "Shared transduction for :map/:or and :ref/:lazy-ref patches.
   Flushes at completion so slicing discovered below this level is emitted here:
   callers only read :sch and :form off the returned acc, so an unflushed
   :pending-slicing would be discarded along with the slice arms it holds."
  [acc sub-elements main-path version]
  (transduce
   (map identity)
   (fn ([acc] (flush-pending-slicing acc))
     ([acc [_k items]]
      (element-definition->attribute acc main-path version items)))
   acc
   (group-sub-elements main-path sub-elements)))

(defn- prepend-base-sym
  "Prepend a base schema symbol to the form vector, threading into any existing -> form."
  [f base-sym]
  (if (and (seq f) (seq? (first f)) (= '-> (first (first f))))
    [`(~'-> ~base-sym ~@(rest (first f)))]
    (if (empty? f)
      [`(~'-> ~base-sym (~'m/schema ~'options) ~'m/deref)]
      [`(~'-> ~base-sym (~'m/schema ~'options) ~'m/deref ~@f)])))

(defn- splice-resource-type-form
  "Splice a mu/update-properties call into a form vector to set :resourceType,
   and the snapshot element order when one is known for this path."
  [f rt]
  (let [order   (get *element-order* rt)
        rt-form `(~'mu/update-properties
                  (~'fn [~'props]
                        (~'-> (~'or ~'props {:closed true})
                              (~'assoc :resourceType ~rt)
                              ~@(when (seq order)
                                  [`(~'assoc :fhir/element-order ~order)]))))]
    (if (seq f)
      (let [[tf ty opts & tail] (first f)]
        (if (= tf '->)
          [`(~tf ~ty ~opts ~rt-form ~@tail)]
          (conj (vec f) rt-form)))
      [rt-form])))

(defn- patch-element [acc _id attr-type _main-attr sub-elements main-path version]
  (let [old-sch (:sch acc)
        raw-code (:code attr-type)
        add-rt? (not= (count main-path) 1)
        rt (when add-rt? (str/join "." main-path))
        base-primitive (when raw-code (fhir-primitives raw-code))
        base-kw (when-not base-primitive
                  (if raw-code
                    (lookup-schema-kw raw-code version)
                    (keyword (str base-ns-prefix ".Element")
                             (str "v" (str/replace version "." "-")))))]
    (when (and (not base-primitive) (not old-sch) (nil? (resolve-malli-sch base-kw)))
      (println "NO BASE-SCH FOUND FOR:" base-kw "raw-code:" raw-code))
    (let [field-info (:field-info acc)
          ;; Use shape ref-kw for resolution when available, fall back to malli
          resolved-old-sch (if-let [ref-kw (:ref-kw field-info)]
                             (resolve-ref-kw ref-kw)
                             (let [resolved (resolve-sch-through-refs old-sch)]
                               (if (and (vector? resolved) (= :lazy-ref (first resolved)))
                                 (:form (get @*local-registry* (second resolved)))
                                 resolved)))
          ;; Use shape for map check when available, fall back to malli
          old-sch-map? (if field-info
                         (or (shape/complex? field-info) (shape/ref? field-info))
                         (and resolved-old-sch
                              (try (= :map (m/type (if (vector? resolved-old-sch)
                                                     resolved-old-sch
                                                     (m/schema resolved-old-sch external-registry))))
                                   (catch Exception _ false))))
          ;; The type the emitted form threads from at runtime. Prefer the ref
          ;; of the actual inherited schema: on a multi-typed field (the CDA ANY
          ;; choice) the compiled entry holds the LAST declared variant, while
          ;; the shape's :ref-kw recorded the FIRST, so the shape record is
          ;; stale exactly where this decision matters.
          inherited-kw (or (ref-kw-from-sch old-sch) (:ref-kw field-info))
          ;; When the differential declares a type that differs from the inherited
          ;; one, the declared type is authoritative: descend into it and emit a
          ;; base-fn thread instead of updating the inherited field schema.
          ;; Namespaces are compared instead of full keywords because the CDA
          ;; driver registers the same type under several version keywords
          ;; (aliases) that differ only in the keyword name; the namespace keeps
          ;; the package path + type name and drops only the version segment.
          declared-override? (and raw-code
                                  (not base-primitive)
                                  (not (#{"Element" "BackboneElement"} raw-code))
                                  (keyword? inherited-kw)
                                  base-kw
                                  (not= (namespace base-kw) (namespace inherited-kw))
                                  (some? (resolve-malli-sch base-kw)))
          effective-old-sch (if (or declared-override?
                                    (and resolved-old-sch (not old-sch-map?)))
                              nil
                              resolved-old-sch)]
      (transduce
       (map identity)
       (fn ([acc]
            (let [acc (flush-pending-slicing acc)
                  acc-val (if-not effective-old-sch
                            (if base-primitive
                              acc
                              (do
                                (swap! *references-atom* conj base-kw)
                                ;; :type-override? tells the caller the emitted
                                ;; base thread is authoritative and must not be
                                ;; re-threaded from the inherited field schema.
                                (cond-> (update acc :form #(prepend-base-sym % (kw->base-fn-form base-kw)))
                                  declared-override? (assoc :type-override? true))))
                            acc)]
              (if add-rt?
                (-> acc-val
                    (update :form splice-resource-type-form rt))
                acc)))
         ([acc [_k items]]
          (element-definition->attribute acc main-path version items)))
       (assoc acc :sch (cond
                         effective-old-sch
                         (cond-> effective-old-sch
                           add-rt? (mu/assoc :resourceType [:enum rt]))

                         base-primitive
                         (m/schema base-primitive external-registry)

                         :else
                         (cond-> (resolve-malli-sch base-kw)
                           add-rt? (mu/assoc :resourceType [:enum rt]))))
       (group-sub-elements main-path sub-elements)))))

(defn- dispatch-from-field-info
  "Derive compute-element-patch dispatch value from shape field-info when FHIR type code is nil."
  [field-info old-sch]
  (cond
    ;; The shape only describes the StructureDefinition's own root fields, so
    ;; anything more than one level down has no field-info. Introspect the
    ;; schema we are standing on instead — returning nil here drops every
    ;; sub-element below that level, which is how nested slicing went missing.
    (nil? field-info)
    (let [t (some-> old-sch m/type)]
      (cond
        (#{:map :or} t) t
        ;; Only follow refs that name another generated schema. A
        ;; contentReference ref carries a local-registry string ("#Foo.bar")
        ;; that resolve-malli-sch cannot resolve, and descending into it emits
        ;; mu/assoc against the ref itself.
        (and (#{:ref :lazy-ref} t)
             (keyword? (first (m/children old-sch))))
        t))

    (shape/complex? field-info) :map
    (shape/ref? field-info) :ref
    ;; Fall back to malli introspection for edge cases (:or, :vector, etc.)
    old-sch (some-> old-sch m/type)
    :else nil))

(defn compute-element-patch
  [{old-sch :sch :as acc} id {:keys [code] :as attr-type} main-attr sub-elements main-path version]
  (let [field-info (:field-info acc)
        dispatch (if code
                   code
                   (dispatch-from-field-info field-info old-sch))]
    (case dispatch
      (:map :or)
      (patch-with-sub-elements (update acc :sch unwrap-sequential) sub-elements main-path version)

      (:ref :lazy-ref)
      (let [ref-target (or (:ref-kw field-info)
                           (some-> old-sch m/children first))]
        (patch-with-sub-elements
         (assoc acc :sch (resolve-malli-sch ref-target))
         sub-elements main-path version))

      nil
      (if-some [contentReference (:contentReference main-attr)]
        (let [cr (if-let [idx (str/index-of contentReference "#")]
                   (subs contentReference idx)
                   contentReference)]
          (assoc acc :sch [:lazy-ref cr] :form [[:ref cr]]))
        acc)

      (:string :boolean :enum) acc

      ("Element" "BackboneElement")
      (patch-element acc id attr-type main-attr sub-elements main-path version)

      :vector
      (let [inner-sch (second (:sch acc))
            inner-acc (assoc acc :sch inner-sch)
            patched-acc (compute-element-patch inner-acc id attr-type main-attr sub-elements main-path version)]
        (assoc patched-acc :sch [:vector (:sch patched-acc)]))

      ;; default
      (let [has-deeper-sub? (some #(> (count (:path %)) (count main-path)) sub-elements)
            is-primitive? (boolean (when code (fhir-primitives code)))]
        (if (and has-deeper-sub? (not is-primitive?))
          (patch-element acc id attr-type main-attr sub-elements main-path version)
          (prim-or-ref acc attr-type version))))))

;; ---------------------------------------------------------------------------
;; attr->value-schema-patch
;; ---------------------------------------------------------------------------

(defn- new-field?
  "Is this acc for a new field (mu/assoc) vs an existing one (mu/update)?
   Prefers explicit :new-field? flag, falls back to :sch nil check."
  [acc]
  (if (contains? acc :new-field?)
    (:new-field? acc)
    (nil? (:sch acc))))

(defn- wrap-sequential-form
  "Shared logic for attr-max '*' and numeric max: wraps in sequential.
   New fields (mu/assoc): emit [:sequential ...] wrapping the value form.
   Existing fields (mu/update): emit mu/update-properties to set :max if needed."
  [acc new-acc max-val]
  (if (new-field? acc)
    ;; Nothing was computed for the element, so there is no child to wrap.
    ;; A bare [:sequential] is not a valid schema; leaving the form empty lets
    ;; create-new-child-schema skip the field instead of emitting one.
    (if (empty? (:form new-acc))
      new-acc
      ;; A repeating primitive admits nil. An occurrence carrying only
      ;; id/extensions has no value, and both wire formats hold its place with a
      ;; null in the value array while the id/extensions go to the same index of
      ;; the parallel _field array (Timing.event, Patient.name.given, ...).
      (let [child-forms (if (:primitive? new-acc)
                          [`[:maybe ~@(:form new-acc)]]
                          (:form new-acc))]
        (assoc new-acc :form (if max-val
                               [`[:sequential {:max ~max-val} ~@child-forms]]
                               [`[:sequential ~@child-forms]]))))
    (if max-val
      (assoc new-acc :form (conj (vec (:form new-acc))
                                 `(~'mu/update-properties ~'merge {:max ~max-val})))
      new-acc)))

(defn attr->value-schema-patch [acc id attr-type {attr-max :max :as main-attr} sub-elements main-path version]
  (let [fixed-k (first (filter #(str/starts-with? (name %) "fixed") (keys main-attr)))
        fixed-v (when fixed-k (get main-attr fixed-k))
        computed-acc (if fixed-v
                       {:sch [:enum {} fixed-v], :form [`[:enum {} ~fixed-v]], :fixed-enum? true}
                       (compute-element-patch acc id attr-type main-attr sub-elements main-path version))
        new-acc (if (*recursive-references* main-path)
                  (let [ref (str "#" (str/join "." main-path))
                        sch [:lazy-ref ref]]
                    (swap! *local-registry* assoc ref {:form (first (:form computed-acc))
                                                            :forms (vec (:form computed-acc))
                                                            :sch  (:sch computed-acc)})
                    {:sch sch, :form [sch]})
                  computed-acc)]
    (case attr-max
      "*"  (wrap-sequential-form acc new-acc nil)
      "1"  (if (and (not (new-field? acc)) (shape/seq-field? (:field-info acc)))
             ;; Existing sequential constrained to max=1: keep sequential, set :max 1
             (wrap-sequential-form acc new-acc 1)
             new-acc)
      "0"  nil
      nil  (if (and (new-field? acc) (:contentReference main-attr))
             ;; Content references with unset max inherit from base (typically "*").
             ;; Wrap in sequential so the array nature is preserved.
             (wrap-sequential-form acc new-acc nil)
             new-acc)
      ;; numeric max > 1
      (wrap-sequential-form acc new-acc (parse-long attr-max)))))

;; ---------------------------------------------------------------------------
;; Child schema update/create
;; ---------------------------------------------------------------------------

(defn- update-existing-child-schema [old-acc k attr-type main-attr props sub-elements main-path version sub-sch field-info]
  (let [is-seq? (or (shape/seq-field? field-info)
                    (and (nil? field-info) sub-sch
                         (try (= :sequential (m/type sub-sch)) (catch Exception _ false))))
        is-sliced? (shape/sliced? field-info)
        inner-sch (unwrap-sequential sub-sch)
        ;; unwrap-sequential hid the :maybe of a repeating primitive, so the
        ;; emitted mu/update needs an extra level to land on the primitive
        ;; instead of replacing the :maybe with it.
        maybe-inner? (and is-seq? sub-sch
                          (try (= :maybe (m/type (mu/get sub-sch 0)))
                               (catch Exception _ false)))
        ;; When inner schema is a FHIR primitive (e.g. :string for code),
        ;; sub-elements like extension belong on the _field companion,
        ;; not on the primitive value itself. Skip deep patching.
        inner-primitive? (and inner-sch
                              (try (some? (:fhir/primitive (m/properties inner-sch)))
                                   (catch Exception _ false))
                              (some #(> (count (:path %)) (count main-path)) sub-elements))]
    ;; When field is already sliced (:multi), or inner schema is a primitive
    ;; with sub-elements, skip sub-element patching.
    (if (or is-sliced? inner-primitive?)
      (if (seq props)
        (-> old-acc
            (update :form conj `(~'mu/update-entry-properties ~k merge ~props)))
        old-acc)
      (let [{_new-sub-sch :sch new-sub-form :form :as sub-acc}
            (attr->value-schema-patch {:sch inner-sch, :form [], :field-info field-info, :new-field? false}
                                      (:id main-attr) attr-type main-attr sub-elements main-path version)
            ;; ref-kw: prefer shape, fall back to malli introspection
            ref-kw (or (:ref-kw field-info)
                       (when inner-sch
                         (let [typ (try (m/type inner-sch) (catch Exception _ nil))]
                           (when (#{:ref :lazy-ref} typ)
                             (let [raw (first (m/children inner-sch))]
                               (when (keyword? raw) raw))))))
            ;; contentReference fields (like Questionnaire.item) need dereferencing
            ;; but shouldn't be tracked in *references-atom*
            needs-deref? (or ref-kw (shape/content-ref? field-info))]
    (if sub-acc
      (let [;; A declared-type override made the inherited ref irrelevant here;
            ;; the declared type's keyword was already recorded in patch-element.
            _ (when (and ref-kw (not (:type-override? sub-acc)))
                (swap! *references-atom* conj ref-kw))
            update-form-entry
            `(~'mu/update ~k (~'fn [~'sch]
                                    ~(let [target (if is-seq? 'inner-sch 'sch)
                                           update-expr (cond
                                                         ;; The differential declared a type that
                                                         ;; differs from the inherited one: the
                                                         ;; (-> (base-X) ...) thread replaces the
                                                         ;; inherited schema outright. Keep it
                                                         ;; verbatim (never re-thread from target)
                                                         ;; and flatten trailing forms onto it.
                                                         (:type-override? sub-acc)
                                                         (let [inner (first new-sub-form)]
                                                           (if (= 1 (count new-sub-form))
                                                             inner
                                                             `(~'-> ~@(rest inner) ~@(rest new-sub-form))))

                                                         (seq? (first new-sub-form))
                                                         (let [inner (first new-sub-form)
                                                               single-thread? (and (= (count new-sub-form) 1) (= '-> (first inner)))]
                                                           (if needs-deref?
                                                             (if single-thread?
                                                               (let [[_ _base & steps] inner]
                                                                 `(~'-> ~target (~'m/schema ~'options) ~'m/deref ~@steps))
                                                               `(~'-> ~target (~'m/schema ~'options) ~'m/deref ~@new-sub-form))
                                                             (if single-thread?
                                                               (let [[_ _base & steps] inner]
                                                                 `(~'-> ~target ~@steps))
                                                               `(~'-> ~target ~@new-sub-form))))
                                                         ;; Non-seq form (bare vector like [:or ...]) — use directly as replacement
                                                         :else
                                                         (first new-sub-form))]
                                       (cond
                                         maybe-inner?
                                         `(~'mu/update ~'sch 0
                                           (~'fn [~'maybe-sch]
                                            (~'mu/update ~'maybe-sch 0
                                             (~'fn [~'inner-sch] ~update-expr))))

                                         is-seq?
                                         `(~'mu/update ~'sch 0 (~'fn [~'inner-sch] ~update-expr))

                                         :else update-expr))))
            acc1 (if (seq new-sub-form)
                   (update old-acc :form conj update-form-entry)
                   old-acc)
            acc2 (if (and (seq props) (not (:fixed-enum? sub-acc)))
                   (update acc1 :form conj `(~'mu/update-entry-properties ~k merge ~props))
                   acc1)]
        acc2)
      (-> old-acc
          (update :form conj `(~'mu/dissoc ~k))
          (update :shape shape/dissoc-field k)))))))
(defn- create-new-child-schema [old-acc k attr-type main-attr props sub-elements main-path version]
  (if-some [{new-sub-sch :sch new-sub-form :form primitive? :primitive? fixed-enum? :fixed-enum?}
            (attr->value-schema-patch {:sch nil, :form [], :new-field? true}
                                      (:id main-attr) attr-type main-attr sub-elements main-path version)]
    (if (empty? new-sub-form)
      ;; No schema to add (e.g. nil type code constraining an existing field).
      ;; Only apply property changes if any.
      (if (not-empty props)
        (update old-acc :form conj `(~'mu/update-entry-properties ~k ~'merge ~props))
        old-acc)
      (do (assert (<= (count new-sub-form) 1))
          (let [element-kw (lookup-schema-kw "Element" version)
                value-form (first new-sub-form)
                ;; A repeating primitive needs a positionally-parallel companion:
                ;; _given[i] carries the id/extensions of given[i].
                repeating? (and (vector? value-form) (= :sequential (first value-form)))
                under-form (if repeating?
                             [:sequential [:ref element-kw]]
                             [:ref element-kw])]
            (swap! *references-atom* conj element-kw)
            (-> old-acc
                (update :form conj `(~'mu/assoc ~k ~value-form))
                (cond-> (and (not-empty props) (not fixed-enum?))
                  (update :form conj `(~'mu/update-entry-properties ~k ~'merge ~props)))
                (cond-> primitive?
                  (update :form conj
                          `(~'mu/assoc ~(underscore-attr k) ~under-form)
                          `(~'mu/optional-keys [~(underscore-attr k)])))
                (update :shape shape/assoc-field k
                        (let [ref-kw (when (and (vector? new-sub-sch)
                                                (= :lazy-ref (first new-sub-sch))
                                                (keyword? (second new-sub-sch)))
                                       (second new-sub-sch))
                              content-ref? (and (not ref-kw)
                                                (vector? new-sub-sch)
                                                (= :lazy-ref (first new-sub-sch))
                                                (string? (second new-sub-sch)))]
                          (cond-> (shape/field-info attr-type (:max main-attr) ref-kw)
                            content-ref? (assoc :content-ref? true))))))))
    old-acc))

;; ---------------------------------------------------------------------------
;; apply-element-patch
;; ---------------------------------------------------------------------------

(defn- extract-extension-value-type
  "For extension slices, extract the value[x] type code from sub-elements.
   Returns the type code (e.g. \"Coding\") if the slice has a simple value[x]
   with a complex (non-primitive) type, nil otherwise."
  [sub-elements main-path]
  (let [value-elem (first (filter (fn [{:keys [path]}]
                                    (and (> (count path) (count main-path))
                                         (let [last-seg (last path)]
                                           (and (string? last-seg)
                                                (str/starts-with? last-seg "value")))))
                                  sub-elements))
        code (when value-elem (-> value-elem :type first :code))]
    (when (and code (not (fhir-primitives code)))
      code)))

(defn- extract-extension-primitive-value-type
  "Like `extract-extension-value-type` but returns the value[x] type code when it IS a FHIR
   primitive (e.g. \"string\" for a `text` sub-extension). Lets an inline primitive value[x]
   slice collapse to a value-key'd primitive instead of retaining the full Extension (with all
   value[x] variants). Mirrors the complex-type collapse so primitive and complex slices are
   generated consistently."
  [sub-elements main-path]
  (let [value-elem (first (filter (fn [{:keys [path]}]
                                    (and (> (count path) (count main-path))
                                         (let [last-seg (last path)]
                                           (and (string? last-seg)
                                                (str/starts-with? last-seg "value")))))
                                  sub-elements))
        code (when value-elem (-> value-elem :type first :code))]
    (when (and code (fhir-primitives code))
      code)))

(defn- prepare-slice-context
  "Shared setup for both extension and non-extension slice processing."
  [_old-acc k attr-type main-attr sub-elements main-path version base-sch min-val props slice-name]
  (let [base-field-kw (let [kn (name k)]
                        (when-let [idx (str/index-of kn ":")]
                          (keyword (subs kn 0 idx))))
        effective-k (keyword (str/replace slice-name "[x]" "X"))
        url-val (or (first (keep :fixedUri sub-elements))
                    (first (:profile attr-type)))
        base-field-name (or base-field-kw k)
        is-extension? (= base-field-name :extension)
        opt? (or (not min-val) (zero? min-val))
        type-props (if is-extension?
                     (cond-> {:fhir/extension true :optional opt?}
                       url-val (assoc :url url-val)
                       (:code attr-type) (assoc :fhir/extension-value-type (:code attr-type)))
                     (cond-> {:fhir/slice-name slice-name :optional opt?}
                       url-val (assoc :url url-val)))
        ;; For extension slices with a complex value[x] type (e.g. Coding),
        ;; emit the bare value type ref instead of wrapping in Extension.
        bare-value-code (when is-extension?
                          (extract-extension-value-type sub-elements main-path))
        ;; An inline primitive value[x] slice (e.g. text -> valueString): collapse it the same
        ;; way as a complex slice instead of retaining the full Extension (all value[x] variants).
        prim-value-code (when (and is-extension? (not bare-value-code))
                          (extract-extension-primitive-value-type sub-elements main-path))
        ;; Compute value-key from the value[x] type code so the transformer knows which key to
        ;; extract from FHIR JSON extension entries (e.g. :valueCoding / :valueString). Set for
        ;; both complex and inline-primitive slices; profile-based primitive extensions get theirs
        ;; via profile-value-key detection below (which also wraps in sequential).
        value-key-kw (when-let [c (or bare-value-code prim-value-code)]
                       (keyword (str "value" (str/upper-case (subs c 0 1)) (subs c 1))))
        final-props (cond-> (merge props type-props)
                      value-key-kw (assoc :fhir/value-key value-key-kw))
        {new-sub-sch :sch new-sub-form :form}
        (cond
          bare-value-code
          ;; Emit bare value type: skip Extension wrapping, use the value type directly.
          ;; Wrap in [:sequential ...] when max > 1.
          (let [value-kw (lookup-schema-kw bare-value-code version)
                _ (swap! *references-atom* conj value-kw)
                attr-max (:max main-attr)
                max-val (when (and attr-max (not= attr-max "*") (not= attr-max "0"))
                          (parse-long attr-max))]
            ;; Always wrap in sequential for extension slices — the Clojure-native
            ;; representation uses vectors even for max=1.
            {:sch [:sequential [:lazy-ref value-kw]]
             :form [(if max-val
                      `[:sequential {:max ~max-val} [:ref ~value-kw]]
                      `[:sequential [:ref ~value-kw]])]})

          prim-value-code
          ;; Emit the primitive value schema directly (value-key'd), dropping the Extension
          ;; wrapper and its unused value[x] siblings. Sequential to match the slice convention.
          (let [prim-sch (get fhir-primitives prim-value-code)
                attr-max (:max main-attr)
                max-val (when (and attr-max (not= attr-max "*") (not= attr-max "0"))
                          (parse-long attr-max))]
            {:sch (if max-val [:sequential {:max max-val} prim-sch] [:sequential prim-sch])
             :form [(if max-val
                      `[:sequential {:max ~max-val} ~prim-sch]
                      `[:sequential ~prim-sch])]})

          :else
          ;; Filter sub-elements to only those belonging to this slice.
          ;; Without this filter, sub-elements from all slices (e.g. both
          ;; component:systolic and component:diastolic) are mixed together,
          ;; and per-slice constraints are lost.
          (let [slice-prefix main-path
                slice-sub-elements (into [] (filter (fn [{:keys [path]}]
                                                      (let [pc (count path)
                                                            sc (count slice-prefix)]
                                                        (or (= path slice-prefix)
                                                            (and (> pc sc)
                                                                 (= (subvec path 0 sc) slice-prefix))))))
                                         sub-elements)
                has-deeper? (some #(> (count (:path %)) (count main-path)) slice-sub-elements)
                ;; When the type code is nil (inherited from base), attr->value-schema-patch
                ;; returns acc unchanged because compute-element-patch's nil dispatch is a no-op.
                ;; For slices with deeper sub-elements, use patch-with-sub-elements directly
                ;; to apply the sub-element constraints on the base schema.
                inner-sch (unwrap-sequential base-sch)
                result (if (and (nil? (:code attr-type)) has-deeper? inner-sch)
                         (patch-with-sub-elements {:sch inner-sch, :form [], :new-field? false}
                                                  slice-sub-elements main-path version)
                         (attr->value-schema-patch {:sch inner-sch, :form [], :new-field? false}
                                                   (:id main-attr) attr-type main-attr slice-sub-elements main-path version))]
            result))
        new-sch (or new-sub-sch [:any])
        ;; Resolve profile keyword for override-form and primitive extension detection
        profile-kw (when-let [profile (first (:profile attr-type))]
                     ;; The canonical index resolves by URL identity, so it picks the
                     ;; package that actually defines the profile and the version that
                     ;; package publishes. The by-name scan below matches on the last
                     ;; namespace segment only and picks an arbitrary hash-order entry
                     ;; when several versions are in the atom; it stays as the fallback
                     ;; for drivers that supply no index (e.g. the CDA generator).
                     (or (resolve-canonical-kw profile)
                         (let [profile-clean (strip-canonical-version profile)
                               profile-name (munge-ns (str/replace (last (str/split profile-clean #"/")) "." "-"))]
                           (or (first (filter #(= (kw->type-name %) profile-name)
                                              (keys @*schema-atom*)))
                               (uri->kw2 profile version)))))
        ;; Last resort. With an index bound this only fires for a canonical no
        ;; package in the run defines (the run was planned with :skip-missing).
        ;; Emitting the unresolvable keyword would make the file fail to load, so
        ;; the slice degrades to the element's own type: it keeps its :url
        ;; discriminator but loses the profile's narrowing.
        profile-kw (when profile-kw
                     (if (or (contains? @*schema-atom* profile-kw)
                             (contains? *known-canonical-kws* profile-kw)
                             (some? (resolve-malli-sch profile-kw)))
                       profile-kw
                       (let [degraded (when-let [code (:code attr-type)]
                                        (lookup-schema-kw code version))]
                         (when *unresolved-profiles*
                           (swap! *unresolved-profiles* conj
                                  {:profile (first (:profile attr-type))
                                   :from *current-definition*
                                   :degraded-to degraded}))
                         degraded)))
        ;; For profile-based extension slices (no value[x] in sub-elements),
        ;; check the resolved schema for :fhir/primitive-extension to derive value-key.
        profile-value-key (when (and is-extension? profile-kw (not value-key-kw))
                            (let [entry (get @*schema-atom* profile-kw)
                                  resolved-sch (:sch entry)]
                              (when resolved-sch
                                (let [sch-props (try (m/properties resolved-sch) (catch Exception _ nil))]
                                  (when (:fhir/primitive-extension sch-props)
                                    (let [prim-code (:fhir/primitive sch-props)]
                                      (when prim-code
                                        (keyword (str "value" (str/upper-case (subs prim-code 0 1))
                                                     (subs prim-code 1))))))))))
        final-props (if profile-value-key
                      (assoc final-props :fhir/value-key profile-value-key)
                      final-props)
        override-form (when profile-kw
                        (swap! *references-atom* conj profile-kw)
                        (let [is-sequential? (or profile-value-key
                                                 (if (vector? new-sub-sch)
                                                   (= :sequential (first new-sub-sch))
                                                   (= :sequential (try (m/type new-sub-sch) (catch Exception _ nil)))))]
                          (if is-sequential?
                            `[:sequential [:ref ~profile-kw]]
                            `[:ref ~profile-kw])))
        update-fn-form `(~'fn [~'existing]
                              ~(if override-form
                                 override-form
                                 (if (and (seq new-sub-form) (seq? (first new-sub-form)))
                                   (let [inner (first new-sub-form)]
                                     (if (and (= (count new-sub-form) 1) (= '-> (first inner)))
                                       inner
                                       `(~'-> ~'existing ~@new-sub-form)))
                                   (first new-sub-form))))
        ;; For extension slices, extract the assoc value from the update-fn body
        ;; when it's a -> thread that constructs the value from scratch.
        ;; When override-form is present, always use assoc since the field may
        ;; not exist on the base schema (e.g. Element base for complex extensions).
        ;; For multi-form extension slices (complex extensions with sub-extensions),
        ;; build a threading form from the base Extension schema.
        base-ext-ref-kw (when (and is-extension? (not override-form))
                          (let [inner-sch (unwrap-sequential base-sch)]
                            (when inner-sch
                              (let [t (try (m/type inner-sch) (catch Exception _ nil))]
                                (when (#{:ref :lazy-ref} t)
                                  (let [raw (first (m/children inner-sch))]
                                    (when (keyword? raw) raw)))))))
        assoc-form (cond
                     ;; Bare value extension (complex): use the value ref directly
                     bare-value-code
                     (first new-sub-form)
                     ;; Profile override: use the override form
                     (and is-extension? override-form)
                     override-form
                     ;; Thread-based extension: extract assoc value from threading form
                     (and is-extension? (seq new-sub-form) (seq? (first new-sub-form)))
                     (let [inner (first new-sub-form)]
                       (if (and (= (count new-sub-form) 1) (= '-> (first inner)))
                         inner
                         ;; Multi-form case: wrap sub-forms in a thread starting from
                         ;; the base Extension schema (resolved via base-fn or ref)
                         (when base-ext-ref-kw
                           (let [base-fn (kw->base-fn-form base-ext-ref-kw)]
                             `(~'-> ~base-fn (~'m/schema ~'options) ~'m/deref ~@new-sub-form))))))]
    {:effective-k effective-k
     :base-field-name base-field-name
     :is-extension? is-extension?
     :final-props final-props
     :new-sub-sch new-sub-sch
     :new-sub-form new-sub-form
     :new-sch new-sch
     :override-form override-form
     :assoc-form assoc-form
     :update-fn-form update-fn-form}))

(defn- apply-keyed-update
  "Apply a slice update to a key: emit form + update sch. Shared by extension slices and fallback.
   Uses mu/assoc when assoc-form or override-form is present, mu/update otherwise."
  [old-acc effective-k update-fn-form _new-sch final-props assoc-form]
  (-> old-acc
      (update :form conj (if assoc-form
                           `(~'mu/assoc ~effective-k ~assoc-form)
                           `(~'mu/update ~effective-k ~update-fn-form)))
      (cond-> (not-empty final-props)
        (update :form conj `(~'mu/update-entry-properties ~effective-k ~'merge ~final-props)))
      (update :shape shape/assoc-field effective-k
              {:type "Extension" :fhir/extension true})))
(defn- apply-extension-slice
  "Extension slices: assoc directly on the parent map as sibling keys."
  [old-acc {:keys [effective-k final-props new-sch update-fn-form override-form assoc-form]}]
  (apply-keyed-update old-acc effective-k update-fn-form new-sch final-props (or override-form assoc-form)))

(defn- slice-arm-form
  "Build the constrained arm schema form from prepare-slice-context output."
  [new-sub-form]
  (let [slice-forms (if (and (= (count new-sub-form) 1)
                             (seq? (first new-sub-form))
                             (= '-> (first (first new-sub-form))))
                      (let [[_ _base & steps] (first new-sub-form)] steps)
                      new-sub-form)
        is-replacement? (and (= (count slice-forms) 1) (not (seq? (first slice-forms))))]
    {:slice-forms slice-forms
     :is-replacement? is-replacement?
     :arm-form (cond
                 is-replacement? (first slice-forms)
                 (seq slice-forms) `(~'-> ~'base-arm ~@slice-forms)
                 :else 'base-arm)}))

(defn- merge-slice-into-multi-form
  "Emit form code that adds or replaces one multi arm on an already-sliced field.
   Reconstructs the multi from children (mu/assoc treats vector dispatch keys as paths).
   If the live field is not yet a multi (shape marked sliced but sch is still the
   element map), wraps it as open multi with a default arm first.

   Note: do not use `->` to thread into an `(fn …)` form — macroexpansion becomes
   `(fn x [inner] …)` which is a syntax error."
  [base-k dispatch-value arm-form is-sequential?]
  (let [ensure-body
        `(~'let [~'inner (~'-> ~'inner (~'m/schema ~'options) ~'m/deref)
                 ~'multi (~'if (= :multi (~'m/type ~'inner))
                           ~'inner
                           (~'m/schema [:multi {:closed false}
                                        [:malli.core/default ~'inner]]
                                       ~'options))
                 ~'props (~'or (~'m/properties ~'multi) {})
                 ~'kids (~'vec (~'m/children ~'multi))
                 ;; m/children of :multi are [dispatch-key props schema] triples;
                 ;; the schema is the third element. Reading the second returned
                 ;; the (usually nil) props, so the base arm silently fell back
                 ;; to the bare [:map {:closed false}] vector, which carries no
                 ;; registry and cannot host [:ref ...] constraints.
                 ~'base-arm (~'or (~'some (~'fn [~'c]
                                            (~'when (= ~dispatch-value (~'first ~'c))
                                              (~'nth ~'c 2)))
                                          ~'kids)
                                  (~'some (~'fn [~'c]
                                            (~'when (= :malli.core/default (~'first ~'c))
                                              (~'nth ~'c 2)))
                                          ~'kids)
                                  [:map {:closed false}])
                 ~'arm ~arm-form
                 ~'idx (~'first (~'keep-indexed (~'fn [~'i ~'c]
                                                  (~'when (= ~dispatch-value (~'first ~'c)) ~'i))
                                                ~'kids))
                 ~'kids' (~'if ~'idx
                           (~'assoc ~'kids ~'idx [~dispatch-value ~'arm])
                           (~'conj ~'kids [~dispatch-value ~'arm]))]
           (~'m/schema (~'into [:multi ~'props] ~'kids') ~'options))]
    (if is-sequential?
      `(~'mu/update ~base-k
                    (~'fn [~'sch]
                      (~'let [~'inner (~'mu/get ~'sch 0)]
                        [:sequential ~ensure-body])))
      `(~'mu/update ~base-k
                    (~'fn [~'inner] ~ensure-body)))))

(defn- apply-non-extension-slice
  "Non-extension slices: collect into :pending-slicing, merge into an existing multi,
   or fall back to direct threading on the element schema."
  [old-acc base-sch sub-elements main-path slice-name
   {:keys [base-field-name effective-k final-props new-sub-sch new-sub-form new-sch update-fn-form]}]
  (let [base-k base-field-name]
    (if-let [pending (get-in old-acc [:pending-slicing base-k])]
      (let [{:keys [dispatch-value this-path]}
            (extract-dispatch-value (:discriminators pending) sub-elements main-path slice-name)]
        (update-in old-acc [:pending-slicing base-k :slices] conj
                   {:slice-name slice-name
                    :dispatch-value dispatch-value
                    :this-path this-path
                    :sch new-sub-sch
                    :form new-sub-form}))
      ;; No pending-slicing: if the base field is already a multi (or parent shape
      ;; marked it sliced — profile-on-profile), add/replace one arm. Otherwise
      ;; fall back to direct threading on the element schema.
      ;; Only merge when base field is already a multi. shape/sliced? alone is not
      ;; enough: flush can mark sliced yet skip multi when dispatch is nil.
      (let [seq-multi (as-sequential-multi base-sch)
            is-base-seq? (boolean (or (when seq-multi (first seq-multi))
                                      (some-> base-sch m/type (= :sequential))))
            {:keys [slice-forms is-replacement?]} (slice-arm-form new-sub-form)]
        (cond
          seq-multi
          (let [dv (standalone-dispatch-value sub-elements main-path slice-name)
                final-arm (if is-replacement?
                            (first slice-forms)
                            (if (seq slice-forms)
                              `(~'-> ~'base-arm ~@slice-forms)
                              'base-arm))]
            (-> old-acc
                (update :form conj (merge-slice-into-multi-form base-k dv final-arm (first seq-multi)))
                (update :shape shape/mark-sliced base-k)))

          :else
          (let [base-ref-kw (when base-sch
                              (let [t (try (m/type base-sch) (catch Exception _ nil))]
                                (when (#{:ref :lazy-ref} t)
                                  (let [raw (first (m/children base-sch))]
                                    (when (keyword? raw) raw)))))]
            (when base-ref-kw
              (swap! *references-atom* conj base-ref-kw))
            (if (seq slice-forms)
              (-> old-acc
                  (update :form conj
                          (if is-replacement?
                            `(~'mu/update ~base-k (~'fn [~'sch] ~(first slice-forms)))
                            (if is-base-seq?
                              `(~'mu/update ~base-k
                                            (~'fn [~'sch]
                                                  (~'mu/update ~'sch 0
                                                               (~'fn [~'inner]
                                                                     (~'-> ~'inner ~@slice-forms)))))
                              (if base-ref-kw
                                `(~'mu/update ~base-k
                                              (~'fn [~'sch]
                                                    (~'-> ~'sch
                                                          (~'m/schema ~'options)
                                                          ~'m/deref
                                                          ~@slice-forms)))
                                `(~'mu/update ~base-k
                                              (~'fn [~'sch]
                                                    (~'-> ~'sch ~@slice-forms))))))))
              ;; No slice forms and no multi host: skip
              old-acc)))))))

(defn- apply-regular-element
  "Non-slice element: update or create, then capture slicing info if present."
  [old-acc effective-k attr-type main-attr props sub-elements main-path version sub-sch field-info]
  (let [res (if (or field-info sub-sch)
              (update-existing-child-schema old-acc effective-k attr-type main-attr props sub-elements main-path version sub-sch field-info)
              (create-new-child-schema old-acc effective-k attr-type main-attr props sub-elements main-path version))
        slicing (:slicing main-attr)
        has-resolve? (some #(and (:path %) (str/includes? (:path %) "resolve()"))
                           (:discriminator slicing))]
    (if (and slicing (not has-resolve?) (not (#{:extension :modifierExtension} effective-k)))
        (let [raw-field-sch (mu/get (:sch res) effective-k)
              base-sch-for-slicing (unwrap-sequential raw-field-sch)
              field-is-sequential? (or (some-> raw-field-sch m/type (= :sequential))
                                       (shape/seq-field? field-info))
              [cleaned-form captured-base-form] (find-and-remove-base-form (vec (:form res)) effective-k)]
        (-> res
            (assoc :form cleaned-form)
            (assoc-in [:pending-slicing effective-k]
                      {:discriminators (:discriminator slicing)
                       :rules (or (:rules slicing) "open")
                       :field-is-sequential? field-is-sequential?
                       :base-form captured-base-form
                       :base-sch base-sch-for-slicing
                       :slices []})
            (update :shape shape/mark-sliced effective-k)))
      res)))

(defn apply-element-patch [{:keys [sch shape] :as old-acc} _id k attr-type {attr-min :min slice-name :sliceName :as main-attr} sub-elements main-path version]
  (let [effective-k (if slice-name (keyword (str/replace slice-name "[x]" "X")) k)
        base-field-kw (when slice-name
                        (let [kn (name k)]
                          (when-let [idx (str/index-of kn ":")]
                            (keyword (subs kn 0 idx)))))
        ;; Use shape for field existence check, fall back to mu/get for the actual schema
        field-info (or (shape/get-field shape effective-k)
                       (shape/get-field shape k)
                       (when base-field-kw (shape/get-field shape base-field-kw)))
        sub-sch (mu/get sch effective-k)
        base-sch (or sub-sch (mu/get sch k) (when base-field-kw (mu/get sch base-field-kw)))
        min-val (when attr-min (parse-long (str attr-min)))
        ;; Choice type variants (medication[x] → medicationCodeableConcept, medicationReference)
        ;; are individually optional — the min constraint applies to the group, not each variant
        choice-type? (and _id (str/includes? (str _id) "[x]"))
        props (cond-> (merge (select-keys main-attr [:isSummary :short :definition :comment :binding])
                             (representation-props main-attr))
                choice-type? (assoc :optional true)
                (and (not choice-type?) min-val (or field-info (zero? min-val))) (assoc :optional (zero? min-val)))]
    (if slice-name
      (let [ctx (prepare-slice-context old-acc k attr-type main-attr sub-elements main-path version base-sch min-val props slice-name)]
        (if (:is-extension? ctx)
          (apply-extension-slice old-acc ctx)
          (apply-non-extension-slice old-acc base-sch sub-elements main-path slice-name ctx)))
      (apply-regular-element old-acc effective-k attr-type main-attr props sub-elements main-path version sub-sch field-info))))

;; ---------------------------------------------------------------------------
;; Extension classification
;; ---------------------------------------------------------------------------

(defn simple-extension? [{:keys [type differential url]}]
  (and (= type "Extension")
       (not= url "http://hl7.org/fhir/StructureDefinition/Extension")
       (let [elements (:element differential)
             value-element (first (filter #(-> % :path (= "Extension.value[x]")) elements))
             complex-extensions (filter #(and (-> % :path (= "Extension.extension"))
                                              (not= "0" (str (:max %))))
                                        elements)]
         (and value-element
              (not= "0" (str (:max value-element)))
              (empty? complex-extensions)))))

;; ---------------------------------------------------------------------------
;; element-definition->attribute
;; ---------------------------------------------------------------------------

(defn element-definition->attribute [acc parent-path version items]
  (let [parent-path-count (count parent-path)
        attrs (into [] (filter (fn [{:keys [path]}]
                                 (= (- (count path) parent-path-count) 1)))
                    items)
        ;; A differential may constrain a grandchild without restating the level
        ;; in between (ReferralNote gives ClinicalDocument.component.structuredBody
        ;; but never ClinicalDocument.component). Synthesize the missing level from
        ;; the shared prefix, otherwise the whole subtree is dropped.
        attrs (if (seq attrs)
                attrs
                (when-let [p (some #(let [p (:path %)]
                                      (when (> (count p) parent-path-count) p))
                                   items)]
                  [{:path (into [] (take (inc parent-path-count)) p)}]))]
    (if (empty? attrs)
      acc
      (let [{attr-types :type
             main-path  :path
             :as        main-attr} (apply merge attrs)
            sub-elements (into [] (filter (fn [{:keys [path]}]
                                            (>= (count path) parent-path-count)))
                               items)
            id (nth main-path parent-path-count)
            expand? (str/includes? id "[x]")]
        (reduce
         (fn [acc {:keys [code] :as attr-type}]
           (if (or (not expand?) code)
             (apply-element-patch acc id
                                  (if expand?
                                    (keyword (str/replace id "[x]"
                                                          (str (str/upper-case (subs code 0 1)) (subs code 1))))
                                    (keyword id))
                                  attr-type main-attr sub-elements main-path version)
             acc))
         acc
         (if (nil? attr-types)
           (if (:contentReference main-attr)
             [nil]
             [{:code nil}])
           attr-types))))))

;; ---------------------------------------------------------------------------
;; Structure definition properties
;; ---------------------------------------------------------------------------

(defn- choice-variant-kw
  "value[x] + type code -> :valueQuantity, matching element-definition->attribute."
  [leaf code]
  (keyword (str/replace leaf "[x]" (str (str/upper-case (subs code 0 1)) (subs code 1)))))

(defn snapshot->element-order
  "Map each complex path in an SD snapshot to its child element keys in document
   order. Choice elements expand to one key per declared type; slices collapse
   onto the base element name, because XML has no slice concept and every slice
   of an element serializes under that element's name."
  [snapshot-elements]
  (reduce
   (fn [acc {:keys [path type]}]
     (let [segs (str/split path #"\.")]
       (if (< (count segs) 2)
         acc
         (let [parent (str/join "." (butlast segs))
               leaf   (last segs)
               ks     (if (str/includes? leaf "[x]")
                        (into [] (comp (keep :code) (map #(choice-variant-kw leaf %))) type)
                        [(keyword leaf)])]
           (update acc parent (fn [prev] (into (or prev []) (remove (set prev)) ks)))))))
   {}
   snapshot-elements))

(defn- build-sd-properties
  "Build the properties map for a StructureDefinition. For a complex extension
   (type \"Extension\") the canonical :url and :fhir/value-key :complex are stamped so
   the schema carries enough to fold/unfold the extension without an external
   url->value-type registry (simple extensions get :fhir/value-key :valueX instead).
   Non-extension StructureDefinitions (profiles, resources) are unaffected."
  [{:keys [description title url] t :type} base-element]
  (let [extension? (= t "Extension")
        order      (get *element-order* t)]
    (-> {:closed true}
        (cond->
          t           (assoc :resourceType t)
          (seq order) (assoc :fhir/element-order order)
          description (assoc :fhir.structure-definition/description description)
          title       (assoc :fhir.structure-definition/title title)
          (and extension? url) (assoc :url url)
          extension?  (assoc :fhir/value-key :complex))
        (cond->
          (:definition base-element) (assoc :fhir/definition (:definition base-element))
          (:short base-element) (assoc :fhir/short (:short base-element))))))

(defn structure-definition->patch [{:keys [version]
                                    {:keys [element]} :differential t :type
                                    :as x}]
  (let [base-element (first (filter #(-> % :id (= t)) element))
        ;; In complex extensions, the :extension field should be optional because
        ;; named slices are promoted to map keys — the unsliced :extension array
        ;; only holds remainders.
        force-extension-optional? (= t "Extension")]
    (fn [acc]
      (let [sd-props (build-sd-properties x base-element)
            props-handled (-> acc
                              (update :form conj `(~'mu/update-properties ~'merge ~sd-props)))]
        ;; Group elements by their first 2 path segments, then sort so base
        ;; fields (no `:` in key) come before their slices (with `:`). This is
        ;; needed because xforms/by-key can emit groups in hash-map order.
        (let [groups (into []
                          (comp
                           (remove #(-> % :id (= t)))
                           (map (fn [x]
                                  (assoc x :path (into [] (str/split (or (:id x) (:path x)) #"\.")))))
                           (map (fn [x]
                                  (if (and force-extension-optional?
                                           (= (:path x) [t "extension"])
                                           (not (:sliceName x)))
                                    (assoc x :min 0)
                                    x)))
                           (xforms/by-key (fn [{:keys [path]}]
                                            (into [] (take 2) path))
                                          (xforms/into [])))
                          element)
              sorted (sort-by (fn [[k _]]
                                (let [seg (second k)
                                      base (when seg
                                             (let [idx (str/index-of seg ":")]
                                               (if idx (subs seg 0 idx) seg)))
                                      has-colon? (and seg (str/includes? seg ":"))]
                                  [base (if has-colon? 1 0)]))
                              groups)]
          (flush-pending-slicing
           (reduce (fn [acc [_k items]]
                     (element-definition->attribute acc [t] version items))
                   props-handled
                   sorted)))))))

(defn sch-form [acc]
  (assoc acc :form `(~'-> (~'m/schema :map ~'options) ~@(:form acc))))

(defn- add-local-registry-refs-to-acc
  "Assoc local-registry entries onto acc, adding source-kws to :references."
  [acc entries]
  (let [ref-kws (into #{}
                      (keep (fn [[_ {:keys [type source-kw source-ref]}]]
                              (cond
                                (= :ref type) source-kw
                                ;; :own entries that chain from a base def need the
                                ;; base's namespace in :references for :require generation.
                                source-ref source-ref)))
                      entries)]
    (-> acc
        (assoc :local-registry entries)
        (update :references into ref-kws))))

(defn- lr-key->def-name
  "Convert a local-registry key like \"#Bundle.link\" to a def name like \"Bundle-link\"."
  [lr-key]
  (str/replace lr-key #"[#.]" {"#" "" "." "-"}))

(defn wrap-local-registry
  ([acc] (wrap-local-registry acc nil))
  ([acc base-kw]
   (if-some [local-registry (not-empty @*local-registry*)]
     (let [base-lr (:local-registry acc)
           entries (into {}
                        (map (fn [[k {:keys [form forms sch]}]]
                               (let [into-schema-form (make-into-schema-form form)
                                     base-entry (get base-lr k)]
                                 (if-let [source-kw
                                          (when base-entry
                                            (let [src (case (:type base-entry)
                                                        :own base-kw
                                                        :ref (:source-kw base-entry))
                                                  src-lr (:local-registry (get @*schema-atom* src))
                                                  src-form (:form (get src-lr k))]
                                              (when (= into-schema-form src-form)
                                                src)))]
                                   [k {:type :ref :source-kw source-kw}]
                                   ;; When this entry differs from its base and a base entry exists,
                                   ;; wrap the patch forms in a pipeline that chains from the base's def.
                                   (if (and base-entry (seq (or forms [form])))
                                     (let [src (case (:type base-entry)
                                                 :own base-kw
                                                 :ref (:source-kw base-entry))
                                           base-def-sym (symbol (str (kw->ns-sym src))
                                                                (lr-key->def-name k))
                                           all-forms (or forms [form])
                                           wrapped `(~'-> (~'m/schema ~base-def-sym ~'options)
                                                          ~'m/deref
                                                          ~@all-forms)]
                                       [k {:type :own :form (make-into-schema-form wrapped) :sch sch
                                           :source-ref src}])
                                     [k {:type :own :form into-schema-form :sch sch}])))))
                        local-registry)]
       (add-local-registry-refs-to-acc acc entries))
     ;; No new local-registry entries from processing, but the acc may have
     ;; inherited entries from the base. Convert :own entries to :ref.
     (if (and base-kw (:local-registry acc))
       (let [refs (into {}
                        (map (fn [[k entry]]
                               [k (case (:type entry)
                                    :ref entry
                                    :own {:type :ref :source-kw base-kw})]))
                        (:local-registry acc))]
         (add-local-registry-refs-to-acc acc refs))
       acc))))

;; ---------------------------------------------------------------------------
;; Simple extension patch
;; ---------------------------------------------------------------------------

(defn- simple-extension->patch [{:keys [name title description url]
                                 {:keys [element]} :differential}]
  (let [value-element (first (filter #(-> % :path (= "Extension.value[x]")) element))
        attr-types (:type value-element)
        attr-type (if (seq attr-types) (first attr-types) {:code "Element"})
        id (:id value-element)
        main-path (into [] (str/split (or id (:path value-element)) #"\."))
        ;; Value key (:valueString / :valueReference / ...) derived from the single
        ;; value[x] type code, so the extension schema carries enough to fold/unfold a
        ;; FHIR extension entry without an external url->value-type registry. Only set
        ;; when the value[x] has a concrete type (skip the {:code "Element"} fallback).
        value-key (when-let [c (and (seq attr-types) (:code attr-type))]
                    (keyword (str "value" (str/upper-case (subs c 0 1)) (subs c 1))))]
    (fn [_acc]
      (let [{new-sub-sch :sch new-sub-form :form primitive? :primitive?}
            (attr->value-schema-patch {:sch nil, :form [], :new-field? true}
                                      id attr-type value-element [] main-path nil)

            props (cond-> {:closed true
                           :resourceType name}
                    description (assoc :fhir.structure-definition/description description)
                    title (assoc :fhir.structure-definition/title title)
                    url (assoc :url url)
                    value-key (assoc :fhir/value-key value-key)
                    (:definition value-element) (assoc :fhir/definition (:definition value-element))
                    (:short value-element) (assoc :fhir/short (:short value-element))
                    primitive? (assoc :fhir/primitive-extension true)
                    (:binding value-element) (assoc :binding (:binding value-element)))

            final-sch (-> (or new-sub-sch [:any])
                          (m/schema external-registry)
                          (mu/update-properties (fn [p] (merge p props))))]

        {:sch final-sch
         :form [`(~'-> ~(first new-sub-form) (~'m/schema ~'options) (~'mu/update-properties ~'merge ~props))]}))))

;; ---------------------------------------------------------------------------
;; structure-definition->schema
;; ---------------------------------------------------------------------------

(defn structure-definition->schema [schema-atom]
  (fn [{:keys [url version fhirVersion kind name type]
        {:keys [element]} :differential baseDefinition :baseDefinition
        :as x}]
    (let [kw (uri->kw2 url version)
          basekw (when baseDefinition
                   (let [base-def (strip-canonical-version baseDefinition)
                         base-url ^URL (io/as-url base-def)
                         path (into [] (remove empty?) (str/split (.getPath base-url) #"\/"))
                         m-name (munge-ns (str/replace (last path) "." "-"))]
                     (or (first (filter #(= (kw->type-name %) m-name) (keys @schema-atom)))
                         (resolve-canonical-kw baseDefinition)
                         (uri->kw2 base-def (or fhirVersion version)))))
          ;; Complex extensions should derive from Element (id + extension only)
          ;; instead of Extension (which carries all value[x] variants)
          basekw (if (and basekw
                          (= type "Extension")
                          (not (simple-extension? x))
                          (not= url "http://hl7.org/fhir/StructureDefinition/Extension"))
                   (lookup-schema-kw "Element" (or fhirVersion version))
                   basekw)
          recursive-references (into #{}
                                     (comp (keep :contentReference)
                                           (map (fn [cr]
                                                  (let [idx (str/index-of cr "#")]
                                                    (if idx (subs cr (inc idx)) (subs cr 1)))))
                                           (map #(into [] (str/split % #"\."))))
                                     element)
          local-registry (atom {})
          is-simple? (simple-extension? x)
          ;; Ask the index about the *canonical*, not about the keyword the scan
          ;; happened to choose. Those differ whenever a driver registers aliases:
          ;; the scan can return an alias keyword the index has never heard of,
          ;; and testing that keyword would skip the check exactly where it is
          ;; needed. The canonical is what says whether this run defines the base.
          _ (when (and *defer-unready-base*
                       basekw (not is-simple?)
                       (contains? *canonical-index* (strip-canonical-version baseDefinition))
                       (nil? (resolve-malli-sch basekw)))
              (throw (ex-info (str "base schema not generated yet: " basekw)
                              {:type ::base-not-ready :definition url :base basekw})))]
      (swap! schema-atom assoc kw
             (binding [*references-atom* (atom #{})
                       *local-registry* local-registry
                       *recursive-references* recursive-references
                       *base-refs* (atom {})
                       *element-order* (snapshot->element-order
                                        (get-in x [:snapshot :element]))]
               (if (and basekw (not is-simple?))
                 (do
                   (swap! *references-atom* conj basekw)
                   (let [patched (-> schema-atom deref
                                     (get basekw)
                                     (assoc :sch (resolve-malli-sch basekw))
                                     (assoc :form [])
                                     (update :shape #(or % {}))
                                     ((structure-definition->patch x))
                                     (update :form (fn [f]
                                                     `(~'->
                                                       ~(kw->base-fn-form basekw)
                                                       (~'m/schema ~'options)
                                                       ~'m/deref
                                                       ~@(when (= kind "resource")
                                                           [`(~'mu/assoc :resourceType [:enum ~type])])
                                                       ~@f)))
                                     (assoc :kind kind)
                                     (assoc :references @*references-atom*)
                                     (assoc :base-refs @*base-refs*))]
                     (-> patched
                         (wrap-local-registry basekw))))
                 (let [patch-fn (if is-simple?
                                  (simple-extension->patch x)
                                  (structure-definition->patch x))
                       initial-acc (if is-simple?
                                     {:sch nil :form [] :shape {}}
                                     {:sch  (cond-> (m/schema :map external-registry)
                                              (= kind "resource")
                                              (mu/assoc :resourceType [:enum type]))
                                      :form []
                                      :shape {}})
                       patched-acc (patch-fn initial-acc)]
                   (-> (if is-simple?
                         (assoc patched-acc :form (first (:form patched-acc)))
                         (sch-form patched-acc))
                       (assoc :kind kind)
                       (assoc :references @*references-atom*)
                       (assoc :base-refs @*base-refs*)
                                              wrap-local-registry))))))))

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------

(comment

  ;; New pipeline (see com.breezeehr.fhir-schema-gen/generate!)
  (require '[com.breezeehr.fhir-schema-gen :as gen])
  (def r4b-plan (gen/plan [{:type :bundle :path "definitions.json/profiles-types.json"}
                           {:type :bundle :path "definitions.json/profiles-resources.json"}
                           {:type :bundle :path "definitions.json/extension-definitions.json"}
                           {:type :bundle :path "definitions.json/profiles-others.json"}] #{nil}))
  (def sa (atom {}))
  (gen/generate! sa
                 ["target" "staging" "src"]
                 [".." "fhir" "malli" "r4b" "src"]
                 r4b-plan))
