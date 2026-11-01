(ns server.inferno-setup
  "Setup shared by the Inferno test kits the `inferno-*` bb tasks drive.

   Two kits live side by side, each a gitignored clone with its own docker
   compose project:

   - `inferno-test-kit/`: the US Core test kit plus the tracked custom suites
     (base-fhir-r4) and the bulk_data_test_kit gem (`bb inferno-setup`).
   - `inferno-g10-kit/`: the ONC (g)(10) Standardized API certification kit,
     cloned at a pinned release tag (`bb inferno-g10-setup`). It is a separate
     clone because it pins its own inferno_core, us_core_test_kit,
     smart_app_launch_test_kit, bulk_data_test_kit and tls_test_kit versions,
     which conflict with the US Core kit's lockfile (v8.1.0 locks inferno_core
     1.6.0 and smart_app_launch_test_kit 1.0.3; the US Core clone locks 1.0.6
     and 1.0.0).

   Both kits get the same container patch ([[patch-compose]]): the mkcert root
   CA mounted and trusted, host-gateway names so the containers reach servers
   on the host, and an nginx host port that does not collide with the other
   kit."
  (:require [babashka.process :refer [shell]]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def us-core-kit-dir "inferno-test-kit")
(def g10-kit-dir "inferno-g10-kit")

(def g10-kit-repo "https://github.com/onc-healthit/onc-certification-g10-test-kit")

(def g10-kit-tag
  "Pinned release of the (g)(10) kit. Bump deliberately: the suite option ids,
   group short ids and required capability lists the runner relies on are
   read from this release."
  "v8.1.0")

(def us-core-nginx-port 8080)
(def g10-default-nginx-port 8081)

(def container-root-ca "/opt/inferno/rootCA.pem")

(def default-extra-host-names
  "Names every patched container resolves to the host. fhir.local is the
   dromon test-server's TLS name (`bb tls-setup`)."
  ["host.docker.internal" "fhir.local"])

(defn split-list
  "A space- or comma-separated env value as a vector of non-blank tokens."
  [s]
  (into [] (remove str/blank?) (str/split (str s) #"[,\s]+")))

(defn extra-host-names
  "The host names to map to the host gateway: the defaults plus those named in
   INFERNO_EXTRA_HOSTS (space or comma separated), which is how an external
   target's development host names reach the containers. A token that is not
   a plain host name is rejected rather than written into the compose file."
  [env]
  (let [extra (split-list (get env "INFERNO_EXTRA_HOSTS"))]
    (doseq [h extra]
      (when-not (re-matches #"[A-Za-z0-9]([A-Za-z0-9\-\.]*[A-Za-z0-9])?" h)
        (throw (ex-info (str "INFERNO_EXTRA_HOSTS entry is not a host name: " (pr-str h))
                        {:entry h}))))
    (into [] (distinct) (concat default-extra-host-names extra))))

(defn nginx-port
  "The host port a kit's nginx is published on. The g10 kit defaults to 8081
   so it can run next to the US Core kit (8080); INFERNO_G10_PORT overrides."
  [kit env]
  (case kit
    :us-core us-core-nginx-port
    :g10 (let [raw (get env "INFERNO_G10_PORT")]
           (if (str/blank? raw)
             g10-default-nginx-port
             (let [p (parse-long (str/trim raw))]
               (when-not (and p (< 0 p 65536))
                 (throw (ex-info (str "INFERNO_G10_PORT is not a port: " (pr-str raw)) {:value raw})))
               p)))))

(defn- env-key [entry] (first (str/split (str entry) #"=" 2)))

(defn- set-env
  "Sets `kvs` ({name value}) in a compose `environment` list of NAME=value
   strings, replacing any entry with the same name and dropping duplicates."
  [env-list kvs]
  (let [names (set (keys kvs))
        kept  (into [] (comp (remove #(contains? names (env-key %))) (distinct)) env-list)]
    (into kept (map (fn [[k v]] (str k "=" v))) (sort-by key kvs))))

(defn- add-distinct [xs x]
  (into [] (distinct) (conj (vec xs) x)))

(defn patch-compose
  "The kit's docker-compose.yml (parsed) with the inferno and worker services
   patched: root CA bind mount and SSL_CERT_FILE, host-gateway names, and any
   extra `environment` entries; nginx published on `nginx-port`. Idempotent:
   patching a patched file yields the same map, and duplicate volume or
   environment entries left by earlier non-idempotent patches are collapsed."
  [compose {:keys [nginx-port extra-host-names environment]}]
  (let [patch-svc (fn [svc]
                    (-> svc
                        (assoc :extra_hosts (mapv #(str % ":host-gateway") extra-host-names))
                        (update :volumes add-distinct (str "./rootCA.pem:" container-root-ca))
                        (update :environment set-env (merge {"SSL_CERT_FILE" container-root-ca}
                                                            environment))))]
    (-> compose
        (update-in [:services :inferno] patch-svc)
        (update-in [:services :worker] patch-svc)
        (assoc-in [:services :nginx :ports] [(str nginx-port ":80")]))))

(defn patch-background-compose
  "The kit's docker-compose.background.yml (parsed) with nginx on
   `nginx-port`. With `drop-redis-port?` redis is no longer published on the
   host: the kit's containers reach it on the compose network, and a second
   kit publishing 6379 would collide with the first."
  [compose {:keys [nginx-port drop-redis-port?]}]
  (cond-> (assoc-in compose [:services :nginx :ports] [(str nginx-port ":80")])
    (and drop-redis-port? (get-in compose [:services :redis]))
    (update-in [:services :redis] dissoc :ports)))

(defn- rewrite-yaml!
  "Applies `f` to the YAML at `path`; writes only when the result differs, so
   an already patched file is left byte-for-byte alone."
  [path f]
  (let [before (yaml/parse-string (slurp path))
        after  (f before)]
    (if (= before after)
      false
      (do (spit path (yaml/generate-string after :dumper-options {:flow-style :block}))
          true))))

(defn patch-compose-files!
  "Patches both compose files of the kit in `kit-dir` (see [[patch-compose]]
   and [[patch-background-compose]]). Returns true when anything changed."
  [kit-dir opts]
  (let [main (str kit-dir "/docker-compose.yml")
        bg   (str kit-dir "/docker-compose.background.yml")
        a    (rewrite-yaml! main #(patch-compose % opts))
        b    (rewrite-yaml! bg #(patch-background-compose % opts))]
    (println (if (or a b) "  compose files patched." "  compose files already patched."))
    (boolean (or a b))))

(defn ensure-root-ca!
  "Copies mkcert's rootCA.pem into `kit-dir` before any container starts. The
   compose patch bind-mounts it, and Podman silently creates an empty
   directory at a missing bind source, which leaves the in-container path
   unreadable as a certificate and fails every TLS handshake from Ruby. A stub
   directory left by such a start is removed first."
  [kit-dir]
  (let [f (io/file kit-dir "rootCA.pem")]
    (when (and (.exists f) (.isDirectory f))
      (println "Removing stub rootCA.pem directory left by a Podman bind mount...")
      (shell "rm" "-rf" (.getPath f)))
    (when-not (.isFile f)
      (let [caroot (str/trim (:out (shell {:out :string} "mkcert" "-CAROOT")))]
        (println "Copying mkcert rootCA.pem into" (str kit-dir "/") "...")
        (io/copy (io/file caroot "rootCA.pem") f)))))

(defn- run-kit-setup-script! [kit-dir]
  (println "Running" (str kit-dir "/setup.sh") "...")
  (shell {:dir kit-dir} "./setup.sh")
  (let [keep-file (io/file kit-dir "data/redis/.keep")]
    (when (.exists keep-file)
      (println "Removing redis .keep file to allow permission fixes...")
      (io/delete-file keep-file true))))

;; ── US Core kit ───────────────────────────────────────────────────────────────

(defn install-custom-suites!
  "Copies the tracked custom Inferno suites into the (gitignored) kit clone so
   Inferno auto-loads them from lib/*.rb. Idempotent and cheap; run every time
   so a fresh checkout gets them even when the kit itself is already set up."
  []
  (let [src "inferno-custom/base-fhir-r4"
        lib (str us-core-kit-dir "/lib")]
    (when (.isDirectory (io/file src))
      (println "Installing base-fhir-r4 custom suite into" lib "...")
      (.mkdirs (io/file lib "base_fhir_r4"))
      (io/copy (io/file src "base_fhir_r4_test_kit.rb")
               (io/file lib "base_fhir_r4_test_kit.rb"))
      (doseq [f (.listFiles (io/file src "base_fhir_r4"))
              :when (and (.isFile f) (str/ends-with? (.getName f) ".rb"))]
        (io/copy f (io/file lib "base_fhir_r4" (.getName f)))))))

(defn ensure-bulk-gem!
  "Wires the Inferno bulk_data_test_kit into the US Core kit so the
   bulk_data_v200/bulk_data_v101 suites become available:
     1. Append `gem \"bulk_data_test_kit\"` to the Gemfile so the image build's
        `bundle install` pulls it in. Unpinned, so Bundler's conservative
        resolution keeps inferno_core and the other locked kits untouched
        (resolves to 0.13.0).
     2. Drop a loader file into lib/ that requires the gem. Inferno's suite
        boot only globs the app's own lib/*.rb (gem suites are not
        auto-loaded), so without this shim the installed gem registers no
        suites.
   Both steps are idempotent."
  []
  (let [gemfile (io/file us-core-kit-dir "Gemfile")
        loader  (io/file us-core-kit-dir "lib/bulk_data_test_kit_loader.rb")]
    (when (.isFile gemfile)
      (let [contents (slurp gemfile)]
        (when-not (str/includes? contents "bulk_data_test_kit")
          (println "Appending bulk_data_test_kit gem to" (.getPath gemfile) "...")
          (spit gemfile (str (str/trimr contents) "\n\ngem \"bulk_data_test_kit\"\n"))))
      (let [loader-src "require \"bulk_data_test_kit\"\n"]
        (when-not (and (.isFile loader) (= loader-src (slurp loader)))
          (println "Writing bulk_data_test_kit loader into" (.getPath loader) "...")
          (.mkdirs (.getParentFile loader))
          (spit loader loader-src))))))

(defn- us-core-initialised?
  "True once the US Core kit's setup.sh has run. Its compose file only gains
   the root CA mount from the patch this namespace applies after setup.sh."
  []
  (let [f (io/file us-core-kit-dir "docker-compose.yml")]
    (and (.isFile f) (str/includes? (slurp f) container-root-ca))))

(defn us-core-setup!
  "Body of `bb inferno-setup`: clone the US Core kit, install the custom
   suites and the bulk gem, run the kit's setup once, patch the compose files
   and rebuild the image so the custom suites are baked in."
  [env]
  (when-not (.exists (io/file us-core-kit-dir))
    (println "Cloning us-core-test-kit...")
    (shell "git" "clone" "https://github.com/inferno-framework/us-core-test-kit" us-core-kit-dir))
  (ensure-root-ca! us-core-kit-dir)
  (install-custom-suites!)
  (ensure-bulk-gem!)
  (when-not (us-core-initialised?)
    (run-kit-setup-script! us-core-kit-dir))
  (println "Patching docker-compose files...")
  (patch-compose-files! us-core-kit-dir {:nginx-port       us-core-nginx-port
                                         :extra-host-names (extra-host-names env)})
  ;; Bake the custom suites into the image (cache-friendly; a no-op when lib/
  ;; is unchanged).
  (println "Rebuilding inferno image so custom suites are present...")
  (shell {:dir us-core-kit-dir} "docker" "compose" "build" "inferno" "worker"))

;; ── (g)(10) kit ───────────────────────────────────────────────────────────────

(defn- git-out [dir & args]
  (str/trim (:out (apply shell {:dir dir :out :string :err :string :continue true} "git" args))))

(defn ensure-g10-clone!
  "Clones the (g)(10) kit at [[g10-kit-tag]], or fails when an existing clone
   is checked out at anything else (a stale clone would silently run other
   suite ids). Delete inferno-g10-kit/ to move to a new tag."
  []
  (if-not (.exists (io/file g10-kit-dir))
    (do (println "Cloning" g10-kit-repo "at" g10-kit-tag "...")
        (shell "git" "-c" "advice.detachedHead=false" "clone" "--depth" "1"
               "--branch" g10-kit-tag g10-kit-repo g10-kit-dir))
    (let [head (git-out g10-kit-dir "describe" "--tags" "--exact-match" "HEAD")]
      (when-not (= head g10-kit-tag)
        (throw (ex-info (str g10-kit-dir " is checked out at " (pr-str head) ", not " g10-kit-tag
                             ". Remove the directory and run bb inferno-g10-setup again.")
                        {:dir g10-kit-dir :head head :expected g10-kit-tag})))
      (println g10-kit-dir "already at" g10-kit-tag))))

(defn g10-inferno-base-url
  "Inferno's own base url for the g10 kit, as the browser on the host sees it.
   REDIRECT_URI and LAUNCH_URI derive from it, and clients are registered with
   exactly those, so the container must be told the published port."
  [env]
  (str "http://localhost:" (nginx-port :g10 env)))

(def ^:private g10-setup-marker "data/.dromon-g10-setup-done")

(defn g10-setup!
  "Body of `bb inferno-g10-setup`: clone the pinned (g)(10) kit next to the US
   Core kit, patch the compose files (root CA, host-gateway names, nginx on
   INFERNO_G10_PORT, redis not published, INFERNO_HOST set to the published
   base url), run its setup.sh once (pull, build, migrate) and rebuild the
   image so presets under config/presets are baked in."
  [env]
  (ensure-g10-clone!)
  (ensure-root-ca! g10-kit-dir)
  ;; Patched before setup.sh, which starts containers to migrate: the stock
  ;; compose files publish nginx on 80 and redis on 6379.
  (println "Patching docker-compose files...")
  (patch-compose-files! g10-kit-dir {:nginx-port       (nginx-port :g10 env)
                                     :drop-redis-port? true
                                     :extra-host-names (extra-host-names env)
                                     :environment      {"INFERNO_HOST" (g10-inferno-base-url env)}})
  (if (.exists (io/file g10-kit-dir g10-setup-marker))
    (println "g10 kit already initialised -- skipping setup.sh.")
    (do (run-kit-setup-script! g10-kit-dir)
        (spit (io/file g10-kit-dir g10-setup-marker) g10-kit-tag)))
  (println "Rebuilding g10 inferno image...")
  (shell {:dir g10-kit-dir} "docker" "compose" "build" "inferno" "worker")
  (println)
  (println "(g)(10) kit ready. Start it with: (cd" g10-kit-dir "&& docker compose up -d)")
  (println "Web UI:" (str (g10-inferno-base-url env) "/suites/g10_certification")))
