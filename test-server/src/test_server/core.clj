(ns test-server.core
  "Config-driven entry point for the dromon test server.

   Which FHIR store backend and which malli schema package(s) get loaded
   is controlled by deps.edn aliases (`:store/xtdb2`, `:store/mock`,
   `:malli/uscore8`, etc.) plus the `:test-server.store` and
   `:test-server.schemas` env / system-property overrides read in `-main`.

   Nothing in this namespace statically requires a malli schema package
   or a store implementation — both are pulled in via `requiring-resolve`
   so the same `test-server.core` namespace works against any combination
   of backends present on the classpath."
  (:require [server.core :as fhir-server]
            [server.bulk-job-store :as bulk-job-store]
            [server.logging :as logging]
            [clojure.edn :as edn]
            [integrant.core :as ig]
            [fhir-terminology.tx-proxy]
            [fhir-terminology.cache]
            [test-server.search-params :as sp]
            [fhir-store.protocol :as db]))

;; ---------------------------------------------------------------------------
;; Config selection
;; ---------------------------------------------------------------------------

(defn- env-node-config
  "Optional XTDB node-config overlay parsed from the XTDB_NODE_CONFIG env var
   (an EDN map, e.g. `{:indexer {:flush-duration \"PT1H\"} :compactor {:threads 1}}`).
   Merged into the active store preset's node config so config variants can be
   swept without code changes. Returns {} when unset, reproducing XTDB defaults.
   Only keys XTDB recognizes take effect; unknown keys are logged and ignored."
  []
  (if-let [s (System/getenv "XTDB_NODE_CONFIG")]
    (edn/read-string s)
    {}))

(def ^:private store-presets
  "Map of store-name -> integrant config fragment that constructs an
   IFHIRStore under the key `:test-server/store`. Each fragment is
   merged into the system config; the namespaces it references are
   loaded lazily via `require` in `load-store-ns!` before init."
  {:xtdb2 {:requires '[fhir-store-xtdb2.core]
           :extra    {:fhir-store/xtdb2-node  (env-node-config)
                      :fhir-store/xtdb2-store {:node             (ig/ref :fhir-store/xtdb2-node)
                                               :resource/schemas (ig/ref :fhir/schemas)}}
           :store-ref (ig/ref :fhir-store/xtdb2-store)}
   ;; Persistent local-disk XTDB v2 node. Writes log + storage under
   ;; `test-server/xtdb-data/` (relative to the test-server working dir).
   ;; Override the base dir with env var XTDB_DATA_DIR. Each tenant creates
   ;; its own node pointed at the same config; for multi-tenant persistent
   ;; runs you must template the path per tenant. Inferno uses a single
   ;; `default` tenant so one path is fine.
   :xtdb2-disk {:requires '[fhir-store-xtdb2.core]
                :extra    {:fhir-store/xtdb2-node
                           (let [base (or (System/getenv "XTDB_DATA_DIR") "xtdb-data")]
                             (merge {:log     [:local {:path (str base "/log")}]
                                     :storage [:local {:path (str base "/storage")}]}
                                    (env-node-config)))
                           :fhir-store/xtdb2-store {:node             (ig/ref :fhir-store/xtdb2-node)
                                                    :resource/schemas (ig/ref :fhir/schemas)}}
                :store-ref (ig/ref :fhir-store/xtdb2-store)}
   :mock  {:requires '[fhir-store.mock.sys]
           :extra    {:fhir-store/mock {}}
           :store-ref (ig/ref :fhir-store/mock)}
   ;; In-memory Datomic (`datomic:mem://`), no transactor required. Selected via
   ;; TEST_SERVER_STORE=datomic with the `:store/datomic` deps alias on the
   ;; classpath. Used by the compartment-e2e runner to verify enforcement
   ;; against the Datomic backend.
   :datomic {:requires '[fhir-store-datomic.core]
             :extra    {:fhir-store/datomic-store {:resource/schemas (ig/ref :fhir/schemas)
                                                   :storage          :mem
                                                   :close-on-halt?   true}}
             :store-ref (ig/ref :fhir-store/datomic-store)}
   ;; Persistent Datomic dev storage pointed at a pre-existing database
   ;; produced by fhir-datomic-decant. The `default` tenant is mapped to the
   ;; decant target database instead of the usual `{prefix}-{tenant}` name.
   ;; Requires a running transactor (default datomic:dev://localhost:4334);
   ;; override with DATOMIC_BASE_URI / DATOMIC_DEFAULT_DB. Selected via
   ;; TEST_SERVER_STORE=datomic-decant with the `:store/datomic` deps alias.
   :datomic-decant
   {:requires '[fhir-store-datomic.core]
    :extra    {:fhir-store/datomic-store
               {:resource/schemas (ig/ref :fhir/schemas)
                :storage          :dev
                :base-uri         (or (System/getenv "DATOMIC_BASE_URI")
                                      "datomic:dev://localhost:4334")
                :tenant-db-names  {"default" (or (System/getenv "DATOMIC_DEFAULT_DB")
                                                 "phi-fhir-test-decanted")}
                :close-on-halt?   false}}
    :store-ref (ig/ref :fhir-store/datomic-store)}})

(def ^:private schema-presets
  "Map of schema-package -> namespace whose `specs` Var lists the schema
   specs to feed into `:fhir/schemas`."
  {:uscore8 'test-server.schemas.uscore8})

(def ^:private extra-operations
  "Deployment-specific FHIR operations merged over the fhir-server built-ins
   (see `server.core/resource-operations`).

   $telehealth-signal carries WebRTC signaling between the patient portal
   and the provider over long polling. It writes no clinical data, so it is
   gated on the `read` Keto relation: anyone who may read the appointment
   may signal on it."
  {"Appointment"
   {"$telehealth-signal" {:get  'server.telehealth/poll-signal
                          :post 'server.telehealth/post-signal
                          :keto/relation "read"}}})

(defn- load-ns! [ns-sym]
  (require ns-sym))

(defn- resolve-schema-specs [pkg]
  (let [ns-sym (or (get schema-presets pkg)
                   (throw (ex-info "Unknown schema package" {:package pkg
                                                             :known   (keys schema-presets)})))]
    (load-ns! ns-sym)
    @(or (ns-resolve ns-sym 'specs)
         (throw (ex-info "Schema package ns has no `specs` Var" {:ns ns-sym})))))

(defn build-config
  "Build the integrant system config from selectors.

   `opts` keys:
   - :store    -- one of (keys store-presets); default :xtdb2
   - :schemas  -- one of (keys schema-presets); default :uscore8
   - :port     -- HTTP port (default 8080)
   - :ssl-port -- HTTPS port (default 8443)"
  [{:keys [store schemas port ssl-port]
    :or   {store    :xtdb2
           schemas  :uscore8
           port     8080
           ssl-port 8443}}]
  (let [{:keys [requires extra store-ref]} (or (get store-presets store)
                                                (throw (ex-info "Unknown store" {:store store})))
        specs (resolve-schema-specs schemas)]
    (run! load-ns! requires)
    (merge {:fhir/schemas {:specs specs
                           :operations extra-operations}
            :test-server/seeder    {:store store-ref}
            ;; In-memory Bulk Data Access ($export) job registry. Exports stream
            ;; from the store at download time (no temp files), so the only
            ;; bounded resource is concurrent download streams
            ;; (max-concurrent-streams=4) plus a metadata ttl (ttl-ms=1h), from
            ;; server.bulk-job-store/default-config. Override any key here per
            ;; deployment, or via the BULK_* env vars (BULK_MAX_CONCURRENT_STREAMS,
            ;; BULK_JOB_TTL_MS; the :fhir/bulk-job-store init-key layers env
            ;; overrides on top).
            :fhir/bulk-job-store bulk-job-store/default-config
            :fhir-terminology/tx-proxy {:base-url nil}
            :fhir-terminology/cached   {:delegate (ig/ref :fhir-terminology/tx-proxy)}
            :server/jetty {:port          port
                           :ssl-port      ssl-port
                           :keystore      "../fhir-server/dev-keystore.p12"
                           :keystore-type "PKCS12"
                           :key-password  (or (System/getenv "KEYSTORE_PASSWORD") "changeit")
                           :store         store-ref
                           :schemas       (ig/ref :fhir/schemas)
                           :terminology   (ig/ref :fhir-terminology/cached)
                           :bulk-job-store (ig/ref :fhir/bulk-job-store)
                           ;; Force integrant to init the seeder (which creates
                           ;; and warms the default tenant) before Jetty starts
                           ;; accepting traffic, so the first real request is
                           ;; never billed for per-tenant cold-start cost.
                           :seeded        (ig/ref :test-server/seeder)}}
           extra)))

(def ^:private seed-patients
  "Baseline Patients seeded into the default tenant so a system-level
   $export produces a Patient NDJSON file with at least two DISTINCT ids
   (the Bulk Data validator requires >= 2). The inferno runner additionally
   PUTs Patient/123; seeding it here too makes that PUT idempotent and keeps
   the two-id guarantee even when the runner is not involved."
  [{:resourceType "Patient"
    :id "123"
    :text {:status "generated"
           :div "<div xmlns=\"http://www.w3.org/1999/xhtml\">John Smith</div>"}
    :active true
    :identifier [{:system "urn:oid:1.2.36.146.595.217.0.1" :value "pat-123"}]
    :name [{:family "Smith" :given ["John"]}]
    :gender "male"
    :birthDate "1980-01-01"
    :address [{:line ["123 Main St"] :city "Anytown" :state "NY"
               :postalCode "12345" :country "US"}]}
   {:resourceType "Patient"
    :id "bulk-export-2"
    :text {:status "generated"
           :div "<div xmlns=\"http://www.w3.org/1999/xhtml\">Jane Doe</div>"}
    :active true
    :identifier [{:system "urn:oid:1.2.36.146.595.217.0.1" :value "pat-bulk-2"}]
    :name [{:family "Doe" :given ["Jane"]}]
    :gender "female"
    :birthDate "1975-05-05"
    :address [{:line ["456 Oak Ave"] :city "Anytown" :state "NY"
               :postalCode "12345" :country "US"}]}])

(def ^:private seed-groups
  "Baseline Groups seeded into the default tenant. Group \"1\" exists so
   Group-level Bulk Data Access (Group/1/$export) resolves to 202 instead of
   404; the Inferno bulk_data suite drives group export against group_id:1. Its
   member.entity references the two baseline Patients so the group export
   confines its output to that cohort. This is a minimal, base-R4-valid Group
   (required elements: type, actual)."
  [{:resourceType "Group"
    :id "1"
    :type "person"
    :actual true
    :member [{:entity {:reference "Patient/123"}}
             {:entity {:reference "Patient/bulk-export-2"}}]}])

(defmethod ig/init-key :test-server/seeder [_ {:keys [store]}]
  (println "Provisioning default tenant...")
  (db/create-tenant store "default" {:if-exists :ignore})
  (println "Warming up default tenant (cold-path init)...")
  (db/warmup-tenant store "default")
  (println "Seeding" (count sp/search-parameters) "SearchParameters in one transaction...")
  ;; Batched into a single transact-transaction so the per-tx fixed cost is
  ;; paid once instead of N times. See docs/proposals/xtdb2-create-per-tx-floor.md.
  (let [entries (mapv (fn [p]
                        {:request  {:method "PUT" :url (str "SearchParameter/" (:id p))}
                         :resource p})
                      sp/search-parameters)]
    (db/transact-transaction store "default" entries))
  (println "Seeding" (count seed-patients) "baseline Patients...")
  (db/transact-transaction
   store "default"
   (mapv (fn [p]
           {:request  {:method "PUT" :url (str "Patient/" (:id p))}
            :resource p})
         seed-patients))
  (println "Seeding" (count seed-groups) "baseline Groups...")
  (db/transact-transaction
   store "default"
   (mapv (fn [g]
           {:request  {:method "PUT" :url (str "Group/" (:id g))}
            :resource g})
         seed-groups))
  true)

(defonce system (atom nil))

(defn start-system!
  ([] (start-system! {}))
  ([opts]
   (reset! system (ig/init (build-config opts)))))

(defn stop-system! []
  (when @system
    (ig/halt! @system)
    (reset! system nil)))

(defn- env-keyword [k]
  (some-> (System/getenv k) keyword))

(defn- assert-supported-jvm! []
  ;; XTDB v2 (and this server's virtual-thread usage) require Java 21+ (bytecode
  ;; major 65). There is no upper bound: XTDB 2.2.x is plain Java-21 bytecode (no
  ;; preview features) and runs on 21 through 25+.
  ;;
  ;; On Java 24+, JEP 498 restricts sun.misc.Unsafe memory access, which Arrow's
  ;; netty allocator (used by the XTDB node) relies on. Without
  ;; `--sun-misc-unsafe-memory-access=allow` the node init dies inside
  ;; org.apache.arrow.memory.netty.DefaultAllocationManagerFactory.<clinit> with
  ;; a confusing trace far from here, so fail fast with a clear message. The
  ;; inferno runner adds this flag automatically on Java 24+.
  (let [version-prop (System/getProperty "java.specification.version")
        major        (try (Integer/parseInt version-prop) (catch Exception _ 0))
        input-args   (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean))
        unsafe-flag? (boolean (some #(.contains ^String % "sun-misc-unsafe-memory-access=allow")
                                    input-args))]
    (cond
      (< major 21)
      (throw (ex-info (str "test-server requires Java 21 or newer. Detected Java "
                           version-prop ". Set JAVA_HOME to a JDK 21+ install "
                           "(e.g. /usr/lib/jvm/java-21-openjdk-amd64) and retry.")
                      {:java-specification-version version-prop}))

      (and (>= major 24) (not unsafe-flag?))
      (throw (ex-info (str "On Java " version-prop " the XTDB node's Arrow/netty "
                           "allocator needs JEP-498 Unsafe memory access. Launch with "
                           "-J--sun-misc-unsafe-memory-access=allow (the inferno runner "
                           "adds this automatically), or use a JDK 21 install.")
                      {:java-specification-version version-prop})))))

(defn -main [& args]
  (assert-supported-jvm!)
  ;; Dev-only trace-tap must initialize BEFORE the logging/OTel handler so it
  ;; can install its SimpleSpanProcessor on the SDK that Telemere will adopt.
  (when (= "1" (System/getenv "DROMON_DEV_TRACE_TAP"))
    (when-let [init! (try (requiring-resolve 'server.dev.trace-tap/init!)
                          (catch Throwable _ nil))]
      (init!)))
  (logging/init-logging!)
  (let [first-arg (first args)
        opts {:store    (or (env-keyword "TEST_SERVER_STORE")   :xtdb2)
              :schemas  (or (env-keyword "TEST_SERVER_SCHEMAS") :uscore8)
              :port     (cond
                          (map? first-arg)    (:port first-arg)
                          (string? first-arg) (parse-long first-arg)
                          :else 8080)
              :ssl-port (cond
                          (map? first-arg)    (:ssl-port first-arg)
                          (string? first-arg) (parse-long first-arg)
                          :else 8443)}]
    (start-system! opts)))

(comment
  (start-system!)
  (start-system! {:store :mock :schemas :uscore8})
  (stop-system!))
