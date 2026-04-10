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
            [server.logging :as logging]
            [integrant.core :as ig]
            [fhir-terminology.tx-proxy]
            [fhir-terminology.cache]
            [test-server.search-params :as sp]
            [fhir-store.protocol :as db]))

;; ---------------------------------------------------------------------------
;; Config selection
;; ---------------------------------------------------------------------------

(def ^:private store-presets
  "Map of store-name -> integrant config fragment that constructs an
   IFHIRStore under the key `:test-server/store`. Each fragment is
   merged into the system config; the namespaces it references are
   loaded lazily via `require` in `load-store-ns!` before init."
  {:xtdb2 {:requires '[fhir-store-xtdb2.core]
           :extra    {:fhir-store/xtdb2-node  {}
                      :fhir-store/xtdb2-store {:node             (ig/ref :fhir-store/xtdb2-node)
                                               :resource/schemas (ig/ref :fhir/schemas)}}
           :store-ref (ig/ref :fhir-store/xtdb2-store)}
   :mock  {:requires '[fhir-store.mock.sys]
           :extra    {:fhir-store/mock {}}
           :store-ref (ig/ref :fhir-store/mock)}})

(def ^:private schema-presets
  "Map of schema-package -> namespace whose `specs` Var lists the schema
   specs to feed into `:fhir/schemas`."
  {:uscore8 'test-server.schemas.uscore8})

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
    (merge {:fhir/schemas {:specs specs}
            :test-server/seeder    {:store store-ref}
            :fhir-terminology/tx-proxy {:base-url nil}
            :fhir-terminology/cached   {:delegate (ig/ref :fhir-terminology/tx-proxy)}
            :server/jetty {:port          port
                           :ssl-port      ssl-port
                           :keystore      "../fhir-server/dev-keystore.p12"
                           :keystore-type "PKCS12"
                           :key-password  (or (System/getenv "KEYSTORE_PASSWORD") "changeit")
                           :store         store-ref
                           :schemas       (ig/ref :fhir/schemas)
                           :terminology   (ig/ref :fhir-terminology/cached)}}
           extra)))

(defmethod ig/init-key :test-server/seeder [_ {:keys [store]}]
  (println "Seeding SearchParameters...")
  (doseq [p sp/search-parameters]
    (db/create-resource store "default" :SearchParameter (:id p) p))
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
  ;; The server depends on java.util.concurrent.StructuredTaskScope$ShutdownOnFailure,
  ;; which was a JEP-453 preview API present in JDK 21-24 and removed in JDK 25 when
  ;; structured concurrency was finalized with a different class shape. Running under
  ;; JDK 25+ produces a ClassNotFoundException a few seconds after Jetty starts, with
  ;; a confusing trace far from the binding. Fail fast with a clear message instead.
  (let [version-prop (System/getProperty "java.specification.version")
        major        (try (Integer/parseInt version-prop) (catch Exception _ 0))]
    (when (>= major 25)
      (throw (ex-info (str "test-server requires Java 21-24. Detected Java "
                           version-prop ". JDK 25 removed the preview "
                           "java.util.concurrent.StructuredTaskScope$ShutdownOnFailure "
                           "API that this server depends on. Set JAVA_HOME to a JDK 21 "
                           "install (e.g. /usr/lib/jvm/java-21-openjdk-amd64) and retry.")
                      {:java-specification-version version-prop})))))

(defn -main [& args]
  (assert-supported-jvm!)
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
