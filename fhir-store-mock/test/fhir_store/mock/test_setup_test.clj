(ns fhir-store.mock.test-setup-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.mock.test-setup :as ts]
            [fhir-store.protocol :as protocol])
  (:import (java.time Instant)))

(def ^:private tenant "directory")

(def ^:private org-registry
  {"identifier" {:type "token" :target nil
                 :columns [{:col "identifier" :fhir-type "Identifier" :array? true}]}
   "name" {:type "string" :target nil
           :columns [{:col "name" :fhir-type "string" :array? false}]}
   "near" {:type "special" :target nil :columns []}
   "partof" {:type "reference" :target ["Organization"]
             :columns [{:col "partOf" :fhir-type "Reference" :array? false}]}})

(defn- org [id npi & {:as more}]
  (merge {:resourceType "Organization"
          :id id
          :name (str "Pharmacy " id)
          :identifier [{:system "http://hl7.org/fhir/sid/us-npi" :value npi}]}
         more))

(defn- thrown-data [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest fixed-clock-test
  (let [store (ts/mock-store)]
    (testing "a write is stamped with the fixed instant"
      (is (= ts/default-instant
             (get-in (protocol/create-resource store tenant :Organization "o1" (org "o1" "1"))
                     [:meta :lastUpdated]))))
    (testing "set-clock! and advance-clock! move it, on the store or the clock"
      (is (= (Instant/parse "2026-10-08T12:00:00Z")
             (ts/set-clock! store "2026-10-08T12:00:00Z")))
      (is (= (Instant/parse "2026-10-08T12:05:00Z") (ts/advance-clock! store "PT5M")))
      (is (= (Instant/parse "2026-10-08T12:06:00Z")
             (ts/advance-clock! (:clock store) (java.time.Duration/ofMinutes 1))))
      (is (= (Instant/parse "2026-10-08T12:06:00Z")
             (get-in (protocol/update-resource store tenant :Organization "o1" (org "o1" "1"))
                     [:meta :lastUpdated]))))
    (testing "a store on wall-clock time has no fixed clock to move"
      (let [wall (ts/mock-store {:clock #(Instant/now)})]
        (is (thrown? clojure.lang.ExceptionInfo (ts/set-clock! wall ts/default-instant)))))))

(deftest seeded-ids-test
  (let [a (ts/seeded-ids 7)
        b (ts/seeded-ids 7)
        c (ts/seeded-ids 8)
        ids-a [(a) (a) (a)]]
    (is (= ids-a [(b) (b) (b)]) "the same seed mints the same ids in order")
    (is (= 3 (count (set ids-a))))
    (is (not= (first ids-a) (c)))
    (is (every? #(java.util.UUID/fromString %) ids-a))
    (testing "the store's creates without an id take them"
      (let [store (ts/mock-store {:id-fn (ts/seeded-ids 7)})]
        (is (= (first ids-a)
               (:id (protocol/create-resource store tenant :Organization nil {:resourceType "Organization"}))))))))

(deftest mock-store-test
  (let [store (ts/mock-store {:tenants ["realm" :directory]
                              :registries {"Organization" org-registry}})]
    (testing ":tenants are created"
      (is (= #{"realm" "directory"} (set (keys @(:state store))))))
    (testing ":registries answer a search that passes none"
      (ts/seed! store tenant [(org "o1" "111") (org "o2" "222")])
      (is (= ["o2"] (mapv :id (protocol/search store tenant :Organization {:identifier "222"} nil))))
      (is (= ["o2"] (mapv :id (protocol/search store tenant "Organization" {"name" "pharmacy o2"} nil)))))
    (testing "a registry the caller passes wins"
      (is (= [] (mapv :id (protocol/search store tenant :Organization {:name "pharmacy o2"} {})))))))

(deftest tenants-test
  (let [store (ts/mock-store)]
    (ts/ensure-tenant! store tenant)
    (ts/seed! store tenant (org "o1" "1"))
    (ts/ensure-tenant! store tenant)
    (is (some? (protocol/read-resource store tenant :Organization "o1"))
        "ensure-tenant! keeps an existing tenant's resources")
    (ts/reset-tenant! store tenant)
    (is (nil? (protocol/read-resource store tenant :Organization "o1")))
    (ts/reset-tenant! store "fresh")
    (is (contains? @(:state store) "fresh"))))

(deftest seed-test
  (let [store (ts/mock-store)]
    (testing "upserts under each resource's own id and keyword type, in order"
      (let [stored (ts/seed! store tenant [(org "o2" "2") (org "o1" "1")])]
        (is (= ["o2" "o1"] (mapv :id stored)))
        (is (= ["1" "1"] (mapv #(get-in % [:meta :versionId]) stored)))
        (is (= (first stored) (protocol/read-resource store tenant "Organization" "o2")))
        (is (contains? (get @(:state store) tenant) :Organization))))
    (testing "seeding again is an update"
      (is (= "2" (get-in (first (ts/seed! store tenant [(org "o1" "1")])) [:meta :versionId]))))
    (testing ":transform runs before the check"
      (is (= ["t1"] (mapv :id (ts/seed! store tenant [{:resourceType "Location"}]
                                        {:transform #(assoc % :id "t1")})))))
    (testing "a resource without resourceType or id refuses the whole call, naming its index"
      (let [d (thrown-data #(ts/seed! store tenant [(org "ok" "9") {:resourceType "Organization"}]))]
        (is (= {:fhir/status 400 :fhir/code "invalid" :index 1 :resource-type "Organization"} d)))
      (is (nil? (protocol/read-resource store tenant :Organization "ok")) "nothing was written")
      (is (= {:fhir/status 400 :fhir/code "invalid" :index 0}
             (thrown-data #(ts/seed! store tenant [{:id "x"}])))))))

(deftest seed-bundle-test
  (let [store (ts/mock-store)]
    (testing "a collection Bundle becomes PUTs under each entry's own id"
      (let [resp (ts/seed-bundle! store tenant {:resourceType "Bundle" :type "collection"
                                               :entry [{:resource (org "o1" "1")}
                                                       {:resource (org "o2" "2")}]})]
        (is (= "transaction-response" (:type resp)))
        (is (= ["200 OK" "200 OK"] (mapv #(get-in % [:response :status]) (:entry resp))))
        (is (some? (protocol/read-resource store tenant :Organization "o2")))))
    (testing "entries with a request are kept, and :transform applies"
      (ts/seed-bundle! store tenant {:resourceType "Bundle" :type "transaction"
                                    :entry [{:request {:method "DELETE" :url "Organization/o1"}}
                                            {:resource {:resourceType "Location" :id "l1"}
                                             :request {:method "PUT" :url "Location/l1"}}]}
                       {:transform #(assoc % :status "active")})
      (is (nil? (protocol/read-resource store tenant :Organization "o1")))
      (is (= "active" (:status (protocol/read-resource store tenant :Location "l1")))))
    (testing "one bad entry lands nothing"
      (is (= 400 (:fhir/status (thrown-data #(ts/seed-bundle! store tenant
                                                              {:resourceType "Bundle"
                                                               :entry [{:resource (org "o9" "9")}
                                                                       {:resource {:resourceType "Location"}}]})))))
      (is (nil? (protocol/read-resource store tenant :Organization "o9"))))
    (testing "a non-Bundle is refused"
      (is (= 400 (:fhir/status (thrown-data #(ts/seed-bundle! store tenant (org "o1" "1")))))))))

(deftest seed-files-test
  (let [store (ts/mock-store)
        dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "fhir-store-mock-seed-" (System/nanoTime)))
              .mkdirs)
        one (io/file dir "one.json")
        many (io/file dir "many.json")]
    (try
      (spit one "{\"resourceType\":\"Medication\",\"id\":\"m1\",\"amount\":{\"numerator\":{\"value\":1.50}}}")
      (spit many "[{\"resourceType\":\"Organization\",\"id\":\"o1\"},{\"resourceType\":\"Organization\",\"id\":\"o2\"}]")
      (testing "a file of one resource and a file of an array, in file order"
        (is (= ["m1" "o1" "o2"] (mapv :id (ts/seed-files! store tenant [(str one) many])))))
      (testing "decimals keep their written precision"
        (is (= 1.50M (get-in (protocol/read-resource store tenant :Medication "m1")
                             [:amount :numerator :value])))
        (is (= "1.50" (str (get-in (protocol/read-resource store tenant :Medication "m1")
                                   [:amount :numerator :value])))))
      (testing "one source, :transform and :read-json"
        (is (= [{:resourceType "Organization" :id "o3"}]
               (mapv #(dissoc % :meta)
                     (ts/seed-files! store tenant "ignored"
                                     {:read-json (fn [_] {:resourceType "Organization"})
                                      :transform #(assoc % :id "o3")})))))
      (finally
        (run! io/delete-file [one many dir])))))

(deftest swap-resource-test
  (let [store (ts/mock-store)]
    (ts/seed! store tenant (org "o1" "1"))
    (testing "reads, applies f with args, and writes the next version"
      (let [swapped (ts/swap-resource! store tenant :Organization "o1" assoc :active false)]
        (is (= "2" (get-in swapped [:meta :versionId])))
        (is (false? (:active (protocol/read-resource store tenant :Organization "o1"))))))
    (testing "a missing resource is a 404"
      (is (= {:fhir/status 404 :fhir/code "not-found" :resource-type "Organization" :id "nope"}
             (thrown-data #(ts/swap-resource! store tenant :Organization "nope" identity)))))
    (testing "a write between the read and the update is a 412"
      (is (= 412 (:fhir/status
                  (thrown-data
                   #(ts/swap-resource! store tenant :Organization "o1"
                                       (fn [r]
                                         (protocol/update-resource store tenant :Organization "o1" r)
                                         r)))))))))

(deftest pin-search-test
  (let [store (ts/mock-store {:registries {:Organization org-registry}})]
    (ts/seed! store tenant [(org "o1" "1") (org "o2" "2") (org "o3" "3")])
    (testing "a pin answers with its ids in pin order, whatever the matcher says"
      (ts/pin-search! store tenant "Organization" {"near" "42|-71" :name "x"} ["o3" "o1"])
      (is (= ["o3" "o1"] (mapv :id (protocol/search store tenant :Organization
                                                    {:name "x" :near "42|-71"} nil)))))
    (testing "result params are ignored when matching and applied to the answer"
      (is (= ["o1"] (mapv :id (protocol/search store tenant :Organization
                                               {"name" "x" "near" "42|-71" "_count" "1" "_sort" "_id"}
                                               nil))))
      (is (= 2 (protocol/count-resources store tenant :Organization
                                         {:name "x" :near "42|-71" :_count "1"} nil))))
    (testing "repeated values compare as a set"
      (ts/pin-search! store tenant :Organization {:identifier ["1" "2"]} ["o2"])
      (is (= ["o2"] (mapv :id (protocol/search store tenant :Organization
                                               {:identifier ["2" "1"]} nil)))))
    (testing "other params are evaluated"
      (is (= ["o1"] (mapv :id (protocol/search store tenant :Organization {:identifier "1"} nil)))))
    (testing "a pinned id that is not stored is an error"
      (protocol/delete-resource store tenant :Organization "o3")
      (is (= {:fhir/status 500 :fhir/code "exception" :resource-type "Organization" :id "o3"}
             (thrown-data #(protocol/search store tenant :Organization {:name "x" :near "42|-71"} nil)))))
    (testing "unpin-search! and clear-pins!"
      (ts/unpin-search! store tenant :Organization {:near "42|-71" :name "x"})
      (is (= [] (protocol/search store tenant :Organization {:name "x" :near "42|-71"} nil)))
      (ts/pin-search! store "other" :Organization {:name "y"} [])
      (ts/clear-pins! store tenant)
      (is (= [["other" :Organization #{["name" "y"]}]] (keys (:pins @(:harness store)))))
      (ts/clear-pins! store)
      (is (= {} (:pins @(:harness store)))))
    (testing "pinned ids must be strings"
      (is (thrown? clojure.lang.ExceptionInfo (ts/pin-search! store tenant :Organization {} [1]))))))

(deftest strict-search-test
  (let [store (ts/mock-store {:registries {:Organization org-registry}})
        refusal (fn [params]
                  (thrown-data #(protocol/search store tenant :Organization params nil)))]
    (ts/seed! store tenant [(org "o1" "1") (org "o2" "2" :partOf {:reference "Organization/o1"})])
    (testing "off: an undescribed parameter falls back to the field of the same name"
      (is (= [] (protocol/search store tenant :Organization {:alias "x"} nil))))
    (ts/strict-search! store)
    (testing "evaluable parameters still answer"
      (is (= ["o2"] (mapv :id (protocol/search store tenant :Organization
                                               {:identifier "http://hl7.org/fhir/sid/us-npi|2"
                                                :partof:Organization "o1"
                                                :name:contains "O2"
                                                :_id "o2"
                                                :_count "5"} nil))))
      (is (= ["o1"] (mapv :id (protocol/search store tenant :Organization {:partof:missing "true"} nil)))))
    (testing "what the matcher cannot evaluate is a 501 naming the parameter"
      (is (= {:fhir/status 501 :fhir/code "not-supported" :resource-type "Organization"
              :param "alias" :reason "no search registry descriptor"}
             (refusal {:alias "x"})))
      (is (= "modifier :text" (:reason (refusal {"identifier:text" "npi"}))))
      (is (= "parameter type \"special\"" (:reason (refusal {:near "42|-71"}))))
      (is (= "chained parameter" (:reason (refusal {:partof.name "x"}))))
      (is (= "reverse chained parameter" (:reason (refusal {"_has:Location:organization:name" "x"}))))
      (is (= "modifier :above" (:reason (refusal {"_profile:above" "x"}))))
      (is (= 501 (:fhir/status (thrown-data #(protocol/count-resources store tenant :Organization
                                                                       {:alias "x"} nil))))))
    (testing "a quantity with a unit is not evaluated"
      (let [registry {"value-quantity" {:type "quantity" :target nil
                                        :columns [{:col "valueQuantity" :fhir-type "Quantity" :array? false}]}}]
        (is (= [] (protocol/search store tenant :Observation {"value-quantity" "5.4"} registry)))
        (is (= 501 (:fhir/status (thrown-data #(protocol/search store tenant :Observation
                                                                {"value-quantity" "5.4|http://unitsofmeasure.org|mg"}
                                                                registry)))))))
    (testing "a pinned search is never refused"
      (ts/pin-search! store tenant :Organization {:alias "x"} ["o1"])
      (is (= ["o1"] (mapv :id (protocol/search store tenant :Organization {:alias "x"} nil)))))
    (testing "turned off again"
      (ts/strict-search! store false)
      (is (= [] (protocol/search store tenant :Organization {:alias "y"} nil))))
    (testing "on from construction"
      (let [strict (ts/mock-store {:strict-search? true})]
        (is (= 501 (:fhir/status (thrown-data #(protocol/search strict tenant :Organization {:name "x"} nil)))))))))

(deftest recording-test
  (let [store (ts/mock-store {:record? true :registries {:Organization org-registry}})]
    (ts/seed! store tenant (org "o1" "111"))
    (protocol/update-resource store tenant :Organization "o1" (org "o1" "111")
                              {:if-match "1" :tx-metadata {:principal {:userId "secret-user"}}})
    (protocol/read-resource store tenant :Organization "o1")
    (protocol/read-resource store tenant :Organization "nope")
    (protocol/vread-resource store tenant :Organization "o1" 1)
    (protocol/history store tenant :Organization "o1")
    (protocol/search store tenant :Organization {:identifier "111" :_count "5"} nil)
    (protocol/count-resources store tenant :Organization {:name "Pharmacy"} nil)
    (protocol/delete-resource store tenant :Organization "o1")
    (protocol/delete-resource store tenant :Organization "o1")
    (testing "writes: ids, versions and opts keys, never values"
      (is (= [{:op :update :tenant tenant :type "Organization" :id "o1" :version-id "1" :opts #{}}
              {:op :update :tenant tenant :type "Organization" :id "o1" :version-id "2"
               :opts #{:if-match :tx-metadata}}
              {:op :delete :tenant tenant :type "Organization" :id "o1" :version-id "3"
               :opts #{} :written? true}
              {:op :delete :tenant tenant :type "Organization" :id "o1" :version-id nil
               :opts #{} :written? false}]
             (ts/writes store))))
    (testing "reads"
      (is (= [{:op :read :tenant tenant :type "Organization" :id "o1" :found? true}
              {:op :read :tenant tenant :type "Organization" :id "nope" :found? false}
              {:op :vread :tenant tenant :type "Organization" :id "o1" :version-id "1" :found? true}
              {:op :history :tenant tenant :type "Organization" :id "o1" :count 2}]
             (ts/reads store))))
    (testing "searches: parameter names, never values"
      (is (= [{:op :search :tenant tenant :type "Organization" :params #{"_count" "identifier"}
               :pinned? false :ids ["o1"]}
              {:op :count :tenant tenant :type "Organization" :params #{"name"}
               :pinned? false :count 1}]
             (ts/searches store))))
    (testing "no value reaches the log"
      (let [printed (pr-str (ts/log store))]
        (is (not (re-find #"111|secret-user|Pharmacy" printed)))))
    (testing "clear-log! and recording!"
      (ts/clear-log! store)
      (is (= [] (ts/log store)))
      (ts/recording! store false)
      (protocol/read-resource store tenant :Organization "o1")
      (is (= [] (ts/log store)))
      (ts/recording! store)
      (protocol/read-resource store tenant :Organization "o1")
      (is (= 1 (count (ts/reads store)))))
    (testing "a transaction logs its entries once it commits, and nothing when it rolls back"
      (ts/clear-log! store)
      (protocol/transact-transaction store tenant
                                     [{:resource (org "o5" "5") :request {:method "PUT" :url "Organization/o5"}}])
      (is (= [:update] (mapv :op (ts/log store))))
      (ts/clear-log! store)
      (is (thrown? Exception
                   (protocol/transact-transaction store tenant
                                                  [{:resource (org "o6" "6") :request {:method "PUT" :url "Organization/o6"}}
                                                   {:resource (org "o5" "5")
                                                    :request {:method "PUT" :url "Organization/o5" :ifMatch "W/\"9\""}}])))
      (is (= [] (ts/log store))))
    (testing "off by default"
      (let [plain (ts/mock-store)]
        (ts/seed! plain tenant (org "o1" "1"))
        (is (= [] (ts/log plain)))))))

(deftest snapshot-test
  (let [store (ts/mock-store {:record? true})]
    (ts/seed! store tenant (org "o1" "1"))
    (ts/pin-search! store tenant :Organization {:name "x"} ["o1"])
    (let [snap (ts/snapshot store)
          minted (:id (protocol/create-resource store tenant :Organization nil {:resourceType "Organization"}))]
      (ts/advance-clock! store "PT1H")
      (ts/seed! store tenant (org "o2" "2"))
      (ts/clear-pins! store)
      (ts/restore! store snap)
      (testing "resources, pins and the log are back"
        (is (nil? (protocol/read-resource store tenant :Organization "o2")))
        (is (nil? (protocol/read-resource store tenant :Organization minted)))
        (is (= ["o1"] (mapv :id (protocol/search store tenant :Organization {:name "x"} nil))))
        (is (= [:update :read :read :search] (mapv :op (ts/log store)))
            "the log as snapshotted, then this block's own reads and search"))
      (testing "the clock and the id sequence are back, so a rerun mints the same"
        (is (= ts/default-instant @(:clock store)))
        (is (= minted (:id (protocol/create-resource store tenant :Organization nil
                                                     {:resourceType "Organization"})))))))
  (testing "with-snapshot restores however f ends, and makes a fixture"
    (let [store (ts/mock-store)]
      (ts/seed! store tenant (org "o1" "1"))
      (is (thrown? clojure.lang.ExceptionInfo
                   (ts/with-snapshot store
                     (fn []
                       (ts/seed! store tenant (org "o2" "2"))
                       (throw (ex-info "test body failed" {}))))))
      (is (nil? (protocol/read-resource store tenant :Organization "o2")))
      ((ts/with-snapshot store) #(protocol/delete-resource store tenant :Organization "o1"))
      (is (some? (protocol/read-resource store tenant :Organization "o1"))))))

(deftest plain-store-unchanged-test
  (testing "a store from create-mock-store with no harness options searches as before"
    (let [store (mock/create-mock-store {})]
      (protocol/create-resource store tenant :Organization "o1" (org "o1" "1"))
      (is (= [] (protocol/search store tenant :Organization {:alias "x"} nil)))
      (is (= ["o1"] (mapv :id (protocol/search store tenant :Organization {:name "Pharmacy o1"} nil))))
      (is (= [] (:log @(:harness store))))))
  (testing "a MockStore built without create-mock-store has no harness and still works"
    (let [store (mock/->MockStore (atom {}) {})]
      (protocol/create-resource store tenant :Organization "o1" (org "o1" "1"))
      (is (= ["o1"] (mapv :id (protocol/search store tenant :Organization {} nil)))))))
