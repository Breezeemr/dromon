(ns com.breezeehr.fhir-json-transform
  "FHIR JSON malli transformer — decodes/encodes between FHIR JSON wire format
   and Clojure-native representation.

   Decode (FHIR JSON → Clojure):
   - string→java.time objects (dates, times, instants)
   - :extension array → named keys using schema metadata

   Encode (Clojure → FHIR JSON):
   - java.time objects → ISO strings
   - named extension keys → :extension array entries

   Usage:
     (m/decode schema json-value (fhir-json-transformer))
     (m/encode schema clj-value  (fhir-json-transformer))"
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [malli.experimental.time.transform :as mett]
            #?@(:cljs [["@js-joda/core" :as js-joda]]))
  #?(:clj (:import (java.time Year YearMonth))))

#?(:cljs (def ^:private Year (.-Year js-joda)))
#?(:cljs (def ^:private YearMonth (.-YearMonth js-joda)))

;; ---------------------------------------------------------------------------
;; Partial-precision temporal decode/encode (gYear / gYearMonth)
;; ---------------------------------------------------------------------------

(defn- safe-parse
  "String -> temporal via f; returns the input unchanged on parse failure so a
   malli :or branch falls through to the next precision (mirrors malli's -safe)."
  [f]
  (fn [x]
    (if (string? x)
      (try (f x) (catch #?(:clj Exception :cljs :default) _ x))
      x)))

(def ^:private year-decoder       (safe-parse #(Year/parse %)))
(def ^:private year-month-decoder (safe-parse #(YearMonth/parse %)))

(defn- precision-time-transformer
  "Adds decoders/encoders for the partial-precision FHIR temporal schemas
   :time/year and :time/year-month, which malli's time module does not provide.
   Composed alongside mett/time-transformer so an :or of date precisions resolves
   year -> year-month -> date -> dateTime."
  []
  (mt/transformer
    {:name     :time-precision
     :decoders {:time/year       year-decoder
                :time/year-month year-month-decoder}
     :encoders {:time/year       str
                :time/year-month str}}))

;; ---------------------------------------------------------------------------
;; FHIR uuid: the JSON wire form is the urn:uuid: URI
;;
;; FHIR types `uuid` as a URI, always written `urn:uuid:<uuid>`; the schema
;; models it as malli `:uuid`, so the Clojure-native value is a UUID. malli's
;; json-transformer maps `:uuid` both ways already, but on FHIR's terms both
;; directions are wrong: its decoder's regex rejects the urn: prefix (leaving
;; a string where the schema says UUID, so a spec-valid resource fails
;; validation), and its encoder emits a bare `<uuid>`, which is not a form
;; FHIR admits for the type.
;; ---------------------------------------------------------------------------

(def ^:private urn-uuid-prefix "urn:uuid:")

(def ^:private uuid-re
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn- ->uuid [s]
  #?(:clj  (java.util.UUID/fromString s)
     :cljs (uuid s)))

(defn- fhir-uuid-decoder
  "urn:uuid:<uuid> -> UUID. A bare <uuid> is left to the json-transformer,
   which already decodes it. Anything unparseable passes through unchanged
   so validation, not the transformer, reports it (see safe-parse)."
  [x]
  (if (and (string? x) (str/starts-with? x urn-uuid-prefix))
    (let [s (subs x (count urn-uuid-prefix))]
      (if (re-matches uuid-re s) (->uuid s) x))
    x))

(defn- fhir-uuid-encoder
  "UUID -> urn:uuid:<uuid>. Also prefixes a bare <uuid> string so the result
   does not depend on whether the json-transformer's `:uuid` encoder has
   already stringified the value. Idempotent: an already-prefixed string does
   not match `uuid-re`, so it is returned unchanged."
  [x]
  (cond
    (uuid? x) (str urn-uuid-prefix x)
    (and (string? x) (re-matches uuid-re x)) (str urn-uuid-prefix x)
    :else x))

;; ---------------------------------------------------------------------------
;; Shared helpers
;; ---------------------------------------------------------------------------

(defn- extension-fields
  "Extract extension field metadata from a map schema's children.
   Returns a seq of {:key k :url url :value-key vk} for each child
   that has :fhir/extension true in its entry properties."
  [schema]
  (keep (fn [[k props _child-schema]]
          (when (:fhir/extension props)
            {:key       k
             :url       (:url props)
             :value-key (:fhir/value-key props)}))
        (m/children schema)))

;; ---------------------------------------------------------------------------
;; Decode: FHIR JSON → Clojure-native
;; ---------------------------------------------------------------------------

(defn- promote-extensions
  "Move entries from the :extension array to named keys.

   For each extension field:
   - Finds entries in :extension matching the field's :url
   - If :value-key is set (bare value), extracts that key from each entry
     and collects into a vector
   - Otherwise passes the entry through as-is (single entry → map)"
  [m ext-fields]
  (if-let [ext-array (seq (:extension m))]
    (let [consumed-urls (into #{} (map :url) ext-fields)]
      (as-> m m'
        (reduce (fn [m' {:keys [key url value-key]}]
                  (let [entries (filterv #(= url (:url %)) ext-array)]
                    (if (seq entries)
                      (assoc m' key
                             (if value-key
                               (mapv #(get % value-key) entries)
                               (first entries)))
                      m')))
                m'
                ext-fields)
        (let [remaining (into [] (remove #(contains? consumed-urls (:url %))) ext-array)]
          (if (seq remaining)
            (assoc m' :extension remaining)
            (dissoc m' :extension)))))
    m))

(defn- fhir-extension-decoder
  "Compile-time decoder for map schemas with extension fields."
  [schema _options]
  (let [ext-fields (seq (extension-fields schema))]
    (when ext-fields
      {:enter (fn [m]
                (if (map? m)
                  (promote-extensions m ext-fields)
                  m))})))

;; ---------------------------------------------------------------------------
;; Encode: Clojure-native → FHIR JSON
;; ---------------------------------------------------------------------------

(defn- demote-extensions
  "Move named extension keys back into the :extension array.

   For each extension field present in the map:
   - If :value-key is set (bare value), wraps each value in an extension entry
     with :url and the value-key
   - Otherwise inserts the entry as-is into the :extension array"
  [m ext-fields]
  (let [present (filterv #(contains? m (:key %)) ext-fields)]
    (if (seq present)
      (let [ext-entries
            (into (vec (:extension m))
                  (mapcat (fn [{:keys [key url value-key]}]
                            (let [v (get m key)]
                              (if value-key
                                ;; Bare values → wrap each in extension entry
                                (mapv (fn [val] {:url url value-key val}) v)
                                ;; Complex extension → single entry as-is
                                [(assoc v :url url)]))))
                  present)
            ext-keys (into #{} (map :key) present)]
        (-> (apply dissoc m ext-keys)
            (assoc :extension ext-entries)))
      m)))

(defn- fhir-extension-encoder
  "Compile-time encoder for map schemas with extension fields."
  [schema _options]
  (let [ext-fields (seq (extension-fields schema))]
    (when ext-fields
      {:leave (fn [m]
                (if (map? m)
                  (demote-extensions m ext-fields)
                  m))})))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn fhir-json-transformer
  "Transformer that decodes/encodes between FHIR JSON and Clojure-native format.

   Decode handles:
   - :time/local-date        — ISO date string → LocalDate
   - :time/offset-date-time  — ISO datetime string → OffsetDateTime
   - :time/instant           — ISO instant string → Instant
   - :uuid                   — urn:uuid: URI → UUID
   - :extension promotion    — FHIR extension arrays → named keys

   Encode handles:
   - LocalDate/OffsetDateTime → ISO strings
   - UUID                     → urn:uuid: URI
   - named extension keys    → :extension arrays with value wrapping"
  []
  (mt/transformer
    (mt/json-transformer)
    (mett/time-transformer)
    (precision-time-transformer)
    {:name :fhir-uuid
     :decoders {:uuid fhir-uuid-decoder}
     :encoders {:uuid fhir-uuid-encoder}}
    {:name :fhir-extensions
     :decoders {:map {:compile fhir-extension-decoder}}
     :encoders {:map {:compile fhir-extension-encoder}}}))
