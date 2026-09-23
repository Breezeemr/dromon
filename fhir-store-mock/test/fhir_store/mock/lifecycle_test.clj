(ns fhir-store.mock.lifecycle-test
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.lifecycle :as lc]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as protocol]))

(def ^:private tenant "test-tenant")

(defn- recording-lifecycle [calls]
  (reify lc/IWriteLifecycle
    (prepare [_ write]
      (swap! calls conj [:prepare (:resource-type write) (:method write)])
      (assoc (:resource write) :language "prepared"))
    (tx-ops [_ write]
      (swap! calls conj [:tx-ops (:language (:resource write))])
      [[:probe (:id write)]])
    (after-commit [_ commit]
      (swap! calls conj [:after-commit
                         (mapv (juxt :resource-type :method) (:writes commit))
                         (mapv :tx-ops (:writes commit))]))))

(defn- after-commits [calls]
  (filterv #(= :after-commit (first %)) @calls))

(deftest create-and-update-store-the-prepared-body
  (let [calls (atom [])
        store (mock/create-mock-store {:resource/lifecycle (recording-lifecycle calls)})
        created (protocol/create-resource store tenant :Patient "p1" {:active true})]
    (is (= "prepared" (:language created)))
    (is (= "prepared" (:language (protocol/read-resource store tenant :Patient "p1"))))
    (is (= [[:prepare "Patient" :create]
            [:tx-ops "prepared"]
            [:after-commit [["Patient" :create]] [[[:probe "p1"]]]]]
           @calls)
        "tx-ops are recorded on the commit's writes, never applied")
    (reset! calls [])
    (let [updated (protocol/update-resource store tenant :Patient "p1" {:active false}
                                            {:if-match "1"})]
      (is (= "prepared" (:language updated)))
      (is (= "2" (get-in updated [:meta :versionId])))
      (is (= [:prepare :tx-ops :after-commit] (mapv first @calls))))))

(deftest refused-writes-fire-nothing
  (let [calls (atom [])
        store (mock/create-mock-store {:resource/lifecycle (recording-lifecycle calls)})]
    (protocol/create-resource store tenant :Patient "p1" {:active true})
    (reset! calls [])
    (testing "If-Match mismatch is refused before prepare"
      (is (thrown? Exception (protocol/update-resource store tenant :Patient "p1" {}
                                                       {:if-match "9"})))
      (is (= [] @calls)))
    (testing "a create conflict"
      (is (thrown? Exception (protocol/create-resource store tenant :Patient "p1" {})))
      (is (= [] @calls)))
    (testing "deletes never reach the lifecycle"
      (protocol/delete-resource store tenant :Patient "p1")
      (is (= [] @calls)))))

(deftest transaction-fires-once-and-only-on-success
  (let [calls (atom [])
        store (mock/create-mock-store {:resource/lifecycle (recording-lifecycle calls)})
        resp (protocol/transact-transaction
              store tenant
              [{:request {:method "POST" :url "Patient"} :resource {:active true}}
               {:request {:method "PUT" :url "Patient/p2"} :resource {:active true}}])]
    (is (= ["prepared" "prepared"] (mapv (comp :language :resource) (:entry resp))))
    (is (= [[:after-commit [["Patient" :create] ["Patient" :update]]]]
           (mapv #(subvec % 0 2) (after-commits calls))))
    (reset! calls [])
    (is (thrown? Exception
                 (protocol/transact-transaction
                  store tenant
                  [{:request {:method "PUT" :url "Patient/p3"} :resource {:active true}}
                   {:request {:method "INVALID" :url "Patient/x"}}])))
    (is (empty? (after-commits calls)) "a rolled-back Bundle fires nothing")
    (is (nil? (protocol/read-resource store tenant :Patient "p3")))))

(deftest batch-fires-per-entry
  (let [calls (atom [])
        store (mock/create-mock-store {:resource/lifecycle (recording-lifecycle calls)})]
    (protocol/transact-bundle store tenant
                              [{:request {:method "POST" :url "Patient"} :resource {:active true}}
                               {:request {:method "PUT" :url "Patient/b2"} :resource {:active true}}])
    (is (= 2 (count (after-commits calls))))))

(deftest no-lifecycle-is-unchanged
  (let [store (mock/create-mock-store {})
        created (protocol/create-resource store tenant :Patient "p1" {:active true})]
    (is (nil? (:resource/lifecycle store)))
    (is (nil? (:language created)))
    (is (= true (:active (protocol/read-resource store tenant :Patient "p1"))))))
