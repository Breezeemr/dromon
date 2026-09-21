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
;; `start-auth-stack!` serves Hydra, Kratos and Keto over TLS natively, each
;; under its own local*.breezeehr.com name, from one mkcert certificate. That
;; is the whole local dev loop -- flotilla, login-consent, cabotage2 and the
;; cookie-authentication harness all reach the stack by these names -- so no
;; plain-HTTP or loopback URL is left for a browser or a service to hold on to.
;;
;; The main pool (`start!`, `bb setup`) is untouched: it is the Inferno
;; harness's stack, whose containers reach Hydra as http://hydra:4444 through
;; the nginx terminator below, and it never faces a browser.

(def ory-hosts
  "The names the auth stack serves under, and the /etc/hosts entries it needs."
  ["localhydra.breezeehr.com" "localkratos.breezeehr.com" "localketo.breezeehr.com"])

(def hydra-issuer
  "Hydra's issuer in the auth stack. Compared VERBATIM against the id_token
   `iss` by every relying party (flotilla's `hydra-issuer`, the harness's
   `:issuer`), trailing slash included."
  "https://localhydra.breezeehr.com:4444/")

(def ^:private ory-tls-dir (str pwd "/docker/tls"))
(def ^:private ory-tls-mount "/etc/ory/tls")
(def ^:private ory-cert-path (str ory-tls-mount "/ory.pem"))
(def ^:private ory-key-path (str ory-tls-mount "/ory-key.pem"))

(def ^:private ory-tls-docker-args
  "Mounts the certificate pair read-only into a container."
  ["-v" (str ory-tls-dir ":" ory-tls-mount ":ro")])

(defn ensure-ory-tls-cert!
  "Mints docker/tls/ory.pem and ory-key.pem with mkcert: one certificate for
   the three Ory names plus *.breezeehr.com and loopback. These are the same
   files the repo-root `bb cert:setup ory` writes, so either path yields the
   same pair. The key is made world-readable because the Ory images run as a
   non-root user and read it through the bind mount. No-op once present."
  []
  (let [cert (java.io.File. ory-tls-dir "ory.pem")
        key  (java.io.File. ory-tls-dir "ory-key.pem")]
    (when-not (and (.isFile cert) (.isFile key))
      (println "Minting the Ory TLS certificate via mkcert...")
      (.mkdirs (java.io.File. ory-tls-dir))
      (apply shell "mkcert" "-cert-file" (.getPath cert) "-key-file" (.getPath key)
             (concat ory-hosts ["*.breezeehr.com" "localhost" "127.0.0.1" "::1"]))
      (shell "chmod" "644" (.getPath key)))))

(defn- hosts-entry-present? [contents host]
  (boolean
    (some (fn [line]
            (let [[addr & names] (-> line (str/split #"#" 2) first str/trim (str/split #"\s+"))]
              (and (= addr "127.0.0.1") (some #{host} names))))
          (str/split-lines contents))))

(defn- warn-missing-ory-hosts!
  "Says which of the three names /etc/hosts does not resolve yet. Writing the
   file needs sudo, which this task does not take; the repo-root
   `bb cert:setup ory` (or `bb hosts:setup`) does."
  []
  (let [contents (let [f (java.io.File. "/etc/hosts")] (if (.isFile f) (slurp f) ""))
        missing  (remove #(hosts-entry-present? contents %) ory-hosts)]
    (when (seq missing)
      (println "WARNING: not in /etc/hosts:" (str/join ", " missing))
      (println "         run `bb cert:setup ory` (or `bb hosts:setup`) from the repo root, or add:")
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
   on the host and this presents https://localketo.breezeehr.com:4466 (read)
   and :4467 (write)."
  []
  ["docker" "run" "-d" "--name" "keto-tls" "--network" network-name
   "--memory" "64m" "--memory-swap" "64m"
   "-p" "4466:4466" "-p" "4467:4467"
   "-v" (str pwd "/docker/keto-tls.conf:/etc/nginx/nginx.conf:ro")
   "-v" (str ory-tls-dir ":/etc/nginx/certs:ro")
   "docker.io/library/nginx:latest"])

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
  "The `docker run` argv that starts the Kratos container from docker/kratos.yml.
   The config names the TLS pair under /etc/ory/tls (`serve.public.tls`,
   `serve.admin.tls`), so the cert dir is mounted here."
  []
  (concat
    ["docker" "run" "-d" "--name" "kratos" "--network" network-name
     "--memory" "128m" "--memory-swap" "128m" "--cpus" "0.5"
     "-p" "4433:4433" "-p" "4434:4434"
     "-v" (str pwd "/docker/kratos.yml:/etc/config/kratos/kratos.yml")
     "-v" (str pwd "/docker/identity.schema.json:/etc/config/kratos/identity.schema.json")]
    ory-tls-docker-args
    ["-e" (str "DSN=" pg-dsn-base "kratos?sslmode=disable")
     "-e" (str "SECRETS_COOKIE=" kratos-cookie-secret)
     "-e" (str "SECRETS_CIPHER=" kratos-cipher-secret)
     "docker.io/oryd/kratos:v1.3.0" "serve" "-c" "/etc/config/kratos/kratos.yml" "--dev"]))

(defn start-auth-stack!
  "Starts the secondary auth stack on top of the main pool: ensures
   ory-pg/keto/hydra via `start!`, then brings up Kratos and recreates Hydra
   and Keto serving TLS under their own names --
   https://localhydra.breezeehr.com:4444 (admin :4445),
   https://localkratos.breezeehr.com:4433 (admin :4434) and
   https://localketo.breezeehr.com:4466 (write :4467) -- with Hydra's
   login/consent URLs pointed at the login-consent app on the host.

   The certificate is minted here if missing (`ensure-ory-tls-cert!`); the
   /etc/hosts entries are the repo-root `bb cert:setup ory`'s job, and a
   missing one is warned about, not written.

   opts:
   - :login-app-base-url  base URL Hydra redirects login/consent/logout
                          challenges to. These redirects are followed by
                          the BROWSER, so the URL must be host-reachable
                          (default https://localauth.breezeehr.com:3001),
                          unlike the token hook which Hydra calls itself
                          and therefore uses host.docker.internal.
   - :token-hook-url      when set, Hydra calls this webhook at token mint
                          time (OAUTH2_TOKEN_HOOK_URL) and allows the
                          `patient` top-level claim; point it at a running
                          dromon /auth/token-hook to exercise SMART launch
                          claims end to end

   Boot failures are loud (assert-container-up!): a broken kratos.yml fails
   here instead of leaving a dead container in the pool."
  ([] (start-auth-stack! {}))
  ([{:keys [login-app-base-url token-hook-url]
     :or   {login-app-base-url "https://localauth.breezeehr.com:3001"}}]
   (start!)
   (ensure-ory-tls-cert!)
   (warn-missing-ory-hosts!)
   (ensure-kratos-database!)
   (println "Running kratos migrations...")
   (apply shell (concat ["docker" "run" "--rm" "--network" network-name
                         "-v" (str pwd "/docker/kratos.yml:/etc/config/kratos/kratos.yml")
                         "-v" (str pwd "/docker/identity.schema.json:/etc/config/kratos/identity.schema.json")]
                        ory-tls-docker-args
                        ["-e" (str "DSN=" pg-dsn-base "kratos?sslmode=disable")
                         "docker.io/oryd/kratos:v1.3.0"
                         "migrate" "sql" "-e" "--yes" "-c" "/etc/config/kratos/kratos.yml"]))
   (println "Starting Ory Kratos (TLS, https://localkratos.breezeehr.com:4433)...")
   (when-not (container-running? "kratos")
     (when (container-exists? "kratos")
       (println "kratos exists but is stopped — removing and recreating...")
       (shell "docker" "rm" "-f" "kratos"))
     (apply shell (kratos-run-args))
     (assert-container-up! "kratos"))
   (println "Recreating Keto behind the TLS terminator (https://localketo.breezeehr.com:4466)...")
   (doseq [c ["keto-tls" "keto"]]
     (when (container-exists? c)
       (shell "docker" "rm" "-f" c)))
   (apply shell (keto-run-args {:publish-ports? false}))
   (assert-container-up! "keto")
   ;; After Keto: nginx resolves the name once at startup.
   (apply shell (keto-tls-run-args))
   (assert-container-up! "keto-tls")
   (println "Recreating Hydra with TLS (issuer" hydra-issuer ") and login/consent URLs at"
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
                           "-e" (str "URLS_SELF_ISSUER=" hydra-issuer)
                           "-e" (str "URLS_LOGIN=" login-app-base-url "/login")
                           "-e" (str "URLS_CONSENT=" login-app-base-url "/consent")
                           "-e" (str "URLS_LOGOUT=" login-app-base-url "/logout")]
                          (when token-hook-url
                            ["-e" (str "OAUTH2_TOKEN_HOOK_URL=" token-hook-url)
                             "-e" "OAUTH2_ALLOWED_TOP_LEVEL_CLAIMS=patient"]))))
   (assert-container-up! "hydra")
   (println "Auth stack started successfully!")
   (println "  Hydra   https://localhydra.breezeehr.com:4444  (admin :4445)")
   (println "  Kratos  https://localkratos.breezeehr.com:4433 (admin :4434)")
   (println "  Keto    https://localketo.breezeehr.com:4466   (write :4467)")))

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
