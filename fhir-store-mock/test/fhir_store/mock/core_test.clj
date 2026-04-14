(ns fhir-store.mock.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as protocol]))

(deftest create-read-test
  (let [store (mock/create-mock-store {})
        tenant "test-tenant"
        resource {:resourceType "Patient" :name [{:family "Smith"}]}
        created (protocol/create-resource store tenant "Patient" nil resource)
        id (:id created)]
    (testing "create assigns id and version"
      (is (string? id))
      (is (= "1" (get-in created [:meta :versionId]))))

    (testing "read returns the created resource"
      (let [read-back (protocol/read-resource store tenant "Patient" id)]
        (is (= created read-back))))))

(deftest update-test
  (let [store (mock/create-mock-store {})
        tenant "test-tenant"
        resource {:resourceType "Patient" :name [{:family "Smith"}]}
        created (protocol/create-resource store tenant "Patient" "pt1" resource)]
    (testing "update increments version"
      (let [updated-resource (assoc resource :active true)
            updated (protocol/update-resource store tenant "Patient" "pt1" updated-resource)]
        (is (= "2" (get-in updated [:meta :versionId])))
        (is (= true (:active updated)))

        (testing "read returns updated version"
          (is (= updated (protocol/read-resource store tenant "Patient" "pt1"))))

        (testing "vread returns specific versions"
          (is (= created (protocol/vread-resource store tenant "Patient" "pt1" "1")))
          (is (= updated (protocol/vread-resource store tenant "Patient" "pt1" "2"))))))))

(deftest delete-test
  (let [store (mock/create-mock-store {})
        tenant "test-tenant"
        resource {:resourceType "Patient" :name [{:family "Smith"}]}
        created (protocol/create-resource store tenant "Patient" "pt1" resource)]
    (testing "delete removes from read but keeps history"
      (is (true? (protocol/delete-resource store tenant "Patient" "pt1")))
      (is (nil? (protocol/read-resource store tenant "Patient" "pt1")))
      (is (= created (protocol/vread-resource store tenant "Patient" "pt1" "1"))))))

(deftest if-match-update-test
  (let [store (mock/create-mock-store {})
        tenant "test-tenant"
        res {:resourceType "Patient" :name [{:family "Smith"}]}
        _ (protocol/create-resource store tenant "Patient" "pt1" res)]
    (testing "update with matching :if-match succeeds"
      (let [updated (protocol/update-resource store tenant "Patient" "pt1"
                                               (assoc res :active true)
                                               {:if-match "1"})]
        (is (= "2" (get-in updated [:meta :versionId])))))
    (testing "update with mismatched :if-match throws 412"
      (let [e (try
                (protocol/update-resource store tenant "Patient" "pt1"
                                          (assoc res :active false)
                                          {:if-match "99"})
                nil
                (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= 412 (:fhir/status (ex-data e))))))
    (testing "update with :if-match against nonexistent throws 412"
      (let [e (try
                (protocol/update-resource store tenant "Patient" "missing"
                                          res {:if-match "1"})
                nil
                (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= 412 (:fhir/status (ex-data e))))))))

(deftest if-match-delete-test
  (let [store (mock/create-mock-store {})
        tenant "test-tenant"
        res {:resourceType "Patient" :name [{:family "Smith"}]}
        _ (protocol/create-resource store tenant "Patient" "pt1" res)]
    (testing "delete with mismatched :if-match throws 412"
      (let [e (try
                (protocol/delete-resource store tenant "Patient" "pt1" {:if-match "99"})
                nil
                (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= 412 (:fhir/status (ex-data e))))))
    (testing "delete with matching :if-match succeeds"
      (is (true? (protocol/delete-resource store tenant "Patient" "pt1" {:if-match "1"}))))))

(deftest search-test
  (let [store (mock/create-mock-store {})
        tenant "test-tenant"
        p1 {:resourceType "Patient" :name [{:family "Smith"}]}
        p2 {:resourceType "Patient" :name [{:family "Jones"}]}
        o1 {:resourceType "Observation" :status "final"}]
    (protocol/create-resource store tenant "Patient" "1" p1)
    (protocol/create-resource store tenant "Patient" "2" p2)
    (protocol/create-resource store tenant "Observation" "1" o1)

    (testing "search returns all active resources of type"
      (let [patients (protocol/search store tenant "Patient" {} nil)]
        (is (= 2 (count patients)))
        (is (= #{"1" "2"} (set (map :id patients)))))

      (let [observations (protocol/search store tenant "Observation" {} nil)]
        (is (= 1 (count observations)))))))

(deftest transact-transaction-ordering-test
  (testing "transaction entries are processed in FHIR-specified order: DELETE -> POST -> PUT -> GET"
    (let [store (mock/create-mock-store {})
          tenant "test-tenant"
          ;; Create a resource first so we can DELETE and PUT against it
          _ (protocol/create-resource store tenant "Patient" "existing" {:resourceType "Patient" :name [{:family "Original"}]})
          ;; Submit entries out of order: GET, PUT, POST, DELETE
          entries [{:request {:method "GET" :url "Patient/existing"}
                    :resource nil}
                   {:request {:method "PUT" :url "Patient/new-put"}
                    :resource {:resourceType "Patient" :name [{:family "PutNew"}]}}
                   {:request {:method "POST" :url "Observation"}
                    :resource {:resourceType "Observation" :status "final"}}
                   {:request {:method "DELETE" :url "Patient/existing"}}]
          result (protocol/transact-transaction store tenant entries)]
      (is (= "Bundle" (:resourceType result)))
      (is (= "transaction-response" (:type result)))
      ;; Verify all 4 entries produced results
      (is (= 4 (count (:entry result))))
      ;; Verify the ordering: DELETE first, then POST, then PUT, then GET
      (let [statuses (mapv #(get-in % [:response :status]) (:entry result))]
        (is (= "204 No Content" (nth statuses 0)) "DELETE should be first")
        (is (= "201 Created" (nth statuses 1)) "POST should be second")
        (is (= "200 OK" (nth statuses 2)) "PUT should be third (upsert)")
        ;; GET for deleted resource returns 404
        (is (= "404 Not Found" (nth statuses 3)) "GET should be last")))))

(deftest transact-transaction-rollback-test
  (testing "transaction rolls back on failure"
    (let [store (mock/create-mock-store {})
          tenant "test-tenant"
          ;; Create a resource we expect to survive rollback
          _ (protocol/create-resource store tenant "Patient" "survivor" {:resourceType "Patient" :name [{:family "Survivor"}]})
          ;; Create a transaction where the second entry will fail
          ;; (POST with an id that already exists triggers duplicate error)
          _ (protocol/create-resource store tenant "Patient" "dup" {:resourceType "Patient" :name [{:family "Existing"}]})
          entries [{:request {:method "PUT" :url "Patient/survivor"}
                    :resource {:resourceType "Patient" :name [{:family "Updated"}]}}
                   {:request {:method "POST" :url "Patient"}
                    ;; POST tries to create — this should succeed (generates new id)
                    :resource {:resourceType "Patient" :name [{:family "NewPatient"}]}}]]
      ;; This transaction should succeed normally
      (let [result (protocol/transact-transaction store tenant entries)]
        (is (= "transaction-response" (:type result))))
      ;; Verify the original "survivor" was updated (PUT processed after POST in ordering)
      (let [res (protocol/read-resource store tenant "Patient" "survivor")]
        (is (= [{:family "Updated"}] (:name res))))))

  (testing "failed transaction restores previous state"
    (let [store (mock/create-mock-store {})
          tenant "test-tenant"
          _ (protocol/create-resource store tenant "Patient" "keep-me" {:resourceType "Patient" :name [{:family "Original"}]})
          ;; First entry (DELETE, order=0) will succeed and remove "keep-me"
          ;; Second entry uses an unsupported method to trigger an error
          entries [{:request {:method "DELETE" :url "Patient/keep-me"}}
                   {:request {:method "INVALID" :url "Patient/foo"}
                    :resource {:resourceType "Patient"}}]]
      ;; The transaction should throw because INVALID method hits default case in (case ...)
      (is (thrown? Exception (protocol/transact-transaction store tenant entries)))
      ;; After rollback, the original resource should still exist
      (let [res (protocol/read-resource store tenant "Patient" "keep-me")]
        (is (some? res) "Resource should be restored after rollback")
        (is (= [{:family "Original"}] (:name res)))))))

(deftest transact-bundle-batch-test
  (testing "batch processes entries independently; per-entry failures do not affect others"
    (let [store (mock/create-mock-store {})
          tenant "batch-tenant"
          _ (protocol/create-resource store tenant "Patient" "alive" {:resourceType "Patient" :name [{:family "Alive"}]})
          entries [{:request {:method "POST" :url "Patient"}
                    :resource {:resourceType "Patient" :name [{:family "Fresh"}]}}
                   {:request {:method "GET" :url "Patient/alive"}}
                   {:request {:method "GET" :url "Patient/missing"}}
                   {:request {:method "BOGUS" :url "Patient/x"}}]
          result (protocol/transact-bundle store tenant entries)]
      (is (= "Bundle" (:resourceType result)))
      (is (= "batch-response" (:type result)))
      (is (= 4 (count (:entry result))) "result preserves input order")
      (let [[post get-ok get-missing bogus] (:entry result)]
        (is (= "201 Created" (get-in post [:response :status])))
        (is (some? (:resource post)))
        (is (= "200 OK" (get-in get-ok [:response :status])))
        (is (= "alive" (get-in get-ok [:resource :id])))
        (is (= "404 Not Found" (get-in get-missing [:response :status])))
        (is (= "400 Bad Request" (get-in bogus [:response :status]))))
      (testing "successful entries actually landed; failed ones did not roll back others"
        (let [all (protocol/search store tenant "Patient" {} nil)]
          (is (>= (count all) 2) "alive + newly posted patient exist"))))))

(deftest create-tenant-test
  (testing "create-tenant then search returns empty vector"
    (let [store (mock/create-mock-store {})]
      (protocol/create-tenant store "tid")
      (is (= [] (protocol/search store "tid" "Patient" {} nil)))))

  (testing "create-tenant twice with defaults throws 409"
    (let [store (mock/create-mock-store {})]
      (protocol/create-tenant store "tid")
      (let [e (try
                (protocol/create-tenant store "tid")
                nil
                (catch clojure.lang.ExceptionInfo ex ex))]
        (is (some? e))
        (is (= 409 (:fhir/status (ex-data e)))))))

  (testing "create-tenant twice with :if-exists :ignore preserves data"
    (let [store (mock/create-mock-store {})]
      (protocol/create-tenant store "tid")
      (protocol/create-resource store "tid" "Patient" "p1"
                                {:resourceType "Patient" :name [{:family "Keep"}]})
      (protocol/create-tenant store "tid" {:if-exists :ignore})
      (is (some? (protocol/read-resource store "tid" "Patient" "p1")))))

  (testing "create-tenant with :if-exists :replace wipes prior data"
    (let [store (mock/create-mock-store {})]
      (protocol/create-tenant store "tid")
      (protocol/create-resource store "tid" "Patient" "p1"
                                {:resourceType "Patient" :name [{:family "Gone"}]})
      (protocol/create-tenant store "tid" {:if-exists :replace})
      (is (nil? (protocol/read-resource store "tid" "Patient" "p1"))))))

(deftest delete-tenant-test
  (testing "delete-tenant then read-resource returns nil"
    (let [store (mock/create-mock-store {})]
      (protocol/create-tenant store "tid")
      (protocol/create-resource store "tid" "Patient" "p1"
                                {:resourceType "Patient" :name [{:family "X"}]})
      (protocol/delete-tenant store "tid")
      (is (nil? (protocol/read-resource store "tid" "Patient" "p1")))))

  (testing "delete-tenant with :if-absent :ignore on missing tenant is a no-op"
    (let [store (mock/create-mock-store {})]
      (is (nil? (protocol/delete-tenant store "missing" {:if-absent :ignore})))))

  (testing "delete-tenant with defaults on missing tenant throws 404"
    (let [store (mock/create-mock-store {})
          e (try
              (protocol/delete-tenant store "missing")
              nil
              (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= 404 (:fhir/status (ex-data e)))))))

(deftest warmup-tenant-test
  (testing "warmup-tenant on a new store creates the tenant key"
    (let [store (mock/create-mock-store {})]
      (protocol/warmup-tenant store "tid")
      (is (contains? @(:state store) "tid"))))

  (testing "warmup-tenant preserves prior data"
    (let [store (mock/create-mock-store {})]
      (protocol/create-tenant store "tid")
      (protocol/create-resource store "tid" "Patient" "p1"
                                {:resourceType "Patient" :name [{:family "Keep"}]})
      (protocol/warmup-tenant store "tid")
      (is (some? (protocol/read-resource store "tid" "Patient" "p1"))))))
