(ns server.inferno-runner-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [server.inferno-g10-clients :as clients]
            [server.inferno-runner :as runner]
            [server.inferno-setup :as setup]))

(defn- thrown-message [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-message e))))

;; ── runner-config ─────────────────────────────────────────────────────────────

(deftest dromon-target-keeps-the-historical-defaults
  (let [c (runner/runner-config {})]
    (is (= :dromon (:target c)))
    (is (= :us-core (:kit c)))
    (is (= "inferno-test-kit" (:kit-dir c)))
    (is (= "us_core_v610" (:suite c)))
    (is (= ["2"] (:groups c)))
    (is (= {} (:suite-options c)))
    (is (= "https://fhir.local:3001/default/fhir" (:fhir-url c)))
    (is (= "default" (:realm c)))
    (is (= "https://fhir.local:4443" (:oauth-base-url c)))
    (is (= "https://fhir.local:4443/oauth2/token" (:token-url c)))
    (testing "the runner's own client_credentials token still comes from plain-HTTP Hydra"
      (is (= "http://127.0.0.1:4444/oauth2/token" (:runner-token-url c))))
    (is (= "http://127.0.0.1:4445" (:hydra-admin-url c)))
    (is (= "http://127.0.0.1:4467" (:keto-write-url c)))
    (is (= "123" (:patient-ids c)))
    (is (= "1" (:group-id c)))
    (is (false? (:backend? c)))))

(deftest external-target-is-configured-entirely-from-env
  (let [c (runner/runner-config
           {"INFERNO_TARGET"          "external"
            "INFERNO_KIT"             "g10"
            "INFERNO_FHIR_URL"        "https://fhir.example.test:8444/realm/dev/fhir/"
            "INFERNO_OAUTH_BASE_URL"  "https://auth.example.test:4444/"
            "INFERNO_HYDRA_ADMIN_URL" "https://auth.example.test:4445/"
            "INFERNO_PATIENT_IDS"     "p1,p2"
            "INFERNO_GROUP_ID"        "g-1"})]
    (is (= :external (:target c)))
    (is (= "inferno-g10-kit" (:kit-dir c)))
    (is (= "g10_certification" (:suite c)))
    (is (= ["1" "3"] (:groups c)))
    (is (= {"us_core_version"          "us_core_7"
            "smart_app_launch_version" "smart_app_launch_2_2"
            "multi_patient_version"    "multi_patient_api_stu2"}
           (:suite-options c)))
    (is (= "https://fhir.example.test:8444/realm/dev/fhir" (:fhir-url c)))
    (is (= "dev" (:realm c)))
    (is (= "https://auth.example.test:4444" (:oauth-base-url c)))
    (is (= "https://auth.example.test:4444/oauth2/token" (:token-url c)))
    (is (= (:token-url c) (:runner-token-url c)))
    (is (= "https://auth.example.test:4445" (:hydra-admin-url c)))
    (testing "no Keto grants against an external target unless asked for"
      (is (nil? (:keto-write-url c))))
    (is (= "p1,p2" (:patient-ids c)))
    (is (= "g-1" (:group-id c)))))

(deftest token-url-override-and-alias
  (is (= "https://a.test/t" (:token-url (runner/runner-config {"INFERNO_TOKEN_URL" "https://a.test/t"}))))
  (is (= "https://b.test/t" (:token-url (runner/runner-config {"BACKEND_TOKEN_URL" "https://b.test/t"}))))
  (is (= "https://a.test/t" (:token-url (runner/runner-config {"INFERNO_TOKEN_URL" "https://a.test/t"
                                                               "BACKEND_TOKEN_URL" "https://b.test/t"})))))

(deftest malformed-config-throws
  (is (re-find #"INFERNO_TARGET" (thrown-message #(runner/runner-config {"INFERNO_TARGET" "prod"}))))
  (is (re-find #"INFERNO_KIT" (thrown-message #(runner/runner-config {"INFERNO_KIT" "g11"}))))
  (is (re-find #"INFERNO_FHIR_URL" (thrown-message #(runner/runner-config {"INFERNO_TARGET" "external"}))))
  (is (re-find #"INFERNO_CLIENT_SECRET" (thrown-message #(runner/runner-config {"INFERNO_CLIENT_ID" "x"})))))

(deftest suite-options-are-checked-against-the-kit
  (is (= {"us_core_version" "us_core_6" "multi_patient_version" "multi_patient_api_stu1"}
         (:suite-options (runner/runner-config
                          {"INFERNO_KIT" "g10"
                           "INFERNO_SUITE_OPTIONS" "us_core_version:us_core_6, multi_patient_version:multi_patient_api_stu1"}))))
  (testing "US Core 8 is not offered by the pinned g10 kit"
    (is (re-find #"not offered"
                 (thrown-message #(runner/runner-config {"INFERNO_KIT" "g10"
                                                         "INFERNO_SUITE_OPTIONS" "us_core_version:us_core_8"})))))
  (is (re-find #"option:value"
               (thrown-message #(runner/runner-config {"INFERNO_KIT" "g10" "INFERNO_SUITE_OPTIONS" "us_core_7"}))))
  (testing "the US Core kit passes options through unchecked"
    (is (= {"a" "b"} (:suite-options (runner/runner-config {"INFERNO_SUITE_OPTIONS" "a:b"}))))))

(deftest groups-all-runs-the-whole-suite
  (is (= [] (:groups (runner/runner-config {"INFERNO_GROUPS" "all"}))))
  (is (= ["1.8" "8"] (:groups (runner/runner-config {"INFERNO_GROUPS" "1.8, 8"})))))

(deftest url->realm-takes-the-segment-before-fhir
  (is (= "default" (runner/url->realm "https://fhir.local:3001/default/fhir")))
  (is (= "dev" (runner/url->realm "https://host.test/realm/dev/fhir")))
  (is (nil? (runner/url->realm "https://host.test/baseR4"))))

;; ── command and inputs ────────────────────────────────────────────────────────

(deftest g10-discovery-command
  (let [c   (runner/runner-config {"INFERNO_KIT" "g10" "INFERNO_TARGET" "external"
                                   "INFERNO_FHIR_URL" "https://fhir.example.test/fhir"})
        cmd (runner/execute-command c nil nil)]
    (is (= ["docker" "compose" "exec" "-T" "inferno" "bundle" "exec" "inferno" "execute"
            "--suite" "g10_certification"
            "--suite-options" "multi_patient_version:multi_patient_api_stu2"
            "smart_app_launch_version:smart_app_launch_2_2" "us_core_version:us_core_7"
            "--groups" "1" "3"
            "--inputs" "url:https://fhir.example.test/fhir"
            "standalone_smart_auth_info:{\"auth_type\":\"symmetric\",\"use_discovery\":\"true\"}"
            "ehr_smart_auth_info:{\"auth_type\":\"symmetric\",\"use_discovery\":\"true\"}"
            "--outputter" "json"]
           cmd))))

(deftest redact-output-hides-credential-arguments
  (let [cmd ["docker" "compose" "exec" "--inputs" "url:https://f.test/fhir"
             "bulk_smart_auth_info:{\"client_secret\":\"s3cr3t\"}"]
        err (str "Error: executing docker-compose exec --inputs url:https://f.test/fhir "
                 "bulk_smart_auth_info:{\"client_secret\":\"s3cr3t\"}: exit status 7")
        out (runner/redact-output cmd err)]
    (is (not (str/includes? out "s3cr3t")))
    (is (str/includes? out "bulk_smart_auth_info:<redacted>"))
    (is (str/includes? out "url:https://f.test/fhir"))
    (is (= "" (runner/redact-output cmd nil)))))

(deftest parse-report-skips-log-lines
  (let [out (str "W, [2026-11-01T23:33:06 #52]  WARN -- : Unknown input `ehr_smart_auth_info`\n"
                 "[\n  {\"result\": \"pass\", \"test_id\": \"a\"},\n  {\"result\": \"wait\"}\n]\n")]
    (is (= [{:result "pass" :test_id "a"} {:result "wait"}] (runner/parse-report out))))
  (is (= [{:result "fail"}]
         (runner/parse-report "[worker] starting\n[\n  {\"result\": \"fail\"}\n]\n")))
  (is (nil? (runner/parse-report "\"Missing the following required inputs: url\"")))
  (is (nil? (runner/parse-report "[\n  {\"result\": ")))
  (testing "a log line that is itself a JSON array is not the report"
    (is (= [{:result "pass"}]
           (runner/parse-report "[1, 2]\n[\n  {\"result\": \"pass\"}\n]\n"))))
  (is (nil? (runner/parse-report nil))))

(deftest g10-backend-inputs
  (let [c (runner/runner-config {"INFERNO_KIT" "g10" "INFERNO_TARGET" "external"
                                 "INFERNO_AUTH_MODE" "backend_services" "INFERNO_GROUPS" "8"
                                 "INFERNO_FHIR_URL" "https://fhir.example.test/fhir"
                                 "INFERNO_GROUP_ID" "grp" "INFERNO_PATIENT_IDS" "a,b"})]
    (is (= ["url:https://fhir.example.test/fhir"
            "bulk_server_url:https://fhir.example.test/fhir"
            "bulk_smart_auth_info:{AUTH}"
            "group_id:grp"
            "bulk_patient_ids_in_group:a,b"
            "bulk_timeout:180"
            "since_timestamp:2020-01-01T00:00:00Z"]
           (runner/input-tokens c nil "{AUTH}")))))

(deftest us-core-inputs-unchanged
  (is (= ["url:https://fhir.local:3001/default/fhir"
          "patient_ids:123"
          "smart_auth_info:{\"access_token\":\"tok\"}"]
         (runner/input-tokens (runner/runner-config {}) "tok" nil)))
  (is (= ["url:x" "smart_auth_info:{\"access_token\":\"tok\"}"]
         (runner/input-tokens (runner/runner-config {"INFERNO_INPUTS" "url:x smart_auth_info:{{cred_json}}"}) "tok" nil))))

(deftest printed-command-hides-credentials
  (let [printed (str/join " " (runner/redact-command
                               ["--inputs" "url:https://x.test/fhir"
                                "smart_auth_info:{\"access_token\":\"SECRET1\"}"
                                "bulk_smart_auth_info:{\"jwks\":\"SECRET2\"}"
                                "patient_ids:123"]))]
    (is (not (str/includes? printed "SECRET")))
    (is (str/includes? printed "url:https://x.test/fhir"))
    (is (str/includes? printed "patient_ids:123"))
    (is (str/includes? printed "smart_auth_info:<redacted>"))))

;; ── backend keys ──────────────────────────────────────────────────────────────

(def ^:private kit-like-jwks
  {:keys [{:kty "EC" :crv "P-384" :x "X" :y "Y" :use "sig" :key_ops ["verify"] :kid "ec-kid" :alg "ES384"}
          {:kty "EC" :crv "P-384" :x "X" :y "Y" :d "D" :key_ops ["sign"] :kid "ec-kid" :alg "ES384"}
          {:kty "RSA" :n "N" :e "AQAB" :use "sig" :key_ops ["verify"] :kid "rsa-kid" :alg "RS384"}
          {:kty "RSA" :n "N" :e "AQAB" :d "D" :key_ops ["sign"] :kid "rsa-kid" :alg "RS384"}]})

(deftest public-key-and-kid-are-picked-by-alg
  (is (= {:kty "EC" :crv "P-384" :x "X" :y "Y" :kid "ec-kid" :alg "ES384" :use "sig"}
         (runner/backend-public-jwk kit-like-jwks)))
  (is (= {:kty "RSA" :n "N" :e "AQAB" :kid "rsa-kid" :alg "RS384" :use "sig"}
         (runner/backend-public-jwk kit-like-jwks "RS384")))
  (is (= "ec-kid" (runner/backend-signing-kid kit-like-jwks)))
  (is (= "rsa-kid" (runner/backend-signing-kid kit-like-jwks "RS384"))))

;; ── compose patch ─────────────────────────────────────────────────────────────

(def ^:private stock-g10-compose
  {:services {:inferno {:build {:context "./"} :volumes ["./data:/opt/inferno/data"]}
              :worker  {:build {:context "./"} :volumes ["./data:/opt/inferno/data"]
                        :command "bundle exec sidekiq -r ./worker.rb"}
              :nginx   {:extends {:file "docker-compose.background.yml" :service "nginx"}}}})

(def ^:private stock-g10-background
  {:services {:nginx {:image "nginx" :ports ["80:80"]}
              :redis {:image "redis" :ports ["6379:6379"]}
              :hl7_validator_service {:image "infernocommunity/inferno-resource-validator"}}})

(deftest compose-patch-is-idempotent-and-collapses-duplicates
  (let [opts    {:nginx-port 8081
                 :extra-host-names (setup/extra-host-names {"INFERNO_EXTRA_HOSTS" "fhir.example.test, auth.example.test"})
                 :environment {"INFERNO_HOST" "http://localhost:8081"}}
        once    (setup/patch-compose stock-g10-compose opts)
        twice   (setup/patch-compose once opts)
        inferno (get-in once [:services :inferno])]
    (is (= once twice))
    (is (= ["host.docker.internal:host-gateway" "fhir.local:host-gateway"
            "fhir.example.test:host-gateway" "auth.example.test:host-gateway"]
           (:extra_hosts inferno)))
    (is (= ["./data:/opt/inferno/data" "./rootCA.pem:/opt/inferno/rootCA.pem"] (:volumes inferno)))
    (is (= ["INFERNO_HOST=http://localhost:8081" "SSL_CERT_FILE=/opt/inferno/rootCA.pem"] (:environment inferno)))
    (is (= (:extra_hosts inferno) (get-in once [:services :worker :extra_hosts])))
    (is (= ["8081:80"] (get-in once [:services :nginx :ports])))
    (testing "duplicate entries left by the old non-idempotent patch collapse"
      (let [dup (-> stock-g10-compose
                    (assoc-in [:services :inferno :volumes]
                              ["./data:/opt/inferno/data" "./rootCA.pem:/opt/inferno/rootCA.pem"
                               "./rootCA.pem:/opt/inferno/rootCA.pem"])
                    (assoc-in [:services :inferno :environment]
                              ["SSL_CERT_FILE=/opt/inferno/rootCA.pem" "SSL_CERT_FILE=/opt/inferno/rootCA.pem"]))]
        (is (= (get-in once [:services :inferno :volumes])
               (get-in (setup/patch-compose dup opts) [:services :inferno :volumes])))
        (is (= (get-in once [:services :inferno :environment])
               (get-in (setup/patch-compose dup opts) [:services :inferno :environment])))))))

(deftest background-patch-moves-nginx-and-unpublishes-redis
  (let [patched (setup/patch-background-compose stock-g10-background {:nginx-port 8081 :drop-redis-port? true})]
    (is (= ["8081:80"] (get-in patched [:services :nginx :ports])))
    (is (nil? (get-in patched [:services :redis :ports])))
    (is (= "redis" (get-in patched [:services :redis :image])))
    (is (= setup/validator-mem-limit (get-in patched [:services :hl7_validator_service :mem_limit]))))
  (testing "the US Core kit keeps its redis port"
    (is (= ["6379:6379"] (get-in (setup/patch-background-compose stock-g10-background {:nginx-port 8080})
                                 [:services :redis :ports])))))

(deftest extra-hosts-and-port-validation
  (is (re-find #"not a host name" (thrown-message #(setup/extra-host-names {"INFERNO_EXTRA_HOSTS" "a.test:1.2.3.4"}))))
  (is (= 8081 (setup/nginx-port :g10 {})))
  (is (= 9000 (setup/nginx-port :g10 {"INFERNO_G10_PORT" "9000"})))
  (is (re-find #"not a port" (thrown-message #(setup/nginx-port :g10 {"INFERNO_G10_PORT" "80a"})))))

;; ── g10 clients ───────────────────────────────────────────────────────────────

(deftest g10-client-payloads
  (let [ps (clients/client-payloads {:prefix         "inferno-g10"
                                     :inferno-base   "http://localhost:8081"
                                     :secrets        {:symmetric "s1" :ehr "s2"}
                                     :asymmetric-jwk {:kid "a"}
                                     :backend-jwk    {:kid "b"}})
        user-facing (dissoc ps :backend)
        scopes      (set (str/split (get-in ps [:public :scope]) #" "))]
    (is (= #{:public :symmetric :ehr :asymmetric :backend} (set (keys ps))))
    (is (every? #(= "public" (:subject_type %)) (vals ps)))
    (is (every? #(= ["http://localhost:8081/custom/smart/redirect"] (:redirect_uris %)) (vals user-facing)))
    (is (= {:smart {:ehr_launch true :launch_uri "http://localhost:8081/custom/smart/launch"}}
           (get-in ps [:ehr :metadata])))
    (is (every? #(nil? (:metadata %)) (vals (dissoc ps :ehr))))
    (is (= "none" (get-in ps [:public :token_endpoint_auth_method])))
    (is (= ["client_secret_basic" "s1"] ((juxt :token_endpoint_auth_method :client_secret) (:symmetric ps))))
    (is (= ["private_key_jwt" "ES384" {:keys [{:kid "a"}]}]
           ((juxt :token_endpoint_auth_method :token_endpoint_auth_signing_alg :jwks) (:asymmetric ps))))
    (is (= ["client_credentials"] (get-in ps [:backend :grant_types])))
    (is (= "system/*.read system/*.rs system/*.r" (get-in ps [:backend :scope])))
    (testing "the exact scope strings the suite requests are registered"
      (is (every? scopes ["openid" "fhirUser" "launch" "launch/patient" "offline_access"
                          "patient/Patient.read" "patient/Patient.rs" "user/Observation.rs"
                          "patient/Medication.rs" "user/Specimen.read"
                          "patient/Media.rs" "user/Media.rs"]))
      (is (every? scopes clients/granular-scopes))
      (is (= 8 (count clients/granular-scopes))))))

(deftest preset-template-renders-completely
  (let [template (slurp clients/preset-template-path)
        values   (clients/preset-values {:fhir-url "https://f.test/fhir" :token-url "https://a.test/oauth2/token"
                                         :group-id "1" :patient-ids "123"}
                                        {:public "p" :symmetric "s" :ehr "e" :asymmetric "a" :backend "b"}
                                        {:symmetric "sec\"ret" :ehr "x"}
                                        "kid")
        rendered (json/parse-string (clients/render-template template values) true)]
    (is (= "g10_certification" (:test_suite_id rendered)))
    (testing "the asymmetric launch signs with the alg of the one registered key"
      (is (= "ES384" (get-in (some #(when (= "asymmetric_smart_auth_info" (:name %)) %) (:inputs rendered))
                             [:value :encryption_algorithm]))))
    (is (not (str/includes? (json/generate-string rendered) "${")))
    (is (= "sec\"ret" (get-in (some #(when (= "standalone_smart_auth_info" (:name %)) %) (:inputs rendered))
                              [:value :client_secret])))
    (testing "the template in git carries placeholders, never a credential"
      (is (str/includes? template "${SYMMETRIC_CLIENT_SECRET}")))
    (testing "a placeholder without a value is refused"
      (is (re-find #"FHIR_URL" (thrown-message #(clients/render-template template (dissoc values "FHIR_URL"))))))))

;; ── review fixes ──────────────────────────────────────────────────────────────

(deftest us-core-kit-dir-follows-the-inferno-dir-var
  (testing "dromon-datomic rebinds inferno-dir to ../dromon/inferno-test-kit"
    (with-redefs [runner/inferno-dir "../dromon/inferno-test-kit"]
      (is (= "../dromon/inferno-test-kit" (:kit-dir (runner/runner-config {}))))
      (is (= "inferno-g10-kit" (:kit-dir (runner/runner-config {"INFERNO_KIT" "g10"})))))))

(deftest dromon-target-pins-the-oauth-issuer
  (is (re-find #"INFERNO_OAUTH_BASE_URL"
               (thrown-message #(runner/runner-config {"INFERNO_OAUTH_BASE_URL" "https://other.test"}))))
  (is (= "https://fhir.local:4443"
         (:oauth-base-url (runner/runner-config {"INFERNO_OAUTH_BASE_URL" "https://fhir.local:4443/"})))))

(deftest keto-grants-are-least-privilege-for-external-targets
  (let [dromon   (runner/runner-config {})
        external (runner/runner-config {"INFERNO_TARGET" "external"
                                        "INFERNO_FHIR_URL" "https://f.test/realm/dev/fhir"})
        relations #(set (map second %))]
    (testing "dromon keeps the historical grant set (it seeds data)"
      (is (= #{"read" "write" "search-type"} (relations (runner/runner-client-tuples dromon))))
      (is (= (runner/runner-client-tuples dromon) (runner/backend-client-tuples dromon))))
    (testing "an external target never gets write"
      (is (= #{"read" "search-type"} (relations (runner/runner-client-tuples external))))
      (is (= [["system" "read"]] (runner/backend-client-tuples external))))))

(deftest redaction-by-value-survives-quoting-and-escaping
  (let [jwks "{\"keys\":[{\"kty\":\"EC\",\"d\":\"PRIVATEKEYMATERIAL\",\"x\":\"pubx\"}]}"
        auth (json/generate-string {:auth_type "backend_services" :client_id "c1"
                                    :token_url "https://a.test/oauth2/token" :jwks jwks})
        cmd  ["exec" "--inputs" "url:https://f.test/fhir" (str "bulk_smart_auth_info:" auth)
              "custom_authorization_header:Bearer HEADERTOKEN123"
              "token_url:https://a.test/oauth2/token"]
        echo (str "Error: exec '--inputs' 'bulk_smart_auth_info:" (str/replace auth "\"" "\\\"") "'"
                  " custom_authorization_header:\"Bearer HEADERTOKEN123\" runner=RUNNERTOKEN99")
        out  (runner/redact-output cmd ["RUNNERTOKEN99"] echo)]
    (is (not (str/includes? out "PRIVATEKEYMATERIAL")))
    (is (not (str/includes? out "HEADERTOKEN123")))
    (is (not (str/includes? out "RUNNERTOKEN99")))
    (testing "non-secret values stay readable"
      (is (str/includes? out "pubx"))
      (is (= ["exec" "--inputs" "url:https://f.test/fhir" "bulk_smart_auth_info:<redacted>"
              "custom_authorization_header:<redacted>" "token_url:https://a.test/oauth2/token"]
             (runner/redact-command cmd))))
    (testing "short values are never treated as secrets"
      (is (= [] (runner/secret-values ["x_token:abc"] ["short"]))))))

(deftest report-inputs-and-outputs-are-redacted
  (let [report [{:result "pass"
                 :inputs [{:name "url" :value "https://f.test/fhir" :type "text"}
                          {:name "smart_auth_info" :type "auth_info"
                           :value (json/generate-string {:access_token "AT-SECRET" :refresh_token "RT-SECRET"
                                                         :client_secret "CS-SECRET" :client_id "cid"
                                                         :token_url "https://a.test/t"})}]
                 :outputs [{:name "standalone_access_token" :value "OUT-SECRET"}
                           {:name "token_url" :value "https://a.test/t"}]}]
        red    (runner/redact-report report)
        text   (json/generate-string red)]
    (doseq [secret ["AT-SECRET" "RT-SECRET" "CS-SECRET" "OUT-SECRET"]]
      (is (not (str/includes? text secret)) secret))
    (is (str/includes? text "cid"))
    (is (= "https://a.test/t" (get-in red [0 :outputs 1 :value])))
    (is (= "https://f.test/fhir" (get-in red [0 :inputs 0 :value])))))

(deftest g10-clone-must-be-the-pinned-commit
  (is (nil? (setup/check-g10-head! setup/g10-kit-commit)))
  (is (re-find #"not ff87c7e" (thrown-message #(setup/check-g10-head! "0000000")))))
