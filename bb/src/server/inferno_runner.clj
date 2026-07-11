(ns server.inferno-runner
  (:require [babashka.process :refer [shell process]]
            [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [server.docker-env :as env]))

;; ── Pre-flight helpers ────────────────────────────────────────────────────────

(def inferno-dir "inferno-test-kit")

(defn ensure-inferno-containers! []
  (println "Ensuring Inferno containers are up..." inferno-dir)
  (shell {:dir inferno-dir}
         "docker" "compose" "up" "-d"
         "--wait"        ; wait for healthchecks where defined
         "inferno" "worker" "hl7_validator_service"
         "nginx" "redis"))

(defn wait-for-validator
  "Polls the HL7 validator via nginx until it responds with 200 or timeout.
   The hl7_validator_service takes 30-90s to load FHIR packages on cold start."
  [timeout-seconds]
  (println "Waiting for HL7 validator to be ready (may take up to 90s on cold start)...")
  (loop [left timeout-seconds]
    (let [resp (try
                 (curl/get "http://localhost:8080/hl7validatorapi/"
                           {:timeout 3000 :throw false})
                 (catch Exception _ nil))]
      (cond
        (and resp (= 200 (:status resp)))
        (println "HL7 validator is ready!")

        (<= left 0)
        (do (println "ERROR: HL7 validator did not become ready within timeout.")
            (println "Check: docker compose -f inferno-test-kit/docker-compose.yml logs hl7_validator_service")
            (System/exit 1))

        :else
        (do (print ".") (flush)
            (Thread/sleep 3000)
            (recur (- left 3))))))
  (println)) ; newline after dots

;; ── FHIR server helpers ───────────────────────────────────────────────────────

(defn wait-for-server [timeout-seconds]
  (println "Waiting for FHIR server to start...")
  (loop [left timeout-seconds]
    (let [resp (try
                 (curl/get "https://fhir.local:3001/default/fhir/metadata"
                           {:timeout 2000
                            :throw false
                            :insecure? true})
                 (catch Exception _ nil))]
      (if (and resp (= 200 (:status resp)))
        (println "Server is up!")
        (if (<= left 0)
          (do
            (println "ERROR: FHIR server failed to start within timeout. Check server.log.")
            (System/exit 1))
          (do
            (Thread/sleep 1000)
            (recur (dec left))))))))

;; ── Hydra / Keto setup ────────────────────────────────────────────────────────

(defn create-client []
  (println "Creating Hydra client...")
  (let [resp (try
               (curl/post "http://127.0.0.1:4445/admin/clients"
                          {:headers {"Content-Type" "application/json"}
                           :body (json/generate-string {:client_name "test"
                                                        :grant_types ["client_credentials"]
                                                        :token_endpoint_auth_method "client_secret_basic"})
                           :timeout 10000
                           :throw false})
               (catch Exception e {:status 500 :body (.getMessage e)}))
        client (try (json/parse-string (:body resp) true) (catch Exception _ nil))]
    (when (not= 201 (:status resp))
      (println "Failed to create client:" (:body resp))
      (System/exit 1))
    client))

(defn get-token [client-id client-secret]
  (println "Getting access token...")
  (let [resp (try
               (curl/post "http://127.0.0.1:4444/oauth2/token"
                          {:basic-auth [client-id client-secret]
                           :form-params {"grant_type" "client_credentials"}
                           :timeout 10000
                           :throw false})
               (catch Exception e {:status 500 :body (.getMessage e)}))
        body (try (json/parse-string (:body resp) true) (catch Exception _ nil))]
    (when (not= 200 (:status resp))
      (println "Failed to get token:" (:body resp))
      (System/exit 1))
    (:access_token body)))

(defn grant-keto-permissions [client-id]
  (println "Granting Keto permissions...")
  (let [objects ["Patient/123" "Patient" "Observation" "AllergyIntolerance" "CarePlan" "CareTeam" "Condition"
                 "Coverage" "Device" "DiagnosticReport" "DocumentReference" "Encounter" "Goal"
                 "Immunization" "MedicationDispense" "MedicationRequest" "Procedure"
                 "QuestionnaireResponse" "RelatedPerson" "ServiceRequest" "Specimen"
                 "Practitioner" "PractitionerRole" "Organization" "Location" "Provenance" "Endpoint"
                 "system"]]
    (doseq [obj objects
            relation ["read" "write" "search-type"]]
      (let [resp (try
                   (curl/put "http://127.0.0.1:4467/admin/relation-tuples"
                             {:headers {"Content-Type" "application/json"}
                              :body (json/generate-string {:namespace "fhir"
                                                           :object obj
                                                           :relation relation
                                                           :subject_id client-id})
                              :timeout 10000
                              :throw false})
                   (catch Exception e {:status 500 :body (.getMessage e)}))]
        (when (not= 201 (:status resp))
          (println "Failed to grant" relation "permission for" obj ":" (:body resp))
          (System/exit 1))))))

;; ── SMART Backend Services (private_key_jwt) ──────────────────────────────────
;; The bulk_data_v200 auth group runs the real RFC 7523 client-credentials flow:
;; Inferno signs a client assertion with a private JWK and posts it to Hydra's
;; token endpoint. Wiring:
;;   1. Hydra's issuer must be reachable under the same name from the host and
;;      from inside the Inferno container (host-gateway). docker/hydra.yml pins
;;      it to http://fhir.local:4444/; `ensure-hydra-issuer!` recreates a stale
;;      container so the running issuer matches.
;;   2. Register a Hydra client (token_endpoint_auth_method private_key_jwt) with
;;      the PUBLIC JWK so Hydra can verify the assertion.
;;   3. Grant that client subject the Keto "system" read tuple so the RS256
;;      access token Hydra issues passes dromon's authorization.
;;   4. Hand Inferno the backend-services `smart_auth_info` (token_url, client_id,
;;      alg, kid, and the PRIVATE JWK set it signs the assertion with).

(def ^:private backend-alg "ES384")
;; SMART Backend Services requires the token endpoint over TLS. The nginx TLS
;; terminator (docker/hydra-tls.conf) fronts Hydra at https://fhir.local:4443
;; and Hydra's issuer is set to match, so the client-assertion `aud` validates.
(def ^:private desired-hydra-issuer "https://fhir.local:4443/")
(def ^:private oauth-base-url "https://fhir.local:4443")

(defn backend-mode? []
  (= "backend_services" (System/getenv "INFERNO_AUTH_MODE")))

(defn- backend-token-url []
  (or (not-empty (System/getenv "BACKEND_TOKEN_URL"))
      (str oauth-base-url "/oauth2/token")))

(defn- b64url-no-pad [^bytes bs]
  (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bs))

(defn- i2osp-48
  "Fixed-length 48-byte big-endian octet string for a P-384 field element,
   dropping any leading sign byte and left-padding shorter magnitudes."
  [^java.math.BigInteger n]
  (let [bs (.toByteArray n)
        len (alength bs)
        start (if (> len 48) (- len 48) 0)
        keep (- len start)
        out (byte-array 48)]
    (System/arraycopy bs start out (- 48 keep) keep)
    out))

(defn- ec-field->b64 [^java.math.BigInteger n]
  (b64url-no-pad (i2osp-48 n)))

(defn- hex->bytes [^String h]
  (let [n (quot (count h) 2)
        ba (byte-array n)]
    (dotimes [i n]
      (aset ba i (unchecked-byte (Integer/parseInt (subs h (* 2 i) (+ 2 (* 2 i))) 16))))
    ba))

(defn- openssl-hex-block
  "Concatenated hex between two `openssl ec -text` markers (colons/whitespace
   stripped)."
  [text start end]
  (some-> (re-find (re-pattern (str "(?s)" start "(.*?)" end)) text)
          second
          (str/replace #"[^0-9a-fA-F]" "")))

(defn generate-backend-jwks
  "Generates a fresh EC P-384 (ES384) key set: a private signing JWK (Inferno
   signs the client assertion with it) and the matching public verify JWK
   (registered with Hydra). babashka's SCI cannot reference the java.security EC
   spec classes, so the keypair is minted via a piped `openssl` command (the key
   never touches disk) and the field elements are parsed from its text output.
   No key material is written to disk or committed; the private JWK is passed to
   Inferno inline via smart_auth_info."
  []
  (let [text (:out (shell {:out :string} "sh" "-c"
                          (str "openssl ecparam -name secp384r1 -genkey -noout"
                               " | openssl ec -text -noout -conv_form uncompressed 2>/dev/null")))
        priv (openssl-hex-block text "priv:" "pub:")
        pub  (openssl-hex-block text "pub:" "ASN1 OID:")
        ;; uncompressed public point: 04 || X(48 bytes) || Y(48 bytes)
        x    (b64url-no-pad (hex->bytes (subs pub 2 98)))
        y    (b64url-no-pad (hex->bytes (subs pub 98 194)))
        d    (ec-field->b64 (java.math.BigInteger. priv 16))
        kid  (str "dromon-backend-services-" (subs (str (random-uuid)) 0 8))]
    {:keys [{:kty "EC" :crv "P-384" :x x :y y :d d :key_ops ["sign"] :kid kid :alg backend-alg}
            {:kty "EC" :crv "P-384" :x x :y y :use "sig" :key_ops ["verify"] :kid kid :alg backend-alg}]}))

(defn- backend-signing-kid [jwks]
  (:kid (some #(when (some #{"sign"} (:key_ops %)) %) (:keys jwks))))

(defn- backend-public-jwk
  "The public JWK Hydra registers to verify the client assertion: the verify key
   (or any key) reduced to its public components."
  [jwks]
  (let [k (or (some #(when-not (:d %) %) (:keys jwks)) (first (:keys jwks)))]
    {:kty (:kty k) :crv (:crv k) :x (:x k) :y (:y k)
     :kid (:kid k) :alg backend-alg :use "sig"}))

(defn live-hydra-issuer []
  (try
    (-> (curl/get "http://127.0.0.1:4444/.well-known/openid-configuration"
                  {:timeout 3000 :throw false})
        :body (json/parse-string true) :issuer)
    (catch Exception _ nil)))

(defn ensure-hydra-issuer!
  "Ensures the running Hydra advertises `desired-hydra-issuer`. Recreates the
   container from docker/hydra.yml (idempotently) if it does not, then waits for
   the token endpoint to come back."
  []
  (let [iss (live-hydra-issuer)]
    (if (= iss desired-hydra-issuer)
      (println "Hydra issuer already" desired-hydra-issuer)
      (do
        (println "Hydra issuer is" (pr-str iss) "-- recreating for" desired-hydra-issuer)
        (env/restart-hydra!)
        (loop [left 30]
          (let [now (live-hydra-issuer)]
            (cond
              (= now desired-hydra-issuer) (println "Hydra issuer is now" desired-hydra-issuer)
              (<= left 0) (do (println "ERROR: Hydra did not come back with issuer" desired-hydra-issuer)
                              (System/exit 1))
              :else (do (Thread/sleep 1000) (recur (dec left))))))))))

(defn create-backend-client
  "Registers a Hydra client for the SMART Backend Services flow: private_key_jwt
   auth with the inline PUBLIC JWK. Returns the client_id."
  [public-jwk]
  (println "Creating Hydra backend-services (private_key_jwt) client...")
  (let [resp (try
               (curl/post "http://127.0.0.1:4445/admin/clients"
                          {:headers {"Content-Type" "application/json"}
                           :body (json/generate-string
                                  {:client_name "inferno-backend-services"
                                   :grant_types ["client_credentials"]
                                   :response_types []
                                   :token_endpoint_auth_method "private_key_jwt"
                                   :token_endpoint_auth_signing_alg backend-alg
                                   :jwks {:keys [public-jwk]}
                                   :scope "system/*.read"})
                           :timeout 10000
                           :throw false})
               (catch Exception e {:status 500 :body (.getMessage e)}))
        client (try (json/parse-string (:body resp) true) (catch Exception _ nil))]
    (when (not= 201 (:status resp))
      (println "Failed to create backend-services client:" (:body resp))
      (System/exit 1))
    (:client_id client)))

(defn backend-smart-auth-info
  "The `smart_auth_info` JSON string Inferno consumes for a backend-services run.
   `jwks-str` is the PRIVATE JWK set (as a string) Inferno signs the assertion
   with; `token_url` is both the assertion `aud` and the POST target."
  [client-id jwks-str kid]
  (json/generate-string
   {:auth_type "backend_services"
    :use_discovery "false"
    :token_url (backend-token-url)
    :client_id client-id
    :requested_scopes "system/*.read"
    :encryption_algorithm backend-alg
    :kid kid
    :jwks jwks-str}))

;; Store warmup used to happen here via an authenticated `GET /Patient?_count=1`
;; to force per-tenant cold-start (XTDB node start, Datomic peer connect, JIT of
;; the read path). That is now handled in-process by `test-server/seeder`, which
;; calls `(db/create-tenant store "default" {:if-exists :ignore})` and
;; `(db/warmup-tenant store "default")` before Jetty starts accepting traffic.
;; By the time `wait-for-server` sees a 200 on `/metadata`, the default tenant
;; is already warm, so the runner has nothing left to do here.

(defn insert-patient [token]
  (println "Inserting test Patient...")
  (let [patient-body {:resourceType "Patient"
                      :id "123"
                      :text {:status "generated" :div "<div xmlns=\"http://www.w3.org/1999/xhtml\">John Smith</div>"}
                      :active true
                      :identifier [{:system "urn:oid:1.2.36.146.595.217.0.1" :value "pat-123"}]
                      :name [{:family "Smith" :given ["John"]}]
                      :gender "male"
                      :birthDate "1980-01-01"
                      :address [{:line ["123 Main St"] :city "Anytown" :state "NY" :postalCode "12345" :country "US"}]}
        resp (try
               (curl/put "https://fhir.local:3001/default/fhir/Patient/123"
                         {:headers {"Authorization" (str "Bearer " token)
                                    "Content-Type" "application/json"}
                          :body (json/generate-string patient-body)
                          :timeout 10000
                          :throw false
                          :insecure? true})
               (catch Exception e {:status 500 :body (.getMessage e)}))]
    (when (not (#{200 201} (:status resp)))
      (println "Failed to insert patient. Status:" (:status resp) "Body:" (:body resp))
      (System/exit 1))))

(defn insert-test-data [token]
  (println "Inserting test data bundle...")
  (let [resp (try
               (let [result (shell {:out :string :err :string :continue true}
                                   "curl" "-s" "-k" "--max-time" "30"
                                   "-X" "POST"
                                   "-H" (str "Authorization: Bearer " token)
                                   "-H" "Content-Type: application/json"
                                   "-d" "@bb/resources/inferno-test-bundle.json"
                                   "-w" "\n%{http_code}"
                                   "https://fhir.local:3001/default/fhir")
                     lines (str/split-lines (:out result))
                     status (parse-long (last lines))
                     body (str/join "\n" (butlast lines))]
                 {:status status :body body})
               (catch Exception e {:status 500 :body (.getMessage e)}))]
    (if (#{200 201} (:status resp))
      (println "Test data bundle inserted successfully.")
      (do
        (println "Failed to insert test data bundle. Status:" (:status resp))
        (println "Response body:" (:body resp))
        (System/exit 1)))))

;; ── Suite configuration (env-overridable) ─────────────────────────────────────
;; The suite, group filter, and inputs default to the historical US Core v6.1.0
;; run so existing tooling is unaffected. Override via env to point the same
;; runner at other suites (e.g. base FHIR R4, SMART App Launch):
;;   INFERNO_SUITE       suite id (default "us_core_v610")
;;   INFERNO_GROUPS      space/comma list of group ids, or "all"/"" to run the
;;                       whole suite (default "2")
;;   INFERNO_FHIR_URL    server base url (default the local default tenant)
;;   INFERNO_PATIENT_IDS patient_ids input (default "123")
;;   INFERNO_INPUTS      full replacement for the --inputs tokens, space
;;                       separated "key:value"; the tokens {{token}} and
;;                       {{cred_json}} are substituted with the obtained access
;;                       token and its JSON envelope.

(def default-suite "us_core_v610")

(defn inferno-suite []
  (or (not-empty (System/getenv "INFERNO_SUITE")) default-suite))

(defn- group-args []
  (let [g (or (System/getenv "INFERNO_GROUPS") "2")]
    (if (contains? #{"" "all" "ALL"} (str/trim g))
      []
      (into ["--groups"] (remove str/blank? (str/split g #"[,\s]+"))))))

(defn- input-args
  "The `--inputs k:v ...` argument vector. INFERNO_INPUTS fully replaces the
   defaults; the substitution tokens {{token}}, {{cred_json}}, and
   {{backend_auth}} expand to the access token, its JSON envelope, and the
   backend-services smart_auth_info JSON respectively."
  [token backend-auth]
  (let [cred-json (json/generate-string {:access_token token})
        subst (fn [s] (-> s
                          (str/replace "{{cred_json}}" cred-json)
                          (str/replace "{{backend_auth}}" (or backend-auth ""))
                          (str/replace "{{token}}" token)))
        url (or (not-empty (System/getenv "INFERNO_FHIR_URL"))
                "https://fhir.local:3001/default/fhir")
        tokens (cond
                 (not-empty (System/getenv "INFERNO_INPUTS"))
                 (mapv subst (remove str/blank? (str/split (System/getenv "INFERNO_INPUTS") #"\s+")))

                 ;; Backend-services default input set for the bulk_data suites:
                 ;; the FHIR base is `bulk_server_url` and auth is the real
                 ;; private_key_jwt flow driven from `smart_auth_info`.
                 (backend-mode?)
                 [(str "bulk_server_url:" url)
                  (str "smart_auth_info:" backend-auth)
                  "group_id:1"
                  "bulk_timeout:180"
                  "since_timestamp:2020-01-01T00:00:00Z"]

                 :else
                 [(str "url:" url)
                  (str "patient_ids:" (or (not-empty (System/getenv "INFERNO_PATIENT_IDS")) "123"))
                  (str "smart_auth_info:" cred-json)])]
    (into ["--inputs"] tokens)))

;; ── Test runner ───────────────────────────────────────────────────────────────

(defn run-inferno-tests [token backend-auth]
  (println "Running Inferno tests for suite" (inferno-suite) "...")
  (.mkdirs (io/file "target"))

  (let [cmd (into ["docker" "compose" "exec" "-T" "inferno" "bundle" "exec" "inferno" "execute"
                   "--suite" (inferno-suite)]
                  (concat (group-args)
                          (input-args token backend-auth)
                          ["--outputter" "json"]))
        _ (println "Executing:" (str/join " " cmd))]

    (try
      (let [proc (process cmd {:dir inferno-dir :out :string :err :string})
            ;; 10-minute hard timeout
            result (deref (future @proc) 600000 ::timeout)]
        (if (= ::timeout result)
          (do
            (println "ERROR: Inferno test suite timed out after 10 minutes.")
            (println "Check worker logs: docker compose -f inferno-test-kit/docker-compose.yml logs worker")
            (.destroy proc)
            (System/exit 1))
          (do
            (when (seq (:err result))
              (println "Inferno stderr:" (:err result)))
            (let [report-file (str "target/inferno-report-" (inferno-suite) ".json")]
              (spit "target/inferno-report.json" (:out result))
              (spit report-file (:out result))
              (println "Inferno run completed. Report written to" report-file
                       "(and target/inferno-report.json)"))
            (let [report (try (json/parse-string (:out result) true) (catch Exception _ nil))]
              (if report
                (let [passes (count (filter #(= "pass" (:result %)) report))
                      fails  (count (filter #(= "fail" (:result %)) report))
                      errors (count (filter #(= "error" (:result %)) report))
                      ;; ANSI color helpers
                      green  "\033[32m"
                      red    "\033[31m"
                      yellow "\033[33m"
                      reset  "\033[0m"
                      color  (fn [r] (case r "pass" green "fail" red "error" yellow reset))
                      badge  (fn [r] (case r "pass" "PASS" "fail" "FAIL" "error" "ERR " "SKIP"))]
                  ;; Per-test table
                  (println "\n╔══════════════════════════════════════════════════════════════╗")
                  (println "║                     Inferno Test Results                     ║")
                  (println "╠══════════════════════════════════════════════════════════════╣")
                  (doseq [t report]
                    (let [r  (:result t "skip")
                          id (or (:test_id t) (:test_group_id t) "unknown")
                          msg (or (:result_message t) "")]
                      (println (format "%s[%s]%s %-45s %s"
                                       (color r) (badge r) reset
                                       (if (> (count id) 45) (str (subs id 0 42) "...") id)
                                       (if (> (count msg) 60) (str (subs msg 0 57) "...") msg)))))
                  (println "╠══════════════════════════════════════════════════════════════╣")
                  (println (format "║ %s%d passed%s  %s%d failed%s  %s%d errors%s"
                                   green passes reset
                                   red   fails  reset
                                   yellow errors reset))
                  (println "╚══════════════════════════════════════════════════════════════╝\n"))
                (do
                  (println "WARN: Could not parse JSON report. Raw output:")
                  (println (:out result))))))))
      (catch Exception e
        (println "Error running inferno tests:" (.getMessage e))
        (when-let [edata (ex-data e)]
          (println "Stdout:" (:out edata))
          (println "Stderr:" (:err edata)))
        (System/exit 1)))))

;; ── Entry point ───────────────────────────────────────────────────────────────

(defn kill-stale-inferno-processes! []
  (println "Killing any stale 'inferno execute' processes in the container...")
  (try
    (shell {:dir inferno-dir :out :string :err :string}
           "sh" "-c" "docker compose exec -T inferno pkill -f 'inferno execute' 2>/dev/null || true")
    (catch Exception _
      ;; Container may not be running yet — that's fine
      nil)))

(defn check! []
  "Smoke-test: verify Inferno containers are healthy and the FHIR server responds
  to GET /default/fhir/metadata. Does NOT run the full test suite."
  (println "=== Inferno Environment Check ===")

  ;; 1. Check that the Inferno containers are up
  (println "\n[1/3] Checking Inferno containers...")
  (let [result (try
                 (shell {:dir inferno-dir :out :string :err :string}
                        "docker" "compose" "ps" "--services" "--filter" "status=running")
                 (catch Exception e {:exit 1 :out "" :err (.getMessage e)}))]
    (if (zero? (:exit result 0))
      (println "  ✓ Containers responding to docker compose ps")
      (do (println "  ✗ Could not query container status:" (:err result))
          (System/exit 1))))

  ;; 2. Check FHIR server metadata endpoint
  (println "\n[2/3] Checking FHIR server metadata endpoint...")
  (let [resp (try
               (curl/get "https://fhir.local:3001/default/fhir/metadata"
                         {:timeout 5000 :throw false :insecure? true})
               (catch Exception _ nil))]
    (if (and resp (= 200 (:status resp)))
      (println "  ✓ FHIR server responded with 200 at https://fhir.local:3001/default/fhir/metadata")
      (do (println "  ✗ FHIR server did not respond (status:" (some-> resp :status) ")")
          (println "    Start the server with: clojure -X:test test-server.core/-main :port 3000 :ssl-port 3001")
          (System/exit 1))))

  ;; 3. Check HL7 validator via nginx
  (println "\n[3/3] Checking HL7 validator service...")
  (let [resp (try
               (curl/get "http://localhost:8080/hl7validatorapi/"
                         {:timeout 5000 :throw false})
               (catch Exception _ nil))]
    (if (and resp (= 200 (:status resp)))
      (println "  ✓ HL7 validator responded with 200")
      (println "  ⚠ HL7 validator not reachable (may still be warming up)")))

  (println "\n=== Check complete. Environment looks good! ==="))

(def ^:private server-java-home
  "JDK the spawned FHIR server actually runs on. Pinned here (overridable via
   DROMON_SERVER_JAVA_HOME) and reused for both the launch `JAVA_HOME` and the
   JVM-flag version check, so the flag selection matches the runtime regardless
   of the ambient `java`/JAVA_HOME. A mismatch (e.g. ambient JDK 25 but the
   server launched on JDK 21) adds JDK-24+ flags the runtime rejects, so the JVM
   fails to start."
  (or (not-empty (System/getenv "DROMON_SERVER_JAVA_HOME"))
      "/usr/lib/jvm/java-21-openjdk-amd64"))

(defn- target-java-major
  "Major version of the JDK the spawned `clojure` server will run on
   (`server-java-home`). Defaults to 21 if it can't be determined."
  []
  (let [out (try (str (:err (shell {:out :string :err :string :continue true}
                                   (str server-java-home "/bin/java") "-version")))
                 (catch Exception _ ""))]
    (or (some-> (re-find #"version \"(\d+)" out) second parse-long) 21)))

(defn run-check! []
  (println "Starting test run...")

  ;; 0. Kill any stale inferno execute processes from previous runs
  (kill-stale-inferno-processes!)

  ;; 1. Ensure Inferno Docker stack is up
  (ensure-inferno-containers!)

  ;; 2. Wait for HL7 validator (cold start can take 30-90s)
  (wait-for-validator 120)

  ;; 3. Start the FHIR server
  (println "Killing existing java processes (port 3000)...")
  (try (shell {:out :string :err :string} "sh" "-c" "fuser -k 3000/tcp 2>/dev/null || true")
       (catch Exception _))
  (println "Waiting for port 3000 to clear...")
  (Thread/sleep 2000)

  (io/delete-file "server.log" true)

  (println "Starting FHIR server...")
  (let [profile?  (= "1" (System/getenv "DROMON_PERF_PROFILE"))
        otel?     (= "1" (System/getenv "DROMON_OTEL"))
        heap-size (or (System/getenv "DROMON_PERF_HEAP") "6g")
        heap-flags [(str "-J-Xmx" heap-size)
                    (str "-J-Xms" heap-size)
                    "-J-XX:+AlwaysPreTouch"]
        perf-dir  (-> (io/file "perf-analysis") .getAbsoluteFile .getPath)
        _         (when profile? (.mkdirs (io/file perf-dir)))
        jfr-flag  (when profile?
                    (str "-J-XX:StartFlightRecording=name=inferno,filename="
                         perf-dir "/inferno.jfr,settings=profile,dumponexit=true,maxsize=500M"))
        gc-flag   (when profile?
                    (str "-J-Xlog:gc*:file=" perf-dir "/gc.log:time,uptime:filecount=5,filesize=20M"))
        base-args (into ["clojure"]
                        (concat heap-flags
                                ["-J--add-opens=java.base/java.nio=ALL-UNNAMED"
                                 "-J--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED"
                                 "-J--enable-preview"]
                                ;; Java 24+ (JEP 498) restricts sun.misc.Unsafe
                                ;; memory access, which Arrow's netty allocator
                                ;; (the XTDB node) needs. The flag is rejected on
                                ;; <= 23, so only add it on 24+.
                                (when (>= (target-java-major) 24)
                                  ["-J--sun-misc-unsafe-memory-access=allow"])))
        ;; Append :otel to the alias chain when DROMON_OTEL=1 so the OTel
        ;; SDK + OTLP exporter land on the classpath alongside the :test
        ;; deps that include the xtdb2 store and uscore8 schemas.
        alias-str (if otel? "-X:otel:test" "-X:test")
        cmd       (-> base-args
                      (cond-> jfr-flag (conj jfr-flag))
                      (cond-> gc-flag  (conj gc-flag))
                      (into [alias-str "test-server.core/-main" ":port" "3000" ":ssl-port" "3001"]))
        otel-env  (when otel?
                    {"DROMON_OTEL" "1"
                     "OTEL_SERVICE_NAME" "dromon-fhir-server"
                     "OTEL_TRACES_EXPORTER" "otlp"
                     "OTEL_EXPORTER_OTLP_ENDPOINT" "http://localhost:4318"
                     "OTEL_EXPORTER_OTLP_PROTOCOL" "http/protobuf"
                     ;; Batch span processor tuned for a short test run.
                     "OTEL_BSP_SCHEDULE_DELAY" "500"
                     "OTEL_BSP_EXPORT_TIMEOUT" "5000"})]
    (when profile?
      (println "DROMON_PERF_PROFILE=1 -- launching with JFR + GC log, heap" heap-size)
      (println "  JFR:" (str perf-dir "/inferno.jfr"))
      (println "  GC log:" (str perf-dir "/gc.log")))
    (when otel?
      (println "DROMON_OTEL=1 -- launching with OTel SDK, OTLP exporter to http://localhost:4318"))
    (process cmd
             {:dir "test-server"
            :out (io/file "server.log")
            :err :out
            :extra-env (merge {"JAVA_HOME" server-java-home
                               "PATH" (str server-java-home "/bin:" (System/getenv "PATH"))}
                              ;; In backend-services mode the SMART discovery doc
                              ;; must advertise a TLS token endpoint reachable
                              ;; from the Inferno container and matching Hydra's
                              ;; issuer (the nginx TLS terminator).
                              (when (backend-mode?)
                                {"OAUTH_BASE_URL" oauth-base-url})
                              otel-env)}))

  (wait-for-server 30)

  ;; 4. Setup test data and run tests

  ;; Backend-services auth needs Hydra's issuer to match the container-reachable
  ;; TLS token endpoint, plus the nginx TLS terminator that serves it; align both
  ;; before issuing any tokens.
  (when (backend-mode?)
    (ensure-hydra-issuer!)
    (env/ensure-hydra-tls-terminator!))

  (let [{client-id :client_id
         client-secret :client_secret} (create-client)
        token (get-token client-id client-secret)]

    (println "Client ID:" client-id)
    (grant-keto-permissions client-id)
    (insert-patient token)
    (insert-test-data token)
    ;; In backend-services mode, register the private_key_jwt client, grant it
    ;; the Keto system tuple, and build the smart_auth_info Inferno signs with.
    (let [backend-auth (when (backend-mode?)
                         (let [jwks     (generate-backend-jwks)
                               jwks-str (json/generate-string jwks)
                               kid      (backend-signing-kid jwks)
                               bcid     (create-backend-client (backend-public-jwk jwks))]
                           (println "Backend-services client ID:" bcid)
                           (grant-keto-permissions bcid)
                           (backend-smart-auth-info bcid jwks-str kid)))]
      (run-inferno-tests token backend-auth))
    (try (shell {:out :string :err :string} "sh" "-c" "pkill -f 'test-server.core/-main' || true")
         (catch Exception _))
    (System/exit 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (run-check!))
