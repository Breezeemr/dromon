(ns server.routing
  (:require [malli.core :as m]
            [server.fhir-coercion :as fc]
            [server.handlers :as handlers]))

(def ^:private open-responses
  "Response schemas that allow any body shape (for search/history Bundles)."
  {200      {:body :any}
   201      {:body :any}
   204      {:body :any}
   304      {:body :any}
   :default {:body :any}})

(defn- resource-responses
  "Response schemas for endpoints returning a single resource (read/vread/update).
   Validates 200 responses against cap-schema."
  [cap-schema]
  (if cap-schema
    {200      {:body cap-schema}
     201      {:body cap-schema}
     204      {:body :any}
     304      {:body :any}
     :default {:body fc/operation-outcome-schema}}
    open-responses))

(defn resolve-handler [sym]
  (when sym
    (require (symbol (namespace sym)))
    (let [v (resolve sym)]
      (when v @v)))) ;; deref the var to get the actual function

(defn- build-interaction-routes
  "Given a fhir type and its configured interactions/handlers, build nested Reitit
   route vectors. Uses nested routing so static segments (/_search, /_history)
   take precedence over wildcard segments (/:id).

   In reitit, a route with children is a prefix only — to make it also an endpoint,
   handlers go in a \"\" (empty string) child route."
  [fhir-type interactions handlers cap-schema search-registry all-registries decoders encoders]
  (let [wrap-handler (fn [interaction config]
                       (let [handler-sym (get handlers interaction)
                             base-handler (resolve-handler handler-sym)]
                         (fn [req] (base-handler (assoc req
                                                        :fhir/resource-type fhir-type
                                                        :fhir/interaction-config config
                                                        :fhir/search-registry search-registry
                                                        :fhir/all-registries all-registries)))))
        wrap-decode-contained (resolve-handler 'server.handlers/wrap-decode-contained)
        wrap-encode-resp      (resolve-handler 'server.handlers/wrap-encode-contained-response)
        wrap-with-contained   (fn [h] (if decoders (wrap-decode-contained h decoders) h))
        wrap-with-encode      (fn [h] (if encoders (wrap-encode-resp h encoders) h))
        base-path (str "/:tenant-id/fhir/" fhir-type)

        res-responses (resource-responses cap-schema)

        ;; Type-level endpoint data (GET search, POST create)
        type-data (cond-> {}
                    (contains? interactions :search-type)
                    (assoc :get (wrap-with-encode (wrap-handler :search-type (get interactions :search-type))))

                    (contains? interactions :create)
                    (assoc :post {:handler    (wrap-with-encode (wrap-with-contained (wrap-handler :create (get interactions :create))))
                                  :parameters {:body (or cap-schema :any)}})

                    (contains? interactions :update)
                    (assoc :put {:handler (wrap-with-encode (wrap-with-contained (wrap-handler :conditional-update (get interactions :update))))
                                 :parameters {:body (or cap-schema :any)}})

                    (contains? interactions :delete)
                    (assoc :delete (wrap-handler :conditional-delete (get interactions :delete)))

                    (contains? interactions :patch)
                    (assoc :patch {:handler (wrap-handler :conditional-patch (get interactions :patch))
                                   :parameters {:body fc/json-patch-schema}})

                    true
                    (assoc :responses open-responses))

        ;; /:id endpoint data (GET read, PUT update, PATCH, DELETE)
        id-data (cond-> {}
                  (contains? interactions :read)
                  (assoc :get (wrap-with-encode (wrap-handler :read (get interactions :read))))

                  (contains? interactions :update)
                  (assoc :put {:handler    (wrap-with-encode (wrap-with-contained (wrap-handler :update (get interactions :update))))
                               :parameters {:body (or cap-schema :any)}})

                  (contains? interactions :patch)
                  (assoc :patch {:handler    (wrap-handler :patch (get interactions :patch))
                                 :parameters {:body fc/json-patch-schema}})

                  (contains? interactions :delete)
                  (assoc :delete (wrap-handler :delete (get interactions :delete)))

                  true
                  (assoc :responses res-responses))

        ;; /_history under /:id — may have /:vid child
        id-history-children (cond-> []
                              (contains? interactions :history-instance)
                              (conj ["" {:get (wrap-with-encode (wrap-handler :history-instance (get interactions :history-instance)))
                                         :responses open-responses}])

                              (contains? interactions :vread)
                              (conj ["/:vid" {:get (wrap-with-encode (wrap-handler :vread (get interactions :vread)))
                                              :responses res-responses}]))

        ;; Compartment search child under /:id (only for compartment-defining types)
        compartment-handler (when (handlers/valid-compartment-types fhir-type)
                              (let [base-handler (resolve-handler 'server.handlers/compartment-search)]
                                (fn [req]
                                  (base-handler (assoc req
                                                       :fhir/all-registries all-registries
                                                       :path-params (-> (:path-params req)
                                                                        (assoc :compartment-type fhir-type
                                                                               :compartment-id (:id (:path-params req))
                                                                               :target-type (:target-type (:path-params req)))))))))

        ;; Children under /:id
        id-children (cond-> []
                      (seq id-data)
                      (conj ["" id-data])

                      (seq id-history-children)
                      (conj (into ["/_history" {}] id-history-children))

                      compartment-handler
                      (conj ["/:target-type" {:get compartment-handler}]))

        ;; $validate handler — always available when cap-schema exists
        validate-handler (when cap-schema
                           (let [base-handler (resolve-handler 'server.handlers/validate-resource)]
                             (fn [req]
                               (base-handler (assoc req
                                                    :fhir/resource-type fhir-type
                                                    :fhir/cap-schema cap-schema)))))

        ;; Type-level children (/_search, /_history, $validate, /:id)
        type-children (cond-> []
                        ;; Type-level endpoint (GET search, POST create)
                        (seq type-data)
                        (conj ["" type-data])

                        (contains? interactions :search-type)
                        (conj ["/_search" {:post (wrap-with-encode (wrap-handler :search-type (get interactions :search-type)))
                                           :responses open-responses}])

                        (contains? interactions :history-type)
                        (conj ["/_history" {:get (wrap-with-encode (wrap-handler :history-type (get interactions :history-type)))}])

                        validate-handler
                        (conj ["/$validate" {:post {:handler validate-handler
                                                    :parameters {:body :any}}}])

                        (seq id-children)
                        (conj (into ["/:id" {}] id-children)))]

    (into [base-path {}] type-children)))

(defn- build-operation-routes
  "Given a fhir type and its configured operations, build Reitit route vectors."
  [fhir-type operations]
  (reduce
   (fn [acc [op-name methods-map]]
     (let [route-data (reduce
                       (fn [m [method handler-sym]]
                         (let [handler-fn (resolve-handler handler-sym)
                               wrapped-handler (fn [req]
                                                 (handler-fn (assoc req
                                                                    :fhir/resource-type fhir-type
                                                                    :fhir/operation op-name)))]
                           (assoc m method wrapped-handler)))
                       {}
                       methods-map)]
       (-> acc
           (conj [(str "/:tenant-id/fhir/" fhir-type "/" op-name) route-data])
           (conj [(str "/:tenant-id/fhir/" fhir-type "/:id/" op-name) route-data]))))
   []
   operations))

(defn build-resource-routes
  "Takes a list of malli schemas, extracts properties, and returns Reitit routes.
   `decoders` and `encoders` maps ({resource-type → fn}) wire the
   contained-resource request decoder and the response demoter into
   create/update/read/search handlers."
  ([schemas] (build-resource-routes schemas nil nil))
  ([schemas decoders encoders]
   (let [;; Collect all search registries first so each route can access any type's registry
         all-registries (reduce
                         (fn [acc schema]
                           (let [props (m/properties schema)
                                 fhir-type (:resourceType props)
                                 search-registry (:fhir/search-registry props)]
                             (if (and fhir-type search-registry)
                               (assoc acc fhir-type search-registry)
                               acc)))
                         {}
                         schemas)]
     (reduce
      (fn [acc schema]
        (let [props (m/properties schema)
              fhir-type (:resourceType props)
              interactions (:fhir/interactions props {})
              handlers (:fhir/handlers props {})
              operations (:fhir/operations props {})]
          (if fhir-type
            (let [cap-schema (:fhir/cap-schema props)
                  search-registry (:fhir/search-registry props)
                  interaction-route (when (seq interactions)
                                      (build-interaction-routes fhir-type interactions handlers cap-schema search-registry all-registries decoders encoders))
                  operation-routes (when (seq operations)
                                     (build-operation-routes fhir-type operations))]
              (cond-> acc
                interaction-route (conj interaction-route)
                operation-routes (into operation-routes)))
            acc)))
      []
      schemas))))

(defn build-system-routes
  "Returns non-resource FHIR system routes."
  [schemas all-registries decoders]
  (let [wrap-system-search (fn [handler-fn]
                             (fn [req]
                               (handler-fn (assoc req :fhir/all-registries all-registries))))]
    [["/.well-known/smart-configuration" {:get  ((resolve-handler 'server.handlers/smart-configuration))
                                          :public? true}]
     ["/:tenant-id/fhir/metadata" {:get ((resolve-handler 'server.handlers/capability-statement) schemas)
                                   :public? true}]
     ["/:tenant-id/fhir/_history" {:get (wrap-system-search (resolve-handler 'server.handlers/system-history))}]
     ["/:tenant-id/fhir/_search"  {:get (wrap-system-search (resolve-handler 'server.handlers/system-search))
                                   :post (wrap-system-search (resolve-handler 'server.handlers/system-search))}]
     ["/:tenant-id/fhir"          {:post (let [build-tx (resolve-handler 'server.handlers/transaction)]
                                           (build-tx decoders))}]]))

(defn build-fhir-routes [schemas]
  (let [;; Compute all-registries once for both system and resource routes
        all-registries (reduce
                        (fn [acc schema]
                          (let [props (m/properties schema)
                                fhir-type (:resourceType props)
                                search-registry (:fhir/search-registry props)]
                            (if (and fhir-type search-registry)
                              (assoc acc fhir-type search-registry)
                              acc)))
                        {}
                        schemas)
        ;; Per-resourceType cap-schema decoders & encoders, used for bundle
        ;; entries and recursive :contained handling on read/write routes.
        decoders ((resolve-handler 'server.handlers/build-resource-decoders) schemas)
        encoders ((resolve-handler 'server.handlers/build-resource-encoders) schemas)]
    (into (build-system-routes schemas all-registries decoders)
          (build-resource-routes schemas decoders encoders))))
