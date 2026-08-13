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
  "The `docker run` argv that starts the Kratos container from docker/kratos.yml."
  []
  ["docker" "run" "-d" "--name" "kratos" "--network" network-name
   "--memory" "128m" "--memory-swap" "128m" "--cpus" "0.5"
   "-p" "4433:4433" "-p" "4434:4434"
   "-v" (str pwd "/docker/kratos.yml:/etc/config/kratos/kratos.yml")
   "-v" (str pwd "/docker/identity.schema.json:/etc/config/kratos/identity.schema.json")
   "-e" (str "DSN=" pg-dsn-base "kratos?sslmode=disable")
   "-e" (str "SECRETS_COOKIE=" kratos-cookie-secret)
   "-e" (str "SECRETS_CIPHER=" kratos-cipher-secret)
   "docker.io/oryd/kratos:v1.3.0" "serve" "-c" "/etc/config/kratos/kratos.yml" "--dev"])

(defn start-auth-stack!
  "Starts the secondary auth stack on top of the main pool: ensures
   ory-pg/keto/hydra via `start!`, then brings up Kratos and recreates Hydra
   with its login/consent URLs pointed at the login-consent app on the host.

   opts:
   - :login-app-base-url  base URL Hydra redirects login/consent/logout
                          challenges to, as seen FROM the Hydra container
                          (default http://host.docker.internal:3001)
   - :token-hook-url      when set, Hydra calls this webhook at token mint
                          time (OAUTH2_TOKEN_HOOK_URL) and allows the
                          `patient` top-level claim; point it at a running
                          dromon /auth/token-hook to exercise SMART launch
                          claims end to end

   Boot failures are loud (assert-container-up!): a broken kratos.yml fails
   here instead of leaving a dead container in the pool."
  ([] (start-auth-stack! {}))
  ([{:keys [login-app-base-url token-hook-url]
     :or   {login-app-base-url "http://host.docker.internal:3001"}}]
   (start!)
   (ensure-kratos-database!)
   (println "Running kratos migrations...")
   (shell "docker" "run" "--rm" "--network" network-name
          "-v" (str pwd "/docker/kratos.yml:/etc/config/kratos/kratos.yml")
          "-v" (str pwd "/docker/identity.schema.json:/etc/config/kratos/identity.schema.json")
          "-e" (str "DSN=" pg-dsn-base "kratos?sslmode=disable")
          "docker.io/oryd/kratos:v1.3.0"
          "migrate" "sql" "-e" "--yes" "-c" "/etc/config/kratos/kratos.yml")
   (println "Starting Ory Kratos...")
   (when-not (container-running? "kratos")
     (when (container-exists? "kratos")
       (println "kratos exists but is stopped — removing and recreating...")
       (shell "docker" "rm" "-f" "kratos"))
     (apply shell (kratos-run-args))
     (assert-container-up! "kratos"))
   (println "Recreating Hydra with login/consent URLs at" login-app-base-url "...")
   (when (container-exists? "hydra")
     (shell "docker" "rm" "-f" "hydra"))
   (apply shell (hydra-run-args
                  (concat ["--add-host" "host.docker.internal:host-gateway"
                           "-e" (str "URLS_LOGIN=" login-app-base-url "/login")
                           "-e" (str "URLS_CONSENT=" login-app-base-url "/consent")
                           "-e" (str "URLS_LOGOUT=" login-app-base-url "/logout")]
                          (when token-hook-url
                            ["-e" (str "OAUTH2_TOKEN_HOOK_URL=" token-hook-url)
                             "-e" "OAUTH2_ALLOWED_TOP_LEVEL_CLAIMS=patient"]))))
   (assert-container-up! "hydra")
   (println "Auth stack started successfully!")))

(defn stop-auth-stack!
  "Tears down the secondary auth stack additions: removes Kratos and restores
   Hydra to its plain docker/hydra.yml configuration. The main pool keeps
   running."
  []
  (when (container-exists? "kratos")
    (println "Removing kratos container")
    (shell "docker" "rm" "-f" "kratos"))
  (when (container-exists? "hydra")
    (restart-hydra!))
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
    (shell "docker" "run" "-d" "--name" "keto" "--network" network-name
           "--memory" "128m" "--memory-swap" "128m" "--cpus" "1.0"
           "-p" "4466:4466" "-p" "4467:4467"
           "-v" (str pwd "/docker/keto.yml:/etc/config/keto/keto.yml")
           "-v" (str pwd "/docker/namespaces.ts:/etc/config/keto/namespaces.ts")
           "-e" (str "DSN=" pg-dsn-base "keto?sslmode=disable")
           "docker.io/oryd/keto:v0.12.0" "serve" "-c" "/etc/config/keto/keto.yml")
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
  (doseq [c ["jaeger" "hydra-tls" "keto" "hydra" "ory-pg"]]
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
