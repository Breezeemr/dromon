(ns fhir-store-xtdb2.remote-server-test
  "The store's :server mode: tenants as databases attached to a standalone
   XTDB server. The naming and option checks run everywhere; the rest needs a
   server and runs only when XTDB_TEST_SERVER names one as host:port, e.g.
   the fhir-search-bench podman pod (`bb xtdb-kafka-up`, 127.0.0.1:15432),
   whose config defines the `kafkaCluster` the default attach template uses."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [xtdb.api :as xt]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]))

(deftest tenant-db-names-are-distinct-identifiers
  (let [server {:database-prefix "t_"}
        ids    ["default" "Default" "a-b" "a_b" "a_002db" "x y" "ünï" "" "9"]
        names  (map #(core-db/tenant-db-name server %) ids)]
    (testing "distinct tenant ids never share a database"
      (is (= (count ids) (count (set names)))))
    (testing "every name is a plain lower-case SQL identifier"
      (is (every? #(re-matches #"[a-z][a-z0-9_]*" %) names)))
    (is (= "t_default" (core-db/tenant-db-name server "default")))
    (is (= "fhir_default" (core-db/tenant-db-name {} "default")))))

(deftest server-mode-refuses-what-the-server-owns
  (let [server {:host "127.0.0.1" :port 1}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"only :sql"
                          (core-db/create-xtdb-store {:server server :query-mode :xtql})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :node-config"
                          (core-db/create-xtdb-store {:server server
                                                      :node-config {:log [:local {:path "x"}]}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"server\s+owns it"
                          (core-db/create-xtdb-store {:server server
                                                      :pipelined-replica-appends? true})))))

(def ^:private skipped?
  "Evaluated into the server test's var metadata at load time, so kaocha
   reports it pending rather than failing it for lack of assertions."
  (nil? (System/getenv "XTDB_TEST_SERVER")))

(defn- test-server []
  (when-let [hp (System/getenv "XTDB_TEST_SERVER")]
    (let [[host port] (str/split hp #":")]
      {:host host :port (parse-long port)
       :database-prefix (str "rst_" (subs (str (random-uuid)) 0 8) "_")})))

(defn- attached? [server db]
  (contains? (set (keys (:latest-completed-txs (xt/status (xt/client (assoc (select-keys server [:host :port])
                                                                           :dbname "xtdb"))))))
             db))

(defn- root-message
  "The innermost cause's message; the store's trace spans wrap what it throws."
  [^Throwable e]
  (ex-message (last (take-while some? (iterate ex-cause e)))))

(defn- patient [id family]
  {:resourceType "Patient" :id id :name [{:family family}]})

(deftest ^{:kaocha/pending skipped?} tenant-lifecycle-on-a-server
  (when-let [server (test-server)]
    (let [store (core-db/create-xtdb-store {:server server})
          db    (core-db/tenant-db-name server "t1")]
      (try
        (db/create-tenant store "t1")
        (testing "create-tenant attaches the tenant's database"
          (is (attached? server db)))

        (testing "writes read back, and a basis is the tenant's own"
          (db/create-resource store "t1" :Patient "p1" (patient "p1" "One"))
          (is (= "One" (get-in (db/read-resource store "t1" :Patient "p1") [:name 0 :family])))
          (is (= ["p1"] (mapv :id (db/search store "t1" :Patient {} {}))))
          (is (some? (:tx-id (db/current-basis store "t1")))))

        (testing "a write is visible to reads on other pooled connections"
          (let [misses (->> (range 32)
                            (pmap (fn [i]
                                    (let [id (str "v" i)]
                                      (db/create-resource store "t1" :Patient id (patient id "V"))
                                      (nil? (db/read-resource store "t1" :Patient id)))))
                            (filter true?)
                            count)]
            (is (zero? misses))))

        (testing "a second store instance finds the tenant on the server"
          (let [other (core-db/create-xtdb-store {:server server})]
            (is (= "Tenant already exists"
                   (try (db/create-tenant other "t1") nil
                        (catch Exception e (root-message e)))))
            (is (= "One" (get-in (db/read-resource other "t1" :Patient "p1") [:name 0 :family])))))

        (testing ":replace gives a fresh database, not a replay of the old log"
          (db/create-tenant store "t1" {:if-exists :replace})
          (is (attached? server db))
          (is (nil? (db/read-resource store "t1" :Patient "p1")))
          (is (empty? (db/search store "t1" :Patient {} {}))))

        (testing "delete-tenant with :close-storage? detaches it"
          (db/delete-tenant store "t1" {:close-storage? true})
          (is (not (attached? server db))))
        (finally
          (db/delete-tenant store "t1" {:if-absent :ignore :close-storage? true}))))))
