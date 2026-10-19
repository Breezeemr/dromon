(ns fhir-search-bench.flightsql-repro
  "Focused reproductions of three XTDB FlightSQL bugs against a running
   standalone server (`bb flightsql-repro-nightly` starts one). Rows are
   written over pgwire, then read back over pgwire and FlightSQL; pgwire is
   the reference.

     clojure -M:xtdb -m fhir-search-bench.flightsql-repro <group> <pgwire-port> <flight-sql-port> [race-secs]

   Groups:

   - layout  bug 1: a page shaped differently from the declared result type
             once blocks have flushed (\"no more buffers / no more field
             nodes for field ...\"). Bug 2, silent variant: a struct written
             with the same fields in another key order comes back with its
             strings swapped.
   - crash   bug 2 with an f64 among the reordered fields: the server sends
             f64 bytes as Utf8 offsets, and the client decodes them without
             bounds checks (wrong values, or a client JVM SIGSEGV).
   - race    bug 3: concurrent FlightSQL streams corrupt each other through
             Arrow's JVM-wide empty buffer. The controls are the same reads
             over pgwire and over a single FlightSQL stream. The rows stay in
             the live index and are written in one key order, so bugs 1 and 2
             cannot cause these failures.

   layout and crash need a server with `indexer.rowsPerBlock: 20`
   (docker/xtdb-flightsql/repro.yaml), race one that flushes no block
   (docker/xtdb-flightsql/bench.yaml); each on a fresh, empty log.
   Each group runs in its own JVM, so a client crash ends only that group.
   Each case prints one `CASE <id> PASS|FAIL <detail>` line; a case that may
   crash the JVM prints `CASE-START <id>` first."
  (:require [clojure.set :as set]
            [xtdb.api :as xt]
            [xtdb.flight-sql :as fsql])
  (:import (java.util.concurrent Callable ConcurrentHashMap Executors TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(defn- report! [id pass? detail]
  (println "CASE" (name id) (if pass? "PASS" "FAIL") detail)
  (flush))

(defn- start! [id]
  (println "CASE-START" (name id))
  (flush))

(defn- error-text [^Throwable e]
  (let [msg (or (some ex-message (take-while some? (iterate ex-cause e))) "")
        s   (str (.getSimpleName (class e)) ": " msg)]
    (subs s 0 (min 300 (count s)))))

(defn- compare-read!
  "Read `sql` over pgwire and over a fresh FlightSQL session; PASS when the
   two row sets are equal."
  [id pg flight-port sql]
  (let [want (set (xt/q pg [sql]))
        got  (try (with-open [fs (fsql/open-conn {:port flight-port})]
                    (set (xt/q fs [sql])))
                  (catch Throwable e e))]
    (cond
      (instance? Throwable got)
      (report! id false (str "FlightSQL error: " (error-text got)))

      (= want got)
      (report! id true (str (count want) " row(s), equal to pgwire"))

      :else
      (report! id false (str "wrong values: pgwire " (pr-str (first (set/difference want got)))
                             " FlightSQL " (pr-str (first (set/difference got want))))))))

(defn- layout! [pg flight-port]
  ;; 50 rows flush to blocks with vq {value, unit} and v a long; the live
  ;; row has vq {value} and v a string.
  (doseq [i (range 50)]
    (xt/execute-tx pg [[:sql (str "INSERT INTO b1_obs RECORDS {_id: 'f" i "', vq: {value: 1.0, unit: 'mg'}, v: 1}")]]))
  (xt/execute-tx pg [[:sql "INSERT INTO b1_obs RECORDS {_id: 'live', vq: {value: 2.0}, v: 'str'}"]])
  (compare-read! :bug1-live-row-lacks-struct-field pg flight-port "SELECT * FROM b1_obs WHERE _id = 'live'")
  (compare-read! :bug1-flushed-row-long-string-union pg flight-port "SELECT * FROM b1_obs WHERE _id = 'f1'")
  (compare-read! :bug1-select-star pg flight-port "SELECT * FROM b1_obs")
  ;; The first FlightSQL read of a field set fixes its declared order for the
  ;; server JVM; the second table writes the same fields in another order.
  (xt/execute-tx pg [[:sql "INSERT INTO b2_first RECORDS {_id: 's1', s: {b2_alpha: 'A', b2_bravo: 'B', b2_charlie: 'C'}}"]])
  (compare-read! :bug2-first-key-order pg flight-port "SELECT _id, s FROM b2_first")
  (xt/execute-tx pg [[:sql "INSERT INTO b2_second RECORDS {_id: 's1', s: {b2_charlie: 'C', b2_alpha: 'A', b2_bravo: 'B'}}"]])
  (compare-read! :bug2-second-key-order-strings pg flight-port "SELECT _id, s FROM b2_second"))

(defn- crash! [pg flight-port]
  (xt/execute-tx pg [[:sql "INSERT INTO b2x_first RECORDS {_id: 'o1', q: {b2x_system: 'SYSTEM', b2x_unit: 'UNIT', b2x_value: 60.0, b2x_code: 'CODE'}}"]])
  (compare-read! :bug2-mixed-first-key-order pg flight-port "SELECT * FROM b2x_first")
  (xt/execute-tx pg [[:sql "INSERT INTO b2x_second RECORDS {_id: 'o1', q: {b2x_value: 60.0, b2x_unit: 'UNIT', b2x_system: 'SYSTEM', b2x_code: 'CODE'}}"]])
  (start! :bug2-mixed-second-key-order-f64)
  (compare-read! :bug2-mixed-second-key-order-f64 pg flight-port "SELECT _id, q FROM b2x_second WHERE TRUE"))

(def ^:private race-rows 200)

(defn- race-record
  "One Observation-shaped row. Every third row carries `component`, a list of
   structs that holds a list: for the other rows a page's inner list vector
   has no elements, and Arrow unloads its offsets from the shared empty
   buffer, which is what the concurrent streams then race on."
  [i]
  (str "{_id: 'r" i "', status: 'final',"
       " code: {coding: [{system: 'http://loinc.org', code: '8867-4', display: 'Heart rate'}], label: 'Heart rate'},"
       " subject: {reference: 'Patient/p1'},"
       " rq: {system: 'http://unitsofmeasure.org', unit: '/min', value: " (+ 60.5 i) ", code: '/min'}"
       (when (zero? (mod i 3))
         ", component: [{code: {coding: [{system: 'http://loinc.org', code: '8480-6'}]}, cq: {unit: 'mm[Hg]', value: 120.5}}]")
       "}"))

(defn- read-storm
  "`threads` threads re-read random rows for `secs` seconds, each through its
   own connection from `open-conn` (reopened after a failure). Counts reads
   that throw or differ from `expected`. Prints each new kind of failure
   (`CASE-NOTE <id> ...`) and the counts every two seconds
   (`CASE-PROGRESS <id> ...`), so a client crash does not lose them."
  [id open-conn expected threads secs]
  (let [deadline (+ (System/currentTimeMillis) (* 1000 secs))
        reads    (AtomicLong.)
        bad      (AtomicLong.)
        msgs     (ConcurrentHashMap.)
        pool     (Executors/newFixedThreadPool threads)
        note!    (fn [m]
                   (when (and (< (.size msgs) 6) (nil? (.putIfAbsent msgs m true)))
                     (println "CASE-NOTE" (name id) m)
                     (flush)))
        connect  (fn [] (try (open-conn)
                             (catch Throwable e
                               (.incrementAndGet bad)
                               (note! (str "connect: " (error-text e)))
                               (Thread/sleep 100)
                               nil)))]
    (dotimes [_ threads]
      (.submit pool ^Callable
               (fn []
                 (loop [conn (connect)]
                   (cond
                     (>= (System/currentTimeMillis) deadline)
                     nil

                     (nil? conn)
                     (recur (connect))

                     :else
                     (let [id (str "r" (rand-int race-rows))
                           ok (try
                                (let [rows (xt/q conn ["SELECT * FROM b3_obs WHERE _id = ?" id])]
                                  (or (= [(expected id)] rows)
                                      (do (note! (str "wrong row " (pr-str (first rows)))) false)))
                                (catch Throwable e (note! (error-text e)) ::error))]
                       (.incrementAndGet reads)
                       (when-not (true? ok) (.incrementAndGet bad))
                       (if (= ::error ok)
                         (do (when (instance? java.io.Closeable conn)
                               (try (.close ^java.io.Closeable conn) (catch Throwable _)))
                             (recur (connect)))
                         (recur conn))))))))
    (.shutdown pool)
    (let [give-up (+ deadline 120000)]
      (while (and (not (.awaitTermination pool 2 TimeUnit/SECONDS))
                  (< (System/currentTimeMillis) give-up))
        (println "CASE-PROGRESS" (name id) (.get bad) "bad of" (.get reads) "reads"
                 (if (.isEmpty msgs) "" (str "; e.g. " (pr-str (vec (keys msgs))))))
        (flush)))
    {:reads (.get reads) :bad (.get bad) :messages (vec (keys msgs))}))

(defn- race! [pg flight-port secs]
  (doseq [batch (partition-all 20 (range race-rows))]
    (xt/execute-tx pg [[:sql (str "INSERT INTO b3_obs RECORDS " (apply str (interpose ", " (map race-record batch))))]]))
  (let [expected (into {} (map (juxt :xt/id identity)) (xt/q pg ["SELECT * FROM b3_obs"]))
        storm!   (fn [id threads open-conn]
                   (start! id)
                   (let [{:keys [reads bad messages]} (read-storm id open-conn expected threads secs)]
                     (report! id (and (pos? reads) (zero? bad))
                              (str threads " thread(s) x " secs " s: " bad " bad of " reads " reads"
                                   (when (seq messages) (str "; e.g. " (pr-str messages)))))))
        flight   #(fsql/open-conn {:port flight-port})]
    ;; One FlightSQL reader shows the table itself streams correctly, so
    ;; failures with eight are the race rather than bug 1 or 2.
    (storm! :bug3-control-pgwire-concurrent-reads 8 (constantly pg))
    (storm! :bug3-control-flight-sql-one-reader 1 flight)
    (storm! :bug3-flight-sql-concurrent-reads 8 flight)))

(defn -main [group pg-port flight-port & [race-secs]]
  (let [pg          (xt/client {:host "127.0.0.1" :port (parse-long pg-port) :dbname "xtdb"})
        flight-port (parse-long flight-port)]
    (case group
      "layout" (layout! pg flight-port)
      "crash"  (crash! pg flight-port)
      "race"   (race! pg flight-port (parse-long (or race-secs "20"))))
    (shutdown-agents)
    (System/exit 0)))
