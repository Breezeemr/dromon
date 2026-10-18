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
