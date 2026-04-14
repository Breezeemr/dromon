(ns server.inferno-runner
  (:require [babashka.process :refer [shell process]]
            [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

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

;; ── Test runner ───────────────────────────────────────────────────────────────

(defn run-inferno-tests [token]
  (println "Running Inferno tests...")
  (.mkdirs (io/file "target"))

  (let [cred-json (json/generate-string {:access_token token})
        cmd ["docker" "compose" "exec" "-T" "inferno" "bundle" "exec" "inferno" "execute"
             "--suite" "us_core_v610"
             "--groups" "2"
             "--inputs"
             "url:https://fhir.local:3001/default/fhir"
             "patient_ids:123"
             (str "smart_auth_info:" cred-json)
             "--outputter" "json"]
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
            (spit "target/inferno-report.json" (:out result))
            (println "Inferno run completed. Report written to target/inferno-report.json")
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
                                 "-J--enable-preview"]))
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
            :extra-env (merge {"JAVA_HOME" "/usr/lib/jvm/java-21-openjdk-amd64"
                               "PATH" (str "/usr/lib/jvm/java-21-openjdk-amd64/bin:" (System/getenv "PATH"))}
                              otel-env)}))

  (wait-for-server 30)

  ;; 4. Setup test data and run tests
  (let [{client-id :client_id
         client-secret :client_secret} (create-client)
        token (get-token client-id client-secret)]

    (println "Client ID:" client-id)
    (grant-keto-permissions client-id)
    (insert-patient token)
    (insert-test-data token)
    (run-inferno-tests token)
    (try (shell {:out :string :err :string} "sh" "-c" "pkill -f 'test-server.core/-main' || true")
         (catch Exception _))
    (System/exit 0)))

(when (= *file* (System/getProperty "babashka.file"))
  (run-check!))
