(ns fhir-store-xtdb2.core
  (:require [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [next.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [taoensso.telemere :as t]
            [cheshire.core :as json]
            [cheshire.generate :as json-gen]
            [integrant.core :as ig]
            [fhir-store.protocol :as fp :refer [IFHIRStore]]
            [fhir-store-xtdb2.datetime :as dt]
            [fhir-store-xtdb2.transform :as xf])
  (:import [java.time LocalDate LocalDateTime Instant OffsetDateTime ZonedDateTime LocalTime Year YearMonth]
           [com.zaxxer.hikari HikariConfig HikariDataSource]))

(defn- method-order
  "Returns sort key for FHIR transaction entry processing order per §3.1.0.11.2:
   DELETE (0) → POST (1) → PUT/PATCH (2) → GET/HEAD (3)."
  [method]
  (case (str/upper-case method)
    "DELETE" 0
    "POST" 1
    "PUT" 2
    "PATCH" 2
    "GET" 3
    "HEAD" 3
    4))

(defn- build-urn-uuid-mapping
  "Builds a map from urn:uuid: fullUrls to ResourceType/assigned-id for all
   transaction bundle entries that have a urn:uuid: fullUrl."
  [entries]
  (into {}
        (keep (fn [{:keys [fullUrl resource-type id]}]
                (when (and fullUrl (str/starts-with? fullUrl "urn:uuid:"))
                  [fullUrl (str resource-type "/" id)])))
        entries))

(defn- resolve-urn-uuid-references
  "Walks a resource body, replacing any string value that starts with 'urn:uuid:'
   with its resolved ResourceType/id from the mapping. Leaves unresolvable
   urn:uuid: references as-is."
  [resource urn-mapping]
  (if (empty? urn-mapping)
    resource
    (walk/postwalk
     (fn [x]
       (if (string? x)
         (get urn-mapping x x)
         x))
     resource)))

;; Register Cheshire encoders for java.time types so they serialize as ISO strings
(json-gen/add-encoder LocalDate (fn [d jg] (.writeString jg (str d))))
(json-gen/add-encoder LocalDateTime (fn [d jg] (.writeString jg (str d))))
(json-gen/add-encoder Instant (fn [d jg] (.writeString jg (str d))))
(json-gen/add-encoder OffsetDateTime (fn [d jg] (.writeString jg (str d))))
(json-gen/add-encoder ZonedDateTime (fn [d jg] (.writeString jg (str d))))
(json-gen/add-encoder LocalTime (fn [d jg] (.writeString jg (str d))))
(json-gen/add-encoder Year (fn [d jg] (.writeString jg (str d))))
(json-gen/add-encoder YearMonth (fn [d jg] (.writeString jg (str d))))


(defn ^:no-doc table-name
  "SQL table identifier for a resource type: the LOWERCASED name, double-quoted.
   xtdb SQL folds unquoted identifiers to lowercase, so every existing database
   (written historically with unquoted `INSERT INTO Patient`) stores lowercase
   table names -- quoting the lowercased form resolves to the same tables AND
   parses for resource types that collide with reserved words (`Flag` failed to
   parse entirely when spliced unquoted). Use this for every table splice; a
   quoted mixed-case form like \"Patient\" would be a DIFFERENT table."
  [resource-type]
  (str "\"" (str/lower-case (name resource-type)) "\""))

(defn ^:no-doc encode-resource-doc
  "Runs the malli storage encoder for the given resource type and returns the
   raw XTDB document map (pre-SQL-serialization) with :_id and :fhir_version
   injected. Used by both the SQL INSERT builder and the put-docs path —
   the put-docs path renames :_id → :xt/id before handing the doc to XTDB."
  [resource-type id resource-map storage-encoders & {:keys [version]}]
  (let [rt-name (name resource-type)
        encode-fn (get storage-encoders rt-name (get storage-encoders :default))]
    (cond-> (encode-fn resource-map)
      true    (assoc :_id id)
      version (assoc :fhir_version version))))

(defn ^:no-doc doc->put-doc
  "Rewrites a storage doc (as produced by encode-resource-doc) into the shape
   [:put-docs table doc] expects: :_id becomes :xt/id. All other keys pass
   through unchanged so the column types match the SQL path byte-for-byte."
  [doc]
  (-> doc
      (dissoc :_id)
      (assoc :xt/id (:_id doc))))

(defn ^:no-doc extract-and-build-sql
  "Takes a resource map and resource-type, builds a parameterized SQL INSERT for XTDB.
   Optional kwargs:
     :version — the monotonic version string to inject as the \"fhir_version\" column.
   Public (no-doc) so bulk importers (fhir-datomic-decant's xtdb target) reuse the
   exact document/SQL shape the live store writes."
  [resource-type id resource-map storage-encoders & {:keys [version]}]
  (let [doc (encode-resource-doc resource-type id resource-map storage-encoders :version version)
        cols (keys doc)
        col-names (str/join ", " (map #(format "\"%s\"" (name %)) cols))
        placeholders (str/join ", " (repeat (count cols) "?"))
        sql (format "INSERT INTO %s (%s) VALUES (%s)" (table-name resource-type) col-names placeholders)
        args (mapv doc cols)]
    [sql args]))

(defn ^:no-doc current-version
  "Reads the current fhir_version column for a resource row. Returns the version
   string or nil if no row exists."
  [node resource-type id]
  (let [query (format "SELECT fhir_version FROM %s WHERE _id = ?" (table-name resource-type))
        row (first (xt/q node [query id]))]
    (when row
      (when-let [v (or (:fhir-version row) (:fhir_version row) (get row "fhir_version"))]
        (str v)))))

(defn ^:no-doc current-versions-bulk
  "Bulk variant of current-version: takes a collection of ids for a single
   resource type and returns a map of id -> version string for rows that
   exist. Missing ids are absent from the map. Used by transact-transaction
   to avoid N sequential round-trips when building PUT version numbers, and
   by bulk importers to seed their version caches on resume."
  [node resource-type ids]
  (let [ids (distinct ids)]
    (if (empty? ids)
      {}
      ;; = ANY(?) with a single array param keeps the SQL text stable regardless
      ;; of id count, so it hits XTDB's plan cache instead of recompiling per size.
      (let [query (format "SELECT _id, fhir_version FROM %s WHERE _id = ANY(?)"
                          (table-name resource-type))
            rows (xt/q node [query (vec ids)])]
        (into {}
              (keep (fn [row]
                      (let [rid (or (:xt/id row) (:_id row) (get row "_id"))
                            v (or (:fhir-version row) (:fhir_version row) (get row "fhir_version"))]
                        (when (and rid v) [(str rid) (str v)]))))
              rows)))))

(declare xtdb->fhir)

;; Forward declarations for the optional XTQL pathway. Defined in
;; fhir-store-xtdb2.query-xtql, which requires this ns. Under :query-mode :xtql
;; the protocol methods dispatch to these; under :sql (default) they are unused.
(declare read-xtql vread-xtql deleted?-xtql history-xtql history-type-xtql
         search-xtql count-resources-xtql
         create-xtql update-xtql delete-xtql transact-transaction-xtql)

(defn- bulk-read-by-ids
  "Bulk SELECT * WHERE _id IN (...) for a single resource type. Returns a
   map of id -> decoded FHIR resource. Used by transact-transaction to
   avoid N post-commit round-trips when building the transaction response."
  [node read-decoders resource-type ids]
  (let [ids (distinct ids)]
    (if (empty? ids)
      {}
      (let [query (format "SELECT *, _system_from FROM %s WHERE _id = ANY(?)"
                          (table-name resource-type))
            rows (xt/q node [query (vec ids)])]
        (into {}
              (keep (fn [row]
                      (let [res (xtdb->fhir row read-decoders)]
                        (when-let [rid (:id res)]
                          [(str rid) res]))))
              rows)))))

(defn ^:no-doc next-version
  "Computes the next version string from a current version (or nil for first)."
  [current]
  (if (and current (not (str/blank? current)))
    (if-let [n (parse-long current)]
      (str (inc n))
      "1")
    "1"))

(defn ^:no-doc tx-key->basis
  "Store basis of a committed transaction, from the xt/execute-tx return
   (an xtdb TxKey). tx-id is monotonically increasing per node."
  [tx-key]
  {:tx-id (:tx-id tx-key)
   :system-time (:system-time tx-key)})

(defn ^:no-doc with-basis
  "Attach the committed transaction's basis as :fhir-store/basis metadata
   on a write return value (see the IFHIRStore protocol docstring)."
  [ret tx-key]
  (vary-meta ret assoc :fhir-store/basis (tx-key->basis tx-key)))

(defn- inject-meta
  "Injects :meta :versionId and :meta :lastUpdated onto a decoded FHIR resource.
   XTDB returns _system_from as a ZonedDateTime whose str form carries a zone
   suffix (\"...Z[UTC]\") that is not a valid FHIR instant, so it is converted
   to an Instant first."
  [result version system-from]
  (cond-> result
    version     (assoc-in [:meta :versionId] (str version))
    system-from (assoc-in [:meta :lastUpdated]
                          (str (if (instance? ZonedDateTime system-from)
                                 (.toInstant ^ZonedDateTime system-from)
                                 system-from)))))

(defn- parse-date-prefix
  "Parses a FHIR date search value into [prefix date-string].
   FHIR date prefixes: eq, ne, lt, gt, ge, le, sa, eb, ap. Default is eq."
  [value-str]
  (let [prefixes #{"eq" "ne" "lt" "gt" "ge" "le" "sa" "eb" "ap"}
        maybe-prefix (when (>= (count value-str) 2) (subs value-str 0 2))]
    (if (contains? prefixes maybe-prefix)
      [maybe-prefix (subs value-str 2)]
      ["eq" value-str])))

(defn- build-date-condition
  "Builds a parameterized SQL condition for a date-type FHIR search parameter.
   Returns [sql-fragment params-vector].
   Uses native date types for comparison against XTDB DATE/TIMESTAMP columns."
  [column-name value-str]
  (let [[prefix date-val] (parse-date-prefix value-str)
        {:keys [lower upper precision]} (dt/parse-search-date date-val)
        col (format "\"%s\"" column-name)]
    (case prefix
      ;; eq: for partial dates (year/month), use range; for exact, use equality
      "eq" (if (= precision :instant)
             [(format "%s = ?" col) [lower]]
             [(format "(%s >= ? AND %s < ?)" col col) [lower upper]])
      ;; ne: inverse of eq
      "ne" (if (= precision :instant)
             [(format "%s <> ?" col) [lower]]
             [(format "(%s < ? OR %s >= ?)" col col) [lower upper]])
      "lt" [(format "%s < ?" col) [lower]]
      "gt" (if (= precision :instant)
             [(format "%s > ?" col) [lower]]
             [(format "%s >= ?" col) [upper]])
      "ge" [(format "%s >= ?" col) [lower]]
      "le" (if (= precision :instant)
             [(format "%s <= ?" col) [lower]]
             [(format "%s < ?" col) [upper]])
      "sa" (if (= precision :instant)
             [(format "%s > ?" col) [lower]]
             [(format "%s >= ?" col) [upper]])
      "eb" [(format "%s < ?" col) [lower]]
      "ap" (if (= precision :instant)
             [(format "%s = ?" col) [lower]]
             [(format "(%s >= ? AND %s < ?)" col col) [lower upper]])
      ;; fallback to eq
      (if (= precision :instant)
        [(format "%s = ?" col) [lower]]
        [(format "(%s >= ? AND %s < ?)" col col) [lower upper]]))))

;; ---------------------------------------------------------------------------
;; Type-driven SQL condition builders
;; ---------------------------------------------------------------------------

(defn- build-token-col-condition
  "Builds parameterized SQL for a single token-type column.
   Returns [sql-fragment params-vector].
   v-val is the raw code value, system-val is the raw system string or nil."
  [col v-val system-val]
  (let [col-name (:col col)
        fhir-type (:fhir-type col)
        array? (:array? col)
        sub-col (:sub-col col)
        sub-fhir-type (:sub-fhir-type col)
        sub-array? (:sub-array? col false)
        ;; Helper to build a coding WHERE clause with optional system
        coding-where (fn [alias]
                       (if system-val
                         [(format "(%s.val).\"system\" = ? AND (%s.val).\"code\" = ?" alias alias) [system-val v-val]]
                         [(format "(%s.val).\"code\" = ?" alias) [v-val]]))]
    (cond
      ;; Nested path (e.g., participant.role, hospitalization.dischargeDisposition)
      sub-col
      (if array?
        ;; Parent is array: UNNEST parent, then handle sub-col
        (if (and (or (= sub-fhir-type "CodeableConcept") (nil? sub-fhir-type)) sub-array?)
          ;; Sub is array of CodeableConcept: triple UNNEST
          (let [[cw-sql cw-params] (coding-where "code")]
            [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS p(val) WHERE EXISTS (SELECT 1 FROM UNNEST((p.val).\"%s\") AS r(val) WHERE EXISTS (SELECT 1 FROM UNNEST((r.val).\"coding\") AS code(val) WHERE %s))))"
                     col-name sub-col cw-sql)
             cw-params])
          ;; Sub is single CodeableConcept or unknown: UNNEST parent, struct access sub, UNNEST coding
          (let [[cw-sql cw-params] (coding-where "code")]
            [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS p(val) WHERE EXISTS (SELECT 1 FROM UNNEST((p.val).\"%s\".\"coding\") AS code(val) WHERE %s)))"
                     col-name sub-col cw-sql)
             cw-params]))
        ;; Parent is struct: struct access, then UNNEST coding
        (let [[cw-sql cw-params] (coding-where "c")]
          [(format "(EXISTS (SELECT 1 FROM UNNEST((\"%s\").\"%s\".\"coding\") AS c(val) WHERE %s))"
                   col-name sub-col cw-sql)
           cw-params]))

      ;; CodeableConcept
      (= fhir-type "CodeableConcept")
      (if array?
        (let [[cw-sql cw-params] (coding-where "code")]
          [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS c(val) WHERE EXISTS (SELECT 1 FROM UNNEST((c.val).\"coding\") AS code(val) WHERE %s)))"
                   col-name cw-sql)
           cw-params])
        (let [[cw-sql cw-params] (coding-where "c")]
          [(format "(EXISTS (SELECT 1 FROM UNNEST((\"%s\").\"coding\") AS c(val) WHERE %s))"
                   col-name cw-sql)
           cw-params]))

      ;; Identifier
      (= fhir-type "Identifier")
      (let [[id-sql id-params] (if system-val
                                 ["(i.val).\"system\" = ? AND (i.val).\"value\" = ?" [system-val v-val]]
                                 ["(i.val).\"value\" = ?" [v-val]])]
        [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS i(val) WHERE %s))"
                 col-name id-sql)
         id-params])

      ;; Coding (single or array)
      (= fhir-type "Coding")
      (if array?
        (let [[cw-sql cw-params] (coding-where "c")]
          [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS c(val) WHERE %s))" col-name cw-sql)
           cw-params])
        (if system-val
          [(format "(\"%s\").\"system\" = ? AND (\"%s\").\"code\" = ?" col-name col-name) [system-val v-val]]
          [(format "(\"%s\").\"code\" = ?" col-name) [v-val]]))

      ;; Default: simple equality (code, string, enum, etc.)
      :else
      [(format "\"%s\" = ?" col-name) [v-val]])))

(defn- build-reference-col-condition
  "Builds parameterized SQL for a single reference-type column.
   Returns [sql-fragment params-vector].
   v-val is the raw value string, target is the target types vector."
  [col v-val target]
  (let [col-name (:col col)
        fhir-type (:fhir-type col)
        array? (:array? col)
        sub-col (:sub-col col)
        ;; Determine the reference prefix from target (e.g., "Patient/")
        target-type (first target)
        with-prefix-val (when (and target-type (not (str/includes? v-val "/")))
                          (str target-type "/" v-val))]
    (cond
      ;; Canonical URL field (stored as plain string, not a Reference struct)
      (= fhir-type "canonical")
      [(format "\"%s\" = ?" col-name) [v-val]]

      ;; Nested reference in array (e.g., Encounter.location.location)
      (and sub-col array?)
      (if with-prefix-val
        [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS l(val) WHERE (l.val).\"%s\".\"reference\" = ? OR (l.val).\"%s\".\"reference\" = ?))"
                 col-name sub-col sub-col)
         [v-val with-prefix-val]]
        [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS l(val) WHERE (l.val).\"%s\".\"reference\" = ?))"
                 col-name sub-col)
         [v-val]])

      ;; Nested reference in struct
      sub-col
      (let [ref-expr (format "(\"%s\").\"%s\".\"reference\"" col-name sub-col)]
        (if with-prefix-val
          [(format "(%s = ? OR %s = ?)" ref-expr ref-expr) [v-val with-prefix-val]]
          [(format "%s = ?" ref-expr) [v-val]]))

      ;; Simple array of references
      array?
      (if with-prefix-val
        [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS r(val) WHERE (r.val).\"reference\" = ? OR (r.val).\"reference\" = ?))"
                 col-name)
         [v-val with-prefix-val]]
        [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS r(val) WHERE (r.val).\"reference\" = ?))"
                 col-name)
         [v-val]])

      ;; Simple reference field
      :else
      (if with-prefix-val
        [(format "((\"%s\").\"reference\" = ? OR (\"%s\").\"reference\" = ?)" col-name col-name) [v-val with-prefix-val]]
        [(format "(\"%s\").\"reference\" = ?" col-name) [v-val]]))))

(defn- build-period-condition
  "Builds a parameterized SQL condition for a Period-type column with date prefix logic.
   Returns [sql-fragment params-vector] or nil."
  [col-name value-str]
  (let [[prefix date-val] (parse-date-prefix value-str)
        {:keys [lower upper precision]} (dt/parse-search-date date-val)
        start-col (format "(\"%s\").\"start\"" col-name)]
    (case prefix
      ("gt" "sa") (if (= precision :instant)
                    [(format "%s > ?" start-col) [lower]]
                    [(format "%s >= ?" start-col) [upper]])
      ("lt" "eb") [(format "%s < ?" start-col) [lower]]
      "ge" [(format "%s >= ?" start-col) [lower]]
      "le" (if (= precision :instant)
             [(format "%s <= ?" start-col) [lower]]
             [(format "%s < ?" start-col) [upper]])
      "ne" nil ;; skip period matching for ne
      ;; eq/default: skip period matching to avoid false positives with Inferno
      nil)))

(defn- parse-quantity-value
  "Parses a FHIR quantity search value into {:prefix p :value BigDecimal
   :system s :code c}. Format: [prefix]number[|system|code], e.g. \"ge50\" or
   \"5.4|http://unitsofmeasure.org|mg\". The prefix set matches the date
   prefixes. Returns :value nil when the numeric part cannot be parsed."
  [value-str]
  (let [[prefix rest-str] (parse-date-prefix value-str)
        parts (str/split rest-str #"\|" -1)
        number (first parts)
        system (when (>= (count parts) 2) (nth parts 1))
        code (when (>= (count parts) 3) (nth parts 2))]
    {:prefix prefix
     :value (try (BigDecimal. (str/trim number)) (catch Exception _ nil))
     :system (when (and system (not (str/blank? system))) system)
     :code (when (and code (not (str/blank? code))) code)}))

(defn- build-quantity-col-condition
  "Builds a parameterized SQL condition for a single quantity-type column.
   Returns [sql-fragment params-vector] or nil. Compares the nested Quantity
   .value numerically with the FHIR prefix (eq/ne/gt/lt/ge/le/sa/eb/ap; ap is
   +/-10%); an optional |system|code constrains the unit. Columns without a
   numeric value (e.g. valueSampledData) still emit a .value comparison, which
   simply matches nothing for those rows."
  [col value-str]
  (let [col-name (:col col)
        {:keys [prefix value system code]} (parse-quantity-value value-str)]
    (when value
      (let [val-expr (format "(\"%s\").\"value\"" col-name)
            sys-expr (format "(\"%s\").\"system\"" col-name)
            code-expr (format "(\"%s\").\"code\"" col-name)
            value-cond (case prefix
                         "eq" [(format "%s = ?" val-expr) [value]]
                         "ne" [(format "%s <> ?" val-expr) [value]]
                         ("gt" "sa") [(format "%s > ?" val-expr) [value]]
                         "ge" [(format "%s >= ?" val-expr) [value]]
                         ("lt" "eb") [(format "%s < ?" val-expr) [value]]
                         "le" [(format "%s <= ?" val-expr) [value]]
                         "ap" [(format "(%s >= ? AND %s <= ?)" val-expr val-expr)
                               [(.multiply value 0.9M) (.multiply value 1.1M)]]
                         [(format "%s = ?" val-expr) [value]])
            conds (cond-> [value-cond]
                    system (conj [(format "%s = ?" sys-expr) [system]])
                    code   (conj [(format "%s = ?" code-expr) [code]]))]
        (if (= 1 (count conds))
          (first conds)
          [(str "(" (str/join " AND " (map first conds)) ")")
           (into [] (mapcat second) conds)])))))

(defn- build-quantity-condition
  "Builds a parameterized SQL condition for a quantity search parameter across
   its columns (OR), using the raw value string (the number is the first
   pipe-segment, so this must run before the token-style system|code split).
   Returns [sql-fragment params-vector] or nil."
  [search-param value-str]
  (let [col-conds (keep #(build-quantity-col-condition % value-str)
                        (:columns search-param))]
    (when (seq col-conds)
      (if (= 1 (count col-conds))
        (first col-conds)
        [(str "(" (str/join " OR " (map first col-conds)) ")")
         (into [] (mapcat second) col-conds)]))))

(defn- build-date-col-condition
  "Builds parameterized SQL for a single date-type column.
   Returns [sql-fragment params-vector] or nil."
  [col v-str]
  (let [col-name (:col col)
        fhir-type (:fhir-type col)
        array? (:array? col)
        sub-col (:sub-col col)
        sub-fhir-type (:sub-fhir-type col)
        extension-url (:extension-url col)
        extension-promoted? (:extension-promoted? col)]
    (cond
      ;; Extension date promoted to a top-level array of bare date/time values
      ;; (FHIR JSON transformer's value-key extraction): UNNEST and compare.
      (and extension-promoted? array?)
      (let [[prefix date-val] (parse-date-prefix v-str)
            {:keys [lower upper precision]} (dt/parse-search-date date-val)
            cmp (fn [op v]
                  [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS d(val) WHERE d.val %s ?))"
                           col-name op)
                   [v]])
            cmp-range (fn [v1 v2]
                        [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS d(val) WHERE d.val >= ? AND d.val < ?))"
                                 col-name)
                         [v1 v2]])]
        (case prefix
          "eq" (if (= precision :instant) (cmp "=" lower) (cmp-range lower upper))
          "ne" (if (= precision :instant) (cmp "<>" lower)
                   [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS d(val) WHERE d.val < ? OR d.val >= ?))"
                            col-name)
                    [lower upper]])
          "lt" (cmp "<" lower)
          "gt" (if (= precision :instant) (cmp ">" lower) (cmp ">=" upper))
          "ge" (cmp ">=" lower)
          "le" (if (= precision :instant) (cmp "<=" lower) (cmp "<" upper))
          "sa" (if (= precision :instant) (cmp ">" lower) (cmp ">=" upper))
          "eb" (cmp "<" lower)
          "ap" (if (= precision :instant) (cmp "=" lower) (cmp-range lower upper))
          (if (= precision :instant) (cmp "=" lower) (cmp-range lower upper))))

      ;; Extension date: search within the extension array for matching URL + valueDateTime
      (and extension-url (= col-name "extension"))
      (let [[_prefix date-val] (parse-date-prefix v-str)
            {:keys [lower upper precision]} (dt/parse-search-date date-val)]
        (if (= precision :instant)
          [(format "(EXISTS (SELECT 1 FROM UNNEST(\"extension\") AS e(val) WHERE (e.val).\"url\" = ? AND (e.val).\"valueDateTime\" = ?))")
           [extension-url lower]]
          [(format "(EXISTS (SELECT 1 FROM UNNEST(\"extension\") AS e(val) WHERE (e.val).\"url\" = ? AND (e.val).\"valueDateTime\" >= ? AND (e.val).\"valueDateTime\" < ?))")
           [extension-url lower upper]]))

      ;; Extension date with top-level field (schema-defined extension field)
      extension-url
      (build-date-condition col-name v-str)

      ;; Nested date in array (e.g., Goal.target.dueDate)
      (and sub-col array?)
      (let [[_prefix date-val] (parse-date-prefix v-str)
            {:keys [lower upper precision]} (dt/parse-search-date date-val)]
        (if (= precision :instant)
          [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS t(val) WHERE (t.val).\"%s\" = ?))"
                   col-name sub-col)
           [lower]]
          [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS t(val) WHERE (t.val).\"%s\" >= ? AND (t.val).\"%s\" < ?))"
                   col-name sub-col sub-col)
           [lower upper]]))

      ;; Nested date in struct
      sub-col
      (if (= sub-fhir-type "Period")
        ;; Period inside a struct: access start through nested struct path
        (let [[prefix date-val] (parse-date-prefix v-str)
              {:keys [lower upper precision]} (dt/parse-search-date date-val)
              start-expr (format "(\"%s\").\"%s\".\"start\"" col-name sub-col)]
          (case prefix
            ("gt" "sa") (if (= precision :instant)
                          [(format "%s > ?" start-expr) [lower]]
                          [(format "%s >= ?" start-expr) [upper]])
            ("lt" "eb") [(format "%s < ?" start-expr) [lower]]
            "ge" [(format "%s >= ?" start-expr) [lower]]
            "le" (if (= precision :instant)
                   [(format "%s <= ?" start-expr) [lower]]
                   [(format "%s < ?" start-expr) [upper]])
            "ne" nil
            nil))
        ;; Non-Period date in struct: use struct accessor syntax with native types
        (let [[prefix date-val] (parse-date-prefix v-str)
              {:keys [lower upper precision]} (dt/parse-search-date date-val)
              col-expr (format "(\"%s\").\"%s\"" col-name sub-col)]
          (case prefix
            "eq" (if (= precision :instant)
                   [(format "%s = ?" col-expr) [lower]]
                   [(format "(%s >= ? AND %s < ?)" col-expr col-expr) [lower upper]])
            "ne" (if (= precision :instant)
                   [(format "%s <> ?" col-expr) [lower]]
                   [(format "(%s < ? OR %s >= ?)" col-expr col-expr) [lower upper]])
            "lt" [(format "%s < ?" col-expr) [lower]]
            "gt" (if (= precision :instant)
                   [(format "%s > ?" col-expr) [lower]]
                   [(format "%s >= ?" col-expr) [upper]])
            "ge" [(format "%s >= ?" col-expr) [lower]]
            "le" (if (= precision :instant)
                   [(format "%s <= ?" col-expr) [lower]]
                   [(format "%s < ?" col-expr) [upper]])
            "sa" (if (= precision :instant)
                   [(format "%s > ?" col-expr) [lower]]
                   [(format "%s >= ?" col-expr) [upper]])
            "eb" [(format "%s < ?" col-expr) [lower]]
            "ap" (if (= precision :instant)
                   [(format "%s = ?" col-expr) [lower]]
                   [(format "(%s >= ? AND %s < ?)" col-expr col-expr) [lower upper]])
            ;; default eq
            (if (= precision :instant)
              [(format "%s = ?" col-expr) [lower]]
              [(format "(%s >= ? AND %s < ?)" col-expr col-expr) [lower upper]]))))

      ;; Period type
      (= fhir-type "Period")
      (build-period-condition col-name v-str)

      ;; dateTime, instant, date
      :else
      (build-date-condition col-name v-str))))

(defn- build-string-col-condition
  "Builds parameterized SQL for a single string-type column.
   Returns [sql-fragment params-vector].
   v-val is the raw string value."
  [col v-val]
  (let [col-name (:col col)
        fhir-type (:fhir-type col)
        array? (:array? col)
        sub-col (:sub-col col)]
    (cond
      ;; Nested string in array: e.g., Patient.name.family
      (and sub-col array?)
      [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS n(val) WHERE (n.val).\"%s\" = ?))"
               col-name sub-col)
       [v-val]]

      ;; Nested string in struct: e.g., Location.address.city
      sub-col
      [(format "(\"%s\").\"%s\" = ?" col-name sub-col) [v-val]]

      ;; HumanName array: search across family and given
      (and (= fhir-type "HumanName") array?)
      [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS n(val) WHERE (n.val).family = ? OR (n.val).given = ?) OR \"%s\" = ?)"
               col-name col-name)
       [v-val v-val v-val]]

      ;; Address array: search across city, state, postalCode
      (and (= fhir-type "Address") array?)
      [(format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS a(val) WHERE (a.val).city = ? OR (a.val).state = ? OR (a.val).postalCode = ?))"
               col-name)
       [v-val v-val v-val]]

      ;; Simple string
      :else
      [(format "\"%s\" = ?" col-name) [v-val]])))

(defn- build-typed-condition
  "Builds parameterized SQL condition for a single value using registry search-param metadata.
   Returns [sql-fragment params-vector] or nil.
   v-val is the raw value, system-val is the raw system string or nil."
  [search-param v-val system-val]
  (let [sp-type (:type search-param)
        target (:target search-param)
        columns (:columns search-param)
        col-conds (case sp-type
                    "token"     (keep #(build-token-col-condition % v-val system-val) columns)
                    "reference" (keep #(build-reference-col-condition % v-val target) columns)
                    "date"      (keep #(build-date-col-condition % v-val) columns)
                    "string"    (keep #(build-string-col-condition % v-val) columns)
                    ;; Fallback: simple equality on first column
                    (when-let [col (first columns)]
                      [[(format "\"%s\" = ?" (:col col)) [v-val]]]))]
    (when (seq col-conds)
      (if (= 1 (count col-conds))
        (first col-conds)
        ;; Multiple columns: OR them together, merging params
        (let [sqls (mapv first col-conds)
              params (into [] (mapcat second) col-conds)]
          [(str "(" (str/join " OR " sqls) ")") params])))))

(defn- flat-token-columns
  "When a token search-param's columns are all top-level (no sub-col)
   Coding/CodeableConcept fields, returns those columns -- each has a
   denormalized `<col>_tokens` array (see transform/add-token-columns).
   Otherwise nil (sub-col, Identifier, or simple-code token searches keep the
   struct path)."
  [search-param]
  (let [cols (:columns search-param)]
    (when (and (= "token" (:type search-param))
               (seq cols)
               (every? (fn [c] (and (#{"CodeableConcept" "Coding"} (:fhir-type c))
                                    (not (:sub-col c))))
                       cols))
      cols)))

(defn- token->needle
  "Maps one FHIR token value to its flat-array needle (a bare code). The
   denormalized array stores only bare codes, so any system-qualified value
   (`system|code` or `system|`) returns nil and the caller falls back to the
   struct path, which matches system + code precisely."
  [v]
  (let [parts (str/split v #"\|" -1)]
    (if (= 1 (count parts))
      (first parts)                       ; bare code
      (let [sys (first parts) code (second parts)]
        (if (and (str/blank? sys) (seq code))
          code                            ; |code -> bare
          nil)))))                        ; system|code / system| -> struct fallback

(defn- build-flat-token-condition
  "Flat-array token condition: one
   `EXISTS (SELECT 1 FROM UNNEST(\"<col>_tokens\") AS t(v) WHERE t.v IN (?...))`
   per column, OR'd across columns; comma-separated values become the IN list.
   Returns [sql params] or nil if any value is system-only (so the caller falls
   back to the struct path)."
  [cols value-str]
  (let [needles (mapv token->needle (str/split value-str #","))]
    (when (every? some? needles)
      (let [ph (str/join "," (repeat (count needles) "?"))
            per-col (map (fn [c]
                           [(format "EXISTS (SELECT 1 FROM UNNEST(\"%s_tokens\") AS t(v) WHERE t.v IN (%s))"
                                    (:col c) ph)
                            needles])
                         cols)]
        (if (= 1 (count per-col))
          (first per-col)
          [(str "(" (str/join " OR " (map first per-col)) ")")
           (vec (mapcat second per-col))])))))

(defn- build-condition-struct
  "Builds a parameterized SQL WHERE condition for a given FHIR search parameter.
   Returns [sql-fragment params-vector].
   Handles comma-separated values as OR (per FHIR spec), and pipe-delimited
   system|code token notation. When system|code format is used, both system
   and code are matched in the SQL condition.
   search-param is the enriched descriptor from the search registry (or nil)."
  [param-name param-value search-param]
  (let [param-str (if (keyword? param-value) (name param-value) (str param-value))
        comma-values (str/split param-str #",")]
    (if (> (count comma-values) 1)
      ;; Multiple comma-separated values: OR them together
      (let [conditions (map #(build-condition-struct param-name % search-param) comma-values)
            sqls (mapv first conditions)
            params (into [] (mapcat second) conditions)]
        [(str "(" (str/join " OR " sqls) ")") params])
      ;; Single value
      (let [val-parts (str/split param-str #"\|")
            v-str (last val-parts)
            system-str (when (= 2 (count val-parts)) (first val-parts))
            pname (name param-name)]
        (cond
          ;; Boolean values are not parameterized (SQL TRUE/FALSE literals)
          (boolean? param-value)
          [(format "\"%s\" = %s" pname (if param-value "TRUE" "FALSE")) []]

          ;; Special case: _id is always direct equality
          (= pname "_id")
          ["_id = ?" [v-str]]

          ;; Quantity: the number is the first pipe-segment (number|system|code),
          ;; so parse from the raw single value rather than the token-style
          ;; system|code split above.
          (and search-param (= "quantity" (:type search-param)))
          (or (build-quantity-condition search-param param-str)
              [(format "\"%s\" = ?" pname) [v-str]])

          ;; Use registry metadata when available
          search-param
          (or (build-typed-condition search-param v-str system-str)
              ;; Fallback if typed condition returns nil
              [(format "\"%s\" = ?" pname) [v-str]])

          ;; No registry entry: fallback to direct column match
          :else
          [(format "\"%s\" = ?" pname) [v-str]])))))

(defn- build-condition
  "Builds a parameterized SQL WHERE condition for a search parameter. For token
   searches on top-level Coding/CodeableConcept fields, uses the denormalized
   `<col>_tokens` array (a single scalar UNNEST ... IN) which is far cheaper than
   UNNEST-ing an array of structs under ORDER BY; everything else falls through
   to the struct path."
  [param-name param-value search-param]
  (let [param-str (if (keyword? param-value) (name param-value) (str param-value))]
    (or (when search-param
          (when-let [cols (flat-token-columns search-param)]
            ;; The flat array only beats the struct UNNEST when the struct would
            ;; do extra work: a comma-OR (one mark-join per value) or an array-of
            ;; -CodeableConcept column (a nested UNNEST). For a single value on a
            ;; single CodeableConcept the struct equality is already optimal and
            ;; slightly faster, so keep it.
            (when (or (str/includes? param-str ",")
                      (some :array? cols))
              (build-flat-token-condition cols param-str))))
        (build-condition-struct param-name param-value search-param))))
(defn- drop-empty-sequentials
  "Recursively removes map entries whose value is an empty sequential collection
   (`[]` or an empty list). FHIR wire semantics require a repeating element with
   zero entries to be OMITTED, not serialized as `[]`; a column stored as an
   empty array (e.g. Claim.insurance) would otherwise decode back to `[]` and
   diverge from the datomic read surface, which carries no such key. Only
   sequential emptiness is pruned -- empty strings, `false`, `0`, and empty maps
   are preserved, since those are legitimate primitive/element values."
  [x]
  (walk/postwalk
   (fn [node]
     (if (map? node)
       (into (empty node)
             (remove (fn [[_ v]] (and (sequential? v) (empty? v))))
             node)
       node))
   x))

(defn ^:no-doc xtdb->fhir
  "Converts an XTDB query result row back to a FHIR resource map.
   Uses the precompiled malli decoder for the resource type, falling back to the
   :default decoder (built from :map) for types without a specific schema.
   Captures the server-managed fhir_version column and xt/system_from so they can
   be injected back as :meta :versionId / :meta :lastUpdated after decoding.
   Empty sequential collections are pruned from the reconstructed resource so the
   read surface matches FHIR wire semantics (repeating elements with zero entries
   are omitted, never emitted as `[]`)."
  [record read-decoders]
  (when record
    (let [id (or (:xt/id record) (:_id record))
          version (or (:fhir-version record) (:fhir_version record) (get record "fhir_version"))
          system-from (or (:xt/system_from record) (:xt/system-from record) (get record "_system_from"))
          stripped (-> record
                       (dissoc :xt/id :_id :fhir_version :fhir-version
                               :xt/system_from :xt/system_to :xt/system-from :xt/system-to
                               :xt/valid_from :xt/valid_to
                               :fhir_source :fhir-source)
                       ;; Drop the denormalized token search columns so they
                       ;; never leak into the reconstructed resource. XTDB returns
                       ;; the `<field>_tokens` columns as kebab-cased keywords
                       ;; (`:code-tokens`), so match either spelling.
                       (->> (remove (fn [[k _]]
                                      (let [n (name k)]
                                        (or (str/ends-with? n "-tokens")
                                            (str/ends-with? n "_tokens")))))
                            (into {}))
                       (set/rename-keys {:resourcetype :resourceType}))
          rt (:resourceType stripped)
          decode-fn (get read-decoders rt (get read-decoders :default))
          base (drop-empty-sequentials (decode-fn stripped))
          with-id (if id (assoc base :id (str id)) base)]
      (inject-meta with-id version system-from))))

(defn- parse-sort-param
  "Parses a FHIR _sort parameter string into a vector of {:field :dir} maps.
   e.g. \"-date,name\" -> [{:field \"date\" :dir :desc} {:field \"name\" :dir :asc}]"
  [sort-str]
  (when (and sort-str (not (str/blank? sort-str)))
    (mapv (fn [s]
            (let [s (str/trim s)]
              (if (str/starts-with? s "-")
                {:field (subs s 1) :dir :desc}
                {:field s :dir :asc})))
          (str/split sort-str #","))))

(defn- sort-field->sql-col
  "Maps a FHIR sort field name to a SQL column expression.
   Uses the search registry to find the column name when available,
   otherwise falls back to the field name directly."
  [field search-registry]
  (if-let [sp (get search-registry field)]
    ;; Use the first column from the registry entry
    (let [col (first (:columns sp))]
      (when col
        (let [col-name (:col col)
              sub-col (:sub-col col)]
          (if sub-col
            ;; Nested field: use struct accessor
            (format "(\"%s\").\"%s\"" col-name sub-col)
            (format "\"%s\"" col-name)))))
    ;; Fallback: use field name directly as column name
    (format "\"%s\"" field)))

(defn- build-order-by-clause
  "Builds a SQL ORDER BY clause from parsed sort specs.
   Returns nil if no valid sort specs."
  [sort-specs search-registry]
  (when (seq sort-specs)
    (let [clauses (keep (fn [{:keys [field dir]}]
                          (when-let [col-expr (sort-field->sql-col field search-registry)]
                            (str col-expr (if (= dir :desc) " DESC" " ASC"))))
                        sort-specs)]
      (when (seq clauses)
        (str " ORDER BY " (str/join ", " clauses))))))

(def ^:private default-pool-opts
  "Defaults for the per-tenant HikariCP connection pool. xt/q and xt/execute-tx
   open a fresh pgwire JDBC connection per call when handed the node; reusing
   pooled connections eliminates that per-request session churn. A bounded pool
   caps concurrent pgwire sessions while virtual threads cheaply park on borrow.
   min-idle is modest (the win is connection reuse up to max-size, not pre-warming);
   raise it per deployment if first-request cold-open latency matters."
  {:max-size 24 :min-idle 4 :connection-timeout-ms 10000})

(defn- make-pool
  "Builds a HikariCP pool over the XTDB node (which is a javax.sql.DataSource)."
  ^HikariDataSource [^javax.sql.DataSource node tenant-id pool-opts]
  (let [{:keys [max-size min-idle connection-timeout-ms]} (merge default-pool-opts pool-opts)
        cfg (doto (HikariConfig.)
              (.setDataSource node)
              (.setMaximumPoolSize (int max-size))
              (.setMinimumIdle (int min-idle))
              (.setConnectionTimeout (long connection-timeout-ms))
              (.setPoolName (str "xtdb-" tenant-id)))]
    (HikariDataSource. cfg)))

(defn- close-entry!
  "Closes a tenant entry's pool then its node, swallowing errors."
  [{:keys [node pool]}]
  (when pool (try (.close ^HikariDataSource pool) (catch Throwable _ nil)))
  (when node (try (.close ^java.lang.AutoCloseable node) (catch Throwable _ nil))))

(defn- get-or-create-entry
  "Returns {:node node :pool HikariDataSource} for the tenant, creating both if
   absent. compare-and-set via swap! handles concurrent creation; the loser of a
   race closes the pool+node it created and uses the winner's entry."
  [store tenant-id]
  (let [nodes (:nodes store)
        tid (str tenant-id)]
    (or (get @nodes tid)
        (t/trace!
         {:id :store/node.start
          :data {:tenant-id tid}}
         (let [new-node (xtn/start-node (:node-config store))
               new-pool (make-pool new-node tid (:pool-opts store))
               new-entry {:node new-node :pool new-pool}
               existing (get (swap! nodes (fn [m]
                                            (if (contains? m tid)
                                              m
                                              (assoc m tid new-entry))))
                             tid)]
           (if (identical? existing new-entry)
             new-entry
             (do (close-entry! new-entry)
                 existing)))))))

(defn- get-or-create-node
  "Back-compat accessor returning just the node, for callers that don't borrow a
   pooled connection (tenant lifecycle, valueset stubs, XTQL pathway)."
  [store tenant-id]
  (:node (get-or-create-entry store tenant-id)))

(defn- persistent-backend?
  "True when the node-config targets persistent storage (i.e. has a
   :log or :storage entry). The empty map used for in-memory xtdb2
   nodes returns false."
  [cfg]
  (or (some? (:log cfg)) (some? (:storage cfg))))

;; NOTE: `test-server.core/store-presets :xtdb2-disk` does NOT template
;; its :log / :storage paths per tenant, so in the current single-tenant
;; (`default`) setup calling `delete-tenant-storage!` wipes the one and
;; only tenant's storage. A true multi-tenant disk deployment must
;; template per-tenant paths before relying on `:close-storage? true`.
(defn- delete-tenant-storage!
  "For each of :log / :storage whose value is shaped `[:local {:path p}]`,
   recursively delete the on-disk directory. Missing paths are ignored.
   In-memory configs (no :log / :storage) are a no-op."
  [store _tid]
  (let [cfg (:node-config store)
        paths (keep (fn [k]
                      (let [v (get cfg k)]
                        (when (and (vector? v)
                                   (= :local (first v))
                                   (map? (second v)))
                          (:path (second v)))))
                    [:log :storage])]
    (doseq [p paths]
      (let [root (java.io.File. ^String (str p))]
        (when (.exists root)
          ;; Post-order walk so directory entries are removed before
          ;; the directory itself.
          (let [path (.toPath root)]
            (with-open [stream (java.nio.file.Files/walk
                                 path
                                 (into-array java.nio.file.FileVisitOption []))]
              (let [files (into [] (.toArray (.sorted stream
                                                      (java.util.Comparator/reverseOrder))))]
                (doseq [^java.nio.file.Path fp files]
                  (try
                    (java.nio.file.Files/deleteIfExists fp)
                    (catch Throwable _ nil)))))))))))

;; ---------------------------------------------------------------------------
;; Simple-read SQL impls. Extracted from the protocol method bodies so the
;; dispatcher in XTDBStore can route to these under :query-mode :sql and to
;; the XTQL siblings (fhir-store-xtdb2.query-xtql) under :query-mode :xtql.
;; ---------------------------------------------------------------------------

;; All read-surface SELECTs project `*, _system_from`: SELECT * alone does NOT
;; include xtdb's system columns, and _system_from is what inject-meta turns
;; into :meta :lastUpdated.

(defn- read-sql [node resource-type id read-decoders]
  (let [query (format "SELECT *, _system_from FROM %s WHERE _id = ?" (table-name resource-type))
        results (into [] (xt/q node [query id]))]
    (xtdb->fhir (first results) read-decoders)))

(defn- vread-sql [node resource-type id vid read-decoders]
  (let [query (format "SELECT *, _system_from FROM %s FOR SYSTEM_TIME AS OF ? WHERE _id = ?" (table-name resource-type))
        results (into [] (xt/q node [query vid id]))]
    (xtdb->fhir (first results) read-decoders)))

(defn- deleted?-sql [node resource-type id]
  (let [table (table-name resource-type)
        current-query (format "SELECT _id FROM %s WHERE _id = ?" table)
        current-results (into [] (xt/q node [current-query id]))]
    (if (seq current-results)
      false
      (let [history-query (format "SELECT _id FROM %s FOR ALL SYSTEM_TIME WHERE _id = ?" table)
            history-results (into [] (xt/q node [history-query id]))]
        (boolean (seq history-results))))))

(defn- history-sql [node resource-type id read-decoders]
  (let [query (format "SELECT *, _system_from FROM %s FOR ALL SYSTEM_TIME WHERE _id = ?" (table-name resource-type))]
    (mapv #(xtdb->fhir % read-decoders) (xt/q node [query id]))))

(def ^:private ^:no-doc result-params
  #{"_count" "_skip" "_offset" "_sort" "_include" "_revinclude"
    "_total" "_elements" "_contained" "_containedType"
    "_summary" "_format" "_pretty"})

(defn ^:no-doc prepare-search-args
  "Parses a FHIR search params map into the inputs both the SQL and XTQL
   pathways need: the filtering subset, parsed sort-specs, limit, offset."
  [params search-registry]
  (let [raw-count (or (get params :_count) (get params "_count") "50")
        raw-skip (or (get params :_skip) (get params "_skip") "0")
        limit (if (string? raw-count) (parse-long raw-count) raw-count)
        offset (if (string? raw-skip) (parse-long raw-skip) raw-skip)
        raw-sort (or (get params :_sort) (get params "_sort"))
        sort-specs (parse-sort-param raw-sort)
        filter-params (into {}
                            (remove (fn [[k _]] (contains? result-params (name k))))
                            params)]
    {:filter-params filter-params
     :sort-specs sort-specs
     :search-registry search-registry
     :limit limit
     :offset offset}))

(defn- where+params
  "Builds the WHERE fragment (without the leading WHERE) and its params for a
   set of filter params, or [nil []] when there are none."
  [filter-params search-registry]
  (if (empty? filter-params)
    [nil []]
    (let [conditions (map (fn [k]
                            (build-condition k (get filter-params k)
                                             (get search-registry (name k))))
                          (keys filter-params))]
      [(str/join " AND " (map first conditions))
       (into [] (mapcat second) conditions)])))

(defn- row-id [r] (or (:_id r) (:xt/id r) (get r "_id")))

(defn- fetch-by-ids
  "Fetches full rows for `ids` and returns them as FHIR resources in the same
   order as `ids` (phase 2 of the two-phase sorted search)."
  [node resource-type ids read-decoders]
  (if (empty? ids)
    []
    (let [rows (xt/q node [(format "SELECT *, _system_from FROM %s WHERE _id = ANY(?)" (table-name resource-type))
                           (vec ids)])
          by-id (into {} (map (fn [r] [(row-id r) r])) rows)]
      (into [] (keep #(some-> (get by-id %) (xtdb->fhir read-decoders))) ids))))

(defn- search-sql
  [node resource-type {:keys [filter-params sort-specs search-registry limit offset]} read-decoders]
  ;; LIMIT/OFFSET are bound as params (not inlined) so the SQL text is stable
  ;; across pages — repeated searches of a given shape hit XTDB's plan cache.
  (let [order-by (build-order-by-clause sort-specs search-registry)
        [where params] (where+params filter-params search-registry)
        where-sql (if where (str " WHERE " where) "")
        rt (table-name resource-type)]
    (if (seq sort-specs)
      ;; Two-phase: an ORDER BY defeats LIMIT early-termination, so a single
      ;; SELECT * would materialize every matched row's full column set just to
      ;; sort and keep `limit`. Instead sort/limit a narrow _id projection
      ;; (only the WHERE + sort-key columns are read), then fetch the page's
      ;; full rows by id and restore the sorted order.
      (let [id-q (format "SELECT _id FROM %s%s%s LIMIT ? OFFSET ?" rt where-sql (or order-by ""))
            ids (mapv row-id (xt/q node (into [id-q] (conj params limit offset))))]
        (fetch-by-ids node resource-type ids read-decoders))
      ;; No sort: a single SELECT * with LIMIT streams the first `limit` rows and
      ;; stops (early-termination), so the wide projection cost is already bounded.
      (let [q (format "SELECT *, _system_from FROM %s%s LIMIT ? OFFSET ?" rt where-sql (or order-by ""))]
        (mapv #(xtdb->fhir % read-decoders) (xt/q node (into [q] (conj params limit offset))))))))

(defn- count-sql
  [node resource-type {:keys [filter-params search-registry]}]
  (let [[query-str all-params]
        (if (empty? filter-params)
          [(format "SELECT COUNT(*) AS cnt FROM %s" (table-name resource-type)) []]
          (let [cols (keys filter-params)
                conditions (map (fn [k]
                                  (build-condition k (get filter-params k)
                                                   (get search-registry (name k))))
                                cols)
                where-clause (str/join " AND " (map first conditions))
                p (into [] (mapcat second) conditions)]
            [(format "SELECT COUNT(*) AS cnt FROM %s WHERE %s"
                     (table-name resource-type) where-clause) p]))
        result (first (xt/q node (into [query-str] all-params)))]
    (or (:cnt result) 0)))

;; ---------------------------------------------------------------------------
;; Write-side SQL impls.
;; ---------------------------------------------------------------------------

(defn- create-sql [node resource-type id resource storage-encoders]
  (let [version "1"
        rt-name (name resource-type)
        [sql args] (extract-and-build-sql resource-type id resource storage-encoders
                                          :version version)
        assert-op [:sql (format "ASSERT NOT EXISTS (SELECT 1 FROM %s WHERE _id = ?)"
                                (table-name resource-type))
                   [id]]
        tx-key (try
                 (xt/execute-tx node [assert-op [:sql sql args]])
                 (catch Exception e
                   (throw (ex-info (str "Resource already exists: " rt-name "/" id)
                                   {:fhir/status 409 :fhir/code "conflict"
                                    :resource-type rt-name :id id}
                                   e))))]
    (with-basis
      (-> resource
          (assoc :id id)
          (assoc-in [:meta :versionId] version))
      tx-key)))

(defn- update-sql [node resource-type id resource opts storage-encoders]
  (let [rt-name (table-name resource-type)
        if-match (:if-match opts)
        current (current-version node resource-type id)
        _ (when (and if-match (nil? current))
            (throw (ex-info "Version conflict: resource does not exist"
                            {:fhir/status 412 :fhir/code "conflict"
                             :expected if-match :actual nil})))
        _ (when (and if-match current (not= if-match current))
            (throw (ex-info "Version conflict"
                            {:fhir/status 412 :fhir/code "conflict"
                             :expected if-match :actual current})))
        expected-vid (or if-match current)
        new-version (next-version expected-vid)
        [sql args] (extract-and-build-sql resource-type id resource storage-encoders
                                          :version new-version)
        assert-op (if expected-vid
                    [:sql (format "ASSERT EXISTS (SELECT 1 FROM %s WHERE _id = ? AND fhir_version = ?)"
                                  rt-name)
                     [id expected-vid]]
                    [:sql (format "ASSERT NOT EXISTS (SELECT 1 FROM %s WHERE _id = ?)"
                                  rt-name)
                     [id]])
        tx-key (try
                 (xt/execute-tx node [assert-op [:sql sql args]])
                 (catch Exception e
                   (if if-match
                     (throw (ex-info (str "Version conflict: " (ex-message e))
                                     {:fhir/status 412 :fhir/code "conflict"
                                      :expected if-match}
                                     e))
                     (throw (ex-info (str "Conflict: " (ex-message e))
                                     {:fhir/status 409 :fhir/code "conflict"}
                                     e)))))]
    (with-basis
      (-> resource
          (assoc :id id)
          (assoc-in [:meta :versionId] new-version))
      tx-key)))

(defn- delete-sql [node resource-type id opts]
  (let [rt-name (table-name resource-type)
        if-match (:if-match opts)
        current (when if-match (current-version node resource-type id))
        _ (when (and if-match (nil? current))
            (throw (ex-info "Version conflict: resource does not exist"
                            {:fhir/status 412 :fhir/code "conflict"
                             :expected if-match :actual nil})))
        _ (when (and if-match (not= if-match current))
            (throw (ex-info "Version conflict"
                            {:fhir/status 412 :fhir/code "conflict"
                             :expected if-match :actual current})))
        assert-op (when if-match
                    [:sql (format "ASSERT EXISTS (SELECT 1 FROM %s WHERE _id = ? AND fhir_version = ?)"
                                  rt-name)
                     [id if-match]])
        delete-op [:sql (format "DELETE FROM %s WHERE _id = ?" rt-name) [id]]
        tx-ops (if assert-op [assert-op delete-op] [delete-op])
        tx-key (try
                 (xt/execute-tx node tx-ops)
                 (catch Exception e
                   (if if-match
                     (throw (ex-info (str "Version conflict: " (ex-message e))
                                     {:fhir/status 412 :fhir/code "conflict"
                                      :expected if-match}
                                     e))
                     (throw (ex-info (str "Conflict: " (ex-message e))
                                     {:fhir/status 409 :fhir/code "conflict"}
                                     e)))))]
    ;; Deletes have no resource to return; an empty map carries the basis
    ;; metadata so the write-return convention holds across all writes.
    (with-basis {} tx-key)))

(defn- history-type-sql [node resource-type params read-decoders]
  (let [raw-count (or (get params :_count) (get params "_count") "50")
        limit (if (string? raw-count) (parse-long raw-count) raw-count)
        since (or (get params :_since) (get params "_since"))
        at (or (get params :_at) (get params "_at"))
        [where-clause where-params]
        (cond
          since [" WHERE _system_from > TIMESTAMP ?" [since]]
          at    [" WHERE _system_from <= TIMESTAMP ?" [at]]
          :else ["" []])
        query (format "SELECT *, _system_from FROM %s FOR ALL SYSTEM_TIME%s ORDER BY _system_from DESC LIMIT ?"
                      (table-name resource-type) where-clause)
        results (into [] (xt/q node (into [query] (conj where-params limit))))]
    (mapv #(xtdb->fhir % read-decoders) results)))

(defrecord XTDBStore [nodes node-config storage-encoders read-decoders query-mode pool-opts]
  IFHIRStore

  (create-resource [this tenant-id resource-type id resource]
    (t/trace!
     {:id :store/create
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)]
       (case query-mode
         :xtql (create-xtql node resource-type id resource storage-encoders)
         (with-open [conn (jdbc/get-connection pool)]
           (create-sql conn resource-type id resource storage-encoders))))))

  (read-resource [this tenant-id resource-type id]
    (t/trace!
     {:id :store/read
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)]
       (case query-mode
         :xtql (read-xtql node resource-type id read-decoders)
         (with-open [conn (jdbc/get-connection pool)]
           (read-sql conn resource-type id read-decoders))))))

  (vread-resource [this tenant-id resource-type id vid]
    (t/trace!
     {:id :store/vread
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id :vid vid}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)]
       (case query-mode
         :xtql (vread-xtql node resource-type id vid read-decoders)
         (with-open [conn (jdbc/get-connection pool)]
           (vread-sql conn resource-type id vid read-decoders))))))

  (update-resource [this tenant-id resource-type id resource]
    (fp/update-resource this tenant-id resource-type id resource nil))

  (update-resource [this tenant-id resource-type id resource opts]
    (t/trace!
     {:id :store/update
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)]
       (case query-mode
         :xtql (update-xtql node resource-type id resource opts storage-encoders)
         (with-open [conn (jdbc/get-connection pool)]
           (update-sql conn resource-type id resource opts storage-encoders))))))

  (delete-resource [this tenant-id resource-type id]
    (fp/delete-resource this tenant-id resource-type id nil))

  (delete-resource [this tenant-id resource-type id opts]
    (t/trace!
     {:id :store/delete
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)]
       (case query-mode
         :xtql (delete-xtql node resource-type id opts)
         (with-open [conn (jdbc/get-connection pool)]
           (delete-sql conn resource-type id opts))))))

  (resource-deleted? [this tenant-id resource-type id]
    ;; A resource is "deleted" if it has history (existed in the past) but no current row
    (t/trace!
     {:id :store/resource-deleted?
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)]
       (case query-mode
         :xtql (deleted?-xtql node resource-type id)
         (with-open [conn (jdbc/get-connection pool)]
           (deleted?-sql conn resource-type id))))))

  (search [this tenant-id resource-type params search-registry]
    (t/trace!
     {:id :store/search
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)
           args (prepare-search-args params search-registry)
           ;; node-based thunk: only used as the XTQL pathway's SQL fallback.
           sql-thunk #(search-sql node resource-type args read-decoders)]
       (try
         (case query-mode
           :xtql (search-xtql node resource-type args read-decoders sql-thunk)
           (with-open [conn (jdbc/get-connection pool)]
             (search-sql conn resource-type args read-decoders)))
         (catch Exception e
           ;; Search failures should never yield HTTP 500. Per FHIR spec, unsupported
           ;; or broken search params return empty results. Common causes: table/column
           ;; not found, struct field access failures, type mismatches in XTDB SQL.
           (t/event! ::search-query-failed
                     {:level :warn
                      :data {:resource-type (name resource-type)
                             :params (:filter-params args)
                             :error (.getMessage e)}})
           [])))))

  (count-resources [this tenant-id resource-type params search-registry]
    (t/trace!
     {:id :store/count
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)
           args (prepare-search-args params search-registry)
           ;; node-based thunk: only used as the XTQL pathway's SQL fallback.
           sql-thunk #(count-sql node resource-type args)]
      (try
        (case query-mode
          :xtql (count-resources-xtql node resource-type args sql-thunk)
          (with-open [conn (jdbc/get-connection pool)]
            (count-sql conn resource-type args)))
        (catch Exception e
          (t/event! ::count-query-failed
                    {:level :warn
                     :data {:resource-type (name resource-type)
                            :params (:filter-params args)
                            :error (.getMessage e)}})
          0)))))

  (history [this tenant-id resource-type id]
    (t/trace!
     {:id :store/history
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)]
       (case query-mode
         :xtql (history-xtql node resource-type id read-decoders)
         (with-open [conn (jdbc/get-connection pool)]
           (history-sql conn resource-type id read-decoders))))))

  (history-type [this tenant-id resource-type params]
    (t/trace!
     {:id :store/history-type
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [node pool]} (get-or-create-entry this tenant-id)]
       (case query-mode
         :xtql (history-type-xtql node resource-type params read-decoders)
         (with-open [conn (jdbc/get-connection pool)]
           (history-type-sql conn resource-type params read-decoders))))))

  (transact-transaction [this tenant-id entries]
    ;; Pre-compute entry metadata (method, resource-type, id) for use in both
    ;; building tx-ops and constructing the response afterward.
    ;; Entries are reordered per FHIR §3.1.0.11.2: DELETE -> POST -> PUT/PATCH -> GET/HEAD
    (t/trace!
     {:id :store/transact-transaction
      :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
    (let [{:keys [pool]} (get-or-create-entry this tenant-id)]
     (with-open [conn (jdbc/get-connection pool)]
      (let [node conn
          entry-metas
          (t/trace!
           {:id :store/transact-transaction.build-tx
            :data {:entry-count (count entries)}}
           (let [metas (->> (mapv (fn [entry]
                                    (let [{:keys [request resource fullUrl]} entry
                                          method (str/upper-case (:method request))
                                          url (:url request)
                                          parts (str/split url #"/")
                                          resource-type (first parts)
                                          id (or (second parts)
                                                 (if (and fullUrl (str/starts-with? fullUrl "urn:uuid:"))
                                                   (subs fullUrl 9)
                                                   (str (java.util.UUID/randomUUID))))]
                                      {:method method
                                       :resource-type resource-type
                                       :id id
                                       :fullUrl fullUrl
                                       :resource resource}))
                                  entries)
                            (sort-by #(method-order (:method %)))
                            vec)
                 urn-mapping (build-urn-uuid-mapping metas)]
             (mapv (fn [em]
                     (if (:resource em)
                       (update em :resource resolve-urn-uuid-references urn-mapping)
                       em))
                   metas)))
          ;; Bulk-fetch current versions for every PUT in one query per
          ;; distinct resource type, replacing N sequential round-trips.
          put-versions-by-type
          (t/trace!
           {:id :store/transact-transaction.current-versions
            :data {:entry-count (count entry-metas)}}
           (reduce (fn [acc [rt metas]]
                     (assoc acc rt (current-versions-bulk node rt (map :id metas))))
                   {}
                   (group-by :resource-type
                             (filter #(= "PUT" (:method %)) entry-metas))))
          last-updated (java.time.Instant/now)
          ;; Single pass over entry-metas: emit tx-ops AND the per-entry
          ;; response metadata (new version, final resource with :id+:meta
          ;; populated). This lets the response builder assemble the Bundle
          ;; without a post-commit round-trip.
          ;; Per-entry write-op emitter. The SQL pathway emits
          ;; [:sql insert-stmt args] / [:sql delete-stmt args] — the XTQL
          ;; pathway emits [:put-docs table doc] / [:delete-docs table id],
          ;; bypassing the INSERT planner entirely. execute-tx accepts both
          ;; shapes (and a mix with [:sql ASSERT ...]) in one atomic call.
          xtql-mode? (= query-mode :xtql)
          emit-write-op
          (fn [{:keys [method resource-type id resource] :as _em} vid]
            (case method
              ("POST" "PUT")
              (if xtql-mode?
                [:put-docs (keyword resource-type)
                 (doc->put-doc
                  (encode-resource-doc resource-type id resource storage-encoders
                                       :version vid))]
                (let [[sql args] (extract-and-build-sql
                                   resource-type id resource storage-encoders
                                   :version vid)]
                  [:sql sql args]))
              "DELETE"
              (if xtql-mode?
                [:delete-docs (keyword resource-type) id]
                [:sql (format "DELETE FROM %s WHERE _id = ?" (table-name resource-type))
                 [id]])))
          {:keys [tx-ops entry-results]}
          (t/trace!
           {:id :store/transact-transaction.sql-encode
            :data {:entry-count (count entry-metas) :query-mode query-mode}}
           (reduce (fn [acc {:keys [method resource-type id] :as em}]
                     (case method
                       "POST"
                       (let [vid "1"
                             final (-> (:resource em)
                                       (assoc :id id)
                                       (assoc-in [:meta :versionId] vid)
                                       (assoc-in [:meta :lastUpdated] last-updated))]
                         (-> acc
                             (update :tx-ops conj (emit-write-op em vid))
                             (update :entry-results conj (assoc em :resource final :vid vid))))
                       "PUT"
                       (let [current (get-in put-versions-by-type [resource-type id])
                             vid (next-version current)
                             final (-> (:resource em)
                                       (assoc :id id)
                                       (assoc-in [:meta :versionId] vid)
                                       (assoc-in [:meta :lastUpdated] last-updated))]
                         (-> acc
                             (update :tx-ops conj (emit-write-op em vid))
                             (update :entry-results conj (assoc em :resource final :vid vid))))
                       "DELETE"
                       (-> acc
                           (update :tx-ops conj (emit-write-op em nil))
                           (update :entry-results conj em))
                       ;; GET / HEAD inside a transaction: we still have to
                       ;; read the resource to satisfy the response. Defer
                       ;; these to the read-back phase by leaving :resource
                       ;; nil on entry-results.
                       (update acc :entry-results conj em)))
                   {:tx-ops [] :entry-results []}
                   entry-metas))]
      (let [tx-key (t/trace!
                    {:id :store/transact-transaction.execute-tx
                     :data {:op-count (count tx-ops)}}
                    (xt/execute-tx node tx-ops))]
      ;; Build the response. Writes return from in-memory metadata (no
      ;; round-trips). GET/HEAD entries, if any, still need a read — batched
      ;; per resource-type to avoid N sequential SELECTs.
      (t/trace!
       {:id :store/transact-transaction.build-response
        :data {:entry-count (count entry-results)}}
       (let [read-needed (filter (fn [{:keys [method]}]
                                   (contains? #{"GET" "HEAD"} method))
                                 entry-results)
             rows-by-type-id
             (reduce (fn [acc [rt metas]]
                       (assoc acc rt (bulk-read-by-ids node read-decoders rt
                                                       (map :id metas))))
                     {}
                     (group-by :resource-type read-needed))
             last-mod-str (str last-updated)]
         (with-basis
          {:resourceType "Bundle"
           :type "transaction-response"
           :entry (mapv (fn [{:keys [method resource-type id resource vid]}]
                         (case method
                           "DELETE"
                           {:response {:status "204 No Content"}}

                           ("GET" "HEAD")
                           (let [res (get-in rows-by-type-id [resource-type id])
                                 rvid (get-in res [:meta :versionId])
                                 rlm (get-in res [:meta :lastUpdated])]
                             (if res
                               (cond-> {:response (cond-> {:status "200 OK"}
                                                    rvid (assoc :etag (str "W/\"" rvid "\""))
                                                    rlm (assoc :lastModified (str rlm)))}
                                 (= method "GET") (assoc :resource res))
                               {:response {:status "404 Not Found"}}))

                           ;; POST and PUT: build directly from the
                           ;; sql-encode phase's precomputed metadata.
                           (cond-> {:response (cond-> {:status (if (= method "POST") "201 Created" "200 OK")}
                                                vid (assoc :etag (str "W/\"" vid "\""))
                                                last-mod-str (assoc :lastModified last-mod-str)
                                                (= method "POST") (assoc :location (str "/" tenant-id "/fhir/" resource-type "/" id "/_history/" vid)))}
                             resource (assoc :resource resource))))
                       entry-results)}
          tx-key)))))))))

  (transact-bundle [this tenant-id entries]
    ;; Batch semantics: each entry is processed independently via the
    ;; single-resource CRUD methods on this store. Per-entry failures
    ;; are captured as OperationOutcome responses and do NOT affect
    ;; other entries. Returns a batch-response Bundle in input order.
    (t/trace!
     {:id :store/transact-bundle
      :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
     (let [results
           (mapv
            (fn [entry]
              (try
                (let [req-map (:request entry)
                      method (some-> (:method req-map) str/upper-case)
                      url (:url req-map)
                      parts (when url (str/split url #"/"))
                      resource-type (first parts)
                      id (second parts)
                      resource (:resource entry)
                      raw-if-match (or (:ifMatch req-map) (get req-map "ifMatch"))
                      entry-if-match (when raw-if-match
                                       (or (second (re-find #"W/\"(.+)\"" raw-if-match))
                                           raw-if-match))]
                  (case method
                    "POST"
                    (let [new-id (str (java.util.UUID/randomUUID))
                          res (fp/create-resource this tenant-id (keyword resource-type) new-id resource)
                          vid (get-in res [:meta :versionId])
                          last-mod (str (get-in res [:meta :lastUpdated]))]
                      {:resource res
                       :response (cond-> {:status "201 Created"}
                                   vid (assoc :etag (str "W/\"" vid "\"")
                                              :location (str "/" tenant-id "/fhir/" resource-type "/" (:id res) "/_history/" vid))
                                   last-mod (assoc :lastModified last-mod))})

                    "PUT"
                    (let [res (if entry-if-match
                                (fp/update-resource this tenant-id (keyword resource-type) id resource
                                                    {:if-match entry-if-match})
                                (fp/update-resource this tenant-id (keyword resource-type) id resource))
                          vid (get-in res [:meta :versionId])
                          last-mod (str (get-in res [:meta :lastUpdated]))]
                      {:resource res
                       :response (cond-> {:status "200 OK"}
                                   vid (assoc :etag (str "W/\"" vid "\""))
                                   last-mod (assoc :lastModified last-mod))})

                    "DELETE"
                    (do (if entry-if-match
                          (fp/delete-resource this tenant-id (keyword resource-type) id
                                              {:if-match entry-if-match})
                          (fp/delete-resource this tenant-id (keyword resource-type) id))
                        {:response {:status "204 No Content"}})

                    "GET"
                    (let [res (fp/read-resource this tenant-id (keyword resource-type) id)]
                      (if res
                        (let [vid (get-in res [:meta :versionId])
                              last-mod (str (get-in res [:meta :lastUpdated]))]
                          {:resource res
                           :response (cond-> {:status "200 OK"}
                                       vid (assoc :etag (str "W/\"" vid "\""))
                                       last-mod (assoc :lastModified last-mod))})
                        {:response {:status "404 Not Found"
                                    :outcome {:resourceType "OperationOutcome"
                                              :issue [{:severity "error"
                                                       :code "not-found"
                                                       :diagnostics (str resource-type "/" id " not found")}]}}}))

                    {:response {:status "400 Bad Request"
                                :outcome {:resourceType "OperationOutcome"
                                          :issue [{:severity "error"
                                                   :code "invalid"
                                                   :diagnostics (str "Unsupported method: " method)}]}}}))
                (catch Exception e
                  {:response {:status "400 Bad Request"
                              :outcome {:resourceType "OperationOutcome"
                                        :issue [{:severity "error"
                                                 :code "exception"
                                                 :diagnostics (str "Entry failed: " (ex-message e))}]}}})))
            entries)]
       {:resourceType "Bundle"
        :type "batch-response"
        :entry results})))

  (create-tenant [this tenant-id]
    (fp/create-tenant this tenant-id nil))
  (create-tenant [this tenant-id opts]
    (t/trace!
     {:id :store/create-tenant
      :data {:tenant-id (str tenant-id) :opts opts}}
     (let [tid       (str tenant-id)
           if-exists (get opts :if-exists :error)
           existing  (contains? @(:nodes this) tid)]
       (cond
         (and existing (= :error if-exists))
         (throw (ex-info "Tenant already exists"
                         {:fhir/status 409 :fhir/code "conflict"
                          :tenant-id tid}))

         (and existing (= :ignore if-exists))
         nil

         (and existing (= :replace if-exists))
         (do (fp/delete-tenant this tid {:if-absent :ignore
                                         :close-storage? true})
             (get-or-create-node this tid)
             nil)

         :else
         (do (get-or-create-node this tid)
             nil)))))

  (delete-tenant [this tenant-id]
    (fp/delete-tenant this tenant-id nil))
  (delete-tenant [this tenant-id opts]
    (t/trace!
     {:id :store/delete-tenant
      :data {:tenant-id (str tenant-id) :opts opts}}
     (let [tid       (str tenant-id)
           if-absent (get opts :if-absent :error)
           close?    (get opts :close-storage? false)
           existing  (get @(:nodes this) tid)]
       (cond
         (and (nil? existing) (= :error if-absent))
         (throw (ex-info "Tenant not found"
                         {:fhir/status 404 :fhir/code "not-found"
                          :tenant-id tid}))

         (some? existing)
         (do
           (close-entry! existing)
           (swap! (:nodes this) dissoc tid)
           (when (and close? (persistent-backend? (:node-config this)))
             (delete-tenant-storage! this tid))
           nil)

         :else nil))))

  (warmup-tenant [this tenant-id]
    (fp/warmup-tenant this tenant-id nil))
  (warmup-tenant [this tenant-id opts]
    (t/trace!
     {:id :store/warmup-tenant
      :data {:tenant-id (str tenant-id)}}
     (let [tid (str tenant-id)
           rts (or (:resource-types opts) #{:Patient})]
       (get-or-create-node this tid)
       (doseq [rt rts]
         (try
           (fp/search this tid rt {"_count" "1"} {})
           (catch Throwable _ nil)))
       nil))))

(defn- xtdb-valueset-expand [store tenant-id _params id]
  ;; In a real XTDB implementation, we'd query for codes using XTDB
  (let [vs (if id
             (fp/read-resource store tenant-id :ValueSet id)
             {:resourceType "ValueSet"
              :id "xtdb-valueset"
              :status "active"})]
    (if vs
      (assoc vs
             :expansion {:total 1
                         :timestamp (str (java.time.Instant/now))
                         :contains [{:system "http://example.com"
                                     :code "xtdb"
                                     :display "XTDB Expanded Code"}]})
      {:resourceType "OperationOutcome"
       :issue [{:severity "error"
                :code "not-found"
                :diagnostics (str "ValueSet " id " not found")}]})))

(defn- xtdb-valueset-lookup [_store _tenant-id _params]
  ;; In a real XTDB implementation, we'd look up the code details
  {:resourceType "Parameters"
   :parameter [{:name "name"
                :valueString "XTDB Lookup Result"}
               {:name "display"
                :valueString "XTDB"}]})

(defn create-xtdb-store
  "Creates an XTDB implementation of IFHIRStore with per-tenant node isolation.
   Config map keys:
   - :resource/schemas  — compiled malli schemas for all resource types (optional)
   - :node-config       — XTDB node configuration (default: {} for in-memory)
   - :query-mode        — pathway for reads and writes. :sql (default) uses
     dynamic SQL + INSERT/DELETE. :xtql uses XTQL reads and put-docs/delete-docs
     writes, with [:sql ASSERT ...] retained for optimistic concurrency.
   - :pool-opts         — per-tenant HikariCP connection-pool overrides
     (:max-size, :min-idle, :connection-timeout-ms); see default-pool-opts."
  [{:keys [resource/schemas node-config query-mode pool-opts]
    :or {node-config {} schemas [] query-mode :sql pool-opts {}}}]
  (assert (contains? #{:sql :xtql} query-mode)
          (str "query-mode must be :sql or :xtql, got " query-mode))
  (let [storage-encoders (xf/build-storage-encoders schemas)
        read-decoders    (xf/build-read-decoders schemas)
        store (->XTDBStore (atom {}) node-config storage-encoders read-decoders query-mode
                           (or pool-opts {}))]
    (assoc store :operations {:valueset-expand xtdb-valueset-expand
                              :valueset-lookup xtdb-valueset-lookup})))

(defmethod ig/init-key :fhir-store/xtdb2-node [_ config]
  ;; Kept for backward compatibility; returns the config map for use by the store.
  (println "Starting XTDB2 Node (config passthrough for per-tenant isolation)")
  config)

(defmethod ig/halt-key! :fhir-store/xtdb2-node [_ _]
  ;; No-op: nodes are managed by the store now
  nil)

(defmethod ig/init-key :fhir-store/xtdb2-store [_ {:keys [node resource/schemas query-mode pool-opts]}]
  (println "Starting XTDB2 FHIR Store (per-tenant node isolation)"
           (str "[query-mode=" (or query-mode :sql) "]"))
  (create-xtdb-store {:resource/schemas schemas
                      :node-config node
                      :query-mode (or query-mode :sql)
                      :pool-opts pool-opts}))

(defmethod ig/halt-key! :fhir-store/xtdb2-store [_ store]
  (println "Stopping XTDB2 FHIR Store - closing all tenant pools + nodes")
  (when-let [nodes (:nodes store)]
    (doseq [[tenant-id entry] @nodes]
      (println (str "  Closing pool + node for tenant: " tenant-id))
      (try
        (close-entry! entry)
        (catch Exception e
          (println (str "  Warning: error closing tenant " tenant-id ": " (.getMessage e))))))
    (reset! nodes {})))

;; Load the optional XTQL pathway. query-xtql requires this ns, so we can't
;; require it at the top — this bottom-of-file load, plus the alter-var-root
;; block below, binds the forward-declared Vars (read-xtql, vread-xtql, ...)
;; to the implementations in fhir-store-xtdb2.query-xtql.
(require 'fhir-store-xtdb2.query-xtql)

(doseq [sym '[read-xtql vread-xtql deleted?-xtql history-xtql history-type-xtql
              search-xtql count-resources-xtql
              create-xtql update-xtql delete-xtql transact-transaction-xtql]]
  (when-let [impl (resolve (symbol "fhir-store-xtdb2.query-xtql" (name sym)))]
    (intern 'fhir-store-xtdb2.core sym @impl)))
