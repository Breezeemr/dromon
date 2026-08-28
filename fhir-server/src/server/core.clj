(ns server.core
  (:require [malli.core :as m]
            [malli.util :as mu]
            [ring.adapter.jetty9 :as jetty]
            [server.router :as router]
            [server.search-registry :as sr]
            ;; Required eagerly so the :fhir/bulk-job-store init-key defmethod is
            ;; registered before ig/init runs (integrant does not auto-require a
            ;; bare "fhir" namespace for the composite key).
            [server.bulk-job-store]
            [integrant.core :as ig]))

;; Back-compat aliases. These moved to server.router when the router assembly
;; was made composable; consumers outside this repo (test-server tests, the
;; jib2 contract spike) still reference them here.

(def java-time-encode-mapper
  "Moved to server.router; alias kept for external consumers."
  router/java-time-encode-mapper)

(def java-time-decode-mapper
  "Moved to server.router; alias kept for external consumers."
  router/java-time-decode-mapper)

(def muuntaja-instance
  "Moved to server.router; alias kept for external consumers."
  router/muuntaja-instance)

(def wrap-fhir-store
  "Moved to server.router; alias kept for external consumers."
  router/wrap-fhir-store)

(def wrap-terminology
  "Moved to server.router; alias kept for external consumers."
  router/wrap-terminology)

(def wrap-bulk-job-store
  "Moved to server.router; alias kept for external consumers."
  router/wrap-bulk-job-store)

(def wrap-keto-url
  "Moved to server.router; alias kept for external consumers."
  router/wrap-keto-url)

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

(defn- lenient-schema
  "Relax `schema` for response validation of externally produced (e.g.
   decanted) resources: every inline :map becomes open and all its entries
   optional, so undeclared promoted keys pass and base-required elements the
   store never held do not fail the read. Keys that ARE present still
   validate against their declared schemas. m/walk does not enter :ref
   children, so datatype refs (CodeableConcept, Reference, ...) are
   untouched; FHIR required cardinalities live on resource roots and
   inlined BackboneElements, which this does reach."
  [schema]
  (m/walk schema
          (m/schema-walker
           (fn [s]
             (if (= :map (m/type s))
               (-> s
                   (mu/update-properties dissoc :closed)
                   mu/optional-keys)
               s)))))

(defn- lenient-default-branch-multi
  "Compile cap-data's :multi with the :default branch relaxed by
   [[lenient-schema]]. Branches keyed by concrete meta.profile URLs are kept
   exactly as generated, so profiled resources keep strict validation."
  [cap-data registry]
  (let [branches (mapv (fn [branch]
                         (if (= :default (first branch))
                           [:default (lenient-schema
                                      (m/schema (peek branch)
                                                (when registry {:registry registry})))]
                           branch))
                       (:branches cap-data))]
    (cap-data->multi-schema (assoc cap-data :branches branches) registry)))

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

(defn- maybe-apply-breeze-storage-overlay
  "When the Breeze storage registry is on the classpath (breeze-ig malli
   package), recompile `schema` so HumanName/Address resolve to USEP
   card-one string annotations. No-op when the package is absent so
   open-source dromon stays free of a hard breeze-ig dependency."
  [schema]
  (try
    (if-let [apply-overlay (requiring-resolve
                            'com.breezehealthplatform.breeze.storage.registry/apply-overlay)]
      (or (apply-overlay schema) schema)
      schema)
    (catch Throwable _
      schema)))

(defn resolve-schema
  "Resolve a single schema spec into a server-ready capability schema.
   A spec is either:
   - a fully qualified symbol naming a Var holding either the new
     capability data map (e.g. `us-core.capability.v8-0-1.Patient/capability`
     or `breeze.capability.v1-0-0.Patient/capability`) or a pre-compiled
     malli `:multi` schema (legacy/base-resource Vars); OR
   - a map `{:schema <fq-sym> :interactions [..] :search-params [..]}` where
     :interactions and :search-params, when provided, override what the schema
     declares before conversion.

   :search-params matters for a spec pointing at a plain resource schema
   (`...StructureDefinition.Group.v4-3-0/full-sch`) rather than a generated
   capability namespace: only capability namespaces carry a search parameter
   list, so a plain schema that declares the `search-type` interaction gets an
   empty search registry, and `server.handlers/search-type` — which rejects any
   parameter the registry does not declare — then answers every filtered search
   on that type with a 400. Each entry is `{:name :type :definition}`, with
   :definition the SearchParameter's canonical URL; the JSON behind it is
   loaded off the classpath (see `server.search-registry/build-resource-registry`).

   :lenient-default-responses? -- cap-data specs only (no-op for plain
   full-sch specs); attaches :fhir/response-schema, a copy of the :multi
   whose :default branch is open with all-optional entries; used by routing
   for read/vread/create/update RESPONSE validation while request bodies
   keep the strict cap-schema. For stores that hold externally produced
   (decanted) resources without meta.profile.

   When `com.breezehealthplatform.breeze.storage.registry` is loadable,
   the resolved schema is recompiled under that registry overlay so
   ordered multi-string fields (HumanName.given/prefix/suffix,
   Address.line) store as USEP cardinality-one strings."
  ([spec] (resolve-schema spec nil))
  ([spec {:keys [operations]}]
   (let [{:keys [schema interactions search-params lenient-default-responses?]} (if (map? spec) spec {:schema spec})
         resolved @(resolve-sym schema)
         registry (when (cap-data? resolved) (sibling-registry-var schema))
         resolved (if interactions
                    (if (cap-data? resolved)
                      (assoc resolved :interactions interactions)
                      (mu/update-properties resolved into {:interactions interactions}))
                    resolved)
         resolved (if search-params
                    (if (cap-data? resolved)
                      (assoc resolved :search-params search-params)
                      (mu/update-properties resolved into {:search-params search-params}))
                    resolved)
         ;; Attach the lenient copy AFTER the overlay so the overlay's
         ;; (m/schema (m/form stamped) opts) recompile never has to carry it.
         server-schema (-> (capability-schema->server-schema resolved registry operations)
                           maybe-apply-breeze-storage-overlay)]
     (if (and lenient-default-responses? (cap-data? resolved))
       (mu/update-properties server-schema assoc :fhir/response-schema
                             (lenient-default-branch-multi resolved (or registry (:registry resolved))))
       server-schema))))

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

(defn fhir-app
  "Builds the complete dromon FHIR ring handler for `store` and the
   server-ready `schemas` vector (see [[resolve-schemas]]).

   Options: `:jwks-url`, `:keto-url`, `:terminology`, `:cors-allowed-origins`,
   `:enforce-smart-scopes?`, `:bulk-job-store`; each falls back to an
   environment variable as documented on `server.router/resolve-options`.

   This is a thin composition of `server.router/resolve-options`,
   `default-middleware`, `router` and `default-handler`. Hosts that need to
   insert, replace or extend middleware should compose those pieces directly
   rather than fork this function -- see the `server.router` namespace
   docstring."
  [store schemas & {:as opts}]
  (router/handler schemas (router/default-middleware store (router/resolve-options opts))))


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
