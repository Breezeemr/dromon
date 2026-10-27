(ns fhir-store-xtdb2.tx-metadata-test
  "The `:tx-metadata` stamp is kept beside the data: written in the same
   transaction as every write verb's data, and read back per version with
   `tx-metadata-of`. Runs in both query modes, and over every transport with
   the tests.edn profiles."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [xtdb.api :as xt]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store-xtdb2.tx-metadata :as txm]
            [fhir-store.lifecycle :as lc]
            [fhir-store.protocol :as db]))

(def ^:private tenant "stamp-tenant")

(def ^:private stamp
  {:principal {:credential "session" :altId "jdoe" :userId "6f1c2d3e-0000-4000-8000-000000000001"}
   :device    {:name "example-service" :versions {"example-service" "abc123" "example-mapper" "def456"}}
   :x/undocumented [1 #{:a "b"} #uuid "6f1c2d3e-0000-4000-8000-000000000002"]})

(def ^:private other-stamp
  {:principal {:credential "token"} :device {:name "example-batch"}})

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

(defn- vid [res] (get-in res [:meta :versionId]))

(defn- delete-vid
  "A delete returns no resource; its version is named by its commit time."
  [res]
  (core-db/version-of-system-time (get-in (meta res) [:fhir-store/basis :system-time])))

(defn- etag-vid [entry]
  (second (re-find #"W/\"(.*)\"" (get-in entry [:response :etag]))))

(defn- stamp-rows
  "Every stamp row the tenant holds, or 0 when none was ever written."
  [store]
  (let [node (:node (get @(:nodes store) tenant))]
    (if (seq (xt/q node [txm/exists-sql]))
      (count (xt/q node [(str "SELECT _id FROM " txm/table " FOR ALL SYSTEM_TIME")]))
      0)))

(defn- stamped [s] {:tx-metadata s})

(deftest the-store-keeps-stamps
  (each-mode [store {}]
    (is (satisfies? db/ITxMetadataStore store))
    (is (true? (db/supports-tx-metadata? store)))))

(deftest single-resource-writes-round-trip
  (each-mode [store {}]
    (let [c (db/create-resource store tenant :Patient "p1" {:active true} (stamped stamp))
          u (db/update-resource store tenant :Patient "p1" {:active false} (stamped other-stamp))
          u2 (db/update-resource store tenant :Patient "p1" {:active true})
          d (db/delete-resource store tenant :Patient "p1" (stamped stamp))]
      (testing "each version answers the stamp it was written under, undocumented keys included"
        (is (= stamp (db/tx-metadata-of store tenant :Patient "p1" (vid c))))
        (is (= other-stamp (db/tx-metadata-of store tenant "Patient" "p1" (vid u)))))
      (testing "an unstamped write answers nil at its own version and hides no earlier stamp"
        (is (nil? (db/tx-metadata-of store tenant :Patient "p1" (vid u2))))
        (is (= stamp (db/tx-metadata-of store tenant :Patient "p1" (vid c)))))
      (testing "a delete is a version, addressed by its commit time"
        (is (= stamp (db/tx-metadata-of store tenant :Patient "p1" (delete-vid d))))))))

(deftest a-version-answers-only-for-its-own-resource
  (each-mode [store {}]
    (let [c (db/create-resource store tenant :Patient "p1" {:active true} (stamped stamp))]
      (db/create-resource store tenant :Patient "p2" {:active true})
      (is (nil? (db/tx-metadata-of store tenant :Patient "p2" (vid c))) "another resource")
      (is (nil? (db/tx-metadata-of store tenant :Observation "p1" (vid c))) "another type")
      (is (nil? (db/tx-metadata-of store tenant :Patient "nope" (vid c))) "no such resource")
      (is (nil? (db/tx-metadata-of store tenant :Patient "p1" "3")) "a stored counter")
      (is (nil? (db/tx-metadata-of store tenant :Patient "p1" "not-a-version")))
      (is (nil? (db/tx-metadata-of store tenant :Patient "p1" "2001-01-01T000000Z"))
          "a well-formed version no write made"))))

(deftest a-tenant-with-no-stamp-answers-nil
  (each-mode [store {}]
    (let [c (db/create-resource store tenant :Patient "p1" {:active true})]
      (is (= 0 (stamp-rows store)))
      (is (nil? (db/tx-metadata-of store tenant :Patient "p1" (vid c))))
      (is (nil? (db/tx-metadata-of store "untouched-tenant" :Patient "p1" (vid c)))))))

(deftest a-delete-that-deleted-nothing-is-stamped-nowhere
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "seed" {:active true})
    (let [d (db/delete-resource store tenant :Patient "never-was" (stamped stamp))]
      (is (= 0 (stamp-rows store)))
      (is (nil? (db/tx-metadata-of store tenant :Patient "never-was" (delete-vid d)))))))

(deftest a-refused-write-is-stamped-nowhere
  (each-mode [store {}]
    (let [c (db/create-resource store tenant :Patient "p1" {:active true})]
      (is (thrown? Exception
                   (db/update-resource store tenant :Patient "p1" {:active false}
                                       {:if-match "2001-01-01T000000Z" :tx-metadata stamp})))
      (is (thrown? Exception
                   (db/create-resource store tenant :Patient "p1" {:active false} (stamped stamp))))
      (is (thrown? Exception
                   (db/delete-resource store tenant :Patient "p1"
                                       {:if-match "2001-01-01T000000Z" :tx-metadata stamp})))
      (is (= 0 (stamp-rows store)))
      (is (= (vid c) (vid (db/read-resource store tenant :Patient "p1")))))))

(defn- problem
  "The ex-data of the refusal `f` throws: a store span rethrows it wrapped,
   so the cause chain is walked to the first ex-data naming a status."
  [f]
  (try (f) nil
       (catch Exception e
         (some #(when (:fhir/status (ex-data %)) (ex-data %))
               (take-while some? (iterate ex-cause e))))))

(defrecord Principal [altId])

(deftest a-malformed-stamp-refuses-the-write
  (each-mode [store {}]
    (testing "check-tx-metadata's shape rules, before anything is written"
      (let [data (problem #(db/create-resource store tenant :Patient "p1" {:active true}
                                               (stamped {:device {:versions {}}})))]
        (is (= 500 (:fhir/status data)))
        (is (= :device-name-missing (:tx-metadata/problem data))))
      (is (nil? (db/read-resource store tenant :Patient "p1"))))
    (testing "a value edn cannot read back, named by its path and never its value"
      (let [data (problem #(db/create-resource store tenant :Patient "p2" {:active true}
                                               (stamped {:principal (->Principal "jdoe")})))]
        (is (= :unprintable-value (:tx-metadata/problem data)))
        (is (= [:principal] (:path data)))
        (is (not (str/includes? (pr-str data) "jdoe"))))
      (is (nil? (db/read-resource store tenant :Patient "p2"))))
    (testing "text XTDB would store as ?"
      (let [data (problem #(db/update-resource store tenant :Patient "p3" {:active true}
                                               (stamped {:principal {:altId "j\uD83D"}})))]
        (is (= :unprintable-value (:tx-metadata/problem data)))
        (is (= [:principal :altId] (:path data)))
        (is (= :unpaired-surrogate (:tx-metadata/reason data))))
      (is (nil? (db/read-resource store tenant :Patient "p3"))))
    (testing "the batch and transaction verbs refuse it too"
      (is (= :device-name-missing
             (:tx-metadata/problem
              (problem #(db/transact-transaction store tenant
                                                 [{:request {:method "PUT" :url "Patient/p4"}
                                                   :resource {:active true}}]
                                                 (stamped {:device {}}))))))
      (is (= :device-name-missing
             (:tx-metadata/problem
              (problem #(db/transact-bundle store tenant
                                            [{:request {:method "PUT" :url "Patient/p4"}
                                              :resource {:active true}}]
                                            (stamped {:device {}}))))))
      (is (nil? (db/read-resource store tenant :Patient "p4"))))
    (is (= 0 (stamp-rows store)))))

(deftest a-stamp-that-names-nothing-is-no-stamp
  (each-mode [store {}]
    (let [a (db/create-resource store tenant :Patient "p1" {:active true} (stamped {}))
          b (db/update-resource store tenant :Patient "p1" {:active false}
                                (stamped {:principal nil :device nil}))]
      (is (= 0 (stamp-rows store)))
      (is (nil? (db/tx-metadata-of store tenant :Patient "p1" (vid a))))
      (is (nil? (db/tx-metadata-of store tenant :Patient "p1" (vid b)))))))

(deftest a-transaction-bundle-carries-one-stamp
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "old" {:active true})
    (let [res (db/transact-transaction
               store tenant
               [{:request {:method "POST" :url "Patient"} :fullUrl "urn:uuid:new-one"
                 :resource {:active true}}
                {:request {:method "PUT" :url "Patient/put-one"} :resource {:active false}}
                {:request {:method "DELETE" :url "Patient/old"}}
                {:request {:method "DELETE" :url "Patient/never-was"}}
                {:request {:method "GET" :url "Patient/put-one"}}]
               (stamped stamp))
          tx-vid (core-db/version-of-system-time
                  (get-in (meta res) [:fhir-store/basis :system-time]))]
      (testing "every resource the Bundle wrote answers its stamp at the Bundle's version"
        (is (= stamp (db/tx-metadata-of store tenant :Patient "new-one" tx-vid)))
        (is (= stamp (db/tx-metadata-of store tenant :Patient "put-one" tx-vid)))
        (is (= stamp (db/tx-metadata-of store tenant :Patient "old" tx-vid))))
      (testing "a DELETE of nothing, and a GET, are stamped nowhere"
        (is (nil? (db/tx-metadata-of store tenant :Patient "never-was" tx-vid)))
        (is (= 3 (stamp-rows store))))))
  (testing "a Bundle that writes nothing commits no stamp"
    (each-mode [store {}]
      (db/create-resource store tenant :Patient "p1" {:active true})
      (db/transact-transaction store tenant
                               [{:request {:method "GET" :url "Patient/p1"}}
                                {:request {:method "HEAD" :url "Patient/absent" :ifNoneMatch "*"}}]
                               (stamped stamp))
      (is (= 0 (stamp-rows store)))))
  (testing "a Bundle refused at commit leaves no stamp"
    (each-mode [store {}]
      (db/create-resource store tenant :Patient "p1" {:active true})
      (is (thrown? Exception
                   (db/transact-transaction
                    store tenant
                    [{:request {:method "PUT" :url "Patient/p2"} :resource {:active true}}
                     {:request {:method "PUT" :url "Patient/p1" :ifMatch "W/\"2001-01-01T000000Z\""}
                      :resource {:active false}}]
                    (stamped stamp))))
      (is (= 0 (stamp-rows store))))))

(defn- counter-lifecycle
  "Every write compare-and-sets one counter row against the value its db
   read, so writes planned together conflict and all but one run alone."
  []
  (reify lc/IWriteLifecycle
    (prepare [_ write] (:resource write))
    (tx-ops [_ {:keys [db]}]
      (let [v (:v (first (xt/q db ["SELECT v FROM stamp_counter WHERE _id = 'c'"])))]
        [[:sql "ASSERT EXISTS (SELECT 1 FROM stamp_counter WHERE _id = 'c' AND v = ?)" [v]]
         [:sql "INSERT INTO stamp_counter (_id, v) VALUES ('c', ?)" [(inc v)]]]))
    (after-commit [_ _] nil)))

(deftest a-batch-stamps-each-transaction-it-commits
  (each-mode [store {}]
    (let [d (db/create-resource store tenant :Patient "d" {:active true})
          res (db/transact-bundle store tenant
                                  [{:request {:method "PUT" :url "Patient/a"} :resource {:active true}}
                                   {:request {:method "POST" :url "Patient"} :resource {:active true}}
                                   {:request {:method "DELETE" :url "Patient/d"}}
                                   {:request {:method "PUT" :url "Patient/stale"
                                              :ifMatch (str "W/\"" (vid d) "\"")}
                                    :resource {:active true}}]
                                  (stamped stamp))
          [a post del stale] (:entry res)
          group-vid (etag-vid a)]
      (is (= "204 No Content" (get-in del [:response :status])))
      (is (= "400 Bad Request" (get-in stale [:response :status])))
      (testing "the group's entries answer the stamp at the group's version"
        (is (= stamp (db/tx-metadata-of store tenant :Patient "a" group-vid)))
        (is (= stamp (db/tx-metadata-of store tenant :Patient (get-in post [:resource :id])
                                        (etag-vid post))))
        (is (= stamp (db/tx-metadata-of store tenant :Patient "d" group-vid))))
      (is (= 3 (stamp-rows store)) "a failed entry is stamped nowhere")))
  (testing "entries that run alone stamp through their own verb"
    (each-mode [store {}]
      (db/create-resource store tenant :Patient "seed" {:active true})
      (xt/execute-tx (:node (get @(:nodes store) tenant))
                     [[:sql "INSERT INTO stamp_counter (_id, v) VALUES ('c', 0)"]])
      (let [store (assoc store :resource/lifecycle (counter-lifecycle))
            res (db/transact-bundle store tenant
                                    (mapv (fn [id] {:request {:method "PUT" :url (str "Patient/" id)}
                                                    :resource {:active true}})
                                          ["x" "y" "z"])
                                    (stamped other-stamp))]
        (is (= ["200 OK" "200 OK" "200 OK"] (mapv #(get-in % [:response :status]) (:entry res))))
        (is (= 3 (count (distinct (map etag-vid (:entry res))))) "three transactions")
        (doseq [[id e] (map vector ["x" "y" "z"] (:entry res))]
          (is (= other-stamp (db/tx-metadata-of store tenant :Patient id (etag-vid e))) id))))))

(deftest an-unreadable-stamp-throws-rather-than-answering-nil
  (each-mode [store {}]
    (db/create-resource store tenant :Patient "seed" {:active true})
    (let [node (:node (get @(:nodes store) tenant))
          ;; Another writer's row: a version and a stamp no store verb wrote.
          foreign! (fn [id text]
                     (core-db/version-of-system-time
                      (:system-time
                       (xt/execute-tx node [[:sql "INSERT INTO \"patient\" (_id, active) VALUES (?, true)" [id]]
                                            [:sql (str "INSERT INTO " txm/table " (_id, stamp) VALUES (?, ?)")
                                             [(txm/stamp-key :Patient id) text]]]))))]
      (doseq [[id text] [["p1" "#not/edn {:altId \"jdoe\"}"]
                         ["p2" "[\"jdoe\"]"]]]
        (let [data (problem #(db/tx-metadata-of store tenant :Patient id (foreign! id text)))]
          (is (= :unreadable-stamp (:tx-metadata/problem data)) id)
          (is (= id (:id data)))
          (is (not (str/includes? (pr-str data) "jdoe"))))))))
