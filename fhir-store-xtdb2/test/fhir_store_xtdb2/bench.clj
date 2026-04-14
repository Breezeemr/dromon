(ns fhir-store-xtdb2.bench
  "Deterministic microbench comparing :sql and :xtql pathways on a common
   read+write workload. Each operation is timed individually with
   System/nanoTime — no criterium dependency. A collecting telemere handler
   tallies :xtql/fallback events so the final table can call out how many
   operations under :xtql actually ran natively vs. delegated to SQL.

   Usage: clojure -M:test -m fhir-store-xtdb2.bench [N]
          N is the seed corpus size (default 500)."
  (:require [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]
            [taoensso.telemere :as t]))

(set! *warn-on-reflection* true)

(def ^:private ^java.util.Random rng (java.util.Random. 42))

(defn- reset-rng! []
  (.setSeed rng 42))

(defn- random-gender []
  (if (.nextBoolean rng) "male" "female"))

(defn- random-active []
  (.nextBoolean rng))

(defn- random-patient [idx]
  {:resourceType "Patient"
   :active (random-active)
   :gender (random-gender)})

(defn- pt-id [idx]
  (format "pt-%05d" idx))

(defn- seed!
  "Writes N patients into the store under tenant `bench`. Uses the same RNG
   seed for both pathways so contents are byte-identical."
  [store n]
  (reset-rng!)
  (dotimes [i n]
    (db/create-resource store :bench :Patient (pt-id i) (random-patient i))))

(defn- time-ns
  "Runs f, returns elapsed nanoseconds."
  ^long [f]
  (let [t0 (System/nanoTime)]
    (f)
    (- (System/nanoTime) t0)))

(defn- run-op [label iters f]
  (let [samples (long-array iters)]
    (dotimes [i iters]
      (aset samples i (time-ns f)))
    (java.util.Arrays/sort samples)
    (let [total (reduce + samples)
          mean  (quot total iters)
          p50   (aget samples (quot iters 2))
          p95   (aget samples (min (dec iters) (long (* 0.95 iters))))]
      {:op label
       :iters iters
       :mean-ms (/ mean 1e6)
       :p50-ms  (/ p50 1e6)
       :p95-ms  (/ p95 1e6)
       :total-ms (/ total 1e6)})))

(defn- workload
  "Runs the read+write workload against a store. Returns a vector of per-op
   result maps. `n` is the seed corpus size; `iters` is the per-op repetition."
  [store n iters]
  (let [ids (mapv pt-id (range n))
        hot-id (pt-id (quot n 2))]
    [(run-op "read-by-id" iters
       #(db/read-resource store :bench :Patient hot-id))
     (run-op "search gender=male" iters
       #(db/search store :bench :Patient {:gender "male" :_count "20"} nil))
     (run-op "search active=true" iters
       #(db/search store :bench :Patient {:active true :_count "20"} nil))
     (run-op "count active=true" iters
       #(db/count-resources store :bench :Patient {:active true} nil))
     (run-op "history-type Patient" (quot iters 2)
       #(db/history-type store :bench :Patient {:_count "50"}))
     (run-op "create-resource" iters
       ;; Fresh UUIDs avoid colliding with seeded ids or warmup-inserted ids.
       #(db/create-resource store :bench :Patient
                            (str (java.util.UUID/randomUUID))
                            {:resourceType "Patient" :active true :gender "male"}))
     (run-op "update-resource" iters
       (let [ctr (atom 0)]
         #(let [i (mod (swap! ctr inc) n)]
            (db/update-resource store :bench :Patient (pt-id i)
                                {:resourceType "Patient" :active false :gender "male"}))))]))

(defn- fmt [x] (format "%.3f" (double x)))

(defn- print-table [label results]
  (println)
  (println (str "## " label))
  (println "| op | iters | mean (ms) | p50 (ms) | p95 (ms) | total (ms) |")
  (println "|---|---:|---:|---:|---:|---:|")
  (doseq [{:keys [op iters mean-ms p50-ms p95-ms total-ms]} results]
    (println (format "| %s | %d | %s | %s | %s | %s |"
                     op iters (fmt mean-ms) (fmt p50-ms) (fmt p95-ms) (fmt total-ms)))))

(defn- install-fallback-counter []
  (let [tally (atom {})]
    (t/add-handler!
     :bench/fallback-counter
     (fn [signal]
       (when (= (:id signal) :xtql/fallback)
         (swap! tally update (get-in signal [:data :reason] :unknown) (fnil inc 0))))
     {:dispatch-opts {:min-level :info}})
    tally))

(defn -main [& args]
  (let [n (or (some-> (first args) parse-long) 500)
        iters (or (some-> (second args) parse-long) 200)
        tally (install-fallback-counter)
        sql  (core-db/create-xtdb-store {:query-mode :sql})
        xtql (core-db/create-xtdb-store {:query-mode :xtql})]
    (println (format "Seeding %d Patients per store..." n))
    (seed! sql  n)
    (seed! xtql n)
    (println "Warmup...")
    (dotimes [_ 1] (workload sql  n (quot iters 4)))
    (dotimes [_ 1] (workload xtql n (quot iters 4)))
    (println (format "Running workload (%d iters/op)..." iters))
    (let [sql-res  (workload sql  n iters)
          xtql-res (workload xtql n iters)]
      (print-table ":sql pathway" sql-res)
      (print-table ":xtql pathway" xtql-res))
    (println)
    (println "## :xtql/fallback events")
    (if (seq @tally)
      (doseq [[reason n] @tally]
        (println (format "- %s: %d" reason n)))
      (println "(none)"))
    (println)
    (doseq [[_ node] @(:nodes sql)]  (.close node))
    (doseq [[_ node] @(:nodes xtql)] (.close node))
    (shutdown-agents)))
