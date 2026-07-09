(ns server.core
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.util :as mu]
            [ring.adapter.jetty9 :as jetty]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [muuntaja.core :as m-core]
            [muuntaja.format.json :as muuntaja-json]
            [jsonista.core :as json]
            [server.routing :as routing]
            [server.middleware :as middleware]
            [server.fhir-coercion :as fhir-coercion]
            [server.search-registry :as sr]
            [ring.middleware.head :refer [wrap-head]]
            [server.auth :as auth]
            [server.keto :as keto]
            [server.scope :as scope]
            [server.compartment :as compartment]
            ;; Required eagerly so the :fhir/bulk-job-store init-key defmethod is
            ;; registered before ig/init runs (integrant does not auto-require a
            ;; bare "fhir" namespace for the composite key).
            [server.bulk-job-store]
            [integrant.core :as ig])
  (:import [com.fasterxml.jackson.datatype.jsr310 JavaTimeModule]
           [com.fasterxml.jackson.databind SerializationFeature]))

(def java-time-encode-mapper
  "Jackson ObjectMapper that serializes java.time objects to ISO strings."
  (doto (json/object-mapper {:modules [(JavaTimeModule.)]})
    (.disable com.fasterxml.jackson.databind.SerializationFeature/WRITE_DATES_AS_TIMESTAMPS)))

(def java-time-decode-mapper
  "Jackson ObjectMapper that deserializes with keyword keys."
  (json/object-mapper {:decode-key-fn keyword}))

(def muuntaja-instance
  (m-core/create
    (-> m-core/default-options
        (assoc-in [:formats "application/json" :matches] #"^application/(fhir\+)?json$")
        (assoc-in [:formats "application/json" :encoder-opts]
                  {:mapper java-time-encode-mapper})
        (assoc-in [:formats "application/json" :decoder-opts]
                  {:mapper java-time-decode-mapper
                   :bigdecimals true})
        (assoc-in [:formats "application/json-patch+json"]
                  {:decoder [muuntaja-json/decoder
                             {:mapper java-time-decode-mapper
                              :bigdecimals true}]
                   :encoder [muuntaja-json/encoder
                             {:mapper java-time-encode-mapper}]}))))

(def default-handlers
  {:read              'server.handlers/read-resource
   :search-type       'server.handlers/search-type
   :create            'server.handlers/create-resource
   :update            'server.handlers/update-resource
   :delete            'server.handlers/delete-resource
   :history-instance  'server.handlers/history-instance
   :history-type      'server.handlers/history-type
   :vread             'server.handlers/vread-resource
   :patch             'server.handlers/patch-resource
   :conditional-update  'server.handlers/conditional-update
   :conditional-delete  'server.handlers/conditional-delete
   :conditional-patch   'server.handlers/conditional-patch})

(def resource-operations
  "Built-in resource-level operations, keyed by resource type then operation
   name. Each operation maps HTTP method keywords to fully qualified handler
   symbols; non-method keys become Reitit route data (see
   `server.routing/build-operation-routes`).

   Consumers extend this per deployment by passing an `:operations` map of
   the same shape to [[resolve-schemas]] (or the `:fhir/schemas` integrant
   component); entries merge over these defaults."
  {"ValueSet" {"$expand" {:get  'server.handlers/valueset-expand
                           :post 'server.handlers/valueset-expand}
               "$lookup" {:get  'server.handlers/valueset-lookup
                           :post 'server.handlers/valueset-lookup}}})

(defn- merge-operations
  "Merges an extra {resourceType {op-name config}} operations map over the
   built-in defaults, merging per resource type so extensions can add
   operations to types that already have built-ins."
  [extra-operations]
  (merge-with merge resource-operations (or extra-operations {})))

(defn cap-data->multi-schema
  "Compile the capability data map (as emitted by
   `com.breezeehr.capability-statement/resource-cap-data-form`) into a
   malli `:multi` schema. The `:branches` map becomes the `:multi` branch
   list; `:dispatch`/`:resourceType`/`:interactions`/`:search-params`
   move to the `:multi` properties so existing readers of
   `(m/properties cap-schema)` keep working.

   When `registry` is non-nil it is attached to the compiled schema."
  [{:keys [resourceType dispatch interactions search-params branches]} registry]
  (let [multi-vec (into [:multi
                         {:dispatch      dispatch
                          :resourceType  resourceType
                          :interactions  interactions
                          :search-params search-params}]
                        branches)]
    (if registry
      (m/schema multi-vec {:registry registry})
      (m/schema multi-vec))))

(defn- cap-data?
  "True when `x` looks like the capability data map produced by the
   regenerated capability namespaces (rather than a pre-compiled malli
   `:multi` schema)."
  [x]
  (and (map? x)
       (contains? x :branches)
       (contains? x :resourceType)))

(defn capability-schema->server-schema
  "Convert a generated capability schema (data map or pre-compiled malli
   :multi) into a malli schema whose properties carry the metadata that
   `routing.clj` expects.

   When `cap-or-data` is the capability data map produced by the
   regenerated capability namespaces, this compiles the `:multi` first.
   The registry is read off the data map's `:registry` key when not
   supplied explicitly. Pre-compiled malli schemas are accepted for
   backward compatibility."
  ([cap-or-data]
   (capability-schema->server-schema cap-or-data nil nil))
  ([cap-or-data registry]
   (capability-schema->server-schema cap-or-data registry nil))
  ([cap-or-data registry extra-operations]
   (let [registry     (or registry (when (cap-data? cap-or-data) (:registry cap-or-data)))
         cap-compiled (if (cap-data? cap-or-data)
                        (cap-data->multi-schema cap-or-data registry)
                        cap-or-data)
         props           (m/properties  cap-compiled)
         ;; NOTE: keyword lookup on a compiled malli schema returns nil; the
         ;; resource type lives in the schema properties. Reading it off the
         ;; schema directly silently dropped operations for every type.
         resource-type   (:resourceType props)
         interactions    (:interactions props [])
         search-params   (:search-params props [])
         search-registry (sr/build-resource-registry search-params cap-compiled)
         interaction-map (into {}
                           (map (fn [i]
                                  (let [kw (keyword i)]
                                    (if (= kw :search-type)
                                      [kw {:search-parameters search-params}]
                                      [kw {}]))))
                           interactions)
         conditional-keys (cond-> []
                            (contains? interaction-map :update) (conj :conditional-update)
                            (contains? interaction-map :delete) (conj :conditional-delete)
                            (contains? interaction-map :patch)  (conj :conditional-patch))
         handlers        (select-keys default-handlers (into (keys interaction-map) conditional-keys))
         operations      (get (merge-operations extra-operations) resource-type {})]
     (mu/update-properties cap-compiled
                           into
                           {:fhir/interactions    interaction-map
                            :fhir/handlers        handlers
                            :fhir/operations      operations
                            :fhir/search-registry search-registry
                            :xtdb/collection      resource-type
                            :fhir/cap-schema      cap-compiled}))))

(defn- resolve-sym
  "requiring-resolve a fully qualified symbol, throwing if not found."
  [sym]
  (when-not (qualified-symbol? sym)
    (throw (ex-info "Schema spec must be a fully qualified symbol" {:sym sym})))
  (or (requiring-resolve sym)
      (throw (ex-info "Could not resolve schema var" {:sym sym}))))

(defn- sibling-registry-var
  "Return the value of a `registry` Var in the same namespace as `schema-sym`,
   or nil if there isn't one. Capability namespaces emit a sibling
   `registry` Var with the composite registry needed to compile a `:multi`
   built from the data map's branches."
  [schema-sym]
  (when-let [v (some-> (resolve (symbol (namespace schema-sym) "registry"))
                       deref)]
    v))

(defn resolve-schema
  "Resolve a single schema spec into a server-ready capability schema.
   A spec is either:
   - a fully qualified symbol naming a Var holding either the new
     capability data map (e.g. `us-core.capability.v8-0-1.Patient/capability`
     (named `capability`) or a pre-compiled malli `:multi` schema
     (legacy/base-resource Vars); OR
   - a map `{:schema <fq-sym> :interactions [..]}` where :interactions,
     when provided, override the schema-declared interactions before
     conversion."
  ([spec] (resolve-schema spec nil))
  ([spec {:keys [operations]}]
   (let [{:keys [schema interactions]} (if (map? spec) spec {:schema spec})
         resolved @(resolve-sym schema)
         registry (when (cap-data? resolved) (sibling-registry-var schema))
         resolved (if interactions
                    (if (cap-data? resolved)
                      (assoc resolved :interactions interactions)
                      (mu/update-properties resolved into {:interactions interactions}))
                    resolved)]
     (capability-schema->server-schema resolved registry operations))))

(defn resolve-schemas
  "Resolve a collection of schema specs (see [[resolve-schema]]) into the
   server-ready vector consumed by [[fhir-app]] and the routing layer.

   `opts` supports:
   - :operations -- {resourceType {op-name {method handler-sym, ...}}} map of
     deployment-specific operations merged over [[resource-operations]]."
  ([specs] (resolve-schemas specs nil))
  ([specs opts]
   (mapv #(resolve-schema % opts) specs)))

(defmethod ig/init-key :fhir/schemas [_ {:keys [specs operations]}]
  (resolve-schemas specs {:operations operations}))

(defn wrap-fhir-store [handler store]
  (fn [req]
    (handler (assoc req :fhir/store store))))

(defn wrap-terminology [handler terminology]
  (fn [req]
    (handler (assoc req :fhir/terminology terminology))))

(defn wrap-bulk-job-store
  "Inject the Bulk Data Access ($export) job registry into each request,
   mirroring wrap-fhir-store. The bulk-export handlers read it from
   :fhir/bulk-job-store."
  [handler bulk-job-store]
  (fn [req]
    (handler (assoc req :fhir/bulk-job-store bulk-job-store))))

(defn- parse-cors-origins
  "Parses CORS allowed origins from a comma-separated string or collection into a set.
   Returns nil if input is nil or blank."
  [origins]
  (cond
    (set? origins) origins
    (coll? origins) (set origins)
    (string? origins) (if (str/blank? origins)
                        nil
                        (set (map str/trim (str/split origins #","))))
    :else nil))

(defn fhir-app
  [store schemas & {:keys [jwks-url keto-url terminology cors-allowed-origins enforce-smart-scopes? bulk-job-store]}]
  (let [jwks-url (or jwks-url
                     (System/getenv "JWKS_URL")
                     (when-not (System/getenv "JWT_DEV_SECRET")
                       "http://localhost:4444/.well-known/jwks.json"))
        keto-url (or keto-url (System/getenv "KETO_URL") "http://localhost:4466")
        enforce-smart-scopes? (if (some? enforce-smart-scopes?)
                                enforce-smart-scopes?
                                (= "1" (System/getenv "ENFORCE_SMART_SCOPES")))
        cors-origins (parse-cors-origins
                       (or cors-allowed-origins
                           (System/getenv "CORS_ALLOWED_ORIGINS")))
        ;; Dev-only per-request span capture. Only loaded when env is set,
        ;; so the OTel SDK is not required on the default classpath.
        trace-tap-mw (when (= "1" (System/getenv "DROMON_DEV_TRACE_TAP"))
                       (some-> (requiring-resolve 'server.dev.trace-tap/wrap-trace-tap)
                               deref))]
  (ring/ring-handler
   (ring/router
    (routing/build-fhir-routes schemas)
    {:conflicts nil
     :data {:coercion fhir-coercion/coercion
            :muuntaja muuntaja-instance
            :fhir/all-registries (routing/collect-registries schemas)
            :middleware (cond-> []
                          trace-tap-mw (conj trace-tap-mw)
                          true (into [middleware/wrap-telemere-trace
                                      middleware/wrap-otel-context
                                      wrap-head
                         middleware/wrap-request-id
                         [middleware/wrap-cors cors-origins]
                         parameters/parameters-middleware
                         middleware/wrap-format-override
                         middleware/wrap-not-acceptable
                         middleware/wrap-unsupported-media-type
                         muuntaja/format-negotiate-middleware
                         middleware/wrap-fhir-response-headers
                         middleware/wrap-summary
                         middleware/wrap-elements
                         middleware/wrap-prefer
                         [middleware/wrap-pretty-print java-time-encode-mapper]
                         muuntaja/format-response-middleware
                         middleware/wrap-fhir-exceptions
                         muuntaja/format-request-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware
                         rrc/coerce-exceptions-middleware
                         [wrap-fhir-store store]
                         [wrap-terminology terminology]
                         [wrap-bulk-job-store bulk-job-store]
                         [auth/wrap-jwt-auth {:jwks-url jwks-url}]])
                          enforce-smart-scopes? (conj [scope/wrap-smart-scope {}]
                                                      [compartment/wrap-patient-compartment {}])
                          true (conj [keto/wrap-keto-authorization {:keto-url keto-url}]))}})
   (some-fn
    (ring/redirect-trailing-slash-handler {:method :strip})
    ;; The default handler runs OUTSIDE the router's middleware chain, so
    ;; muuntaja never encodes its body. A raw map body makes the Jetty
    ;; adapter throw (no StreamableResponseBody impl for PersistentArrayMap),
    ;; turning every unmatched route (e.g. a resource type with no schema)
    ;; into a 500. Pre-encode the OperationOutcome to a JSON string.
    (ring/create-default-handler
     {:not-found
      (constantly
       {:status 404
        :headers {"Content-Type" "application/fhir+json"}
        :body (json/write-value-as-string
               {:resourceType "OperationOutcome"
                :issue [{:severity "error"
                         :code "not-found"
                         :diagnostics "Resource or endpoint not found"}]})})})))))


(defmethod ig/init-key :server/jetty [_ {:keys [port ssl-port keystore keystore-type key-password store schemas
                                                jwks-url keto-url terminology cors-allowed-origins bulk-job-store]}]
  (println "Starting Jetty Server on port" port "and SSL port" ssl-port "with virtual threads")
  (let [jetty-opts (merge {:port port
                           :join? false
                           :virtual-threads? true}
                          (when ssl-port
                            {:ssl? true
                             :ssl-port ssl-port
                             :keystore keystore
                             :keystore-type keystore-type
                             :key-password key-password}))]
    (jetty/run-jetty (fhir-app store schemas :jwks-url jwks-url :keto-url keto-url :terminology terminology
                               :cors-allowed-origins cors-allowed-origins :bulk-job-store bulk-job-store) jetty-opts)))

(defmethod ig/halt-key! :server/jetty [_ server]
  (println "Stopping Jetty Server")
  (.stop server))
