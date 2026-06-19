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

(defn build-storage-encoders
  "Build per-resource-type encoder functions from schemas.
   Returns a map of resource-type-string → (fn [resource] -> xtdb-doc).
   Includes a :default encoder built from :map for unknown resource types."
  [schemas]
  (let [default-xf (xtdb-storage-transformer nil)
        default-encoder (m/encoder :map default-xf)
        wrap (fn [enc] (fn [resource] (add-token-columns (enc resource))))
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
