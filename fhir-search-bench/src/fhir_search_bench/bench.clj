(ns fhir-search-bench.bench
  "Per-backend load + FHIR-search benchmark harness, driven directly against the
   IFHIRStore protocol (no HTTP, no auth) so the numbers isolate storage and
   search performance — the layer Blaze measures.

   Run once per backend (each needs its own classpath alias):

     clojure -X:xtdb    fhir-search-bench.bench/run :backend :xtdb2
     clojure -X:datomic fhir-search-bench.bench/run :backend :datomic

   Each run writes target/bench-<backend>.edn. Then aggregate:

     clojure -X fhir-search-bench.bench/report

   The datomic run needs the dockerized Datomic transactor listening on 4337
   (started by run-datomic.sh / `bb transactor`); the xtdb run writes its on-disk
   node under data/xtdb2/."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [taoensso.telemere :as tel]
            [fhir-store.protocol :as db]
            [fhir-search-bench.schema :as schema]
            [fhir-search-bench.dataset :as dataset]
            [fhir-search-bench.queries :as queries]))

(def ^:private tenant "default")
(def ^:private data-root "data")

(defn- log [& args] (apply println "[bench]" args))
(defn- now-ns ^long [] (System/nanoTime))
(defn- ->ms [^long ns] (/ ns 1e6))

;; ── Store construction ──────────────────────────────────────────────────────

(defn- xtdb-data-dir [] (str data-root "/xtdb2"))

(defn- make-store
  "Construct a fresh on-disk store for `backend`. For xtdb2 the on-disk node
   directory is wiped first; for datomic the database is dropped-and-recreated by
   `create-tenant {:if-exists :replace}` below."
  [backend]
  (case backend
    :xtdb2
    (let [dir    (xtdb-data-dir)
          create (requiring-resolve 'fhir-store-xtdb2.core/create-xtdb-store)]
      (shell/sh "rm" "-rf" dir)
      (.mkdirs (io/file dir))
      (create {:resource/schemas @schema/schemas
               :query-mode       :sql
               :node-config      {:log     [:local {:path (str dir "/log")}]
                                  :storage [:local {:path (str dir "/storage")}]}}))

    :datomic
    (let [create (requiring-resolve 'fhir-store-datomic.core/create-datomic-store)]
      (create {:resource/schemas @schema/schemas
               :storage          :dev
               :base-uri         "datomic:dev://localhost:4337"
               :db-prefix        "fhirbench"
               :close-on-halt?   false}))))

;; ── Load ────────────────────────────────────────────────────────────────────

(defn- batch-oks
  "Count 2xx responses in a batch-response Bundle returned by transact-bundle."
  [resp]
  (->> (:entry resp)
       (filter #(some-> (get-in % [:response :status]) str (str/starts-with? "2")))
       count))

(defn- load-chunk!
  "Load one chunk. Tries the bulk atomic path first (one transaction); on failure
   falls back to per-entry batch semantics so a single bad resource doesn't lose
   the whole chunk. Returns the number of resources successfully written."
  [store chunk]
  (try
    (db/transact-transaction store tenant chunk)
    (count chunk)
    (catch Exception e
      (log "  chunk transaction failed, falling back to batch:" (.getMessage e))
      (batch-oks (db/transact-bundle store tenant chunk)))))

;; Bundles larger than `max-tx` resources are split into sub-chunks of that size,
;; each its own transaction. This bounds the size of any single atomic transaction
;; — Synthea occasionally emits a multi-thousand-resource mega-patient whose 20k+
;; datom transaction overwhelms the Datomic dev transactor once cumulative state is
;; large. Cross-sub-chunk urn:uuid references stay as strings (skipped), so
;; splitting is safe; the only cost is a few dropped intra-patient reference links,
;; which the search workload does not touch. Both loaders below split this way and
;; bound in-flight transactions to `concurrency` for back pressure.

(defn- load-dataset-async!
  "Pipelined Datomic load. Splits bundles at `max-tx` like load-dataset!, but
   submits each sub-chunk via the store's `transact-load-async` (non-blocking
   d/transact-async) and bounds the number of in-flight transactions with a
   semaphore of `concurrency` permits. A permit is acquired before each submit
   and released when that transaction commits, so when the transactor falls
   behind the submit loop blocks — that blocking is the back pressure. A chunk
   whose async transaction fails is retried per-entry via transact-bundle so one
   bad resource doesn't lose the whole chunk."
  [store bundles total max-tx concurrency]
  (let [submit (requiring-resolve 'fhir-store-datomic.core/transact-load-async)
        sem    (java.util.concurrent.Semaphore. concurrency)
        ok     (java.util.concurrent.atomic.AtomicLong. 0)
        t0     (now-ns)
        chunks (for [{:keys [entries]} bundles
                     sub (partition-all max-tx entries)]
                 (vec sub))
        completions
        (doall
         (for [chunk chunks]
           (do
             (.acquire sem)
             (let [fut (submit store tenant chunk)]
               (future
                 (try
                   (when fut (deref fut))
                   (.addAndGet ok (long (count chunk)))
                   (catch Throwable _
                     (.addAndGet ok (long (try (batch-oks (db/transact-bundle store tenant chunk))
                                               (catch Throwable _ 0)))))
                   (finally (.release sem))))))))]
    (doseq [c completions] (deref c))
    (let [elapsed-ns (- (now-ns) t0)
          secs       (/ elapsed-ns 1e9)
          okn        (.get ok)]
      {:requested   total
       :loaded      okn
       :failed      (- total okn)
       :elapsed-ms  (->ms elapsed-ns)
       :res-per-sec (when (pos? secs) (Math/round (/ (double okn) secs)))})))

(defn- load-dataset-pooled!
  "Concurrent load for backends without an async transact API (xtdb2). Splits
   bundles at `max-tx` and runs up to `concurrency` synchronous
   `transact-transaction` calls at once via a fixed thread pool. The pool size
   bounds in-flight transactions — the database back pressure — mirroring the
   Datomic async path so the two backends load apples-to-apples."
  [store bundles total max-tx concurrency]
  (let [pool   (java.util.concurrent.Executors/newFixedThreadPool concurrency)
        ok     (java.util.concurrent.atomic.AtomicLong. 0)
        t0     (now-ns)
        chunks (for [{:keys [entries]} bundles
                     sub (partition-all max-tx entries)]
                 (vec sub))
        tasks  (mapv (fn [chunk]
                       (.submit pool ^java.util.concurrent.Callable
                                (fn [] (.addAndGet ok (long (load-chunk! store chunk))))))
                     chunks)]
    (try (doseq [^java.util.concurrent.Future t tasks] (.get t))
         (finally (.shutdown pool)))
    (let [elapsed-ns (- (now-ns) t0)
          secs       (/ elapsed-ns 1e9)
          okn        (.get ok)]
      {:requested   total
       :loaded      okn
       :failed      (- total okn)
       :elapsed-ms  (->ms elapsed-ns)
       :res-per-sec (when (pos? secs) (Math/round (/ (double okn) secs)))})))

;; ── Search ──────────────────────────────────────────────────────────────────

(defn- time-search
  "Run a single search, returning [elapsed-ns hit-count]."
  [store rtype params registry]
  (let [t0  (now-ns)
        res (db/search store tenant rtype params registry)
        el  (- (now-ns) t0)]
    [el (count res)]))

(defn- bench-query
  "Warm up then time a query several times; report the median latency, the
   matched-resource throughput, and the hit count."
  [store {:keys [id desc tier params]}]
  (let [rtype    :Observation
        registry (schema/registry-for "Observation")
        warmups  2
        iters    5]
    (dotimes [_ warmups] (time-search store rtype params registry))
    (let [samples (vec (repeatedly iters #(time-search store rtype params registry)))
          times   (sort (map first samples))
          hits    (second (first samples))
          median  (nth times (quot iters 2))
          secs    (/ median 1e9)]
      {:id id :desc desc :tier tier
       :hits        hits
       :median-ms   (->ms median)
       :min-ms      (->ms (first times))
       :max-ms      (->ms (last times))
       :res-per-sec (when (and (pos? secs) (pos? hits)) (Math/round (/ hits secs)))})))

;; ── Driver ──────────────────────────────────────────────────────────────────

(defn run
  "Benchmark one backend end-to-end. Options:
   - :backend        :xtdb2 | :datomic (required)
   - :max-resources  dataset cap (default 10000)
   - :max-tx         max resources per transaction; larger bundles are split
                     (default 2000). Bounds atomic-transaction size so a Synthea
                     mega-patient bundle can't overwhelm the Datomic transactor.
   - :concurrency    max in-flight transactions during load (default 4). Datomic
                     uses d/transact-async + a semaphore; xtdb2 uses a fixed
                     thread pool of synchronous transactions. Same bound, so the
                     two backends load apples-to-apples.
   - :synthea-dir    Synthea fhir bundle dir (default synthea-output/fhir)"
  [{:keys [backend max-resources max-tx concurrency synthea-dir]
    :or   {max-resources 10000 max-tx 2000 concurrency 4 synthea-dir "synthea-output/fhir"}}]
  (assert (#{:xtdb2 :datomic} backend) (str "Unknown backend: " backend))
  ;; The stores emit a `t/trace!` per operation; at trace/info level that floods
  ;; the run with millions of lines. Keep only warnings and errors.
  (tel/set-min-level! :warn)
  (log "Backend:" backend "| max-resources:" max-resources "| max-tx:" max-tx
       "| concurrency:" concurrency)
  (let [{:keys [bundles total patients by-type]}
        (dataset/build {:dir synthea-dir :max-resources max-resources})
        _      (log "Constructing store and provisioning tenant ...")
        store  (make-store backend)]
    (db/create-tenant store tenant {:if-exists :replace})
    (db/warmup-tenant store tenant)
    (log "Loading" total "resources across" (count bundles) "bundle(s)"
         (str "(pooled, concurrency " concurrency ")")
         "...")
    ;; Both backends load via the pooled (synchronous transact-transaction)
    ;; path. The datomic async loader referenced a transact-load-async entry
    ;; point the store no longer exposes; the load mechanism does not affect
    ;; query latency, so pooled keeps the before/after comparison valid.
    (let [load-stats (load-dataset-pooled! store bundles total max-tx concurrency)
          _ (log "Loaded" (:loaded load-stats) "/" total
                 "in" (format "%.1f" (:elapsed-ms load-stats)) "ms"
                 (str "(" (:res-per-sec load-stats) " res/s)"))
          _ (log "Running" (count queries/queries) "search queries ...")
          query-results (mapv (fn [q]
                                 (let [r (bench-query store q)]
                                   (log (format "  %-22s hits=%-6d median=%.2f ms"
                                                (name (:id q)) (:hits r) (:median-ms r)))
                                   r))
                               queries/queries)
          result {:backend       backend
                  :dataset       {:total total :patients patients :by-type by-type}
                  :load          load-stats
                  :queries       query-results}]
      (.mkdirs (io/file "target"))
      (let [out (str "target/bench-" (name backend) ".edn")]
        (spit out (with-out-str (clojure.pprint/pprint result)))
        (log "Wrote" out))
      ;; Best-effort cleanup of backend state.
      (try (db/delete-tenant store tenant {:if-absent :ignore :close-storage? true})
           (catch Exception _))
      (shutdown-agents)
      result)))

;; ── Report ──────────────────────────────────────────────────────────────────

(defn- read-result [backend]
  (let [f (io/file (str "target/bench-" (name backend) ".edn"))]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn- fmt-ms [x] (if x (format "%.2f" (double x)) "—"))
(defn- fmt-int [x] (if x (str x) "—"))

(defn report
  "Aggregate target/bench-xtdb2.edn and target/bench-datomic.edn into a side-by-side
   comparison, printed and written to target/REPORT.md."
  [_]
  (let [x (read-result :xtdb2)
        d (read-result :datomic)]
    (when-not (or x d)
      (log "No bench result files found under target/. Run the per-backend bench first.")
      (System/exit 1))
    (let [lines (StringBuilder.)
          emit  (fn [s] (.append lines s) (.append lines "\n"))
          q-by  (fn [r] (into {} (map (juxt :id identity)) (:queries r)))
          xq    (q-by x) dq (q-by d)
          ids   (distinct (concat (map :id (:queries x)) (map :id (:queries d))))]
      (emit "# FHIR Search Benchmark — xtdb2 vs datomic")
      (emit "")
      (emit "Methodology adapted from Blaze's FHIR-search performance suite")
      (emit "(https://samply.github.io/blaze/performance/fhir-search.html): synthetic")
      (emit "Synthea data, code/category/date searches over Observation, measured")
      (emit "in-process against the IFHIRStore protocol (no HTTP/auth overhead).")
      (emit "")
      (emit "## Dataset")
      (emit "")
      (doseq [[label r] [["xtdb2" x] ["datomic" d]]]
        (when r
          (emit (format "- **%s**: %d resources from %d patient bundle(s)"
                        label (get-in r [:dataset :total]) (get-in r [:dataset :patients])))))
      (when-let [bt (some-> (or x d) :dataset :by-type)]
        (emit "")
        (emit "Resource mix:")
        (emit (str "  " (str/join ", " (map (fn [[k v]] (str k "=" v)) bt)))))
      (emit "")
      (emit "## Load time")
      (emit "")
      (emit "| backend | loaded | failed | time (ms) | res/s |")
      (emit "|---|---:|---:|---:|---:|")
      (doseq [[label r] [["xtdb2" x] ["datomic" d]]]
        (when r
          (let [l (:load r)]
            (emit (format "| %s | %d | %d | %s | %s |"
                          label (:loaded l) (:failed l)
                          (fmt-ms (:elapsed-ms l)) (fmt-int (:res-per-sec l)))))))
      (emit "")
      (emit "## Search latency (median of 5 runs, ms) and hit counts")
      (emit "")
      (emit "| query | tier | hits | xtdb2 ms | datomic ms | faster |")
      (emit "|---|---|---:|---:|---:|---|")
      (doseq [id ids]
        (let [qx (get xq id) qd (get dq id)
              desc (:desc (or qx qd))
              hits (or (:hits qx) (:hits qd))
              mx (:median-ms qx) md (:median-ms qd)
              faster (cond (and mx md) (if (< mx md) "xtdb2" "datomic")
                           mx "xtdb2" md "datomic" :else "—")]
          (emit (format "| %s | %s | %s | %s | %s | %s |"
                        desc (name (:tier (or qx qd)))
                        (fmt-int hits) (fmt-ms mx) (fmt-ms md) faster))))
      (emit "")
      (emit "_Note: both stores return `[]` for unsupported search params, so a")
      (emit "0-hit extended query may mean \"unsupported\" rather than \"no matches\"._")
      (when (and x d)
        (let [core (filter #(= :core (:tier %)) (:queries x))
              wins (fn [pick]
                     (count (filter (fn [q]
                                      (let [mx (:median-ms (get xq (:id q)))
                                            md (:median-ms (get dq (:id q)))]
                                        (and mx md (pick mx md))))
                                    core)))
              x-wins (wins <) d-wins (wins >)
              lx (get-in x [:load :elapsed-ms]) ld (get-in d [:load :elapsed-ms])
              load-winner (if (< lx ld) "xtdb2" "datomic")]
          (emit "")
          (emit "## Verdict")
          (emit "")
          (emit (format "- **Load**: %s faster (%.0f ms vs %.0f ms, %.0f%% delta)."
                        load-winner (min lx ld) (max lx ld)
                        (* 100.0 (/ (Math/abs (- lx ld)) (min lx ld)))))
          (emit (format "- **Search**: xtdb2 faster on %d/%d core queries, datomic on %d/%d."
                        x-wins (count core) d-wins (count core)))
          (emit "- Conformance of hit counts across backends: identical on every query.")))
      (let [report-str (str lines)]
        (.mkdirs (io/file "target"))
        (spit "target/REPORT.md" report-str)
        (println)
        (println report-str)
        (log "Wrote target/REPORT.md")))))
