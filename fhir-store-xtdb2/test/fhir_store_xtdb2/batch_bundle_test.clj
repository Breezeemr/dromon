(ns fhir-store-xtdb2.batch-bundle-test
  "Batch Bundles share transactions: consecutive writes commit together, and
   an entry that fails inside a group runs alone afterwards, so each entry's
   response is the one its own verb gives."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [xtdb.api :as xt]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.lifecycle :as lc]
            [fhir-store.protocol :as db]))

(def ^:private tenant "batch-tenant")

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defmacro ^:private each-mode
  [[store opts] & body]
  `(doseq [mode# [:sql :xtql]]
     (testing (str "query-mode " mode#)
       (let [~store (core-db/create-xtdb-store (assoc ~opts :query-mode mode#))]
         (try
           ~@body
           (finally
             (close-store-nodes! ~store)))))))

(defmacro ^:private counting-txs
  "Runs `body` with every xt/execute-tx counted into `n`, an atom. One arity
   calls another through the var, so only the outermost call counts."
  [n & body]
  `(let [orig# xt/execute-tx
         depth# (atom 0)]
     (with-redefs [xt/execute-tx (fn [& args#]
                                   (when (zero? @depth#) (swap! ~n inc))
                                   (swap! depth# inc)
                                   (try (apply orig# args#)
                                        (finally (swap! depth# dec))))]
       ~@body)))

(defn- post [active] {:request {:method "POST" :url "Patient"} :resource {:active active}})
(defn- put
  ([id active] {:request {:method "PUT" :url (str "Patient/" id)} :resource {:active active}})
  ([id active if-match] (assoc-in (put id active) [:request :ifMatch] if-match)))
(defn- delete [id] {:request {:method "DELETE" :url (str "Patient/" id)}})
(defn- get-entry [id] {:request {:method "GET" :url (str "Patient/" id)}})

(defn- statuses [res] (mapv #(get-in % [:response :status]) (:entry res)))

(defn- etag [vid] (str "W/\"" vid "\""))

(deftest writes-share-one-transaction
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "gone" {:active true})
    (let [n (atom 0)
          entries (-> (mapv (fn [_] (post true)) (range 20))
                      (into (map #(put (str "p" %) true)) (range 5))
                      (conj (delete "gone")))
          res (counting-txs n (db/transact-bundle store tenant entries))]
      (is (= 1 @n) "one transaction for the whole batch")
      (is (= (concat (repeat 20 "201 Created") (repeat 5 "200 OK") ["204 No Content"])
             (statuses res)))
      (testing "each response is the committed version, as a read answers it"
        (doseq [{:keys [resource response]} (take 25 (:entry res))]
          (let [read (db/read-resource store tenant :Patient (:id resource))]
            (is (= (get-in read [:meta :versionId]) (get-in resource [:meta :versionId])))
            (is (= (etag (get-in read [:meta :versionId])) (:etag response)))
            (is (= (get-in read [:meta :lastUpdated]) (:lastModified response))))))
      (is (str/ends-with? (get-in res [:entry 0 :response :location])
                          (str "/_history/" (get-in res [:entry 0 :resource :meta :versionId]))))
      (is (nil? (db/read-resource store tenant :Patient "gone"))))))

(deftest groups-are-bounded
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "seed" {:active true})
    (let [n (atom 0)]
      (with-redefs-fn {#'core-db/batch-group-max-entries 3}
        #(counting-txs n (db/transact-bundle store tenant (mapv (fn [_] (post true)) (range 7)))))
      (is (= 3 @n)))))

(deftest one-resource-twice-is-two-versions
  (each-mode [store {}]
    (let [res (db/transact-bundle store tenant [(put "twice" true) (put "twice" false)])
          [v1 v2] (map #(get-in % [:resource :meta :versionId]) (:entry res))]
      (is (= ["200 OK" "200 OK"] (statuses res)))
      (is (not= v1 v2))
      (is (= true (:active (db/vread-resource store tenant :Patient "twice" v1))))
      (is (= false (:active (db/read-resource store tenant :Patient "twice")))))))

(deftest a-get-sees-the-entries-before-it
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "seed" {:active true})
    (let [res (db/transact-bundle store tenant
                                  [(get-entry "g1")
                                   (put "g1" true)
                                   (get-entry "g1")
                                   (put "g1" false)
                                   (get-entry "seed")])]
      (is (= ["404 Not Found" "200 OK" "200 OK" "200 OK" "200 OK"] (statuses res)))
      (is (= true (get-in res [:entry 2 :resource :active]))))))

(deftest a-stale-if-match-fails-only-its-entry
  (testing "on a pool of one connection, which the group gives back before the entry runs alone"
    (each-mode [store {:pool-opts {:max-size 1 :min-idle 1}}]
      (let [v1 (get-in (db/create-resource store tenant :Patient "g" {:active true}) [:meta :versionId])
            _ (db/update-resource store tenant :Patient "g" {:active false})
            res (db/transact-bundle store tenant [(post true)
                                                  (put "g" true (etag v1))
                                                  (put "other" true)])]
        (is (= ["201 Created" "400 Bad Request" "200 OK"] (statuses res)))
        (is (= false (:active (db/read-resource store tenant :Patient "g"))))))))

(deftest the-current-if-match-writes-in-the-group
  (each-mode [store {}]
    (let [v1 (get-in (db/create-resource store tenant :Patient "g" {:active true}) [:meta :versionId])
          d1 (get-in (db/create-resource store tenant :Patient "d" {:active true}) [:meta :versionId])
          n (atom 0)
          res (counting-txs n (db/transact-bundle
                               store tenant
                               [(put "g" false (etag v1))
                                (put "h" true)
                                (assoc-in (delete "d") [:request :ifMatch] (etag d1))]))]
      (is (= ["200 OK" "200 OK" "204 No Content"] (statuses res)))
      (is (= 1 @n))
      (is (nil? (db/read-resource store tenant :Patient "d"))))))

(defn- failing-for
  "A lifecycle whose tx-ops fail the transaction for the resource `bad-id`."
  [bad-id after-commits]
  (reify lc/IWriteLifecycle
    (prepare [_ write] (:resource write))
    (tx-ops [_ write]
      (when (= bad-id (:id write))
        [[:sql "ASSERT 1 = 2"]]))
    (after-commit [_ commit]
      (swap! after-commits conj (mapv :id (:writes commit))))))

(deftest an-entry-whose-op-fails-runs-alone
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "seed" {:active true})
    (let [commits (atom [])
          store (assoc store :resource/lifecycle (failing-for "bad" commits))
          n (atom 0)
          res (counting-txs n (db/transact-bundle store tenant
                                                  [(put "a" true) (put "bad" true) (put "b" true)]))]
      (is (= ["200 OK" "400 Bad Request" "200 OK"] (statuses res)))
      (is (= 3 @n) "the group, the group without it, and the entry alone")
      (is (= [["a"] ["b"]] @commits) "after-commit once per entry that landed")
      (is (some? (db/read-resource store tenant :Patient "a")))
      (is (nil? (db/read-resource store tenant :Patient "bad"))))))

(deftest too-many-failures-run-the-rest-alone
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "seed" {:active true})
    (let [store (assoc store :resource/lifecycle
                       (reify lc/IWriteLifecycle
                         (prepare [_ write] (:resource write))
                         (tx-ops [_ write]
                           (when (str/starts-with? (:id write) "bad")
                             [[:sql "ASSERT 1 = 2"]]))
                         (after-commit [_ _] nil)))
          entries (into [(put "ok1" true)]
                        (concat (map #(put (str "bad" %) true) (range 5)) [(put "ok2" true)]))
          res (db/transact-bundle store tenant entries)]
      (is (= (concat ["200 OK"] (repeat 5 "400 Bad Request") ["200 OK"]) (statuses res)))
      (is (some? (db/read-resource store tenant :Patient "ok2"))))))

(defn- counter-lifecycle
  "Every write bumps one counter row, compare-and-set against the value read
   from the write's db: two writes planned against the same state conflict."
  []
  (reify lc/IWriteLifecycle
    (prepare [_ write] (:resource write))
    (tx-ops [_ {:keys [db]}]
      (let [v (:v (first (xt/q db ["SELECT v FROM batch_counter WHERE _id = 'c'"])))]
        [[:sql "ASSERT EXISTS (SELECT 1 FROM batch_counter WHERE _id = 'c' AND v = ?)" [v]]
         [:sql "INSERT INTO batch_counter (_id, v) VALUES ('c', ?)" [(inc v)]]]))
    (after-commit [_ _] nil)))

(deftest a-conflict-with-a-sibling-resolves-alone
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "seed" {:active true})
    (xt/execute-tx (:node (get @(:nodes store) tenant))
                   [[:sql "INSERT INTO batch_counter (_id, v) VALUES ('c', 0)"]])
    (let [store (assoc store :resource/lifecycle (counter-lifecycle))
          res (db/transact-bundle store tenant [(post true) (post true) (post true)])]
      (is (= ["201 Created" "201 Created" "201 Created"] (statuses res))
          "as when the entries ran one by one")
      (is (= 3 (:v (first (xt/q (:node (get @(:nodes store) tenant))
                                 ["SELECT v FROM batch_counter WHERE _id = 'c'"]))))))))

(deftest an-unknown-outcome-is-not-retried
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "seed" {:active true})
    (let [n (atom 0)
          res (with-redefs [xt/execute-tx (fn [& _]
                                            (swap! n inc)
                                            (throw (java.io.IOException. "connection reset")))]
                (db/transact-bundle store tenant [(post true) (put "x" true)]))]
      (is (= 1 @n) "a write whose commit is unknown is never sent twice")
      (is (= ["400 Bad Request" "400 Bad Request"] (statuses res)))
      (is (str/includes? (get-in res [:entry 0 :response :outcome :issue 0 :diagnostics])
                         "connection reset")))))
