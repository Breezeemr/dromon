(ns test-server.fhir-test
  "Integration tests for FHIR resource CRUD and JSON Patch operations
   using the mock store and a minimal ring app (no auth)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [us-core.capability.v8-0-1.Patient :as cap-patient]
            [us-core.capability.v8-0-1.Observation :as cap-observation]
            [us-core.capability.v8-0-1.Organization :as cap-organization]
            [us-core.capability.v8-0-1.Provenance :as cap-provenance]
            [us-core.capability.v8-0-1.Condition :as cap-condition]
            [server.core :as core]
            [server.routing :as routing]
            [server.middleware :as middleware]
            [server.fhir-coercion :as fhir-coercion]
            [ring.middleware.head :refer [wrap-head]]
            [jsonista.core :as json]
            [clojure.pprint]))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword}))

(defn- test-schemas
  "Minimal schemas for testing — Patient and Observation with basic interactions."
  []
  [(core/capability-schema->server-schema cap-observation/full-sch)
   (core/capability-schema->server-schema cap-patient/full-sch)
   (core/capability-schema->server-schema cap-organization/full-sch)
   (core/capability-schema->server-schema cap-provenance/full-sch)
   (core/capability-schema->server-schema cap-condition/full-sch)])

(defn- test-app
  "Build a ring app with the mock store and no auth middleware."
  [store]
  (ring/ring-handler
   (ring/router
    (routing/build-fhir-routes (test-schemas))
    {:conflicts nil
     :data {:coercion fhir-coercion/coercion
            :muuntaja core/muuntaja-instance
            :middleware [middleware/wrap-telemere-trace
                        wrap-head
                        middleware/wrap-request-id
                        middleware/wrap-cors
                        parameters/parameters-middleware
                        middleware/wrap-format-override
                        middleware/wrap-not-acceptable
                        middleware/wrap-unsupported-media-type
                        muuntaja/format-negotiate-middleware
                        middleware/wrap-fhir-response-headers
                        middleware/wrap-summary
                        middleware/wrap-elements
                        middleware/wrap-prefer
                        [middleware/wrap-pretty-print core/java-time-encode-mapper]
                        muuntaja/format-response-middleware
                        middleware/wrap-fhir-exceptions
                        muuntaja/format-request-middleware
                        rrc/coerce-request-middleware
                        rrc/coerce-response-middleware
                        rrc/coerce-exceptions-middleware
                        [core/wrap-fhir-store store]]}})
   (some-fn
    (ring/redirect-trailing-slash-handler {:method :strip})
    (ring/create-default-handler
     {:not-found (constantly {:status 404 :body {:error "Not Found"}})}))))

(defn- json-body
  "Encode a value to a JSON input stream for a ring request body."
  [data]
  (java.io.ByteArrayInputStream.
   (json/write-value-as-bytes data)))

(defn- parse-body
  "Parse a ring response body. Handles maps (already decoded) and InputStreams."
  [body]
  (cond
    (map? body) body
    (instance? java.io.InputStream body) (json/read-value body json-mapper)
    (bytes? body) (json/read-value body json-mapper)
    :else body))

(defn- request
  "Build a ring request map. Splits ?query-string out of the URI if present."
  ([method uri]
   (request method uri nil))
  ([method uri body]
   (let [[path qs] (clojure.string/split uri #"\?" 2)
         base (cond-> {:request-method method
                       :uri path
                       :headers {"accept" "application/json"}}
                qs (assoc :query-string qs))]
     (if body
       (cond-> (assoc base
                      :body (json-body body)
                      :headers {"content-type" "application/json"
                                "accept" "application/json"})
         qs (assoc :query-string qs))
       base))))

(defn- json-patch-request
  "Build a ring request with application/json-patch+json content type.
   Splits ?query-string out of the URI if present."
  [uri ops]
  (let [[path qs] (clojure.string/split uri #"\?" 2)]
    (cond-> {:request-method :patch
             :uri path
             :body (json-body ops)
             :headers {"content-type" "application/json-patch+json"
                       "accept" "application/json"}}
      qs (assoc :query-string qs))))

;; ---------------------------------------------------------------------------
;; CRUD tests
;; ---------------------------------------------------------------------------

(deftest create-resource-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        patient {:resourceType "Patient"
                 :name [{:family "Smith" :given ["John"]}]
                 :gender "male"}
        resp (app (request :post "/default/fhir/Patient" patient))
        body (parse-body (:body resp))]
    (testing "returns 201 with Location header including _history/vid"
      (is (= 201 (:status resp)))
      (is (string? (get-in resp [:headers "Location"])))
      (is (clojure.string/includes? (get-in resp [:headers "Location"]) "/_history/1")))
    (testing "response has id and meta"
      (is (string? (:id body)))
      (is (= "1" (get-in body [:meta :versionId]))))))

(deftest read-resource-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Doe"}]})
        resp (app (request :get "/default/fhir/Patient/pt-1"))
        body (parse-body (:body resp))]
    (testing "returns 200 with the resource"
      (is (= 200 (:status resp)))
      (is (= "pt-1" (:id body)))
      (is (= "Doe" (get-in body [:name 0 :family]))))))

(deftest read-not-found-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        resp (app (request :get "/default/fhir/Patient/nonexistent"))]
    (is (= 404 (:status resp)))))

(deftest update-resource-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Doe"}]})
        updated {:resourceType "Patient" :name [{:family "Doe-Updated"}] :gender "female"}
        resp (app (request :put "/default/fhir/Patient/pt-1" updated))
        body (parse-body (:body resp))]
    (testing "returns 200 with incremented version"
      (is (= 200 (:status resp)))
      (is (= "2" (get-in body [:meta :versionId])))
      (is (= "Doe-Updated" (get-in body [:name 0 :family]))))))

(deftest update-resource-id-mismatch-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Doe"}]})
        updated {:resourceType "Patient" :id "wrong-id" :name [{:family "Doe-Updated"}]}
        resp (app (request :put "/default/fhir/Patient/pt-1" updated))
        body (parse-body (:body resp))]
    (testing "returns 400 when body id does not match URL id"
      (is (= 400 (:status resp)))
      (is (= "OperationOutcome" (:resourceType body))))
    (testing "allows update when body id matches URL id"
      (let [matching {:resourceType "Patient" :id "pt-1" :name [{:family "Doe-v2"}]}
            resp2 (app (request :put "/default/fhir/Patient/pt-1" matching))]
        (is (= 200 (:status resp2)))))
    (testing "allows update when body has no id"
      (let [no-id {:resourceType "Patient" :name [{:family "Doe-v3"}]}
            resp3 (app (request :put "/default/fhir/Patient/pt-1" no-id))]
        (is (= 200 (:status resp3)))))))

(deftest update-as-create-test
  (let [store (mock/create-mock-store {})
        app (test-app store)]
    (testing "PUT to non-existent ID creates resource with 201"
      (let [resp (app (request :put "/default/fhir/Patient/new-pt"
                               {:resourceType "Patient" :name [{:family "Upsert"}]}))
            body (parse-body (:body resp))]
        (is (= 201 (:status resp)))
        (is (string? (get-in resp [:headers "Location"])))
        (is (= "new-pt" (:id body)))))
    (testing "PUT to existing ID updates with 200"
      (let [resp (app (request :put "/default/fhir/Patient/new-pt"
                               {:resourceType "Patient" :name [{:family "Updated"}]}))
            body (parse-body (:body resp))]
        (is (= 200 (:status resp)))
        (is (= "2" (get-in body [:meta :versionId])))))))

(deftest delete-resource-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Doe"}]})
        resp (app (request :delete "/default/fhir/Patient/pt-1"))]
    (testing "returns 204"
      (is (= 204 (:status resp))))
    (testing "subsequent read returns 410 Gone (not 404)"
      (is (= 410 (:status (app (request :get "/default/fhir/Patient/pt-1"))))))
    (testing "read of never-existed resource returns 404"
      (is (= 404 (:status (app (request :get "/default/fhir/Patient/never-existed"))))))))

(deftest search-resource-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Smith"}]})
        _ (db/create-resource store "default" :Patient "pt-2"
                              {:resourceType "Patient" :name [{:family "Jones"}]})
        resp (app (request :get "/default/fhir/Patient"))
        body (parse-body (:body resp))]
    (testing "returns a searchset Bundle"
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "searchset" (:type body))))
    (testing "contains both patients"
      (is (= 2 (count (:entry body)))))
    (testing "includes Bundle.total"
      (is (= 2 (:total body))))))

(deftest post-search-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Smith"}]})]
    (testing "POST _search with form-encoded params returns searchset"
      (let [resp (app {:request-method :post
                       :uri "/default/fhir/Patient/_search"
                       :headers {"content-type" "application/x-www-form-urlencoded"
                                 "accept" "application/json"}
                       :form-params {"family" "Smith"}})
            body (parse-body (:body resp))]
        (is (= 200 (:status resp)))
        (is (= "Bundle" (:resourceType body)))
        (is (= "searchset" (:type body)))))))

(deftest history-resource-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Doe"}]})
        _ (db/update-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Doe-v2"}]})
        resp (app (request :get "/default/fhir/Patient/pt-1/_history"))
        body (parse-body (:body resp))]
    (testing "returns history with both versions"
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "history" (:type body)))
      (is (= 2 (:total body)))
      (is (= 2 (count (:entry body)))))))

;; ---------------------------------------------------------------------------
;; JSON Patch tests (RFC 6902)
;; ---------------------------------------------------------------------------

(deftest patch-add-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe" :given ["Jane"]}]})
        ops [{:op "add" :path "/gender" :value "female"}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))
        body (parse-body (:body resp))]
    (testing "add operation sets a new field"
      (is (= 200 (:status resp)))
      (is (= "female" (:gender body)))
      (is (= "2" (get-in body [:meta :versionId]))))))

(deftest patch-replace-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe"}]
                               :gender "male"})
        ops [{:op "replace" :path "/gender" :value "female"}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))
        body (parse-body (:body resp))]
    (testing "replace changes an existing field"
      (is (= 200 (:status resp)))
      (is (= "female" (:gender body))))))

(deftest patch-remove-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe"}]
                               :gender "male"
                               :active true})
        ops [{:op "remove" :path "/active"}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))
        body (parse-body (:body resp))]
    (testing "remove deletes a field"
      (is (= 200 (:status resp)))
      (is (nil? (:active body)))
      (is (= "male" (:gender body))))))

(deftest patch-move-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Observation "obs-1"
                              {:resourceType "Observation"
                               :status "final"
                               :code {:coding [{:system "http://loinc.org"
                                                :code "15074-8"
                                                :display "Glucose [Moles/volume] in Blood"}]}
                               :note [{:text "first"} {:text "second"}]})
        ops [{:op "move" :from "/note/0" :path "/note/1"}]
        resp (app (json-patch-request "/default/fhir/Observation/obs-1" ops))
        body (parse-body (:body resp))]
    (testing "move rearranges array elements"
      (is (= 200 (:status resp)))
      (is (= "second" (get-in body [:note 0 :text])))
      (is (= "first" (get-in body [:note 1 :text]))))))

(deftest patch-copy-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe" :given ["Jane"]}]})
        ops [{:op "copy" :from "/name/0/family" :path "/name/0/text"}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))
        body (parse-body (:body resp))]
    (testing "copy duplicates a value to a new path"
      (is (= 200 (:status resp)))
      (is (= "Doe" (get-in body [:name 0 :text])))
      (is (= "Doe" (get-in body [:name 0 :family]))))))

(deftest patch-test-op-succeeds
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe"}]
                               :gender "male"})
        ops [{:op "test" :path "/gender" :value "male"}
             {:op "replace" :path "/gender" :value "female"}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))
        body (parse-body (:body resp))]
    (testing "test passes and subsequent replace is applied"
      (is (= 200 (:status resp)))
      (is (= "female" (:gender body))))))

(deftest patch-test-op-fails
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :gender "male"})
        ops [{:op "test" :path "/gender" :value "female"}
             {:op "replace" :path "/gender" :value "other"}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))]
    (testing "failed test aborts the patch and returns an error"
      (is (not= 200 (:status resp))))))

(deftest patch-multiple-ops-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe" :given ["Jane"]}]
                               :gender "male"
                               :active false})
        ops [{:op "replace" :path "/gender" :value "female"}
             {:op "replace" :path "/active" :value true}
             {:op "add" :path "/birthDate" :value "1990-01-01"}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))
        body (parse-body (:body resp))]
    (testing "multiple operations applied in order"
      (is (= 200 (:status resp)))
      (is (= "female" (:gender body)))
      (is (= true (:active body)))
      (is (= "1990-01-01" (:birthDate body))))))

(deftest patch-not-found-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        ops [{:op "add" :path "/gender" :value "female"}]
        resp (app (json-patch-request "/default/fhir/Patient/nonexistent" ops))
        body (parse-body (:body resp))]
    (testing "patch on nonexistent resource returns 404"
      (is (= 404 (:status resp)))
      (is (= "OperationOutcome" (:resourceType body))))))

(deftest patch-add-to-array-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe"}]})
        ops [{:op "add" :path "/name/-" :value {:family "Smith" :given ["Bob"]}}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))
        body (parse-body (:body resp))]
    (testing "add with /- appends to array"
      (is (= 200 (:status resp)))
      (is (= 2 (count (:name body))))
      (is (= "Smith" (get-in body [:name 1 :family]))))))

(deftest patch-nested-replace-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe" :given ["Jane"]}]})
        ops [{:op "replace" :path "/name/0/family" :value "Smith"}]
        resp (app (json-patch-request "/default/fhir/Patient/pt-1" ops))
        body (parse-body (:body resp))]
    (testing "replace works on nested paths"
      (is (= 200 (:status resp)))
      (is (= "Smith" (get-in body [:name 0 :family])))
      (is (= ["Jane"] (get-in body [:name 0 :given]))))))

;; ---------------------------------------------------------------------------
;; If-Match (version-aware update/patch/delete) tests
;; ---------------------------------------------------------------------------

(deftest if-match-update-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Doe"}]})]
    (testing "update succeeds with matching version"
      (let [resp (app (-> (request :put "/default/fhir/Patient/pt-1"
                                   {:resourceType "Patient" :name [{:family "Updated"}]})
                          (assoc-in [:headers "if-match"] "W/\"1\"")))]
        (is (= 200 (:status resp)))))
    (testing "update fails with version mismatch - returns 412"
      (let [resp (app (-> (request :put "/default/fhir/Patient/pt-1"
                                   {:resourceType "Patient" :name [{:family "Again"}]})
                          (assoc-in [:headers "if-match"] "W/\"99\"")))]
        (is (= 412 (:status resp)))
        (is (= "OperationOutcome" (:resourceType (parse-body (:body resp)))))))
    (testing "update without If-Match succeeds (optional)"
      (let [resp (app (request :put "/default/fhir/Patient/pt-1"
                               {:resourceType "Patient" :name [{:family "NoMatch"}]}))]
        (is (= 200 (:status resp)))))))

(deftest if-match-patch-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient"
                               :name [{:family "Doe"}]
                               :gender "male"})]
    (testing "patch succeeds with matching version"
      (let [resp (app (-> (json-patch-request "/default/fhir/Patient/pt-1"
                                              [{:op "replace" :path "/gender" :value "female"}])
                          (assoc-in [:headers "if-match"] "W/\"1\"")))]
        (is (= 200 (:status resp)))))
    (testing "patch fails with version mismatch - returns 412"
      (let [resp (app (-> (json-patch-request "/default/fhir/Patient/pt-1"
                                              [{:op "replace" :path "/gender" :value "other"}])
                          (assoc-in [:headers "if-match"] "W/\"99\"")))]
        (is (= 412 (:status resp)))
        (is (= "OperationOutcome" (:resourceType (parse-body (:body resp)))))))
    (testing "patch without If-Match succeeds (optional)"
      (let [resp (app (json-patch-request "/default/fhir/Patient/pt-1"
                                          [{:op "replace" :path "/gender" :value "male"}]))]
        (is (= 200 (:status resp)))))))

(deftest if-match-delete-test
  (let [store (mock/create-mock-store {})
        app (test-app store)]
    (testing "delete fails with version mismatch - returns 412"
      (let [_ (db/create-resource store "default" :Patient "pt-2"
                                  {:resourceType "Patient" :name [{:family "Doe"}]})
            resp (app (-> (request :delete "/default/fhir/Patient/pt-2")
                          (assoc-in [:headers "if-match"] "W/\"99\"")))]
        (is (= 412 (:status resp)))
        (is (= "OperationOutcome" (:resourceType (parse-body (:body resp)))))))
    (testing "delete succeeds with matching version"
      (let [_ (db/create-resource store "default" :Patient "pt-3"
                                  {:resourceType "Patient" :name [{:family "Smith"}]})
            resp (app (-> (request :delete "/default/fhir/Patient/pt-3")
                          (assoc-in [:headers "if-match"] "W/\"1\"")))]
        (is (= 204 (:status resp)))))
    (testing "delete without If-Match succeeds (optional)"
      (let [_ (db/create-resource store "default" :Patient "pt-4"
                                  {:resourceType "Patient" :name [{:family "Jones"}]})
            resp (app (request :delete "/default/fhir/Patient/pt-4"))]
        (is (= 204 (:status resp)))))))

;; ---------------------------------------------------------------------------
;; Conditional read (If-None-Match / If-Modified-Since) tests
;; ---------------------------------------------------------------------------

(deftest conditional-read-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-1"
                              {:resourceType "Patient" :name [{:family "Doe"}]})]
    (testing "If-None-Match with matching version returns 304"
      (let [resp (app (-> (request :get "/default/fhir/Patient/pt-1")
                          (assoc-in [:headers "if-none-match"] "W/\"1\"")))]
        (is (= 304 (:status resp)))
        (is (nil? (:body resp)))))
    (testing "If-None-Match with different version returns 200"
      (let [resp (app (-> (request :get "/default/fhir/Patient/pt-1")
                          (assoc-in [:headers "if-none-match"] "W/\"99\"")))]
        (is (= 200 (:status resp)))))
    (testing "no conditional headers returns 200"
      (is (= 200 (:status (app (request :get "/default/fhir/Patient/pt-1"))))))))

;; ---------------------------------------------------------------------------
;; Conditional create (If-None-Exist) tests
;; ---------------------------------------------------------------------------

(deftest conditional-create-no-match-test
  (testing "creates normally with If-None-Exist when no matches found"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          resp (app (-> (request :post "/default/fhir/Patient"
                                 {:resourceType "Patient" :name [{:family "New"}]})
                        (assoc-in [:headers "if-none-exist"] "family=NonExistent")))
          body (parse-body (:body resp))]
      (is (= 201 (:status resp)))
      (is (string? (get-in resp [:headers "Location"])))
      (is (string? (:id body))))))

(deftest conditional-create-one-match-test
  (testing "returns 200 with existing resource when exactly one match"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          ;; Create a patient directly in the store
          _ (db/create-resource store "default" :Patient "pt-exist"
                                {:resourceType "Patient" :name [{:family "Existing"}]})
          ;; Mock store search returns all resources, so with one patient we get one match
          resp (app (-> (request :post "/default/fhir/Patient"
                                 {:resourceType "Patient" :name [{:family "Duplicate"}]})
                        (assoc-in [:headers "if-none-exist"] "family=Existing")))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "pt-exist" (:id body))))))

(deftest conditional-create-multiple-matches-test
  (testing "returns 412 when multiple matches found"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          ;; Create two patients so search returns multiple
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Patient "pt-2"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          resp (app (-> (request :post "/default/fhir/Patient"
                                 {:resourceType "Patient" :name [{:family "Smith"}]})
                        (assoc-in [:headers "if-none-exist"] "family=Smith")))
          body (parse-body (:body resp))]
      (is (= 412 (:status resp)))
      (is (= "OperationOutcome" (:resourceType body))))))

(deftest conditional-create-without-header-test
  (testing "creates normally when no If-None-Exist header present"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          resp (app (request :post "/default/fhir/Patient"
                             {:resourceType "Patient" :name [{:family "Normal"}]}))]
      (is (= 201 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Conditional update (PUT /[type]?[search]) tests
;; ---------------------------------------------------------------------------

(deftest conditional-update-no-match-creates-test
  (testing "PUT /Patient?family=Nobody creates when no matches"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          resp (app (request :put "/default/fhir/Patient?family=Nobody"
                             {:resourceType "Patient" :name [{:family "Nobody"}]}))
          body (parse-body (:body resp))]
      (is (= 201 (:status resp)))
      (is (string? (:id body)))
      (is (string? (get-in resp [:headers "Location"]))))))

(deftest conditional-update-one-match-updates-test
  (testing "PUT /Patient?family=Smith updates when exactly one match"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          resp (app (request :put "/default/fhir/Patient?family=Smith"
                             {:resourceType "Patient" :name [{:family "Smith-Updated"}]}))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "2" (get-in body [:meta :versionId]))))))

(deftest conditional-update-multiple-matches-test
  (testing "PUT /Patient?family=Dup returns 412 when multiple matches"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Dup"}]})
          _ (db/create-resource store "default" :Patient "pt-2"
                                {:resourceType "Patient" :name [{:family "Dup"}]})
          resp (app (request :put "/default/fhir/Patient?family=Dup"
                             {:resourceType "Patient" :name [{:family "Dup-Updated"}]}))
          body (parse-body (:body resp))]
      (is (= 412 (:status resp)))
      (is (= "OperationOutcome" (:resourceType body))))))

(deftest conditional-update-id-mismatch-test
  (testing "PUT /Patient?family=Solo returns 400 when body id mismatches resolved id"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Solo"}]})
          resp (app (request :put "/default/fhir/Patient?family=Solo"
                             {:resourceType "Patient" :id "wrong-id" :name [{:family "Solo"}]}))
          body (parse-body (:body resp))]
      (is (= 400 (:status resp)))
      (is (= "OperationOutcome" (:resourceType body))))))

;; ---------------------------------------------------------------------------
;; Conditional delete (DELETE /[type]?[search]) tests
;; ---------------------------------------------------------------------------

(deftest conditional-delete-no-match-test
  (testing "DELETE /Patient?family=Ghost returns 204 when no matches"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          resp (app (request :delete "/default/fhir/Patient?family=Ghost"))]
      (is (= 204 (:status resp))))))

(deftest conditional-delete-one-match-test
  (testing "DELETE /Patient?family=Target deletes the single match"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Target"}]})
          resp (app (request :delete "/default/fhir/Patient?family=Target"))]
      (is (= 204 (:status resp)))
      ;; Verify the resource is gone
      (is (nil? (db/read-resource store "default" :Patient "pt-1"))))))

(deftest conditional-delete-multiple-matches-test
  (testing "DELETE /Patient?family=Multi returns 412 when multiple matches"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Multi"}]})
          _ (db/create-resource store "default" :Patient "pt-2"
                                {:resourceType "Patient" :name [{:family "Multi"}]})
          resp (app (request :delete "/default/fhir/Patient?family=Multi"))
          body (parse-body (:body resp))]
      (is (= 412 (:status resp)))
      (is (= "OperationOutcome" (:resourceType body))))))

;; ---------------------------------------------------------------------------
;; _sort parameter tests
;; ---------------------------------------------------------------------------

(deftest sort-search-test
  (let [store (mock/create-mock-store {})
        app (test-app store)
        _ (db/create-resource store "default" :Patient "pt-a"
                              {:resourceType "Patient" :name [{:family "Zebra"}] :birthDate "1990-01-01"})
        _ (db/create-resource store "default" :Patient "pt-b"
                              {:resourceType "Patient" :name [{:family "Alpha"}] :birthDate "2000-01-01"})]
    (testing "_sort by birthDate ascending"
      (let [resp (app (request :get "/default/fhir/Patient?_sort=birthDate"))
            body (parse-body (:body resp))
            ids (mapv #(get-in % [:resource :id]) (:entry body))]
        (is (= 200 (:status resp)))
        (is (= ["pt-a" "pt-b"] ids))))
    (testing "_sort by birthDate descending"
      (let [resp (app (request :get "/default/fhir/Patient?_sort=-birthDate"))
            body (parse-body (:body resp))
            ids (mapv #(get-in % [:resource :id]) (:entry body))]
        (is (= 200 (:status resp)))
        (is (= ["pt-b" "pt-a"] ids))))))

;; ---------------------------------------------------------------------------
;; Conditional patch (PATCH /[type]?[search]) tests
;; ---------------------------------------------------------------------------

(deftest conditional-patch-no-match-test
  (testing "PATCH /Patient?family=Nobody returns 404 when no matches"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          resp (app (json-patch-request "/default/fhir/Patient?family=Nobody"
                                        [{:op "add" :path "/gender" :value "male"}]))
          body (parse-body (:body resp))]
      (is (= 404 (:status resp)))
      (is (= "OperationOutcome" (:resourceType body))))))

(deftest conditional-patch-one-match-test
  (testing "PATCH /Patient?family=Sole patches the single match"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient"
                                 :name [{:family "Sole"}]
                                 :gender "male"})
          resp (app (json-patch-request "/default/fhir/Patient?family=Sole"
                                        [{:op "replace" :path "/gender" :value "female"}]))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "female" (:gender body))))))

(deftest conditional-patch-multiple-matches-test
  (testing "PATCH /Patient?family=Twins returns 412 when multiple matches"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Twins"}]})
          _ (db/create-resource store "default" :Patient "pt-2"
                                {:resourceType "Patient" :name [{:family "Twins"}]})
          resp (app (json-patch-request "/default/fhir/Patient?family=Twins"
                                        [{:op "add" :path "/gender" :value "male"}]))
          body (parse-body (:body resp))]
      (is (= 412 (:status resp)))
      (is (= "OperationOutcome" (:resourceType body))))))

;; ---------------------------------------------------------------------------
;; System-level search tests
;; ---------------------------------------------------------------------------

(deftest system-search-all-types-test
  (testing "GET /_search without _type returns resources from all types"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Observation "obs-1"
                                {:resourceType "Observation" :status "final"})
          resp (app (request :get "/default/fhir/_search"))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "searchset" (:type body)))
      (is (= 2 (:total body)))
      (is (= 2 (count (:entry body)))))))

(deftest system-search-with-type-filter-test
  (testing "GET /_search?_type=Patient returns only Patient resources"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Observation "obs-1"
                                {:resourceType "Observation" :status "final"})
          resp (app (request :get "/default/fhir/_search?_type=Patient"))
          body (parse-body (:body resp))
          resource-types (set (map #(get-in % [:resource :resourceType]) (:entry body)))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= 1 (:total body)))
      (is (= #{"Patient"} resource-types)))))

(deftest system-search-post-test
  (testing "POST /_search with _type in form params"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Observation "obs-1"
                                {:resourceType "Observation" :status "final"})
          resp (app {:request-method :post
                     :uri "/default/fhir/_search"
                     :headers {"content-type" "application/x-www-form-urlencoded"
                               "accept" "application/json"}
                     :form-params {"_type" "Observation"}})
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= 1 (:total body)))
      (is (= "Observation" (get-in body [:entry 0 :resource :resourceType]))))))

(deftest system-search-multiple-types-test
  (testing "GET /_search?_type=Patient,Observation returns both types"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Observation "obs-1"
                                {:resourceType "Observation" :status "final"})
          resp (app (request :get "/default/fhir/_search?_type=Patient,Observation"))
          body (parse-body (:body resp))
          resource-types (set (map #(get-in % [:resource :resourceType]) (:entry body)))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= 2 (:total body)))
      (is (= #{"Patient" "Observation"} resource-types)))))

(deftest system-search-empty-result-test
  (testing "GET /_search with no data returns empty Bundle"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          resp (app (request :get "/default/fhir/_search?_type=Patient"))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "searchset" (:type body)))
      (is (= 0 (:total body)))
      (is (empty? (:entry body))))))

;; ---------------------------------------------------------------------------
;; _include tests
;; ---------------------------------------------------------------------------

(deftest include-follows-reference-test
  (testing "_include=Patient:organization includes referenced Organization"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Organization "org-1"
                                {:resourceType "Organization" :name "Acme Hospital"})
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient"
                                 :name [{:family "Smith"}]
                                 :managingOrganization {:reference "Organization/org-1"}})
          resp (app (request :get "/default/fhir/Patient?_include=Patient:managingOrganization"))
          body (parse-body (:body resp))
          entries (:entry body)
          match-entries (filter #(= "match" (get-in % [:search :mode])) entries)
          include-entries (filter #(= "include" (get-in % [:search :mode])) entries)]
      (is (= 200 (:status resp)))
      (is (= 1 (count match-entries)) "should have 1 match entry")
      (is (= 1 (count include-entries)) "should have 1 include entry")
      (is (= "Organization" (get-in (first include-entries) [:resource :resourceType])))
      (is (= "org-1" (get-in (first include-entries) [:resource :id]))))))

(deftest include-no-reference-test
  (testing "_include with no matching references returns only matches"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient"
                                 :name [{:family "Smith"}]})
          resp (app (request :get "/default/fhir/Patient?_include=Patient:managingOrganization"))
          body (parse-body (:body resp))
          entries (:entry body)]
      (is (= 200 (:status resp)))
      (is (= 1 (count entries)))
      (is (= "match" (get-in (first entries) [:search :mode]))))))

(deftest include-does-not-affect-total-test
  (testing "_include entries do not change Bundle.total (total counts only matches)"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Organization "org-1"
                                {:resourceType "Organization" :name "Acme"})
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient"
                                 :name [{:family "Smith"}]
                                 :managingOrganization {:reference "Organization/org-1"}})
          resp (app (request :get "/default/fhir/Patient?_include=Patient:managingOrganization"))
          body (parse-body (:body resp))]
      (is (= 1 (:total body)) "total should count only primary results, not includes")
      (is (= 2 (count (:entry body))) "entries should contain match + include"))))

;; ---------------------------------------------------------------------------
;; _revinclude tests
;; ---------------------------------------------------------------------------

(deftest revinclude-provenance-target-test
  (testing "_revinclude=Provenance:target includes Provenance resources"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Provenance "prov-1"
                                {:resourceType "Provenance"
                                 :target [{:reference "Patient/pt-1"}]
                                 :recorded "2024-01-01T00:00:00Z"
                                 :agent [{:who {:reference "Practitioner/pract-1"}}]})
          resp (app (request :get "/default/fhir/Patient?_revinclude=Provenance:target"))
          body (parse-body (:body resp))
          entries (:entry body)
          match-entries (filter #(= "match" (get-in % [:search :mode])) entries)
          include-entries (filter #(= "include" (get-in % [:search :mode])) entries)]
      (is (= 200 (:status resp)))
      (is (= 1 (count match-entries)) "should have 1 match entry")
      (is (= 1 (count include-entries)) "should have 1 include entry")
      (is (= "Provenance" (get-in (first include-entries) [:resource :resourceType])))
      (is (= "prov-1" (get-in (first include-entries) [:resource :id]))))))

(deftest revinclude-no-matching-references-test
  (testing "_revinclude with no referencing resources returns only matches"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          resp (app (request :get "/default/fhir/Patient?_revinclude=Provenance:target"))
          body (parse-body (:body resp))
          entries (:entry body)]
      (is (= 200 (:status resp)))
      (is (= 1 (count entries)))
      (is (= "match" (get-in (first entries) [:search :mode]))))))

(deftest revinclude-does-not-affect-total-test
  (testing "_revinclude entries do not change Bundle.total"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Provenance "prov-1"
                                {:resourceType "Provenance"
                                 :target [{:reference "Patient/pt-1"}]
                                 :recorded "2024-01-01T00:00:00Z"
                                 :agent [{:who {:reference "Practitioner/pract-1"}}]})
          resp (app (request :get "/default/fhir/Patient?_revinclude=Provenance:target"))
          body (parse-body (:body resp))]
      (is (= 1 (:total body)) "total should count only primary results")
      (is (= 2 (count (:entry body))) "entries should contain match + revinclude"))))

;; ---------------------------------------------------------------------------
;; Instance-level history tests
;; ---------------------------------------------------------------------------

(deftest history-instance-returns-bundle-test
  (testing "GET /Patient/:id/_history returns a history Bundle"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Doe"}]})
          _ (db/update-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Doe-Updated"}]})
          resp (app (request :get "/default/fhir/Patient/pt-1/_history"))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "history" (:type body)))
      (is (= 2 (:total body)))
      (is (= 2 (count (:entry body))))
      (is (every? #(= "pt-1" (get-in % [:resource :id])) (:entry body))))))

;; ---------------------------------------------------------------------------
;; Type-level history tests
;; ---------------------------------------------------------------------------

(deftest history-type-returns-bundle-test
  (testing "GET /Patient/_history returns a history Bundle with all versions"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Patient "pt-2"
                                {:resourceType "Patient" :name [{:family "Jones"}]})
          _ (db/update-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith-Updated"}]})
          resp (app (request :get "/default/fhir/Patient/_history"))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "history" (:type body)))
      (is (= 3 (:total body)) "should have 3 versions total (2 for pt-1, 1 for pt-2)")
      (is (= 3 (count (:entry body)))))))

(deftest history-type-empty-test
  (testing "GET /Patient/_history with no data returns empty history Bundle"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          resp (app (request :get "/default/fhir/Patient/_history"))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "history" (:type body)))
      (is (= 0 (:total body)))
      (is (empty? (:entry body))))))

;; ---------------------------------------------------------------------------
;; System-level history tests
;; ---------------------------------------------------------------------------

(deftest system-history-returns-bundle-test
  (testing "GET /_history returns a history Bundle across all types"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          _ (db/create-resource store "default" :Patient "pt-1"
                                {:resourceType "Patient" :name [{:family "Smith"}]})
          _ (db/create-resource store "default" :Observation "obs-1"
                                {:resourceType "Observation" :status "final"})
          resp (app (request :get "/default/fhir/_history"))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "history" (:type body)))
      (is (>= (:total body) 2) "should include at least the Patient and Observation"))))

(deftest system-history-empty-test
  (testing "GET /_history with no data returns empty history Bundle"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          resp (app (request :get "/default/fhir/_history"))
          body (parse-body (:body resp))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (:resourceType body)))
      (is (= "history" (:type body)))
      (is (= 0 (:total body)))
      (is (empty? (:entry body))))))

(def ^:private patient-with-extensions
  {:resourceType "Patient"
   :id "p1"
   :meta {:profile ["http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"]}
   :identifier [{:system "urn:foo" :value "p1"}]
   :name [{:family "Smith" :given ["John"]}]
   :gender "male"
   :birthDate "1980-01-01"
   :extension [{:url "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race"
                :extension [{:url "ombCategory"
                             :valueCoding {:system "urn:oid:2.16.840.1.113883.6.238" :code "2106-3" :display "White"}}
                            {:url "text" :valueString "White"}]}
               {:url "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex"
                :valueCode "M"}]})

(def ^:private contained-condition
  "A US Core Condition (problems-health-concerns profile) with a
   condition-assertedDate extension. Embedded as :contained on a Patient."
  {:resourceType "Condition"
   :id "c1"
   :meta {:profile ["http://hl7.org/fhir/us/core/StructureDefinition/us-core-condition-problems-health-concerns"]}
   :clinicalStatus {:coding [{:system "http://terminology.hl7.org/CodeSystem/condition-clinical" :code "active"}]}
   :verificationStatus {:coding [{:system "http://terminology.hl7.org/CodeSystem/condition-ver-status" :code "confirmed"}]}
   :category [{:coding [{:system "http://hl7.org/fhir/us/core/CodeSystem/condition-category" :code "problem-list-item"}]}]
   :code {:coding [{:system "http://snomed.info/sct" :code "44054006" :display "Diabetes"}]}
   :subject {:reference "Patient/p1"}
   :extension [{:url "http://hl7.org/fhir/StructureDefinition/condition-assertedDate"
                :valueDateTime "2015-06-01"}]})

(deftest contained-resource-decode-individual-route-test
  (testing "Individual PUT with a :contained resource decodes the contained
            child's extensions and round-trips them through GET"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          patient (assoc patient-with-extensions :contained [contained-condition])
          put-resp (app (request :put "/default/fhir/Patient/p1" patient))
          stored (db/read-resource store "default" :Patient "p1")
          stored-condition (first (:contained stored))]
      (is (#{200 201} (:status put-resp)) (str "PUT failed: " (parse-body (:body put-resp))))
      (is (some? stored-condition))
      (is (= "Condition" (:resourceType stored-condition)))
      (testing "contained Condition's assertedDate extension was promoted on storage"
        (is (vector? (:assertedDate stored-condition))
            (str "expected promoted :assertedDate; got: " (pr-str stored-condition))))
      (let [get-resp (app (request :get "/default/fhir/Patient/p1"))
            get-body (parse-body (:body get-resp))
            get-contained (first (:contained get-body))]
        (is (= 200 (:status get-resp)))
        (testing "contained Condition is demoted on read"
          (is (nil? (:assertedDate get-contained))
              "promoted :assertedDate should not leak to wire format")
          (is (some #(= "http://hl7.org/fhir/StructureDefinition/condition-assertedDate" (:url %))
                    (:extension get-contained))
              "extension should be demoted back into the :extension array"))))))

(deftest contained-resource-decode-bundle-route-test
  (testing "Transaction Bundle PUT with a :contained resource also decodes
            the contained child's extensions"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          patient (assoc patient-with-extensions :contained [contained-condition])
          bundle {:resourceType "Bundle"
                  :type "transaction"
                  :entry [{:fullUrl "Patient/p1"
                           :resource patient
                           :request {:method "PUT" :url "Patient/p1"}}]}
          tx-resp (app (request :post "/default/fhir" bundle))
          stored (db/read-resource store "default" :Patient "p1")
          stored-condition (first (:contained stored))]
      (is (#{200 201} (:status tx-resp)) (str "tx failed: " (parse-body (:body tx-resp))))
      (when (some? stored)
        (is (some? stored-condition))
        (is (vector? (:assertedDate stored-condition))
            (str "expected promoted :assertedDate; got: " (pr-str stored-condition)))))))

(deftest individual-put-extension-roundtrip-test
  (testing "Individual PUT/GET round-trips Patient extensions in canonical FHIR shape"
    (let [store (mock/create-mock-store {})
          app (test-app store)
          put-resp (app (request :put "/default/fhir/Patient/p1" patient-with-extensions))
          get-resp (app (request :get "/default/fhir/Patient/p1"))
          get-body (parse-body (:body get-resp))]
      (is (#{200 201} (:status put-resp)))
      (is (= 200 (:status get-resp)))
      (is (vector? (:extension get-body))
          "GET response should expose extensions in the canonical :extension array")
      (is (some #(= "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex" (:url %))
                (:extension get-body))
          "birthsex (unpromoted) survives the round-trip")
      (is (some #(= "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race" (:url %))
                (:extension get-body))
          "race (promoted on storage) is demoted back to the extension array on read")
      (is (not (contains? get-body :race))
          "promoted :race field should not leak into the response"))))

;; ---------------------------------------------------------------------------
;; Root transaction endpoint trailing-slash normalization
;; ---------------------------------------------------------------------------

(deftest root-transaction-accepts-trailing-slash
  (testing "POST /{tenant}/fhir succeeds and /{tenant}/fhir/ redirects to it"
    (let [bundle {:resourceType "Bundle"
                  :type         "transaction"
                  :entry        [{:fullUrl "urn:uuid:patient-1"
                                  :resource {:resourceType "Patient"
                                             :name [{:family "Trailing" :given ["Slash"]}]}
                                  :request  {:method "POST" :url "Patient"}}]}
          store (mock/create-mock-store {})
          app   (test-app store)]
      (testing "no-slash form transacts directly"
        (let [resp (app (request :post "/default/fhir" bundle))
              body (parse-body (:body resp))]
          (is (= 200 (:status resp)))
          (is (= "Bundle" (:resourceType body)))
          (is (= "transaction-response" (:type body)))
          (is (= 1 (count (:entry body))))))
      (testing "trailing-slash form 308-redirects to the no-slash form"
        (let [resp (app (request :post "/default/fhir/" bundle))]
          (is (= 308 (:status resp)))
          (is (= "/default/fhir" (get-in resp [:headers "Location"]))))))))
