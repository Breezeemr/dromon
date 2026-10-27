(ns fhir-store-xtdb2.core
  (:require [xtdb.api :as xt]
            [xtdb.basis :as basis]
            [xtdb.node :as xtn]
            [clojure.string :as str]
            [clojure.set :as set]
            [clojure.walk :as walk]
            [taoensso.telemere :as t]
            [fhir-store.trace :as ftrace]
            [fhir-store.lifecycle :as lc]
            [cheshire.core :as json]
            [cheshire.generate :as json-gen]
            [integrant.core :as ig]
            [fhir-store.protocol :as fp :refer [IFHIRStore]]
            [fhir-store-xtdb2.conns :as conns :refer [with-conn]]
            [fhir-store-xtdb2.datetime :as dt]
            [fhir-store-xtdb2.transform :as xf]
            [fhir-store-xtdb2.tx-metadata :as txm]
            [xtdb.serde :as serde])
  (:import [java.sql SQLException]
           [java.time LocalDate LocalDateTime Instant OffsetDateTime ZonedDateTime ZoneOffset LocalTime Year YearMonth]
           [java.time.format DateTimeFormatter]
           [com.zaxxer.hikari HikariConfig HikariDataSource SQLExceptionOverride SQLExceptionOverride$Override]
           [org.postgresql.util PSQLException ServerErrorMessage]
           [xtdb.api DataSource$ConnectionBuilder FlightSqlConfig]
           [xtdb.api.error Incorrect]))

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

(defn ^:no-doc refuse-unencodable-text!
  "Refuses a write whose id or body holds an unpaired UTF-16 surrogate, which
   XTDB would store as \"?\" (see xf/unpaired-surrogate-paths). Throws a 400
   naming each offending element path, as the message and as
   `:fhir/expression`. Never the value: both the message and the ex-data
   reach the log."
  [resource-type id resource-map]
  (let [rt-name (name resource-type)
        id-bad? (and (string? id) (xf/unpaired-surrogate? id))
        paths (into [] (distinct)
                    (concat (when id-bad? [(str rt-name ".id")])
                            (xf/unpaired-surrogate-paths rt-name resource-map)))]
    (when (seq paths)
      (throw (ex-info (str "Unpaired UTF-16 surrogate in " (str/join ", " paths)
                           ": not valid Unicode, so it cannot be stored unchanged")
                      (cond-> {:fhir/status 400 :fhir/code "invalid"
                               :fhir/expression paths
                               :resource-type rt-name}
                        (not id-bad?) (assoc :id id)))))))

(defn ^:no-doc refuse-unencodable-input!
  "Refuses a store call whose resource type, id, version id or params hold an
   unpaired UTF-16 surrogate. pgjdbc sends one as \"?\" (see
   xf/unpaired-surrogate-paths), so the call would act on a different table,
   row or match than the one named: a read finds the row whose id has a \"?\"
   there, a search matches a stored \"?\" -- one written before writes refused
   them, say. The type is spliced into SQL as a table name, and declared before
   anything else runs, so this goes first in every verb.

   Throws a 400 naming each offending input, never its text. A parameter whose
   value is the problem is also named `http.<name>` in `:fhir/location`, FHIR's
   convention for an HTTP parameter; one whose NAME is the problem is only
   counted, since naming it would repeat the text."
  [{:keys [resource-type id vid params]}]
  (let [bad-value? (fn [v] (some xf/unencodable? (tree-seq coll? seq v)))
        bad-names (count (filter xf/unencodable? (keys params)))
        bad-params (into [] (keep (fn [[k v]]
                                    (when (and (not (xf/unencodable? k)) (bad-value? v))
                                      (name k))))
                         params)
        bad-type? (xf/unencodable? resource-type)
        bad-id? (xf/unencodable? id)
        what (cond-> []
               bad-type?             (conj "the resource type")
               bad-id?               (conj "the id")
               (xf/unencodable? vid) (conj "the version id")
               (pos? bad-names)      (conj "a parameter's name")
               (seq bad-params)      (into (map #(str "parameter '" % "'")) bad-params))]
    (when (seq what)
      (throw (ex-info (str "Unpaired UTF-16 surrogate in " (str/join ", " what)
                           ": not valid Unicode, so the store cannot use it unchanged")
                      (cond-> {:fhir/status 400 :fhir/code "invalid"}
                        (seq bad-params)
                        (assoc :fhir/location (mapv #(str "http." %) bad-params))

                        (and resource-type (not bad-type?))
                        (assoc :resource-type (name resource-type))

                        (and id (not bad-id?))
                        (assoc :id id)))))))

(defn ^:no-doc version-of-system-time
  "The versionId of the version XTDB stamped at system time `t` (an Instant,
   or the ZonedDateTime `_system_from` reads as): `t` in UTC as Instant.toString
   renders it, without the colons FHIR's id type does not allow, e.g.
   `2026-10-17T041241.058126Z`. A version written since commit-time
   versioning stores no fhir_version; its id is derived from its own
   `_system_from`, which is unique within a resource's history (delete and
   re-create included) and needs no read before the write. A version split by
   a later valid-time write keeps its `_system_from`, so it keeps its id."
  [t]
  (let [t (if (instance? ZonedDateTime t) (.toInstant ^ZonedDateTime t) t)]
    (when (instance? Instant t)
      (str/replace (str t) ":" ""))))

(defn ^:no-doc system-time-of-version
  "The system time a commit-time versionId names, or nil for any other
   versionId (a stored counter such as \"3\")."
  [vid]
  (let [s (str vid)
        i (str/index-of s "T")]
    (when (and i (>= (count s) (+ i 6)))
      (try
        (Instant/parse (str (subs s 0 (+ i 3)) ":" (subs s (+ i 3) (+ i 5)) ":" (subs s (+ i 5))))
        (catch Exception _ nil)))))

(def ^:no-doc version-match-sql
  "Selects the version `vid` names: a stored fhir_version (rows written before
   commit-time versioning, and by importers that stamp one), or the row with
   no stored version whose `_system_from` the id names. Binds
   version-match-args."
  "(fhir_version = ? OR (fhir_version IS NULL AND _system_from = ?))")

(defn ^:no-doc version-match-args [vid]
  [(str vid) (system-time-of-version vid)])

(defn ^:no-doc row-version
  "The versionId of a row read with its fhir_version and `_system_from`."
  [row]
  (or (some-> (or (:fhir-version row) (:fhir_version row) (get row "fhir_version")) str)
      (version-of-system-time (or (:xt/system-from row) (:xt/system_from row)
                                  (get row "_system_from")))))

(defn ^:no-doc encode-resource-doc
  "Runs the malli storage encoder for the given resource type and returns the
   raw XTDB document map (pre-SQL-serialization) with :_id and :fhir_version
   injected. Used by both the SQL INSERT builder and the put-docs path —
   the put-docs path renames :_id → :xt/id before handing the doc to XTDB.
   Every write path encodes through here, so this is where a body XTDB
   cannot store unchanged is refused (see refuse-unencodable-text!)."
  [resource-type id resource-map storage-encoders & {:keys [version]}]
  (refuse-unencodable-text! resource-type id resource-map)
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
     :version — a version string to store as the \"fhir_version\" column. The
       store passes none: a version's id is derived from its `_system_from`
       (version-of-system-time). Importers that keep their own numbering pass
       one, and it wins on read.
     :valid-time — {:valid-from t :valid-to t-or-nil}; adds the _valid_from /
       _valid_to columns so the row is placed on the valid-time axis instead of
       defaulting to now-until-end-of-time. A nil :valid-to is omitted, which
       XTDB reads as end-of-time.
   Public (no-doc) so bulk importers (fhir-datomic-decant's xtdb target) reuse the
   exact document/SQL shape the live store writes."
  [resource-type id resource-map storage-encoders & {:keys [version valid-time]}]
  (let [doc (cond-> (encode-resource-doc resource-type id resource-map storage-encoders :version version)
              (:valid-from valid-time) (assoc :_valid_from (:valid-from valid-time))
              (:valid-to valid-time)   (assoc :_valid_to (:valid-to valid-time)))
        cols (keys doc)
        col-names (str/join ", " (map #(format "\"%s\"" (name %)) cols))
        placeholders (str/join ", " (repeat (count cols) "?"))
        sql (format "INSERT INTO %s (%s) VALUES (%s)" (table-name resource-type) col-names placeholders)
        args (mapv doc cols)]
    [sql args]))

;; ---------------------------------------------------------------------------
;; Stale plans.
;;
;; A `SELECT *` row type is the table's column set, so a write that adds a
;; column, or a type to one, changes it. pgwire refuses to run a statement
;; described under the old row type ("cached plan must not change result
;; type", SQLSTATE 0A000, :prepared-query-out-of-date). Two ways to get one:
;;   - a pooled connection keeps a named server-side statement across
;;     requests. The pool turns those off (see default-pool-opts).
;;   - a write lands between one execution's Parse and its Bind. No client
;;     setting closes that window, so run-query re-runs the read.
;; ---------------------------------------------------------------------------

(defn ^:no-doc stale-plan?
  "True when `e`, or a cause of it, is pgwire refusing a statement whose row
   type changed after it was described: either the pgjdbc SQLException the
   pool sees, or the anomaly xt/q rethrows it as."
  [^Throwable e]
  (boolean
   (some (fn [^Throwable c]
           (or (= :prepared-query-out-of-date (:xtdb.error/code (ex-data c)))
               (and (instance? SQLException c)
                    (= "0A000" (.getSQLState ^SQLException c))
                    (str/includes? (str (ex-message c)) "cached plan must not change result type"))))
         (take-while some? (iterate ex-cause e)))))

(def ^:private stale-plan-attempts 3)

(defn ^:no-doc run-query
  "xt/q, re-run when pgwire refuses it as a stale plan. The refusal comes
   before any row and every caller is a read, so a re-run repeats nothing; it
   is described afresh against the current schema."
  [conn query+args]
  (loop [attempt 1]
    (let [result (try
                   (xt/q conn query+args)
                   (catch Exception e
                     (if (and (< attempt stale-plan-attempts) (stale-plan? e))
                       ::stale-plan
                       (throw e))))]
      (if (identical? ::stale-plan result)
        (do (t/event! ::stale-plan-rerun {:level :debug :data {:attempt attempt}})
            (recur (inc attempt)))
        result))))

(defn ^:no-doc current-version
  "Reads the current fhir_version column for a resource row. Returns the version
   string or nil if no row exists."
  [node resource-type id]
  (let [query (format "SELECT fhir_version, _system_from FROM %s WHERE _id = ?" (table-name resource-type))
        row (first (run-query node [query id]))]
    (when row
      (row-version row))))

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
      (let [query (format "SELECT _id, fhir_version, _system_from FROM %s WHERE _id = ANY(?)"
                          (table-name resource-type))
            rows (run-query node [query (vec ids)])]
        (into {}
              (keep (fn [row]
                      (let [rid (or (:xt/id row) (:_id row) (get row "_id"))
                            v (row-version row)]
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
            rows (run-query node [query (vec ids)])]
        (into {}
              (keep (fn [row]
                      (let [res (xtdb->fhir row read-decoders)]
                        (when-let [rid (:id res)]
                          [(str rid) res]))))
              rows)))))

(defn ^:no-doc next-version
  "Computes the next version string from a current version (or nil for first).
   Kept for importers that number versions themselves; the store derives
   versionIds from system time (version-of-system-time)."
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

(defn ^:no-doc last-updated-string
  "`meta.lastUpdated` for system time `t` (an Instant, or the ZonedDateTime
   `_system_from` reads as): UTC, the fractional second without trailing
   zeros. fhir-server's response coercion decodes a FHIR instant to an
   OffsetDateTime and writes it back in this form on a single-resource
   response, and passes history and search Bundles through as the store
   rendered them. `Instant.toString` keeps trailing zeros (`.120380`), which a
   read then answered as `.12038`, so the two disagreed about one version."
  [t]
  (let [t (if (instance? ZonedDateTime t) (.toInstant ^ZonedDateTime t) t)]
    (if (instance? Instant t)
      (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME (.atOffset ^Instant t ZoneOffset/UTC))
      (str t))))

(defn ^:no-doc committed
  "What a write of `resource` returns once `tx-key` has committed it as
   `version`: `meta.lastUpdated` is the transaction's system time, which is
   the `_system_from` every later read of that version reports, never the
   one the caller sent."
  [resource id version tx-key]
  (with-basis
    (-> resource
        (assoc :id id)
        (assoc-in [:meta :versionId] version)
        (assoc-in [:meta :lastUpdated] (last-updated-string (:system-time tx-key))))
    tx-key))

;; ---------------------------------------------------------------------------
;; Write lifecycle (see fhir-store.lifecycle for the ordering contract).
;;
;; A write path receives an lc-ctx, {:lifecycle :tenant-id :store :opts},
;; built once per store call; :opts is the verb's opts, handed to the lifecycle
;; on the write map. With no lifecycle configured every helper below is a
;; pass-through, so the write behaves exactly as it did before the seam.
;; ---------------------------------------------------------------------------

(defn- lifecycle-ctx [store tenant-id opts]
  {:lifecycle (:resource/lifecycle store)
   :tenant-id (str tenant-id)
   :store     store
   :opts      opts})

(defn ^:no-doc lifecycle-write
  "Run the lifecycle's prepare, then its tx-ops, for one create or update
   whose preconditions have passed and whose id is final. `db` is the node or
   connection the write executes through. Returns the write map with
   :resource the PREPARED body (the one to encode), :submitted the body as
   sent, and :tx-ops the ops to append to the same execute-tx (nil for none).
   Exceptions propagate: they refuse the write before anything is sent."
  [{:keys [lifecycle tenant-id store opts]} db method resource-type id resource]
  (let [write     {:tenant-id     tenant-id
                   :resource-type (name resource-type)
                   :id            id
                   :method        method
                   :resource      resource
                   :opts          opts
                   :db            db
                   :entity        nil
                   :store         store}
        prepared  (lc/prepare-write lifecycle write)
        ops-write (assoc write :resource prepared :submitted resource)]
    (assoc ops-write :tx-ops (lc/write-tx-ops lifecycle ops-write))))

(defn ^:no-doc lifecycle-tx-ops
  "`write`'s lifecycle ops, refused when any of them holds an unpaired UTF-16
   surrogate, which would reach XTDB as \"?\" like any other text (see
   xf/unpaired-surrogate-paths). Every XTDB tx-op form is plain data -- a SQL
   string, or a vector of strings, keywords, docs and arg rows -- so walking
   an op reaches all of its text.

   Call it after the write's own body is encoded, which refuses a body holding
   one with its element paths. A hit here therefore came from elsewhere: from
   the submitted body, which `prepare` rewrote but the ops still carry -- the
   client's text, so the same 400 a body refusal gives -- or from the
   lifecycle's own data, a 500. Call it outside any catch that maps execute-tx
   failures to a 409 or 412. Names ops by position, never their text."
  [{:keys [resource-type id submitted tx-ops]}]
  (let [bad (into [] (keep-indexed (fn [i op]
                                     (when (some xf/unencodable? (tree-seq coll? seq op)) i)))
                  tx-ops)]
    (when (seq bad)
      (refuse-unencodable-text! resource-type id submitted)
      (throw (ex-info (str "Lifecycle tx-ops " (str/join ", " bad) " for " resource-type
                           " hold an unpaired UTF-16 surrogate: not valid Unicode, so they"
                           " cannot be sent unchanged")
                      {:fhir/status 500 :fhir/code "exception"
                       :resource-type resource-type :id id
                       :lifecycle/tx-op-indexes bad})))
    tx-ops))

(defn ^:no-doc fire-after-commit!
  "Hand one committed transaction's writes to the lifecycle. Contained: a
   failing after-commit is logged, never rethrown."
  [{:keys [lifecycle tenant-id store]} writes tx-key]
  (lc/fire-after-commit! lifecycle {:tenant-id tenant-id
                                    :writes    (vec writes)
                                    :result    tx-key
                                    :store     store}))

(defn ^:no-doc lifecycle-op-failure?
  "True when execute-tx failed on an op at or past `own-op-count`, i.e. one a
   lifecycle appended. That failure is rethrown as is: reporting it as the
   resource's own conflict (409 already exists, 412 version) would be false."
  [e own-op-count]
  (when-let [idx (:tx-op-idx (ex-data e))]
    (>= idx own-op-count)))

(defn ^:no-doc delete-tx-ops
  "The ops of one delete: the guard, the stamp, then the DELETE, and the
   index of the stamp op (nil without one). The stamp precedes the DELETE
   because it is written only for a resource current at that point (see
   txm/delete-op)."
  [assert-op stamp-op delete-op]
  (let [ops (cond-> [] assert-op (conj assert-op) stamp-op (conj stamp-op))]
    [(conj ops delete-op) (when stamp-op (dec (count ops)))]))

(defn ^:no-doc stamp-op-failure?
  "True when execute-tx failed on the stamp op at `stamp-idx`. Rethrown as
   is: it is no conflict of the resource's."
  [e stamp-idx]
  (and stamp-idx (= stamp-idx (:tx-op-idx (ex-data e)))))

(defn ^:no-doc version-assert-op
  "The ASSERT that fails a transaction unless `id` still holds `vid`."
  [resource-type id vid]
  [:sql (format "ASSERT EXISTS (SELECT 1 FROM %s WHERE _id = ? AND %s)"
                (table-name resource-type) version-match-sql)
   (into [id] (version-match-args vid))])

(defn- absent-assert-op
  "The ASSERT of a transaction `HEAD` entry with `ifNoneMatch` `*`: the
   transaction fails unless no current `resource-type`/`id` exists. Throws
   400 for any other `ifNoneMatch`, or one on a `GET`."
  [{:keys [method resource-type id if-none-match]}]
  (when-not (and (= "HEAD" method) (= "*" (str/trim (str if-none-match))))
    (throw (ex-info (str "A transaction entry's ifNoneMatch is supported only as \"*\" on HEAD, not "
                         (pr-str if-none-match) " on " method)
                    {:fhir/status 400 :fhir/code "not-supported"
                     :resource-type resource-type :id id})))
  [:sql (format "ASSERT NOT EXISTS (SELECT 1 FROM %s WHERE _id = ?)" (table-name resource-type))
   [id]])

(defn- entry-if-match
  "The version a transaction PUT entry's `request.ifMatch` guards on, checked
   against the `current` version before the lifecycle sees the write: nil when
   the entry is unguarded, else the version it must still hold at commit.
   Throws 412 when the guard already fails."
  [{:keys [if-match resource-type id]} current]
  (when if-match
    (when (nil? current)
      (throw (ex-info "Version conflict: resource does not exist"
                      {:fhir/status 412 :fhir/code "conflict"
                       :resource-type resource-type :id id
                       :expected (fp/if-match-label if-match) :actual nil})))
    (let [expected (if (= fp/if-match-any if-match) current if-match)]
      (when (not= expected current)
        (throw (ex-info "Version conflict"
                        {:fhir/status 412 :fhir/code "conflict"
                         :resource-type resource-type :id id
                         :expected expected :actual current})))
      expected)))

(defn- inject-meta
  "Injects :meta :versionId and :meta :lastUpdated onto a decoded FHIR resource.
   XTDB returns _system_from as a ZonedDateTime whose str form carries a zone
   suffix (\"...Z[UTC]\") that is not a valid FHIR instant; see
   [[last-updated-string]]."
  [result version system-from]
  (cond-> result
    version     (assoc-in [:meta :versionId] (str version))
    system-from (assoc-in [:meta :lastUpdated] (last-updated-string system-from))))

(defn- parse-date-prefix
  "Parses a FHIR date search value into [prefix date-string].
   FHIR date prefixes: eq, ne, lt, gt, ge, le, sa, eb, ap. Default is eq."
  [value-str]
  (let [prefixes #{"eq" "ne" "lt" "gt" "ge" "le" "sa" "eb" "ap"}
        maybe-prefix (when (>= (count value-str) 2) (subs value-str 0 2))]
    (if (contains? prefixes maybe-prefix)
      [maybe-prefix (subs value-str 2)]
      ["eq" value-str])))

(defn- date-expr-condition
  "Builds a parameterized SQL condition applying a FHIR date search value to
   the date/dateTime/instant SQL expression `col`.
   Returns [sql-fragment params-vector].
   Uses native date types for comparison against XTDB DATE/TIMESTAMP values."
  [col value-str]
  (let [[prefix date-val] (parse-date-prefix value-str)
        {:keys [lower upper precision]} (dt/parse-search-date date-val)]
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

(defn- build-date-condition
  "Builds a parameterized SQL condition for a date-type FHIR search parameter
   on a top-level column. Returns [sql-fragment params-vector]."
  [column-name value-str]
  (date-expr-condition (format "\"%s\"" column-name) value-str))

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
        ;; Coding WHERE clause; `|code` (a blank system) requires the system
        ;; to be absent
        coding-where (fn [alias]
                       (cond
                         (nil? system-val)
                         [(format "(%s.val).\"code\" = ?" alias) [v-val]]

                         (str/blank? system-val)
                         [(format "(%s.val).\"system\" IS NULL AND (%s.val).\"code\" = ?" alias alias) [v-val]]

                         :else
                         [(format "(%s.val).\"system\" = ? AND (%s.val).\"code\" = ?" alias alias) [system-val v-val]]))]
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
        (cond
          (nil? system-val)
          [(format "(\"%s\").\"code\" = ?" col-name) [v-val]]

          (str/blank? system-val)
          [(format "(\"%s\").\"system\" IS NULL AND (\"%s\").\"code\" = ?" col-name col-name) [v-val]]

          :else
          [(format "(\"%s\").\"system\" = ? AND (\"%s\").\"code\" = ?" col-name col-name) [system-val v-val]]))

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

(defn- period-start-condition
  "Builds a parameterized SQL condition applying a FHIR date search value to
   a Period through `start-col`, the SQL expression for its start.
   Returns [sql-fragment params-vector] or nil."
  [start-col value-str]
  (let [[prefix date-val] (parse-date-prefix value-str)
        {:keys [lower upper precision]} (dt/parse-search-date date-val)]
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

(defn- build-period-condition
  "Builds a parameterized SQL condition for a Period-type column with date prefix logic.
   Returns [sql-fragment params-vector] or nil."
  [col-name value-str]
  (period-start-condition (format "(\"%s\").\"start\"" col-name) value-str))

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

(defn- prefixed-number-condition
  "Compares the numeric SQL expression `num-expr` with BigDecimal `value`
   under a FHIR prefix (eq/ne/gt/lt/ge/le/sa/eb/ap; ap is +/-10%).
   Returns [sql-fragment params-vector]."
  [num-expr prefix ^BigDecimal value]
  (case prefix
    "eq" [(format "%s = ?" num-expr) [value]]
    "ne" [(format "%s <> ?" num-expr) [value]]
    ("gt" "sa") [(format "%s > ?" num-expr) [value]]
    "ge" [(format "%s >= ?" num-expr) [value]]
    ("lt" "eb") [(format "%s < ?" num-expr) [value]]
    "le" [(format "%s <= ?" num-expr) [value]]
    "ap" [(format "(%s >= ? AND %s <= ?)" num-expr num-expr)
          [(.multiply value 0.9M) (.multiply value 1.1M)]]
    [(format "%s = ?" num-expr) [value]]))

(defn- quantity-expr-condition
  "Builds a parameterized SQL condition applying a FHIR quantity search value
   to the Quantity-valued SQL expression `expr`. Returns [sql-fragment
   params-vector] or nil. Compares the Quantity's .value numerically with the
   FHIR prefix; an optional |system|code constrains the unit."
  [expr value-str]
  (let [{:keys [prefix value system code]} (parse-quantity-value value-str)]
    (when value
      (let [conds (cond-> [(prefixed-number-condition (format "(%s).\"value\"" expr)
                                                      prefix value)]
                    system (conj [(format "(%s).\"system\" = ?" expr) [system]])
                    code   (conj [(format "(%s).\"code\" = ?" expr) [code]]))]
        (if (= 1 (count conds))
          (first conds)
          [(str "(" (str/join " AND " (map first conds)) ")")
           (into [] (mapcat second) conds)])))))

(defn- build-quantity-col-condition
  "Builds a parameterized SQL condition for a single quantity-type column.
   Returns [sql-fragment params-vector] or nil. Columns without a numeric
   value (e.g. valueSampledData) still emit a .value comparison, which simply
   matches nothing for those rows."
  [col value-str]
  (quantity-expr-condition (format "\"%s\"" (:col col)) value-str))

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
        (period-start-condition (format "(\"%s\").\"%s\".\"start\"" col-name sub-col) v-str)
        (date-expr-condition (format "(\"%s\").\"%s\"" col-name sub-col) v-str))

      ;; Period type
      (= fhir-type "Period")
      (build-period-condition col-name v-str)

      ;; dateTime, instant, date
      :else
      (build-date-condition col-name v-str))))

(defn- string-prefix-sql
  "SQL fragment applying FHIR string-search semantics -- case-insensitive
   prefix match -- to a single string-valued expression. Uses POSITION
   rather than LIKE because the search value is a literal, not a pattern:
   LIKE honours %/_ metacharacters in the parameter and XTDB's ESCAPE
   clause silently matches nothing (2.2.0-beta1). The paired parameter
   must be lowercased by the caller."
  [expr]
  (format "POSITION(? IN LOWER(%s)) = 1" expr))

(defn- string-prefix-array-sql
  "Like string-prefix-sql but for an expression evaluating to an array of
   strings (e.g. HumanName.given, Address.line): matches when any element
   has the prefix. Absent/null arrays UNNEST to no rows."
  [array-expr]
  (format "EXISTS (SELECT 1 FROM UNNEST(%s) AS sp(v) WHERE %s)"
          array-expr (string-prefix-sql "sp.v")))

(defn- human-name-prefix-sql
  "OR-joined prefix conditions over a HumanName struct expression, searching
   family (string) and given (array of strings) like the Datomic backend."
  [elem-expr]
  (str (string-prefix-sql (format "(%s).\"family\"" elem-expr))
       " OR "
       (string-prefix-array-sql (format "(%s).\"given\"" elem-expr))))

(defn- address-prefix-sql
  "OR-joined prefix conditions over an Address struct expression, searching
   the same sub-fields as the Datomic backend: text, line (array), city,
   district, state, postalCode, country. Sub-field accessors are quoted so
   camelCase names (postalCode) are not case-folded by the SQL parser."
  [elem-expr]
  (str/join " OR "
            (concat
             (map #(string-prefix-sql (format "(%s).\"%s\"" elem-expr %))
                  ["text" "city" "district" "state" "postalCode" "country"])
             [(string-prefix-array-sql (format "(%s).\"line\"" elem-expr))])))

(defn- build-string-col-condition
  "Builds parameterized SQL for a single string-type column applying FHIR
   string-search semantics: case- (not accent-) insensitive prefix match,
   mirroring the Datomic backend's lower-case + startsWith clauses.
   Returns [sql-fragment params-vector].
   v-val is the raw string value."
  [col v-val]
  (let [col-name (:col col)
        fhir-type (:fhir-type col)
        array? (:array? col)
        sub-col (:sub-col col)
        sub-array? (:sub-array? col false)
        sql (cond
              ;; Nested string in array: e.g., Patient.name.family, or a
              ;; nested array like Patient.name.given (sub-array?).
              (and sub-col array?)
              (let [sub-expr (format "(n.val).\"%s\"" sub-col)]
                (format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS n(val) WHERE %s))"
                        col-name
                        (if sub-array?
                          (string-prefix-array-sql sub-expr)
                          (string-prefix-sql sub-expr))))

              ;; Nested string in struct: e.g., Location.address.city
              sub-col
              (let [sub-expr (format "(\"%s\").\"%s\"" col-name sub-col)]
                (if sub-array?
                  (string-prefix-array-sql sub-expr)
                  (string-prefix-sql sub-expr)))

              ;; HumanName: search across family and given
              (= fhir-type "HumanName")
              (if array?
                (format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS n(val) WHERE %s))"
                        col-name (human-name-prefix-sql "n.val"))
                (format "(%s)" (human-name-prefix-sql (format "\"%s\"" col-name))))

              ;; Address: search across text, line, city, district, state,
              ;; postalCode, country
              (= fhir-type "Address")
              (if array?
                (format "(EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS a(val) WHERE %s))"
                        col-name (address-prefix-sql "a.val"))
                (format "(%s)" (address-prefix-sql (format "\"%s\"" col-name))))

              ;; Repeating simple string: e.g., Location.alias
              array?
              (string-prefix-array-sql (format "\"%s\"" col-name))

              ;; Simple string
              :else
              (string-prefix-sql (format "\"%s\"" col-name)))
        ;; Every placeholder binds the same lowercased needle, so the param
        ;; vector follows directly from the placeholder count.
        needle (str/lower-case v-val)]
    [sql (vec (repeat (count (filter #{\?} sql)) needle))]))

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
   denormalized array stores only bare codes, so any value that constrains the
   system (`system|code`, `system|`, or `|code`, which requires the system to
   be absent) returns nil and the caller falls back to the struct path."
  [v]
  (when-not (str/includes? v "|")
    v))

(def ^:private token-slots
  "Leading `<col>_tokens` elements a flat token search compares by index.
   Real Coding/CodeableConcept fields rarely carry more codes than this, and
   each slot is one more scalar comparison in the scan."
  4)

(defn- flat-token-column-condition
  "Token match on one `<col>_tokens` array as
   `(\"t\"[1] IN (?..) OR .. OR \"t\"[K] IN (?..) OR _id IN (<fallback>))`.

   A correlated `EXISTS (SELECT 1 FROM UNNEST(\"t\") ...)` (or `? = ANY(\"t\")`)
   is planned as a mark-join applied to every row of the table, 10-30x the cost
   of a scalar predicate, and it defeats the scan's early filtering under ORDER
   BY and COUNT. Indexed access stays a scalar predicate XTDB evaluates in the
   scan. Arrays longer than token-slots are found by the fallback, an
   uncorrelated subquery XTDB runs once: its scalar CARDINALITY filter is
   applied in the scan, so the UNNEST only touches the long arrays.

   `table-ref` is [sql params] for the table being searched, temporal qualifier
   included, so the fallback reads the same versions as the outer query."
  [col needles [table-sql table-params]]
  (let [tokens (format "\"%s_tokens\"" col)
        [match match-params] (if (= 1 (count needles))
                               ["= ?" needles]
                               [(format "IN (%s)" (str/join "," (repeat (count needles) "?"))) needles])
        slots (for [i (range 1 (inc token-slots))]
                (format "%s[%d] %s" tokens i match))
        fallback (format "_id IN (SELECT _id FROM %s WHERE CARDINALITY(%s) > %d AND EXISTS (SELECT 1 FROM UNNEST(%s) AS t(v) WHERE t.v %s))"
                         table-sql tokens token-slots tokens match)]
    [(str "(" (str/join " OR " (concat slots [fallback])) ")")
     (-> []
         (into (mapcat (constantly match-params)) slots)
         (into table-params)
         (into match-params))]))

(defn- build-flat-token-condition
  "Flat-array token condition: flat-token-column-condition per column, OR'd
   across columns; comma-separated values become one IN list. Returns
   [sql params] or nil if any value is system-qualified (so the caller falls
   back to the struct path)."
  [cols value-str table-ref]
  (let [needles (mapv token->needle (str/split value-str #","))]
    (when (every? some? needles)
      (let [per-col (map #(flat-token-column-condition (:col %) needles table-ref) cols)]
        (if (= 1 (count per-col))
          (first per-col)
          [(str "(" (str/join " OR " (map first per-col)) ")")
           (into [] (mapcat second) per-col)])))))

;; ---------------------------------------------------------------------------
;; Resource-level search parameters
;;
;; FHIR defines _lastUpdated, _tag, _profile and _security on Resource, so they
;; mean the same thing on every type and need no registry entry (the server
;; grants the meta three on every type; see
;; server.search-registry/resource-level-params). They are answered here, ahead
;; of the registry: without that, build-condition-struct quotes the parameter
;; name as a column, and from XTDB 2.2.0-rc0 a column nothing declared fails the
;; whole query at planning, which search turns into an empty page.
;; ---------------------------------------------------------------------------

(def ^:private last-updated-search-param
  "Resource.meta.lastUpdated is the row's system time here, so the filter reads
   `_system_from`, the column server.search-registry resolves the expression
   to when a registry does declare `_lastUpdated`."
  {:type "date"
   :columns [{:col "_system_from" :fhir-type "instant" :array? false}]})

(def ^:private meta-coding-fields
  "Token parameters over a Coding array in the stored `meta` struct."
  {"_tag" "tag"
   "_security" "security"})

(defn ^:no-doc resource-level-param?
  "True when `pname` is a search parameter every resource type answers from
   resource-level storage rather than its search registry."
  [pname]
  (or (= "_lastUpdated" pname)
      (= "_profile" pname)
      (contains? meta-coding-fields pname)))

(defn- meta-coding-predicate
  "[sql params] matching one FHIR token value against the Coding `m.val`, or
   nil for a value naming neither a system nor a code:
   `code` any system, `system|code` both, `system|` any code in that system,
   `|code` that code with no system."
  [token]
  (let [[a b :as parts] (str/split token #"\|" 2)
        piped? (= 2 (count parts))
        system (when piped? (not-empty a))
        code (not-empty (if piped? b a))
        clauses (cond-> []
                  system                    (conj ["(m.val).\"system\" = ?" [system]])
                  (and piped? (not system)) (conj ["(m.val).\"system\" IS NULL" []])
                  code                      (conj ["(m.val).\"code\" = ?" [code]]))]
    (when (or system code)
      [(str "(" (str/join " AND " (map first clauses)) ")")
       (into [] (mapcat second) clauses)])))

(defn- build-meta-coding-condition
  "`_tag` / `_security`: one EXISTS over the meta Coding array, its comma-
   separated values OR'd inside it. A value matching nothing at all (every
   alternative empty) is FALSE rather than unconstrained."
  [field value-str]
  (let [preds (keep meta-coding-predicate (str/split value-str #","))]
    (if (empty? preds)
      ["FALSE" []]
      [(format "EXISTS (SELECT 1 FROM UNNEST((\"meta\").\"%s\") AS m(val) WHERE %s)"
               field (str/join " OR " (map first preds)))
       (into [] (mapcat second) preds)])))

(defn- build-meta-profile-condition
  "`_profile`: exact uri match against any entry of the meta profile array,
   comma-separated values OR'd. The value is matched whole, so a canonical
   carrying `|version` matches only a profile stored with that version."
  [value-str]
  (let [uris (into [] (remove str/blank?) (str/split value-str #","))]
    (if (empty? uris)
      ["FALSE" []]
      [(format "EXISTS (SELECT 1 FROM UNNEST((\"meta\").\"profile\") AS p(v) WHERE p.v IN (%s))"
               (str/join ", " (repeat (count uris) "?")))
       uris])))

(declare build-condition-struct)

(defn- build-resource-level-condition
  "[sql params] for a parameter resource-level-param? accepts. The registry is
   not consulted: a `_lastUpdated` entry resolves to the same column, and no
   per-type entry can describe the meta struct."
  [pname param-value]
  (let [param-str (if (keyword? param-value) (name param-value) (str param-value))]
    (case pname
      "_lastUpdated" (build-condition-struct pname param-str last-updated-search-param)
      "_profile"     (build-meta-profile-condition param-str)
      (build-meta-coding-condition (get meta-coding-fields pname) param-str))))

;; ---------------------------------------------------------------------------
;; Presence (`X.exists() and X != false`) and composite parameters
;;
;; Both arrive with an empty :columns and their own registry key (see
;; server.search-registry/build-resource-registry). The builders below work on
;; SQL value expressions rather than column names, so one component builder
;; serves a top-level column ("code") and a field of an UNNESTed element
;; ((cs.val)."code") alike.
;; ---------------------------------------------------------------------------

(defn- present-and-not-false-sql
  "SQL that is never NULL: true when the column holds a value other than
   boolean false. A repeating column is skipped (nil): `X != false` has no
   single answer for a collection."
  [{:keys [col array? sub-col fhir-type sub-fhir-type]}]
  (when-not array?
    (let [expr (if sub-col
                 (format "(\"%s\").\"%s\"" col sub-col)
                 (format "\"%s\"" col))]
      (if (= "boolean" (if sub-col sub-fhir-type fhir-type))
        (format "COALESCE(%s, FALSE)" expr)
        (format "%s IS NOT NULL" expr)))))

(defn- build-exists-not-false-condition
  "Token `true` matches a resource where some column holds a value other than
   boolean false (deceasedBoolean true, or any deceasedDateTime); `false`
   matches every other resource, including one without the element. Any other
   value matches nothing."
  [columns value-str]
  (let [present (keep present-and-not-false-sql columns)]
    (if (empty? present)
      ["FALSE" []]
      (let [any-present (str "(" (str/join " OR " present) ")")]
        (case value-str
          "true"  [any-present []]
          "false" [(str "NOT " any-present) []]
          ["FALSE" []])))))

(defn- split-unescaped
  "Splits `s` on each `sep` not escaped by a backslash and unescapes `\\<sep>`
   in the parts (FHIR R4B 3.1.1.5.10). Keeps empty parts."
  [s sep]
  (let [q (java.util.regex.Pattern/quote (str sep))]
    (mapv #(str/replace % (str "\\" sep) (str sep))
          (str/split s (re-pattern (str "(?<!\\\\)" q)) -1))))

(defn- parse-token
  "FHIR token value -> {:system :code}. `system|code` constrains both,
   `|code` requires the system to be absent (:system ::absent), `system|`
   matches any code in the system, and a bare `code` any system."
  [v]
  (if-let [i (str/index-of v "|")]
    (let [system (subs v 0 i)
          code (subs v (inc i))]
      {:system (if (str/blank? system) ::absent system)
       :code (when-not (str/blank? code) code)})
    {:code v}))

(defn- system-value-match
  "AND of the token's constraints on a struct expression with a system field
   and a value field (`code` for Coding, `value` for Identifier)."
  [expr value-field {:keys [system code]}]
  (let [conds (cond-> []
                (string? system)    (conj [(format "(%s).\"system\" = ?" expr) [system]])
                (= ::absent system) (conj [(format "(%s).\"system\" IS NULL" expr) []])
                code                (conj [(format "(%s).\"%s\" = ?" expr value-field) [code]]))]
    (when (seq conds)
      [(str/join " AND " (map first conds)) (into [] (mapcat second) conds)])))

(defn- token-expr-condition
  [fhir-type expr value-str]
  (let [token (parse-token value-str)]
    (case fhir-type
      "CodeableConcept"
      (when-let [[sql params] (system-value-match "cd.val" "code" token)]
        [(format "EXISTS (SELECT 1 FROM UNNEST((%s).\"coding\") AS cd(val) WHERE %s)" expr sql)
         params])
      "Coding"     (system-value-match expr "code" token)
      "Identifier" (system-value-match expr "value" token)
      "boolean"    (case (:code token)
                     "true"  [(format "%s = TRUE" expr) []]
                     "false" [(format "%s = FALSE" expr) []]
                     nil)
      ;; code, string, uri, id, canonical: the system is implied by the element
      (when-let [code (:code token)]
        [(format "%s = ?" expr) [code]]))))

(defn- reference-expr-condition
  [expr value-str target]
  (let [ref-expr (format "(%s).\"reference\"" expr)
        target-type (first target)]
    (if (and target-type (not (str/includes? value-str "/")))
      [(format "(%s = ? OR %s = ?)" ref-expr ref-expr) [value-str (str target-type "/" value-str)]]
      [(format "%s = ?" ref-expr) [value-str]])))

(defn- component-value-condition
  "One composite part applied to one value of a component column, or nil when
   the part cannot be read as that column's type (a Period under `eq`, an
   unparseable number)."
  [{:keys [type target]} fhir-type expr part]
  (case type
    "token"     (token-expr-condition fhir-type expr part)
    "quantity"  (quantity-expr-condition expr part)
    "date"      (if (= "Period" fhir-type)
                  (period-start-condition (format "(%s).\"start\"" expr) part)
                  (date-expr-condition expr part))
    "string"    [(string-prefix-sql expr) [(str/lower-case part)]]
    "reference" (reference-expr-condition expr part target)
    "number"    (let [[prefix number] (parse-date-prefix part)]
                  (when-let [n (try (BigDecimal. (str/trim number)) (catch Exception _ nil))]
                    (prefixed-number-condition expr prefix n)))
    "uri"       [(format "%s = ?" expr) [part]]
    nil))

(defn- column-value-condition
  "Applies `f` to the SQL expression for each value one column descriptor
   selects under `base` (nil: the row itself), UNNESTing repeating levels so
   `f` sees one value at a time. Returns [sql params] or nil."
  [base {:keys [col array? sub-col sub-array?]} f]
  (let [over (fn [expr repeating? alias g]
               (if repeating?
                 (when-let [[sql params] (g (str alias ".val"))]
                   [(format "EXISTS (SELECT 1 FROM UNNEST(%s) AS %s(val) WHERE %s)" expr alias sql)
                    params])
                 (g expr)))
        col-expr (if base (format "(%s).\"%s\"" base col) (format "\"%s\"" col))]
    (over col-expr array? "cv"
          (fn [v]
            (if sub-col
              (over (format "(%s).\"%s\"" v sub-col) sub-array? "cw" f)
              (f v))))))

(defn- or-conditions
  [conds]
  (case (count conds)
    0 nil
    1 (first conds)
    [(str "(" (str/join " OR " (map first conds)) ")") (into [] (mapcat second) conds)]))

(defn- composite-scope-condition
  "Every part matched within one scope: on the row itself, or -- for an
   element scope -- all on the SAME element, so `8480-6$gt100` never pairs
   one component's code with another component's value."
  [{:keys [element components]} parts]
  (let [repeating? (:array? element)
        base (cond
               (nil? element) nil
               repeating? "cs.val"
               :else (format "\"%s\"" (:col element)))
        conds (mapv (fn [component part]
                      (or-conditions
                       (keep (fn [col]
                               (column-value-condition
                                base col
                                #(component-value-condition
                                  component
                                  (if (:sub-col col) (:sub-fhir-type col) (:fhir-type col))
                                  % part)))
                             (:columns component))))
                    components parts)]
    (when (every? some? conds)
      (let [sql (str/join " AND " (map first conds))
            params (into [] (mapcat second) conds)]
        (if repeating?
          [(format "EXISTS (SELECT 1 FROM UNNEST(\"%s\") AS cs(val) WHERE %s)" (:col element) sql)
           params]
          [(str "(" sql ")") params])))))

(defn- build-composite-condition
  "A composite search value: `,` separates alternatives (OR), `$` separates
   the parts of one alternative in the order of the SearchParameter's
   components. An alternative matches in any scope (`combo-*` has two: the
   Observation and each of its components). An alternative with the wrong
   number of parts, or a part its column cannot read, matches nothing."
  [search-param value-str]
  (let [scopes (:composite search-param)]
    (or (or-conditions
         (keep (fn [alternative]
                 (let [parts (split-unescaped alternative \$)]
                   (or-conditions
                    (keep #(when (= (count parts) (count (:components %)))
                             (composite-scope-condition % parts))
                          scopes))))
               (split-unescaped value-str \,)))
        ["FALSE" []])))

(defn- split-or-values
  "Splits a search value on each comma not escaped by a backslash and
   unescapes `\\,` in the parts (FHIR R4B 3.1.1.5.10), so a value such as
   `Processed as Primary\\, Forwarded` is one alternative. Trailing empty
   parts are dropped, as `str/split` drops them."
  [s]
  (mapv #(str/replace % "\\," ",") (str/split s #"(?<!\\),")))

(declare build-value-condition)

(defn- build-condition-struct
  "Builds a parameterized SQL WHERE condition for a given FHIR search parameter.
   Returns [sql-fragment params-vector].
   Handles comma-separated values as OR (per FHIR spec; `\\,` is a literal
   comma inside one value), and pipe-delimited system|code token notation.
   When system|code format is used, both system and code are matched in the
   SQL condition.
   search-param is the enriched descriptor from the search registry (or nil)."
  [param-name param-value search-param]
  (let [param-str (if (keyword? param-value) (name param-value) (str param-value))]
    (if (:composite search-param)
      ;; A composite splits its own value: `,` and `$` may be escaped inside
      ;; a part.
      (build-composite-condition search-param param-str)
      ;; Split once: an alternative is never split again, so an escaped comma
      ;; stays inside its value.
      (let [comma-values (split-or-values param-str)]
        (if (> (count comma-values) 1)
          (let [conditions (map #(build-value-condition param-name % % search-param) comma-values)
                sqls (mapv first conditions)
                params (into [] (mapcat second) conditions)]
            [(str "(" (str/join " OR " sqls) ")") params])
          (build-value-condition param-name param-value
                                 (or (first comma-values) param-str) search-param))))))

(defn- build-value-condition
  "The condition for one alternative of a search value, already split from
   its siblings and unescaped. `param-value` is the caller's value (a boolean
   stays a boolean); `param-str` is the alternative as a string."
  [param-name param-value param-str search-param]
  (let [val-parts (str/split param-str #"\|")
        v-str (last val-parts)
        system-str (when (= 2 (count val-parts)) (first val-parts))
        pname (name param-name)]
    (cond
      ;; Ahead of the boolean branch: a direct caller may pass true/false
      ;; itself, and the parameter name is not a column here.
      (:exists-not-false search-param)
      (build-exists-not-false-condition (:exists-not-false search-param) param-str)

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
      [(format "\"%s\" = ?" pname) [v-str]])))

(defn- build-condition
  "Builds a parameterized SQL WHERE condition for a search parameter.
   Resource-level parameters (see resource-level-param?) are answered from
   resource-level storage whatever the registry says. Bare-code token searches
   on top-level Coding/CodeableConcept fields read the denormalized
   `<col>_tokens` array by index (see flat-token-column-condition), which XTDB
   filters in the scan instead of UNNEST-ing structs row by row; everything
   else falls through to the struct path.

   `table-ref` is [sql params] naming the searched table with its temporal
   qualifier, for conditions that query the table again."
  [param-name param-value search-param table-ref]
  (let [pname (name param-name)
        param-str (if (keyword? param-value) (name param-value) (str param-value))]
    (if (resource-level-param? pname)
      (build-resource-level-condition pname param-value)
      (or (when search-param
            (when-let [cols (flat-token-columns search-param)]
              (build-flat-token-condition cols param-str table-ref)))
          (build-condition-struct param-name param-value search-param)))))
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
          version (row-version record)
          system-from (or (:xt/system_from record) (:xt/system-from record) (get record "_system_from"))
          stripped (-> record
                       (dissoc :xt/id :_id :fhir_version :fhir-version
                               :xt/system_from :xt/system_to :xt/system-from :xt/system-to
                               :xt/valid_from :xt/valid_to :xt/valid-from :xt/valid-to
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

(def ^:private resource-sort-columns
  "Sort fields every resource type accepts, mapped to the system columns that
   hold them: Resource.meta.lastUpdated is the row's system time here, as the
   `_lastUpdated` filter maps it (last-updated-search-param)."
  {"_lastUpdated" "_system_from"
   "_id"          "_id"})

(defn- sort-field->sql-col
  "Maps a FHIR sort field name to a SQL column expression, or nil when the
   field names nothing this store can sort by. Resource-level fields map to
   their system columns; anything else goes through the search registry's
   first column for the field.

   An unknown field is dropped rather than quoted as a column: from XTDB
   2.2.0-rc0 an ORDER BY on a column nothing declared fails at planning, which
   search turns into an empty page. FHIR lets a server ignore a sort it does
   not support."
  [field search-registry]
  (if-let [sys-col (get resource-sort-columns field)]
    (format "\"%s\"" sys-col)
    (when-let [col (first (:columns (get search-registry field)))]
      (let [col-name (:col col)
            sub-col (:sub-col col)]
        (if sub-col
          ;; Nested field: use struct accessor
          (format "(\"%s\").\"%s\"" col-name sub-col)
          (format "\"%s\"" col-name))))))

(defn- build-order-by-clause
  "Builds a SQL ORDER BY clause from parsed sort specs.
   Returns nil if no valid sort specs. Each spec sort-field->sql-col cannot
   map is left out with a warn naming the field."
  [sort-specs search-registry]
  (when (seq sort-specs)
    (let [clauses (keep (fn [{:keys [field dir]}]
                          (if-let [col-expr (sort-field->sql-col field search-registry)]
                            (str col-expr (if (= dir :desc) " DESC" " ASC"))
                            (do (t/event! ::sort-field-ignored
                                          {:level :warn
                                           :data {:field field}})
                                nil)))
                        sort-specs)]
      (when (seq clauses)
        (str " ORDER BY " (str/join ", " clauses))))))

(def ^:private default-pool-opts
  "Defaults for the per-tenant HikariCP connection pool. xt/q and xt/execute-tx
   open a fresh pgwire JDBC connection per call when handed the node; reusing
   pooled connections eliminates that per-request session churn. A bounded pool
   caps concurrent pgwire sessions while virtual threads cheaply park on borrow.
   min-idle is modest (the win is connection reuse up to max-size, not pre-warming);
   raise it per deployment if first-request cold-open latency matters.

   prepare-threshold is pgjdbc's prepareThreshold: the executions of one SQL
   text on a connection after which it becomes a named server-side statement.
   A named statement keeps the row type it was described with, and a pooled
   connection keeps it across requests, so it goes stale as soon as a write
   adds a column (see stale-plan?). 0 never names one. That costs a parse and
   describe per execution, about 0.1 ms; XTDB's plan cache is node-wide and
   keyed by the query, not the statement, so searches still hit it."
  {:max-size 24 :min-idle 4 :connection-timeout-ms 10000 :prepare-threshold 0})

(defn- connection-source
  "`node` as a DataSource whose connections carry pgjdbc's prepareThreshold."
  ^javax.sql.DataSource [^xtdb.api.DataSource node prepare-threshold]
  (letfn [(build [^DataSource$ConnectionBuilder builder]
            ;; pgjdbc reads connection properties as strings.
            (.build (.option builder "prepareThreshold" (str prepare-threshold))))]
    (reify javax.sql.DataSource
      (getConnection [_] (build (.createConnectionBuilder node)))
      (getConnection [_ user password]
        (build (-> (.createConnectionBuilder node) (.user user) (.password password))))
      (getLogWriter [_] (.getLogWriter node))
      (setLogWriter [_ writer] (.setLogWriter node writer))
      (getLoginTimeout [_] (.getLoginTimeout node))
      (setLoginTimeout [_ seconds] (.setLoginTimeout node seconds))
      (getParentLogger [_] (.getParentLogger node))
      (unwrap [_ iface] (.unwrap node iface))
      (isWrapperFor [_ iface] (.isWrapperFor node iface)))))

(defn- incorrect-anomaly-detail?
  "True when a server error's detail is an XTDB `incorrect` anomaly. pgwire
   encodes the anomaly in the fallback output format, which XTDB's JDBC
   driver sets to transit on every connection it opens."
  [^ServerErrorMessage message]
  (when-let [detail (.getDetail message)]
    (try
      (instance? Incorrect (serde/read-transit detail :json))
      (catch Exception _ false))))

(defn ^:no-doc statement-refusal?
  "True when the XTDB server sent `e` to refuse one statement: SQLSTATE 0A000,
   or 08P01 carrying an `incorrect` anomaly.

   pgwire sends 0A000 for a stale plan and for every `unsupported` anomaly: an
   SQL feature it lacks, a parameter type it cannot read. It sends 08P01 for
   every `incorrect` anomaly without a code of its own: a parse or plan error,
   division by zero, a failed cast. Either way it answers one statement and
   keeps serving the connection: the error goes out as an ErrorResponse,
   messages are skipped until Sync, and an open transaction is marked failed
   for the caller's ROLLBACK.

   Any other 08P01 is left to Hikari. pgwire's own protocol violations carry
   no anomaly, and the ones that do end a session (a malformed or unreadable
   message) close the socket without sending anything. Neither state counts
   when pgjdbc raises it itself, since it then carries no server message."
  [^SQLException e]
  (boolean
   (when (instance? PSQLException e)
     (when-let [message (.getServerErrorMessage ^PSQLException e)]
       (case (.getSQLState e)
         "0A000" true
         "08P01" (incorrect-anomaly-detail? message)
         false)))))

(def ^:private keep-connection-on-statement-refusal
  "Hikari evicts a connection on SQLSTATE 0A000 and on any 08 state, but
   XTDB sends 0A000 and 08P01 to refuse one statement on a healthy connection
   (see statement-refusal?). Evicting it also hides the refusal: xt/q then
   runs ROLLBACK on the closed connection, and \"Connection is closed\" is all
   that reaches the caller."
  (reify SQLExceptionOverride
    (adjudicate [_ e]
      (if (statement-refusal? e)
        SQLExceptionOverride$Override/DO_NOT_EVICT
        SQLExceptionOverride$Override/CONTINUE_EVICT))))

(extend-protocol conns/ConnSource
  HikariDataSource
  (acquire [pool] (.getConnection pool))
  (release [_ conn _failure] (.close ^java.sql.Connection conn)))

(defn- make-pool
  "Builds a HikariCP pool over the XTDB node (which is a javax.sql.DataSource)."
  ^HikariDataSource [node tenant-id pool-opts]
  (let [{:keys [max-size min-idle connection-timeout-ms prepare-threshold]}
        (merge default-pool-opts pool-opts)
        cfg (doto (HikariConfig.)
              (.setDataSource (connection-source node prepare-threshold))
              (.setExceptionOverride keep-connection-on-statement-refusal)
              (.setMaximumPoolSize (int max-size))
              (.setMinimumIdle (int min-idle))
              (.setConnectionTimeout (long connection-timeout-ms))
              (.setPoolName (str "xtdb-" tenant-id)))]
    (HikariDataSource. cfg)))

(defn- make-conn-source
  "The tenant's connection source for its :transport (see
   fhir-store-xtdb2.conns): a Hikari pool over `node`'s pgwire server, ADBC
   connections on the node itself, or FlightSQL sessions on the node's
   FlightSQL port. A remote tenant's FlightSQL `node` already is its pool."
  [store node tenant-id]
  (let [pool-opts (merge default-pool-opts (:pool-opts store))]
    (case (:transport store)
      :pgwire (make-pool node tenant-id (:pool-opts store))
      :adbc (conns/adbc-source node)
      :flight-sql (if (:server store)
                    node
                    (conns/flight-sql-pool
                     (assoc (select-keys pool-opts [:max-size :connection-timeout-ms])
                            :port (.getFlightSqlPort ^xtdb.api.Xtdb node)))))))

(defn- close-node!
  "Closes an embedded node, or a remote tenant's FlightSQL pool. A remote
   tenant's pgwire DataSource holds nothing open."
  [node]
  (when (instance? java.lang.AutoCloseable node)
    (.close ^java.lang.AutoCloseable node)))

(defn- close-entry!
  "Closes a tenant entry's connection source then its node, swallowing errors.
   A remote FlightSQL tenant's two are one pool, and closing it twice is safe."
  [{:keys [node pool]}]
  (when pool (try (.close ^java.lang.AutoCloseable pool) (catch Throwable _ nil)))
  (when node (try (close-node! node) (catch Throwable _ nil))))

;; ---------------------------------------------------------------------------
;; Node start: node config and table declarations.
;; ---------------------------------------------------------------------------

(defn ->xtdb-config
  "The xtdb.api.Xtdb$Config a store node starts from: `node-config` (a node
   config map, or an Xtdb$Config, which is then modified in place).

   Pipelined replica-log appends need no setting: from XTDB 2.2.0-rc2 a log
   leader always pipelines, and the beta3 switch (IndexerConfig's
   pipelinedReplicaAppends, XTDB_PIPELINED_REPLICA_APPENDS) is gone.

   `:flight-sql?` starts the node's FlightSQL server on a free loopback port,
   unless `node-config` already configures one."
  ^xtdb.api.Xtdb$Config [node-config {:keys [flight-sql?]}]
  (let [cfg (xtn/->config node-config)]
    (when (and flight-sql? (nil? (.getFlightSql cfg)))
      ;; No-arg ctor plus setters: XTDB after 2.2.0-beta3 dropped the
      ;; (host, port) constructor.
      (.setFlightSql cfg (doto (FlightSqlConfig.) (.setHost "127.0.0.1") (.setPort 0))))
    cfg))

(defn start-node
  "Starts an in-process XTDB node the way the store starts each tenant's: see
   ->xtdb-config for `opts`. Public so bulk importers (fhir-datomic-decant's
   xtdb target) start their nodes with the same settings."
  ([node-config] (start-node node-config {}))
  ([node-config opts]
   (xtn/start-node (->xtdb-config node-config opts))))

(defn- quote-ident
  "Double-quoted, case-exact SQL identifier."
  [s]
  (str "\"" (str/replace s "\"" "\"\"") "\""))

(defn ^:no-doc schema-declarations
  "table-name -> column vector to declare, for every schema that names its
   :resourceType. Keys are table-name's spelling, so a declaration lands on
   exactly the table the reads and writes use."
  [schemas]
  (reduce-kv (fn [acc rt cols]
               (update acc (table-name rt)
                       (fn [prev] (into [] (distinct) (concat prev cols)))))
             {}
             (xf/build-declared-columns schemas)))

;; ---------------------------------------------------------------------------
;; Remote mode: tenants as databases on a standalone XTDB server.
;;
;; With :server set, the store starts no node. Each tenant is a database
;; ATTACHed to the server's primary `xtdb` database, reached over pgwire
;; through xt/client; its log and storage come from :attach-template (by
;; default a Kafka topic on the server's `kafkaCluster` and a local path). The
;; server owns every node-level setting, so the store takes none of them in
;; this mode.
;; ---------------------------------------------------------------------------

(def ^:no-doc default-attach-template
  "The ATTACH config a tenant database gets unless :server names another.
   `{db}` is the database name and `{uid}` is fresh per attach, so a tenant
   replaced by create-tenant never replays the log of the one it replaced."
  (str "log: !Kafka\n"
       "  cluster: kafkaCluster\n"
       "  topic: 'fhir.{db}.{uid}'\n"
       "  autoCreateTopic: true\n"
       "storage: !Local\n"
       "  path: '/var/lib/xtdb/dbs/{db}-{uid}'"))

(defn ^:no-doc tenant-db-name
  "The server database holding tenant `tid`: :database-prefix (default
   \"fhir_\") followed by the tenant id with every character outside a-z and
   0-9 written as _ and four hex digits, so distinct tenant ids never share a
   database and every name is a plain SQL identifier."
  [server tid]
  (str (:database-prefix server "fhir_")
       (apply str (map (fn [c]
                         (if (or (<= (int \a) (int c) (int \z)) (<= (int \0) (int c) (int \9)))
                           c
                           (format "_%04x" (int c))))
                       (str tid)))))

(defn- server-client
  "An xt/client DataSource on database `db` of the :server."
  ^xtdb.api.DataSource [server db]
  (xt/client (assoc (select-keys server [:host :port :user :password]) :dbname db)))

(defn- cause-text [^Throwable e]
  (str/join " | " (keep ex-message (take-while some? (iterate ex-cause e)))))

(defn- attached-databases
  "Names of the databases attached to the server, from its status."
  [server]
  (set (keys (:latest-completed-txs (xt/status (server-client server "xtdb"))))))

(defn- run-on-primary!
  "Runs `sql` as a statement of its own on the server's primary database.
   ATTACH and DETACH refuse to run inside a transaction, so they cannot go
   through xt/execute-tx."
  [server sql]
  (with-open [conn (.getConnection (server-client server "xtdb"))]
    (.execute (.createStatement conn) sql)))

(defn- retry-until
  "Calls `f` until it returns without throwing, while `retry?` holds for what
   it threw and :attach-timeout-ms (default 60s) has not passed."
  [server retry? f]
  (let [deadline (+ (System/currentTimeMillis) (long (:attach-timeout-ms server 60000)))]
    (loop []
      (let [r (try {:ok (f)} (catch Exception e {:error e}))]
        (cond
          (contains? r :ok) (:ok r)
          (and (retry? (:error r)) (< (System/currentTimeMillis) deadline))
          (do (Thread/sleep 250) (recur))
          :else (throw (:error r)))))))

(defn- attach-tenant-database!
  "Attaches database `db` with a fresh {uid}, retrying while a detach of the
   same name is still finishing (2.2 detaches asynchronously), then waits
   until the new database answers a query."
  [server db]
  (let [cfg (-> (or (:attach-template server) default-attach-template)
                (str/replace "{db}" db)
                (str/replace "{uid}" (subs (str (random-uuid)) 0 8)))]
    (retry-until server #(str/includes? (cause-text %) "being-detached")
                 #(run-on-primary! server (str "ATTACH DATABASE " db " WITH $$\n" cfg "\n$$")))
    (retry-until server (constantly true)
                 #(xt/q (server-client server db) ["SELECT 1 AS ready"]))
    nil))

(defn- detach-tenant-database!
  [server db]
  (run-on-primary! server (str "DETACH DATABASE " db)))

(defn- remote-tenant-node
  "The tenant's database on the server, attaching it first when the server has
   no database of that name: a pgwire DataSource, or over :flight-sql a pool of
   FlightSQL sessions on it. ATTACH, DETACH and the attached-database list stay
   on pgwire either way."
  [store server tid]
  (let [db (tenant-db-name server tid)]
    (when-not (contains? (attached-databases server) db)
      (attach-tenant-database! server db))
    (if (= :flight-sql (:transport store))
      (conns/flight-sql-pool
       (assoc (select-keys (merge default-pool-opts (:pool-opts store))
                           [:max-size :connection-timeout-ms])
              :host (:host server "127.0.0.1")
              :port (:flight-sql-port server)
              :dbname db))
      (server-client server db))))

(defn- server-has-tenant?
  "True when the store runs against a server that has tenant `tid`'s database
   attached."
  [store tid]
  (boolean (when-let [server (:server store)]
             (contains? (attached-databases server) (tenant-db-name server tid)))))

(defn- node-db-name
  "The database name a tenant's node reports itself under: the tenant's
   database on a server, `xtdb` on an embedded node."
  [store tid]
  (if-let [server (:server store)]
    (tenant-db-name server tid)
    "xtdb"))

(defn ^:no-doc declaration-op
  "One `CREATE TABLE <table> (<cols>)` tx op. CREATE TABLE is additive and
   idempotent: on an existing table it adds the columns it lacks and keeps
   every column it has, so it is how an existing on-disk tenant picks up new
   types and columns. `cols` need not repeat `_id` for a table that exists."
  [table cols]
  [:sql (format "CREATE TABLE %s (%s)" table (str/join ", " (map quote-ident cols)))])

(defn- log-end-token
  "An await token for every message on the node's log when this is called.
   start-node returns before a node opened on an existing log has replayed
   it, and a read waits only for transactions submitted through the node
   itself, so until a read awaits this token the node can answer from part
   of its catalog and part of its rows. Only database `db`'s messages: a
   server lists every database it has attached."
  [node db]
  (basis/->tx-basis-str
   (-> (group-by :db-name (filter #(= db (:db-name %))
                                  (xt/q node ["SHOW LATEST_SUBMITTED_MSG_IDS"])))
       (update-vals #(mapv :msg-id %)))))

(defn- columns-on-node
  "table-name -> set of the column names the node's catalog lists, read once
   the node has replayed its whole log. Every later read on the node sees at
   least that much of the log."
  [node db]
  (reduce (fn [acc {:keys [table-name column-name]}]
            (update acc (quote-ident table-name) (fnil conj #{}) column-name))
          {}
          (xt/q node [(str "SELECT table_name, column_name FROM information_schema.columns"
                           " WHERE table_schema = 'public'")]
                {:await-token (log-end-token node db)})))

(defn ^:no-doc missing-declarations
  "The part of `declarations` (table-name -> columns) that `on-node` (as
   columns-on-node returns it) lacks: every column of a table the node does
   not list, only the absent columns of one it does. Empty when nothing is
   missing."
  [declarations on-node]
  (reduce-kv (fn [acc table cols]
               (let [missing (into [] (remove (get on-node table #{})) cols)]
                 (cond-> acc (seq missing) (assoc table missing))))
             {}
             declarations))

(defn ^:no-doc declare-tables!
  "Declares every table in `declarations` (table-name -> columns, as
   schema-declarations builds it) in ONE transaction. From XTDB 2.2.0-rc0 a
   SELECT, COUNT, temporal read, DELETE, ASSERT or :delete-docs that names a
   table or column nothing has written fails at planning (\"Table not found\" /
   \"Column not found\"); INSERT and :put-docs are exempt. A declared,
   never-written column plans as untyped, so reads answer empty.

   `tx-opts` goes to xt/execute-tx. A caller that stamps later transactions
   with an explicit historical :system-time must pass an earlier one here:
   a declaration committed at wall-clock now refuses every later stamp."
  ([node declarations] (declare-tables! node declarations nil))
  ([node declarations tx-opts]
   (when (seq declarations)
     (let [ops (mapv (fn [[table cols]] (declaration-op table cols)) declarations)]
       (if tx-opts
         (xt/execute-tx node ops tx-opts)
         (xt/execute-tx node ops))))))

(defn- start-tenant-node
  "Starts a tenant's node, waits for it to replay its log, and declares the
   store's schema tables and columns the node does not already have. When it
   has them all, as a reopened on-disk tenant does on every start after its
   first, no transaction is written: a declaration committed at wall-clock now
   would refuse every later transaction stamped with an earlier explicit
   :system-time, such as a historical import into the same directory.

   Returns [node tables]: `tables` is every table ensure-declared! need not
   declare, the schema tables and each table the node already lists with
   every transform/store-columns column, so a reopened tenant does not
   declare its schemaless types again either. A table missing one of them,
   written by something other than the store or before a column joined
   store-columns, is left out and declared on its first use.
   Closes the node and names the tenant when any of this fails."
  [store tid]
  (let [node (if-let [server (:server store)]
               (remote-tenant-node store server tid)
               (start-node (:node-config store)
                           {:flight-sql? (= :flight-sql (:transport store))}))]
    (try
      (let [declarations (:declared-columns store)
            on-node (columns-on-node node (node-db-name store tid))]
        (declare-tables! node (missing-declarations declarations on-node))
        [node (into (set (keys declarations))
                    (keep (fn [[table cols]] (when (every? cols xf/store-columns) table)))
                    on-node)])
      (catch Throwable e
        (try (close-node! node) (catch Throwable _ nil))
        (throw (ex-info (str "Could not declare tables for tenant " tid ": " (ex-message e))
                        {:tenant-id tid :tables (count (:declared-columns store))}
                        e))))))

(defn- get-or-create-entry
  "Returns {:node node :pool conn-source :declared atom} for the tenant,
   creating them if absent; :pool is where the store's operations borrow
   their connections (see make-conn-source). compare-and-set via swap!
   handles concurrent creation; the loser of a race closes the pool+node it
   created and uses the winner's entry. :declared holds the table names declared on the node so
   far, seeded with the tables start-tenant-node finds or declares (see
   ensure-declared!)."
  [store tenant-id]
  (let [nodes (:nodes store)
        tid (str tenant-id)]
    (or (get @nodes tid)
        (ftrace/trace!
         {:id :store/node.start
          :data {:tenant-id tid}}
         (let [[new-node declared] (start-tenant-node store tid)
               new-pool (make-conn-source store new-node tid)
               new-entry {:node new-node :pool new-pool
                          :db-name (node-db-name store tid)
                          :declared (atom declared)}
               existing (get (swap! nodes (fn [m]
                                            (if (contains? m tid)
                                              m
                                              (assoc m tid new-entry))))
                             tid)]
           (if (identical? existing new-entry)
             new-entry
             (do (close-entry! new-entry)
                 existing)))))))

(defn- ensure-declared!
  "Declares `resource-type`'s table on the entry's node, with the only columns
   the store itself reads on a type no schema enumerates (transform/
   store-columns), unless it is already declared. Schema types, and types the
   node already had at start, are seeded into `declared`, so this costs one
   set lookup per call; the first touch of any other type per tenant costs
   one small transaction. Two callers racing here both declare, which is
   harmless: CREATE TABLE is idempotent."
  [{:keys [node declared]} resource-type]
  (when resource-type
    (let [table (table-name resource-type)]
      (when-not (contains? @declared table)
        (declare-tables! node {table xf/store-columns})
        (swap! declared conj table))))
  nil)

(defn- entry-for
  "The tenant's entry, with `resource-type`'s table declared on its node."
  [store tenant-id resource-type]
  (doto (get-or-create-entry store tenant-id)
    (ensure-declared! resource-type)))

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
;; Bitemporal clause construction.
;;
;; XTDB places the temporal qualifier immediately after the table name, so its
;; bind params PRECEDE every WHERE param. Callers must concat in that order --
;; getting it backwards silently swaps a timestamp for a search value and the
;; query still runs. Every builder below returns [sql-fragment params] so the
;; ordering is carried by construction rather than by convention.
;;
;; Verified against xtdb-core 2.2.0-beta3: both `FOR ALL VALID_TIME` and
;; `FOR VALID_TIME ALL` parse (this ns uses the `FOR ALL ...` form already
;; established by history-sql), `AS OF ?` binds a JDBC param on both axes, and
;; Instant / LocalDate / ISO-string all coerce correctly as the bound value.
;; ---------------------------------------------------------------------------

(defn ^:no-doc basis->for-clause
  "SQL temporal qualifier for `basis` ({:system-time _ :valid-time _}, either
   key optional) as [sql params]. Returns [\"\" []] for a nil/empty basis, which
   is the atemporal default: valid now, as best known."
  [{:keys [system-time valid-time] :as basis}]
  (if (or (nil? basis) (and (nil? system-time) (nil? valid-time)))
    ["" []]
    (let [;; valid time first, mirroring the SQL:2011 ordering XTDB documents
          parts (cond-> []
                  valid-time  (conj [" FOR VALID_TIME AS OF ?" valid-time])
                  system-time (conj [" FOR SYSTEM_TIME AS OF ?" system-time]))]
      [(apply str (map first parts))
       (mapv second parts)])))

(defn ^:no-doc all-time-clause
  "Qualifier selecting every version on both axes, for timeline reads."
  []
  " FOR ALL VALID_TIME FOR ALL SYSTEM_TIME")

;; ---------------------------------------------------------------------------
;; Simple-read SQL impls. Extracted from the protocol method bodies so the
;; dispatcher in XTDBStore can route to these under :query-mode :sql and to
;; the XTQL siblings (fhir-store-xtdb2.query-xtql) under :query-mode :xtql.
;; ---------------------------------------------------------------------------

;; All read-surface SELECTs project `*, _system_from`: SELECT * alone does NOT
;; include xtdb's system columns, and _system_from is what inject-meta turns
;; into :meta :lastUpdated.

(defn- read-sql
  ([node resource-type id read-decoders]
   (read-sql node resource-type id read-decoders nil))
  ([node resource-type id read-decoders basis]
   (let [[for-sql for-params] (basis->for-clause basis)
         query (format "SELECT *, _system_from FROM %s%s WHERE _id = ?"
                       (table-name resource-type) for-sql)
         results (into [] (run-query node (into [query] (conj for-params id))))]
     (xtdb->fhir (first results) read-decoders))))

(defn ^:no-doc vread-sql
  "`vid` is the FHIR versionId, i.e. the `fhir_version` every write stamps, so
   the version is selected by value across system time rather than read AS OF
   a point. Same qualifier as history-sql, so every versionId instance history
   lists can be read back. The column holds strings; an integer vid would
   otherwise match nothing."
  [node resource-type id vid read-decoders]
  (let [query (format "SELECT *, _system_from FROM %s FOR ALL SYSTEM_TIME WHERE _id = ? AND %s"
                      (table-name resource-type) version-match-sql)
        results (into [] (run-query node (into [query id] (version-match-args vid))))]
    (xtdb->fhir (first results) read-decoders)))

(defn- deleted?-sql [node resource-type id]
  (let [table (table-name resource-type)
        current-query (format "SELECT _id FROM %s WHERE _id = ?" table)
        current-results (into [] (run-query node [current-query id]))]
    (if (seq current-results)
      false
      (let [history-query (format "SELECT _id FROM %s FOR ALL SYSTEM_TIME WHERE _id = ?" table)
            history-results (into [] (run-query node [history-query id]))]
        (boolean (seq history-results))))))

(defn- history-sql [node resource-type id read-decoders]
  (let [query (format "SELECT *, _system_from FROM %s FOR ALL SYSTEM_TIME WHERE _id = ?" (table-name resource-type))]
    (mapv #(xtdb->fhir % read-decoders) (run-query node [query id]))))

(def ^:private ^:no-doc result-params
  #{"_count" "_skip" "_offset" "_sort" "_include" "_revinclude"
    "_total" "_elements" "_contained" "_containedType"
    "_summary" "_format" "_pretty"
    ;; Temporal selectors. They name a snapshot rather than restrict which
    ;; resources match, and the basis reaches the store as its own argument, so
    ;; they must never reach build-condition -- which would compile "_asOf" into
    ;; a column comparison against a table that has no such column.
    "_asOf" "_validAt"})

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
   set of filter params, or [nil []] when there are none. `table-ref` is the
   searched table as [sql params], temporal qualifier included (see
   build-condition)."
  [filter-params search-registry table-ref]
  (if (empty? filter-params)
    [nil []]
    (let [conditions (map (fn [k]
                            (build-condition k (get filter-params k)
                                             (get search-registry (name k))
                                             table-ref))
                          (keys filter-params))]
      [(str/join " AND " (map first conditions))
       (into [] (mapcat second) conditions)])))

(defn- row-id [r] (or (:_id r) (:xt/id r) (get r "_id")))

(defn- fetch-by-ids
  "Fetches full rows for `ids` and returns them as FHIR resources in the same
   order as `ids` (phase 2 of the two-phase sorted search).

   `basis` MUST be the same basis phase 1 selected the ids under. Fetching the
   current rows for ids that matched a historical snapshot would return today's
   version of yesterday's result set -- a mismatch no test on either phase alone
   would catch."
  [node resource-type ids read-decoders basis]
  (if (empty? ids)
    []
    (let [[for-sql for-params] (basis->for-clause basis)
          rows (run-query node (into [(format "SELECT *, _system_from FROM %s%s WHERE _id = ANY(?)"
                                              (table-name resource-type) for-sql)]
                                     (conj for-params (vec ids))))
          by-id (into {} (map (fn [r] [(row-id r) r])) rows)]
      (into [] (keep #(some-> (get by-id %) (xtdb->fhir read-decoders))) ids))))

(defn- search-sql
  ([node resource-type args read-decoders]
   (search-sql node resource-type args read-decoders nil))
  ([node resource-type {:keys [filter-params sort-specs search-registry limit offset]} read-decoders basis]
  ;; LIMIT/OFFSET are bound as params (not inlined) so the SQL text is stable
  ;; across pages — repeated searches of a given shape hit XTDB's plan cache.
  (let [order-by (build-order-by-clause sort-specs search-registry)
        [for-sql for-params] (basis->for-clause basis)
        rt (str (table-name resource-type) for-sql)
        [where where-params] (where+params filter-params search-registry [rt for-params])
        ;; Temporal params bind before WHERE params (see basis->for-clause).
        params (into for-params where-params)
        where-sql (if where (str " WHERE " where) "")]
    (if (seq sort-specs)
      ;; Two-phase: an ORDER BY defeats LIMIT early-termination, so a single
      ;; SELECT * would materialize every matched row's full column set just to
      ;; sort and keep `limit`. Instead sort/limit a narrow _id projection
      ;; (only the WHERE + sort-key columns are read), then fetch the page's
      ;; full rows by id and restore the sorted order.
      (let [id-q (format "SELECT _id FROM %s%s%s LIMIT ? OFFSET ?" rt where-sql (or order-by ""))
            ids (mapv row-id (run-query node (into [id-q] (conj params limit offset))))]
        (fetch-by-ids node resource-type ids read-decoders basis))
      ;; No sort: a single SELECT * with LIMIT streams the first `limit` rows and
      ;; stops (early-termination), so the wide projection cost is already bounded.
      (let [q (format "SELECT *, _system_from FROM %s%s LIMIT ? OFFSET ?" rt where-sql (or order-by ""))]
        (mapv #(xtdb->fhir % read-decoders) (run-query node (into [q] (conj params limit offset)))))))))

(defn- count-sql
  ([node resource-type args] (count-sql node resource-type args nil))
  ([node resource-type {:keys [filter-params search-registry]} basis]
   (let [[for-sql for-params] (basis->for-clause basis)
         rt (str (table-name resource-type) for-sql)
         [where where-params] (where+params filter-params search-registry [rt for-params])
         query-str (format "SELECT COUNT(*) AS cnt FROM %s%s" rt (if where (str " WHERE " where) ""))
         result (first (run-query node (into [query-str] (into for-params where-params))))]
     (or (:cnt result) 0))))

;; ---------------------------------------------------------------------------
;; Write-side SQL impls.
;; ---------------------------------------------------------------------------

(defn- create-sql [node resource-type id resource storage-encoders lc-ctx id-minted? stamp]
  (let [rt-name (name resource-type)
        write (lifecycle-write lc-ctx node :create resource-type id resource)
        resource (:resource write)
        [sql args] (extract-and-build-sql resource-type id resource storage-encoders)
        ;; The ASSERT runs inside the transaction on XTDB's single writer, one
        ;; transaction at a time, and costs a point lookup that grows with the
        ;; table: it bounds concurrent creates. A minted id cannot exist, so,
        ;; as for a transaction's POST entries, it gets none.
        own-ops (cond-> []
                  (not id-minted?)
                  (conj [:sql (format "ASSERT NOT EXISTS (SELECT 1 FROM %s WHERE _id = ?)"
                                      (table-name resource-type))
                         [id]])
                  true (conj [:sql sql args]))
        tx-ops (cond-> (into own-ops (lifecycle-tx-ops write))
                 stamp (conj (txm/write-op stamp [[resource-type id]])))
        tx-key (try
                 (xt/execute-tx node tx-ops)
                 (catch Exception e
                   ;; Only the ASSERT means "already exists"; a minted create
                   ;; has none, so whatever failed is reported as itself.
                   (when (or id-minted? (lifecycle-op-failure? e (count own-ops)))
                     (throw e))
                   (throw (ex-info (str "Resource already exists: " rt-name "/" id)
                                   {:fhir/status 409 :fhir/code "conflict"
                                    :resource-type rt-name :id id}
                                   e))))]
    (fire-after-commit! lc-ctx [write] tx-key)
    (committed resource id (version-of-system-time (:system-time tx-key)) tx-key)))

(defn- update-sql [node resource-type id resource opts storage-encoders lc-ctx stamp]
  (let [rt-name (table-name resource-type)
        supplied (fp/normalize-if-match (:if-match opts))
        ;; Only an If-Match reads first. Without one a PUT is last-writer-wins:
        ;; its versionId comes from the commit, so there is nothing to read
        ;; or to assert, and two racing PUTs both land.
        current (when supplied (current-version node resource-type id))
        _ (when (and supplied (nil? current))
            (throw (ex-info "Version conflict: resource does not exist"
                            {:fhir/status 412 :fhir/code "conflict"
                             :expected (fp/if-match-label supplied) :actual nil})))
        ;; `If-Match: *` guards on existence alone, so past the check above it
        ;; resolves to whatever version is current — the ASSERT below still
        ;; makes the write atomic against a racing writer.
        if-match (if (= fp/if-match-any supplied) current supplied)
        _ (when (and if-match current (not= if-match current))
            (throw (ex-info "Version conflict"
                            {:fhir/status 412 :fhir/code "conflict"
                             :expected if-match :actual current})))
        write (lifecycle-write lc-ctx node :update resource-type id resource)
        resource (:resource write)
        [sql args] (extract-and-build-sql resource-type id resource storage-encoders)
        own-ops (cond-> []
                  if-match (conj (version-assert-op resource-type id if-match))
                  true (conj [:sql sql args]))
        tx-ops (cond-> (into own-ops (lifecycle-tx-ops write))
                 stamp (conj (txm/write-op stamp [[resource-type id]])))
        tx-key (try
                 (xt/execute-tx node tx-ops)
                 (catch Exception e
                   (when (lifecycle-op-failure? e (count own-ops))
                     (throw e))
                   (if if-match
                     (throw (ex-info (str "Version conflict: " (ex-message e))
                                     {:fhir/status 412 :fhir/code "conflict"
                                      :expected if-match}
                                     e))
                     (throw e))))]
    (fire-after-commit! lc-ctx [write] tx-key)
    (committed resource id (version-of-system-time (:system-time tx-key)) tx-key)))

(defn- delete-sql [node resource-type id opts stamp]
  (let [rt-name (table-name resource-type)
        supplied (fp/normalize-if-match (:if-match opts))
        current (when supplied (current-version node resource-type id))
        _ (when (and supplied (nil? current))
            (throw (ex-info "Version conflict: resource does not exist"
                            {:fhir/status 412 :fhir/code "conflict"
                             :expected (fp/if-match-label supplied) :actual nil})))
        if-match (if (= fp/if-match-any supplied) current supplied)
        _ (when (and if-match (not= if-match current))
            (throw (ex-info "Version conflict"
                            {:fhir/status 412 :fhir/code "conflict"
                             :expected if-match :actual current})))
        assert-op (when if-match
                    (version-assert-op resource-type id if-match))
        delete-op [:sql (format "DELETE FROM %s WHERE _id = ?" rt-name) [id]]
        [tx-ops stamp-idx] (delete-tx-ops assert-op
                                          (txm/delete-op stamp rt-name resource-type id)
                                          delete-op)
        tx-key (try
                 (xt/execute-tx node tx-ops)
                 (catch Exception e
                   (when (stamp-op-failure? e stamp-idx)
                     (throw e))
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

;; ---------------------------------------------------------------------------
;; Point-in-time snapshot reads (basis capture + as-of scan/count)
;;
;; These back the bulk export's lazy stream-at-download model: pin a basis at
;; kickoff (current-basis) and read every resource of a type AS OF that basis at
;; download time (scan-type-as-of). XTDB v2's `FOR SYSTEM_TIME AS OF <instant>`
;; gives the consistent snapshot; the basis system-time is the commit time of
;; the latest visible transaction, read from `xt/status`.
;; ---------------------------------------------------------------------------

(defn ^:no-doc xtdb-current-basis
  "Read the tenant node's latest-completed-transaction basis as
   {:tx-id <long-or-nil> :system-time <Instant>}. system-time is the commit
   time of that transaction, normalized to a java.time.Instant; on a node with
   no committed transactions it falls back to the wall-clock now so the returned
   token is always usable by `FOR SYSTEM_TIME AS OF`. A store-started node
   always has one: the table declarations are its first transaction. `db`
   names the tenant's database, since a server reports every database it has
   attached; without it the node's only database is read."
  ([node] (xtdb-current-basis node nil))
  ([node db]
  (let [txs    (:latest-completed-txs (xt/status node))
        tx-key (first (if db (get txs db) (first (vals txs))))
        st     (:system-time tx-key)
        inst   (cond
                 (instance? Instant st)        st
                 (instance? ZonedDateTime st)  (.toInstant ^ZonedDateTime st)
                 (some? st)                     (Instant/parse (str st))
                 :else                          (Instant/now))]
    {:tx-id (:tx-id tx-key)
     :system-time inst})))

(defn ^:no-doc basis->system-time
  "Coerce a basis map's :system-time to a java.time.Instant for use as the
   `FOR SYSTEM_TIME AS OF ?` parameter. A nil basis (or nil :system-time) reads
   the live snapshot (now)."
  ^java.time.Instant [basis]
  (let [st (:system-time basis)]
    (cond
      (instance? Instant st)       st
      (instance? ZonedDateTime st) (.toInstant ^ZonedDateTime st)
      (some? st)                   (Instant/parse (str st))
      :else                        (Instant/now))))

(defn ^:no-doc basis->valid-time
  "A basis map's :valid-time as a java.time.Instant, or nil for valid now. A
   LocalDate is its start in UTC."
  [basis]
  (let [vt (:valid-time basis)]
    (cond
      (nil? vt)                       nil
      (instance? Instant vt)          vt
      (instance? ZonedDateTime vt)    (.toInstant ^ZonedDateTime vt)
      (instance? java.time.LocalDate vt) (.toInstant (.atStartOfDay ^java.time.LocalDate vt java.time.ZoneOffset/UTC))
      :else                           (Instant/parse (str vt)))))

(def ^:private ^:no-doc scan-page-size 1000)

(defn ^:no-doc scan-type-as-of-reducible
  "Reducible (IReduceInit) that streams every live resource of `resource-type`
   as of `as-of` (an Instant), keyset-paginated by _id so only one page of
   `page-size` rows is materialized at a time. Backpressure: the reduce pulls
   the next page only after the reducing fn returns for the current page, so a
   fn that blocks on a slow OutputStream bounds peak memory to one page. A
   `reduced` accumulator halts the scan without fetching further pages. The
   pinned system-time makes keyset paging stable (no rows shift between pages).

   With `valid-time`, rows true at that instant rather than now."
  ([pool resource-type as-of read-decoders page-size]
   (scan-type-as-of-reducible pool resource-type as-of nil read-decoders page-size))
  ([pool resource-type ^java.time.Instant as-of valid-time read-decoders page-size]
  (reify clojure.lang.IReduceInit
    (reduce [_ f init]
      (with-conn [conn pool]
        (let [[clause clause-params] (basis->for-clause {:system-time as-of :valid-time valid-time})
              q (format "SELECT *, _system_from FROM %s%s WHERE _id > ? ORDER BY _id LIMIT ?"
                        (table-name resource-type) clause)]
          (loop [after "" acc init]
            (let [rows (run-query conn (into (into [q] clause-params) [after page-size]))
                  n    (count rows)]
              (if (zero? n)
                acc
                (let [result (reduce (fn [a row]
                                       (let [a' (f a (xtdb->fhir row read-decoders))]
                                         (if (reduced? a') (reduced a') a')))
                                     acc rows)]
                  (if (reduced? result)
                    @result
                    (if (< n page-size)
                      result
                      (recur (str (row-id (nth rows (dec n)))) result)))))))))))))

(defn ^:no-doc count-as-of-sql
  "COUNT(*) of `resource-type` as of `as-of` (an Instant)."
  [conn resource-type ^java.time.Instant as-of]
  (let [result (first (run-query conn [(format "SELECT COUNT(*) AS cnt FROM %s FOR SYSTEM_TIME AS OF ?"
                                               (table-name resource-type))
                                       as-of]))]
    (or (:cnt result) 0)))

;; ---------------------------------------------------------------------------
;; Type history window: `_since` and `_at`.
;;
;; Both reach the store as raw query strings and must be bound as java.time
;; values: XTDB refuses `TIMESTAMP ?` at parse time, and a string bound against
;; `_system_from` fails at execution. Bounds are Instants because a LocalDate
;; bound through the pool is read in the JVM's zone while the same bound handed
;; to the node is read as UTC; an Instant means one thing on both paths.
;; ---------------------------------------------------------------------------

(def ^:private fhir-instant-pattern
  "FHIR `instant`: seconds required, fraction optional, zone required."
  #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})")

(defn- invalid-history-param [pname value reason]
  (ex-info (format "Invalid %s value '%s': %s" pname value reason)
           {:fhir/status 400 :fhir/code "invalid"
            :fhir/location [(str "http." pname)]}))

(defn- parse-since
  "`_since` is a FHIR instant. A value without a zone names no instant at all,
   so it is refused rather than guessed: ignoring it would answer a wider
   question than the one asked."
  [v]
  (cond
    (instance? Instant v) v
    (and (string? v) (re-matches fhir-instant-pattern v))
    (try (.toInstant (OffsetDateTime/parse v))
         (catch java.time.format.DateTimeParseException _
           (throw (invalid-history-param "_since" v "not a valid date and time"))))
    :else
    (throw (invalid-history-param "_since" v "expected a FHIR instant with seconds and a zone, e.g. 2026-01-01T00:00:00Z"))))

(defn- parse-at
  "`_at` is a FHIR date(Time) naming a period, parsed as a `_lastUpdated`
   search value is. Returns {:as-of t} for an instant, or {:from t :to t} for
   the half-open period a reduced-precision value names, its days starting at
   UTC midnight (the zone the date parser assumes for a zone-less value)."
  [v]
  (if (instance? Instant v)
    {:as-of v}
    (let [{:keys [lower upper precision]}
          (try (dt/parse-search-date (str v))
               (catch Exception _
                 (throw (invalid-history-param "_at" v "expected a FHIR date or dateTime"))))
          ->instant (fn [d] (if (instance? LocalDate d)
                              (.toInstant (.atStartOfDay ^LocalDate d java.time.ZoneOffset/UTC))
                              (.toInstant ^OffsetDateTime d)))]
      (if (= :instant precision)
        {:as-of (->instant lower)}
        {:from (->instant lower) :to (->instant upper)}))))

(defn ^:no-doc history-type-window
  "The `_since` / `_at` restrictions of a type-history call, either key nil
   when its parameter is absent or blank. FHIR R4B http.html#history:
     _since  versions created at or after the instant  -> `_system_from >= since`
     _at     versions current at some point during the period
             -> a system-time qualifier, `AS OF` a point or `FROM .. TO` a period.
   Both may be given; each narrows the result."
  [params]
  (let [param (fn [k] (let [v (or (get params (keyword k)) (get params k))]
                        (when-not (and (string? v) (str/blank? v)) v)))]
    {:since (some-> (param "_since") parse-since)
     :at    (some-> (param "_at") parse-at)}))

(defn- history-type-sql [node resource-type params read-decoders]
  (let [raw-count (or (get params :_count) (get params "_count") "50")
        limit (if (string? raw-count) (parse-long raw-count) raw-count)
        {:keys [since at]} (history-type-window params)
        [for-clause for-params]
        (cond
          (nil? at)   [" FOR ALL SYSTEM_TIME" []]
          (:as-of at) [" FOR SYSTEM_TIME AS OF ?" [(:as-of at)]]
          :else       [" FOR SYSTEM_TIME FROM ? TO ?" [(:from at) (:to at)]])
        [where-clause where-params]
        (if since
          [" WHERE _system_from >= ?" [since]]
          ["" []])
        query (format "SELECT *, _system_from FROM %s%s%s ORDER BY _system_from DESC LIMIT ?"
                      (table-name resource-type) for-clause where-clause)
        ;; The temporal qualifier follows the table name, so its params come first.
        results (into [] (run-query node (-> [query] (into for-params) (into where-params) (conj limit))))]
    (mapv #(xtdb->fhir % read-decoders) results)))

(defn ^:no-doc row->timeline-entry
  "Splits a timeline row into its decoded resource and its four temporal bounds.
   xtdb->fhir strips the bound columns, so they are read off the raw row first."
  [row read-decoders]
  {:resource    (xtdb->fhir row read-decoders)
   :valid-from  (or (:xt/valid-from row) (:xt/valid_from row) (get row "_valid_from"))
   :valid-to    (or (:xt/valid-to row) (:xt/valid_to row) (get row "_valid_to"))
   :system-from (or (:xt/system-from row) (:xt/system_from row) (get row "_system_from"))
   :system-to   (or (:xt/system-to row) (:xt/system_to row) (get row "_system_to"))})

(defn- timeline-sql
  "Every version of one resource across both axes, oldest-first. The bound
   columns must be projected explicitly: `SELECT *` does not include them."
  [node resource-type id read-decoders]
  (let [query (format (str "SELECT *, _valid_from, _valid_to, _system_from, _system_to "
                           "FROM %s%s WHERE _id = ? ORDER BY _system_from, _valid_from")
                      (table-name resource-type) (all-time-clause))]
    (mapv #(row->timeline-entry % read-decoders) (run-query node [query id]))))

;; ---------------------------------------------------------------------------
;; Valid-time writes.
;;
;; A portion INSERT is an exact replacement over its portion: XTDB's
;; WITHOUT-OVERLAPS handling splits the surrounding rectangles and the new
;; version carries only the columns written, so an element absent from the new
;; resource does not survive from the version it replaces. Verified on
;; 2.2.0-beta3 -- writing {payer} over a [Mar,Apr) portion of a row that also
;; had {note} yields payer-only inside the portion and the original on both
;; sides. No preceding delete is needed, and a merge-style `UPDATE ... SET`
;; would be WRONG here: it would leave stale columns behind.
;;
;; Note also that a bare INSERT (no bounds) is valid from NOW, not for all
;; time. Backdated truth must always name its portion.
;;
;; A portion DELETE is a cut, not a lookup, and the same rectangle splitting
;; applies. Verified on 2.2.0-beta3: `FROM ? TO ?` erases exactly the
;; intersection of the portion with what is there, so a bound inside a
;; rectangle leaves the part beyond it standing, a bound equal to a later
;; rectangle's valid-from leaves that rectangle and its _system_from untouched
;; (the axis is half-open), and a portion overlapping nothing writes no row at
;; all rather than erroring. Binding nil to `TO ?` is end-of-time, byte-for-byte
;; the old `TO NULL` result, which is why one format string serves both the
;; bounded and the unbounded close and the plan-cache key stays stable. Binding
;; nil to `FROM ?` is beginning-of-time and erases the whole run.
;;
;; An empty or inverted portion is REFUSED and nothing is written. It is neither
;; a no-op nor a close to end-of-time. The STORE refuses it, not the engine: the
;; engine validates a portion only for the rows the DELETE selects, so a
;; malformed portion overlapping no live rectangle of that resource would
;; otherwise be accepted silently -- the guarantee holding for the rows that
;; happen to overlap and not for the call.
;; ---------------------------------------------------------------------------

(defn- put-valid-time-sql
  [node resource-type id resource vt storage-encoders]
  (let [[sql args] (extract-and-build-sql resource-type id resource storage-encoders
                                          :valid-time vt)
        tx-key (xt/execute-tx node [[:sql sql args]])]
    (committed resource id (version-of-system-time (:system-time tx-key)) tx-key)))

(defn- refuse-empty-portion!
  "Refuses an empty or inverted portion before the statement is issued.

   The engine validates a portion only for the rows the DELETE actually
   selects, so a malformed portion overlapping no live rectangle of this
   resource is accepted and writes nothing -- indistinguishable from a portion
   that legitimately touched nothing, and the opposite of what the protocol
   promises. The store owns the rule instead, so `valid-to` later than
   `valid-from` holds for EVERY call rather than for the ones that happen to
   overlap. Non-Instant bounds are left to the engine, which coerces them."
  [resource-type id valid-from valid-to]
  (when (and (instance? Instant valid-from)
             (instance? Instant valid-to)
             (not (.isBefore ^Instant valid-from ^Instant valid-to)))
    (throw (ex-info "Invalid valid times: valid-to must be later than valid-from"
                    {:fhir/status 400 :fhir/code "invalid"
                     :xtdb.error/code :xtdb.indexer/invalid-valid-times
                     :resource-type (name resource-type) :id id
                     :valid-from valid-from :valid-to valid-to}))))

(defn- close-valid-time-sql
  [node resource-type id valid-from valid-to]
  (refuse-empty-portion! resource-type id valid-from valid-to)
  (let [sql (format "DELETE FROM %s FOR PORTION OF VALID_TIME FROM ? TO ? WHERE _id = ?"
                    (table-name resource-type))
        tx-key (xt/execute-tx node [[:sql sql [valid-from valid-to id]]])]
    (with-basis {} tx-key)))

(defn- valid-time-statement
  "The SQL statement of one `transact-valid-time` op."
  [{:keys [op resource-type id resource vt valid-from valid-to]} storage-encoders]
  (case op
    :put   (let [[sql args] (extract-and-build-sql resource-type id resource storage-encoders
                                                   :valid-time vt)]
             [:sql sql args])
    :close (do (refuse-empty-portion! resource-type id valid-from valid-to)
               [:sql (format "DELETE FROM %s FOR PORTION OF VALID_TIME FROM ? TO ? WHERE _id = ?"
                             (table-name resource-type))
                [valid-from valid-to id]])
    (throw (ex-info (str "Unknown valid-time op " (pr-str op) "; expected :put or :close")
                    {:fhir/status 400 :fhir/code "invalid" :op op}))))

(defn- transact-valid-time-sql
  [node ops storage-encoders]
  (let [statements (mapv #(valid-time-statement % storage-encoders) ops)
        tx-key (xt/execute-tx node statements)]
    (with-basis {:count (count statements)} tx-key)))

(defn- reject-xtql-temporal!
  "The XTQL pathway has no temporal clause construction. Fail loudly instead of
   silently answering the current-state question."
  [query-mode]
  (when (= :xtql query-mode)
    (throw (ex-info "Temporal reads require :query-mode :sql"
                    {:fhir/status 400 :fhir/code "not-supported"
                     :query-mode query-mode}))))

(defn- entry-opts
  "A batch entry's opts: the Bundle's own, with the entry's If-Match in place
   of any the Bundle carried. nil when there is neither, so the plain arity is
   kept."
  [opts if-match]
  (not-empty (cond-> (dissoc opts :if-match)
               if-match (assoc :if-match if-match))))

(defn- put-op
  "The op that writes `resource` (already prepared) as `id`, for `query-mode`."
  [query-mode resource-type id resource storage-encoders]
  (if (= query-mode :xtql)
    [:put-docs (keyword resource-type)
     (doc->put-doc (encode-resource-doc resource-type id resource storage-encoders))]
    (let [[sql args] (extract-and-build-sql resource-type id resource storage-encoders)]
      [:sql sql args])))

(defn- delete-op [query-mode resource-type id]
  (if (= query-mode :xtql)
    [:delete-docs (keyword resource-type) id]
    [:sql (format "DELETE FROM %s WHERE _id = ?" (table-name resource-type)) [id]]))

;; ---------------------------------------------------------------------------
;; Batch Bundles.
;;
;; A batch's entries are independent: one that fails must not affect the
;; others. Running each through its own write verb gives that, but costs one
;; transaction, and one log round trip, per entry. So consecutive writes are
;; committed together in one transaction, a group, and an entry that fails
;; inside it is taken out and run on its own through its write verb once the
;; rest has committed. Every response a failing entry gets is therefore the
;; one its verb gives, against the state the group left.
;;
;; A group never holds two entries naming the same resource, and a GET of a
;; resource the open group writes closes it first, so a GET or write sees
;; what the entries before it wrote to that resource. A lifecycle's reads of
;; OTHER resources see the state before the group, as a transaction Bundle
;; entry's do: FHIR forbids interdependent batch entries.
;; ---------------------------------------------------------------------------

(def ^:private batch-group-max-entries 1000)

(def ^:private batch-group-max-aborts
  "Entries a group may lose to a failed op before the rest run one by one: a
   lost entry costs a resubmission of the whole group."
  3)

(defn- batch-entry-response
  "One batch entry run through the store's own verbs, failures included, as
   its batch-response entry."
  [store tenant-id opts entry]
  (try
    (let [req-map (:request entry)
          method (some-> (:method req-map) str/upper-case)
          url (:url req-map)
          parts (when url (str/split url #"/"))
          resource-type (first parts)
          id (second parts)
          resource (:resource entry)
          ;; update-sql/delete-sql normalize the ETag spellings
          ;; themselves; this only decides whether the entry
          ;; carries a precondition at all.
          entry-if-match (or (:ifMatch req-map) (get req-map "ifMatch"))]
      (case method
        "POST"
        (let [new-id (str (java.util.UUID/randomUUID))
              res (fp/create-resource store tenant-id (keyword resource-type) new-id resource
                                      (assoc (entry-opts opts nil) :id-minted? true))
              vid (get-in res [:meta :versionId])
              last-mod (str (get-in res [:meta :lastUpdated]))]
          {:resource res
           :response (cond-> {:status "201 Created"}
                       vid (assoc :etag (str "W/\"" vid "\"")
                                  :location (str "/" tenant-id "/fhir/" resource-type "/" (:id res) "/_history/" vid))
                       last-mod (assoc :lastModified last-mod))})

        "PUT"
        (let [res (if-let [o (entry-opts opts entry-if-match)]
                    (fp/update-resource store tenant-id (keyword resource-type) id resource o)
                    (fp/update-resource store tenant-id (keyword resource-type) id resource))
              vid (get-in res [:meta :versionId])
              last-mod (str (get-in res [:meta :lastUpdated]))]
          {:resource res
           :response (cond-> {:status "200 OK"}
                       vid (assoc :etag (str "W/\"" vid "\""))
                       last-mod (assoc :lastModified last-mod))})

        "DELETE"
        (do (if-let [o (entry-opts opts entry-if-match)]
              (fp/delete-resource store tenant-id (keyword resource-type) id o)
              (fp/delete-resource store tenant-id (keyword resource-type) id))
            {:response {:status "204 No Content"}})

        "GET"
        (let [res (fp/read-resource store tenant-id (keyword resource-type) id)]
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
      (let [{:fhir/keys [expression location]}
            (some #(when (:fhir/status (ex-data %)) (ex-data %))
                  (take-while some? (iterate ex-cause e)))]
        {:response {:status "400 Bad Request"
                    :outcome {:resourceType "OperationOutcome"
                              :issue [(cond-> {:severity "error"
                                               :code "exception"
                                               :diagnostics (str "Entry failed: " (ex-message e))}
                                        (seq expression) (assoc :expression expression)
                                        (seq location) (assoc :location location))]}}}))))

(defn- classify-batch-entry
  "How the batch runs `entry`: :group for a write a group can carry, :read for
   one that writes nothing (a GET, an unsupported method), :alone for a write
   only its own verb can judge (no type, no id, no body). :key names the
   resource it touches."
  [idx entry]
  (let [req (:request entry)
        method (when (string? (:method req)) (str/upper-case (:method req)))
        [resource-type id] (when (string? (:url req)) (str/split (:url req) #"/"))
        if-match (or (:ifMatch req) (get req "ifMatch"))
        base {:idx idx :entry entry :method method :resource-type resource-type :id id
              :key [resource-type id] :if-match if-match}]
    (case method
      ("POST" "PUT" "DELETE")
      (if (and (not (str/blank? resource-type))
               (or (= "POST" method) (not (str/blank? id)))
               (or (= "DELETE" method) (map? (:resource entry))))
        (cond-> (assoc base :run :group)
          ;; A POST's id is minted, so it collides with nothing.
          (= "POST" method) (assoc :key nil))
        (assoc base :run :alone))
      (assoc base :run :read))))

(defn- batch-segments
  "Splits classified entries, in order, into segments run one after another:
   {:group [...] :reads [...]} or {:alone entry}. A group's reads run before
   it commits, so each must not name a resource the group writes before it."
  [classified]
  (let [close (fn [{:keys [segs open]}]
                {:segs (cond-> segs (or (seq (:group open)) (seq (:reads open))) (conj open))
                 :open {:group [] :reads [] :keys #{}}})
        state (reduce
               (fn [{:keys [open] :as st} {:keys [run key] :as e}]
                 (case run
                   :group (let [st (if (or (contains? (:keys open) key)
                                           (>= (count (:group open)) batch-group-max-entries))
                                     (close st)
                                     st)]
                            (-> st
                                (update-in [:open :group] conj e)
                                (update-in [:open :keys] (fn [ks] (cond-> ks key (conj key))))))
                   :read (let [st (if (contains? (:keys open) key) (close st) st)]
                           (update-in st [:open :reads] conj e))
                   :alone (-> (close st) (update :segs conj {:alone e}))))
               {:segs [] :open {:group [] :reads [] :keys #{}}}
               classified)]
    (:segs (close state))))

(defn- plan-batch-write
  "The ops and lifecycle write for one :group entry, built against `db`, the
   handle the group commits through. Throws whatever the entry's own verb
   would refuse it for before sending anything; the caller then runs it alone.
   `versions` maps [type id] to the current version, read for guarded entries.
   `stamp` is the Bundle's stamp text: each plan carries its own stamp op, so
   a plan taken out of the group takes its stamp with it."
  [store tenant-id opts stamp db versions
   {:keys [method resource-type id if-match entry] :as e}]
  (let [{:keys [query-mode storage-encoders]} store
        id (if (= "POST" method) (str (java.util.UUID/randomUUID)) id)
        _ (refuse-unencodable-input! {:resource-type resource-type :id id})
        guard (when if-match
                (entry-if-match {:if-match (fp/normalize-if-match if-match)
                                 :resource-type resource-type :id id}
                                (get versions [resource-type id])))
        assert-ops (cond-> [] guard (conj (version-assert-op resource-type id guard)))]
    (if (= "DELETE" method)
      (assoc e :id id :ops (first (delete-tx-ops (first assert-ops)
                                                 (txm/delete-op stamp (table-name resource-type)
                                                                resource-type id)
                                                 (delete-op query-mode resource-type id))))
      (let [;; As the entry's own verb: a POST takes no If-Match.
            lc-opts (entry-opts opts (when (= "PUT" method) if-match))
            write (lifecycle-write (lifecycle-ctx store tenant-id lc-opts)
                                   db
                                   (if (= "POST" method) :create :update)
                                   resource-type id (:resource entry))
            own-ops (conj assert-ops (put-op query-mode resource-type id (:resource write)
                                             storage-encoders))]
        (assoc e :id id :write write
               :ops (cond-> (into own-ops (lifecycle-tx-ops write))
                      stamp (conj (txm/write-op stamp [[resource-type id]]))))))))

(defn- blamed-plan
  "The plan whose op `e`, a failed execute-tx of `plans`' ops, names."
  [plans e]
  (when-let [idx (:tx-op-idx (ex-data e))]
    (reduce (fn [start p]
              (let [end (+ start (count (:ops p)))]
                (if (< idx end) (reduced p) end)))
            0 plans)))

(defn- tx-refused-unsent?
  "True when execute-tx refused the transaction before the log took it (a
   statement that does not parse, a doc without an id): nothing committed, and
   no op is named."
  [e]
  (and (nil? (:tx-op-idx (ex-data e)))
       (= :cognitect.anomalies/incorrect (:cognitect.anomalies/category (ex-data e)))))

(defn- execute-batch-group!
  "Commits `plans` in one transaction, taking out each one a failed op names
   and resubmitting the rest. Returns {:tx-key :landed :alone} with :alone the
   plans to run alone; or, when a failure leaves the commit unknown,
   {:error e :failed plans} so that no entry is written twice."
  [db plans]
  (loop [pending plans aborts 0 alone []]
    (if (empty? pending)
      {:landed [] :alone alone}
      (let [r (try {:tx-key (xt/execute-tx db (into [] (mapcat :ops) pending))}
                   (catch Exception e {:error e}))]
        (if-let [tx-key (:tx-key r)]
          {:tx-key tx-key :landed pending :alone alone}
          (let [e (:error r)
                blamed (blamed-plan pending e)]
            (cond
              (and blamed (< aborts batch-group-max-aborts))
              (recur (filterv #(not= (:idx %) (:idx blamed)) pending)
                     (inc aborts) (conj alone blamed))

              (or blamed (tx-refused-unsent? e))
              {:landed [] :alone (into alone pending)}

              :else
              {:landed [] :alone alone :error e :failed pending})))))))

(defn- run-batch-group!
  "Plans and commits one group's entries through one handle; returns
   {:responses {idx response} :alone [entries to run alone]}."
  [store tenant-id opts stamp group]
  (let [{:keys [node pool] :as tenant} (get-or-create-entry store tenant-id)
        run (fn [db]
              (let [;; Guarded entries read the version they replace in one
                    ;; query per type, as a transaction Bundle's PUTs do.
                    versions (->> (filter :if-match group)
                                  (group-by :resource-type)
                                  (reduce (fn [acc [rt es]]
                                            (try
                                              (ensure-declared! tenant rt)
                                              (into acc (map (fn [[id v]] [[rt id] v]))
                                                    (current-versions-bulk db rt (map :id es)))
                                              ;; Planning then refuses each guard,
                                              ;; and the entries run alone.
                                              (catch Exception _ acc)))
                                          {}))
                    planned (mapv (fn [e]
                                    (try
                                      (ensure-declared! tenant (:resource-type e))
                                      (plan-batch-write store tenant-id opts stamp db versions e)
                                      (catch Exception _ (assoc e :unplanned? true))))
                                  group)
                    {:keys [tx-key landed alone error failed]}
                    (ftrace/trace!
                     {:id :store/transact-bundle.group
                      :data {:entry-count (count group)}}
                     (execute-batch-group! db (filterv (complement :unplanned?) planned)))
                    vid (when tx-key (version-of-system-time (:system-time tx-key)))]
                (doseq [{:keys [write]} landed :when write]
                  (fire-after-commit! (lifecycle-ctx store tenant-id opts) [write] tx-key))
                {:responses
                 (into {}
                       (concat
                        (for [{:keys [idx method resource-type id write]} landed]
                          [idx (if (= "DELETE" method)
                                 {:response {:status "204 No Content"}}
                                 (let [res (committed (:resource write) id vid tx-key)
                                       last-mod (str (get-in res [:meta :lastUpdated]))]
                                   {:resource res
                                    :response (cond-> {:status (if (= "POST" method) "201 Created" "200 OK")
                                                       :etag (str "W/\"" vid "\"")}
                                                (= "POST" method)
                                                (assoc :location (str "/" tenant-id "/fhir/" resource-type
                                                                      "/" id "/_history/" vid))
                                                true (assoc :lastModified last-mod))}))])
                        (for [{:keys [idx]} failed]
                          [idx {:response {:status "400 Bad Request"
                                           :outcome {:resourceType "OperationOutcome"
                                                     :issue [{:severity "error"
                                                              :code "exception"
                                                              :diagnostics (str "Entry failed: " (ex-message error))}]}}}])))
                 :alone (sort-by :idx (concat (filter :unplanned? planned) alone))}))]
    (if (= :xtql (:query-mode store))
      (run node)
      (with-conn [conn pool] (run conn)))))

(defn- batch-response-entries
  "The batch-response entries for `entries`, in input order. `stamp` is
   `opts`' stamp text; an entry run alone stamps through its own verb."
  [store tenant-id opts stamp entries]
  (let [alone! (fn [acc {:keys [idx entry]}]
                 (assoc acc idx (batch-entry-response store tenant-id opts entry)))
        responses
        (reduce
         (fn [acc seg]
           (if-let [e (:alone seg)]
             (alone! acc e)
             (let [acc (reduce alone! acc (:reads seg))]
               (if (seq (:group seg))
                 ;; The handle is given back before any entry runs alone, so
                 ;; a pool of one connection still serves them.
                 (let [{:keys [responses alone]} (run-batch-group! store tenant-id opts stamp (:group seg))]
                   (reduce alone! (merge acc responses) alone))
                 acc))))
         {}
         (batch-segments (map-indexed classify-batch-entry entries)))]
    (mapv responses (range (count entries)))))

(defn- stamp-table-present?
  "Whether the stamp table exists on the tenant's node. Nothing declares it,
   so before the first stamped write it does not, and a SELECT naming it
   fails at planning. Once seen it is remembered in the entry's `declared`
   set, under its bare name, which no table-name spelling can equal."
  [db {:keys [declared]}]
  (or (contains? @declared txm/table)
      (when (seq (run-query db [txm/exists-sql]))
        (swap! declared conj txm/table)
        true)))

(defn ^:no-doc tx-metadata-sql
  "The stamp version `vid` of `resource-type`/`id` was written under, or nil.

   A commit-time versionId names the transaction's system time, which is the
   stamp row's `_system_from`. Any other versionId is a stored counter, which
   only an importer writes and which therefore never carries a stamp."
  [db entry resource-type id vid]
  (when-let [t (system-time-of-version vid)]
    (when (stamp-table-present? db entry)
      (when-some [text (some (fn [row] (or (:stamp row) (get row "stamp")))
                             (run-query db [txm/read-sql (txm/stamp-key resource-type id) t]))]
        (txm/read-stamp text {:tenant-id (:tenant-id entry)
                              :resource-type (name resource-type)
                              :id id
                              :vid (str vid)})))))

(defrecord XTDBStore [nodes node-config storage-encoders read-decoders query-mode pool-opts]
  IFHIRStore

  (create-resource [this tenant-id resource-type id resource]
    (fp/create-resource this tenant-id resource-type id resource nil))

  (create-resource [this tenant-id resource-type id resource opts]
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/create
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [stamp (txm/stamp-text opts)
           {:keys [node pool]} (entry-for this tenant-id resource-type)]
       (case query-mode
         :xtql (create-xtql node resource-type id resource storage-encoders
                            (lifecycle-ctx this tenant-id (not-empty (dissoc opts :id-minted?)))
                            stamp)
         (with-conn [conn pool]
           ;; :id-minted? is a hint to the store, not one of the host's
           ;; opts, so a lifecycle sees the opts without it.
           (create-sql conn resource-type id resource storage-encoders
                       (lifecycle-ctx this tenant-id (not-empty (dissoc opts :id-minted?)))
                       (true? (:id-minted? opts))
                       stamp))))))

  (read-resource [this tenant-id resource-type id]
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/read
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (entry-for this tenant-id resource-type)]
       (case query-mode
         :xtql (read-xtql node resource-type id read-decoders)
         (with-conn [conn pool]
           (read-sql conn resource-type id read-decoders))))))

  (vread-resource [this tenant-id resource-type id vid]
    (refuse-unencodable-input! {:resource-type resource-type :id id :vid vid})
    (ftrace/trace!
     {:id :store/vread
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id :vid vid}}
     (let [{:keys [node pool]} (entry-for this tenant-id resource-type)]
       (case query-mode
         :xtql (vread-xtql node resource-type id vid read-decoders)
         (with-conn [conn pool]
           (vread-sql conn resource-type id vid read-decoders))))))

  (update-resource [this tenant-id resource-type id resource]
    (fp/update-resource this tenant-id resource-type id resource nil))

  (update-resource [this tenant-id resource-type id resource opts]
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/update
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [stamp (txm/stamp-text opts)
           {:keys [node pool]} (entry-for this tenant-id resource-type)]
       (case query-mode
         :xtql (update-xtql node resource-type id resource opts storage-encoders
                            (lifecycle-ctx this tenant-id opts) stamp)
         (with-conn [conn pool]
           (update-sql conn resource-type id resource opts storage-encoders
                       (lifecycle-ctx this tenant-id opts) stamp))))))

  (delete-resource [this tenant-id resource-type id]
    (fp/delete-resource this tenant-id resource-type id nil))

  (delete-resource [this tenant-id resource-type id opts]
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/delete
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [stamp (txm/stamp-text opts)
           {:keys [node pool]} (entry-for this tenant-id resource-type)]
       (case query-mode
         :xtql (delete-xtql node resource-type id opts stamp)
         (with-conn [conn pool]
           (delete-sql conn resource-type id opts stamp))))))

  (resource-deleted? [this tenant-id resource-type id]
    ;; A resource is "deleted" if it has history (existed in the past) but no current row
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/resource-deleted?
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (entry-for this tenant-id resource-type)]
       (case query-mode
         :xtql (deleted?-xtql node resource-type id)
         (with-conn [conn pool]
           (deleted?-sql conn resource-type id))))))

  (search [this tenant-id resource-type params search-registry]
    (refuse-unencodable-input! {:resource-type resource-type :params params})
    (ftrace/trace!
     {:id :store/search
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [node pool]} (entry-for this tenant-id resource-type)
           args (prepare-search-args params search-registry)
           ;; node-based thunk: only used as the XTQL pathway's SQL fallback.
           sql-thunk #(search-sql node resource-type args read-decoders)]
       (try
         (case query-mode
           :xtql (search-xtql node resource-type args read-decoders sql-thunk)
           (with-conn [conn pool]
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
    (refuse-unencodable-input! {:resource-type resource-type :params params})
    (ftrace/trace!
     {:id :store/count
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [node pool]} (entry-for this tenant-id resource-type)
           args (prepare-search-args params search-registry)
           ;; node-based thunk: only used as the XTQL pathway's SQL fallback.
           sql-thunk #(count-sql node resource-type args)]
      (try
        (case query-mode
          :xtql (count-resources-xtql node resource-type args sql-thunk)
          (with-conn [conn pool]
            (count-sql conn resource-type args)))
        (catch Exception e
          (t/event! ::count-query-failed
                    {:level :warn
                     ;; Parameter names only: the values are the search terms,
                     ;; e.g. the name and birth date being searched for.
                     :data {:resource-type (name resource-type)
                            :params (vec (keys (:filter-params args)))
                            :error (.getMessage e)}})
          0)))))

  (history [this tenant-id resource-type id]
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/history
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [node pool]} (entry-for this tenant-id resource-type)]
       (case query-mode
         :xtql (history-xtql node resource-type id read-decoders)
         (with-conn [conn pool]
           (history-sql conn resource-type id read-decoders))))))

  (history-type [this tenant-id resource-type params]
    (refuse-unencodable-input! {:resource-type resource-type :params params})
    (ftrace/trace!
     {:id :store/history-type
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [node pool]} (entry-for this tenant-id resource-type)]
       (case query-mode
         :xtql (history-type-xtql node resource-type params read-decoders)
         (with-conn [conn pool]
           (history-type-sql conn resource-type params read-decoders))))))

  (transact-transaction [this tenant-id entries]
    (fp/transact-transaction this tenant-id entries nil))

  (transact-transaction [this tenant-id entries opts]
    ;; Pre-compute entry metadata (method, resource-type, id) for use in both
    ;; building tx-ops and constructing the response afterward.
    ;; Entries are reordered per FHIR §3.1.0.11.2: DELETE -> POST -> PUT/PATCH -> GET/HEAD
    (ftrace/trace!
     {:id :store/transact-transaction
      :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
    (let [stamp (txm/stamp-text opts)
          {:keys [pool] :as entry} (get-or-create-entry this tenant-id)]
     (with-conn [conn pool]
      (let [node conn
          entry-metas
          (ftrace/trace!
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
                                       :resource resource
                                       :if-match (fp/normalize-if-match
                                                  (or (:ifMatch request)
                                                      (get request "ifMatch")))
                                       :if-none-match (or (:ifNoneMatch request)
                                                          (get request "ifNoneMatch"))}))
                                  entries)
                            (sort-by #(method-order (:method %)))
                            vec)
                 urn-mapping (build-urn-uuid-mapping metas)]
             (mapv (fn [em]
                     (if (:resource em)
                       (update em :resource resolve-urn-uuid-references urn-mapping)
                       em))
                   metas)))
          _ (run! #(refuse-unencodable-input! (select-keys % [:resource-type :id]))
                  entry-metas)
          ;; Every op below runs in ONE transaction, so a single undeclared
          ;; table (a DELETE or If-Match PUT on a type never written) would
          ;; abort the whole Bundle at planning.
          _ (run! #(ensure-declared! entry %)
                  (distinct (remove str/blank? (keep :resource-type entry-metas))))
          ;; Bulk-fetch current versions for every PUT in one query per
          ;; distinct resource type, replacing N sequential round-trips.
          put-versions-by-type
          (ftrace/trace!
           {:id :store/transact-transaction.current-versions
            :data {:entry-count (count entry-metas)}}
           (reduce (fn [acc [rt metas]]
                     (assoc acc rt (current-versions-bulk node rt (map :id metas))))
                   {}
                   ;; Versions come from the commit, so only a PUT with an
                   ;; ifMatch needs to know the version it replaces.
                   (group-by :resource-type
                             (filter #(and (= "PUT" (:method %)) (:if-match %))
                                     entry-metas))))
          ;; Single pass over entry-metas: emit tx-ops AND the per-entry
          ;; response metadata (new version, final resource with :id+:meta
          ;; populated). This lets the response builder assemble the Bundle
          ;; without a post-commit round-trip.
          ;; Per-entry write-op emitter. The SQL pathway emits
          ;; [:sql insert-stmt args] / [:sql delete-stmt args] — the XTQL
          ;; pathway emits [:put-docs table doc] / [:delete-docs table id],
          ;; bypassing the INSERT planner entirely. execute-tx accepts both
          ;; shapes (and a mix with [:sql ASSERT ...]) in one atomic call.
          lc-ctx (lifecycle-ctx this tenant-id opts)
          ;; `resource` is the lifecycle-PREPARED body for POST/PUT, never
          ;; the entry's submitted one.
          emit-write-op
          (fn [{:keys [method resource-type id] :as _em} resource _vid]
            (case method
              ("POST" "PUT") (put-op query-mode resource-type id resource storage-encoders)
              "DELETE" (delete-op query-mode resource-type id)))
          ;; POST/PUT entries run the lifecycle here, once each id is chosen
          ;; and before anything is encoded. Their tx-ops are collected per
          ;; entry and appended after ALL of the bundle's own write ops.
          {:keys [tx-ops lifecycle-writes entry-results]}
          (ftrace/trace!
           {:id :store/transact-transaction.sql-encode
            :data {:entry-count (count entry-metas) :query-mode query-mode}}
           (reduce (fn [acc {:keys [method resource-type id] :as em}]
                     (case method
                       ("POST" "PUT")
                       (let [current (get-in put-versions-by-type [resource-type id])
                             if-match (when (= "PUT" method)
                                        (entry-if-match em current))
                             ;; Known once the transaction commits.
                             vid :commit-time
                             write (lifecycle-write (assoc lc-ctx :opts (entry-opts opts if-match))
                                                    node
                                                    (if (= "POST" method) :create :update)
                                                    resource-type id (:resource em))
                             prepared (:resource write)
                             final (-> prepared
                                       (assoc :id id)
                                       (assoc-in [:meta :versionId] vid))]
                         (-> acc
                             (update :tx-ops into
                                     (cond-> []
                                       if-match (conj (version-assert-op resource-type id if-match))
                                       true (conj (emit-write-op em prepared vid))))
                             (update :lifecycle-writes conj write)
                             (update :entry-results conj (assoc em :resource final :vid vid))))
                       "DELETE"
                       (-> acc
                           (update :tx-ops into
                                   (first (delete-tx-ops nil
                                                         (txm/delete-op stamp (table-name resource-type)
                                                                        resource-type id)
                                                         (emit-write-op em nil nil))))
                           (update :entry-results conj em))
                       ;; GET / HEAD inside a transaction: we still have to
                       ;; read the resource to satisfy the response. Defer
                       ;; these to the read-back phase by leaving :resource
                       ;; nil on entry-results. A HEAD with ifNoneMatch "*"
                       ;; also asserts the resource absent at commit.
                       (-> acc
                           (cond-> (:if-none-match em)
                             (update :tx-ops conj (absent-assert-op em)))
                           (update :entry-results conj em))))
                   {:tx-ops [] :lifecycle-writes [] :entry-results []}
                   entry-metas))
          own-op-count (count tx-ops)
          guarded? (some #(or (:if-match %) (:if-none-match %)) entry-metas)
          tx-ops (into tx-ops (mapcat lifecycle-tx-ops) lifecycle-writes)
          ;; One stamp for the whole Bundle, recorded against each resource
          ;; it writes. DELETE entries carry theirs inline (delete-tx-ops);
          ;; an all-GET/HEAD Bundle writes nothing and so stamps nothing.
          stamp-op (txm/write-op stamp (for [{:keys [method resource-type id]} entry-metas
                                             :when (#{"POST" "PUT"} method)]
                                         [resource-type id]))
          tx-ops (cond-> tx-ops stamp-op (conj stamp-op))]
      (let [tx-key (ftrace/trace!
                    {:id :store/transact-transaction.execute-tx
                     :data {:op-count (count tx-ops)}}
                    (try
                      (xt/execute-tx node tx-ops)
                      (catch Exception e
                        ;; A guarded PUT whose version moved after it was
                        ;; read fails its own ASSERT: a precondition failure.
                        (if (and guarded? (not (lifecycle-op-failure? e own-op-count)))
                          (throw (ex-info (str "Version conflict: " (ex-message e))
                                          {:fhir/status 412 :fhir/code "conflict"}
                                          e))
                          (throw e)))))]
      ;; Fill in the versionIds, known only now: the commit's system time.
      (let [entry-results (let [cv (version-of-system-time (:system-time tx-key))]
                            (mapv (fn [r]
                                    (if (= :commit-time (:vid r))
                                      (-> r
                                          (assoc :vid cv)
                                          (assoc-in [:resource :meta :versionId] cv))
                                      r))
                                  entry-results))]
      ;; Once for the whole Bundle. A Bundle of only DELETE/GET entries
      ;; handed nothing to the lifecycle, so it has nothing to report.
      (when (seq lifecycle-writes)
        (fire-after-commit! lc-ctx lifecycle-writes tx-key))
      ;; Build the response. Writes return from in-memory metadata (no
      ;; round-trips). GET/HEAD entries, if any, still need a read — batched
      ;; per resource-type to avoid N sequential SELECTs.
      (ftrace/trace!
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
             ;; Every entry's version is stamped with the commit's system
             ;; time, the `_system_from` a later read reports for it.
             last-mod-str (last-updated-string (:system-time tx-key))]
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
                             resource (assoc :resource
                                             (assoc-in resource [:meta :lastUpdated] last-mod-str)))))
                       entry-results)}
          tx-key))))))))))

  (transact-bundle [this tenant-id entries]
    (fp/transact-bundle this tenant-id entries nil))

  (transact-bundle [this tenant-id entries opts]
    ;; Batch semantics: entries are independent, and a failing one is
    ;; answered with an OperationOutcome without affecting the others. See
    ;; batch-response-entries for how they share transactions. Returns a
    ;; batch-response Bundle in input order.
    (ftrace/trace!
     {:id :store/transact-bundle
      :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
     {:resourceType "Bundle"
      :type "batch-response"
      :entry (batch-response-entries this tenant-id opts (txm/stamp-text opts) entries)}))

  (create-tenant [this tenant-id]
    (fp/create-tenant this tenant-id nil))
  (create-tenant [this tenant-id opts]
    (ftrace/trace!
     {:id :store/create-tenant
      :data {:tenant-id (str tenant-id) :opts opts}}
     (let [tid       (str tenant-id)
           if-exists (get opts :if-exists :error)
           ;; A server keeps a tenant's database across store instances, so
           ;; one this store has not opened still exists.
           existing  (or (contains? @(:nodes this) tid)
                         (server-has-tenant? this tid))]
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
    (ftrace/trace!
     {:id :store/delete-tenant
      :data {:tenant-id (str tenant-id) :opts opts}}
     (let [tid       (str tenant-id)
           if-absent (get opts :if-absent :error)
           close?    (get opts :close-storage? false)
           existing  (get @(:nodes this) tid)
           on-server (server-has-tenant? this tid)]
       (cond
         (and (nil? existing) (not on-server) (= :error if-absent))
         (throw (ex-info "Tenant not found"
                         {:fhir/status 404 :fhir/code "not-found"
                          :tenant-id tid}))

         (or (some? existing) on-server)
         (do
           (when existing
             (close-entry! existing)
             (swap! (:nodes this) dissoc tid))
           (cond
             (and close? on-server)
             (detach-tenant-database! (:server this) (tenant-db-name (:server this) tid))

             (and close? existing (persistent-backend? (:node-config this)))
             (delete-tenant-storage! this tid))
           nil)

         :else nil))))

  (warmup-tenant [this tenant-id]
    (fp/warmup-tenant this tenant-id nil))
  (warmup-tenant [this tenant-id opts]
    (ftrace/trace!
     {:id :store/warmup-tenant
      :data {:tenant-id (str tenant-id)}}
     (let [tid (str tenant-id)
           rts (or (:resource-types opts) #{:Patient})]
       (get-or-create-node this tid)
       (doseq [rt rts]
         (try
           (fp/search this tid rt {"_count" "1"} {})
           (catch Throwable _ nil)))
       nil)))

  (current-basis [this tenant-id]
    (ftrace/trace!
     {:id :store/current-basis
      :data {:tenant-id (str tenant-id)}}
     (let [{:keys [node db-name]} (get-or-create-entry this tenant-id)]
       (xtdb-current-basis node db-name))))

  (scan-type-as-of [this tenant-id resource-type basis]
    ;; SQL-only (works under both :sql and :xtql query-modes: both write the
    ;; same lowercased tables, so `FOR SYSTEM_TIME AS OF` reads are valid
    ;; regardless of the write pathway). The reducible borrows a pooled
    ;; connection lazily inside its reduce, i.e. at download/consumption time.
    (refuse-unencodable-input! {:resource-type resource-type})
    (ftrace/trace!
     {:id :store/scan-type-as-of
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [pool]} (entry-for this tenant-id resource-type)]
       (scan-type-as-of-reducible pool resource-type (basis->system-time basis)
                                  (basis->valid-time basis) read-decoders scan-page-size))))

  (count-as-of [this tenant-id resource-type basis]
    (refuse-unencodable-input! {:resource-type resource-type})
    (ftrace/trace!
     {:id :store/count-as-of
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [pool]} (entry-for this tenant-id resource-type)]
       (try
         (with-conn [conn pool]
           (count-as-of-sql conn resource-type (basis->system-time basis)))
         (catch Exception e
           (t/event! ::count-as-of-failed
                     {:level :warn
                      :data {:resource-type (name resource-type)
                             :error (.getMessage e)}})
           0)))))

  fp/ITxMetadataStore

  ;; Unconditionally true: the stamp table needs no declaration (the INSERT
  ;; that writes the first stamp creates it), so every tenant can keep one
  ;; on every transport and in both query modes.
  (tx-metadata-supported? [_this] true)

  (tx-metadata-of [this tenant-id resource-type id vid]
    (refuse-unencodable-input! {:resource-type resource-type :id id :vid vid})
    (ftrace/trace!
     {:id :store/tx-metadata-of
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id :vid vid}}
     (let [{:keys [node pool] :as entry} (assoc (get-or-create-entry this tenant-id)
                                                :tenant-id (str tenant-id))]
       (case query-mode
         :xtql (tx-metadata-sql node entry resource-type id vid)
         (with-conn [conn pool]
           (tx-metadata-sql conn entry resource-type id vid))))))

  fp/ITemporalReadStore

  (temporal-axes [_this] #{:system-time :valid-time})

  (read-as-of [this tenant-id resource-type id basis]
    (reject-xtql-temporal! query-mode)
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/read-as-of
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [pool]} (entry-for this tenant-id resource-type)]
       (with-conn [conn pool]
         (read-sql conn resource-type id read-decoders basis)))))

  (search-as-of [this tenant-id resource-type params search-registry basis]
    (reject-xtql-temporal! query-mode)
    (refuse-unencodable-input! {:resource-type resource-type :params params})
    (ftrace/trace!
     {:id :store/search-as-of
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [pool]} (entry-for this tenant-id resource-type)
           args (prepare-search-args params search-registry)]
       (with-conn [conn pool]
         (search-sql conn resource-type args read-decoders basis)))))

  (count-as-of-basis [this tenant-id resource-type params search-registry basis]
    (reject-xtql-temporal! query-mode)
    (refuse-unencodable-input! {:resource-type resource-type :params params})
    (ftrace/trace!
     {:id :store/count-as-of-basis
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type)}}
     (let [{:keys [pool]} (entry-for this tenant-id resource-type)
           args (prepare-search-args params search-registry)]
       (with-conn [conn pool]
         (count-sql conn resource-type args basis)))))

  (resource-timeline [this tenant-id resource-type id _opts]
    (reject-xtql-temporal! query-mode)
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/resource-timeline
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [pool]} (entry-for this tenant-id resource-type)]
       (with-conn [conn pool]
         (timeline-sql conn resource-type id read-decoders)))))

  fp/IValidTimeStore

  (put-valid-time [this tenant-id resource-type id resource vt]
    (reject-xtql-temporal! query-mode)
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/put-valid-time
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [pool]} (entry-for this tenant-id resource-type)]
       (with-conn [conn pool]
         (put-valid-time-sql conn resource-type id resource vt storage-encoders)))))

  (close-valid-time [this tenant-id resource-type id valid-from]
    (fp/close-valid-time this tenant-id resource-type id valid-from nil))

  (close-valid-time [this tenant-id resource-type id valid-from valid-to]
    (reject-xtql-temporal! query-mode)
    (refuse-unencodable-input! {:resource-type resource-type :id id})
    (ftrace/trace!
     {:id :store/close-valid-time
      :data {:tenant-id (str tenant-id) :resource-type (name resource-type) :id id}}
     (let [{:keys [pool]} (entry-for this tenant-id resource-type)]
       (with-conn [conn pool]
         (close-valid-time-sql conn resource-type id valid-from valid-to)))))

  (transact-valid-time [this tenant-id ops]
    (reject-xtql-temporal! query-mode)
    (when (empty? ops)
      (throw (ex-info "transact-valid-time needs at least one op"
                      {:fhir/status 400 :fhir/code "invalid"})))
    (doseq [{:keys [resource-type id]} ops]
      (refuse-unencodable-input! {:resource-type resource-type :id id}))
    (ftrace/trace!
     {:id :store/transact-valid-time
      :data {:tenant-id (str tenant-id) :ops (count ops)}}
     (let [{:keys [pool]} (reduce (fn [_ rt] (entry-for this tenant-id rt))
                                  nil (distinct (map :resource-type ops)))]
       (with-conn [conn pool]
         (transact-valid-time-sql conn ops storage-encoders))))))

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

(def ^:dynamic *default-transport*
  "The :transport of a store whose config names none. The test suite binds it
   to run every store test over each transport (tests.edn profiles)."
  :pgwire)

(def ^:private transports #{:pgwire :adbc :flight-sql})

(defn create-xtdb-store
  "Creates an XTDB implementation of IFHIRStore with per-tenant node isolation.
   Config map keys:
   - :resource/schemas  — compiled malli schemas for all resource types (optional)
   - :node-config       — XTDB node configuration (default: {} for in-memory)
   - :query-mode        — pathway for reads and writes. :sql (default) uses
     dynamic SQL + INSERT/DELETE. :xtql uses XTQL reads and put-docs/delete-docs
     writes, with [:sql ASSERT ...] retained for optimistic concurrency.
   - :transport         — how reads and writes reach XTDB (see
     fhir-store-xtdb2.conns); default *default-transport*, :pgwire.
     :pgwire     a HikariCP pool of pgwire JDBC connections.
     :adbc       in-process ADBC connections (xt/open-adbc-conn) on the
                 tenant's embedded node; no socket. Refused with :server.
     :flight-sql a pool of FlightSQL sessions: on an embedded node's own
                 FlightSQL server (started on a free loopback port unless
                 :node-config configures one), or with :server on the
                 server's :flight-sql-port.
     The node-level work (table declarations, the start-up catalog read,
     current-basis, the :xtql pathway) runs on the embedded node itself,
     which XTDB drives in-process, on every transport.
   - :pool-opts         — per-tenant connection-pool overrides
     (:max-size, :min-idle, :connection-timeout-ms, :prepare-threshold); see
     default-pool-opts. :flight-sql uses :max-size and
     :connection-timeout-ms; :adbc pools nothing and uses none.
   - :resource/lifecycle — a write lifecycle (qualified symbol, value, or
     constructor fn of this config map); resolved once here and kept on the
     store under :resource/lifecycle. See fhir-store.lifecycle.
   - :pipelined-replica-appends? — refused. XTDB 2.2.0-rc2 always pipelines
     replica-log appends and has no switch for it (see ->xtdb-config), so a
     config that still sets it is reported rather than silently ignored.
   - :server            — run against a standalone XTDB server instead of
     starting nodes: {:host :port :user :password} for xt/client, plus
     :database-prefix (default \"fhir_\", see tenant-db-name),
     :attach-template (the ATTACH config for a new tenant database, with
     {db} and {uid} placeholders; default default-attach-template) and
     :attach-timeout-ms (default 60000), and for :transport :flight-sql
     :flight-sql-port, the server's FlightSQL port (ATTACH, DETACH and the
     attached-database list still go over pgwire). Each tenant is a database attached
     to the server; create-tenant's :replace and delete-tenant's
     :close-storage? detach it. Requires :sql query-mode, and refuses
     :node-config, which the server owns (an ATTACH config has no indexer
     section, so every tenant database takes the server's node-wide indexer
     settings).
     Assumes ONE server node: xt/client's pooled connections carry no
     await token, so a read sees the store's own writes only because the
     node that committed a write also serves the read. Against a cluster
     of several XTDB nodes a read could reach one that has not indexed it.
     Detaching does not erase: the tenant's Kafka topics and its storage on
     the server keep every record until removed there.

   Every tenant node declares the table and columns of each schema type that
   it lacks when it starts, and writes no transaction when it lacks none (see
   start-tenant-node); a type no schema names is declared with
   transform/store-columns on its first use per tenant, unless the node
   already had them all when it started."
  [{:keys [resource/schemas node-config query-mode pool-opts resource/lifecycle
           pipelined-replica-appends? server transport]
    :or {node-config {} schemas [] query-mode :sql pool-opts {}}
    :as config}]
  (assert (contains? #{:sql :xtql} query-mode)
          (str "query-mode must be :sql or :xtql, got " query-mode))
  (when (some? pipelined-replica-appends?)
    (throw (ex-info (str ":pipelined-replica-appends? was removed: XTDB 2.2.0-rc2 always pipelines "
                         "replica-log appends and has no setting for it")
                    {:pipelined-replica-appends? pipelined-replica-appends?})))
  (let [transport (or transport *default-transport*)]
    (when-not (contains? transports transport)
      (throw (ex-info (str "transport must be one of " transports ", got " transport)
                      {:transport transport})))
    (when (and server (= :adbc transport))
      (throw (ex-info "A :server store cannot use the :adbc transport: ADBC runs in-process, on an embedded node"
                      {})))
    (when (and server (= :flight-sql transport) (not (:flight-sql-port server)))
      (throw (ex-info "A :server store on the :flight-sql transport needs the server's :flight-sql-port"
                      {}))))
  (when server
    (when-not (= :sql query-mode)
      (throw (ex-info "A :server store supports only :sql query-mode" {:query-mode query-mode})))
    (when (seq node-config)
      (throw (ex-info "A :server store starts no node, so it takes no :node-config"
                      {:node-config-keys (keys node-config)}))))
  (let [storage-encoders (xf/build-storage-encoders schemas)
        read-decoders    (xf/build-read-decoders schemas)
        store (->XTDBStore (atom {}) node-config storage-encoders read-decoders query-mode
                           (or pool-opts {}))]
    (assoc store
           :declared-columns (schema-declarations schemas)
           :server server
           :transport (or transport *default-transport*)
           :operations {:valueset-expand xtdb-valueset-expand
                        :valueset-lookup xtdb-valueset-lookup}
           :resource/lifecycle (lc/resolve-lifecycle lifecycle config))))

(defmethod ig/init-key :fhir-store/xtdb2-node [_ config]
  ;; Kept for backward compatibility; returns the config map for use by the store.
  (println "Starting XTDB2 Node (config passthrough for per-tenant isolation)")
  config)

(defmethod ig/halt-key! :fhir-store/xtdb2-node [_ _]
  ;; No-op: nodes are managed by the store now
  nil)

(defmethod ig/init-key :fhir-store/xtdb2-store
  [_ {:keys [node resource/schemas query-mode pool-opts resource/lifecycle
             pipelined-replica-appends? server transport]}]
  (println "Starting XTDB2 FHIR Store (per-tenant node isolation)"
           (str "[query-mode=" (or query-mode :sql)
                " transport=" (or transport *default-transport*) "]"))
  (create-xtdb-store {:resource/schemas schemas
                      :node-config node
                      :query-mode (or query-mode :sql)
                      :pool-opts pool-opts
                      :resource/lifecycle lifecycle
                      :pipelined-replica-appends? pipelined-replica-appends?
                      :server server
                      :transport transport}))

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
