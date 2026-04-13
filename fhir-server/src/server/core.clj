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
  {"ValueSet" {"$expand" {:get  'server.handlers/valueset-expand
                           :post 'server.handlers/valueset-expand}
               "$lookup" {:get  'server.handlers/valueset-lookup
                           :post 'server.handlers/valueset-lookup}}})

(defn capability-schema->server-schema
  "Convert a generated capability schema into a :map schema with the metadata
   format expected by routing.clj.
   `cap-sch-vec` is the raw :multi vector (used for extracting metadata).
   `cap-compiled` is the pre-compiled malli schema (used for encoding/decoding)."
  [cap-compiled]
  (let [resource-type (:resourceType cap-compiled)
        props         (m/properties  cap-compiled)
        interactions  (:interactions props [])
        search-params (:search-params props [])
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
        handlers      (select-keys default-handlers (into (keys interaction-map) conditional-keys))
        operations    (get resource-operations resource-type {})]
    (mu/update-properties cap-compiled
                          into
                          {:fhir/interactions   interaction-map
                           :fhir/handlers       handlers
                           :fhir/operations     operations
                           :fhir/search-registry search-registry
                           :xtdb/collection     (:resourceType cap-compiled)
                           :fhir/cap-schema     cap-compiled})))

(defn- resolve-sym
  "requiring-resolve a fully qualified symbol, throwing if not found."
  [sym]
  (when-not (qualified-symbol? sym)
    (throw (ex-info "Schema spec must be a fully qualified symbol" {:sym sym})))
  (or (requiring-resolve sym)
      (throw (ex-info "Could not resolve schema var" {:sym sym}))))

(defn resolve-schema
  "Resolve a single schema spec into a server-ready capability schema.
   A spec is either:
   - a fully qualified symbol naming a Var holding a compiled malli capability
     schema (e.g. `us-core.capability.v8-0-1.Patient/full-sch`); OR
   - a map `{:schema <fq-sym> :interactions [..]}` where :interactions, when
     provided, is merged into the schema's properties before conversion."
  [spec]
  (let [{:keys [schema interactions]} (if (map? spec) spec {:schema spec})
        compiled @(resolve-sym schema)
        compiled (if interactions
                   (mu/update-properties compiled into {:interactions interactions})
                   compiled)]
    (capability-schema->server-schema compiled)))

(defn resolve-schemas
  "Resolve a collection of schema specs (see [[resolve-schema]]) into the
   server-ready vector consumed by [[fhir-app]] and the routing layer."
  [specs]
  (mapv resolve-schema specs))

(defmethod ig/init-key :fhir/schemas [_ {:keys [specs]}]
  (resolve-schemas specs))

(defn wrap-fhir-store [handler store]
  (fn [req]
    (handler (assoc req :fhir/store store))))

(defn wrap-terminology [handler terminology]
  (fn [req]
    (handler (assoc req :fhir/terminology terminology))))

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
  [store schemas & {:keys [jwks-url keto-url terminology cors-allowed-origins]}]
  (let [jwks-url (or jwks-url (System/getenv "JWKS_URL") "http://localhost:4444/.well-known/jwks.json")
        keto-url (or keto-url (System/getenv "KETO_URL") "http://localhost:4466")
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
            :middleware (cond-> [middleware/wrap-telemere-trace
                                 middleware/wrap-otel-context]
                          trace-tap-mw (conj trace-tap-mw)
                          true (into [wrap-head
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
                         [auth/wrap-jwt-auth {:jwks-url jwks-url}]
                         [keto/wrap-keto-authorization {:keto-url keto-url}]]))}})
   (some-fn
    (ring/redirect-trailing-slash-handler {:method :strip})
    (ring/create-default-handler
     {:not-found (constantly {:status 404
                              :body {:resourceType "OperationOutcome"
                                     :issue [{:severity "error"
                                              :code "not-found"
                                              :diagnostics "Resource or endpoint not found"}]}})})))))


(defmethod ig/init-key :server/jetty [_ {:keys [port ssl-port keystore keystore-type key-password store schemas
                                                jwks-url keto-url terminology cors-allowed-origins]}]
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
                               :cors-allowed-origins cors-allowed-origins) jetty-opts)))

(defmethod ig/halt-key! :server/jetty [_ server]
  (println "Stopping Jetty Server")
  (.stop server))
