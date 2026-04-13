(ns server.docker-env
  (:require [babashka.process :refer [shell process check]]
            [clojure.string :as str]))

(def network-name "ory-net")
(def pwd (System/getProperty "user.dir"))
(def pg-password (or (System/getenv "POSTGRES_PASSWORD") "secret"))
(def pg-dsn-base (str "postgres://ory:" pg-password "@ory-pg:5432/"))

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
    (shell "docker" "run" "-d" "--name" "hydra" "--network" network-name
           "--memory" "128m" "--memory-swap" "128m" "--cpus" "0.5"
           "-p" "4444:4444" "-p" "4445:4445"
           "-v" (str pwd "/docker/hydra.yml:/etc/config/hydra/hydra.yml")
           "-e" (str "DSN=" pg-dsn-base "hydra?sslmode=disable")
           "docker.io/oryd/hydra:v2.2.0" "serve" "all" "-c" "/etc/config/hydra/hydra.yml" "--dev")
    (assert-container-up! "hydra"))

  (println "Environment started successfully!"))

(defn stop! []
  (println "Stopping local integration environment...")
  (doseq [c ["keto" "hydra" "ory-pg"]]
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
