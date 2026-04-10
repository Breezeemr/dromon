(ns com.breezeehr.fhir-primitives
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [malli.experimental.time :as met]
            [malli.util :as mu]
            [com.breezeehr.malli-decimal :as md]
            #?@(:cljs [[goog.object]])))




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

(def fhir-registry (merge
                    (m/default-schemas)
                    (mu/schemas)
                    (met/schemas)
                    md/decimal-schemas
                    lazy-ref))

(def staging-fhir-registry
  "Registry for use during schema generation. Overrides :ref with the lazy-ref
   implementation so that forward references (e.g. Element -> Extension) don't
   fail when the target namespace hasn't been staged yet."
  (merge fhir-registry
         {:ref (-lazy-ref-schema {})}))

(def fhir-registry-options {:registry fhir-registry})

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

