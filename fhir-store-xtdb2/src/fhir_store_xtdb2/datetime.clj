(ns fhir-store-xtdb2.datetime
  "Mapping between FHIR date/time primitive types and XTDB date/time column types.

   FHIR defines four date/time primitives (date, dateTime, instant, time) with
   varying precision. XTDB supports DATE, TIMESTAMP WITH TIMEZONE, and TIME.

   FHIR dateTime allows partial precision (year-only '2024', year-month '2024-01')
   which have no XTDB equivalent — these throw on conversion."
  (:import [java.time LocalDate LocalDateTime Instant OffsetDateTime
            ZonedDateTime LocalTime Year YearMonth ZoneOffset]
           [java.time.format DateTimeParseException]))

;; ---------------------------------------------------------------------------
;; FHIR → XTDB type mapping
;;
;;  FHIR primitive  | Java type        | XTDB type                 | Supported
;;  ───────────────────────────────────────────────────────────────────────────
;;  date            | LocalDate        | DATE                      | ✓
;;  dateTime        | OffsetDateTime   | TIMESTAMP WITH TIMEZONE   | ✓
;;  dateTime        | LocalDate        | DATE                      | ✓
;;  dateTime        | Instant          | TIMESTAMP WITH TIMEZONE   | ✓
;;  instant         | OffsetDateTime   | TIMESTAMP WITH TIMEZONE   | ✓
;;  instant         | Instant          | TIMESTAMP WITH TIMEZONE   | ✓
;;  time            | LocalTime        | TIME                      | ✓
;;  dateTime        | Year             | —                         | ✗
;;  dateTime        | YearMonth        | —                         | ✗
;; ---------------------------------------------------------------------------

(defn fhir->xtdb
  "Convert a FHIR date/time value to its XTDB-compatible type.
   Returns the value unchanged if already a supported type.
   Throws ex-info for types XTDB cannot represent (Year, YearMonth)."
  [value]
  (condp instance? value
    ;; Supported types — pass through unchanged to XTDB
    LocalDate       value
    OffsetDateTime  value
    Instant         value
    LocalTime       value

    ;; ZonedDateTime → OffsetDateTime (XTDB stores offset, not zone ID)
    ZonedDateTime   (.toOffsetDateTime ^ZonedDateTime value)

    ;; LocalDateTime has no timezone — promote to UTC offset for XTDB TIMESTAMP WITH TIMEZONE
    LocalDateTime   (.atOffset ^LocalDateTime value java.time.ZoneOffset/UTC)

    ;; Unsupported partial-precision types
    Year            (throw (ex-info (str "FHIR dateTime with year-only precision is not supported by XTDB. "
                                        "Value: " value)
                                   {:type :fhir-store-xtdb2/unsupported-datetime
                                    :fhir-primitive "dateTime"
                                    :java-type "Year"
                                    :value (str value)}))

    YearMonth       (throw (ex-info (str "FHIR dateTime with year-month precision is not supported by XTDB. "
                                        "Value: " value)
                                   {:type :fhir-store-xtdb2/unsupported-datetime
                                    :fhir-primitive "dateTime"
                                    :java-type "YearMonth"
                                    :value (str value)}))

    ;; Non-temporal values (strings, numbers, maps, etc.) — pass through
    value))

;; ---------------------------------------------------------------------------
;; String-to-native coercions for storage encoding
;;
;; When resources arrive via the transaction/batch endpoint their date fields
;; are raw JSON strings (the FHIR JSON transformer in Reitit coercion is not
;; applied).  These helpers parse FHIR date/time strings to java.time objects
;; so that XTDB stores them as native DATE / TIMESTAMP columns.
;; ---------------------------------------------------------------------------

(defn coerce-local-date
  "If `v` is already a LocalDate, return it.  If it is a FHIR date string
   (\"2024-01-15\"), parse it to LocalDate.  Otherwise return `v` unchanged."
  [v]
  (cond
    (instance? LocalDate v) v
    (string? v) (try (LocalDate/parse v)
                     (catch DateTimeParseException _ v))
    :else v))

(defn coerce-offset-date-time
  "If `v` is already an OffsetDateTime, return it.  If it is a FHIR dateTime
   string, parse it.  Otherwise return `v` unchanged."
  [v]
  (cond
    (instance? OffsetDateTime v) v
    (string? v) (try (OffsetDateTime/parse v)
                     (catch DateTimeParseException _
                       (try (.atOffset (Instant/parse v) ZoneOffset/UTC)
                            (catch DateTimeParseException _
                              (try (.atOffset (LocalDateTime/parse v) ZoneOffset/UTC)
                                   (catch DateTimeParseException _ v))))))
    :else v))

(defn coerce-instant
  "If `v` is already an Instant, return it.  If it is an ISO instant string,
   parse it.  Otherwise return `v` unchanged."
  [v]
  (cond
    (instance? Instant v) v
    (string? v) (try (Instant/parse v)
                     (catch DateTimeParseException _
                       (try (.toInstant (OffsetDateTime/parse v))
                            (catch DateTimeParseException _ v))))
    :else v))

(defn coerce-local-time
  "If `v` is already a LocalTime, return it.  If it is a time string,
   parse it.  Otherwise return `v` unchanged."
  [v]
  (cond
    (instance? LocalTime v) v
    (string? v) (try (LocalTime/parse v)
                     (catch DateTimeParseException _ v))
    :else v))

(defn xtdb->fhir
  "Convert an XTDB date/time column value back to the java.time type expected
   by the FHIR Malli schemas. XTDB returns DATE as LocalDate and
   TIMESTAMP WITH TIMEZONE as OffsetDateTime/Instant, which align with
   the :time/* malli schema types. No conversion needed."
  [value]
  value)

(defn fhir-temporal?
  "Returns true if value is a java.time type used by FHIR primitives."
  [value]
  (or (instance? LocalDate value)
      (instance? OffsetDateTime value)
      (instance? Instant value)
      (instance? LocalTime value)
      (instance? ZonedDateTime value)
      (instance? LocalDateTime value)
      (instance? Year value)
      (instance? YearMonth value)))

(defn validate-temporal
  "Validates that a FHIR temporal value can be stored in XTDB.
   Returns the value if valid, throws if unsupported (Year, YearMonth)."
  [value]
  (when (fhir-temporal? value)
    (fhir->xtdb value))
  value)

;; ---------------------------------------------------------------------------
;; Search date parsing — convert FHIR date search strings to native types
;; ---------------------------------------------------------------------------

(defn parse-search-date
  "Parse a FHIR date search string to a native date/time value and its
   precision-based upper bound for range queries.

   Returns {:lower <native> :upper <native> :precision :year|:month|:day|:instant}

   FHIR date search semantics: a partial date like '2024' implicitly means
   the range [2024-01-01, 2025-01-01). For native XTDB queries, we need
   both bounds.

   Throws for formats that cannot be parsed."
  [date-str]
  (let [len (count date-str)]
    (cond
      ;; Year only: "2024"
      (= len 4)
      (let [year (Integer/parseInt date-str)
            lower (LocalDate/of year 1 1)
            upper (LocalDate/of (inc year) 1 1)]
        {:lower lower :upper upper :precision :year})

      ;; Year-month: "2024-01"
      (and (= len 7) (= \- (nth date-str 4)))
      (let [ym (YearMonth/parse date-str)
            lower (.atDay ym 1)
            upper (.atDay (.plusMonths ym 1) 1)]
        {:lower lower :upper upper :precision :month})

      ;; Date: "2024-01-15"
      (= len 10)
      (let [d (LocalDate/parse date-str)
            upper (.plusDays d 1)]
        {:lower d :upper upper :precision :day})

      ;; DateTime with timezone: "2024-01-15T10:30:00Z" or "2024-01-15T10:30:00+05:00"
      (or (> len 10) (some #(= % \T) date-str))
      (let [odt (try
                  (OffsetDateTime/parse date-str)
                  (catch DateTimeParseException _
                    (try
                      ;; Try as instant (ends with Z)
                      (.atOffset (Instant/parse date-str) ZoneOffset/UTC)
                      (catch DateTimeParseException _
                        ;; Try as LocalDateTime (no timezone), assume UTC
                        (.atOffset (LocalDateTime/parse date-str) ZoneOffset/UTC)))))]
        {:lower odt :upper odt :precision :instant})

      :else
      (throw (ex-info (str "Cannot parse FHIR date search value: " date-str)
                      {:type :fhir-store-xtdb2/invalid-date-search
                       :value date-str})))))

(defn search-date-lower
  "Returns the lower bound native date value for a FHIR search date string."
  [date-str]
  (:lower (parse-search-date date-str)))

(defn search-date-upper
  "Returns the upper bound (exclusive) native date value for a FHIR search date string."
  [date-str]
  (:upper (parse-search-date date-str)))

