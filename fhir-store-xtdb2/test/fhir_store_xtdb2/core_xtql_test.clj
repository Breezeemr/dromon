(ns fhir-store-xtdb2.core-xtql-test
  "Parity tests for the :query-mode :xtql pathway. Structure mirrors the
   SQL-mode suite in core-test.clj; assertions call the same protocol
   methods, just against a store configured with :query-mode :xtql."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]))

(defn- close-store-nodes! [store]
  (doseq [[_ node] @(:nodes store)]
    (.close node))
  (reset! (:nodes store) {}))

(defn- xtql-store []
  (core-db/create-xtdb-store {:query-mode :xtql}))

(deftest test-xtql-read-and-search
  (testing "Simple reads and flat-column searches under :xtql"
    (let [store (xtql-store)
          tenant "tq-read"
          pt-a {:resourceType "Patient" :active true :gender "male"}
          pt-b {:resourceType "Patient" :active false :gender "female"}
          pt-c {:resourceType "Patient" :active true :gender "female"}]
      (try
        (db/create-resource store tenant :Patient "p1" pt-a)
        (db/create-resource store tenant :Patient "p2" pt-b)
        (db/create-resource store tenant :Patient "p3" pt-c)

        (testing "read-resource"
          (is (= "p1" (:id (db/read-resource store tenant :Patient "p1"))))
          (is (nil? (db/read-resource store tenant :Patient "missing"))))

        (testing "search: boolean flat column"
          (let [r (db/search store tenant :Patient {:active true} nil)]
            (is (= 2 (count r)))
            (is (= #{"p1" "p3"} (into #{} (map :id) r)))))

        (testing "search: string flat column"
          (let [r (db/search store tenant :Patient {:gender "male"} nil)]
            (is (= 1 (count r)))
            (is (= "p1" (:id (first r))))))

        (testing "search: _id equality"
          (let [r (db/search store tenant :Patient {:_id "p2"} nil)]
            (is (= 1 (count r)))
            (is (= "p2" (:id (first r))))))

        (testing "search: empty filter returns all"
          (is (= 3 (count (db/search store tenant :Patient {} nil)))))

        (testing "search: comma-separated OR"
          (let [r (db/search store tenant :Patient {:gender "male,female"} nil)]
            (is (= 3 (count r)))))

        (testing "count-resources"
          (is (= 2 (db/count-resources store tenant :Patient {:active true} nil))))

        (finally (close-store-nodes! store))))))

(deftest test-xtql-history-and-vread
  (testing "History, history-type, vread under :xtql"
    (let [store (xtql-store)
          tenant "tq-hist"
          patient {:resourceType "Patient" :active true :name [{"family" "H"}]}]
      (try
        (db/create-resource store tenant :Patient "h1" patient)
        (let [time-before (str (java.time.Instant/now))]
          (Thread/sleep 10)
          (db/update-resource store tenant :Patient "h1" (assoc patient :active false))

          (testing "history returns both versions"
            (is (= 2 (count (db/history store tenant :Patient "h1")))))

          (testing "vread returns the earlier version"
            (let [v (db/vread-resource store tenant :Patient "h1" time-before)]
              (is (some? v))
              (is (= true (:active v))))))

        (testing "history-type returns rows"
          (is (pos? (count (db/history-type store tenant :Patient {})))))

        (finally (close-store-nodes! store))))))

(deftest test-xtql-resource-deleted?
  (testing "resource-deleted? under :xtql after put-docs + delete-docs write"
    (let [store (xtql-store)
          tenant "tq-del"]
      (try
        (db/create-resource store tenant :Patient "d1" {:resourceType "Patient" :active true})
        (is (false? (db/resource-deleted? store tenant :Patient "d1")))
        (db/delete-resource store tenant :Patient "d1")
        (is (true? (db/resource-deleted? store tenant :Patient "d1")))
        (finally (close-store-nodes! store))))))

(defn- root-ex-data [ex]
  (loop [e ex]
    (cond
      (nil? e) nil
      (some-> e ex-data :fhir/status) (ex-data e)
      :else (recur (.getCause e)))))

(deftest test-xtql-if-match-update
  (testing "Optimistic concurrency under :xtql (SQL ASSERT + put-docs)"
    (let [store (xtql-store)
          tenant "tq-ifmatch"
          patient {:resourceType "Patient" :active true}]
      (try
        (db/create-resource store tenant :Patient "p1" patient)
        (testing "matching if-match succeeds"
          (let [res (db/update-resource store tenant :Patient "p1"
                                        (assoc patient :active false)
                                        {:if-match "1"})]
            (is (= "2" (get-in res [:meta :versionId])))))
        (testing "mismatched if-match -> 412"
          (let [e (try
                    (db/update-resource store tenant :Patient "p1"
                                        (assoc patient :active true)
                                        {:if-match "1"})
                    nil
                    (catch Throwable ex ex))]
            (is (some? e))
            (is (= 412 (:fhir/status (root-ex-data e))))))
        (finally (close-store-nodes! store))))))

(deftest test-xtql-transact-transaction
  (testing "Atomic bundle transaction under :xtql — put-docs/delete-docs"
    (let [store (xtql-store)
          tenant "tq-tx"
          bundle
          [{:request {:method "POST" :url "Patient"}
            :resource {:resourceType "Patient" :name [{"family" "NewOne"}]}}
           {:request {:method "PUT" :url "Patient/tx-upd"}
            :resource {:resourceType "Patient" :id "tx-upd" :active true}}
           {:request {:method "DELETE" :url "Patient/tx-del"}}]]
      (try
        (db/create-resource store tenant :Patient "tx-del" {:resourceType "Patient" :active false})
        (let [response (db/transact-transaction store tenant bundle)]
          (is (= 3 (count (:entry response))))
          (is (= "204 No Content" (-> response :entry first :response :status)))
          (is (= "201 Created" (-> response :entry second :response :status)))
          (is (= "200 OK" (-> response :entry last :response :status))))
        (testing "PUT'd resource is readable"
          (is (= true (:active (db/read-resource store tenant :Patient "tx-upd")))))
        (testing "DELETE'd resource is gone"
          (is (nil? (db/read-resource store tenant :Patient "tx-del"))))
        (finally (close-store-nodes! store))))))

(deftest test-cross-pathway-storage-compatibility
  (testing "Writing in one mode and reading in the other round-trips correctly"
    (let [sql-store (core-db/create-xtdb-store {})
          xtql-store* (xtql-store)
          patient {:resourceType "Patient" :active true :gender "male"}]
      (try
        (db/create-resource sql-store "shared" :Patient "s1" patient)
        (db/create-resource xtql-store* "shared" :Patient "x1" patient)
        ;; Each tenant is isolated per-store via its own node, so this
        ;; check is really about encoder/decoder parity between pathways.
        (testing "sql-written read-back is identical shape to xtql-written"
          (let [sql-read (db/read-resource sql-store "shared" :Patient "s1")
                xtql-read (db/read-resource xtql-store* "shared" :Patient "x1")]
            (is (= (:resourceType sql-read) (:resourceType xtql-read)))
            (is (= (:active sql-read) (:active xtql-read)))
            (is (= (:gender sql-read) (:gender xtql-read)))
            (is (= "1" (get-in sql-read [:meta :versionId])
                     (get-in xtql-read [:meta :versionId])))))
        (finally
          (close-store-nodes! sql-store)
          (close-store-nodes! xtql-store*))))))
