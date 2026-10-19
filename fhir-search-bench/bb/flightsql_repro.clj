(ns flightsql-repro
  "`bb flightsql-repro-nightly` (and `-beta3`): reproduces the XTDB FlightSQL
   bugs against a standalone XTDB server in podman, then prints PASS/FAIL.

   1. Pulls the server image.
   2. Focused cases (fhir-search-bench.flightsql-repro) against a server with
      rowsPerBlock 20.
   3. The write benchmark (fhir-search-bench.writes) over :pgwire, the
      control, then over :flight-sql, each against a fresh server.

   Containers carry the label dromon.fhir-search-bench=flightsql-repro and are
   removed when the run ends; `bb flightsql-repro-down` removes any left
   behind. No other container is touched. Output goes to
   target/flightsql-repro/<version>/."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util.concurrent TimeUnit)
           (java.util.jar JarFile)))

(def ^:private label-key "dromon.fhir-search-bench")
(def ^:private label-val "flightsql-repro")

(def ^:private snapshot-repo "https://central.sonatype.com/repository/maven-snapshots/")

(def ^:private versions
  ;; The FlightSQL client has to match the server: XTDB after 2.2.0-beta3
  ;; refuses the beta3 client's BEGIN/COMMIT over FlightSQL, so a nightly
  ;; server is driven by the nightly snapshot of xtdb-core.
  {:nightly {:image "ghcr.io/xtdb/xtdb:nightly" :client "2.2.0-SNAPSHOT"}
   :beta3   {:image "ghcr.io/xtdb/xtdb:2.2.0-beta3"}})

;; Host ports clear of the fhirbench-xtdb and xtdb2-local pods.
(def ^:private servers
  {:repro {:name "fhirbench-flightsql-repro" :config "docker/xtdb-flightsql/repro.yaml"
           :pg-port 25433 :flight-port 29833 :health-port 28091}
   :bench {:name "fhirbench-flightsql-bench" :config "docker/xtdb-flightsql/bench.yaml"
           :pg-port 25432 :flight-port 29832 :health-port 28090}})

;; ── podman ──────────────────────────────────────────────────────────────────

(defn- out [& args]
  (let [r (apply p/shell {:out :string :err :string :continue true} args)]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn- remove-own-container!
  "Removes container `cname` if this task created it; refuses one it did not."
  [cname]
  (when-let [l (out "podman" "container" "inspect" "--format"
                    (str "{{index .Config.Labels \"" label-key "\"}}") cname)]
    (when (not= label-val l)
      (throw (ex-info (str "A container named " cname " exists that this task did not create."
                           " Remove or rename it yourself.") {:name cname})))
    (out "podman" "rm" "-f" cname)))

(defn down!
  "Removes every container labelled by this task, and only those."
  []
  (let [ids (some-> (out "podman" "ps" "-a" "-q" "--filter" (str "label=" label-key "=" label-val))
                    (str/split-lines))]
    (doseq [id (remove str/blank? ids)]
      (out "podman" "rm" "-f" id))
    (println "Removed" (count (remove str/blank? ids)) "flightsql-repro container(s)")))

(defn- ready? [port]
  (try (= 200 (:status (http/get (str "http://127.0.0.1:" port "/healthz/ready")
                                 {:throw false :timeout 2000})))
       (catch Exception _ false)))

(defn- start-server! [{:keys [name config pg-port flight-port health-port]} image]
  (remove-own-container! name)
  (p/shell {:out :string}
           "podman" "run" "-d" "--name" name "--label" (str label-key "=" label-val)
           "-p" (str "127.0.0.1:" pg-port ":5432")
           "-p" (str "127.0.0.1:" flight-port ":9832")
           "-p" (str "127.0.0.1:" health-port ":8080")
           "-v" (str (fs/absolutize config) ":/usr/local/lib/xtdb/config.yaml:ro,Z")
           image)
  (loop [i 0]
    (cond
      (ready? health-port) (println name "ready")
      (or (> i 180) (not= "true" (out "podman" "container" "inspect" "--format" "{{.State.Running}}" name)))
      (throw (ex-info (str name " did not become ready:\n" (out "podman" "logs" "--tail" "30" name)) {}))
      :else (do (Thread/sleep 1000) (recur (inc i))))))

(defn- stop-server! [{:keys [name]} log-file]
  (spit log-file (str (:out (p/shell {:out :string :err :out :continue true} "podman" "logs" name))))
  (remove-own-container! name))

;; ── clojure ─────────────────────────────────────────────────────────────────

(defn- clojure-cmd
  "A `clojure` invocation for `version`: `jvm-opts`, then `exec-flag` (-M, -X
   or -A) on the bench's :xtdb alias, with xtdb-core and xtdb-api overridden
   to the version's client when it has one."
  [{:keys [client]} jvm-opts exec-flag]
  (concat ["clojure"]
          (when client
            ["-Sdeps" (pr-str {:mvn/repos {"central-snapshots" {:url snapshot-repo}}
                               :aliases   {:xtdb-client {:override-deps
                                                         {'com.xtdb/xtdb-core {:mvn/version client}
                                                          'com.xtdb/xtdb-api  {:mvn/version client}}}}})])
          (map #(str "-J" %) jvm-opts)
          [(str exec-flag (if client ":xtdb:xtdb-client" ":xtdb"))]))

(defn- crash-opts
  "JVM options that put a crash log in `dir` instead of a core dump."
  [dir prefix]
  [(str "-XX:ErrorFile=" (fs/absolutize dir) "/" prefix "_%p.log") "-XX:-CreateCoredumpOnCrash"])

(defn- client-info [version]
  (let [[c & more] (clojure-cmd version [] "-A")
        cp  (:out (apply p/shell {:out :string} c "-Spath" more))
        jar (first (filter #(re-find #"/xtdb-core-[^/]*\.jar$" %) (str/split (str/trim cp) #":")))]
    (if-not jar
      "xtdb-core not found on the classpath"
      (with-open [j (JarFile. ^String jar)]
        (let [rev (.getValue (.getMainAttributes (.getManifest j)) "Scm-Revision")]
          (str "com.xtdb/xtdb-core " (or (:client version) "2.2.0-beta3")
               (when rev (str " (Scm-Revision " rev ")"))))))))

(defn- image-info [image]
  (out "podman" "image" "inspect" "--format"
       "{{index .RepoDigests 0}} (revision {{index .Labels \"org.opencontainers.image.revision\"}}, created {{.Created}})"
       image))

(defn- run-logged!
  "Runs `cmd`, echoing its merged stdout and stderr and writing them to
   `log-file`. Returns the exit code, or :timeout after `timeout-min`."
  [cmd log-file timeout-min]
  (let [proc (apply p/process {:err :out} cmd)]
    (with-open [w (io/writer (str log-file))]
      (let [pump (future (with-open [r (io/reader (:out proc))]
                           (doseq [line (line-seq r)]
                             (println line)
                             (.write w (str line "\n"))
                             (.flush w))))
            done (.waitFor ^Process (:proc proc) timeout-min TimeUnit/MINUTES)]
        (when-not done (p/destroy-tree proc))
        @pump
        (if done (.exitValue ^Process (:proc proc)) :timeout)))))

;; ── Evidence ────────────────────────────────────────────────────────────────

(def ^:private signatures
  "Error text each bug leaves in a client's output or the server's log."
  [[1 #"no more buffers for field[^\n]*|no more field nodes for field[^\n]*"]
   [2 #"Not enough capacity for data buffer[^\n]*|Unable to allocate buffer of size[^\n]*|index: \d{6,}, length: [^\n]*|index: \d+, length: \d{6,} \(expected[^\n]*"]
   [3 #"index: 0, length: \d{1,3} \(expected: range\(0, 0\)\)[^\n]*|Ingestion stopped[^\n]*|RST_STREAM[^\n]*"]
   [:crash #"SIGSEGV[^\n]*"]])

(defn- bug-label [bug]
  (if (= :crash bug) "JVM SIGSEGV (bug 2 or 3)" (str "bug " bug)))

(defn- find-signatures
  "[[bug count first-match]] for each signature found in `text`."
  [text]
  (for [[bug re] signatures
        :let [hits (re-seq re (or text ""))]
        :when (seq hits)]
    (let [m (str (first hits))]
      [bug (count hits) (subs m 0 (min 220 (count m)))])))

(defn- evidence-lines [source text]
  (for [[bug n m] (find-signatures text)]
    {:bug bug :line (str (bug-label bug) ", " source " x" n ": " m)}))

(defn- workload-problems
  "Mirrors the checks fhir-search-bench.writes' report flags: errors other
   than the expected 412s, readback mismatches, failed checks, lost updates.
   Response order is left to `store-notes`."
  [k w]
  (let [label (pr-str k)]
    (concat
     (cond
       (seq (:succeeded w))
       (when (some pos? (:other-errors w))
         [(str label " other errors " (:other-errors w) " " (pr-str (dissoc (:error-kinds w) "412")))])

       (seq (keep :expected-failures (:checks w)))
       (let [expected (reduce + (keep :expected-failures (:checks w)))]
         (when (or (not= expected (:errors w)) (not= #{"412"} (set (keys (:error-kinds w)))))
           [(str label " expected " expected " 412s, got " (:errors w) " " (pr-str (:error-kinds w)))]))

       (pos? (or (:errors w) 0))
       [(str label " " (:errors w) " errors " (pr-str (:error-kinds w)))])
     (for [r (:readback w)
           :when (pos? (+ (or (:mismatched r) 0) (or (:missing r) 0)))]
       (str label " readback " (:mismatched r) "/" (:checked r) " mismatched"
            (when (:missing r) (str ", " (:missing r) " missing"))))
     (for [c (:checks w)
           :when (or (pos? (or (:leaked-entries c) 0))
                     (pos? (or (:targets-changed c) 0))
                     (some #(and (% c) (< (% c) (:checked c)))
                           [:put-applied :delete-applied :encounter-ref-resolved]))]
       (str label " checks " (pr-str c)))
     (when (some pos? (:lost-updates w))
       [(str label " lost updates " (:lost-updates w))]))))

(defn- store-notes
  "Checks that fail because of the store rather than the transport, so they
   do not decide a transport's verdict: fhir-store-xtdb2 lists a
   transaction's DELETE first in its response, whatever the request order
   (seen over pgwire, and over FlightSQL on XTDB builds where FlightSQL
   reads work)."
  [result]
  (distinct
   (for [[k w] (:workloads result)
         c     (:checks w)
         :when (and (:response-order-ok c) (< (:response-order-ok c) (:checked c)))]
     (str (pr-str k) " response in request order " (:response-order-ok c) "/" (:checked c)
          (when-let [d (:first-order-diff c)]
            (str ", e.g. request " (pr-str (:request d)) " response " (pr-str (:response d))))))))

(defn- result-problems [result]
  (concat
   (when-not (every? true? (vals (:visible-on-return result)))
     [(str "visible on return " (pr-str (:visible-on-return result)))])
   (mapcat (fn [[k w]] (workload-problems k w)) (:workloads result))))

(defn- crash-logs [dir prefix]
  (sort (map str (fs/glob dir (str prefix "_*.log")))))

(defn- tail [file n]
  (str/join "\n" (take-last n (str/split-lines (slurp (str file))))))

(defn- slurp-if [f] (when (fs/exists? f) (slurp (str f))))

;; ── Focused cases ───────────────────────────────────────────────────────────

(defn- parse-cases [text]
  {:starts   (map second (re-seq #"(?m)^CASE-START (\S+)$" text))
   :progress (into {} (for [[_ id p] (re-seq #"(?m)^CASE-PROGRESS (\S+) (.*)$" text)] [id p]))
   :notes    (reduce (fn [m [_ id n]] (update m id (fnil conj []) n)) {}
                     (re-seq #"(?m)^CASE-NOTE (\S+) (.*)$" text))
   :results (for [[_ id verdict detail] (re-seq #"(?m)^CASE (\S+) (PASS|FAIL) ?(.*)$" text)]
              {:id id :verdict verdict :detail detail})})

(defn- focused-group! [version dir srv group race-secs timeout-min]
  (println "\n== Focused cases:" group)
  (let [prefix (str "hs_err_focused_" group)
        log    (str dir "/focused-" group ".out")
        exit   (run-logged! (concat (clojure-cmd version (crash-opts dir prefix) "-M")
                                    ["-m" "fhir-search-bench.flightsql-repro" group
                                     (str (:pg-port srv)) (str (:flight-port srv)) (str race-secs)])
                            log timeout-min)
        {:keys [starts progress notes results]} (parse-cases (slurp log))
        done   (set (map :id results))
        crash  (first (crash-logs dir prefix))
        died   (for [id starts :when (not (done id))]
                 {:id id :verdict "FAIL"
                  :detail (str "client JVM died (exit " exit ")"
                               (when-let [p (progress id)] (str " after " p))
                               (when-let [n (seq (notes id))] (str "; client saw " (pr-str (vec (take 3 n)))))
                               (when crash
                                 (str "; " (or (some (fn [[bug _ m]] (when (= :crash bug) m))
                                                     (find-signatures (slurp crash)))
                                               "crash log")
                                      " (" (fs/file-name crash) ")")))})]
    (concat results died
            (when (and (not= 0 exit) (empty? died))
              [{:id (str group "-run") :verdict "ERROR"
                :detail (str "exit " exit "; last lines of " log ":\n" (tail log 12))}]))))

(defn- focused!
  "Runs each focused group in its own JVM: layout and crash against one fresh
   repro server (rowsPerBlock 20), race against a fresh server that flushes
   no block."
  [version dir race-secs timeout-min]
  (let [run (fn [srv groups slog]
              (start-server! srv (:image version))
              (try (vec (mapcat #(focused-group! version dir srv % race-secs timeout-min) groups))
                   (finally (stop-server! srv slog))))
        a   (run (:repro servers) ["layout" "crash"] (str dir "/server-focused.log"))
        b   (run (:bench servers) ["race"] (str dir "/server-focused-race.log"))]
    {:cases           (into a b)
     :server-evidence (concat (evidence-lines "server log" (slurp-if (str dir "/server-focused.log")))
                              (evidence-lines "race server log" (slurp-if (str dir "/server-focused-race.log"))))}))

;; ── Write benchmark ─────────────────────────────────────────────────────────

(defn- bench!
  "One fhir-search-bench.writes run over `transport` against a fresh bench
   server."
  [version dir transport bench-args timeout-min]
  (println "\n== Write benchmark:" (name transport))
  (let [srv    (:bench servers)
        t      (name transport)
        prefix (str "hs_err_bench_" t)
        result (str (fs/absolutize dir) "/writes-" t ".edn")
        log    (str dir "/bench-" t ".out")
        slog   (str dir "/server-bench-" t ".log")]
    (start-server! srv (:image version))
    (let [exit     (try (run-logged! (concat (clojure-cmd version (crash-opts dir prefix) "-X")
                                             ["fhir-search-bench.writes/run"
                                              ":backend" (str ":xtdb2-" t)
                                              ":xtdb-server" (pr-str (str "127.0.0.1:" (:pg-port srv)))
                                              ":transport" (str transport)
                                              ":out" (pr-str result)
                                              ;; The plain xtdb image has no Kafka module.
                                              ":xtdb-attach-template" (pr-str "log: !InMemory\nstorage: !InMemory")]
                                             (when (= :flight-sql transport)
                                               [":xtdb-flight-sql-port" (str (:flight-port srv))])
                                             bench-args)
                                     log timeout-min)
                        (finally (stop-server! srv slog)))
          crash    (first (crash-logs dir prefix))
          evidence (concat (evidence-lines "client" (slurp log))
                           (evidence-lines "server log" (slurp-if slog))
                           (when crash
                             (evidence-lines (str "crash log " (fs/file-name crash)) (slurp crash))))
          res      (when (fs/exists? result) (edn/read-string (slurp result)))
          problems (when res (result-problems res))
          verdict  (cond
                     (and (= 0 exit) res (empty? problems) (empty? evidence)) "PASS"
                     (or (seq evidence) (seq problems)) "FAIL"
                     :else "ERROR")]
      {:transport t
       :verdict   verdict
       :bugs      (set (map :bug evidence))
       :details   (concat (map :line evidence)
                          (take 10 problems)
                          (for [n (when res (store-notes res))]
                            (str "not judged (store behaviour, not the transport; also seen over FlightSQL on other XTDB builds): " n))
                          (when (not= 0 exit) [(str "bench exit " exit (when-not res ", no result file"))])
                          (when (= "ERROR" verdict) [(str "last lines of " log ":\n" (tail log 12))]))})))

;; ── Task ────────────────────────────────────────────────────────────────────

(def ^:private own-options #{:focused-only :bench-only :race-secs :timeout-min})

(defn- parse-args
  "Splits `args` (key/value pairs, as for clojure -X) into this task's own
   options and the rest, which go to fhir-search-bench.writes/run as given."
  [args]
  (when (odd? (count args))
    (throw (ex-info "Arguments are key/value pairs, e.g. :preload 20000 :repeats 1" {:args args})))
  (reduce (fn [acc [k v]]
            (let [kw (edn/read-string k)]
              (if (own-options kw)
                (assoc-in acc [:own kw] (edn/read-string v))
                (-> acc
                    (update :bench-args into [k v])
                    (assoc-in [:bench-opts kw] (edn/read-string v))))))
          {:own {} :bench-args [] :bench-opts {}}
          (partition 2 args)))

(defn- bugs-in-focused [cases]
  (set (for [{:keys [id verdict]} cases
             :when (= "FAIL" verdict)
             :let [[_ n] (re-find #"^bug(\d)-(?!control)" id)]
             :when n]
         (parse-long n))))

(defn- summary [version-key version server client {:keys [cases server-evidence]} bench dir]
  (let [control-failed (concat (filter #(and (re-find #"control" (:id %)) (not= "PASS" (:verdict %))) cases)
                               (filter #(and (= "pgwire" (:transport %)) (not= "PASS" (:verdict %))) bench))
        flight (first (filter #(= "flight-sql" (:transport %)) bench))
        bugs   (sort-by str (into (bugs-in-focused cases) (:bugs flight)))
        lines  (concat
                ["" (str "XTDB FlightSQL repro: " (name version-key))
                 (str "  server  " (:image version) " = " server)
                 (str "  client  " client)]
                (when (seq cases)
                  (concat ["" "Focused cases (written over pgwire; each FlightSQL read compared with pgwire)"]
                          (for [{:keys [id verdict detail]} cases]
                            (format "  %-5s %-40s %s" verdict id detail))
                          (for [{:keys [line]} server-evidence] (str "        " line))))
                (when (seq bench)
                  (concat ["" "Write benchmark (fhir-search-bench.writes against the server)"]
                          (mapcat (fn [{:keys [transport verdict details]}]
                                    (cons (format "  %-5s %s" verdict transport)
                                          (map #(str "        " %) details)))
                                  bench)))
                [""
                 (str "Bugs reproduced: " (if (seq bugs) (str/join ", " (map bug-label bugs)) "none"))]
                (when (seq control-failed)
                  ["WARNING: a pgwire control did not pass; the FlightSQL results are not conclusive."])
                [(str "Evidence: " dir "/")])]
    (str/join "\n" lines)))

(defn repro!
  "Entry point of `bb flightsql-repro-<version>`. Exits 1 unless every case
   and both benchmark runs PASS, so a fixed XTDB build reads as exit 0."
  [version-key args]
  (let [version (or (versions version-key) (throw (ex-info "Unknown XTDB version" {:version version-key})))
        {:keys [own bench-args bench-opts]} (parse-args args)
        dir         (str "target/flightsql-repro/" (name version-key))
        synthea-dir (or (:synthea-dir bench-opts) "synthea-output/fhir")]
    (when (and (not (:focused-only own)) (not (fs/directory? synthea-dir)))
      (throw (ex-info (str "No Synthea data in " synthea-dir ": run `bb synthea` first, or pass"
                           " :synthea-dir, or :focused-only true to skip the write benchmark")
                      {:synthea-dir synthea-dir})))
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    (println "Pulling" (:image version))
    (p/shell "podman" "pull" (:image version))
    (let [pass?
          (try
            (let [server  (image-info (:image version))
                  client  (client-info version)
                  _       (println "Server image:" server "\nClient:" client)
                  timeout (or (:timeout-min own) 60)
                  focused (when-not (:bench-only own)
                            (focused! version dir (or (:race-secs own) 20) timeout))
                  bench   (when-not (:focused-only own)
                            (mapv #(bench! version dir % bench-args timeout) [:pgwire :flight-sql]))
                  text    (summary version-key version server client focused bench dir)]
              (println text)
              (spit (str dir "/summary.txt") (str text "\n"))
              (every? #(= "PASS" %) (concat (map :verdict (:cases focused)) (map :verdict bench))))
            (finally (down!)))]
      (when-not pass? (System/exit 1)))))
