(ns fhir-store.protocol)

;; Reflective lookup for the OpenTelemetry Context class. We avoid a hard
;; compile-time dependency on the OTel SDK so this module stays free of
;; OpenTelemetry jars when tracing is disabled. When the SDK is on the
;; classpath (DROMON_OTEL=1 path), `Context.makeCurrent()` is invoked via
;; reflection, ensuring that XTDB v2's native spans nest under whatever
;; span is currently active in the request thread.
(def ^:private otel-context-class
  (delay
    (try
      (Class/forName "io.opentelemetry.context.Context")
      (catch Throwable _ nil))))

(defn otel-available?
  "True when the OpenTelemetry SDK is on the classpath."
  []
  (some? @otel-context-class))

(defn ^java.lang.AutoCloseable open-current-otel-scope!
  "Returns an AutoCloseable scope for the current OpenTelemetry context, or
   nil if the SDK is not loaded. Use inside a with-open or try/finally so the
   scope is always closed."
  []
  (when-let [klass @otel-context-class]
    (try
      (let [current (.invoke (.getMethod klass "current" (into-array Class []))
                             nil (into-array Object []))]
        (.invoke (.getMethod klass "makeCurrent" (into-array Class []))
                 current (into-array Object [])))
      (catch Throwable _ nil))))

(defmacro with-otel-context
  "Evaluates body with the current OpenTelemetry context made active for the
   thread, so downstream OTel-instrumented libraries (XTDB v2, etc.) see this
   span as their parent. No-op when the OpenTelemetry SDK is not on the
   classpath."
  [& body]
  `(let [^java.lang.AutoCloseable scope# (open-current-otel-scope!)]
     (try
       ~@body
       (finally
         (when scope# (.close scope#))))))

(defprotocol IFHIRStore
  "Store contract for FHIR resource persistence.

   Write-return basis convention: write methods (create-resource,
   update-resource, delete-resource, transact-transaction) SHOULD attach
   the committed transaction's store basis as Clojure metadata on their
   return value:

     {:fhir-store/basis {:tx-id <long> :system-time <instant>}}

   tx-id is monotonically increasing per store node, so a change feed can
   stamp and order frames by it without minting its own counter. Deletes,
   having no resource to return, return an empty map carrying the
   metadata. Backends that cannot supply a basis omit the metadata;
   callers must treat it as optional."
  (create-resource [this tenant-id resource-type id resource])
  (read-resource [this tenant-id resource-type id])
  (vread-resource [this tenant-id resource-type id vid])
  (update-resource
    [this tenant-id resource-type id resource]
    [this tenant-id resource-type id resource opts]
    "Update (or conditional upsert) a resource. `opts` may contain:
     - :if-match <expected-vid> — enforces an atomic optimistic-concurrency
       check. On version mismatch, implementations throw ex-info with
       `{:fhir/status 412 :fhir/code \"conflict\" :expected :actual}`.
       A missing resource combined with :if-match is also a 412.")
  (delete-resource
    [this tenant-id resource-type id]
    [this tenant-id resource-type id opts]
    "Delete a resource. `opts` may contain :if-match for optimistic
     concurrency; semantics match update-resource.")
  (search [this tenant-id resource-type params search-registry])
  (history [this tenant-id resource-type id])
  (history-type [this tenant-id resource-type params]
    "Returns all versions of all resources of a given type.")
  (count-resources [this tenant-id resource-type params search-registry]
    "Returns the total count of resources matching the search params.")
  (transact-transaction [this tenant-id entries]
    "Atomic FHIR `transaction` Bundle semantics (HL7 FHIR §3.1.0.11.2):
     all entries succeed or all fail as a single database transaction.
     Any failure propagates as an exception that rolls back the whole
     transaction; there is no per-entry error handling.")
  (transact-bundle [this tenant-id entries]
    "FHIR `batch` Bundle semantics: each entry is processed
     independently. Per-entry failures do NOT affect other entries.
     Returns a Bundle of type `batch-response` whose :entry vector
     reports the status of each input entry in the original order.")
  (resource-deleted? [this tenant-id resource-type id]
    "Returns true if the resource was previously created and then deleted,
     false if it exists or was never created.")
  (create-tenant
    [this tenant-id]
    [this tenant-id opts]
    "Eagerly create whatever backing state a tenant needs: the per-tenant
     XTDB node, the Datomic database and connection, the mock store's
     state entry, etc. Schema transacts should run here so the first
     resource call is pure I/O against an already-warm backend.

     `opts` may contain:
     - :if-exists — one of :error (default), :ignore, :replace.
       :error throws ex-info {:fhir/status 409 :fhir/code \"conflict\"}
       when the tenant already has any state. :ignore is a no-op if
       the tenant already exists. :replace is equivalent to calling
       delete-tenant immediately followed by create-tenant.

     Returns nil. Safe to call concurrently for the same tenant; the
     first caller wins and losers see an atomic no-op.")
  (delete-tenant
    [this tenant-id]
    [this tenant-id opts]
    "Remove all per-tenant state. After this call, reads and searches
     against the tenant behave as if the tenant was never created.
     Implementations must release any OS resources held open for the
     tenant (Datomic connection, XTDB node, file handles, JDBC pool).

     `opts` may contain:
     - :if-absent — one of :error (default), :ignore. :ignore is a
       no-op when the tenant has no state.
     - :close-storage? — boolean, default false. When true and the
       backend has persistent storage (datomic :dev/:peer, XTDB
       `xtdb2-disk`), also drop the underlying database/file storage,
       not just the in-process handle. In-memory backends ignore this.

     Returns nil.")
  (warmup-tenant
    [this tenant-id]
    [this tenant-id opts]
    "Prime caches, classloaders, JIT, and any lazy per-tenant init
     without actually mutating data. Intended to be idempotent and safe
     to call on a tenant that already has data — unlike create-tenant,
     which treats an existing tenant as a conflict by default.

     Implementations should issue a representative no-op query against
     the tenant that exercises the same code path a real request would
     take (e.g. a 0-result search against a known-small resource type).
     This forces the read-side classloader to load the decoder, the
     query engine to plan/cache the query shape, and the backend to
     resolve any lazy per-tenant resources.

     `opts` may contain:
     - :resource-types — a collection of resource type keywords to
       exercise. Default is #{:Patient} which is enough to prime the
       common hot paths.

     Returns nil. Never throws on a missing tenant; instead, creates
     the tenant as a side effect and then runs the warmup. This mirrors
     the current `get-or-create-node` / `ensure-tenant-conn!` laziness
     but makes it explicit and callable at application boot.")
  (current-basis [this tenant-id]
    "Capture the current point-in-time basis (snapshot token) for `tenant-id`
     as a map:

       {:tx-id <long-or-nil> :system-time <java.time.Instant>}

     :system-time is the commit time of the latest transaction visible to the
     tenant (never nil; backends with no committed transactions fall back to
     the wall-clock now). Pass the returned map straight to `scan-type-as-of`
     and `count-as-of` to read a consistent snapshot as of this moment. This is
     the read-side dual of the write-return basis convention: it pins a moment
     WITHOUT producing any resource bytes, so an async export can snapshot at
     kickoff and stream lazily at download time.")
  (scan-type-as-of [this tenant-id resource-type basis]
    "Stream every resource of `resource-type` as of `basis` (a token from
     `current-basis`). Returns a REDUCIBLE (clojure.lang.IReduceInit) — NOT a
     realized collection — that pulls the resource set from the store one
     internal page at a time, holding at most one page in memory. The reduce is
     driven by the consumer: a reducing function that blocks (e.g. writing to a
     backpressured OutputStream) pauses the pull, so peak memory stays bounded
     regardless of type size. Early termination (a `reduced` accumulator) stops
     the scan without fetching further pages.

     No filtering is applied here beyond the snapshot: _type / _since /
     _typeFilter / compartment confinement and id-dedup are the export layer's
     responsibility, applied while consuming the stream. Deleted resources are
     excluded (only live rows as of the basis are streamed).")
  (count-as-of [this tenant-id resource-type basis]
    "Total number of live resources of `resource-type` as of `basis` (a token
     from `current-basis`). Unfiltered snapshot count, intended for manifest
     `output[].count` entries. Cheaper than realizing `scan-type-as-of`; a
     backend that cannot count cheaply may return 0."))

(defn create-store
  "Creates an IFHIRStore implementation. `impl-fn` is a function that takes
   a config map and returns an IFHIRStore instance.

   The config map contains:
   - :resource/schemas  — vector of compiled malli schemas (one per supported
                          resource type, each carrying :resourceType, :fhir/cap-schema,
                          :fhir/interactions, :fhir/search-registry in properties)
   - Implementation-specific keys (e.g., XTDB node config)"
  [impl-fn config]
  (impl-fn config))

;; ---------------------------------------------------------------------------
;; Bitemporal extension protocols.
;;
;; Deliberately separate from IFHIRStore, and deliberately split read from
;; write, so a store implements exactly what its engine can honour and
;; `satisfies?` stays a meaningful capability check:
;;
;;   fhir-store-xtdb2   ITemporalReadStore + IValidTimeStore  (both axes)
;;   fhir-store-datomic ITemporalReadStore only               (system time)
;;   fhir-store-mock    neither
;;
;; A store that implemented a verb it cannot honour -- even to throw -- would
;; make the capability undiscoverable ahead of the call, which is what the
;; server layer needs in order to answer 400 instead of a wrong number.
;; ---------------------------------------------------------------------------

(defprotocol ITemporalReadStore
  "Point-in-time reads across one or both time axes.

   A `basis` is {:system-time <Instant|nil> :valid-time <Instant|LocalDate|nil>}.
   nil on an axis means that axis's default: as-best-known for system time,
   valid-now for valid time. A nil basis is an ordinary current read.

   Implementations MUST reject a basis naming an axis absent from
   `temporal-axes` rather than ignoring it. Silently dropping an axis returns a
   plausible but wrong answer -- which is precisely the failure this surface
   exists to prevent."
  (temporal-axes [this]
    "The set of axes this store can honour. #{:system-time} for engines with a
     single append-only timeline (Datomic); #{:system-time :valid-time} for a
     bitemporal engine (XTDB v2).")
  (read-as-of [this tenant-id resource-type id basis]
    "The single resource as it stood at `basis`, or nil when no version of it
     was live at that point.")
  (search-as-of [this tenant-id resource-type params search-registry basis]
    "As `search`, evaluated against the snapshot `basis` names. The same
     search-registry machinery applies; only the snapshot differs.")
  (count-as-of-basis [this tenant-id resource-type params search-registry basis]
    "As `count-resources`, against the snapshot `basis` names.")
  (resource-timeline [this tenant-id resource-type id opts]
    "Every version of one resource, ordered oldest-first by system time.

     Each row is {:resource <fhir-resource>
                  :system-from <Instant> :system-to <Instant-or-nil>}
     plus :valid-from / :valid-to ONLY when :valid-time is in `temporal-axes`.

     The absence of the valid-time keys means 'this store has no such axis' --
     it does NOT mean [beginning-of-time, end-of-time). Callers rendering a
     timeline must branch on `temporal-axes`, never on key presence alone, and
     must not emit a null valid period for a single-axis store. A nil
     :system-to means 'still current', not 'deleted'."))

(defprotocol IValidTimeStore
  "Writes that place a fact on the valid-time axis: retroactive corrections and
   future-dated changes. Requires a bitemporal engine.

   Two engine behaviours make these verbs necessary rather than conveniences:

   1. Valid-time DML without a portion clause applies FROM NOW ON. A correction
      issued as a plain update is therefore a silent PROSPECTIVE change, not the
      retroactive one the user meant. Every retroactive write must name its
      portion, which is what these verbs enforce.
   2. System-time backfill cannot predate already-indexed transactions. Once a
      tenant is live there is no inserting an older system time; anything
      learned later about the past is a valid-time write recorded now. Plan
      historical import oldest-first, before live traffic."
  (put-valid-time [this tenant-id resource-type id resource vt]
    "Record `resource` as the truth over the valid-time portion
     vt = {:valid-from <t> :valid-to <t-or-nil>}, nil :valid-to meaning
     end-of-time.

     Exact replacement over the portion: elements absent from `resource` are
     absent from the result, never inherited from the version being replaced.
     Still a new system-time version, so versionId advances as for any update.")
  (close-valid-time [this tenant-id resource-type id valid-from]
    "Retroactive termination: the resource ceases to be true from `valid-from`
     onward, learned now. History is preserved -- reads at an earlier system
     time still see it live, and reads valid-before `valid-from` still find it.
     This is the retro-eligibility primitive."))
