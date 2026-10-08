(ns fhir-store.mock.host-rows-test
  "The write map's :db as the pre-write basis, protocol/read-in-basis, and
   host rows written in the same swap as the resource."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.lifecycle :as lc]
            [fhir-store.mock.core :as mock]
            [fhir-store.mock.test-setup :as ts]
            [fhir-store.protocol :as protocol]))

(def ^:private tenant "t")

(defn- status-of
  "The :fhir/status a call throws with. A Bundle's span rethrows a failure
   wrapped, so the status may sit on a cause."
  [thunk]
  (try (thunk) nil
       (catch Exception e
         (some #(:fhir/status (ex-data %)) (take-while some? (iterate ex-cause e))))))

(defn- basis-reading-lifecycle
  "Records, per write, what read-in-basis sees in the write map's :db for the
   resource being written and for `Patient/watched`, and keeps the :db."
  [seen]
  (reify lc/IWriteLifecycle
    (prepare [_ {:keys [store db resource-type id] :as write}]
      (swap! seen conj {:id id
                        :method (:method write)
                        :db db
                        :stored (protocol/read-in-basis store db resource-type id)
                        :watched (protocol/read-in-basis store db :Patient "watched")})
      nil)
    (tx-ops [_ _write] nil)
    (after-commit [_ _commit] nil)))

(deftest db-is-the-pre-write-state
  (let [seen (atom [])
        store (mock/create-mock-store {:resource/lifecycle (basis-reading-lifecycle seen)})]
    (is (satisfies? protocol/IBasisReadStore store))
    (testing "a create sees nothing stored"
      (protocol/create-resource store tenant :Patient "p1" {:active true})
      (is (nil? (:stored (peek @seen)))))
    (testing "an update sees the version it replaces"
      (protocol/update-resource store tenant :Patient "p1" {:active false})
      (let [{:keys [stored db]} (peek @seen)]
        (is (= "1" (get-in stored [:meta :versionId])))
        (is (true? (:active stored)))
        (testing "and the :db stays that state after the write landed"
          (is (= "2" (get-in (protocol/read-resource store tenant :Patient "p1") [:meta :versionId])))
          (is (= "1" (get-in (protocol/read-in-basis store db "Patient" "p1") [:meta :versionId]))))))
    (testing "a deleted resource is absent from a basis"
      (protocol/delete-resource store tenant :Patient "p1")
      (is (nil? (protocol/read-in-basis store (mock/tenant-db store tenant) :Patient "p1")))
      (protocol/create-resource store tenant :Patient "p1" {:active true})
      (is (nil? (:stored (peek @seen))) "re-creating a deleted id sees no live version"))))

(deftest db-inside-a-transaction-bundle-is-the-state-before-the-bundle
  (let [seen (atom [])
        store (mock/create-mock-store {:resource/lifecycle (basis-reading-lifecycle seen)})]
    (protocol/create-resource store tenant :Patient "p1" {:active true})
    (reset! seen [])
    (protocol/transact-transaction
     store tenant
     [{:request {:method "PUT" :url "Patient/watched"} :resource {:active true}}
      {:request {:method "PUT" :url "Patient/p1"} :resource {:active false}}
      {:request {:method "POST" :url "Patient"} :resource {:active true}}])
    (let [by-id (into {} (map (juxt :id identity)) @seen)
          posted (some #(when (= :create (:method %)) %) @seen)]
      (is (= 3 (count @seen)))
      (is (= [(:id posted) "watched" "p1"] (mapv :id @seen))
          "the POST runs first and the PUTs keep input order, so Patient/p1 runs after Patient/watched")
      (is (nil? (:stored posted)) "a POST sees nothing stored")
      (is (= "1" (get-in by-id ["p1" :stored :meta :versionId])) "a PUT sees the version it replaces")
      (is (nil? (:stored (by-id "watched"))) "a PUT that creates sees nothing stored")
      (is (nil? (:watched (by-id "p1")))
          "a later entry does not see an earlier entry's write, as on Datomic")
      (is (every? nil? (map :watched @seen)))
      (is (apply = (map :db @seen)) "every entry gets the same :db")
      (is (some? (protocol/read-in-basis store (mock/tenant-db store tenant) :Patient "watched"))
          "the earlier entry's write did land"))))

;; ---------------------------------------------------------------------------
;; Host rows
;; ---------------------------------------------------------------------------

(defn- row-lifecycle
  "Contributes the row ops a test passes as the write's :test/row-ops opt,
   after an op of another shape that the mock must leave unapplied. Refuses
   an inactive Patient in prepare."
  [commits]
  (reify lc/IWriteLifecycle
    (prepare [_ write]
      (when (false? (:active (:resource write)))
        (throw (ex-info "inactive refused" {:fhir/status 422})))
      nil)
    (tx-ops [_ write]
      (into [[:db/add "x" :other/op 1]] (get-in write [:opts :test/row-ops])))
    (after-commit [_ commit]
      (swap! commits conj commit))))

(defn- row-store []
  (let [commits (atom [])]
    [(mock/create-mock-store {:resource/lifecycle (row-lifecycle commits)}) commits]))

(defn- vid [store id]
  (get-in (protocol/read-resource store tenant :Patient id) [:meta :versionId]))

(deftest rows-land-with-the-resource-write
  (let [[store commits] (row-store)]
    (protocol/create-resource store tenant :Patient "p1" {:active true}
                              {:test/row-ops [[:mock.row/put :outbox "m1" {:status :pending :patient "p1"}]]})
    (is (= {"m1" {:status :pending :patient "p1"}} (mock/rows store tenant :outbox)))
    (is (= "1" (vid store "p1")))
    (testing "the commit carries every op, the unapplied ones too, and its :result holds the rows"
      (let [{:keys [writes result]} (peek @commits)]
        (is (= [[:db/add "x" :other/op 1]
                [:mock.row/put :outbox "m1" {:status :pending :patient "p1"}]]
               (:tx-ops (first writes))))
        (is (= {"m1" {:status :pending :patient "p1"}}
               (mock/rows-in-basis store (get result tenant) :outbox)))))
    (testing "a CAS that holds applies with the update"
      (protocol/update-resource store tenant :Patient "p1" {:active true}
                                {:test/row-ops [[:mock.row/cas :outbox "m1" :status :pending :sent]]})
      (is (= "2" (vid store "p1")))
      (is (= :sent (get-in (mock/rows store tenant :outbox) ["m1" :status]))))
    (testing "rows are per tenant"
      (is (= {} (mock/rows store "other" :outbox)))
      (is (= {} (mock/rows store tenant :inbox))))))

(deftest a-failed-cas-leaves-neither-the-resource-nor-the-rows
  (let [[store commits] (row-store)]
    (protocol/create-resource store tenant :Patient "p1" {:active true}
                              {:test/row-ops [[:mock.row/put :outbox "m1" {:status :pending}]]})
    (reset! commits [])
    (let [before (mock/tenant-db store tenant)]
      (is (= 409 (status-of
                  #(protocol/update-resource
                    store tenant :Patient "p1" {:active true :gender "female"}
                    {:test/row-ops [[:mock.row/put :outbox "m2" {:status :pending}]
                                    [:mock.row/cas :outbox "m1" :status :sent :done]]}))))
      (is (= "1" (vid store "p1")))
      (is (nil? (:gender (protocol/read-resource store tenant :Patient "p1"))))
      (is (= {"m1" {:status :pending}} (mock/rows store tenant :outbox))
          "the put before the failed CAS is not applied either")
      (is (= before (mock/tenant-db store tenant)))
      (is (empty? @commits) "after-commit does not fire"))
    (testing "the 409 names the row, never its content"
      (let [e (try (protocol/update-resource
                    store tenant :Patient "p1" {:active true}
                    {:test/row-ops [[:mock.row/cas :outbox "m1" :status :sent :done]]})
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= {:fhir/status 409 :fhir/code "conflict" :table :outbox :key "m1" :attr :status}
               (ex-data e)))))
    (testing "a CAS expecting nil matches an absent row and creates it"
      (protocol/update-resource store tenant :Patient "p1" {:active true}
                                {:test/row-ops [[:mock.row/cas :inbox "i1" :status nil :new]]})
      (is (= {"i1" {:status :new}} (mock/rows store tenant :inbox))))))

(deftest a-refused-write-leaves-no-rows
  (let [[store _] (row-store)]
    (protocol/create-resource store tenant :Patient "p1" {:active true})
    (testing "refused by the lifecycle's prepare"
      (is (= 422 (status-of
                  #(protocol/update-resource
                    store tenant :Patient "p1" {:active false}
                    {:test/row-ops [[:mock.row/put :outbox "m1" {:status :pending}]]}))))
      (is (= {} (mock/rows store tenant :outbox))))
    (testing "refused by If-Match"
      (is (= 412 (status-of
                  #(protocol/update-resource
                    store tenant :Patient "p1" {:active true}
                    {:if-match "9"
                     :test/row-ops [[:mock.row/put :outbox "m1" {:status :pending}]]}))))
      (is (= {} (mock/rows store tenant :outbox))))
    (testing "refused as a create conflict"
      (is (= 409 (status-of
                  #(protocol/create-resource
                    store tenant :Patient "p1" {:active true}
                    {:test/row-ops [[:mock.row/put :outbox "m1" {:status :pending}]]}))))
      (is (= {} (mock/rows store tenant :outbox))))
    (is (= "1" (vid store "p1")))))

(deftest a-transaction-bundle-is-atomic-with-its-rows
  (let [[store commits] (row-store)]
    (protocol/create-resource store tenant :Patient "p1" {:active true}
                              {:test/row-ops [[:mock.row/put :outbox "m1" {:status :pending}]]})
    (reset! commits [])
    (testing "a failed CAS in a later entry rolls back the earlier entries and their rows"
      (is (= 409 (status-of
                  #(protocol/transact-transaction
                    store tenant
                    [{:request {:method "POST" :url "Patient"} :resource {:active true}}
                     {:request {:method "PUT" :url "Patient/p1"} :resource {:active true}}]
                    {:test/row-ops [[:mock.row/cas :outbox "m1" :status :pending :sent]]}))))
      (is (= {"m1" {:status :pending}} (mock/rows store tenant :outbox))
          "the first entry's CAS applied, then the second's failed: neither stays")
      (is (= 1 (protocol/count-resources store tenant :Patient {} nil)))
      (is (= "1" (vid store "p1")))
      (is (empty? @commits)))
    (testing "a refused entry rolls back the rows of the entries before it"
      (is (= 422 (status-of
                  #(protocol/transact-transaction
                    store tenant
                    [{:request {:method "PUT" :url "Patient/p2"} :resource {:active true}}
                     {:request {:method "PUT" :url "Patient/p3"} :resource {:active false}}]
                    {:test/row-ops [[:mock.row/put :inbox "i1" {:status :new}]]}))))
      (is (= {} (mock/rows store tenant :inbox)))
      (is (nil? (protocol/read-resource store tenant :Patient "p2"))))
    (testing "a Bundle that lands keeps every entry's rows"
      (protocol/transact-transaction
       store tenant
       [{:request {:method "PUT" :url "Patient/p2"} :resource {:active true}}]
       {:test/row-ops [[:mock.row/put :inbox "i1" {:status :new}]]})
      (is (= {"i1" {:status :new}} (mock/rows store tenant :inbox))))))

(deftest transact-rows-outside-a-resource-write
  (let [store (mock/create-mock-store {})]
    (testing "a put to a tenant with no resources"
      (let [{:keys [db-before db-after]}
            (mock/transact-rows! store tenant [[:mock.row/put :outbox "m1" {:status :pending}]
                                               [:mock.row/put :outbox "m2" {:status :pending}]])]
        (is (= {} (mock/rows-in-basis store db-before :outbox)))
        (is (= 2 (count (mock/rows-in-basis store db-after :outbox))))))
    (testing "a worker claims a row with a CAS"
      (mock/transact-rows! store tenant [[:mock.row/cas :outbox "m1" :status :pending :claimed]])
      (is (= :claimed (get-in (mock/rows store tenant :outbox) ["m1" :status]))))
    (testing "a second claim of the same row is a 409, and the ops before it in the call do not apply"
      (is (= 409 (status-of
                  #(mock/transact-rows! store tenant
                                        [[:mock.row/cas :outbox "m2" :status :pending :claimed]
                                         [:mock.row/cas :outbox "m1" :status :pending :claimed]]))))
      (is (= :pending (get-in (mock/rows store tenant :outbox) ["m2" :status]))))
    (testing "put nil removes a row"
      (mock/transact-rows! store tenant [[:mock.row/put :outbox "m1" nil]])
      (is (= #{"m2"} (set (keys (mock/rows store tenant :outbox))))))
    (testing "no ops writes nothing"
      (let [{:keys [db-before db-after]} (mock/transact-rows! store tenant [])]
        (is (identical? db-before db-after))))
    (testing "an op of another shape, or a malformed row op, is a 500 and applies nothing"
      (is (= 500 (status-of #(mock/transact-rows! store tenant [[:mock.row/put :outbox "m3" {}]
                                                                 [:db/add "x" :a 1]]))))
      (is (= 500 (status-of #(mock/transact-rows! store tenant [[:mock.row/put :outbox "m3"]]))))
      (is (= 500 (status-of #(mock/transact-rows! store tenant [[:mock.row/put :outbox "m3" "not a map"]]))))
      (is (= 500 (status-of #(mock/transact-rows! store tenant [[:mock.row/swap :outbox "m3" {}]]))))
      (is (= #{"m2"} (set (keys (mock/rows store tenant :outbox))))))))

(deftest a-malformed-row-op-refuses-the-resource-write
  (let [[store _] (row-store)]
    (is (= 500 (status-of #(protocol/create-resource
                            store tenant :Patient "p1" {:active true}
                            {:test/row-ops [[:mock.row/cas :outbox "m1" :status]]}))))
    (is (nil? (protocol/read-resource store tenant :Patient "p1")))))

(deftest snapshot-and-restore-include-rows
  (let [[store _] (row-store)]
    (protocol/create-resource store tenant :Patient "p1" {:active true}
                              {:test/row-ops [[:mock.row/put :outbox "m1" {:status :pending}]]})
    (let [snap (ts/snapshot store)]
      (mock/transact-rows! store tenant [[:mock.row/cas :outbox "m1" :status :pending :sent]
                                         [:mock.row/put :inbox "i1" {:status :new}]])
      (ts/restore! store snap)
      (is (= {"m1" {:status :pending}} (mock/rows store tenant :outbox)))
      (is (= {} (mock/rows store tenant :inbox))))
    (testing "with-snapshot puts rows back however the body ends"
      (ts/with-snapshot store #(mock/transact-rows! store tenant [[:mock.row/put :inbox "i2" {}]]))
      (is (= {} (mock/rows store tenant :inbox))))))
