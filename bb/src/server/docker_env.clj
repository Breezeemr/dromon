(ns server.docker-env
  (:require [babashka.process :refer [shell process check]]
            [clojure.string :as str]))

(def network-name "ory-net")
(def pwd (System/getProperty "user.dir"))
(def pg-password (or (System/getenv "POSTGRES_PASSWORD") "secret"))
(def pg-dsn-base (str "postgres://ory:" pg-password "@ory-pg:5432/"))

(defn- otel-enabled? []
  (= "1" (System/getenv "DROMON_OTEL")))

(defn container-running? [name]
  "Returns true only if the container exists AND is currently running.
   A stopped container returns false, triggering re-creation in start!."
  (let [{:keys [exit out]} @(process ["docker" "inspect" "--format={{.State.Running}}" name]
                                     {:out :string})]
    (and (zero? exit) (= "true" (str/trim out)))))

(defn container-exists? [name]
  "Returns true if the container exists (running OR stopped)."
  (let [{:keys [exit]} @(process ["docker" "inspect" "--format={{.Name}}" name]
                                 {:out :string :err :string})]
    (zero? exit)))

(defn network-exists? []
  (let [{:keys [exit]} @(process ["docker" "network" "inspect" network-name])]
    (zero? exit)))

(defn- assert-container-up!
  "Verifies a long-running container is still up shortly after `docker run -d`.
   Podman's `docker run` returns 0 as soon as the container is created, even if
   it crashes immediately afterwards on a config error. Without this check, a
   broken container is silently absent from the pool and downstream tests fail
   in confusing ways. Throws an ex-info with the container's logs on failure."
  [name]
  (Thread/sleep 1500)
  (when-not (container-running? name)
    (let [{:keys [out err]} @(process ["docker" "logs" name]
                                      {:out :string :err :string})]
      (throw (ex-info (str "Container " name " failed to start")
                      {:container name
                       :stdout    out
                       :stderr    err})))))

(defn hydra-run-args
  "The `docker run` argv that starts the Hydra container from docker/hydra.yml.
   Shared by `start!`, `restart-hydra!` and `start-auth-stack!` so all stay in
   sync. `extra-docker-args` are spliced in before the image name (e.g. -e
   overrides, --add-host)."
  ([] (hydra-run-args []))
  ([extra-docker-args]
   (concat
     ["docker" "run" "-d" "--name" "hydra" "--network" network-name
      "--memory" "128m" "--memory-swap" "128m" "--cpus" "0.5"
      "-p" "4444:4444" "-p" "4445:4445"
      "-v" (str pwd "/docker/hydra.yml:/etc/config/hydra/hydra.yml")
      "-e" (str "DSN=" pg-dsn-base "hydra?sslmode=disable")]
     extra-docker-args
     ["docker.io/oryd/hydra:v2.2.0" "serve" "all" "-c" "/etc/config/hydra/hydra.yml" "--dev"])))

(defn restart-hydra!
  "Recreates the Hydra container from the current docker/hydra.yml. Used to pick
   up an issuer (or other config) change without a full environment teardown.
   Idempotent: removes any existing container first, then re-runs it."
  []
  (println "Recreating Hydra container from docker/hydra.yml...")
  (when (container-exists? "hydra")
    (shell "docker" "rm" "-f" "hydra"))
  (apply shell (hydra-run-args))
  (assert-container-up! "hydra"))

(defn keto-run-args
  "The `docker run` argv that starts the Keto container from docker/keto.yml.
   Shared by `start!`, `restart-keto!` and `start-auth-stack!`. With
   `:publish-ports? false` the read/write ports stay inside the docker
   network, for the auth stack's TLS terminator to front."
  ([] (keto-run-args {}))
  ([{:keys [publish-ports?] :or {publish-ports? true}}]
   (concat
     ["docker" "run" "-d" "--name" "keto" "--network" network-name
      "--memory" "128m" "--memory-swap" "128m" "--cpus" "1.0"]
     (when publish-ports? ["-p" "4466:4466" "-p" "4467:4467"])
     ["-v" (str pwd "/docker/keto.yml:/etc/config/keto/keto.yml")
      "-v" (str pwd "/docker/namespaces.ts:/etc/config/keto/namespaces.ts")
      "-e" (str "DSN=" pg-dsn-base "keto?sslmode=disable")
      "docker.io/oryd/keto:v0.12.0" "serve" "-c" "/etc/config/keto/keto.yml"])))

(defn restart-keto!
  "Recreates the Keto container from docker/keto.yml alone: ports published,
   plain HTTP on 4466/4467, the shape the Inferno harness and `bb setup`
   expect. Idempotent."
  []
  (println "Recreating Keto container from docker/keto.yml...")
  (when (container-exists? "keto")
    (shell "docker" "rm" "-f" "keto"))
  (apply shell (keto-run-args))
  (assert-container-up! "keto"))

;; ── Ory TLS for the auth stack ───────────────────────────────────────────────
;; `start-auth-stack!` serves Hydra and Kratos over TLS natively and Keto
;; behind a TLS terminator, from one mkcert certificate. The HOSTNAMES are a
;; parameter: this repo's own default is `localhost` (TLS on localhost, no
;; /etc/hosts dependency), and master-at-arms2 starts the same stack under its
;; local*.breezeehr.com names through its root `bb auth-stack-up`, which sets
;; HYDRA_HOST, KRATOS_HOST, KETO_HOST and LOGIN_APP_BASE_URL.
;;
;; Two things ride on the names and are why they are printed at the end:
;; Hydra's issuer is compared VERBATIM by every relying party, and Kratos
;; bakes its base URL into every URL it hands the browser. A relying party
;; configured for one set of names cannot log in against a stack started with
;; the other.
;;
;; The main pool (`start!`, `bb setup`) is untouched: it is the Inferno
;; harness's stack, whose containers reach Hydra as http://hydra:4444 through
;; the nginx terminator below, and it never faces a browser.

(def default-ory-hosts
  "This repo's own names for the three services: TLS on localhost."
  {:hydra "localhost" :kratos "localhost" :keto "localhost"})

(defn ory-hosts-from-env
  "The hostnames to serve under. ORY_HOST names all three at once;
   HYDRA_HOST, KRATOS_HOST and KETO_HOST name one each and win over it."
  []
  (let [all (System/getenv "ORY_HOST")]
    {:hydra  (or (System/getenv "HYDRA_HOST") all (:hydra default-ory-hosts))
     :kratos (or (System/getenv "KRATOS_HOST") all (:kratos default-ory-hosts))
     :keto   (or (System/getenv "KETO_HOST") all (:keto default-ory-hosts))}))

(defn hydra-issuer
  "Hydra's issuer for a given Hydra host: compared VERBATIM against the id_token
   `iss` by every relying party (flotilla's `hydra-issuer`, the BFF harness's
   `:issuer`), trailing slash included."
  [hydra-host]
  (str "https://" hydra-host ":4444/"))

(defn ory-urls
  "Every URL the stack serves, for a hosts map."
  [{:keys [hydra kratos keto]}]
  {:hydra-public  (str "https://" hydra ":4444")
   :hydra-admin   (str "https://" hydra ":4445")
   :kratos-public (str "https://" kratos ":4433")
   :kratos-admin  (str "https://" kratos ":4434")
   :keto-read     (str "https://" keto ":4466")
   :keto-write    (str "https://" keto ":4467")})

(def ^:private ory-tls-dir (str pwd "/docker/tls"))
(def ^:private ory-tls-mount "/etc/ory/tls")
(def ^:private ory-cert-path (str ory-tls-mount "/ory.pem"))
(def ^:private ory-key-path (str ory-tls-mount "/ory-key.pem"))

(def ^:private ory-tls-docker-args
  "Mounts the certificate pair read-only into a container."
  ["-v" (str ory-tls-dir ":" ory-tls-mount ":ro")])

(def ^:private ory-cert-baseline-sans
  "Names every minted pair carries whatever the configured hosts are, so a
   pair minted for one set of names still serves the other."
  ["*.breezeehr.com" "localhost" "127.0.0.1" "::1"])

(defn- cert-sans
  "DNS names in the certificate's subjectAltName, or nil when openssl is not
   available to read them."
  [cert-path]
  (let [{:keys [exit out]} @(process ["openssl" "x509" "-in" cert-path "-noout" "-ext" "subjectAltName"]
                                     {:out :string :err :string})]
    (when (zero? exit)
      (map second (re-seq #"DNS:([^,\s]+)" out)))))

(defn- san-covers? [san host]
  (or (= san host)
      (and (str/starts-with? san "*.")
           (let [suffix (subs san 1)]
             (and (str/ends-with? host suffix)
                  (not (str/includes? (subs host 0 (- (count host) (count suffix))) ".")))))))

(defn ensure-ory-tls-cert!
  "Mints docker/tls/ory.pem and ory-key.pem with mkcert for the configured
   hosts plus the baseline names. These are the same files the repo-root
   `bb cert:setup ory` in master-at-arms2 writes, so either path yields a pair
   both sets of names verify against. The key is made world-readable because
   the Ory images run as a non-root user and read it through the bind mount.

   A pair that is present but does not cover a configured host (checked
   through openssl when available) is re-minted."
  [hosts]
  (let [cert  (java.io.File. ory-tls-dir "ory.pem")
        key   (java.io.File. ory-tls-dir "ory-key.pem")
        names (distinct (concat (vals hosts) ory-cert-baseline-sans))
        present? (and (.isFile cert) (.isFile key))
        covers?  (when present?
                   (if-let [sans (cert-sans (.getPath cert))]
                     (every? (fn [h] (some #(san-covers? % h) sans)) (vals hosts))
                     true))]
    (when (and present? (not covers?))
      (println "The Ory certificate does not cover" (str/join ", " (vals hosts)) "-- re-minting."))
    (when-not (and present? covers?)
      (println "Minting the Ory TLS certificate via mkcert for" (str/join ", " names) "...")
      (.mkdirs (java.io.File. ory-tls-dir))
      (apply shell "mkcert" "-cert-file" (.getPath cert) "-key-file" (.getPath key) names)
      (shell "chmod" "644" (.getPath key)))))

(defn- hosts-entry-present? [contents host]
  (boolean
    (some (fn [line]
            (let [[addr & names] (-> line (str/split #"#" 2) first str/trim (str/split #"\s+"))]
              (and (= addr "127.0.0.1") (some #{host} names))))
          (str/split-lines contents))))

(defn- loopback-name?
  "localhost and IP literals resolve without /etc/hosts."
  [host]
  (or (= "localhost" host)
      (re-matches #"[0-9.]+|\[?[0-9a-fA-F:]+\]?" host)))

(defn- warn-missing-hosts!
  "Says which configured names /etc/hosts does not resolve yet. Writing the
   file needs sudo, which this task does not take; in master-at-arms2 the
   repo-root `bb cert:setup ory` (or `bb hosts:setup`) does."
  [hosts]
  (let [contents (let [f (java.io.File. "/etc/hosts")] (if (.isFile f) (slurp f) ""))
        missing  (->> (vals hosts) distinct
                      (remove loopback-name?)
                      (remove #(hosts-entry-present? contents %)))]
    (when (seq missing)
      (println "WARNING: not in /etc/hosts:" (str/join ", " missing))
      (println "         add these (master-at-arms2: `bb cert:setup ory` or `bb hosts:setup` at the repo root):")
      (doseq [h missing]
        (println (str "           127.0.0.1 " h))
        (println (str "           ::1 " h))))))

(defn- ory-tls-env
  "`-e` pairs for one Ory listener group: `prefix` is SERVE_TLS (Hydra, both
   listeners) or SERVE_PUBLIC_TLS / SERVE_ADMIN_TLS (Kratos). Keto has no
   working equivalent -- see `keto-tls-run-args`."
  [prefix]
  ["-e" (str prefix "_CERT_PATH=" ory-cert-path)
   "-e" (str prefix "_KEY_PATH=" ory-key-path)])

(defn keto-tls-run-args
  "The `docker run` argv for the nginx TLS terminator in front of Keto
   (docker/keto-tls.conf). Keto's schema documents serve.read.tls and
   serve.write.tls, but its config provider never reads them and both v0.12
   and v26.2 keep serving plain HTTP with a pair configured (verified), so
   the auth stack terminates TLS here instead: Keto's ports are not published
   on the host and this presents https://<keto-host>:4466 (read) and :4467
   (write). The conf names no server_name, so any host in the certificate
   works."
  []
  ["docker" "run" "-d" "--name" "keto-tls" "--network" network-name
   "--memory" "64m" "--memory-swap" "64m"
   "-p" "4466:4466" "-p" "4467:4467"
   "-v" (str pwd "/docker/keto-tls.conf:/etc/nginx/nginx.conf:ro")
   "-v" (str ory-tls-dir ":/etc/nginx/certs:ro")
   "docker.io/library/nginx:latest"])

;; ── Kratos config rendering ──────────────────────────────────────────────────
;; Kratos bakes serve.public.base_url into every URL it hands the browser and
;; checks return_to against selfservice.allowed_return_urls, so both follow
;; the configured names. docker/kratos.yml is the template; the rendered file
;; is what the containers mount. Kratos does no $VAR substitution of its own
;; (see the template's header), and arrays are awkward through its env
;; mapping, which is why this renders a file rather than passing -e pairs.

(def ^:private kratos-template (str pwd "/docker/kratos.yml"))
(def ^:private kratos-rendered-dir (str pwd "/docker/generated"))
(def ^:private kratos-rendered (str kratos-rendered-dir "/kratos.yml"))

(defn render-kratos-config!
  "Writes docker/generated/kratos.yml from the template with the Kratos public
   and admin URLs and the login app's base URL filled in. Returns the path."
  [{:keys [kratos-public kratos-admin]} login-app-base-url]
  (let [rendered (-> (slurp kratos-template)
                     (str/replace "{{KRATOS_PUBLIC_URL}}" kratos-public)
                     (str/replace "{{KRATOS_ADMIN_URL}}" kratos-admin)
                     (str/replace "{{LOGIN_APP_BASE_URL}}" login-app-base-url))]
    (when-let [left (re-find #"\{\{[A-Z_]+\}\}" rendered)]
      (throw (ex-info (str "docker/kratos.yml has a placeholder this renderer does not fill: " left) {})))
    (.mkdirs (java.io.File. kratos-rendered-dir))
    (spit kratos-rendered rendered)
    kratos-rendered))

;; ── Hydra TLS terminator ──────────────────────────────────────────────────────
;; SMART Backend Services requires the token endpoint over TLS. Hydra keeps
;; serving plain HTTP on 4444; this nginx terminator presents HTTPS on
;; https://fhir.local:4443 and proxies to hydra:4444 (see docker/hydra-tls.conf).

(def ^:private tls-cert-dir (str pwd "/docker/tls"))

(defn ensure-hydra-tls-cert!
  "Mints the fhir.local dev TLS cert/key (via mkcert, using the same CA the
   Inferno container trusts) for the Hydra TLS terminator, if not already
   present."
  []
  (let [cert (java.io.File. tls-cert-dir "fhir.local.pem")
        key  (java.io.File. tls-cert-dir "fhir.local-key.pem")]
    (when-not (and (.isFile cert) (.isFile key))
      (println "Minting fhir.local TLS cert for Hydra terminator via mkcert...")
      (.mkdirs (java.io.File. tls-cert-dir))
      (shell "mkcert" "-cert-file" (.getPath cert) "-key-file" (.getPath key) "fhir.local"))))

(defn hydra-tls-run-args
  "The `docker run` argv for the nginx TLS terminator in front of Hydra."
  []
  ["docker" "run" "-d" "--name" "hydra-tls" "--network" network-name
   "--memory" "64m" "--memory-swap" "64m"
   "-p" "4443:4443"
   "-v" (str pwd "/docker/hydra-tls.conf:/etc/nginx/nginx.conf:ro")
   "-v" (str tls-cert-dir ":/etc/nginx/certs:ro")
   "docker.io/library/nginx:latest"])

(defn ensure-hydra-tls-terminator!
  "Ensures the nginx TLS terminator (https://fhir.local:4443 -> hydra:4444),
   which serves the SMART token endpoint over TLS, is running. Idempotent:
   mints the cert if missing and (re)creates the container if not running."
  []
  (ensure-hydra-tls-cert!)
  (when-not (container-running? "hydra-tls")
    (when (container-exists? "hydra-tls")
      (shell "docker" "rm" "-f" "hydra-tls"))
    (apply shell (hydra-tls-run-args))
    (assert-container-up! "hydra-tls")))

;; ── Secondary auth stack: Kratos + login/consent wiring ──────────────────────
;; Opt-in extension of the main pool for the interactive authorization_code
;; flow (docs/tasks/kratos-reintroduce-secondary-auth-path.md). Never started
;; by `start!`; `bb setup` / `bb inferno-test` keep only ory-pg/keto/hydra.

(declare start!)

(def kratos-cookie-secret
  "Cookie-signing secret for the local dev Kratos. Kratos does not substitute
   $VAR placeholders in YAML config values (docs/tasks/kratos-cipher-secret-config.md),
   so docker/kratos.yml carries no secrets block and these are injected via
   Kratos's native env-var mapping. Dev-only value, overridable, same posture
   as the committed dev Postgres password above."
  (or (System/getenv "KRATOS_SECRETS_COOKIE")
      "dev-only-kratos-cookie-secret-0123456789"))

(def kratos-cipher-secret
  "Cipher secret for the local dev Kratos; see kratos-cookie-secret. Kratos
   requires exactly 32 characters for cipher secrets."
  (or (System/getenv "KRATOS_SECRETS_CIPHER")
      "dev-only-kratos-cipher-secret-32"))

(defn- ensure-kratos-database!
  "Creates the kratos database on ory-pg if it does not exist. docker/init-db.sql
   no longer creates it and only runs when the ory-pg container is first
   created, so an existing environment would otherwise fail kratos migrations."
  []
  (let [{:keys [exit out]} @(process ["docker" "exec" "ory-pg" "psql" "-U" "ory" "-tAc"
                                      "SELECT 1 FROM pg_database WHERE datname='kratos'"]
                                     {:out :string :err :string})]
    (when-not (and (zero? exit) (= "1" (str/trim out)))
      (println "Creating kratos database on ory-pg...")
      (shell "docker" "exec" "ory-pg" "psql" "-U" "ory" "-c" "CREATE DATABASE kratos")
      (shell "docker" "exec" "ory-pg" "psql" "-U" "ory" "-c"
             "GRANT ALL PRIVILEGES ON DATABASE kratos TO ory"))))

(defn kratos-run-args
  "The `docker run` argv that starts the Kratos container from the rendered
   config (`render-kratos-config!`). The config names the TLS pair under
   /etc/ory/tls (`serve.public.tls`, `serve.admin.tls`), so the cert dir is
   mounted here."
  []
  (concat
    ["docker" "run" "-d" "--name" "kratos" "--network" network-name
     "--memory" "128m" "--memory-swap" "128m" "--cpus" "0.5"
     "-p" "4433:4433" "-p" "4434:4434"
     "-v" (str kratos-rendered ":/etc/config/kratos/kratos.yml")
     "-v" (str pwd "/docker/identity.schema.json:/etc/config/kratos/identity.schema.json")]
    ory-tls-docker-args
    ["-e" (str "DSN=" pg-dsn-base "kratos?sslmode=disable")
     "-e" (str "SECRETS_COOKIE=" kratos-cookie-secret)
     "-e" (str "SECRETS_CIPHER=" kratos-cipher-secret)
     "docker.io/oryd/kratos:v1.3.0" "serve" "-c" "/etc/config/kratos/kratos.yml" "--dev"]))

(defn start-auth-stack!
  "Starts the secondary auth stack on top of the main pool: ensures
   ory-pg/keto/hydra via `start!`, then brings up Kratos and recreates Hydra
   and Keto serving TLS under the configured names -- by default
   https://localhost:4444 (admin :4445), https://localhost:4433 (admin :4434)
   and https://localhost:4466 (write :4467) -- with Hydra's login/consent URLs
   pointed at the login-consent app on the host. master-at-arms2's root
   `bb auth-stack-up` passes its local*.breezeehr.com names instead.

   The certificate is minted here if missing or not covering the names
   (`ensure-ory-tls-cert!`); a non-loopback name missing from /etc/hosts is
   warned about, not written.

   opts:
   - :hosts               {:hydra :kratos :keto} hostnames (default
                          `default-ory-hosts`; `ory-hosts-from-env` reads
                          ORY_HOST / HYDRA_HOST / KRATOS_HOST / KETO_HOST)
   - :login-app-base-url  base URL Hydra redirects login/consent/logout
                          challenges to, and Kratos returns to. These
                          redirects are followed by the BROWSER, so the URL
                          must be host-reachable (default
                          https://localhost:3001), unlike the token hook
                          which Hydra calls itself and therefore uses
                          host.docker.internal. It must be same-site with
                          the Kratos host: Kratos browser flows ride a cookie
                          the login UI's fetch has to carry.
   - :token-hook-url      when set, Hydra calls this webhook at token mint
                          time (OAUTH2_TOKEN_HOOK_URL) and allows the
                          `patient` top-level claim; point it at a running
                          dromon /auth/token-hook to exercise SMART launch
                          claims end to end

   Boot failures are loud (assert-container-up!): a broken kratos.yml fails
   here instead of leaving a dead container in the pool."
  ([] (start-auth-stack! {}))
  ([{:keys [hosts login-app-base-url token-hook-url]
     :or   {hosts              default-ory-hosts
            login-app-base-url "https://localhost:3001"}}]
   (let [urls   (ory-urls hosts)
         issuer (hydra-issuer (:hydra hosts))]
     (start!)
     (ensure-ory-tls-cert! hosts)
     (warn-missing-hosts! hosts)
     (ensure-kratos-database!)
     (render-kratos-config! urls login-app-base-url)
     (println "Running kratos migrations...")
     (apply shell (concat ["docker" "run" "--rm" "--network" network-name
                           "-v" (str kratos-rendered ":/etc/config/kratos/kratos.yml")
                           "-v" (str pwd "/docker/identity.schema.json:/etc/config/kratos/identity.schema.json")]
                          ory-tls-docker-args
                          ["-e" (str "DSN=" pg-dsn-base "kratos?sslmode=disable")
                           "docker.io/oryd/kratos:v1.3.0"
                           "migrate" "sql" "-e" "--yes" "-c" "/etc/config/kratos/kratos.yml"]))
     (println "Recreating Ory Kratos (TLS," (:kratos-public urls) ")...")
     ;; Always recreated: the rendered config may name different hosts than
     ;; the running container was started with.
     (when (container-exists? "kratos")
       (shell "docker" "rm" "-f" "kratos"))
     (apply shell (kratos-run-args))
     (assert-container-up! "kratos")
     (println "Recreating Keto behind the TLS terminator (" (:keto-read urls) ")...")
     (doseq [c ["keto-tls" "keto"]]
       (when (container-exists? c)
         (shell "docker" "rm" "-f" c)))
     (apply shell (keto-run-args {:publish-ports? false}))
     (assert-container-up! "keto")
     ;; After Keto: nginx resolves the name once at startup.
     (apply shell (keto-tls-run-args))
     (assert-container-up! "keto-tls")
     (println "Recreating Hydra with TLS (issuer" issuer ") and login/consent URLs at"
              login-app-base-url "...")
     (when (container-exists? "hydra")
       (shell "docker" "rm" "-f" "hydra"))
     (apply shell (hydra-run-args
                    (concat ["--add-host" "host.docker.internal:host-gateway"]
                            ory-tls-docker-args
                            ;; One pair for both listeners: SERVE_TLS is the
                            ;; default SERVE_PUBLIC_TLS and SERVE_ADMIN_TLS
                            ;; inherit. Hydra 2.x serves plain HTTP even with a
                            ;; pair configured until enabled is set.
                            (ory-tls-env "SERVE_TLS")
                            ["-e" "SERVE_TLS_ENABLED=true"
                             "-e" (str "URLS_SELF_ISSUER=" issuer)
                             "-e" (str "URLS_LOGIN=" login-app-base-url "/login")
                             "-e" (str "URLS_CONSENT=" login-app-base-url "/consent")
                             "-e" (str "URLS_LOGOUT=" login-app-base-url "/logout")]
                            (when token-hook-url
                              ["-e" (str "OAUTH2_TOKEN_HOOK_URL=" token-hook-url)
                               "-e" "OAUTH2_ALLOWED_TOP_LEVEL_CLAIMS=patient"]))))
     (assert-container-up! "hydra")
     (println "Auth stack started successfully!")
     (println "  Hydra   " (:hydra-public urls) " (admin" (str (:hydra-admin urls) ")"))
     (println "  Kratos  " (:kratos-public urls) "(admin" (str (:kratos-admin urls) ")"))
     (println "  Keto    " (:keto-read urls) "  (write" (str (:keto-write urls) ")"))
     (println "  Login app" login-app-base-url)
     (println "  Issuer  " issuer "-- relying parties must use this string verbatim."))))

(defn stop-auth-stack!
  "Tears down the secondary auth stack additions: removes Kratos and restores
   Hydra and Keto to their plain docker/*.yml configuration, the shape the
   Inferno harness expects. The main pool keeps running."
  []
  (when (container-exists? "kratos")
    (println "Removing kratos container")
    (shell "docker" "rm" "-f" "kratos"))
  (when (container-exists? "hydra")
    (restart-hydra!))
  (when (container-exists? "keto-tls")
    (println "Removing keto-tls terminator")
    (shell "docker" "rm" "-f" "keto-tls"))
  (when (container-exists? "keto")
    (restart-keto!))
  (println "Auth stack stopped."))

(defn start! []
  (println "Starting local integration environment...")
  (when-not (network-exists?)
    (println "Creating docker network" network-name)
    (shell "docker" "network" "create" network-name))

  (println "Starting PostgreSQL...")
  (when-not (container-running? "ory-pg")
    (when (container-exists? "ory-pg")
      (println "ory-pg exists but is stopped — removing and recreating...")
      (shell "docker" "rm" "-f" "ory-pg"))
    (shell "docker" "run" "-d" "--name" "ory-pg" "--network" network-name
           "--memory" "256m" "--memory-swap" "256m" "--cpus" "1.0"
           "-v" (str pwd "/docker/init-db.sql:/docker-entrypoint-initdb.d/init.sql")
           "-e" "POSTGRES_USER=ory"
           "-e" (str "POSTGRES_PASSWORD=" pg-password)
           "-e" "POSTGRES_DB=ory"
           "docker.io/library/postgres:15-alpine")
    (assert-container-up! "ory-pg"))

  ;; wait for pg
  (println "Waiting for PostgreSQL to be ready...")
  (let [max-wait-ms 30000
        poll-ms     500
        deadline    (+ (System/currentTimeMillis) max-wait-ms)]
    (loop []
      (let [{:keys [exit]} @(process ["docker" "exec" "ory-pg" "pg_isready" "-U" "ory"]
                                     {:out :string :err :string})]
        (cond
          (zero? exit)
          (println "PostgreSQL is ready.")

          (> (System/currentTimeMillis) deadline)
          (throw (ex-info "Timed out waiting for PostgreSQL to be ready" {}))

          :else
          (do (Thread/sleep poll-ms) (recur))))))

  ;; Keto
  (println "Running migrations and starting Ory Keto...")
  (shell "docker" "run" "--rm" "--network" network-name
         "-v" (str pwd "/docker/keto.yml:/etc/config/keto/keto.yml")
         "-v" (str pwd "/docker/namespaces.ts:/etc/config/keto/namespaces.ts")
         "-e" (str "DSN=" pg-dsn-base "keto?sslmode=disable")
         "docker.io/oryd/keto:v0.12.0"
         "migrate" "up" "-y" "-c" "/etc/config/keto/keto.yml")
  (when-not (container-running? "keto")
    (when (container-exists? "keto")
      (println "keto exists but is stopped — removing and recreating...")
      (shell "docker" "rm" "-f" "keto"))
    (apply shell (keto-run-args))
    (assert-container-up! "keto"))

  ;; Hydra
  (println "Running migrations and starting Ory Hydra...")
  (shell "docker" "run" "--rm" "--network" network-name
         "-v" (str pwd "/docker/hydra.yml:/etc/config/hydra/hydra.yml")
         "-e" (str "DSN=" pg-dsn-base "hydra?sslmode=disable")
         "docker.io/oryd/hydra:v2.2.0"
         "migrate" "sql" "-e" "-y" "-c" "/etc/config/hydra/hydra.yml")
  (when-not (container-running? "hydra")
    (when (container-exists? "hydra")
      (println "hydra exists but is stopped — removing and recreating...")
      (shell "docker" "rm" "-f" "hydra"))
    (apply shell (hydra-run-args))
    (assert-container-up! "hydra"))

  ;; Jaeger all-in-one (only when DROMON_OTEL=1). Provides OTLP ingest on
  ;; 4317 (gRPC) / 4318 (HTTP) and a UI on 16686. In-memory storage; dev only.
  (when (otel-enabled?)
    (println "Starting Jaeger all-in-one for OpenTelemetry...")
    (when-not (container-running? "jaeger")
      (when (container-exists? "jaeger")
        (println "jaeger exists but is stopped — removing and recreating...")
        (shell "docker" "rm" "-f" "jaeger"))
      (shell "docker" "run" "-d" "--name" "jaeger" "--network" network-name
             "--memory" "256m" "--memory-swap" "256m" "--cpus" "0.5"
             "-e" "COLLECTOR_OTLP_ENABLED=true"
             "-p" "4317:4317" "-p" "4318:4318" "-p" "16686:16686"
             "docker.io/jaegertracing/all-in-one:1.57")
      (assert-container-up! "jaeger"))
    (println "Jaeger UI available at http://localhost:16686"))

  (println "Environment started successfully!"))

(defn stop! []
  (println "Stopping local integration environment...")
  (doseq [c ["jaeger" "hydra-tls" "keto-tls" "keto" "hydra" "ory-pg"]]
    (when (container-exists? c)
      (println "Removing container" c)
      (shell "docker" "rm" "-f" c)))
  ;; Best-effort cleanup of any lingering kratos container from older setups.
  (when (container-exists? "kratos")
    (println "Removing legacy kratos container")
    (shell "docker" "rm" "-f" "kratos"))
  (when (network-exists?)
    (println "Removing network" network-name)
    (shell "docker" "network" "rm" network-name))
  (println "Environment stopped successfully."))
