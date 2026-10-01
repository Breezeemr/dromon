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

           ;; Transparent nullability wrapper: a repeating primitive
           ;; generates as [:sequential [:maybe prim]], so classify the
           ;; child without consuming depth budget.
           :maybe
           (classify-schema (first (m/children field-schema)) depth)

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

(defn- value-key->fhir-type
  "Derives the FHIR type name from a :fhir/value-key like :valueDateTime.
   Strips the 'value' prefix and lowercases the first char to get 'dateTime'.
   Returns nil when the key does not follow the value[Type] convention."
  [value-key]
  (when value-key
    (let [s (name value-key)]
      (when (str/starts-with? s "value")
        (let [type-part (subs s 5)]
          (when (seq type-part)
            (str (Character/toLowerCase ^char (first type-part))
                 (subs type-part 1))))))))

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
                           :url (:url entry-props))
                    ;; For promoted extensions, override the fhir-type with the
                    ;; actual value type derived from :fhir/value-key (e.g.
                    ;; :valueDateTime -> "dateTime") instead of keeping the
                    ;; extension ref name (e.g. "condition-assertedDate").
                    (and (:fhir/extension entry-props)
                         (:fhir/value-key entry-props))
                    (assoc :fhir-type
                           (or (value-key->fhir-type (:fhir/value-key entry-props))
                               (:fhir-type classification)))))
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

(defn- parse-where-system
  "Parses `path.where(system='X')` returning [field-path system] or nil.
   This is the shape of the `phone` and `email` SearchParameters:
   `Patient.telecom.where(system='phone')`."
  [expr]
  (when-let [match (re-find #"^(.+?)\.where\(system\s*=\s*'([^']+)'\)$" expr)]
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
          (let [[field-path _target-type] (parse-where-clause path)]
            (if (and field-path (str/includes? field-path "."))
              ;; Dotted field path (e.g. Appointment.participant.actor):
              ;; resolve through the nested-path machinery so the store
              ;; receives {:col "participant" :sub-col "actor"} instead of a
              ;; dotted column it cannot translate to Datalog. Target-type
              ;; narrowing still comes from the SearchParameter's :target.
              (resolve-nested-path (str/split field-path #"\.") field-map)
              (let [info (get field-map field-path)]
                [{:col field-path
                  :fhir-type (or (:fhir-type info) "Reference")
                  :array? (:array? info false)}])))

          ;; .where(system='X') - a ContactPoint narrowed to one system
          ;; (`phone`, `email`). The column is the telecom field itself; the
          ;; system rides along as a fixed constraint the store ANDs in.
          ;; Without this branch the expression fell through to the nested
          ;; path case and emitted `:sub-col "where(system='phone')"`, a
          ;; column no store can translate, so the parameter silently
          ;; degraded to an in-memory match that ignored the system.
          (str/includes? path ".where(system")
          (when-let [[field-path system] (parse-where-system path)]
            (mapv #(assoc % :fixed {:system system})
                  (if (str/includes? field-path ".")
                    (resolve-nested-path (str/split field-path #"\.") field-map)
                    (let [info (get field-map field-path)]
                      [{:col field-path
                        :fhir-type (or (:fhir-type info) "ContactPoint")
                        :array? (:array? info false)}]))))

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

(def ^:private any-resource-roots
  "Leading segments that apply to every resource type (Resource.id,
   Resource.meta.lastUpdated, DomainResource.text)."
  #{"Resource" "DomainResource"})

(defn- foreign-alternative?
  "True when one `|` alternative of an expression is rooted at a resource
   type other than `resource-type`: its leading segment, past any opening
   parenthesis, is a capitalised name (FHIRPath element names are lower
   camel case, type names upper) that is neither `resource-type` nor a
   Resource / DomainResource root. A nil `resource-type` keeps everything."
  [alternative resource-type]
  (when resource-type
    (when-let [root (second (re-find #"^[\s(]*([A-Za-z][A-Za-z0-9]*)" alternative))]
      (and (Character/isUpperCase (.charAt ^String root 0))
           (not= root resource-type)
           (not (contains? any-resource-roots root))))))

(defn- resolve-search-param-expression
  "Resolves a SearchParameter's expression into column descriptors.
   Handles pipe-delimited alternatives (e.g. 'Location.name|Location.alias').

   Shared SearchParameters (clinical-identifier, clinical-patient,
   clinical-encounter, clinical-code, clinical-date, ...) list one path per
   base type: 'Observation.identifier | DocumentReference.masterIdentifier'.
   Alternatives rooted at another type are dropped before resolving; stripping
   their prefix instead would turn `DocumentReference.masterIdentifier` into a
   `masterIdentifier` column of Observation, which no Observation has."
  ([expression field-map search-type]
   (resolve-search-param-expression expression field-map search-type nil))
  ([expression field-map search-type resource-type]
   (when expression
     (let [alternatives (->> (str/split expression #"\|(?![^(]*\))")
                             (remove #(foreign-alternative? % resource-type)))
           columns (into [] (mapcat #(resolve-expression % field-map search-type)) alternatives)]
       (vec (distinct columns))))))

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
;; Presence parameters: `X.exists() and X != false`
;; ---------------------------------------------------------------------------

(defn- parse-exists-not-false
  "Returns the path X of an expression shaped `X.exists() and X != false`, or
   nil. Patient.deceased is the R4B instance: its token is true when
   deceasedBoolean is true or any deceasedDateTime is present, false
   otherwise -- including when the element is absent."
  [expression]
  (when expression
    (when-let [[_ path again] (re-matches #"\s*(\S+)\.exists\(\)\s+and\s+(\S+)\s*!=\s*false\s*"
                                          expression)]
      (when (= path again) path))))

;; ---------------------------------------------------------------------------
;; Composite parameters
;; ---------------------------------------------------------------------------

(def ^:private readable-fhir-types
  "search type -> the FHIR types a value of that search type is evaluated
   against. A composite component keeps only the columns its search type can
   read, so no column is compared against the wrong kind of value."
  {"token"     #{"CodeableConcept" "Coding" "Identifier" "code" "string" "uri"
                 "id" "boolean" "canonical"}
   "quantity"  #{"Quantity" "SimpleQuantity" "Age" "Count" "Distance" "Duration"}
   "date"      #{"date" "dateTime" "instant" "Period"}
   "string"    #{"string" "markdown"}
   "reference" #{"Reference"}
   "number"    #{"decimal" "integer" "positiveInt" "unsignedInt"}
   "uri"       #{"uri" "url" "canonical"}})

(def ^:private implied-search-type
  "FHIR type -> the one search type that reads it. Types more than one search
   type reads (string, uri, canonical) are absent."
  (let [ambiguous #{"string" "uri" "canonical"}]
    (into {}
          (for [[search-type fhir-types] readable-fhir-types
                fhir-type fhir-types
                :when (not (ambiguous fhir-type))]
            [fhir-type search-type]))))

(defn- column-fhir-type
  [col]
  (if (:sub-col col) (:sub-fhir-type col) (:fhir-type col)))

(defn- readable-column?
  [search-type col]
  (contains? (get readable-fhir-types search-type #{}) (column-fhir-type col)))

(defn- split-alternatives
  [expression]
  (map str/trim (str/split expression #"\|(?![^(]*\))")))

(defn- qualify-relative-expression
  "Roots each `|` alternative of a component's relative expression
   (`value.as(DateTime) | value.as(Period)`) at a placeholder segment.
   resolve-expression strips an expression's leading segment as its resource
   type, which would otherwise eat the component's own first segment."
  [expression]
  (str/join " | " (map #(str "Element." %) (split-alternatives expression))))

(defn- unwrap-repeating
  [schema]
  (if (#{:sequential :maybe} (m/type schema))
    (recur (first (m/children schema)))
    schema))

(defn- datatype-children
  "Field map of the datatype a capability schema field references
   (`ValueSet.useContext` -> UsageContext), or nil. Inline BackboneElements
   already carry :children; a datatype is a :ref, which classification does
   not follow."
  [cap-schema field-name]
  (try
    (some (fn [[_ _ variant]]
            (some (fn [[k _ field-schema]]
                    (when (= field-name (name k))
                      (let [inner (unwrap-repeating field-schema)]
                        (when (= :ref (m/type inner))
                          (extract-children-map (m/deref-all inner) 0)))))
                  (m/children variant)))
          (m/children cap-schema))
    (catch Exception _ nil)))

(defn- composite-scope
  "The base one composite alternative evaluates its components against:
   `Observation` is the resource itself (:element nil); `Observation.component`
   is each element of that field. A deeper path is not resolved."
  [alternative field-map cap-schema]
  (let [segments (str/split alternative #"\.")]
    (case (count segments)
      1 {:element nil :field-map field-map}
      2 (let [col (second segments)]
          (when-let [info (get field-map col)]
            {:element {:col col
                       :fhir-type (:fhir-type info)
                       :array? (:array? info false)}
             :field-map (or (:children info)
                            (when cap-schema (datatype-children cap-schema col))
                            {})}))
      nil)))

(defn- resolve-composite-component
  "{:type :columns [:target]} for one component, resolved against its scope's
   field map, or nil. The expression is relative to the scope. The type is
   the component's own SearchParameter's, unless that type can read none of
   the columns and the columns' FHIR type implies exactly one other: R4B's
   DocumentReference `relationship` pairs the reference definition with
   `code` and the token definition with `target` (R5 pairs them the other way
   round), and the expressions, not the definitions, say what is compared."
  [{:keys [definition expression]} scope-field-map]
  (let [component-sp (load-search-param-json definition)
        declared-type (:type component-sp)
        candidates (when (and declared-type expression)
                     (resolve-search-param-expression
                      (qualify-relative-expression expression)
                      scope-field-map declared-type))
        readable (filterv #(readable-column? declared-type %) candidates)
        implied (distinct (map #(implied-search-type (column-fhir-type %)) candidates))
        [sp-type columns] (cond
                            (seq readable)
                            [declared-type readable]

                            (and (= 1 (count implied)) (first implied))
                            [(first implied) (vec candidates)])]
    (when (seq columns)
      (cond-> {:type sp-type :columns columns}
        (and (= sp-type declared-type) (seq (:target component-sp)))
        (assoc :target (:target component-sp))))))

(defn- resolve-composite
  "The scopes of a composite SearchParameter: one per alternative of its
   expression that is rooted at this type and whose every component resolves.
   Returns a vector of {:element desc-or-nil :components [component ...]},
   components in the SearchParameter's order (the order of the `$`-separated
   value parts), or nil."
  [full-sp field-map cap-schema resource-type]
  (let [components (:component full-sp)
        expression (:expression full-sp)]
    (when (and expression (seq components))
      (let [scopes (->> (split-alternatives expression)
                        (remove #(foreign-alternative? % resource-type))
                        (keep (fn [alternative]
                                (when-let [{scope-fields :field-map :as scope}
                                           (composite-scope alternative field-map cap-schema)]
                                  (let [resolved (mapv #(resolve-composite-component % scope-fields)
                                                       components)]
                                    (when (every? some? resolved)
                                      {:element (:element scope) :components resolved})))))
                        distinct
                        vec)]
        (when (seq scopes) scopes)))))

;; ---------------------------------------------------------------------------
;; Registry builder
;; ---------------------------------------------------------------------------

(defn- resolve-param-entry
  "The enriched registry entry for one SearchParameter, or nil when its
   expression does not resolve."
  [sp-type full-sp field-map cap-schema resource-type]
  (let [expression (:expression full-sp)
        target (:target full-sp)]
    (if-let [present-path (parse-exists-not-false expression)]
      (let [columns (resolve-search-param-expression present-path field-map sp-type
                                                     resource-type)]
        (when (and (seq columns) (every? :fhir-type columns))
          {:type sp-type :target target :columns [] :exists-not-false columns}))
      (if (= "composite" sp-type)
        (when-let [scopes (resolve-composite full-sp field-map cap-schema resource-type)]
          {:type sp-type :target target :columns [] :composite scopes})
        (let [columns (resolve-search-param-expression expression field-map sp-type
                                                       resource-type)]
          (when (seq columns)
            {:type sp-type :target target :columns columns}))))))

(defn build-resource-registry
  "Builds a search registry for a single resource type.

   Arguments:
   - search-param-refs: vector of {:name, :type, :definition} from capability schema
   - cap-schema: compiled Malli :multi capability schema (for field type introspection)

   Returns a map of {param-name -> enriched-param} where enriched-param is:
   {:type     \"token\"|\"reference\"|\"date\"|\"string\"|\"quantity\"|\"composite\"
    :target   [\"Patient\"] or nil
    :columns  [{:col \"fieldName\" :fhir-type \"CodeableConcept\" :array? true
                :sub-col \"subField\" :sub-fhir-type \"...\" :sub-array? bool
                :fixed {:system \"phone\"}} ...]}

   `:fixed` is present only on columns a `.where(system='X')` expression
   narrows (`phone`, `email`): the store ANDs that system into the match.

   `:columns` means \"the value matches at one of these columns\". Two kinds of
   parameter do not fit that and carry an EMPTY `:columns` plus their own key,
   so a store that does not know the key finds nothing to compile and treats
   the parameter as unsupported instead of answering it wrongly:

   - `:exists-not-false [column ...]` -- an expression shaped
     `X.exists() and X != false` (Patient.deceased). The token is true when
     any column holds a value other than boolean false, false otherwise.
   - `:composite [{:element desc-or-nil :components [...]} ...]` -- a
     composite SearchParameter. Each scope is one base of its expression:
     `:element nil` is the resource itself, otherwise the column whose
     elements the components are evaluated within (`component`). Each
     component is {:type :columns [:target]} in the order of the value's
     `$`-separated parts, its columns relative to the scope. A value matches
     when, in some scope, every part matches on the same resource or on the
     same element. A composite whose components resolve in no scope gets no
     entry, so it is reported as unsupported."
  [search-param-refs cap-schema]
  (let [field-map (if cap-schema
                    (extract-field-map-from-cap-schema cap-schema)
                    {})
        resource-type (when cap-schema
                        (:resourceType (try (m/properties cap-schema)
                                            (catch Exception _ nil))))]
    (reduce
     (fn [acc sp-ref]
       (let [sp-name (:name sp-ref)
             full-sp (load-search-param-json (:definition sp-ref))]
         (if-let [entry (resolve-param-entry (:type sp-ref) full-sp field-map cap-schema
                                             resource-type)]
           (assoc acc sp-name entry)
           (do
             (when-let [expression (:expression full-sp)]
               (log/debug "Search param" sp-name "expression unresolved:" expression))
             acc))))
     {}
     search-param-refs)))

;; ---------------------------------------------------------------------------
;; Search parameter classification
;; ---------------------------------------------------------------------------

(def result-params
  "FHIR R4B search *result* parameters (§3.1.1.4). They shape the response
   rather than restrict which resources match, so they are never looked up in
   a resource type's registry."
  #{"_count" "_skip" "_offset" "_sort" "_include" "_revinclude"
    "_total" "_elements" "_contained" "_containedType"
    "_summary" "_format" "_pretty" "_type"})

(def temporal-params
  "Parameters that select a point in time rather than restrict which resources
   match. Like the result parameters they are never looked up in a resource
   type's registry -- but unlike them they are NOT universally available: a
   store must advertise the corresponding axis (see
   `fhir-store.protocol/ITemporalReadStore`), and the handler rejects the ones
   it cannot honour. Classifying them here only stops them being reported as
   unknown search parameters; it does not grant them."
  {"_asOf"    :system-time
   "_validAt" :valid-time})

(def resource-level-params
  "Filter parameters every resource type accepts whatever its registry
   declares. The store dispatches these off base bookkeeping attributes ahead
   of the registry lookup (`fhir-store-datomic.search/build-param-clauses`),
   so they are honoured even when the registry has no entry for them."
  #{"_id" "_tag" "_security" "_profile"})

(def text-params
  "Filter parameters answered by a full-text index rather than the registry.

   Like the resource-level parameters, no registry ever declares `_text`: its
   SearchParameter (Resource-text) has no expression, so
   `build-resource-registry` can never resolve it to a column. Unlike them it
   is NOT universally granted. Only a store fronting a full-text index can
   honour it (see `fhir-store.protocol/ITextSearchStore`), and the handler
   grants it per request, only when the store advertises that capability for
   the tenant and type in hand. Classifying it here gives the handler a way to
   say so; it does not stop `_text` being reported as unsupported by default."
  #{"_text"})

(defn- param-base-name
  "The parameter name with any `:modifier` suffix removed."
  [pname]
  (if-let [i (str/index-of pname ":")]
    (subs pname 0 i)
    pname))

(defn result-param?
  "True when `pname` names a search result parameter. Matched on the base name
   so modified forms such as `_include:iterate` classify with `_include`."
  [pname]
  (contains? result-params (param-base-name pname)))

(defn temporal-param?
  "True when `pname` names a temporal selector."
  [pname]
  (contains? temporal-params (param-base-name pname)))

(defn text-param?
  "True when `pname` is a full-text parameter, by exact name. A modifier is
   NOT stripped: a text index answers bare `_text`, and `_text:exact` or
   `_text:contains` would reach it as a name it does not match, so they stay
   unsupported like any other modified filter parameter."
  [pname]
  (contains? text-params pname))

(defn filter-params
  "The entries of `params` that restrict which resources match, i.e. everything
   that is neither a search result parameter nor a temporal selector. Keys keep
   their original form."
  [params]
  (into {} (remove (fn [[k _]] (let [n (name k)]
                                 (or (result-param? n) (temporal-param? n)))))
        params))

(defn unsupported-filter-params
  "Names of the filter parameters in `params` that this resource type cannot
   honour: neither a resource-level parameter nor a name `registry` declares.

   Modifiers and chains are deliberately NOT stripped. The store matches a
   registry entry by exact name, so `patient:identifier` and `subject.name`
   constrain a query no more than a misspelling does; reporting them keeps the
   answer aligned with what actually reaches the query builder.

   `opts` may carry `:text-search?`: true when the store answering this
   request advertises a full-text index for the resource type
   (`fhir-store.protocol/ITextSearchStore`), in which case `_text` is not
   reported. Absent or false, `_text` is reported like any other name the
   registry lacks, because a store without an index would silently ignore it.
   The grant is per request rather than a registry entry so that a registry
   built once per type never says more than the store behind a given tenant
   can honour.

   Returns a sorted vector so an OperationOutcome lists issues in a stable
   order."
  ([registry params]
   (unsupported-filter-params registry params {}))
  ([registry params {:keys [text-search?]}]
   (let [registry (or registry {})]
     (->> (filter-params params)
          (map (fn [[k _]] (name k)))
          (remove #(contains? resource-level-params %))
          (remove #(and text-search? (text-param? %)))
          (remove #(contains? registry %))
          distinct
          sort
          vec))))
