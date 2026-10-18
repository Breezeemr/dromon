(ns fhir-store-xtdb2.conns
  "Where a tenant's store operations get the connection they run on. Each
   operation borrows one connection, runs its `xt/q` / `xt/execute-tx` calls on
   it, and gives it back; `with-conn` does both. Three transports:

   - :pgwire      a HikariCP pool of pgwire JDBC connections (fhir-store-xtdb2.core
                  builds it; see default-pool-opts there).
   - :adbc        an in-process ADBC connection (`xt/open-adbc-conn`) on the
                  tenant's embedded node, opened per borrow. No socket, no pgwire
                  encoding: rows come back as Arrow and are decoded in-process.
   - :flight-sql  a bounded pool of FlightSQL connections
                  (`xtdb.flight-sql/open-conn`), each a session of its own on the
                  server, to an embedded node's FlightSQL port or a remote
                  server's.

   All three are `xtdb.protocols/Connectable`s, so the store's queries and
   transactions are the same SQL on every transport."
  (:require [taoensso.telemere :as t]
            [xtdb.api :as xt]
            [xtdb.flight-sql :as flight-sql]
            [xtdb.protocols :as xtp])
  (:import [clojure.lang IReduceInit]
           [java.nio.channels ClosedByInterruptException]
           [java.util.concurrent LinkedBlockingDeque Semaphore TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]
           [org.apache.arrow.flight CallOption CloseSessionRequest FlightRuntimeException]
           [org.apache.arrow.flight.sql FlightSqlClient]
           [xtdb.api Xtdb Xtdb$Connection]
           [xtdb.flight_sql FlightSqlConn]))

(defprotocol ConnSource
  (acquire [src]
    "A connection for one store operation. Hand it back with `release`.")
  (release [src conn failure]
    "Returns `conn`. `failure` is what the operation threw, or nil."))

(defmacro with-conn
  "Runs `body` with `sym` bound to a connection borrowed from `src`, returning
   it afterwards, also when `body` throws."
  [[sym src] & body]
  `(let [src# ~src
         ~sym (acquire src#)
         result# (try
                   ~@body
                   (catch Throwable e#
                     (release src# ~sym e#)
                     (throw e#)))]
     ;; Outside the try, so a release that throws is never released again.
     (release src# ~sym nil)
     result#))

;; ---------------------------------------------------------------------------
;; :adbc
;; ---------------------------------------------------------------------------

(deftype AdbcSource [^Xtdb node]
  ConnSource
  (acquire [_]
    ;; A fresh connection carries no await token. Seeding it with the node's
    ;; is what xt/q on the node itself does, so a read awaits every write
    ;; submitted through the node, including another borrow's.
    (let [^Xtdb$Connection conn (xt/open-adbc-conn node)]
      (some->> (.getAwaitToken node) (.setAwaitToken conn))
      conn))
  (release [_ conn _failure]
    (let [^Xtdb$Connection conn conn]
      (try
        (some->> (.getAwaitToken conn) (.setAwaitToken node))
        (finally (.close conn)))))

  java.lang.AutoCloseable
  ;; The node belongs to the tenant entry, which closes it after this.
  (close [_]))

(defn adbc-source
  "Borrows in-process ADBC connections on the embedded `node`. Opening one is
   an object allocation, so there is nothing to pool."
  [node]
  (when-not (instance? Xtdb node)
    (throw (ex-info "The :adbc transport needs an in-process XTDB node" {:node-class (class node)})))
  (->AdbcSource node))

;; ---------------------------------------------------------------------------
;; :flight-sql
;; ---------------------------------------------------------------------------

(def ^:private statement-categories
  "Anomaly categories the server answers ONE statement with, leaving the
   session fine: xtdb.flight-sql's client rolls back a failed transaction
   before it rethrows, and an aborted commit leaves none open."
  #{:cognitect.anomalies/incorrect :cognitect.anomalies/conflict
    :cognitect.anomalies/unsupported :cognitect.anomalies/not-found
    :cognitect.anomalies/forbidden})

(defn ^:no-doc broken-session?
  "True when `failure` says the FlightSQL session cannot be trusted with the
   next operation. The outermost anomaly in its cause chain decides: a
   statement refusal (incorrect, conflict, ...) keeps the session, anything
   else (a fault, the server unavailable, a call cancelled) does not. XTDB
   keeps the FlightRuntimeException it read as that anomaly's cause, so only
   one with no anomaly above it counts as a transport failure. So does an
   interrupt, which XTDB rethrows as is: it may have landed between BEGIN and
   COMMIT with the rollback interrupted too. A failure with none of these is
   the store's own (a 412 precondition, a 400 refusal) and never reached the
   server."
  [^Throwable failure]
  (loop [^Throwable c failure]
    (cond
      (nil? c) false
      (:cognitect.anomalies/category (ex-data c))
      (not (contains? statement-categories (:cognitect.anomalies/category (ex-data c))))
      (instance? FlightRuntimeException c) true
      (instance? InterruptedException c) true
      (instance? ClosedByInterruptException c) true
      :else (recur (ex-cause c)))))

(def ^:private no-call-opts (make-array CallOption 0))

(defn- close-flight-conn!
  "Ends the server-side session (and the connection the server holds for it)
   before closing the client: FlightSqlConn's own close only drops the channel,
   which leaves the session on the server."
  [^FlightSqlConn conn]
  (try
    (.closeSession ^FlightSqlClient (.-client conn) (CloseSessionRequest.) no-call-opts)
    (catch Throwable _ nil))
  (try (.close ^java.io.Closeable conn) (catch Throwable _ nil)))

(declare flight-plan-q)

(deftype FlightSqlPool [conn-opts ^LinkedBlockingDeque idle ^Semaphore permits
                        ^long timeout-ms ^AtomicBoolean closed]
  ConnSource
  (acquire [_]
    (when (.get closed)
      (throw (ex-info "FlightSQL pool is closed" {:dbname (:dbname conn-opts)})))
    (when-not (.tryAcquire permits timeout-ms TimeUnit/MILLISECONDS)
      (throw (ex-info (str "No FlightSQL connection free after " timeout-ms " ms")
                      {:dbname (:dbname conn-opts) :max-size (:max-size conn-opts)})))
    (try
      (or (.pollFirst idle) (flight-sql/open-conn conn-opts))
      (catch Throwable e
        (.release permits)
        (throw e))))
  (release [_ conn failure]
    (try
      (if (or (.get closed) (and failure (broken-session? failure)))
        (do (when failure
              (t/event! ::flight-sql-conn-evicted
                        {:level :debug :data {:dbname (:dbname conn-opts)
                                              :error (ex-message failure)}}))
            (close-flight-conn! conn))
        (.offerFirst idle conn))
      (finally (.release permits))))

  ;; So a remote tenant's entry can hand the pool itself out as its node:
  ;; each call borrows a session for just that call.
  xtp/Connectable
  (-plan-q [this sql args opts] (flight-plan-q this sql args opts))
  (-submit-tx [this tx-ops opts] (with-conn [c this] (xtp/-submit-tx c tx-ops opts)))
  (-execute-tx [this tx-ops opts] (with-conn [c this] (xtp/-execute-tx c tx-ops opts)))
  (-status [this] (with-conn [c this] (xtp/-status c)))

  java.lang.AutoCloseable
  (close [_]
    (when (.compareAndSet closed false true)
      (loop []
        (when-let [conn (.pollFirst idle)]
          (close-flight-conn! conn)
          (recur))))))

(defn- flight-plan-q [pool sql args opts]
  (reify IReduceInit
    (reduce [_ f start]
      (with-conn [c pool]
        (reduce f start (xtp/-plan-q c sql args opts))))))

(defn flight-sql-pool
  "A pool of at most :max-size FlightSQL sessions on database :dbname at
   :host/:port, opened on first borrow and kept for reuse. A borrow waits
   :connection-timeout-ms for a free one. A session is closed instead of
   reused when its operation failed in a way that may have broken it (see
   broken-session?)."
  [{:keys [host port dbname max-size connection-timeout-ms]
    :or {host "127.0.0.1" dbname "xtdb"}}]
  (when-not port
    (throw (ex-info "The :flight-sql transport needs the server's FlightSQL port" {:host host})))
  (->FlightSqlPool {:host host :port port :dbname dbname :max-size max-size}
                   (LinkedBlockingDeque.)
                   (Semaphore. (int max-size) true)
                   (long connection-timeout-ms)
                   (AtomicBoolean. false)))
