(ns fhir-search-bench.bench
  "Per-backend load + FHIR-search benchmark harness, driven directly against the
   IFHIRStore protocol (no HTTP, no auth) so the numbers isolate storage and
   search performance — the layer Blaze measures.

   dromon benchmarks its own store, xtdb2:

     clojure -X:xtdb fhir-search-bench.bench/run :backend :xtdb2

   which writes its on-disk node under data/xtdb2/ and its result to
   target/bench-xtdb2.edn. Any other IFHIRStore runs through the same harness
   from its own project, passing `:store-fn`, a qualified symbol naming a
   no-argument function that answers a fresh store (master-at-arms2's
   dromon-datomic does this for Datomic). `report` compares two result files:

     clojure -X fhir-search-bench.bench/report :b '\"<other>/target/bench-datomic.edn\"'"
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
  "Construct a fresh store: `(store-fn)` when given, else the built-in xtdb2
   store, whose on-disk node directory is wiped first."
  [backend store-fn]
  (cond
    store-fn
    ((requiring-resolve store-fn))

    (= :xtdb2 backend)
    (let [dir    (xtdb-data-dir)
          create (requiring-resolve 'fhir-store-xtdb2.core/create-xtdb-store)]
      (shell/sh "rm" "-rf" dir)
      (.mkdirs (io/file dir))
      (create {:resource/schemas @schema/schemas
               :query-mode       :sql
               :node-config      {:log     [:local {:path (str dir "/log")}]
                                  :storage [:local {:path (str dir "/storage")}]}}))

    :else
    (throw (ex-info (str "Unknown backend " backend "; pass :store-fn for a store outside dromon")
                    {:backend backend}))))

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
;; -- Synthea occasionally emits a multi-thousand-resource mega-patient whose
;; transaction is far larger than any real write. Cross-sub-chunk urn:uuid
;; references stay as strings (skipped), so splitting is safe; the only cost is a
;; few dropped intra-patient reference links, which the search workload does not
;; touch. In-flight transactions are bounded by `concurrency` for back pressure.

(defn- load-dataset-pooled!
  "Concurrent load. Splits bundles at `max-tx` and runs up to `concurrency`
   synchronous `transact-transaction` calls at once via a fixed thread pool. The
   pool size bounds in-flight transactions, the database back pressure, the same
   for every store so their loads compare."
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

(def ^:private page-cap
  "Page projection captured per query for cross-backend comparison. Bounds the
   edn size: full-set queries return tens of thousands of resources, but only
   the first page is worth comparing (and the limited variants return <= 50)."
  50)

(defn- time-search
  "Run a single search, returning [elapsed-ns result-vector]."
  [store rtype params registry]
  (let [t0  (now-ns)
        res (db/search store tenant rtype params registry)
        el  (- (now-ns) t0)]
    [el res]))

;; The dataset is loaded by POST, so each store assigns its own server-local
;; ids -- result ids are NOT comparable across backends. The sort key
;; (Observation.effectiveDateTime) IS stable, so the bench captures the page's
;; date sequence: for a -date sort both backends must return the same dates in
;; the same order (the specific resources within an equal-date tie can differ,
;; since the id tiebreak runs over store-local ids).
(defn- page-dates [res]
  (mapv :effectiveDateTime (take page-cap res)))

(defn- bench-query
  "Warm up then time a query several times; report the median latency, the
   matched-resource throughput, the hit count, and the first page's
   effectiveDateTime sequence (for cross-backend ordering comparison)."
  [store {:keys [id desc tier params limited? sorted?]}]
  (let [rtype    :Observation
        registry (schema/registry-for "Observation")
        warmups  2
        iters    5]
    (dotimes [_ warmups] (time-search store rtype params registry))
    (let [samples (vec (repeatedly iters #(time-search store rtype params registry)))
          times   (sort (map first samples))
          res0    (second (first samples))
          hits    (count res0)
          dates   (page-dates res0)
          median  (nth times (quot iters 2))
          secs    (/ median 1e9)]
      {:id id :desc desc :tier tier
       :limited?    (boolean limited?)
       :sorted?     (boolean sorted?)
       :hits        hits
       :page-dates  dates
       :median-ms   (->ms median)
       :min-ms      (->ms (first times))
       :max-ms      (->ms (last times))
       :res-per-sec (when (and (pos? secs) (pos? hits)) (Math/round (/ hits secs)))})))

;; ── Driver ──────────────────────────────────────────────────────────────────

(defn run
  "Benchmark one backend end-to-end. Options:
   - :backend        result label, e.g. :xtdb2 (required); names the result file
   - :store-fn       qualified symbol of a no-arg fn answering a fresh store, for
                     a store outside dromon (default: the built-in xtdb2 store)
   - :max-resources  dataset cap (default 10000)
   - :max-tx         max resources per transaction; larger bundles are split
                     (default 2000). Bounds atomic-transaction size so a Synthea
                     mega-patient bundle can't dominate the load.
   - :concurrency    max in-flight transactions during load (default 4), the
                     same bound for every store so loads compare.
   - :synthea-dir    Synthea fhir bundle dir (default synthea-output/fhir)"
  [{:keys [backend store-fn max-resources max-tx concurrency synthea-dir]
    :or   {max-resources 10000 max-tx 2000 concurrency 4 synthea-dir "synthea-output/fhir"}}]
  (when-not (keyword? backend)
    (throw (ex-info "Pass :backend, the result label (e.g. :xtdb2)" {:backend backend})))
  ;; The stores emit a `t/trace!` per operation; at trace/info level that floods
  ;; the run with millions of lines. Keep only warnings and errors.
  (tel/set-min-level! :warn)
  (log "Backend:" backend "| max-resources:" max-resources "| max-tx:" max-tx
       "| concurrency:" concurrency)
  (let [{:keys [bundles total patients by-type]}
        (dataset/build {:dir synthea-dir :max-resources max-resources})
        _      (log "Constructing store and provisioning tenant ...")
        store  (make-store backend store-fn)]
    (db/create-tenant store tenant {:if-exists :replace})
    (db/warmup-tenant store tenant)
    (log "Loading" total "resources across" (count bundles) "bundle(s)"
         (str "(pooled, concurrency " concurrency ")")
         "...")
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

(defn- read-result [path]
  (let [f (io/file path)]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn- fmt-ms [x] (if x (format "%.2f" (double x)) "—"))
(defn- fmt-int [x] (if x (str x) "—"))

(defn- result-match
  "Compares one query's result across backends. Hit counts must match for every
   query. For sorted variants the page is ordered by effectiveDateTime, so both
   backends must return the same date sequence (ids are store-local POST
   assignments and intentionally not compared). Limit-only (unsorted) pages are
   an arbitrary slice, so only the hit count is asserted. Returns a short label."
  [qa qb]
  (cond
    (not (and qa qb))            "—"
    (not= (:hits qa) (:hits qb)) (format "HITS DIFFER %d/%d" (:hits qa) (:hits qb))
    (:sorted? (or qa qb))        (if (= (:page-dates qa) (:page-dates qb))
                                   "page order match" "PAGE ORDER DIFF")
    (:limited? (or qa qb))       "page size match"
    :else                        "hits match"))

(defn report
  "Compare two bench result files side by side, printed and written to
   target/REPORT.md. `:a` defaults to target/bench-xtdb2.edn; `:b` is the other
   store's result, e.g. dromon-datomic's target/bench-datomic.edn. Either may be
   absent, and the report then covers the one present."
  [{:keys [a b] :or {a "target/bench-xtdb2.edn"}}]
  (let [ra (read-result a)
        rb (when b (read-result b))]
    (when-not (or ra rb)
      (log "No bench result files found. Run the per-backend bench first.")
      (System/exit 1))
    (let [la    (some-> ra :backend name)
          lb    (some-> rb :backend name)
          lines (StringBuilder.)
          emit  (fn [s] (.append lines s) (.append lines "\n"))
          q-by  (fn [r] (into {} (map (juxt :id identity)) (:queries r)))
          aq    (q-by ra) bq (q-by rb)
          ids   (distinct (concat (map :id (:queries ra)) (map :id (:queries rb))))]
      (emit (str "# FHIR Search Benchmark — " (str/join " vs " (remove nil? [la lb]))))
      (emit "")
      (emit "Methodology adapted from Blaze's FHIR-search performance suite")
      (emit "(https://samply.github.io/blaze/performance/fhir-search.html): synthetic")
      (emit "Synthea data, code/category/date searches over Observation, measured")
      (emit "in-process against the IFHIRStore protocol (no HTTP/auth overhead).")
      (emit "")
      (emit "## Dataset")
      (emit "")
      (doseq [[label r] [[la ra] [lb rb]]]
        (when r
          (emit (format "- **%s**: %d resources from %d patient bundle(s)"
                        label (get-in r [:dataset :total]) (get-in r [:dataset :patients])))))
      (when-let [bt (some-> (or ra rb) :dataset :by-type)]
        (emit "")
        (emit "Resource mix:")
        (emit (str "  " (str/join ", " (map (fn [[k v]] (str k "=" v)) bt)))))
      (emit "")
      (emit "## Load time")
      (emit "")
      (emit "| backend | loaded | failed | time (ms) | res/s |")
      (emit "|---|---:|---:|---:|---:|")
      (doseq [[label r] [[la ra] [lb rb]]]
        (when r
          (let [l (:load r)]
            (emit (format "| %s | %d | %d | %s | %s |"
                          label (:loaded l) (:failed l)
                          (fmt-ms (:elapsed-ms l)) (fmt-int (:res-per-sec l)))))))
      (emit "")
      (emit "## Search latency (median of 5 runs, ms) and hit counts")
      (emit "")
      (emit (format "| query | tier | hits | %s ms | %s ms | faster | result match |"
                    (or la "—") (or lb "—")))
      (emit "|---|---|---:|---:|---:|---|---|")
      (doseq [id ids]
        (let [qa (get aq id) qb (get bq id)
              desc (:desc (or qa qb))
              hits (or (:hits qa) (:hits qb))
              ma (:median-ms qa) mb (:median-ms qb)
              faster (cond (and ma mb) (if (< ma mb) la lb)
                           ma la mb lb :else "—")]
          (emit (format "| %s | %s | %s | %s | %s | %s | %s |"
                        desc (name (:tier (or qa qb)))
                        (fmt-int hits) (fmt-ms ma) (fmt-ms mb) faster
                        (result-match qa qb)))))
      (emit "")
      (emit "_Note: both stores return `[]` for unsupported search params, so a")
      (emit "0-hit extended query may mean \"unsupported\" rather than \"no matches\"._")
      (when (and ra rb)
        (let [core (filter #(= :core (:tier %)) (:queries ra))
              wins (fn [pick]
                     (count (filter (fn [q]
                                      (let [ma (:median-ms (get aq (:id q)))
                                            mb (:median-ms (get bq (:id q)))]
                                        (and ma mb (pick ma mb))))
                                    core)))
              a-wins (wins <) b-wins (wins >)
              lda (get-in ra [:load :elapsed-ms]) ldb (get-in rb [:load :elapsed-ms])
              load-winner (if (< lda ldb) la lb)]
          (emit "")
          (emit "## Verdict")
          (emit "")
          (emit (format "- **Load**: %s faster (%.0f ms vs %.0f ms, %.0f%% delta)."
                        load-winner (min lda ldb) (max lda ldb)
                        (* 100.0 (/ (Math/abs (- lda ldb)) (min lda ldb)))))
          (emit (format "- **Search**: %s faster on %d/%d core queries, %s on %d/%d."
                        la a-wins (count core) lb b-wins (count core)))
          (let [matches   (map #(result-match (get aq %) (get bq %)) ids)
                bad       (remove #{"hits match" "page size match" "page order match"} matches)
                sorted-ok (every? #(= "page order match" %)
                                  (keep (fn [id]
                                          (let [q (or (get aq id) (get bq id))]
                                            (when (:sorted? q) (result-match (get aq id) (get bq id)))))
                                        ids))]
            (emit (format "- **Conformance**: %s (%d/%d queries)%s."
                          (if (empty? bad) "hit counts agree on every query" "MISMATCHES present (see result match column)")
                          (- (count ids) (count bad)) (count ids)
                          (if sorted-ok
                            "; every sorted page returns the same effectiveDateTime ordering (ids are store-local POST assignments and not compared)"
                            "; some sorted pages differ in date order"))))))
      (let [report-str (str lines)]
        (.mkdirs (io/file "target"))
        (spit "target/REPORT.md" report-str)
        (println)
        (println report-str)
        (log "Wrote target/REPORT.md")))))
