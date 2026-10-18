(ns fhir-store-xtdb2.conns-test
  "The connection sources behind the :adbc and :flight-sql transports. That
   the store answers the same over each is the whole suite's job, run per
   transport through the tests.edn profiles; these pin how the sources lend
   connections."
  (:require [clojure.test :refer [deftest is testing]]
            [xtdb.api :as xt]
            [xtdb.error :as err]
            [xtdb.node :as xtn]
            [fhir-store-xtdb2.conns :as conns :refer [with-conn]]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db])
  (:import [org.apache.arrow.flight CallStatus FlightStatusCode]
           [xtdb.api Xtdb$Connection]))

(deftest broken-session-recognition
  (testing "a statement the server refused leaves the session usable"
    (is (not (conns/broken-session? (err/incorrect :xtdb/sql-error "Column not found: x" {}))))
    (is (not (conns/broken-session? (err/conflict :xtdb/assert-failed "Assert failed" {}))))
    (is (not (conns/broken-session? (ex-info "Version conflict" {:fhir/status 412}
                                             (err/conflict :xtdb/assert-failed "Assert failed" {}))))))
  (testing "the store's own refusal never reached the server"
    (is (not (conns/broken-session? (ex-info "Version conflict" {:fhir/status 412})))))
  (testing "a transport failure or a fault does not"
    (is (conns/broken-session? (err/unavailable nil "UNAVAILABLE: io exception" {})))
    (is (conns/broken-session? (err/fault nil "boom" {})))
    (is (conns/broken-session? (ex-info "wrapped" {} (InterruptedException.))))
    (is (conns/broken-session?
         (ex-info "wrapped" {}
                  (.toRuntimeException (CallStatus. FlightStatusCode/UNAVAILABLE nil "gone" nil)))))))

(deftest adbc-source-lends-in-process-connections
  (with-open [node (xtn/start-node {})]
    (let [src (conns/adbc-source node)]
      (with-conn [c src]
        (is (instance? Xtdb$Connection c))
        (xt/execute-tx c [[:sql "INSERT INTO t (_id) VALUES (1)"]]))
      (testing "a write through one borrow is awaited by the next"
        (is (= [{:xt/id 1}] (with-conn [c src] (xt/q c ["SELECT _id FROM t"])))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"in-process XTDB node"
                        (conns/adbc-source (xt/client {:port 1})))))

(deftest flight-sql-pool-reuses-sessions
  (with-open [node (xtn/start-node {:flight-sql {:port 0}})
              pool (conns/flight-sql-pool {:port (.getFlightSqlPort node)
                                           :max-size 1 :connection-timeout-ms 200})]
    (let [first-conn (with-conn [c pool] c)]
      (testing "a released session is lent again"
        (is (identical? first-conn (with-conn [c pool] c))))

      (testing "a refused statement keeps the session"
        (is (thrown? Exception (with-conn [c pool] (xt/q c ["SELECT nope FROM nowhere"]))))
        (is (identical? first-conn (with-conn [c pool] c))))

      (testing "a broken one is replaced"
        (is (thrown? Exception (with-conn [_ pool] (throw (err/unavailable nil "gone" {})))))
        (is (not (identical? first-conn (with-conn [c pool] c)))))

      (testing "the pool lends at most :max-size sessions"
        (with-conn [_ pool]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No FlightSQL connection free"
                                (with-conn [_ pool] nil)))))

      (testing "the pool is a Connectable, borrowing per call"
        (xt/execute-tx pool [[:sql "INSERT INTO t (_id) VALUES (1)"]])
        (is (= [{:xt/id 1}] (xt/q pool ["SELECT _id FROM t"])))))))

(deftest every-transport-starts-its-source
  (doseq [[transport source-class] {:pgwire     com.zaxxer.hikari.HikariDataSource
                                    :adbc       fhir_store_xtdb2.conns.AdbcSource
                                    :flight-sql fhir_store_xtdb2.conns.FlightSqlPool}]
    (testing transport
      (let [store (core-db/create-xtdb-store {:transport transport})]
        (try
          (db/create-resource store "t" :Patient "p1" {:resourceType "Patient" :active true})
          (is (instance? source-class (:pool (get @(:nodes store) "t"))))
          (is (true? (:active (db/read-resource store "t" :Patient "p1"))))
          (finally
            (doseq [[_ {:keys [node pool]}] @(:nodes store)]
              (.close ^java.lang.AutoCloseable pool)
              (.close ^java.lang.AutoCloseable node))))))))

;; XTDB 2.2.0-beta3's FlightSQL server loads result pages into the declared
;; result type by position (see fhir-store-xtdb2.conns). These pin that a
;; session's reads come back as the node's own xt/q returns them anyway.

(defn- pool-on [node]
  (conns/flight-sql-pool {:port (.getFlightSqlPort node) :max-size 2 :connection-timeout-ms 5000}))

(deftest flight-sql-reads-a-struct-missing-a-field-flushed-blocks-carry
  (with-open [node (xtn/start-node {:flight-sql {:port 0} :indexer {:rows-per-block 20}})
              pool (pool-on node)]
    (doseq [i (range 50)]
      (xt/execute-tx node [[:sql (str "INSERT INTO obs RECORDS {_id: 'f" i "', vq: {value: 1.0, unit: 'mg'}, xs: [1]}")]]))
    (doseq [i (range 3)]
      (xt/execute-tx node [[:sql (str "INSERT INTO obs RECORDS {_id: 'l" i "', vq: {value: 2.0}, xs: ['a', {k: 1}]}")]]))
    (doseq [[sql args] [["SELECT * FROM obs WHERE _id = ?" ["l1"]]
                        ["SELECT * FROM obs WHERE _id = ?" ["f1"]]
                        ["SELECT *, _system_from FROM obs FOR ALL SYSTEM_TIME" []]]]
      (testing sql
        (is (= (set (xt/q node (into [sql] args)))
               (set (with-conn [c pool] (xt/q c (into [sql] args))))))))
    (testing "the query's own row order survives"
      (let [sql "SELECT _id, vq FROM obs ORDER BY _id DESC LIMIT 7 OFFSET 3"]
        (is (= (xt/q node [sql]) (with-conn [c pool] (xt/q c [sql]))))))
    (testing "a statement that is not a query is sent as is"
      (is (seq (with-conn [c pool]
                 (xt/execute-tx c [[:sql "INSERT INTO t (_id) VALUES (1)"]])
                 (xt/q c ["SHOW LATEST_SUBMITTED_TX"])))))))

(deftest flight-sql-reads-struct-fields-in-another-order-than-the-jvm-saw-first
  ;; A JVM that has seen a struct's fields in one order declares that order for
  ;; every later struct with the same fields, on any node. Unconformed, the
  ;; second read returns other fields' bytes, or crashes the JVM.
  (let [vq {"value" 60.0 "unit" "UNIT" "system" "SYSTEM" "code" "CODE"}
        ordered (fn [ks] (apply array-map (mapcat (fn [k] [k (vq k)]) ks)))]
    (doseq [order [["system" "unit" "value" "code"] ["value" "unit" "system" "code"] ["code" "value" "unit" "system"]]]
      (with-open [node (xtn/start-node {:flight-sql {:port 0}})
                  pool (pool-on node)]
        (with-conn [c pool]
          (xt/execute-tx c [[:sql "INSERT INTO obs (_id, vq) VALUES (?, ?)" ["o1" (ordered order)]]])
          (is (= {:value 60.0 :unit "UNIT" :system "SYSTEM" :code "CODE"}
                 (:vq (first (xt/q c ["SELECT * FROM obs WHERE _id = ?" "o1"]))))
              (str "written in order " order)))))))

(deftest materialized-sql-wraps-queries-only
  (is (= (str "SELECT * FROM (SELECT *, ROW_NUMBER() OVER () AS _xt_flight_row FROM (SELECT 1 AS a) AS _xt_flight_rows)"
              " AS _xt_flight_sorted ORDER BY _xt_flight_row")
         (conns/materialized-sql "SELECT 1 AS a;  ")))
  (with-open [node (xtn/start-node {:flight-sql {:port 0}})
              pool (pool-on node)]
    (testing "the row number never reaches the caller, under any key-fn"
      (is (= [{:a 1}] (with-conn [c pool] (xt/q c ["SELECT 1 AS a"]))))
      (is (= [{:a 1}] (with-conn [c pool] (xt/q c ["SELECT 1 AS a"] {:key-fn :snake-case-keyword}))))
      (is (= [{"a" 1}] (with-conn [c pool] (xt/q c ["SELECT 1 AS a"] {:key-fn :snake-case-string})))))
    (testing "a WITH query is wrapped too"
      (is (= [{:b 2}] (with-conn [c pool] (xt/q c ["WITH t AS (SELECT 2 AS b) SELECT b FROM t"])))))))
