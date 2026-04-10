(ns server.search-registry
  "Builds an enriched search parameter registry by combining SearchParameter JSON
   definitions with Malli schema introspection. The registry maps
   [resource-type param-code] -> enriched param descriptor that the store layer
   uses to generate SQL conditions without hardcoded field names."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [malli.core :as m]
            [jsonista.core :as json]))

;; ---------------------------------------------------------------------------
;; Malli schema introspection
;; ---------------------------------------------------------------------------

(defn- ref-type-name
  "Extracts the FHIR type name from a Malli :ref key.
   e.g. :org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0 -> \"CodeableConcept\""
  [ref-key]
  (when-let [ns-str (namespace ref-key)]
    (last (str/split ns-str #"\."))))

(declare classify-schema)

(defn- extract-children-map
  "Extracts sub-field classifications from an inline :map schema (BackboneElement).
   Returns {field-name -> classification} or nil."
  [schema depth]
  (try
    (when (= :map (m/type schema))
      (reduce
       (fn [acc [field-key _entry-props child-schema]]
         (let [classification (classify-schema child-schema (inc depth))]
           (if classification
             (assoc acc (name field-key) classification)
             acc)))
       {}
       (m/children schema)))
    (catch Exception _ nil)))

(defn- classify-schema
  "Classifies a Malli field schema into {:fhir-type \"...\" :array? bool}.
   For BackboneElement fields, includes :children map of sub-field classifications.
   Returns nil for unrecognizable schemas."
  ([field-schema] (classify-schema field-schema 0))
  ([field-schema depth]
   (when (and field-schema (< depth 4))
     (try
       (let [t (m/type field-schema)]
         (case t
           :sequential
           (let [inner-schema (first (m/children field-schema))
                 inner (classify-schema inner-schema (inc depth))]
             (when inner
               (assoc inner :array? true)))

           :ref
           (let [ref-key (first (m/children field-schema))]
             {:fhir-type (ref-type-name ref-key) :array? false})

           :or
           (let [props (m/properties field-schema)]
             (when-let [prim (:fhir/primitive props)]
               {:fhir-type prim :array? false}))

           (:string :int :double :boolean)
           (let [props (m/properties field-schema)
                 prim (or (:fhir/primitive props) (name t))]
             {:fhir-type prim :array? false})

           :enum
           {:fhir-type "code" :array? false}

           :map
           (let [children (extract-children-map field-schema depth)]
             (when (seq children)
               {:fhir-type "BackboneElement" :array? false :children children}))

           ;; fallback: check for fhir/primitive metadata on custom schema types
           ;; (e.g., :time/local-date {:fhir/primitive "date"})
           (let [props (try (m/properties field-schema) (catch Exception _ nil))]
             (when-let [prim (:fhir/primitive props)]
               {:fhir-type prim :array? false}))))
       (catch Exception _ nil)))))

(defn- extract-field-map
  "Extracts a map of {field-name -> {:fhir-type, :array?, :fhir/extension, :url, :children}}
   from a compiled Malli :map schema."
  [map-schema]
  (try
    (reduce
     (fn [acc [field-key entry-props field-schema]]
       (let [field-name (name field-key)
             classification (classify-schema field-schema)]
         (if classification
           (assoc acc field-name
                  (cond-> classification
                    (:fhir/extension entry-props)
                    (assoc :fhir/extension true
                           :url (:url entry-props))))
           acc)))
     {}
     (m/children map-schema))
    (catch Exception _ {})))

(defn- extract-field-map-from-cap-schema
  "Extracts field type information from a compiled capability :multi schema.
   Merges fields from ALL variants so profile-added extension fields are included."
  [cap-schema]
  (try
    (let [variants (m/children cap-schema)]
      (reduce
       (fn [acc [_key _props variant-schema]]
         (merge acc (extract-field-map variant-schema)))
       {}
       variants))
    (catch Exception _ {})))

;; ---------------------------------------------------------------------------
;; FHIRPath expression parsing
;; ---------------------------------------------------------------------------

(defn- upper-first
  "Capitalizes only the first character, preserving the rest.
   Unlike str/capitalize which lowercases the rest: \"dateTime\" -> \"DateTime\"."
  [s]
  (when (seq s)
    (str (Character/toUpperCase ^char (first s)) (subs s 1))))

(defn- strip-resource-prefix
  "Strips 'ResourceType.' prefix from a FHIRPath expression segment."
  [expr]
  (if-let [dot-idx (str/index-of expr ".")]
    (subs expr (inc dot-idx))
    expr))

(defn- parse-where-clause
  "Parses .where(resolve() is X) returning [field-path target-type] or nil."
  [expr]
  (when-let [match (re-find #"^(.+?)\.where\(resolve\(\) is (\w+)\)$" expr)]
    [(nth match 1) (nth match 2)]))

(defn- parse-as-cast
  "Parses .as(type) returning [base-name cast-type] or nil."
  [expr]
  (when-let [match (re-find #"^(.+?)\.as\((\w+)\)$" expr)]
    [(nth match 1) (nth match 2)]))

(defn- parse-paren-cast
  "Parses (R.field as type) returning [path cast-type] or nil."
  [expr]
  (when-let [match (re-find #"^\((.+?) as (\w+)\)$" expr)]
    [(strip-resource-prefix (nth match 1)) (nth match 2)]))

(defn- choice-type-columns
  "For a choice-type base field name (e.g. 'effective'), finds all matching
   concrete field names in the field map filtered by relevance to search type."
  [base-name field-map search-type]
  (let [relevant-fhir-types (case search-type
                              "date" #{"dateTime" "instant" "Period" "date"}
                              "reference" #{"Reference"}
                              nil)
        matches (->> field-map
                     (filter (fn [[k _v]]
                               (and (str/starts-with? k base-name)
                                    (not= k base-name)
                                    (let [suffix (subs k (count base-name))]
                                      (and (seq suffix)
                                           (Character/isUpperCase (first suffix)))))))
                     (map (fn [[k v]] (assoc v :col k))))]
    (if relevant-fhir-types
      (filter #(contains? relevant-fhir-types (:fhir-type %)) matches)
      matches)))

(defn- find-extension-field
  "Finds a schema field matching a FHIR extension URL.
   Returns [field-name field-info] or nil."
  [field-map url]
  (first (filter (fn [[_k v]] (and (:fhir/extension v) (= (:url v) url))) field-map)))

(defn- extract-extension-url
  "Extracts the extension URL from a FHIRPath extension expression."
  [expr]
  (second (re-find #"extension\.where\(url\s*=\s*'([^']+)'\)" expr)))

(defn- xtdb-col-name
  "Returns the column/field name unchanged. Nested struct field names in XTDB v2
   preserve the camelCase produced by the schema-aware storage encoder, so the
   FHIRPath segment (which already matches the canonical FHIR field name) can be
   used as-is for SQL struct accessors."
  [s]
  s)

(defn- resolve-nested-path
  "Resolves a nested path like 'participant.role' using the field map.
   Looks up the parent field and its children to build a descriptor with
   :sub-col, :sub-fhir-type, :sub-array? when possible."
  [segments field-map]
  (let [parent-name (first segments)
        sub-path (str/join "." (rest segments))
        parent-info (get field-map parent-name)]
    (if parent-info
      (let [;; Try to find the sub-field type from BackboneElement children
            sub-info (when-let [children (:children parent-info)]
                       (get children (first (rest segments))))]
        [(cond-> {:col parent-name
                  :fhir-type (or (:fhir-type parent-info) "BackboneElement")
                  :array? (:array? parent-info false)
                  :sub-col (xtdb-col-name sub-path)}
           sub-info (assoc :sub-fhir-type (:fhir-type sub-info)
                           :sub-array? (:array? sub-info false)))])
      ;; Parent not found — return as-is
      [{:col parent-name :fhir-type nil :array? false :sub-col (xtdb-col-name sub-path)}])))

(defn- resolve-expression
  "Resolves a single FHIRPath expression segment into column descriptors.
   Returns a vector of column descriptor maps."
  [expr field-map search-type]
  (let [expr (str/trim expr)]
    (cond
      ;; Resource.id -> _id
      (= expr "Resource.id")
      [{:col "_id" :fhir-type "id" :array? false}]

      ;; Resource.meta.lastUpdated -> maps to XTDB _system_from temporal column
      (= expr "Resource.meta.lastUpdated")
      [{:col "_system_from" :fhir-type "instant" :array? false}]

      ;; (R.field as type) - parenthesized cast
      (str/starts-with? expr "(")
      (when-let [[path cast-type] (parse-paren-cast expr)]
        (let [segments (str/split path #"\.")
              concrete-col (str (last segments) (upper-first cast-type))]
          (if (> (count segments) 1)
            ;; Nested cast like (Goal.target.due as date) -> target with sub-col dueDate
            (let [parent-name (first segments)
                  parent-info (get field-map parent-name)]
              [{:col parent-name
                :fhir-type (or (:fhir-type parent-info) "BackboneElement")
                :array? (:array? parent-info false)
                :sub-col (xtdb-col-name concrete-col)
                :sub-fhir-type cast-type
                :sub-array? false}])
            ;; Simple cast like (Patient.deceased as dateTime) -> deceasedDateTime
            (let [info (get field-map concrete-col)]
              [{:col concrete-col
                :fhir-type (or (:fhir-type info) cast-type)
                :array? (:array? info false)}]))))

      ;; Extension paths — when the schema promotes the extension to a top-level
      ;; field (via the FHIR JSON transformer's value-key extraction), include
      ;; the promoted column AND the generic extension[] array fallback. The
      ;; cap-schema is a :multi over multiple profile variants and only some
      ;; variants may declare the promotion, so heterogeneous storage shapes
      ;; coexist; OR-ing both descriptors lets the search match either.
      (str/includes? expr "extension.where(url")
      (when-let [url (extract-extension-url expr)]
        (let [extension-array-col {:col "extension"
                                   :fhir-type "Extension"
                                   :array? true
                                   :extension-url url}]
          (if-let [[promoted-name promoted-info] (find-extension-field field-map url)]
            [{:col promoted-name
              :fhir-type (:fhir-type promoted-info)
              :array? (:array? promoted-info false)
              :extension-url url
              :extension-promoted? true}
             extension-array-col]
            [extension-array-col])))

      :else
      (let [path (strip-resource-prefix expr)]
        (cond
          ;; .where(resolve() is X) - reference with target type
          (str/includes? path ".where(resolve()")
          (let [[field-path _target-type] (parse-where-clause path)
                info (get field-map field-path)]
            [{:col field-path
              :fhir-type (or (:fhir-type info) "Reference")
              :array? (:array? info false)}])

          ;; .as(type) cast
          (str/includes? path ".as(")
          (when-let [[base-name cast-type] (parse-as-cast path)]
            (let [concrete (str base-name (upper-first cast-type))
                  info (get field-map concrete)]
              [{:col concrete
                :fhir-type (or (:fhir-type info) cast-type)
                :array? (:array? info false)}]))

          ;; Nested path (field.subfield)
          (str/includes? path ".")
          (resolve-nested-path (str/split path #"\.") field-map)

          ;; Simple field
          :else
          (let [info (get field-map path)]
            (if info
              [{:col path :fhir-type (:fhir-type info) :array? (:array? info false)}]
              ;; Not found — try choice type expansion
              (let [choices (choice-type-columns path field-map search-type)]
                (if (seq choices)
                  (vec choices)
                  ;; Last resort
                  [{:col path :fhir-type nil :array? false}])))))))))

(defn- resolve-search-param-expression
  "Resolves a SearchParameter's expression into column descriptors.
   Handles pipe-delimited alternatives (e.g. 'Location.name|Location.alias')."
  [expression field-map search-type]
  (when expression
    (let [alternatives (str/split expression #"\|(?![^(]*\))")
          columns (into [] (mapcat #(resolve-expression % field-map search-type)) alternatives)]
      (vec (distinct columns)))))

;; ---------------------------------------------------------------------------
;; SearchParameter resource loading from classpath
;; ---------------------------------------------------------------------------

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn definition-url->resource-path
  "Converts a SearchParameter definition URL to a classpath resource path.
   http://hl7.org/fhir/us/core/SearchParameter/us-core-condition-category
   -> org/hl7/fhir/us/core/SearchParameter/us_core_condition_category.json"
  [url]
  (try
    (let [parsed (java.net.URI. url)
          host (.getHost parsed)
          host-parts (str/split host #"\.")
          host-reversed (str/join "/" (reverse host-parts))
          path-segments (->> (str/split (.getPath parsed) #"/")
                             (remove str/blank?))
          ;; Replace hyphens with underscores in the last segment (filename)
          init-segments (butlast path-segments)
          last-segment (-> (last path-segments) (str/replace "-" "_"))]
      (str host-reversed "/" (str/join "/" init-segments) "/" last-segment ".json"))
    (catch Exception _ nil)))

(defn- load-search-param-json
  "Loads a SearchParameter JSON resource from the classpath by definition URL."
  [definition-url]
  (when-let [path (definition-url->resource-path definition-url)]
    (when-let [resource (io/resource path)]
      (let [m (json/read-value (slurp resource) json-mapper)]
        ;; Strip underscore-prefixed keys that XTDB doesn't support as columns
        (into {} (remove (fn [[k _]] (-> k name (.startsWith "_")))) m)))))

;; ---------------------------------------------------------------------------
;; Registry builder
;; ---------------------------------------------------------------------------

(defn build-resource-registry
  "Builds a search registry for a single resource type.

   Arguments:
   - search-param-refs: vector of {:name, :type, :definition} from capability schema
   - cap-schema: compiled Malli :multi capability schema (for field type introspection)

   Returns a map of {param-name -> enriched-param} where enriched-param is:
   {:type     \"token\"|\"reference\"|\"date\"|\"string\"
    :target   [\"Patient\"] or nil
    :columns  [{:col \"fieldName\" :fhir-type \"CodeableConcept\" :array? true
                :sub-col \"subField\" :sub-fhir-type \"...\" :sub-array? bool} ...]}"
  [search-param-refs cap-schema]
  (let [field-map (if cap-schema
                    (extract-field-map-from-cap-schema cap-schema)
                    {})]
    (reduce
     (fn [acc sp-ref]
       (let [sp-name (:name sp-ref)
             sp-type (:type sp-ref)
             definition-url (:definition sp-ref)
             full-sp (load-search-param-json definition-url)
             expression (:expression full-sp)
             target (:target full-sp)
             columns (resolve-search-param-expression expression field-map sp-type)]
         (if (seq columns)
           (assoc acc sp-name
                  {:type sp-type
                   :target target
                   :columns columns})
           (do
             (when expression
               (log/debug "Search param" sp-name "expression unresolved:" expression))
             acc))))
     {}
     search-param-refs)))
