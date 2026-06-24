(ns server.compartment-e2e-runner
  "Full-stack patient-compartment integration test against the real local Ory
   stack (Hydra + Keto + Postgres) in Docker.

   Boots the test-server (real in-memory XTDB2) with SMART scope enforcement on,
   mints real RS256 tokens from Hydra (verified by the server against Hydra's
   JWKS), enriches them with a SMART `patient` launch claim via a Hydra token
   hook (a local webhook this runner serves), authorizes via real Keto relation
   tuples, then drives HTTP requests and asserts that a `patient/`-scoped token
   is confined to its launch patient's compartment.

   Run via `bb compartment-e2e`. Exits non-zero if any assertion fails."
  (:require [babashka.process :refer [shell process]]
            [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as hk]
            [server.docker-env :as env]))

(def ^:private base-url   "http://localhost:3000/default/fhir")
(def ^:private hydra-admin "http://127.0.0.1:4445")
(def ^:private hydra-public "http://127.0.0.1:4444")
(def ^:private keto-write "http://127.0.0.1:4467")
(def ^:private hook-port 9777)
(def ^:private java-home "/usr/lib/jvm/java-21-openjdk-amd64")

;; client_id -> launch patient id, consulted by the token-hook webhook.
(def ^:private client->patient (atom {}))

;; ── token-hook webhook ─────────────────────────────────────────────────────

(defn- hook-handler [req]
  (let [payload (try (json/parse-string (some-> (:body req) slurp) true)
                     (catch Exception _ {}))
        cid     (or (get-in payload [:request :client_id])
                    (get-in payload [:session :client_id]))
        pid     (get @client->patient cid)]
    {:status  200
     :headers {"Content-Type" "application/json"}
     :body    (json/generate-string
                (if pid {:session {:access_token {:patient pid}}} {}))}))

(defn- start-webhook! []
  (println "Starting token-hook webhook on :" hook-port)
  (hk/run-server hook-handler {:port hook-port :ip "0.0.0.0"}))

;; ── Hydra lifecycle (hook-enabled, then restored) ──────────────────────────

(defn- run-hydra!
  "(Re)create the `hydra` container. With `hook?`, enable the token hook (so
   tokens carry a `patient` claim) and host-gateway networking so the container
   can reach the webhook on the host. Without it, an ordinary Hydra (restores
   the shared stack to normal for Inferno/dev)."
  [hook?]
  (shell {:continue true :out :string :err :string} "docker" "rm" "-f" "hydra")
  (let [base ["docker" "run" "-d" "--name" "hydra" "--network" env/network-name
              "--memory" "128m" "--memory-swap" "128m" "--cpus" "0.5"
              "-p" "4444:4444" "-p" "4445:4445"
              "-v" (str env/pwd "/docker/hydra.yml:/etc/config/hydra/hydra.yml")
              "-e" (str "DSN=" env/pg-dsn-base "hydra?sslmode=disable")]
        hook ["--add-host" "host.docker.internal:host-gateway"
              "-e" (str "OAUTH2_TOKEN_HOOK_URL=http://host.docker.internal:" hook-port "/hook")
              "-e" "OAUTH2_ALLOWED_TOP_LEVEL_CLAIMS=patient"]
        tail ["docker.io/oryd/hydra:v2.2.0" "serve" "all"
              "-c" "/etc/config/hydra/hydra.yml" "--dev"]]
    (apply shell (concat base (when hook? hook) tail))))

(defn- wait-for-hydra [secs]
  (println "Waiting for Hydra...")
  (loop [left secs]
    (let [ok? (try (= 200 (:status (curl/get (str hydra-public "/health/ready") {:throw false})))
                   (catch Exception _ false))]
      (cond ok? (println "Hydra ready.")
            (<= left 0) (throw (ex-info "Hydra did not become ready" {}))
            :else (do (Thread/sleep 1000) (recur (dec left)))))))

;; ── FHIR server lifecycle ──────────────────────────────────────────────────

(defn- store-alias
  "Deps alias chain to put the chosen store backend on the classpath. `:test`
   already bundles XTDB2; datomic needs its own (out-of-tree) alias added."
  [store]
  (case store
    :datomic "-X:test:store/datomic"
    "-X:test"))

(defn- start-server! [store]
  (io/delete-file "e2e-server.log" true)
  (println (format "Starting FHIR server (store=%s, HTTP :3000, ENFORCE_SMART_SCOPES=1)..."
                   (name store)))
  (process ["clojure" "-J-Xmx4g"
            "-J--add-opens=java.base/java.nio=ALL-UNNAMED"
            "-J--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
            "-J--enable-preview"
            (store-alias store) "test-server.core/-main" ":port" "3000" ":ssl-port" "false"]
           {:dir "test-server"
            :out (io/file "e2e-server.log")
            :err :out
            :extra-env {"ENFORCE_SMART_SCOPES" "1"
                        "TEST_SERVER_STORE" (name store)
                        "JAVA_HOME" java-home
                        "PATH" (str java-home "/bin:" (System/getenv "PATH"))}}))

(defn- stop-server!
  "Stop the FHIR server and wait for :3000 to free, so the next store's server
   can bind it."
  [proc]
  (when proc (try (.destroy (:proc proc)) (catch Exception _)))
  (try (shell {:continue true :out :string :err :string}
              "sh" "-c" "fuser -k 3000/tcp 2>/dev/null || true")
       (catch Exception _))
  (loop [left 15]
    (let [up? (try (= 200 (:status (curl/get (str base-url "/metadata")
                                             {:throw false :timeout 1000})))
                   (catch Exception _ false))]
      (when (and up? (pos? left)) (Thread/sleep 1000) (recur (dec left))))))

(defn- wait-for-server [secs]
  (println "Waiting for FHIR server...")
  (loop [left secs]
    (let [ok? (try (= 200 (:status (curl/get (str base-url "/metadata") {:throw false})))
                   (catch Exception _ false))]
      (cond ok? (println "Server up.")
            (<= left 0) (throw (ex-info "FHIR server did not start" {}))
            :else (do (Thread/sleep 1000) (recur (dec left)))))))

;; ── Hydra / Keto helpers ───────────────────────────────────────────────────

(defn- create-client!
  "Register an OAuth2 client allowed the given scope. Returns {:id :secret}."
  [scope]
  (let [resp (curl/post (str hydra-admin "/admin/clients")
                        {:headers {"Content-Type" "application/json"}
                         :body (json/generate-string
                                 {:client_name "compartment-e2e"
                                  :grant_types ["client_credentials"]
                                  :token_endpoint_auth_method "client_secret_basic"
                                  :scope scope})
                         :throw false})
        c (json/parse-string (:body resp) true)]
    (when (not= 201 (:status resp))
      (throw (ex-info "client creation failed" {:body (:body resp)})))
    {:id (:client_id c) :secret (:client_secret c)}))

(defn- get-token! [{:keys [id secret]} scope]
  (let [resp (curl/post (str hydra-public "/oauth2/token")
                        {:basic-auth [id secret]
                         :form-params {"grant_type" "client_credentials" "scope" scope}
                         :throw false})]
    (when (not= 200 (:status resp))
      (throw (ex-info "token request failed" {:body (:body resp)})))
    (:access_token (json/parse-string (:body resp) true))))

(defn- grant-keto! [client-id objects relations]
  (doseq [obj objects rel relations]
    (let [resp (curl/put (str keto-write "/admin/relation-tuples")
                         {:headers {"Content-Type" "application/json"}
                          :body (json/generate-string {:namespace "fhir" :object obj
                                                        :relation rel :subject_id client-id})
                          :throw false})]
      (when-not (#{200 201} (:status resp))
        (throw (ex-info "keto grant failed" {:obj obj :rel rel :body (:body resp)}))))))

(defn- jwt-payload [token]
  (let [p (second (str/split token #"\."))
        pad (apply str (repeat (mod (- 4 (mod (count p) 4)) 4) "="))]
    (json/parse-string (String. (.decode (java.util.Base64/getUrlDecoder) (str p pad))) true)))

;; ── FHIR requests ──────────────────────────────────────────────────────────

(defn- fhir [method path token & [body]]
  (let [url  (str base-url path)
        opts (cond-> {:headers (cond-> {"Authorization" (str "Bearer " token)
                                        "Accept" "application/json"}
                                 body (assoc "Content-Type" "application/json"))
                      :throw false}
               body (assoc :body (json/generate-string body)))
        resp ((case method :get curl/get :post curl/post :put curl/put :delete curl/delete) url opts)]
    {:status (:status resp)
     :body   (try (json/parse-string (:body resp) true) (catch Exception _ nil))}))

(defn- entry-ids [resp]
  (sort (map #(get-in % [:resource :id]) (get-in resp [:body :entry]))))

;; ── Assertions ─────────────────────────────────────────────────────────────

(def ^:private results (atom []))

(defn- check! [label pass?]
  (swap! results conj [label (boolean pass?)])
  (println (if pass? "  PASS" "  FAIL") "—" label))

;; ── Seeding ────────────────────────────────────────────────────────────────

(defn- seed! [token]
  (println "Seeding patients + observations...")
  (let [put (fn [path body]
              (let [{:keys [status]} (fhir :put path token body)]
                (when-not (#{200 201} status)
                  (throw (ex-info "seed failed" {:path path :status status})))))]
    (put "/Patient/PA" {:resourceType "Patient" :id "PA" :name [{:family "Alpha"}] :gender "female"})
    (put "/Patient/PB" {:resourceType "Patient" :id "PB" :name [{:family "Beta"}] :gender "male"})
    (put "/Observation/obs-a1" {:resourceType "Observation" :id "obs-a1" :status "final"
                                :code {:text "hr"} :subject {:reference "Patient/PA"}})
    (put "/Observation/obs-a2" {:resourceType "Observation" :id "obs-a2" :status "final"
                                :code {:text "bp"} :performer [{:reference "Patient/PA"}]})
    (put "/Observation/obs-b1" {:resourceType "Observation" :id "obs-b1" :status "final"
                                :code {:text "wt"} :subject {:reference "Patient/PB"}})))

;; ── Scenarios ──────────────────────────────────────────────────────────────

(defn- run-scenarios! [store]
 (let [ck (fn [label pass?] (check! (str "[" (name store) "] " label) pass?))]
  ;; Setup client: broad user scope + full Keto, used only to seed data.
  (println "Creating setup client + seeding...")
  (let [setup (create-client! "user/*.cruds")]
    (grant-keto! (:id setup) ["Patient" "Observation"] ["read" "write" "delete"])
    (seed! (get-token! setup "user/*.cruds")))

  ;; Scenario clients. Map each to its launch patient BEFORE minting tokens.
  (let [pa        (create-client! "patient/Observation.rs patient/Patient.rs")
        usr       (create-client! "user/Observation.rs")
        pa-write  (create-client! "patient/Observation.cruds")
        pa-noketo (create-client! "patient/Observation.rs")
        pa-nopt   (create-client! "patient/Observation.rs")]
    (reset! client->patient {(:id pa) "PA" (:id pa-write) "PA"
                             (:id pa-noketo) "PA"})  ; usr + pa-nopt: no patient claim
    (grant-keto! (:id pa) ["Patient" "Observation"] ["read"])
    (grant-keto! (:id usr) ["Observation"] ["read"])
    (grant-keto! (:id pa-write) ["Observation"] ["read" "write"])
    (grant-keto! (:id pa-nopt) ["Observation"] ["read"])
    ;; pa-noketo: intentionally NO Keto tuple.

    (let [pa-tok        (get-token! pa "patient/Observation.rs patient/Patient.rs")
          usr-tok       (get-token! usr "user/Observation.rs")
          pa-write-tok  (get-token! pa-write "patient/Observation.cruds")
          pa-noketo-tok (get-token! pa-noketo "patient/Observation.rs")
          pa-nopt-tok   (get-token! pa-nopt "patient/Observation.rs")]

      (println "\nAsserting...")
      ;; Sanity: the patient-scoped token is a real RS256 Hydra token carrying
      ;; the launch patient + scopes (scp).
      (let [pl (jwt-payload pa-tok)]
        (ck "token: patient launch claim = PA" (= "PA" (:patient pl)))
        (ck "token: scp carries SMART scopes" (some? (:scp pl))))


      ;; Compartment confinement (the union: subject ∪ performer).
      (let [r (fhir :get "/Observation" pa-tok)]
        (ck "patient token sees only its compartment (obs-a1+obs-a2, not obs-b1)"
                (= ["obs-a1" "obs-a2"] (entry-ids r))))
      (ck "cross-patient instance read -> 404"
              (= 404 (:status (fhir :get "/Observation/obs-b1" pa-tok))))
      (ck "own Patient read -> 200"
              (= 200 (:status (fhir :get "/Patient/PA" pa-tok))))
      (ck "other Patient read -> 404"
              (= 404 (:status (fhir :get "/Patient/PB" pa-tok))))

      ;; Unrestricted (user) token is not narrowed.
      (let [r (fhir :get "/Observation" usr-tok)]
        (ck "user-scope token sees all patients' Observations (incl. obs-b1)"
                (= ["obs-a1" "obs-a2" "obs-b1"] (entry-ids r))))

      ;; Cross-patient write is rejected; in-compartment write succeeds.
      (ck "cross-patient write -> 403"
              (= 403 (:status (fhir :post "/Observation" pa-write-tok
                                    {:resourceType "Observation" :status "final"
                                     :code {:text "x"} :subject {:reference "Patient/PB"}}))))
      (ck "in-compartment write -> 201"
              (= 201 (:status (fhir :post "/Observation" pa-write-tok
                                    {:resourceType "Observation" :status "final"
                                     :code {:text "x"} :subject {:reference "Patient/PA"}}))))

      ;; Real Keto denial in the live chain.
      (ck "patient token without a Keto tuple -> 403"
              (= 403 (:status (fhir :get "/Observation" pa-noketo-tok))))

      ;; Launch-context requirement (real token, no patient claim).
      (ck "patient/ scope without a launch patient -> 403"
              (= 403 (:status (fhir :get "/Observation" pa-nopt-tok))))))))

;; ── Orchestration ──────────────────────────────────────────────────────────

(defn- docker-available? []
  (try (zero? (:exit (shell {:continue true :out :string :err :string} "docker" "version")))
       (catch Exception _ false)))

(defn run!
  "Entry point for `bb compartment-e2e`. Runs the full scenario suite against
   each store backend in turn (default: XTDB2 then Datomic). Skips (exit 0)
   when Docker is absent. Optional args select stores, e.g.
   `bb compartment-e2e datomic`."
  [& store-args]
  (when-not (docker-available?)
    (println "Docker/Podman not available -- skipping compartment-e2e.")
    (System/exit 0))

  (let [stores (if (seq store-args) (mapv keyword store-args) [:xtdb2 :datomic])
        ;; NOTE: System/exit is called AFTER the try/finally completes — calling
        ;; it inside the body would begin JVM shutdown and skip the finally
        ;; (leaving the server up and Hydra stuck in hook mode).
        stop-webhook (atom nil)
        server-proc  (atom nil)
        exit-code    (atom 1)]
    (try
      (println "Ensuring Ory stack (Postgres + Keto + Hydra)...")
      (when-not (every? env/container-running? ["ory-pg" "keto" "hydra"])
        (env/start!))
      (reset! stop-webhook (start-webhook!))
      (println "Recreating Hydra with token hook...")
      (run-hydra! true)
      (wait-for-hydra 30)

      (doseq [store stores]
        (println (str "\n========== STORE: " (name store) " =========="))
        (let [p (start-server! store)]
          (reset! server-proc p)
          (wait-for-server 90)
          (run-scenarios! store)
          (stop-server! p)
          (reset! server-proc nil)))

      (let [rs @results
            failed (remove second rs)]
        (println (format "\n==== %d passed, %d failed (stores: %s) ===="
                         (count (filter second rs)) (count failed)
                         (str/join ", " (map name stores))))
        (when (seq failed)
          (doseq [[label _] failed] (println "  FAILED:" label)))
        (reset! exit-code (if (seq failed) 1 0)))

      (catch Exception e
        (println "ERROR:" (ex-message e) (pr-str (ex-data e)))
        (reset! exit-code 1))

      (finally
        (println "Cleaning up...")
        (when-let [p @server-proc] (try (.destroy (:proc p)) (catch Exception _)))
        (try (shell {:continue true :out :string :err :string}
                    "sh" "-c" "fuser -k 3000/tcp 2>/dev/null || true")
             (catch Exception _))
        (when-let [stop @stop-webhook] (try (stop) (catch Exception _)))
        ;; Restore a plain (hook-free) Hydra so the shared stack stays usable.
        (println "Restoring plain Hydra...")
        (try (run-hydra! false) (catch Exception _))))
    (System/exit @exit-code)))
