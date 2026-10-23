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
  ;; :kafka-image is the same build with the Kafka module (the plain image
  ;; has none), for the Kafka-log benchmark.
  {:nightly {:image "ghcr.io/xtdb/xtdb:nightly" :kafka-image "ghcr.io/xtdb/xtdb-aws:nightly"
             :client "2.2.0-SNAPSHOT" :client-repo snapshot-repo}
   :beta3   {:image "ghcr.io/xtdb/xtdb:2.2.0-beta3" :kafka-image "ghcr.io/xtdb/xtdb-aws:2.2.0-beta3"}})

(defn- release-version
  "A published XTDB release: its ghcr.io images, and the same version of
   xtdb-core and xtdb-api from Maven Central as the client."
  [v]
  {:image (str "ghcr.io/xtdb/xtdb:" v) :kafka-image (str "ghcr.io/xtdb/xtdb-aws:" v) :client v})

(def ^:private kafka-broker-image "docker.io/apache/kafka:3.9.1")

;; Bench servers: a fixed heap, so no run times heap resizing, and a cgroup
;; memory limit that also caps the page cache (cgroup v2 charges a
;; container's page cache to it), so neither XTDB's memory cache (256 MiB in
;; the bench configs) nor the OS can hold the whole dataset. The 100k run
;; leaves 1.1-1.6G of data on disk and the server's JVM uses about 2.9G, so
;; 3.5g leaves the page cache well under half the data. (At 5g and 8g the
;; page cache held all of it.)
(def ^:private server-java-opts "-Xms3g -Xmx3g -XX:MaxDirectMemorySize=1g")
(def ^:private server-memory "3500m")
(def ^:private broker-memory "2g")

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
           :java-opts server-java-opts
           :memory    server-memory
           ;; Log and storage on disk: a fresh podman volume per run.
           :volume?   true
           :attach-template (str "log: !Local\n"
                                 "  path: '/var/lib/xtdb/dbs/{db}-{uid}/log'\n"
                                 "storage: !Local\n"
                                 "  path: '/var/lib/xtdb/dbs/{db}-{uid}/storage'")}
   ;; Kafka log: a broker and the server in one pod, storage on a volume.
   ;; No :attach-template, so tenants get the store's default: a Kafka topic
   ;; per tenant database plus local storage.
   :bench-kafka {:name "fhirbench-flightsql-bench-kafka" :pod "fhirbench-flightsql-kafka"
                 :config "docker/xtdb-flightsql/bench-kafka.yaml"
                 :pg-port 25432 :flight-port 29832 :health-port 28090
                 :java-opts server-java-opts :memory server-memory :volume? true}})

(def ^:private storages
  "Write-benchmark variants: where the bench server keeps its log."
  {:disk {:server :bench :image-key :image}
   :kafka {:server :bench-kafka :image-key :kafka-image}})

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

(defn- labelled [& args]
  (remove str/blank? (some-> (apply out (concat args ["--filter" (str "label=" label-key "=" label-val)]))
                             (str/split-lines))))

(defn down!
  "Removes every pod, container and volume labelled by this task, and only
   those."
  []
  (let [pods (labelled "podman" "pod" "ps" "-q")
        _    (doseq [id pods] (out "podman" "pod" "rm" "-f" id))
        ids  (labelled "podman" "ps" "-a" "-q")
        _    (doseq [id ids] (out "podman" "rm" "-f" id))
        vols (labelled "podman" "volume" "ls" "-q")]
    (doseq [v vols] (out "podman" "volume" "rm" "-f" v))
    (println "Removed" (count pods) "pod(s)," (count ids) "container(s) and" (count vols)
             "volume(s) labelled flightsql-repro")))

(defn- remove-own-pod!
  "Removes pod `pname` if this task created it; refuses one it did not."
  [pname]
  (when-let [l (out "podman" "pod" "inspect" "--format"
                    (str "{{index .Labels \"" label-key "\"}}") pname)]
    (when (not= label-val l)
      (throw (ex-info (str "A pod named " pname " exists that this task did not create."
                           " Remove or rename it yourself.") {:name pname})))
    (out "podman" "pod" "rm" "-f" pname)))

(defn- wait-for! [what timeout-s ok?]
  (loop [i 0]
    (cond (ok?) true
          (> i timeout-s) (throw (ex-info (str what " did not become ready") {}))
          :else (do (Thread/sleep 1000) (recur (inc i))))))

(defn- kafka-cmd [pod tool & args]
  (concat ["podman" "exec" (str pod "-broker") (str "/opt/kafka/bin/" tool)
           "--bootstrap-server" "localhost:9092"]
          args))

(defn- start-pod!
  "A pod publishing the server's ports, with a Kafka broker (KRaft, one node)
   whose message.max.bytes matches the server's max.request.size."
  [{:keys [pod pg-port flight-port health-port]}]
  (remove-own-pod! pod)
  (p/shell {:out :string} "podman" "pod" "create" "--name" pod "--label" (str label-key "=" label-val)
           "-p" (str "127.0.0.1:" pg-port ":5432")
           "-p" (str "127.0.0.1:" flight-port ":9832")
           "-p" (str "127.0.0.1:" health-port ":8080"))
  (p/shell {:out :string} "podman" "run" "-d" "--pod" pod "--name" (str pod "-broker")
           "--label" (str label-key "=" label-val)
           "--memory" broker-memory "--memory-swap" broker-memory kafka-broker-image)
  (wait-for! "Kafka" 120 #(some? (apply out (kafka-cmd pod "kafka-topics.sh" "--list"))))
  (apply p/shell {:out :string}
         (kafka-cmd pod "kafka-configs.sh" "--entity-type" "brokers" "--entity-default" "--alter"
                    "--add-config" "message.max.bytes=33554432")))

(defn- volume-name [cname] (str cname "-data"))

(defn- remove-own-volume!
  "Removes volume `vname` if this task created it; refuses one it did not."
  [vname]
  (when-let [l (out "podman" "volume" "inspect" "--format"
                    (str "{{index .Labels \"" label-key "\"}}") vname)]
    (when (not= label-val l)
      (throw (ex-info (str "A volume named " vname " exists that this task did not create."
                           " Remove or rename it yourself.") {:name vname})))
    (out "podman" "volume" "rm" "-f" vname)))

(defn- ready? [port]
  (try (= 200 (:status (http/get (str "http://127.0.0.1:" port "/healthz/ready")
                                 {:throw false :timeout 2000})))
       (catch Exception _ false)))

(defn- start-server! [{:keys [name config pg-port flight-port health-port java-opts memory volume? pod] :as srv} image]
  (remove-own-container! name)
  (when pod (start-pod! srv))
  (when volume?
    (remove-own-volume! (volume-name name))
    (p/shell {:out :string} "podman" "volume" "create" "--label" (str label-key "=" label-val)
             (volume-name name)))
  (apply p/shell {:out :string}
         "podman" "run" "-d" "--name" name "--label" (str label-key "=" label-val)
         (concat
          (when java-opts ["-e" (str "JAVA_TOOL_OPTIONS=" java-opts)])
          ;; Swap capped at the same value, so the limit is not met from swap.
          (when memory ["--memory" memory "--memory-swap" memory])
          ;; A named volume starts with the image's /var/lib/xtdb, owned by
          ;; the image's xtdb user.
          (when volume? ["-v" (str (volume-name name) ":/var/lib/xtdb")])
          (if pod
            ["--pod" pod]
            ["-p" (str "127.0.0.1:" pg-port ":5432")
             "-p" (str "127.0.0.1:" flight-port ":9832")
             "-p" (str "127.0.0.1:" health-port ":8080")])
          ["-v" (str (fs/absolutize config) ":/usr/local/lib/xtdb/config.yaml:ro,Z")
           image]))
  (loop [i 0]
    (cond
      (ready? health-port) (println name "ready")
      (or (> i 180) (not= "true" (out "podman" "container" "inspect" "--format" "{{.State.Running}}" name)))
      (throw (ex-info (str name " did not become ready:\n" (out "podman" "logs" "--tail" "30" name)) {}))
      :else (do (Thread/sleep 1000) (recur (inc i))))))

(defn- server-memory-notes
  "Peak memory and page cache of the server's cgroup, and XTDB's cache
   metrics: evidence that the dataset was not held in memory."
  [{:keys [name health-port memory]}]
  (let [cg    (out "podman" "exec" name "sh" "-c"
                   "cat /sys/fs/cgroup/memory.peak; grep -E '^(anon|file) ' /sys/fs/cgroup/memory.stat; grep -E '^(max|oom_kill) ' /sys/fs/cgroup/memory.events")
        data  (some-> (out "podman" "exec" name "sh" "-c" "du -sb /var/lib/xtdb | cut -f1") parse-long)
        [peak & stat] (some-> cg str/split-lines)
        kv    (into {} (for [l stat :let [[k v] (str/split l #" ")]] [k (parse-long v)]))
        gib   #(when % (format "%.2fG" (/ (double %) (* 1024 1024 1024))))
        cache (try (->> (str/split-lines (:body (http/get (str "http://127.0.0.1:" health-port "/metrics")
                                                       {:throw false :timeout 5000})))
                        (filter #(re-find #"(?i)^[a-z_]*(memory_cache|memorycache|buffer_pool|disk_cache)[a-z_]*(\{|\s)" %))
                        (remove #(str/starts-with? % "#"))
                        (take 6))
                   (catch Exception _ nil))]
    (cond-> []
      peak  (conj (str "server cgroup: peak " (gib (parse-long (str/trim peak))) " of " memory " limit; at the end "
                       (gib (kv "anon")) " anonymous, " (gib (kv "file")) " page cache"
                       (when (and data (kv "file") (pos? data))
                         (format " against %s of data on disk (%.0f%%)"
                                 (gib data) (* 100.0 (/ (double (kv "file")) data))))
                       "; the limit was reached " (kv "max") " time(s), " (kv "oom_kill") " OOM kill(s)"))
      (seq cache) (conj (str "XTDB cache metrics at the end: " (str/join "; " cache))))))

(defn- stop-server!
  "Saves the server's log, removes the container (and its pod and volume),
   and returns notes for the summary: memory evidence, and whether the server
   was no longer running, e.g. killed by the OOM killer."
  [{:keys [name volume? pod memory] :as srv} log-file]
  (spit log-file (str (:out (p/shell {:out :string :err :out :continue true} "podman" "logs" name))))
  (when pod
    (spit log-file (str "\n--- Kafka topics and end offsets: the log went through Kafka\n"
                        (apply out (kafka-cmd pod "kafka-get-offsets.sh")))
          :append true))
  (let [state (out "podman" "container" "inspect" "--format"
                   "{{.State.Running}} {{.State.ExitCode}} {{.State.OOMKilled}}" name)
        mem   (when (and memory (str/starts-with? (str state) "true")) (server-memory-notes srv))]
    (when volume?
      (spit log-file (str "\n--- /var/lib/xtdb on disk at the end of the run\n"
                          (out "podman" "exec" name "sh" "-c" "du -sh /var/lib/xtdb/* 2>/dev/null"))
            :append true))
    (when (seq mem)
      (spit log-file (str "\n--- memory\n" (str/join "\n" mem) "\n") :append true))
    (remove-own-container! name)
    (when pod (remove-own-pod! pod))
    (when volume? (remove-own-volume! (volume-name name)))
    (concat mem
            (when-let [[_ code oom] (some->> state (re-matches #"false (\S+) (\S+)"))]
              [(str "server container had exited with code " code
                    (when (= "true" oom) " (OOM-killed)")
                    "; a kernel OOM kill shows in `journalctl -k`")]))))

;; ── clojure ─────────────────────────────────────────────────────────────────

(defn- clojure-cmd
  "A `clojure` invocation for `version`: `jvm-opts`, then `exec-flag` (-M, -X
   or -A) on the bench's :xtdb alias, with xtdb-core and xtdb-api overridden
   to the version's client, from its :client-repo, when it has one."
  [{:keys [client client-repo]} jvm-opts exec-flag]
  (concat ["clojure"]
          (when client
            ["-Sdeps" (pr-str {:mvn/repos (if client-repo {"xtdb-client" {:url client-repo}} {})
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
        kimage  (str image "-aws")
        result  {:image image :kafka-image kimage :client version :local-image? true
                 :client-repo (str "file://" (fs/absolutize xtdb-m2))
                 :source (str repo " @ " sha)}
        built?  (and (out "podman" "image" "exists" image)
                     (out "podman" "image" "exists" kimage)
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
                                ":docker:standalone:shadowJar" ":docker:aws:shadowJar"
                                ":xtdb-api:jar" ":xtdb-core:jar"
                                ":xtdb-api:generatePomFileForMavenPublication"
                                ":xtdb-core:generatePomFileForMavenPublication"]
                               (str dir "/build-xtdb.out") timeout-min)]
        (when (not= 0 exit)
          (throw (ex-info (str "XTDB Gradle build failed (exit " exit "); see " dir "/build-xtdb.out") {})))
        (install-client! version)
        ;; The standalone image for the disk-log runs, the aws one (which
        ;; carries the Kafka module) for the Kafka-log runs.
        (doseq [[variant tag] [["standalone" image] ["aws" kimage]]]
          (let [exit (run-logged! {:dir xtdb-src}
                                  ["podman" "build" "-f" "docker/Dockerfile"
                                   "--build-arg" (str "VARIANT=" variant)
                                   "--build-arg" (str "GIT_SHA=" sha)
                                   "--build-arg" (str "XTDB_VERSION=" version)
                                   "--label" (str label-key "=" label-val)
                                   "-t" tag "."]
                                  (str dir "/build-image-" variant ".out") timeout-min)]
            (when (not= 0 exit)
              (throw (ex-info (str "XTDB " variant " image build failed (exit " exit "); see "
                                   dir "/build-image-" variant ".out") {})))))))
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
   server for `storage` (:disk or :kafka), as round `round`."
  [version dir storage round transport bench-args timeout-min]
  (println "\n== Write benchmark," (name storage) "log, round" round ":" (name transport))
  (let [{:keys [server image-key]} (storages storage)
        srv    (servers server)
        t      (name transport)
        run-id (str t "-" (name storage) "-r" round)
        prefix (str "hs_err_bench_" run-id)
        result (str (fs/absolutize dir) "/writes-" run-id ".edn")
        log    (str dir "/bench-" run-id ".out")
        slog   (str dir "/server-bench-" run-id ".log")]
    (start-server! srv (image-key version))
    (let [load-before (machine-load)
          server-note (volatile! nil)
          exit     (try (run-logged! (concat (clojure-cmd version (concat bench-client-jvm-opts (crash-opts dir prefix)) "-X")
                                             ["fhir-search-bench.writes/run"
                                              ":backend" (str ":xtdb2-" t)
                                              ":xtdb-server" (pr-str (str "127.0.0.1:" (:pg-port srv)))
                                              ":transport" (str transport)
                                              ":out" (pr-str result)]
                                             (when-let [tpl (:attach-template srv)]
                                               [":xtdb-attach-template" (pr-str tpl)])
                                             (when (= :flight-sql transport)
                                               [":xtdb-flight-sql-port" (str (:flight-port srv))])
                                             bench-args)
                                     log timeout-min)
                        (finally (vreset! server-note (doall (stop-server! srv slog)))))
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
       :storage   (name storage)
       :round     round
       :result    res
       :verdict   verdict
       :bugs      (set (map :bug evidence))
       :details   (concat [(str "machine: " load-before " before, " load-after " after")]
                          @server-note
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

(def ^:private own-options #{:focused-only :bench-only :race-secs :timeout-min :sha :repo :rebuild :rounds
                              :storage :version})

(defn- storage-keys
  "The write-benchmark variants `:storage` asks for: :disk, :kafka or :all
   (the default)."
  [own]
  (case (or (:storage own) :all)
    :all   [:disk :kafka]
    :disk  [:disk]
    :kafka [:kafka]
    (throw (ex-info "Pass :storage :disk, :kafka or :all" {:storage (:storage own)}))))

;; Read as plain strings, so `:sha 9f8553ce2c3c...` needs no EDN quoting.
(def ^:private string-options #{:sha :repo :version})

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

(defn- summary [label version server kafka-server client {:keys [cases server-evidence]} bench dir]
  (let [control-failed (concat (filter #(and (re-find #"control" (:id %)) (not= "PASS" (:verdict %))) cases)
                               (filter #(and (= "pgwire" (:transport %)) (not= "PASS" (:verdict %))) bench))
        bugs   (sort-by str (into (bugs-in-focused cases)
                                      (mapcat :bugs (filter #(= "flight-sql" (:transport %)) bench))))
        lines  (concat
                ["" (str "XTDB FlightSQL repro: " label)]
                (when-let [src (:source version)] [(str "  source  " src)])
                [(str "  server  " (:image version) " = " server)]
                (when kafka-server
                  [(str "  server, Kafka log  " (:kafka-image version) " = " kafka-server)])
                [(str "  client  " client)]
                (when (seq cases)
                  (concat ["" "Focused cases (written over pgwire; each FlightSQL read compared with pgwire)"]
                          (for [{:keys [id verdict detail]} cases]
                            (format "  %-5s %-40s %s" verdict id detail))
                          (for [{:keys [line]} server-evidence] (str "        " line))))
                (when (seq bench)
                  (concat ["" "Write benchmark (fhir-search-bench.writes against the server)"]
                          (mapcat (fn [{:keys [transport storage round verdict details]}]
                                    (cons (format "  %-5s %s, %s log, round %d" verdict transport storage round)
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
                kserver (when (and (not (:focused-only own)) (some #{:kafka} (storage-keys own)))
                          (image-info (:kafka-image version)))
                client  (client-info version)
                _       (println "Server image:" server "\nClient:" client)
                timeout (or (:timeout-min own) 60)
                focused (when-not (:bench-only own)
                          (focused! version dir (or (:race-secs own) 20) timeout))
                ;; ABBA: round 1 runs pgwire first, round 2 flight-sql first,
                ;; and so on, so neither transport always gets the warmer
                ;; machine or the later slot.
                bench   (when-not (:focused-only own)
                          (vec (for [storage (storage-keys own)
                                     round   (range 1 (inc (or (:rounds own) 2)))
                                     t       (cond-> [:pgwire :flight-sql] (even? round) reverse)]
                                 (bench! version dir storage round t bench-args timeout))))
                cmps    (for [storage (distinct (map :storage bench))
                              :let [c (comparison (filter #(= storage (:storage %)) bench))]
                              :when c]
                          [storage c])
                text    (str (summary label version server kserver client focused bench dir)
                             (apply str (for [[storage c] cmps]
                                          (str "\n\nflight-sql vs pgwire, " storage " log\n\n" c))))]
            (println text)
            (spit (str dir "/summary.txt") (str text "\n"))
            (doseq [[storage c] cmps]
              (spit (str dir "/COMPARISON-" storage ".md")
                    (str "# flight-sql vs pgwire, " storage " log: " label "\n\n" c "\n")))
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

(defn- pull-images!
  "Pulls the images the run needs, failing with a clear message when one is
   not published."
  [version parsed]
  (let [kafka? (and (not (get-in parsed [:own :focused-only]))
                    (some #{:kafka} (storage-keys (:own parsed))))]
    (doseq [image (cond-> [(:image version)] kafka? (conj (:kafka-image version) kafka-broker-image))]
      (println "Pulling" image)
      (when-not (zero? (:exit (p/shell {:continue true} "podman" "pull" image)))
        (throw (ex-info (str image " could not be pulled: is it published?") {:image image}))))))

(defn- check-maven-client!
  "Fails unless xtdb-core and xtdb-api `v` are on Maven Central."
  [v]
  (doseq [artifact ["xtdb-core" "xtdb-api"]
          :let [url (str "https://repo1.maven.org/maven2/com/xtdb/" artifact "/" v "/" artifact "-" v ".pom")
                status (try (:status (http/head url {:throw false :timeout 20000}))
                            (catch Exception _ nil))]]
    (when-not (= 200 status)
      (throw (ex-info (str "com.xtdb/" artifact " " v " is not on Maven Central (" url " answered " status ")")
                      {:artifact artifact :version v})))))

(defn repro!
  "Entry point of `bb flightsql-repro-nightly` and `-beta3`."
  [version-key args]
  (let [version (or (versions version-key) (throw (ex-info "Unknown XTDB version" {:version version-key})))
        dir     (str "target/flightsql-repro/" (name version-key))
        parsed  (prepare! args dir)]
    (pull-images! version parsed)
    (run-repro! (name version-key) version dir parsed)))

(defn repro-release!
  "Entry point of `bb flightsql-repro-release :version <v>`: the repro against a
   published XTDB release, server images from ghcr.io and client from Maven
   Central, both at `v`."
  [args]
  (let [v (get-in (parse-args args) [:own :version])]
    (when (str/blank? v)
      (throw (ex-info "Pass :version, e.g. bb flightsql-repro-release :version 2.2.0" {})))
    (check-maven-client! v)
    (let [version (release-version v)
          dir     (str "target/flightsql-repro/" v)
          parsed  (prepare! args dir)]
      (pull-images! version parsed)
      (run-repro! v version dir parsed))))

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
