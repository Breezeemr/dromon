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
  (update-resource [this tenant-id resource-type id resource])
  (delete-resource [this tenant-id resource-type id])
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
     false if it exists or was never created."))

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
