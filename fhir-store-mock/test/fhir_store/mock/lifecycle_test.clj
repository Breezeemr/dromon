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

(defn- constraint-lifecycle
  "A second concern on the same store: refuses an inactive Patient and adds
   its own op."
  [calls]
  (reify lc/IWriteLifecycle
    (prepare [_ write]
      (swap! calls conj [:constraint-prepare (:language (:resource write))])
      (when (false? (:active (:resource write)))
        (throw (ex-info "inactive refused" {:fhir/status 422})))
      (:resource write))
    (tx-ops [_ write]
      [[:constraint (:id write)]])
    (after-commit [_ commit]
      (swap! calls conj [:constraint-after-commit (count (:writes commit))]))))

(deftest a-vector-spec-composes-on-the-store
  (let [calls (atom [])
        store (mock/create-mock-store {:resource/lifecycle [(recording-lifecycle calls)
                                                            (constraint-lifecycle calls)]})]
    (is (= 2 (count (lc/members (:resource/lifecycle store)))))
    (testing "the second member sees the first member's prepared body, and both members' ops land on the write"
      (let [created (protocol/create-resource store tenant :Patient "p1" {:active true})]
        (is (= "prepared" (:language created)))
        (is (some #{[:constraint-prepare "prepared"]} @calls))
        (is (= [[[:probe "p1"] [:constraint "p1"]]]
               (last (first (after-commits calls)))))
        (is (some #{[:constraint-after-commit 1]} @calls))))
    (testing "either member can refuse, and a refused write fires no member"
      (reset! calls [])
      (is (thrown? clojure.lang.ExceptionInfo
                   (protocol/update-resource store tenant :Patient "p1" {:active false})))
      (is (true? (:active (protocol/read-resource store tenant :Patient "p1"))))
      (is (empty? (after-commits calls)))
      (is (not-any? #(= :constraint-after-commit (first %)) @calls)))
    (testing "a transaction fires each member once"
      (reset! calls [])
      (protocol/transact-transaction store tenant
                                     [{:request {:method "PUT" :url "Patient/p2"} :resource {:active true}}
                                      {:request {:method "PUT" :url "Patient/p3"} :resource {:active true}}])
      (is (= 1 (count (after-commits calls))))
      (is (= [[:constraint-after-commit 2]]
             (filterv #(= :constraint-after-commit (first %)) @calls))))))

(defn- opts-recording-lifecycle [seen]
  (reify lc/IWriteLifecycle
    (prepare [_ write]
      (swap! seen conj [(:method write) (:id write) (:opts write)])
      nil)
    (tx-ops [_ _write] nil)
    (after-commit [_ _commit] nil)))

(deftest the-write-map-carries-the-verbs-opts
  (let [seen (atom [])
        store (mock/create-mock-store {:resource/lifecycle (opts-recording-lifecycle seen)})
        origin {:write/origin :test}]
    (protocol/create-resource store tenant :Patient "p1" {:active true})
    (protocol/create-resource store tenant :Patient "p2" {:active true} origin)
    (protocol/update-resource store tenant :Patient "p2" {:active false}
                              (assoc origin :if-match "1"))
    (is (= [[:create "p1" nil]
            [:create "p2" origin]
            [:update "p2" (assoc origin :if-match "1")]]
           @seen))
    (testing "a Bundle's opts reach each entry, with the entry's own If-Match"
      (reset! seen [])
      (protocol/transact-transaction
       store tenant
       [{:request {:method "PUT" :url "Patient/p2" :ifMatch "W/\"2\""} :resource {:active true}}
        {:request {:method "PUT" :url "Patient/p3"} :resource {:active true}}]
       (assoc origin :if-match "ignored"))
      (is (= [[:update "p2" (assoc origin :if-match "W/\"2\"")]
              [:update "p3" origin]]
             @seen))
      (reset! seen [])
      (protocol/transact-bundle
       store tenant
       [{:request {:method "POST" :url "Patient"} :resource {:active true}}]
       origin)
      (is (= [origin] (mapv last @seen))))))
