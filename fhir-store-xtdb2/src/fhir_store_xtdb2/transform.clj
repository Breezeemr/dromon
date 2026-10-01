(ns fhir-store-xtdb2.transform
  "Malli transformers for converting FHIR resources to/from XTDB storage format.

   Built once at store initialization from the resource schemas. These replace
   the postwalk-based transformations with schema-aware malli encode/decode.

   Storage transformer (encode direction: FHIR → XTDB):
   - Root-level map: renames _-prefixed keys to primitive-ext- prefix (keywords)
   - Nested maps: converts keyword keys to strings (preserves camelCase in XTDB structs)
   - Temporal values: coerces to XTDB-native types via datetime/fhir->xtdb

   Read transformer (decode direction: XTDB → FHIR):
   - Root-level map: renames primitive-ext- keys back to _-prefixed
   - Nested maps: converts string keys back to keywords
   - Temporal: ZonedDateTime → OffsetDateTime"
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [clojure.string :as str]
            [com.breezeehr.fhir-json-transform :as fjt]
            [fhir-store-xtdb2.datetime :as dt])
  (:import [java.time ZonedDateTime]))

(def ^:private pext-prefix "primitive-ext-")

(defn- fhir-key->xtdb-key
  "Rename FHIR primitive extension keys (_birthDate etc.) to XTDB-safe column names."
  [k]
  (let [n (name k)]
    (if (and (str/starts-with? n "_") (not= n "_id"))
      (keyword (str pext-prefix (subs n 1)))
      k)))

(defn root-column-name
  "The column name the storage encoder gives a root-level resource key, as a
   string: `:_birthDate` -> \"primitive-ext-birthDate\", every other key (and
   `_id`) by its name, case preserved."
  [k]
  (name (fhir-key->xtdb-key k)))

(defn- xtdb-key->fhir-key
  "Reverse the primitive extension key renaming."
  [k]
  (let [n (name k)]
    (if (str/starts-with? n pext-prefix)
      (keyword (str "_" (subs n (count pext-prefix))))
      k)))

(defn- deref-schema-type
  "Returns the underlying type of a malli schema, dereferencing :malli.core/schema
   wrappers as needed."
  [schema]
  (loop [s schema]
    (let [t (try (m/type s) (catch Exception _ nil))]
      (cond
        (nil? t) nil
        (= t :malli.core/schema)
        (let [children (try (m/children s) (catch Exception _ nil))]
          (if (seq children)
            (recur (first children))
            nil))
        :else t))))

(defn- or-has-time-type?
  "Returns true if an :or schema has at least one :time/* child."
  [schema]
  (some (fn [child]
          (let [t (deref-schema-type child)]
            (and t (#{:time/local-date :time/offset-date-time :time/instant :time/local-time} t))))
        (m/children schema)))

(defn- or-time-coercer
  "Returns a coercion function that tries to parse a string value to the
   best-matching java.time type found in the :or schema's children."
  [schema]
  (let [types (into #{}
                    (keep (fn [child]
                            (deref-schema-type child)))
                    (m/children schema))]
    (fn [v]
      (if-not (string? v)
        ;; Already a java.time or non-temporal value — run through fhir->xtdb
        (dt/fhir->xtdb v)
        ;; String value — try each applicable parser in specificity order.
        ;; Each coerce-* returns the string unchanged if parsing fails,
        ;; so we try them sequentially until one succeeds.
        (let [parsers (cond-> []
                        (contains? types :time/offset-date-time) (conj dt/coerce-offset-date-time)
                        (contains? types :time/instant)          (conj dt/coerce-instant)
                        (contains? types :time/local-date)       (conj dt/coerce-local-date)
                        (contains? types :time/local-time)       (conj dt/coerce-local-time))
              parsed (reduce (fn [val parser]
                               (if (string? val)
                                 (parser val)
                                 (reduced val)))
                             v
                             parsers)]
          (dt/fhir->xtdb parsed))))))

(defn- xtdb-storage-transformer
  "Build a malli transformer for encoding FHIR resources into XTDB storage format.
   `root-resource-type` identifies the top-level resource (e.g. \"Patient\") so
   the compile function can distinguish root maps (keyword keys with _ renaming)
   from nested maps (string keys)."
  [root-resource-type]
  (mt/transformer
    {:name :xtdb-storage
     :encoders
     {:map {:compile
            (fn [schema _opts]
              (let [rt (:resourceType (m/properties schema))
                    root? (= rt root-resource-type)]
                {:leave
                 (fn [m]
                   (if-not (map? m) m
                     (into {}
                           (map (fn [[k v]]
                                  [(if root?
                                     (fhir-key->xtdb-key k)
                                     (if (keyword? k) (name k) k))
                                   v]))
                           m)))}))}
      :or {:compile
           (fn [schema _opts]
             (when (or-has-time-type? schema)
               {:leave (or-time-coercer schema)}))}
      :time/local-date       {:compile (fn [_ _] {:leave (comp dt/fhir->xtdb dt/coerce-local-date)})}
      :time/offset-date-time {:compile (fn [_ _] {:leave (comp dt/fhir->xtdb dt/coerce-offset-date-time)})}
      :time/instant          {:compile (fn [_ _] {:leave (comp dt/fhir->xtdb dt/coerce-instant)})}
      :time/local-time       {:compile (fn [_ _] {:leave (comp dt/fhir->xtdb dt/coerce-local-time)})}}}))

(defn- xtdb-read-transformer
  "Build a malli transformer for decoding XTDB records back to FHIR format.
   `root-resource-type` identifies the top-level resource so the compile function
   can apply primitive-ext- → _ key renaming at the root and string → keyword
   keywordizing at nested levels."
  [root-resource-type]
  (mt/transformer
    {:name :xtdb-read
     :decoders
     {:map {:compile
            (fn [schema _opts]
              (let [rt (:resourceType (m/properties schema))
                    root? (= rt root-resource-type)]
                {:enter
                 (fn [m]
                   (if-not (map? m) m
                     (into {}
                           (map (fn [[k v]]
                                  [(if root?
                                     (xtdb-key->fhir-key k)
                                     (if (string? k) (keyword k) k))
                                   v]))
                           m)))}))}
      :time/offset-date-time {:compile (fn [_ _]
                                {:enter (fn [v]
                                          (if (instance? ZonedDateTime v)
                                            (.toOffsetDateTime ^ZonedDateTime v)
                                            v))})}}}))

;; ---------------------------------------------------------------------------
;; Unpaired UTF-16 surrogates
;;
;; A Java string can hold a surrogate code unit with no partner (the JSON
;; escape "\uD83D" on its own decodes to one), but UTF-8 has no encoding for
;; it. Every String -> UTF-8 step between the store and XTDB's storage swaps it
;; for "?" without complaint. Verified on 2.2.0-beta3: pgjdbc's parameter
;; encoding does it to every [:sql ...] arg, and XTDB's Arrow Utf8Vector does
;; it on the server for :put-docs, whose transit payload still carries the
;; code unit intact. The storage encoder above leaves strings untouched. Once
;; written, the "?" is indistinguishable from a real one, so the store refuses
;; such a value instead (see core/refuse-unencodable-text!). Reads and searches
;; take the same pgjdbc path, where a "?" would MATCH a stored one, so they
;; refuse it too (see core/refuse-unencodable-input!).
;; ---------------------------------------------------------------------------

(defn unpaired-surrogate?
  "True when `s` holds a UTF-16 surrogate code unit that is not half of a
   high-low pair, i.e. text UTF-8 cannot encode."
  [^String s]
  (let [n (.length s)]
    (loop [i 0]
      (if (< i n)
        (let [c (.charAt s i)]
          (cond
            (Character/isHighSurrogate c)
            (if (and (< (inc i) n) (Character/isLowSurrogate (.charAt s (inc i))))
              (recur (+ i 2))
              true)

            (Character/isLowSurrogate c)
            true

            :else
            (recur (inc i))))
        false))))

(defn unencodable?
  "True for a string or keyword whose text holds an unpaired surrogate; false
   for anything else, nil included."
  [x]
  (cond
    (string? x)  (unpaired-surrogate? x)
    (keyword? x) (or (some-> (namespace x) unpaired-surrogate?)
                     (unpaired-surrogate? (name x)))
    :else        false))

(defn- render-path
  "`root-name` followed by `segments`, which the walk keeps innermost first:
   an index renders as `[i]`, a key as `.name`."
  [root-name segments]
  (apply str root-name
         (map (fn [seg]
                (cond
                  (int? seg)     (str "[" seg "]")
                  (keyword? seg) (str "." (name seg))
                  :else          (str "." seg)))
              (reverse segments))))

(defn unpaired-surrogate-paths
  "FHIRPath-style paths (`Practitioner.name[0].family`), rooted at
   `root-name`, of every element in `resource` whose value holds an unpaired
   surrogate, in walk order without repeats. An element whose NAME holds one
   is reported at its parent's path, so no path ever carries the offending
   text. Empty when the resource has none.

   Runs on every write, so a path is rendered only when something is found."
  [root-name resource]
  (letfn [(walk [acc segments v]
            (cond
              (map? v)
              (reduce-kv (fn [acc k v']
                           (if (unencodable? k)
                             (conj! acc (render-path root-name segments))
                             (walk acc (conj segments k) v')))
                         acc v)

              (sequential? v)
              (reduce-kv (fn [acc i v'] (walk acc (conj segments i) v'))
                         acc (vec v))

              (unencodable? v)
              (conj! acc (render-path root-name segments))

              :else acc))]
    (into [] (distinct) (persistent! (walk (transient []) () resource)))))

;; ---------------------------------------------------------------------------
;; Denormalized token columns
;;
;; Token searches on Coding/CodeableConcept fields (e.g. Observation.code,
;; Observation.category) otherwise compile to a correlated EXISTS over
;; UNNEST(field.coding) -- an array of structs. Under ORDER BY (which defeats
;; LIMIT early-termination) XTDB must evaluate that per row over the whole match
;; set, which is expensive (a multi-value OR becomes one mark-join per value).
;; We denormalize each top-level Coding/CodeableConcept field into a flat
;; `<field>_tokens` text[] of "code" and "system|code" strings so the search
;; layer can match a scalar array with a single UNNEST ... IN (...) instead.
;; ---------------------------------------------------------------------------

(defn- coding->tokens
  "Flat token string(s) for one Coding map (string keys, post-encode): the bare
   code. Only the bare code is stored -- adding \"system|code\" would double the
   array and slow the common bare-code search; system-qualified token searches
   fall back to the struct path (see core/token->needle)."
  [c]
  (when (map? c)
    (when-let [code (get c "code")]
      [code])))

(defn- value->tokens
  "Extracts the flat token strings from a top-level field value that is a
   CodeableConcept, a Coding, or an array of either. Returns nil for any other
   value (Quantity carries a \"code\" too, but also a \"value\", so it is
   excluded)."
  [v]
  (letfn [(cc->toks [cc] (when (map? cc) (mapcat coding->tokens (get cc "coding"))))
          (one [x] (cond
                     (and (map? x) (contains? x "coding"))
                     (cc->toks x)
                     (and (map? x) (contains? x "code") (not (contains? x "value")))
                     (coding->tokens x)
                     :else nil))]
    (let [ts (cond
               (sequential? v) (mapcat one v)
               (map? v)        (one v)
               :else           nil)]
      (when (seq ts) (vec (distinct ts))))))

(defn- stringify-nested-keys
  "Recursively converts keyword map keys to strings below the document root.
   XTDB case-folds keyword struct keys like unquoted SQL identifiers
   (:ombCategory is stored as ombcategory) while string keys are stored
   case-exactly. The schema-driven :map encoder already stringifies keys for
   schema-covered entries; this walk covers nested maps the schema walk does
   not reach -- promoted extension structs on resources dispatched to a schema
   branch without those entries (e.g. us-core race/ethnicity on the base R4B
   Patient branch), unknown keys, and the :default open-map encoder."
  [v]
  (cond
    (map? v)
    (persistent!
      (reduce-kv (fn [acc k v']
                   (assoc! acc (if (keyword? k) (name k) k)
                           (stringify-nested-keys v')))
                 (transient {}) v))

    (sequential? v)
    (mapv stringify-nested-keys v)

    :else v))

(defn- stringify-doc-nested-keys
  "Applies stringify-nested-keys to every top-level value of an encoded doc.
   Root keys stay as-is: they become column names, which the SQL builder
   splices double-quoted (case-exact) itself."
  [doc]
  (persistent!
    (reduce-kv (fn [acc k v]
                 (assoc! acc k (stringify-nested-keys v)))
               (transient {}) doc)))

(defn- add-token-columns
  "Adds a `<field>_tokens` text[] column for every top-level Coding/
   CodeableConcept field in the encoded doc, so token search can match a flat
   scalar array instead of UNNEST-ing an array of structs."
  [doc]
  (reduce-kv (fn [acc k v]
               (if (str/ends-with? (name k) "_tokens")
                 acc
                 (if-let [toks (value->tokens v)]
                   (assoc acc (keyword (str (name k) "_tokens")) toks)
                   acc)))
             doc doc))

;; ---------------------------------------------------------------------------
;; Declared columns
;;
;; From XTDB 2.2.0-rc0 a read that names a table or column nothing has ever
;; written fails at planning ("Table not found" / "Column not found") instead
;; of answering empty. The store declares every schema type's table with
;; `CREATE TABLE` when a node starts (see core/declare-tables!); this derives
;; the column list from the same malli schemas the encoders are built from.
;; ---------------------------------------------------------------------------

(def ^:private token-ref-types
  "FHIR datatypes add-token-columns denormalizes into `<field>_tokens`, and the
   search layer reads `<col>_tokens` for (see core/flat-token-columns)."
  #{"CodeableConcept" "Coding"})

(def ^:private token-value-keys
  "Promoted-extension :fhir/value-key values the search registry types as
   CodeableConcept / Coding."
  #{:valueCodeableConcept :valueCoding})

(defn- schema-entries
  "The [key props child-schema] entries of a resource schema: a :map's
   children, the union of every variant of a :multi (one variant per profile,
   so profile-added and promoted-extension entries are all included), and
   :ref / :schema / :and wrappers dereferenced. Anything else has none."
  ([schema] (schema-entries schema 0))
  ([schema depth]
   (when (< depth 8)
     (let [t (try (m/type schema) (catch Exception _ nil))]
       (case t
         :map   (m/children schema)
         :multi (mapcat (fn [[_ _ variant]] (schema-entries variant (inc depth)))
                        (m/children schema))
         :and   (mapcat #(schema-entries % (inc depth)) (m/children schema))
         (:malli.core/schema :schema :ref)
         (when-let [inner (try (m/deref schema) (catch Exception _ nil))]
           (when-not (identical? inner schema)
             (schema-entries inner (inc depth))))
         nil)))))

(defn- token-ref?
  "True when an entry schema is a :ref to CodeableConcept or Coding, or a
   :sequential / :maybe of one. Mirrors the search registry's classification,
   which names a :ref by the last segment of its key's namespace
   (:org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0 -> CodeableConcept)."
  ([schema] (token-ref? schema 0))
  ([schema depth]
   (when (< depth 4)
     (let [t (try (m/type schema) (catch Exception _ nil))]
       (case t
         (:sequential :maybe)
         (token-ref? (first (m/children schema)) (inc depth))

         :ref
         (let [k (first (m/children schema))]
           (boolean
            (when-let [ns-str (and (keyword? k) (namespace k))]
              (contains? token-ref-types (last (str/split ns-str #"\."))))))

         :malli.core/schema
         (token-ref? (first (m/children schema)) (inc depth))

         false)))))

(defn- entry-columns
  "Column names one schema entry stores: its root column, plus `<col>_tokens`
   when the entry is a CodeableConcept / Coding (by :ref, or a promoted
   extension whose value key is one)."
  [[k props child]]
  (when (or (keyword? k) (string? k))
    (let [col (root-column-name k)]
      (if (or (token-value-keys (:fhir/value-key props))
              (token-ref? child))
        [col (str col "_tokens")]
        [col]))))

(defn- registry-param-columns
  "Column names one search-registry entry makes the search SQL read: each
   root `:col`, plus `<col>_tokens` for a token parameter whose columns are
   all top-level CodeableConcept / Coding (the shape core/flat-token-columns
   answers from the token array). Names starting with `_` are XTDB system
   columns (`_id`, `_system_from`) and are never declared; neither is a :col
   that is not a plain name (an unparsed FHIRPath fragment), which no write
   could ever produce.

   A presence entry reads its :exists-not-false columns. A composite reads,
   per scope, the element column it UNNESTs, or for the resource-level scope
   each component's columns."
  [{:keys [type columns exists-not-false composite]}]
  (let [flat-token? (and (= "token" type)
                         (seq columns)
                         (every? #(and (#{"CodeableConcept" "Coding"} (:fhir-type %))
                                       (not (:sub-col %)))
                                 columns))
        plain? #(and (string? %) (re-matches #"[A-Za-z][A-Za-z0-9_-]*" %))]
    (concat
     (for [{:keys [col]} columns
           :when (plain? col)
           c (if flat-token? [col (str col "_tokens")] [col])]
       c)
     (for [{:keys [col]} exists-not-false
           :when (plain? col)]
       col)
     (for [{:keys [element components]} composite
           col (if element
                 [(:col element)]
                 (for [component components, c (:columns component)] (:col c)))
           :when (plain? col)]
       col))))

(defn- registry-columns
  "Column names the schema's own `:fhir/search-registry` property (attached
   by server.core/capability-schema->server-schema) makes searches read.

   A registry can name a column the schema lacks: a shared SearchParameter
   such as clinical-identifier lists other types' paths, and the registry
   resolves each against this type. Before XTDB 2.2.0-rc0 such a column read
   as null, so the OR'd alternative simply never matched; from rc0 it fails
   the whole query at planning, so searching on the parameter's real column
   answered empty. Declared, it plans as an untyped null again."
  [schema]
  (let [registry (:fhir/search-registry (try (m/properties schema) (catch Exception _ nil)))]
    (when (map? registry)
      (mapcat registry-param-columns (vals registry)))))

(def store-columns
  "Columns the store itself reads on every resource type, whatever its schema
   enumerates: `_id` and `fhir_version` (written by every store write and read
   by every current-version check and ASSERT) and `meta` (read by the
   resource-level `_tag`, `_profile` and `_security` searches, see
   core/resource-level-param?)."
  ["_id" "fhir_version" "meta"])

(defn declared-columns
  "Column names to declare for one resource schema, in order: store-columns,
   then each top-level entry under its storage name, with a `<col>_tokens`
   column after each CodeableConcept / Coding entry, then every column the
   schema's search registry reads that the entries did not already name (see
   registry-columns). Distinct, and never empty."
  [schema]
  (into [] (distinct)
        (concat store-columns
                (mapcat entry-columns (schema-entries schema))
                (registry-columns schema))))

(defn build-declared-columns
  "resource-type string -> declared column vector, for every schema that names
   its :resourceType (the same keying as build-storage-encoders). Two schemas
   for one type have their columns merged."
  [schemas]
  (reduce (fn [acc schema]
            (if-let [rt (:resourceType (try (m/properties schema) (catch Exception _ nil)))]
              (update acc rt (fn [cols]
                               (into [] (distinct) (concat cols (declared-columns schema)))))
              acc))
          {}
          schemas))

(defn build-storage-encoders
  "Build per-resource-type encoder functions from schemas.
   Returns a map of resource-type-string → (fn [resource] -> xtdb-doc).
   Includes a :default encoder built from :map for unknown resource types."
  [schemas]
  (let [default-xf (xtdb-storage-transformer nil)
        default-encoder (m/encoder :map default-xf)
        wrap (fn [enc]
               (fn [resource]
                 (add-token-columns (stringify-doc-nested-keys (enc resource)))))
        type-encoders (into {}
                            (keep (fn [schema]
                                    (let [rt (:resourceType (m/properties schema))]
                                      (when rt
                                        (let [xf (xtdb-storage-transformer rt)]
                                          [rt (wrap (m/encoder schema xf))])))))
                            schemas)]
    (assoc type-encoders :default (wrap default-encoder))))

(defn build-read-decoders
  "Build per-resource-type decoder functions from schemas.

   Each decoder runs the XTDB→FHIR storage transform first, then composes
   the FHIR JSON transformer's *encode* direction so promoted extension
   fields are demoted back into the canonical `:extension` array shape.
   Without this second step, search/history responses (which bypass
   reitit's response coercion) would expose the internal promoted shape
   to clients.

   Returns a map of resource-type-string → (fn [xtdb-record] -> fhir-resource).
   Includes a :default decoder built from :map for unknown resource types."
  [schemas]
  (let [fhir-xf (fjt/fhir-json-transformer)
        default-xf (xtdb-read-transformer nil)
        default-decoder (m/decoder :map default-xf)
        type-decoders (into {}
                            (keep (fn [schema]
                                    (let [rt (:resourceType (m/properties schema))]
                                      (when rt
                                        (let [xf (xtdb-read-transformer rt)
                                              storage-decode (m/decoder schema xf)
                                              fhir-encode    (m/encoder schema fhir-xf)]
                                          [rt (fn [record]
                                                (-> record storage-decode fhir-encode))])))))
                            schemas)]
    (assoc type-decoders :default default-decoder)))
