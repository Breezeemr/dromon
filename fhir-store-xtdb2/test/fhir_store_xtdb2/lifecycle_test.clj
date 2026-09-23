(ns fhir-store-xtdb2.lifecycle-test
  "The write lifecycle (fhir-store.lifecycle) as the xtdb2 store runs it, on
   both query modes: prepare's body is what is stored and returned, tx-ops
   land in the resource's own transaction, a refused or aborted write moves
   nothing, and after-commit fires once per committed transaction."
  (:require [clojure.test :refer [deftest is testing]]
            [xtdb.api :as xt]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.lifecycle :as lc]
            [fhir-store.protocol :as db]))

(def ^:private tenant "lifecycle-tenant")

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- root-ex-data [e]
  (some #(when (:fhir/status (ex-data %)) (ex-data %))
        (take-while some? (iterate ex-cause e))))

(defn- probe-op
  "One row per resource in a table the store itself never touches. It
   records the SUBMITTED body's :active, so a later write's row can be told
   from an earlier one's."
  [{:keys [resource-type id resource submitted]}]
  [:put-docs :lifecycle_probe {:xt/id            (str resource-type "/" id)
                               :language         (:language resource)
                               :submitted-active (:active submitted)}])

(defn- recording-lifecycle
  "Stamps every prepared body with :language \"prepared\", contributes
   `(ops-fn write)` as tx-ops (the probe row by default), and records every
   call in `calls`."
  ([calls] (recording-lifecycle calls (fn [write] [(probe-op write)])))
  ([calls ops-fn]
   (reify lc/IWriteLifecycle
     (prepare [_ write]
       (swap! calls conj [:prepare (:resource-type write) (:id write) (:method write)])
       (assoc (:resource write) :language "prepared"))
     (tx-ops [_ write]
       (swap! calls conj [:tx-ops (:resource-type write) (:id write)
                          (:language (:resource write)) (:language (:submitted write))])
       (ops-fn write))
     (after-commit [_ commit]
       (swap! calls conj [:after-commit (:tenant-id commit)
                          (mapv (juxt :resource-type :id :method) (:writes commit))
                          (mapv (comp :language :resource) (:writes commit))
                          (some? (:tx-id (:result commit)))
                          (every? :tx-ops (:writes commit))])))))

(defn- probe-rows [store]
  (let [node (:node (get @(:nodes store) tenant))]
    (->> (xt/q node "SELECT * FROM lifecycle_probe")
         (map (juxt :xt/id :language))
         (into (sorted-set)))))

(defn- probe-submitted-active
  "resource-key -> the submitted :active its probe row recorded."
  [store]
  (let [node (:node (get @(:nodes store) tenant))]
    (into {}
          (map (juxt :xt/id :submitted-active))
          (xt/q node "SELECT * FROM lifecycle_probe"))))

(defn- phases [calls]
  (mapv first @calls))

(defn- after-commits [calls]
  (filterv #(= :after-commit (first %)) @calls))

(defmacro ^:private each-mode
  "Run `body` once per query mode with `store` bound to a fresh store built
   from `opts` plus that mode."
  [[store opts] & body]
  `(doseq [mode# [:sql :xtql]]
     (testing (str "query-mode " mode#)
       (let [~store (core-db/create-xtdb-store (assoc ~opts :query-mode mode#))]
         (try
           ~@body
           (finally
             (close-store-nodes! ~store)))))))

(deftest resolves-once-onto-the-record
  (let [calls (atom [])
        lifecycle (recording-lifecycle calls)]
    (testing "a value is kept as is"
      (let [store (core-db/create-xtdb-store {:resource/lifecycle lifecycle})]
        (is (identical? lifecycle (:resource/lifecycle store)))))
    (testing "a constructor fn is called with the store config"
      (let [seen (atom nil)
            store (core-db/create-xtdb-store
                   {:query-mode :xtql
                    :resource/lifecycle (fn [opts] (reset! seen opts) lifecycle)})]
        (is (identical? lifecycle (:resource/lifecycle store)))
        (is (= :xtql (:query-mode @seen)))))
    (testing "absent means nil"
      (is (nil? (:resource/lifecycle (core-db/create-xtdb-store {})))))))

(deftest prepared-body-is-stored-and-returned
  (each-mode [store {:resource/lifecycle (recording-lifecycle (atom []))}]
    (let [created (db/create-resource store tenant :Patient "p1" {:active true})]
      (is (= "prepared" (:language created)) "create returns the prepared body")
      (is (= "prepared" (:language (db/read-resource store tenant :Patient "p1")))
          "create stores the prepared body"))
    (let [updated (db/update-resource store tenant :Patient "p1" {:active false})]
      (is (= "prepared" (:language updated)))
      (is (= "2" (get-in updated [:meta :versionId])))
      (let [stored (db/read-resource store tenant :Patient "p1")]
        (is (= "prepared" (:language stored)))
        (is (= false (:active stored)))))))

(deftest ordering-and-write-map
  (each-mode [store {}]
    (let [calls (atom [])
          store (assoc store :resource/lifecycle (recording-lifecycle calls))]
      (db/create-resource store tenant :Patient "p1" {:active true})
      (is (= [[:prepare "Patient" "p1" :create]
              ;; tx-ops sees the PREPARED body and the submitted one beside it
              [:tx-ops "Patient" "p1" "prepared" nil]
              [:after-commit tenant [["Patient" "p1" :create]] ["prepared"] true true]]
             @calls))
      (reset! calls [])
      (db/update-resource store tenant :Patient "p1" {:active false} {:if-match "1"})
      (is (= [:prepare :tx-ops :after-commit] (phases calls)))
      (is (= [["Patient" "p1" :update]] (nth (last @calls) 2))))))

(deftest tx-ops-commit-in-the-same-transaction
  (each-mode [store {:resource/lifecycle (recording-lifecycle (atom []))}]
    (db/create-resource store tenant :Patient "p1" {:active true})
    (db/update-resource store tenant :Observation "o1" {:status "final"})
    (is (= #{["Observation/o1" "prepared"] ["Patient/p1" "prepared"]}
           (probe-rows store)))))

(deftest refused-writes-move-nothing
  (each-mode [store {}]
    (let [calls (atom [])
          store (assoc store :resource/lifecycle (recording-lifecycle calls))]
      (db/create-resource store tenant :Patient "p1" {:active true})
      (reset! calls [])

      (testing "an If-Match mismatch is refused before prepare"
        (let [e (try (db/update-resource store tenant :Patient "p1" {:active false}
                                         {:if-match "7"})
                     nil
                     (catch Exception e e))]
          (is (= 412 (:fhir/status (root-ex-data e))))
          (is (= [] @calls))
          (is (= "1" (get-in (db/read-resource store tenant :Patient "p1")
                             [:meta :versionId])))))

      (testing "a create conflict runs no tx-ops write and no after-commit"
        (let [e (try (db/create-resource store tenant :Patient "p1" {:active false})
                     nil
                     (catch Exception e e))]
          (is (= 409 (:fhir/status (root-ex-data e))))
          (is (not-any? #{:after-commit} (phases calls)))
          (is (= true (:active (db/read-resource store tenant :Patient "p1"))))
          (is (= #{["Patient/p1" "prepared"]} (probe-rows store)))
          (is (= {"Patient/p1" true} (probe-submitted-active store))
              "the row is the first create's; the refused one wrote none")))

      (testing "prepare refusing the write sends nothing"
        (let [refusing (reify lc/IWriteLifecycle
                         (prepare [_ _]
                           (throw (ex-info "refused" {:fhir/status 422})))
                         (tx-ops [_ write] [(probe-op write)])
                         (after-commit [_ _] (swap! calls conj [:after-commit])))
              store (assoc store :resource/lifecycle refusing)
              e (try (db/create-resource store tenant :Patient "p2" {:active true})
                     nil
                     (catch Exception e e))]
          (is (= 422 (:fhir/status (root-ex-data e))))
          (is (nil? (db/read-resource store tenant :Patient "p2")))
          (is (not-any? #{:after-commit} (phases calls))))))))

(deftest failing-tx-op-aborts-the-resource-write
  (each-mode [store {}]
    (let [calls (atom [])
          failing (fn [write] [(probe-op write) [:sql "ASSERT 1 = 2" []]])
          store (assoc store :resource/lifecycle (recording-lifecycle calls failing))]
      ;; The XTQL read path refuses a table that was never written, so the
      ;; Patient table must exist before "nothing was stored" can be read.
      (db/create-resource (dissoc store :resource/lifecycle) tenant :Patient "seed"
                          {:active true})
      (testing "create"
        (let [e (try (db/create-resource store tenant :Patient "p1" {:active true})
                     nil
                     (catch Exception e e))]
          (is (some? e))
          (is (not= 409 (:fhir/status (root-ex-data e)))
              "a lifecycle op failure is not reported as the resource already existing")
          (is (nil? (db/read-resource store tenant :Patient "p1")))
          (is (empty? (probe-rows store)))
          (is (empty? (after-commits calls)))))

      (testing "update"
        (let [plain (dissoc store :resource/lifecycle)]
          (db/create-resource plain tenant :Patient "p2" {:active true}))
        (let [e (try (db/update-resource store tenant :Patient "p2" {:active false}
                                         {:if-match "1"})
                     nil
                     (catch Exception e e))]
          (is (some? e))
          (is (not= 412 (:fhir/status (root-ex-data e))))
          (let [stored (db/read-resource store tenant :Patient "p2")]
            (is (= "1" (get-in stored [:meta :versionId])))
            (is (= true (:active stored))))
          (is (empty? (after-commits calls)))))

      (testing "transaction"
        (let [e (try (db/transact-transaction
                      store tenant
                      [{:request {:method "PUT" :url "Patient/p3"} :resource {:active true}}])
                     nil
                     (catch Exception e e))]
          (is (some? e))
          (is (nil? (db/read-resource store tenant :Patient "p3")))
          (is (empty? (after-commits calls))))))))

(deftest transaction-bundle
  (each-mode [store {}]
    (let [calls (atom [])
          store (assoc store :resource/lifecycle (recording-lifecycle calls))]
      (db/create-resource (dissoc store :resource/lifecycle) tenant :Patient "gone"
                          {:active true})
      (let [resp (db/transact-transaction
                  store tenant
                  [{:fullUrl "urn:uuid:11111111-1111-1111-1111-111111111111"
                    :request {:method "POST" :url "Patient"}
                    :resource {:active true}}
                   {:request {:method "PUT" :url "Observation/o1"}
                    :resource {:status "final"
                               :subject {:reference "urn:uuid:11111111-1111-1111-1111-111111111111"}}}
                   {:request {:method "DELETE" :url "Patient/gone"}}])
            pid "11111111-1111-1111-1111-111111111111"
            [ac & more] (after-commits calls)]
        (testing "the response echoes the prepared bodies"
          (is (= ["prepared" "prepared"]
                 (keep (comp :language :resource) (:entry resp)))))
        (testing "the stored bodies are the prepared ones, urn references resolved"
          (is (= "prepared" (:language (db/read-resource store tenant :Patient pid))))
          (let [obs (db/read-resource store tenant :Observation "o1")]
            (is (= "prepared" (:language obs)))
            (is (= (str "Patient/" pid) (get-in obs [:subject :reference])))))
        (testing "every entry's tx-ops committed with the bundle"
          (is (= #{["Observation/o1" "prepared"] [(str "Patient/" pid) "prepared"]}
                 (probe-rows store))))
        (testing "after-commit fired once, with every write and no delete"
          (is (nil? more))
          (is (= [["Patient" pid :create] ["Observation" "o1" :update]] (nth ac 2)))
          (is (= [true true] (subvec ac 4))))))))

(deftest transaction-put-if-match-is-checked-before-the-lifecycle
  (each-mode [store {}]
    (let [calls (atom [])
          store (assoc store :resource/lifecycle (recording-lifecycle calls))
          put (fn [id if-match active]
                {:request (cond-> {:method "PUT" :url (str "Patient/" id)}
                            if-match (assoc :ifMatch if-match))
                 :resource {:active active}})]
      (db/create-resource store tenant :Patient "g1" {:active true})
      (db/update-resource store tenant :Patient "g1" {:active true})
      (reset! calls [])

      (testing "a stale ifMatch is refused with 412 before prepare"
        (let [e (try (db/transact-transaction store tenant [(put "g1" "W/\"1\"" false)])
                     nil
                     (catch Exception e e))]
          (is (= 412 (:fhir/status (root-ex-data e))))
          (is (= [] @calls))
          (is (= "2" (get-in (db/read-resource store tenant :Patient "g1")
                             [:meta :versionId])))
          (is (= {"Patient/g1" true} (probe-submitted-active store)))))

      (testing "an ifMatch on a missing resource is refused with 412"
        (let [e (try (db/transact-transaction store tenant [(put "absent" "W/\"1\"" true)])
                     nil
                     (catch Exception e e))]
          (is (= 412 (:fhir/status (root-ex-data e))))
          (is (= [] @calls))
          (is (nil? (db/read-resource store tenant :Patient "absent")))))

      (testing "a version that moves after the check fails the ASSERT with 412"
        (let [racer (dissoc store :resource/lifecycle)
              racing (recording-lifecycle
                      calls
                      (fn [write]
                        (db/update-resource racer tenant :Patient "g1" {:active true})
                        [(probe-op write)]))
              e (try (db/transact-transaction (assoc store :resource/lifecycle racing)
                                              tenant [(put "g1" "W/\"2\"" false)])
                     nil
                     (catch Exception e e))]
          (is (= 412 (:fhir/status (root-ex-data e))))
          (is (not-any? #{:after-commit} (phases calls)))
          (is (= true (:active (db/read-resource store tenant :Patient "g1"))))
          (is (= {"Patient/g1" true} (probe-submitted-active store)))))
      (reset! calls [])

      (testing "the current ifMatch, and *, write"
        (let [current (get-in (db/read-resource store tenant :Patient "g1") [:meta :versionId])
              resp (db/transact-transaction store tenant [(put "g1" (str "W/\"" current "\"") false)])
              resp* (db/transact-transaction store tenant [(put "g1" "*" true)])]
          (is (= "200 OK" (get-in resp [:entry 0 :response :status])))
          (is (= "200 OK" (get-in resp* [:entry 0 :response :status])))
          (is (= 2 (count (after-commits calls))))
          (is (= true (:active (db/read-resource store tenant :Patient "g1")))))))))

(deftest batch-bundle-fires-per-entry
  (each-mode [store {}]
    (let [calls (atom [])
          store (assoc store :resource/lifecycle (recording-lifecycle calls))]
      (db/transact-bundle store tenant
                          [{:request {:method "POST" :url "Patient"} :resource {:active true}}
                           {:request {:method "PUT" :url "Patient/b2"} :resource {:active true}}])
      (is (= 2 (count (after-commits calls))) "once per entry, never twice for one")
      (is (every? #(= 1 (count (nth % 2))) (after-commits calls))))))

(deftest deletes-skip-the-lifecycle
  (each-mode [store {}]
    (let [calls (atom [])]
      (db/create-resource store tenant :Patient "p1" {:active true})
      (db/delete-resource (assoc store :resource/lifecycle (recording-lifecycle calls))
                          tenant :Patient "p1")
      (is (= [] @calls)))))

(deftest after-commit-failure-is-contained
  (each-mode [store {}]
    (let [store (assoc store :resource/lifecycle
                       (reify lc/IWriteLifecycle
                         (prepare [_ write] (:resource write))
                         (tx-ops [_ _] nil)
                         (after-commit [_ _] (throw (ex-info "host bug" {})))))
          created (db/create-resource store tenant :Patient "p1" {:active true})]
      (is (= "p1" (:id created)))
      (is (some? (db/read-resource store tenant :Patient "p1"))))))

(deftest no-lifecycle-is-unchanged
  (each-mode [store {}]
    (let [created (db/create-resource store tenant :Patient "p1" {:active true})
          updated (db/update-resource store tenant :Patient "p1" {:active false})
          resp (db/transact-transaction
                store tenant
                [{:request {:method "PUT" :url "Patient/p2"} :resource {:active true}}])]
      (is (= {:active true :id "p1" :meta {:versionId "1"}} created))
      (is (= {:active false :id "p1" :meta {:versionId "2"}} updated))
      (is (= true (get-in resp [:entry 0 :resource :active])))
      (is (nil? (get-in resp [:entry 0 :resource :language])))
      (is (= false (:active (db/read-resource store tenant :Patient "p1")))))))
