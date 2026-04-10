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

(defn- lookup-schema-kw
  "Look up a type code in the schema atom, returning the keyword."
  [code version]
  (lookup-kw @*schema-atom* (munge-ns (str/replace code "." "-")) base-ns-prefix version))

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

(defn- resolve-local-registry-schemas
  "Build a malli-compatible registry map from local-registry entries.
   For :own entries, use the stored :sch. For :ref entries, resolve from the source."
  [local-reg]
  (into {}
        (map (fn [[k entry]]
               [k (case (:type entry)
                    :own (:sch entry)
                    :ref (let [src-lr (:local-registry (get @*schema-atom* (:source-kw entry)))]
                           (:sch (get src-lr k))))]))
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
   "date"         [:time/local-date {:fhir/primitive "date"}]
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
   "dateTime"     [:or {:fhir/primitive "dateTime"} :time/offset-date-time :time/local-date :time/instant]
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

(defn unwrap-sequential [sch]
  (if sch
    (case (m/type sch)
      :sequential (mu/get sch 0)
      sch)
    sch))


;; ---------------------------------------------------------------------------
;; Element patch dispatch
;; ---------------------------------------------------------------------------

(declare compute-element-patch)

(defn- make-gb-xform [main-path]
  (comp
   (remove #(-> % :path (= main-path)))
   (xforms/by-key (fn [{:keys [path]}]
                    (into []
                          (take (inc (count main-path)))
                          path))
                  (xforms/into []))))

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
  "Resolve a ref keyword to its compiled malli schema via requiring-resolve."
  [ref-kw]
  (when (keyword? ref-kw)
    (let [base (lookup-kw @*schema-atom* (kw->type-name ref-kw))]
      (resolve-malli-sch (or base ref-kw)))))

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

(defn- extract-this-discriminator-path
  "For $this discriminators, determine the get-in path from the slice root's pattern.
   e.g. patternIdentifier: {system: ...} => [:system]"
  [elem]
  (when-let [[_ field-kw] (extract-pattern-value elem)]
    (when field-kw [field-kw])))

(defn- clean-path-segment [seg]
  (let [idx (str/index-of seg ":")]
    (if idx (subs seg 0 idx) seg)))

(defn- extract-dispatch-value
  "Extract the dispatch value for a slice. Returns a map with :value and optionally
   :this-path (for $this discriminators, the get-in path to the discriminating field)."
  [discriminators sub-elements slice-path]
  (let [results (mapv
                 (fn [{disc-path :path}]
                   (if (= disc-path "$this")
                     (let [slice-root (first (filter #(= (count (:path %)) (count slice-path))
                                                     sub-elements))]
                       {:value (extract-fixed-value slice-root)
                        :this-path (extract-this-discriminator-path slice-root)})
                     (let [disc-segs (str/split disc-path #"\.")
                           match (first
                                  (filter
                                   (fn [elem]
                                     (let [suffix (when (> (count (:path elem)) (count slice-path))
                                                    (subvec (:path elem) (count slice-path)))
                                           cleaned (when suffix (mapv clean-path-segment suffix))]
                                       (= cleaned (vec disc-segs))))
                                   sub-elements))]
                       {:value (extract-fixed-value match)})))
                 discriminators)
        vals (mapv :value results)
        this-path (some :this-path results)]
    {:dispatch-value (if (= (count vals) 1) (first vals) vals)
     :this-path this-path}))

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
                        (fn [{:keys [dispatch-value form]}]
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
                                  :else base-element-form)]
                            [dispatch-value constrained]))
                        slices)
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
  "Shared transduction for :map/:or and :ref/:lazy-ref patches."
  [acc sub-elements main-path version]
  (transduce
   (make-gb-xform main-path)
   (fn ([acc] acc)
     ([acc [_k items]]
      (element-definition->attribute acc main-path version items)))
   acc
   sub-elements))

(defn- prepend-base-sym
  "Prepend a base schema symbol to the form vector, threading into any existing -> form."
  [f base-sym]
  (if (and (seq f) (seq? (first f)) (= '-> (first (first f))))
    [`(~'-> ~base-sym ~@(rest (first f)))]
    (if (empty? f)
      [`(~'-> ~base-sym (~'m/schema ~'options) ~'m/deref)]
      [`(~'-> ~base-sym (~'m/schema ~'options) ~'m/deref ~@f)])))

(defn- splice-resource-type-form
  "Splice a mu/update-properties call into a form vector to set :resourceType."
  [f rt]
  (let [rt-form `(~'mu/update-properties
                  (~'fn [~'props]
                        (~'-> (~'or ~'props {:closed true})
                              (~'assoc :resourceType ~rt))))]
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
          effective-old-sch (if (and resolved-old-sch (not old-sch-map?)) nil resolved-old-sch)]
      (transduce
       (make-gb-xform main-path)
       (fn ([acc]
            (let [acc (flush-pending-slicing acc)
                  acc-val (if-not effective-old-sch
                            (if base-primitive
                              acc
                              (do
                                (swap! *references-atom* conj base-kw)
                                (update acc :form #(prepend-base-sym % (kw->base-fn-form base-kw)))))
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
       sub-elements))))

(defn- dispatch-from-field-info
  "Derive compute-element-patch dispatch value from shape field-info when FHIR type code is nil."
  [field-info old-sch]
  (cond
    (nil? field-info) nil
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
    (assoc new-acc :form (if max-val
                           [`[:sequential {:max ~max-val} ~@(:form new-acc)]]
                           [`[:sequential ~@(:form new-acc)]]))
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
      (let [_ (when ref-kw
                (swap! *references-atom* conj ref-kw))
            update-form-entry
            `(~'mu/update ~k (~'fn [~'sch]
                                    ~(let [target (if is-seq? 'inner-sch 'sch)
                                           update-expr (if (seq? (first new-sub-form))
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
                                                         (first new-sub-form))]
                                       (if is-seq?
                                         `(~'mu/update ~'sch 0 (~'fn [~'inner-sch] ~update-expr))
                                         update-expr))))
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
          (let [element-kw (lookup-schema-kw "Element" version)]
            (swap! *references-atom* conj element-kw)
            (-> old-acc
                (update :form conj `(~'mu/assoc ~k ~(first new-sub-form)))
                (cond-> (and (not-empty props) (not fixed-enum?))
                  (update :form conj `(~'mu/update-entry-properties ~k ~'merge ~props)))
                (cond-> primitive?
                  (update :form conj
                          `(~'mu/assoc ~(underscore-attr k) [:ref ~element-kw])
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
        ;; Compute value-key from the complex type code so the transformer knows
        ;; which key to extract from FHIR JSON extension entries (e.g. :valueCoding).
        ;; Only set for complex types here; primitive extensions get value-key via
        ;; profile-value-key detection below (which also wraps in sequential).
        value-key-kw (when bare-value-code
                       (keyword (str "value" (str/upper-case (subs bare-value-code 0 1))
                                    (subs bare-value-code 1))))
        final-props (cond-> (merge props type-props)
                      value-key-kw (assoc :fhir/value-key value-key-kw))
        {new-sub-sch :sch new-sub-form :form}
        (if bare-value-code
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
                     (let [profile-clean (let [idx (.indexOf ^String profile "|")]
                                           (if (pos? idx) (.substring ^String profile 0 idx) profile))
                           profile-name (munge-ns (str/replace (last (str/split profile-clean #"/")) "." "-"))]
                       (or (first (filter #(= (kw->type-name %) profile-name)
                                          (keys @*schema-atom*)))
                           (uri->kw2 profile version))))
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

(defn- apply-non-extension-slice
  "Non-extension slices: collect into :pending-slicing or fall back to direct threading."
  [old-acc base-sch sub-elements main-path slice-name
   {:keys [base-field-name effective-k final-props new-sub-sch new-sub-form new-sch update-fn-form]}]
  (let [base-k base-field-name]
    (if-let [pending (get-in old-acc [:pending-slicing base-k])]
      (let [{:keys [dispatch-value this-path]} (extract-dispatch-value (:discriminators pending) sub-elements main-path)]
        (update-in old-acc [:pending-slicing base-k :slices] conj
                   {:slice-name slice-name
                    :dispatch-value dispatch-value
                    :this-path this-path
                    :sch new-sub-sch
                    :form new-sub-form}))
      ;; No slicing info: fallback to direct threading
      (let [is-base-seq? (some-> base-sch m/type (= :sequential))
            ;; When base-sch is a :ref/:lazy-ref, resolve it so slice-forms
            ;; can modify the actual map schema (not the opaque ref wrapper).
            base-ref-kw (when base-sch
                          (let [t (try (m/type base-sch) (catch Exception _ nil))]
                            (when (#{:ref :lazy-ref} t)
                              (let [raw (first (m/children base-sch))]
                                (when (keyword? raw) raw)))))
            slice-forms (if (and (= (count new-sub-form) 1) (seq? (first new-sub-form)) (= '-> (first (first new-sub-form))))
                          (let [[_ _base & steps] (first new-sub-form)] steps)
                          new-sub-form)
            ;; If slice-forms is a single bare value (not threading ops), use it as replacement
            is-replacement? (and (= (count slice-forms) 1) (not (seq? (first slice-forms))))]
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
          ;; No slice forms and no pending-slicing: skip the slice entirely.
          ;; The slice key (e.g. :us-core) doesn't exist on the base schema,
          ;; and without slicing context there's no `:multi` to host it.
          old-acc)))))

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
        props (cond-> (select-keys main-attr [:isSummary :short :definition :comment :binding])
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
                    items)]
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

(defn- build-sd-properties
  "Build the properties map for a StructureDefinition."
  [{:keys [description title] t :type} base-element]
  (-> {:closed true}
      (cond->
        t           (assoc :resourceType t)
        description (assoc :fhir.structure-definition/description description)
        title       (assoc :fhir.structure-definition/title title))
      (cond->
        (:definition base-element) (assoc :fhir/definition (:definition base-element))
        (:short base-element) (assoc :fhir/short (:short base-element)))))

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

(defn- simple-extension->patch [{:keys [name title description]
                                 {:keys [element]} :differential}]
  (let [value-element (first (filter #(-> % :path (= "Extension.value[x]")) element))
        attr-types (:type value-element)
        attr-type (if (seq attr-types) (first attr-types) {:code "Element"})
        id (:id value-element)
        main-path (into [] (str/split (or id (:path value-element)) #"\."))]
    (fn [_acc]
      (let [{new-sub-sch :sch new-sub-form :form primitive? :primitive?}
            (attr->value-schema-patch {:sch nil, :form [], :new-field? true}
                                      id attr-type value-element [] main-path nil)

            props (cond-> {:closed true
                           :resourceType name}
                    description (assoc :fhir.structure-definition/description description)
                    title (assoc :fhir.structure-definition/title title)
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
                   (let [base-def (let [idx (.indexOf ^String baseDefinition "|")]
                                    (if (pos? idx) (.substring ^String baseDefinition 0 idx) baseDefinition))
                         base-url ^URL (io/as-url base-def)
                         path (into [] (remove empty?) (str/split (.getPath base-url) #"\/"))
                         m-name (munge-ns (str/replace (last path) "." "-"))]
                     (or (first (filter #(= (kw->type-name %) m-name) (keys @schema-atom)))
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
          is-simple? (simple-extension? x)]
      (swap! schema-atom assoc kw
             (binding [*references-atom* (atom #{})
                       *local-registry* local-registry
                       *recursive-references* recursive-references
                       *base-refs* (atom {})]
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
