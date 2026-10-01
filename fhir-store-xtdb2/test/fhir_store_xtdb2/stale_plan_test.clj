(ns fhir-store-xtdb2.stale-plan-test
  "A write that adds a column changes what `SELECT *` returns, and pgwire
   refuses to run a statement described under the old result type (\"cached
   plan must not change result type\", SQLSTATE 0A000). These pin that such a
   write never fails a later read or write through the store's pool."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db])
  (:import [java.sql SQLException]
           [org.postgresql.util PSQLException PSQLState]))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- put!
  "What the server's PUT handler does without If-Match: read, then update the
   resource if it exists or create it under the client's id if it does not."
  [store tid id resource]
  (if (db/read-resource store tid :Observation id)
    (db/update-resource store tid :Observation id resource)
    (db/create-resource store tid :Observation id resource)))

(defn- observation [value-key value]
  {:resourceType "Observation" :status "final" :code {:text "bp"} value-key value})

(defn- put-across-new-columns
  "Warms the read and search statements on a one-connection pool, writes two
   value[x] shapes the table has never held, then PUTs an existing and a new
   resource and reads everything back."
  [pool-opts]
  (let [store (core-db/create-xtdb-store {:pool-opts (merge {:max-size 1 :min-idle 1} pool-opts)})
        tid "t-stale-plan"]
    (try
      ;; Past pgjdbc's default prepareThreshold of 5, so with named statements
      ;; enabled both the read and the search are server-side by now.
      (dotimes [i 6]
        (put! store tid "obs-1" (observation :valueString (str "v" i)))
        (db/search store tid :Observation {:status "final"} nil))
      (put! store tid "obs-2" (observation :valueDateTime "2024-05-01T10:00:00Z"))
      {:updated  (put! store tid "obs-1" (observation :valueString "after"))
       :created  (put! store tid "obs-3" (observation :valueBoolean true))
       :obs-2    (db/read-resource store tid :Observation "obs-2")
       :searched (db/search store tid :Observation {:status "final"} nil)}
      (finally
        (close-store-nodes! store)))))

(defn- check-puts [{:keys [updated created obs-2 searched]}]
  (is (= ["after" "7"] [(:valueString updated) (get-in updated [:meta :versionId])]))
  (is (= [true "1"] [(:valueBoolean created) (get-in created [:meta :versionId])]))
  (is (= "2024-05-01T10:00:00Z" (str (:valueDateTime obs-2))))
  (is (= #{"obs-1" "obs-2" "obs-3"} (set (map :id searched)))))

(deftest put-after-a-write-adds-a-column
  (testing "default pool: no connection keeps a statement past one execution"
    (check-puts (put-across-new-columns {})))
  (testing "named server-side statements: the stale plan is re-run, not surfaced"
    (check-puts (put-across-new-columns {:prepare-threshold 5}))))

(deftest stale-plan-recognition
  (testing "the pgjdbc refusal Hikari adjudicates"
    (is (core-db/stale-plan?
         (PSQLException. "ERROR: cached plan must not change result type"
                         PSQLState/NOT_IMPLEMENTED))))
  (testing "the anomaly xt/q rethrows it as, under a wrapper"
    (is (core-db/stale-plan?
         (ex-info "Signal `:run` form error" {}
                  (ex-info "cached plan must not change result type"
                           {:xtdb.error/code :prepared-query-out-of-date})))))
  (testing "other 0A000 refusals and closed connections are not stale plans"
    (is (not (core-db/stale-plan?
              (PSQLException. "ERROR: unsupported operation" PSQLState/NOT_IMPLEMENTED))))
    (is (not (core-db/stale-plan? (SQLException. "Connection is closed" "08003"))))))
