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
  (:require [clojure.string :as str]
            [taoensso.telemere :as t]
            [xtdb.api :as xt]
            [xtdb.flight-sql :as flight-sql]
            [xtdb.protocols :as xtp]
            [xtdb.serde :as serde])
  (:import [clojure.lang IReduceInit]
           [java.nio.channels ClosedByInterruptException]
           [java.util.concurrent LinkedBlockingDeque Semaphore TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]
           [org.apache.arrow.flight CallOption CloseSessionRequest FlightRuntimeException]
           [org.apache.arrow.flight.sql FlightSqlClient]
           [xtdb.api Xtdb Xtdb$Connection]
           [xtdb.api.query IKeyFn]
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

;; XTDB 2.2.0-beta3's FlightSQL server streams a query by loading each result
;; page into an Arrow root of the query's declared result type with
;; VectorLoader, which matches buffers by position, not by name. A page that
;; lacks a struct field or union leg the declared type has fails with "no more
;; buffers for field ..." (typical once blocks have flushed); a page whose
;; struct fields come in another order than the declared type's is sent with
;; each field's buffers under another field's name (wrong values, or a client
;; JVM crash decoding them). A sort at the top of the plan copies every row, by
;; name, into vectors of the declared type before that load, so each page
;; leaves the cursor in the declared layout. ROW_NUMBER() OVER () keeps the
;; query's own row order through that sort.

(def ^:private row-number-col
  "The column a materialized read sorts on; stripped from every row."
  "_xt_flight_row")

(defn- materializable? [sql]
  (boolean (and (string? sql) (re-find #"(?i)^\s*(SELECT|WITH)\b" sql))))

(defn ^:no-doc materialized-sql
  "`sql`, a query, wrapped so the server sorts its rows into the declared
   result layout before streaming them (see above)."
  [sql]
  (format "SELECT * FROM (SELECT *, ROW_NUMBER() OVER () AS %s FROM (%s) AS _xt_flight_rows) AS _xt_flight_sorted ORDER BY %s"
          row-number-col (str/replace sql #"[\s;]+$" "") row-number-col))

(deftype MaterializingSession [^FlightSqlConn conn]
  xtp/Connectable
  (-plan-q [_ sql args opts]
    (if (materializable? sql)
      (let [row-key (.denormalize ^IKeyFn (serde/read-key-fn (or (:key-fn opts) :kebab-case-keyword))
                                  row-number-col)
            plan (xtp/-plan-q conn (materialized-sql sql) args opts)]
        (reify IReduceInit
          (reduce [_ f start]
            (reduce (fn [acc row] (f acc (dissoc row row-key))) start plan))))
      (xtp/-plan-q conn sql args opts)))
  (-submit-tx [_ tx-ops opts] (xtp/-submit-tx conn tx-ops opts))
  (-execute-tx [_ tx-ops opts] (xtp/-execute-tx conn tx-ops opts))
  (-status [_] (xtp/-status conn)))

(defn- close-flight-conn!
  "Ends the server-side session (and the connection the server holds for it)
   before closing the client: FlightSqlConn's own close only drops the channel,
   which leaves the session on the server."
  [^MaterializingSession session]
  (let [^FlightSqlConn conn (.-conn session)]
    (try
      (.closeSession ^FlightSqlClient (.-client conn) (CloseSessionRequest.) no-call-opts)
      (catch Throwable _ nil))
    (try (.close ^java.io.Closeable conn) (catch Throwable _ nil))))

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
      (or (.pollFirst idle) (->MaterializingSession (flight-sql/open-conn conn-opts)))
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
