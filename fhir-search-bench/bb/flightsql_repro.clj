(ns flightsql-repro
  "`bb flightsql-repro-nightly` (and `-beta3`, `-from-sha`): reproduces the
   XTDB FlightSQL bugs against a standalone XTDB server in podman, then prints
   PASS/FAIL.

   1. Pulls the server image, or for `-from-sha` builds the server image and
      the xtdb-api/xtdb-core client jars from an XTDB git commit.
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
  {:nightly {:image "ghcr.io/xtdb/xtdb:nightly" :client "2.2.0-SNAPSHOT" :client-repo snapshot-repo}
   :beta3   {:image "ghcr.io/xtdb/xtdb:2.2.0-beta3"}})

;; Host ports clear of the fhirbench-xtdb and xtdb2-local pods.
(def ^:private servers
  {:repro {:name "fhirbench-flightsql-repro" :config "docker/xtdb-flightsql/repro.yaml"
           :pg-port 25433 :flight-port 29833 :health-port 28091}
   :bench {:name "fhirbench-flightsql-bench" :config "docker/xtdb-flightsql/bench.yaml"
           :pg-port 25432 :flight-port 29832 :health-port 28090
           ;; A fixed heap, so no benchmark run times heap resizing and both
           ;; transports' servers have the same one. Not pre-touched: the
           ;; discarded warm-up repeat touches what the timed repeats use, and a
           ;; pre-touched 12g server heap got the server OOM-killed on a 64G
           ;; machine shared with other work.
           :java-opts "-Xms8g -Xmx8g"}})

(def ^:private bench-client-jvm-opts
  "Benchmark client JVMs start with a fixed initial heap for the same reason;
   the :xtdb alias sets -Xmx."
  ["-Xms4g"])

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

(defn- start-server! [{:keys [name config pg-port flight-port health-port java-opts]} image]
  (remove-own-container! name)
  (apply p/shell {:out :string}
         "podman" "run" "-d" "--name" name "--label" (str label-key "=" label-val)
         (concat
          (when java-opts ["-e" (str "JAVA_TOOL_OPTIONS=" java-opts)])
          ["-p" (str "127.0.0.1:" pg-port ":5432")
           "-p" (str "127.0.0.1:" flight-port ":9832")
           "-p" (str "127.0.0.1:" health-port ":8080")
           "-v" (str (fs/absolutize config) ":/usr/local/lib/xtdb/config.yaml:ro,Z")
           image]))
  (loop [i 0]
    (cond
      (ready? health-port) (println name "ready")
      (or (> i 180) (not= "true" (out "podman" "container" "inspect" "--format" "{{.State.Running}}" name)))
      (throw (ex-info (str name " did not become ready:\n" (out "podman" "logs" "--tail" "30" name)) {}))
      :else (do (Thread/sleep 1000) (recur (inc i))))))

(defn- stop-server!
  "Saves the server's log, removes the container, and returns a note when the
   server was no longer running, e.g. killed by the OOM killer."
  [{:keys [name]} log-file]
  (spit log-file (str (:out (p/shell {:out :string :err :out :continue true} "podman" "logs" name))))
  (let [state (out "podman" "container" "inspect" "--format"
                   "{{.State.Running}} {{.State.ExitCode}} {{.State.OOMKilled}}" name)]
    (remove-own-container! name)
    (when-let [[_ code oom] (some->> state (re-matches #"false (\S+) (\S+)"))]
      (str "server container had exited with code " code
           (when (= "true" oom) " (OOM-killed)")
           "; a kernel OOM kill shows in `journalctl -k`"))))

;; ── clojure ─────────────────────────────────────────────────────────────────

(defn- clojure-cmd
  "A `clojure` invocation for `version`: `jvm-opts`, then `exec-flag` (-M, -X
   or -A) on the bench's :xtdb alias, with xtdb-core and xtdb-api overridden
   to the version's client, from its :client-repo, when it has one."
  [{:keys [client client-repo]} jvm-opts exec-flag]
  (concat ["clojure"]
          (when client
            ["-Sdeps" (pr-str {:mvn/repos {"xtdb-client" {:url client-repo}}
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
       "{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}image {{.Id}}{{end}} (revision {{index .Labels \"org.opencontainers.image.revision\"}}, created {{.Created}})"
       image))

(defn- run-logged!
  "Runs `cmd`, echoing its merged stdout and stderr and writing them to
   `log-file`. Returns the exit code, or :timeout after `timeout-min`.
   `opts` are babashka.process options such as :dir and :extra-env."
  ([cmd log-file timeout-min] (run-logged! {} cmd log-file timeout-min))
  ([opts cmd log-file timeout-min]
  (let [proc (apply p/process (merge opts {:err :out}) cmd)]
    (with-open [w (io/writer (str log-file))]
      (let [pump (future (with-open [r (io/reader (:out proc))]
                           (doseq [line (line-seq r)]
                             (println line)
                             (.write w (str line "\n"))
                             (.flush w))))
            done (.waitFor ^Process (:proc proc) timeout-min TimeUnit/MINUTES)]
        (when-not done (p/destroy-tree proc))
        @pump
        (if done (.exitValue ^Process (:proc proc)) :timeout))))))

;; ── XTDB from a git commit ──────────────────────────────────────────────────

(def ^:private default-xtdb-repo "https://github.com/xtdb/xtdb.git")

(def ^:private xtdb-src "target/flightsql-repro/xtdb-src")
(def ^:private xtdb-m2 "target/flightsql-repro/xtdb-m2")

(defn- jdk-home
  "XTDB's Gradle build compiles on JDK 21 and runs tests on 25
   (org.gradle.java.installations.fromEnv in its gradle.properties)."
  [env-var version]
  (or (System/getenv env-var)
      (let [d (str "/usr/lib/jvm/java-" version "-openjdk-amd64")]
        (when (fs/directory? d) d))
      (throw (ex-info (str "Set " env-var " to a JDK " version " home; XTDB's build needs JDK 21 and 25")
                      {:env env-var}))))

(defn- git [& args]
  (apply p/shell {:dir xtdb-src :out :string :err :string} "git" args))

(defn- checkout!
  "Checks out `rev` of `repo` in target/flightsql-repro/xtdb-src and returns
   its full sha. `rev` is a full sha (any commit GitHub serves, PR heads
   included) or a ref `git ls-remote` resolves, such as a branch, a tag or
   pull/6222/head."
  [repo rev]
  (when-not (fs/directory? (str xtdb-src "/.git"))
    (fs/create-dirs xtdb-src)
    (git "init" "-q")
    (git "remote" "add" "origin" repo))
  (git "remote" "set-url" "origin" repo)
  (let [sha (if (re-matches #"[0-9a-f]{40}" rev)
              rev
              (or (some->> (out "git" "ls-remote" repo rev) str/split-lines first (re-find #"^[0-9a-f]{40}"))
                  (throw (ex-info (str "Cannot resolve " rev " in " repo
                                       "; pass the full 40-character sha or a branch, tag or pull/<n>/head")
                                  {:rev rev :repo repo}))))]
    (println "Fetching" sha "from" repo)
    (git "fetch" "-q" "--depth" "1" "origin" sha)
    (git "checkout" "-q" "--detach" "--force" sha)
    sha))

(defn- install-client!
  "Lays xtdb-api and xtdb-core out as a Maven repository under
   target/flightsql-repro/xtdb-m2 (jar plus the POM Gradle generates), so the
   client JVMs resolve them by version like any other dependency."
  [version]
  (doseq [[module artifact] [["api" "xtdb-api"] ["core" "xtdb-core"]]]
    (let [dir (str xtdb-m2 "/com/xtdb/" artifact "/" version)]
      (fs/create-dirs dir)
      (fs/copy (str xtdb-src "/" module "/build/libs/" artifact "-" version ".jar")
               (str dir "/" artifact "-" version ".jar") {:replace-existing true})
      (fs/copy (str xtdb-src "/" module "/build/publications/maven/pom-default.xml")
               (str dir "/" artifact "-" version ".pom") {:replace-existing true}))))

(defn- build-from-sha!
  "Builds XTDB at `rev` of `repo`: the standalone server image, tagged
   localhost/xtdb-flightsql-repro:<sha12>, and the client jars. Skips a sha
   already built unless `rebuild?`. Returns the version map repro runs with."
  [repo rev rebuild? dir timeout-min]
  (let [sha     (checkout! repo rev)
        sha12   (subs sha 0 12)
        version (str "2.2.0-repro-" sha12)
        image   (str "localhost/xtdb-flightsql-repro:" sha12)
        result  {:image image :client version :local-image? true
                 :client-repo (str "file://" (fs/absolutize xtdb-m2))
                 :source (str repo " @ " sha)}
        built?  (and (out "podman" "image" "exists" image)
                     (fs/exists? (str xtdb-m2 "/com/xtdb/xtdb-core/" version "/xtdb-core-" version ".jar")))]
    (if (and built? (not rebuild?))
      (println "Reusing" image "and client" version "(pass :rebuild true to build again)")
      (let [jdk21 (jdk-home "JAVA_HOME_21_X64" 21)
            jdk25 (jdk-home "JAVA_HOME_25_X64" 25)
            _     (println "Building XTDB" sha12 "(Gradle, then podman build); log in" (str dir "/build-xtdb.out"))
            exit  (run-logged! {:dir xtdb-src
                                :extra-env {"JAVA_HOME" jdk21 "JAVA_HOME_21_X64" jdk21
                                            "JAVA_HOME_25_X64" jdk25 "XTDB_VERSION" version}}
                               ["./gradlew" "--no-daemon" "--console=plain"
                                ":docker:standalone:shadowJar" ":xtdb-api:jar" ":xtdb-core:jar"
                                ":xtdb-api:generatePomFileForMavenPublication"
                                ":xtdb-core:generatePomFileForMavenPublication"]
                               (str dir "/build-xtdb.out") timeout-min)]
        (when (not= 0 exit)
          (throw (ex-info (str "XTDB Gradle build failed (exit " exit "); see " dir "/build-xtdb.out") {})))
        (install-client! version)
        (let [exit (run-logged! {:dir xtdb-src}
                                ["podman" "build" "-f" "docker/Dockerfile"
                                 "--build-arg" "VARIANT=standalone"
                                 "--build-arg" (str "GIT_SHA=" sha)
                                 "--build-arg" (str "XTDB_VERSION=" version)
                                 "--label" (str label-key "=" label-val)
                                 "-t" image "."]
                                (str dir "/build-image.out") timeout-min)]
          (when (not= 0 exit)
            (throw (ex-info (str "XTDB image build failed (exit " exit "); see " dir "/build-image.out") {}))))))
    result))

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

(defn- machine-load
  "The 1-minute load average and available memory, as a noise note."
  []
  (let [load (some-> (out "cat" "/proc/loadavg") (str/split #" ") first)
        mem  (some->> (out "cat" "/proc/meminfo") (re-find #"MemAvailable:\s+(\d+)") second parse-long)]
    (str "load " (or load "n/a") ", " (if mem (format "%.0fG" (/ mem 1048576.0)) "n/a") " available")))

(defn- bench!
  "One fhir-search-bench.writes run over `transport` against a fresh bench
   server, as round `round`."
  [version dir round transport bench-args timeout-min]
  (println "\n== Write benchmark, round" round ":" (name transport))
  (let [srv    (:bench servers)
        t      (name transport)
        run-id (str t "-r" round)
        prefix (str "hs_err_bench_" run-id)
        result (str (fs/absolutize dir) "/writes-" run-id ".edn")
        log    (str dir "/bench-" run-id ".out")
        slog   (str dir "/server-bench-" run-id ".log")]
    (start-server! srv (:image version))
    (let [load-before (machine-load)
          server-note (volatile! nil)
          exit     (try (run-logged! (concat (clojure-cmd version (concat bench-client-jvm-opts (crash-opts dir prefix)) "-X")
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
                        (finally (vreset! server-note (stop-server! srv slog))))
          load-after (machine-load)
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
       :round     round
       :result    res
       :verdict   verdict
       :bugs      (set (map :bug evidence))
       :details   (concat [(str "machine: " load-before " before, " load-after " after")]
                          (when @server-note [@server-note])
                          (map :line evidence)
                          (take 10 problems)
                          (for [n (when res (store-notes res))]
                            (str "not judged (store behaviour, not the transport; also seen over FlightSQL on other XTDB builds): " n))
                          (when (not= 0 exit) [(str "bench exit " exit (when-not res ", no result file"))])
                          (when (= "ERROR" verdict) [(str "last lines of " log ":\n" (tail log 12))]))})))

;; ── Transport comparison ────────────────────────────────────────────────────

(defn- median [xs]
  (when (seq xs)
    (let [v (vec (sort xs)) n (count v)]
      (if (odd? n) (v (quot n 2)) (/ (+ (v (dec (quot n 2))) (v (quot n 2))) 2.0)))))

(defn- workload-label [[kind a b]]
  (case kind
    :single     (str "single " (name a))
    :edit       (str "PUT " (name a) " " (str/replace (name b) "-" " "))
    :concurrent (str (name a) " x" b " threads")
    :tx         (str (name a) " " b " entries")
    :mixed      "mixed tx"
    :rollback   "rollback tx"
    :contention "contention (read + update ifMatch)"
    (pr-str kind)))

(def ^:private kind-order [:single :edit :concurrent :tx :mixed :rollback :contention])

(def ^:private sub-order {:create 0 :update 1 :update-if-match 2 :delete 3 :transaction 0 :batch 1
                          :one-field 0 :most-fields 1})

(defn- workload-sort-key [[kind a b]]
  [(.indexOf ^java.util.List kind-order kind) (get sub-order a 9) (str a)
   (if (keyword? b) (get sub-order b 9) (or b 0))])

(defn- comparison
  "Pools every measured repeat's p50 per transport across all rounds of
   `runs`. A workload whose two ranges of repeat p50s overlap is marked as
   within noise, so only separated ranges count as a difference."
  [runs]
  (let [ok      (filter :result runs)
        by-t    (group-by :transport ok)
        reps    (fn [t k] (vec (mapcat #(get-in % [:result :workloads k :p50-by-rep]) (by-t t))))
        rounds  (sort (distinct (map :round ok)))
        per-rnd (fn [t k r] (median (mapcat #(when (= r (:round %)) (get-in % [:result :workloads k :p50-by-rep])) (by-t t))))
        ks      (sort-by workload-sort-key
                         (distinct (mapcat #(keys (get-in % [:result :workloads])) ok)))
        fmt     (fn [x] (if (number? x) (format "%.2f" (double x)) "-"))
        row     (fn [k]
                  (let [f (reps "flight-sql" k) g (reps "pgwire" k)]
                    (when (and (seq f) (seq g))
                      (let [mf (median f) mg (median g)
                            overlap? (and (<= (apply min f) (apply max g)) (<= (apply min g) (apply max f)))
                            ratios (for [r rounds
                                         :let [a (per-rnd "flight-sql" k r) b (per-rnd "pgwire" k r)]
                                         :when (and a b (pos? b))]
                                     (format "%.2f" (/ (double a) b)))]
                        (format "| %s | %s (%d) | %s (%d) | %.2f | %s | %s |"
                                (workload-label k) (fmt mf) (count f) (fmt mg) (count g)
                                (/ (double mf) mg) (str/join ", " ratios)
                                (if overlap? "within noise" (if (> mf mg) "pgwire faster" "flight-sql faster")))))))
        preload (fn [t] (str/join ", " (for [r (sort-by :round (by-t t))]
                                         (format "%.1f s" (/ (get-in r [:result :preload :elapsed-ms] 0.0) 1000)))))]
    (when (and (seq (by-t "flight-sql")) (seq (by-t "pgwire")))
      (str/join "\n"
                (concat
                 ["Median p50 in ms over every measured repeat of every round (repeat count in brackets)."
                  "The ratio is flight-sql / pgwire; per round it is the ratio of that round's medians."
                  "\"within noise\" means the two transports' ranges of repeat p50s overlap."
                  ""
                  (str "Preload: flight-sql " (preload "flight-sql") "; pgwire " (preload "pgwire"))
                  ""
                  "| workload | flight-sql p50 | pgwire p50 | flight/pgwire | per round | verdict |"
                  "|---|---:|---:|---:|---|---|"]
                 (keep row ks))))))

;; ── Task ────────────────────────────────────────────────────────────────────

(def ^:private own-options #{:focused-only :bench-only :race-secs :timeout-min :sha :repo :rebuild :rounds})

;; Read as plain strings, so `:sha 9f8553ce2c3c...` needs no EDN quoting.
(def ^:private string-options #{:sha :repo})

(defn- parse-args
  "Splits `args` (key/value pairs, as for clojure -X) into this task's own
   options and the rest, which go to fhir-search-bench.writes/run as given."
  [args]
  (when (odd? (count args))
    (throw (ex-info "Arguments are key/value pairs, e.g. :preload 20000 :repeats 1" {:args args})))
  (reduce (fn [acc [k v]]
            (let [kw (edn/read-string k)]
              (cond
                (string-options kw)
                (assoc-in acc [:own kw] (str/replace v #"^\"|\"$" ""))

                (own-options kw)
                (assoc-in acc [:own kw] (edn/read-string v))

                :else
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

(defn- summary [label version server client {:keys [cases server-evidence]} bench dir]
  (let [control-failed (concat (filter #(and (re-find #"control" (:id %)) (not= "PASS" (:verdict %))) cases)
                               (filter #(and (= "pgwire" (:transport %)) (not= "PASS" (:verdict %))) bench))
        bugs   (sort-by str (into (bugs-in-focused cases)
                                      (mapcat :bugs (filter #(= "flight-sql" (:transport %)) bench))))
        lines  (concat
                ["" (str "XTDB FlightSQL repro: " label)]
                (when-let [src (:source version)] [(str "  source  " src)])
                [(str "  server  " (:image version) " = " server)
                 (str "  client  " client)]
                (when (seq cases)
                  (concat ["" "Focused cases (written over pgwire; each FlightSQL read compared with pgwire)"]
                          (for [{:keys [id verdict detail]} cases]
                            (format "  %-5s %-40s %s" verdict id detail))
                          (for [{:keys [line]} server-evidence] (str "        " line))))
                (when (seq bench)
                  (concat ["" "Write benchmark (fhir-search-bench.writes against the server)"]
                          (mapcat (fn [{:keys [transport round verdict details]}]
                                    (cons (format "  %-5s %s, round %d" verdict transport round)
                                          (map #(str "        " %) details)))
                                  bench)))
                [""
                 (str "Bugs reproduced: " (if (seq bugs) (str/join ", " (map bug-label bugs)) "none"))]
                (when (seq control-failed)
                  ["WARNING: a pgwire control did not pass; the FlightSQL results are not conclusive."])
                [(str "Evidence: " dir "/")])]
    (str/join "\n" lines)))

(defn- run-repro!
  "Runs the repro for `version` with output in `dir`; exits 1 unless every
   case and both benchmark runs PASS, so a fixed XTDB build reads as exit 0."
  [label version dir {:keys [own bench-args bench-opts]}]
  (let [bench-args (cond-> bench-args
                     ;; One full repeat, discarded, warms both JVMs on every
                     ;; workload's code path before anything is timed.
                     (not (contains? bench-opts :warmup-repeats)) (into [":warmup-repeats" "1"]))]
  (let [pass?
        (try
          (let [server  (image-info (:image version))
                client  (client-info version)
                _       (println "Server image:" server "\nClient:" client)
                timeout (or (:timeout-min own) 60)
                focused (when-not (:bench-only own)
                          (focused! version dir (or (:race-secs own) 20) timeout))
                ;; ABBA: round 1 runs pgwire first, round 2 flight-sql first,
                ;; and so on, so neither transport always gets the warmer
                ;; machine or the later slot.
                bench   (when-not (:focused-only own)
                          (vec (for [round (range 1 (inc (or (:rounds own) 2)))
                                     t     (cond-> [:pgwire :flight-sql] (even? round) reverse)]
                                 (bench! version dir round t bench-args timeout))))
                cmp     (comparison bench)
                text    (str (summary label version server client focused bench dir)
                             (when cmp (str "\n\nflight-sql vs pgwire\n\n" cmp)))]
            (println text)
            (spit (str dir "/summary.txt") (str text "\n"))
            (when cmp (spit (str dir "/COMPARISON.md") (str "# flight-sql vs pgwire: " label "\n\n" cmp "\n")))
            (every? #(= "PASS" %) (concat (map :verdict (:cases focused)) (map :verdict bench))))
          (finally (down!)))]
    (when-not pass? (System/exit 1)))))

(defn- prepare!
  "Parses `args`, checks the Synthea data the benchmark needs, and empties
   `dir`."
  [args dir]
  (let [{:keys [own bench-opts] :as parsed} (parse-args args)
        synthea-dir (or (:synthea-dir bench-opts) "synthea-output/fhir")]
    (when (and (not (:focused-only own)) (not (fs/directory? synthea-dir)))
      (throw (ex-info (str "No Synthea data in " synthea-dir ": run `bb synthea` first, or pass"
                           " :synthea-dir, or :focused-only true to skip the write benchmark")
                      {:synthea-dir synthea-dir})))
    (fs/delete-tree dir)
    (fs/create-dirs dir)
    parsed))

(defn repro!
  "Entry point of `bb flightsql-repro-nightly` and `-beta3`."
  [version-key args]
  (let [version (or (versions version-key) (throw (ex-info "Unknown XTDB version" {:version version-key})))
        dir     (str "target/flightsql-repro/" (name version-key))
        parsed  (prepare! args dir)]
    (println "Pulling" (:image version))
    (p/shell "podman" "pull" (:image version))
    (run-repro! (name version-key) version dir parsed)))

(defn repro-from-sha!
  "Entry point of `bb flightsql-repro-from-sha :sha <sha>`: builds XTDB at
   that commit (server image and matching client) and runs the repro on it.
   `:repo` names another git remote, e.g. a fork; `:rebuild true` builds a
   sha again."
  [args]
  (let [{:keys [sha repo rebuild timeout-min]} (:own (parse-args args))]
    (when (str/blank? sha)
      (throw (ex-info "Pass :sha <commit>, e.g. bb flightsql-repro-from-sha :sha 9f8553ce2c3cd2133bdba7f63477caceb11ea294" {})))
    (let [label  (str "sha-" (subs (str/replace sha #"[^0-9A-Za-z._-]" "_") 0 (min 12 (count sha))))
          dir    (str "target/flightsql-repro/" label)
          parsed (prepare! args dir)
          version (build-from-sha! (or repo default-xtdb-repo) sha (true? rebuild) dir (or timeout-min 60))]
      (run-repro! label version dir parsed))))
