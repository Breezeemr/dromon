(ns com.breezeehr.fhir-primitives
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [malli.experimental.time :as met]
            [malli.util :as mu]
            [com.breezeehr.malli-decimal :as md]
            #?@(:cljs [[goog.object]
                       ["@js-joda/core" :as js-joda]]))
  #?(:clj (:import (java.time Year YearMonth))))

#?(:cljs (def ^:private Year (.-Year js-joda)))
#?(:cljs (def ^:private YearMonth (.-YearMonth js-joda)))

(def precision-time-schemas
  "Partial-precision FHIR temporal schemas not provided by malli's time module:
   :time/year (gYear, e.g. \"1993\") and :time/year-month (gYearMonth, \"1993-07\").
   FHIR date/dateTime allow these partial precisions; they decode to
   java.time.Year / YearMonth so the precision survives (see fhir-json-transform)."
  {:time/year       (m/-simple-schema {:type :time/year
                                       :pred #(instance? Year %)})
   :time/year-month (m/-simple-schema {:type :time/year-month
                                       :pred #(instance? YearMonth %)})})




#?(:cljs (defn- pr-writer-into-schema [obj writer opts]
           (-write writer "#IntoSchema ")
           (-pr-writer {:type (m/-type ^m/IntoSchema obj)} writer opts)))
#?(:cljs (defn- pr-writer-schema [obj writer opts]
           (-pr-writer (m/-form ^m/Schema obj) writer opts)))


(defn -lazy-ref-schema
  ([]
   (-lazy-ref-schema nil))
  ([{:keys [type-properties]}]
   ^{:type :malli.core/into-schema}
   (reify
     malli.core/AST
     (-from-ast [parent ast options] (m/-from-value-ast parent ast options))
     malli.core/IntoSchema
     (-type [_] :ref)
     (-type-properties [_] type-properties)
     (-into-schema [parent properties [ref :as children] {:malli.core/keys [allow-invalid-refs] :as options}]
       (m/-check-children! :ref properties children 1 1)
       (when-not (m/-reference? ref)
         (m/-fail! :malli.core/invalid-ref {:ref ref}))
       (let [rf (m/-memoize (fn []
                              (if-some [looked-up (mr/-schema (m/-registry options) ref)]
                                (m/schema looked-up options)
                                (m/-fail! ::lazy-ref-not-in-registry
                                          {:ref ref
                                           :schemas (keys (mr/-schemas (m/-registry options)))}))))
             children (vec children)
             form (delay (m/-simple-form parent properties children identity options))
             cache (m/-create-cache options)
             ->parser (fn [f] (let [parser (m/-memoize (fn [] (f (rf))))]
                                (fn [x] ((parser) x))))]
         ^{:type :malli.core/schema}
         (reify
           m/AST
           (-to-ast [this _] (m/-to-value-ast this))
           m/Schema
           (-validator [_]
             (let [validator (m/-memoize (fn [] (m/-validator (rf))))]
               (fn [x] ((validator) x))))
           (-explainer [_ path]
             (let [explainer (m/-memoize (fn [] (m/-explainer (rf) (conj path 0))))]
               (fn [x in acc] ((explainer) x in acc))))
           (-parser [_] (->parser m/-parser))
           (-unparser [_] (->parser m/-unparser))
           (-transformer [this transformer method options]
             (let [this-transformer (m/-value-transformer transformer this method options)
                   deref-transformer (m/-memoize (fn [] (m/-transformer (rf) transformer method options)))]
               (m/-intercepting this-transformer (fn [x] (if-some [t (deref-transformer)] (t x) x)))))
           (-walk [this walker path options]
             (let [accept (fn [] (m/-inner walker (rf) (into path [0 0])
                                           (m/-update options :malli.core/walked-refs #(conj (or % #{}) ref))))]
               (when (m/-accept walker this path options)
                 (if (or (not ((m/-boolean-fn (:malli.core/walk-refs options false)) ref))
                         (contains? (:malli.core/walked-refs options) ref))
                   (m/-outer walker this path [ref] options)
                   (m/-outer walker this path [(accept)] options)))))
           (-properties [_] properties)
           (-options [_] options)
           (-children [_] children)
           (-parent [_] parent)
           (-form [_] @form)
           m/Cached
           (-cache [_] cache)
           m/LensSchema
           (-get [_ key default] (if (= key 0) (m/-pointer ref (rf) options) default))
           (-keep [_])
           (-set [this key value] (if (= key 0) (m/-set-children this [value])
                                      (m/-fail! :malli.core/index-out-of-bounds {:schema this, :key key})))
           m/RefSchema
           (-ref [_] ref)
           (-deref [_] (rf))
           m/RegexSchema
           (-regex-op? [_] false)
           (-regex-validator [this] (m/-fail! :malli.core/potentially-recursive-seqex this))
           (-regex-explainer [this _] (m/-fail! :malli.core/potentially-recursive-seqex this))
           (-regex-parser [this] (m/-fail! :malli.core/potentially-recursive-seqex this))
           (-regex-unparser [this] (m/-fail! :malli.core/potentially-recursive-seqex this))
           (-regex-transformer [this _ _ _] (m/-fail! :malli.core/potentially-recursive-seqex this))
           (-regex-min-max [this _] (m/-fail! :malli.core/potentially-recursive-seqex this))))))))


(def lazy-ref {:lazy-ref (-lazy-ref-schema {})})

(defn check-lazy-refs!
  [sch]
  (m/walk sch
          (fn [s path children options]
            s)
          #_(assoc (m/options sch) :malli.core/walk-refs true)
          {:malli.core/walk-refs true}))

(def ^:private data-absent-reason-codes
  #{"unknown" "asked-unknown" "temp-unknown" "not-asked" "asked-declined"
    "masked" "not-applicable" "unsupported" "as-text" "error" "not-a-number"
    "negative-infinity" "positive-infinity" "not-performed" "not-permitted"})

(defn- stated-absence? [element]
  (boolean
    (some #(and (= "http://hl7.org/fhir/StructureDefinition/data-absent-reason" (:url %))
                (contains? data-absent-reason-codes (:valueCode %))
                (not (seq (:extension %)))
                (not-any? (fn [k] (and (keyword? k) (not= :valueCode k)
                                       (.startsWith (name k) "value"))) (keys %)))
          (:extension element))))

(defn- primitive-map-schema
  "Keep a map's structural API and required-key metadata while validating
   generated mandatory strings against either their value or a DAR companion."
  []
  (let [base (m/-map-schema)]
    (reify
      m/AST
      (-from-ast [parent ast options] (m/-from-entry-ast parent ast options))
      m/IntoSchema
      (-type [_] :map)
      (-type-properties [_] (m/-type-properties base))
      (-properties-schema [_ options] (m/-properties-schema base options))
      (-children-schema [_ options] (m/-children-schema base options))
      (-into-schema [parent properties children options]
        (let [structural (m/-into-schema base properties children options)
              cache (m/-create-cache options)
              pairs (into {}
                          (keep (fn [[k props value]]
                                  (when (and (keyword? k) (:fhir/primitive-absence props)
                                             (not (:optional props))
                                             (not (:xml/attr props))
                                             (= :string (m/type value)))
                                    (let [companion (keyword (str "_" (name k)))]
                                      (when (mu/get structural companion)
                                        [k companion])))))
                          (m/children structural))
              relaxed (reduce #(mu/optional-keys %1 [%2]) structural (keys pairs))
              present? (fn [x] (every? (fn [[k companion]]
                                        (or (contains? x k) (stated-absence? (get x companion)))) pairs))
              validate (delay (m/validator relaxed))
              parse (delay (m/parser relaxed))
              unparse (delay (m/unparser relaxed))]
          ^{:type :malli.core/schema}
          (reify
            m/AST
            (-to-ast [_ opts] (m/-to-ast structural opts))
            m/Schema
            (-validator [_] (fn [x] (and (@validate x) (present? x))))
            (-explainer [this path]
              (let [explain (m/-explainer relaxed path)]
                (fn [x in acc]
                  (let [acc (explain x in acc)]
                    (if-not (map? x) acc
                      (reduce (fn [acc [k companion]]
                                (if (or (contains? x k) (stated-absence? (get x companion))) acc
                                  (conj acc {:path (conj path k) :in (conj in k)
                                             :schema this :value nil :type :malli.core/missing-key})))
                              acc pairs))))))
            (-parser [_] (fn [x] (let [result (@parse x)]
                                  (if (and (not= result :malli.core/invalid) (present? x)) result :malli.core/invalid))))
            (-unparser [_] (fn [x] (let [result (@unparse x)]
                                    (if (and (not= result :malli.core/invalid) (present? result)) result :malli.core/invalid))))
            (-transformer [_ transformer method opts] (m/-transformer structural transformer method opts))
            (-walk [this walker path opts] (m/-walk-entries this walker path opts))
            (-properties [_] (m/properties structural))
            (-options [_] options)
            (-children [_] (m/children structural))
            (-parent [_] parent)
            (-form [_] (m/form structural))
            m/EntrySchema
            (-entries [_] (m/-entries structural))
            (-entry-parser [_] (m/-entry-parser structural))
            m/Cached
            (-cache [_] cache)
            m/LensSchema
            (-keep [_] true)
            (-get [this k default] (m/-get-entries this k default))
            (-set [this k value] (m/-set-entries this k value))
            m/ParserInfo
            (-parser-info [_ opts] (m/-parser-info structural opts))))))))

(def fhir-registry (merge
                    (m/default-schemas)
                    (mu/schemas)
                    (met/schemas)
                    precision-time-schemas
                    md/decimal-schemas
                    lazy-ref
                    {:map (primitive-map-schema)}))

(def staging-fhir-registry
  "Registry for use during schema generation. Overrides :ref with the lazy-ref
   implementation so that forward references (e.g. Element -> Extension) don't
   fail when the target namespace hasn't been staged yet."
  (merge fhir-registry
         {:ref (-lazy-ref-schema {})}))

(def fhir-registry-options {:registry fhir-registry})

(def ^:private fhir-lazy-ref-registry
  "Base default-registry for `lazy-full-registry`. Overrides `:ref` with
   `-lazy-ref-schema` so ref resolution is deferred via `m/-memoize` until
   someone actually walks the compiled schema. That deferral is what
   breaks the Element <-> Extension recursion during schema compilation:
   without it, compiling Extension → Element → :ref Extension would
   re-enter the provider before the outer cache entry exists."
  (merge fhir-registry
         {:ref (-lazy-ref-schema {})}))

(defn lazy-full-registry
  "Build a cached malli registry for a compiled FHIR capability schema.
   `local-registry` is the per-file `{schema-name -> IntoSchema}` map
   (possibly a merge of a base profile's registry with a derived one).
   On first lookup of a name the provider resolves it via `local-registry`
   and compiles it with the recursive registry passed in; the result is
   cached in the `lazy-registry`'s atom. Subsequent `:ref` lookups hit the
   cache and malli's `schema` treats already-compiled schemas as identity,
   so Extension / Element / etc. are compiled a single time per `full-sch`
   instance instead of once per :ref site."
  [local-registry]
  (mr/lazy-registry
    fhir-lazy-ref-registry
    (fn [name registry]
      (when-let [hit (get local-registry name)]
        (m/schema hit {:registry registry})))))

(def external-registry
  "Alias for fhir-registry-options. Deprecated — use fhir-registry-options."
  fhir-registry-options)

(def registry
  {:fhir/markdown  :string
   :fhir/date      :time/local-date
   :fhir/url       :string
   :fhir/xhtml     :string
   :fhir/id        :string
   :fhir/canonical :string
   :fhir/code      :string
   :fhir/oid       :string
   :fhir/b64       :string
   :fhir/uri       :string
   :fhir/time      :time/local-time
   :fhir/dateTime  [:or :time/offset-date-time
                    :time/local-date
                    #_'inst?]})



(defn make-schema [malli-registry start-type]
  (m/schema [:schema {:registry
                      malli-registry}
             start-type]
            external-registry))

(defn fhir-from-ast [ast]
  (m/from-ast ast
              external-registry))

(defn fhir-from-ast2
  ([ast reg]
   (m/from-ast
    {:type     :schema,
     :child    ast,
     :registry reg}
    external-registry))
  ([ast reg options]
   (m/from-ast
    {:type     :schema,
     :child    ast,
     :registry reg}
    (into external-registry options))))

(defn update-registry [sch]
  (-> sch
      m/ast
      (m/from-ast external-registry)))

(defn extend-registry [sch registry]
  (-> sch
      (mu/update-properties update :registry into registry)
      update-registry))

