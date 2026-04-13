#!/usr/bin/env bb
;; Parse dromon/server.log to bucket requests by interaction type and compute
;; per-bucket latency stats (count, sum, avg, p50, p95, p99, max).
;; Mirrors datomic-test-server/perf-analysis/analyze-log.clj so results from
;; the two backends are directly comparable.
(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def log-path
  (or (first *command-line-args*)
      (if (.exists (io/file "server.log"))
        "server.log"
        "../server.log")))

(defn categorize [method uri]
  (let [u (or uri "")]
    (cond
      (re-find #"/metadata$" u)              :capability-statement
      (re-find #"/_search$" u)               :search-post
      (re-find #"/\$[A-Za-z][A-Za-z0-9-]*$" u):operation
      (re-find #"^/(default/)?fhir/?$" u)    (if (= method "post") :bundle :root)
      (re-find #"/fhir/[A-Z][A-Za-z]+/[^/]+$" u)
      (case method
        "get"    :read
        "put"    :update
        "delete" :delete
        :other)
      (re-find #"/fhir/[A-Z][A-Za-z]+/?$" u)
      (case method
        "get"    :search-get
        "post"   :create
        :other)
      :else :other)))

(defn resource-type [uri]
  (when-let [m (re-find #"/fhir/([A-Z][A-Za-z]+)(?:/|$)" (or uri ""))]
    (second m)))

(defn percentile [sorted p]
  (when (seq sorted)
    (let [n (count sorted)
          idx (min (dec n) (int (Math/floor (* p (dec n)))))]
      (nth sorted idx))))

(defn fmt-ms [x] (format "%8.2f" (double x)))

(defn print-table [title rows]
  (println)
  (println title)
  (printf "  %-22s %8s %10s %8s %8s %8s %8s %8s\n"
          "bucket" "count" "sum_ms" "avg" "p50" "p95" "p99" "max")
  (println (apply str (repeat 92 \-)))
  (doseq [{:keys [k durs]} rows]
    (let [sorted (sort durs)
          n (count durs)
          sum (reduce + 0.0 durs)
          avg (/ sum n)]
      (printf "  %-22s %8d %10.1f %s %s %s %s %s\n"
              (if (namespace k) (str (namespace k) "/" (name k)) (name k)) n sum
              (fmt-ms avg)
              (fmt-ms (percentile sorted 0.50))
              (fmt-ms (percentile sorted 0.95))
              (fmt-ms (percentile sorted 0.99))
              (fmt-ms (apply max durs))))))

(println "Reading log from:" log-path)
(def raw-entries
  (with-open [r (io/reader log-path)]
    (doall
     (keep (fn [line]
             (try
               (let [m (json/parse-string line true)
                     data (:data m)
                     dur (:duration-ms data)]
                 (when (and dur (:status data))
                   {:method (:method data)
                    :uri (:uri data)
                    :status (:status data)
                    :dur dur}))
               (catch Exception _ nil)))
           (line-seq r)))))

;; The Inferno runner issues a single `GET /default/fhir/Patient?_count=1`
;; warmup before any timed write so cold-start cost (per-tenant XTDB node
;; startup, first SQL plan compile, JIT) is absorbed there instead of being
;; billed against the first real request. Drop that entry so steady-state
;; Patient/search-get numbers aren't skewed by the 1-2 s warmup latency.
;; Identifies the warmup as the first Patient search-get in the log.
(def warmup-idx
  (->> raw-entries
       (map-indexed vector)
       (some (fn [[i e]]
               (when (and (= "get" (:method e))
                          (re-matches #"/(?:default/)?fhir/Patient/?" (or (:uri e) "")))
                 i)))))

(when warmup-idx
  (let [w (nth raw-entries warmup-idx)]
    (printf "Filtering warmup entry: %s %s -> %d in %.0f ms (idx %d)%n"
            (:method w) (:uri w) (:status w) (:dur w) warmup-idx)))

(def entries
  (if warmup-idx
    (vec (concat (subvec (vec raw-entries) 0 warmup-idx)
                 (subvec (vec raw-entries) (inc warmup-idx))))
    raw-entries))

(println "Total HTTP requests with duration:" (count entries))
(println "Distinct resource types:"
         (->> entries (keep #(resource-type (:uri %))) distinct sort))

(let [by-cat (group-by #(categorize (:method %) (:uri %)) entries)
      rows   (for [[k xs] by-cat
                   :let [durs (mapv :dur xs)]
                   :when (seq durs)]
               {:k k :durs durs})
      rows   (sort-by (fn [{:keys [durs]}] (- (reduce + durs))) rows)]
  (print-table "=== By interaction type ===" rows))

(let [by (group-by (fn [e]
                     (keyword (str (or (resource-type (:uri e)) "_none")
                                   "/"
                                   (name (categorize (:method e) (:uri e))))))
                   entries)
      rows (for [[k xs] by
                 :let [durs (mapv :dur xs)]
                 :when (and (seq durs) (>= (count durs) 3))]
             {:k k :durs durs})
      rows (sort-by (fn [{:keys [durs]}] (- (reduce + durs))) rows)
      rows (take 25 rows)]
  (print-table "=== Top 25 (resource / interaction) by total time ===" rows))

(println "\n=== Status codes ===")
(doseq [[s n] (sort (frequencies (map :status entries)))]
  (printf "  %d  %6d\n" s n))
