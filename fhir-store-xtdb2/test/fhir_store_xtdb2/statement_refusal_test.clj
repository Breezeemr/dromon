(ns fhir-store-xtdb2.statement-refusal-test
  "XTDB refuses an SQL feature or a parameter type it does not support with
   SQLSTATE 0A000, and an incorrect statement (a parse or plan error, a failed
   cast) with 08P01. Hikari treats both as a broken connection. These pin that
   the store's pool keeps the connection, so the refusal reaches the caller
   rather than the \"Connection is closed\" xt/q's ROLLBACK raises on an
   evicted one."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]
            [next.jdbc :as jdbc])
  (:import [java.sql SQLException]
           [java.time OffsetTime]
           [org.postgresql PGConnection]
           [org.postgresql.util PSQLException PSQLState ServerErrorMessage]))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- backend-pid
  "The pgwire session behind the pool's next connection. On a one-connection
   pool, a different pid means the previous connection was evicted."
  [pool]
  (with-open [conn (jdbc/get-connection pool)]
    (.getBackendPID (.unwrap conn PGConnection))))

(defn- refusal-messages
  "Calls `f` and returns the messages of what it throws, outermost first."
  [f]
  (try
    (f)
    nil
    (catch Exception e
      (mapv #(str (ex-message %)) (take-while some? (iterate ex-cause e))))))

(defn- mentions? [messages text]
  (boolean (some #(str/includes? % text) messages)))

(deftest unsupported-refusal-reaches-the-caller
  (let [store (core-db/create-xtdb-store {:pool-opts {:max-size 1 :min-idle 1}})
        tid "t-statement-refusal"]
    (try
      (db/create-resource store tid :Observation "obs-1"
                          {:resourceType "Observation" :status "final" :code {:text "bp"}})
      (let [pool (:pool (get @(:nodes store) tid))
            pid (backend-pid pool)]
        (testing "a parameter type XTDB cannot read, bound by a store read"
          (let [messages (refusal-messages
                          #(db/read-resource store tid :Observation (OffsetTime/now)))]
            (is (mentions? messages "Unsupported param type provided for read") messages)
            (is (not (mentions? messages "Connection is closed")) messages)))
        (testing "an SQL feature XTDB lacks, run the way every store read runs"
          (let [messages (refusal-messages
                          #(with-open [conn (jdbc/get-connection pool)]
                             (core-db/run-query
                              conn ["WITH RECURSIVE t AS (SELECT 1 AS n) SELECT * FROM t"])))]
            (is (mentions? messages "Recursive CTEs are not supported yet") messages)
            (is (not (mentions? messages "Connection is closed")) messages)))
        (testing "the connection outlives both refusals and keeps serving reads"
          (is (= pid (backend-pid pool)))
          (is (= "obs-1" (:id (db/read-resource store tid :Observation "obs-1"))))))
      (finally
        (close-store-nodes! store)))))

(deftest incorrect-refusal-reaches-the-caller
  (let [store (core-db/create-xtdb-store {:pool-opts {:max-size 1 :min-idle 1}})
        tid "t-incorrect-refusal"]
    (try
      (db/create-resource store tid :Observation "obs-1"
                          {:resourceType "Observation" :status "final" :code {:text "bp"}})
      (let [pool (:pool (get @(:nodes store) tid))
            pid (backend-pid pool)]
        (doseq [[kind sql expected]
                [["a parse error" "SELECT * FROM" "Errors parsing SQL statement"]
                 ["a plan error" "SELECT nope FROM nonexistent_table" "Table not found: nonexistent_table"]
                 ["division by zero" "SELECT 1/0 AS x" "division by zero"]
                 ["a failed cast" "SELECT CAST('abc' AS INTEGER) AS x" "For input string: \"abc\""]]]
          (testing (str kind ", run the way every store read runs")
            (let [messages (refusal-messages
                            #(with-open [conn (jdbc/get-connection pool)]
                               (core-db/run-query conn [sql])))]
              (is (mentions? messages expected) messages)
              (is (not (mentions? messages "Connection is closed")) messages)
              (is (= pid (backend-pid pool)) "the connection was evicted"))))
        (testing "the connection outlives every refusal and keeps serving reads"
          (is (= "obs-1" (:id (db/read-resource store tid :Observation "obs-1"))))))
      (finally
        (close-store-nodes! store)))))

(defn- server-error
  "A PSQLException as pgjdbc builds it from a server ErrorResponse."
  ([sql-state message]
   (server-error sql-state message nil))
  ([sql-state message detail]
   (PSQLException.
    (ServerErrorMessage.
     (str "SERROR\u0000C" sql-state "\u0000M" message "\u0000"
          (when detail (str "D" detail "\u0000")))))))

(deftest statement-refusal-recognition
  (testing "a 0A000 the server sent, whatever the feature"
    (is (core-db/statement-refusal?
         (server-error "0A000" "Unsupported param type provided for read")))
    (is (core-db/statement-refusal?
         (server-error "0A000" "cached plan must not change result type"))))
  (testing "a 0A000 pgjdbc raised itself is left to Hikari"
    (is (not (core-db/statement-refusal?
              (PSQLException. "Method not yet implemented" PSQLState/NOT_IMPLEMENTED)))))
  (testing "an 08P01 the server sent with an incorrect anomaly, as XTDB's driver receives it"
    (is (core-db/statement-refusal?
         (server-error "08P01" "data exception - division by zero"
                       (str "[\"~#xt/error\",[\"~:incorrect\",\"~:xtdb.expression/division-by-zero\","
                            "\"data exception - division by zero\",[\"^ \"]]]")))))
  (testing "an 08P01 pgwire raised as a protocol violation carries no anomaly"
    (is (not (core-db/statement-refusal?
              (server-error "08P01" "no such portal")))))
  (testing "an 08P01 whose anomaly is not transit, as a session left on JSON receives it"
    (is (not (core-db/statement-refusal?
              (server-error "08P01" "data exception - division by zero"
                            (str "{\"category\":\"incorrect\",\"code\":\"xtdb.expression/division-by-zero\","
                                 "\"message\":\"data exception - division by zero\"}"))))))
  (testing "an 08P01 pgjdbc raised itself is left to Hikari"
    (is (not (core-db/statement-refusal?
              (PSQLException. "Protocol error. Session setup failed." PSQLState/PROTOCOL_VIOLATION)))))
  (testing "a server error in a state that does break the connection"
    (is (not (core-db/statement-refusal?
              (server-error "57P01" "terminating connection due to administrator command")))))
  (testing "a connection that is already closed"
    (is (not (core-db/statement-refusal? (SQLException. "Connection is closed" "08003"))))))
