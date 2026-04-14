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
     but makes it explicit and callable at application boot."))

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
