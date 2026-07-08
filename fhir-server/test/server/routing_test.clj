(ns server.routing-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [server.core :as sc]
            [server.routing :as routing]))

(defn- match-template
  "Route template matched for a path on a router built the way fhir-app
   builds it ({:conflicts nil} resolves conflicts by insertion order)."
  [routes path]
  (-> (ring/router routes {:conflicts nil})
      (r/match-by-path path)
      :template))

(deftest type-level-operations-not-shadowed-by-id-route
  ;; Regression: build-resource-routes used to emit the interaction tree
  ;; before operation routes, so under {:conflicts nil} the /{Type}/:id
  ;; read route matched /{Type}/$op first. That shadowed every type-level
  ;; operation, including the built-in ValueSet/$expand.
  (let [schema (m/schema
                [:map {:resourceType "Appointment"
                       :fhir/interactions {:read {}}
                       :fhir/handlers {:read 'clojure.core/identity}
                       :fhir/operations
                       {"$probe" {:get 'clojure.core/identity}}}])
        routes (routing/build-resource-routes [schema])]
    (testing "type-level operation path matches the operation route"
      (is (= "/:tenant-id/fhir/Appointment/$probe"
             (match-template routes "/t/fhir/Appointment/$probe"))))
    (testing "reads by id still match the interaction tree"
      (is (= "/:tenant-id/fhir/Appointment/:id"
             (match-template routes "/t/fhir/Appointment/a1"))))
    (testing "the matched operation handler receives operation context"
      (let [handler (ring/ring-handler (ring/router routes {:conflicts nil}))
            req-seen (handler {:request-method :get
                               :uri "/t/fhir/Appointment/$probe"})]
        (is (= "$probe" (:fhir/operation req-seen)))
        (is (= "Appointment" (:fhir/resource-type req-seen))))))
  (testing "built-in ValueSet/$expand routes ahead of the read wildcard"
    (let [schema (m/schema [:map {:resourceType "ValueSet"
                                  :fhir/interactions {:read {}}
                                  :fhir/handlers {:read 'clojure.core/identity}}
                            [:id :string]])
          server-schema (sc/capability-schema->server-schema schema nil nil)
          routes (routing/build-resource-routes [server-schema])]
      (is (= "/:tenant-id/fhir/ValueSet/$expand"
             (match-template routes "/t/fhir/ValueSet/$expand"))))))

(deftest operation-routes-carry-route-data
  (testing "non-method keys in an operation config surface as Reitit route
            data on both the type-level and instance-level routes"
    (let [schema (m/schema
                  [:map {:resourceType "Appointment"
                         :fhir/operations
                         {"$telehealth-signal"
                          {:get  'server.telehealth/poll-signal
                           :post 'server.telehealth/post-signal
                           :keto/relation "read"}}}])
          routes (routing/build-resource-routes [schema])
          by-path (into {} (map (juxt first second)) routes)
          type-route (get by-path "/:tenant-id/fhir/Appointment/$telehealth-signal")
          instance-route (get by-path "/:tenant-id/fhir/Appointment/:id/$telehealth-signal")]
      (is (some? type-route))
      (is (some? instance-route))
      (is (= "read" (:keto/relation instance-route)))
      (is (= "read" (:keto/relation type-route)))
      (is (fn? (:get instance-route)))
      (is (fn? (:post instance-route)))
      (is (not (contains? instance-route :keto/relation-fn))
          "only declared keys pass through"))))

(deftest operation-route-handler-injects-operation-context
  (let [schema (m/schema
                [:map {:resourceType "Appointment"
                       :fhir/operations
                       {"$probe" {:get 'clojure.core/identity}}}])
        routes (routing/build-resource-routes [schema])
        by-path (into {} (map (juxt first second)) routes)
        handler (:get (get by-path "/:tenant-id/fhir/Appointment/:id/$probe"))
        req-seen (handler {:path-params {:tenant-id "t" :id "a"}})]
    (is (= "Appointment" (:fhir/resource-type req-seen)))
    (is (= "$probe" (:fhir/operation req-seen)))))

(deftest operations-attach-from-schema-properties
  (testing "resource type is read from schema properties, not keyword lookup
            on the compiled schema (which returns nil and silently dropped
            operations for every type)"
    (let [schema (m/schema [:map {:resourceType "ValueSet"} [:id :string]])
          server-schema (sc/capability-schema->server-schema schema nil nil)
          ops (:fhir/operations (m/properties server-schema))]
      (is (= #{"$expand" "$lookup"} (set (keys ops))))))
  (testing "extra operations reach types resolved through the same path"
    (let [schema (m/schema [:map {:resourceType "Appointment"} [:id :string]])
          server-schema (sc/capability-schema->server-schema
                         schema nil
                         {"Appointment" {"$telehealth-signal"
                                         {:get 'server.telehealth/poll-signal}}})
          ops (:fhir/operations (m/properties server-schema))]
      (is (contains? ops "$telehealth-signal")))))

(deftest extra-operations-merge-over-builtins
  (testing "resolve-schemas :operations extends a type that already has
            built-in operations without clobbering them"
    (let [merged (#'sc/merge-operations
                  {"ValueSet"     {"$custom" {:get 'clojure.core/identity}}
                   "Appointment"  {"$telehealth-signal" {:get 'server.telehealth/poll-signal}}})]
      (is (contains? (get merged "ValueSet") "$expand"))
      (is (contains? (get merged "ValueSet") "$lookup"))
      (is (contains? (get merged "ValueSet") "$custom"))
      (is (contains? (get merged "Appointment") "$telehealth-signal")))))
