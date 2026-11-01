(ns server.inferno-runner
  (:require [babashka.process :as p :refer [shell process]]
            [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [server.docker-env :as env]
            [server.inferno-setup :as setup]))

;; ── Runner configuration (env) ────────────────────────────────────────────────
;; Every knob is read once from the environment by `runner-config`, a pure fn
;; of the env map, so the defaults are testable. Two independent axes:
;;
;;   INFERNO_TARGET   `dromon` (default): boot the local test-server, align the
;;                    local Hydra issuer, seed the test data. `external`: test
;;                    a server that is already running elsewhere; nothing is
;;                    booted, seeded or reconfigured, and the target's Hydra
;;                    issuer is never touched.
;;   INFERNO_KIT      `us-core` (default, inferno-test-kit/) or `g10`
;;                    (inferno-g10-kit/, the ONC (g)(10) certification kit).
;;
;; Target endpoints:
;;   INFERNO_FHIR_URL         FHIR base (dromon default: the local `default`
;;                            tenant; required for `external`)
;;   INFERNO_OAUTH_BASE_URL   authorization server issuer (default
;;                            https://fhir.local:4443)
;;   INFERNO_TOKEN_URL        token endpoint handed to Inferno (default
;;                            <issuer>/oauth2/token; BACKEND_TOKEN_URL is an alias)
;;   INFERNO_HYDRA_ADMIN_URL  Hydra admin API used to register clients
;;                            (default http://127.0.0.1:4445)
;;   INFERNO_KETO_WRITE_URL   Keto write API for `grant-keto-permissions`
;;                            (dromon default http://127.0.0.1:4467; for
;;                            `external` grants are skipped unless it is set)
;;   INFERNO_REALM            realm for Keto objects (default: the path segment
;;                            before /fhir in INFERNO_FHIR_URL)
;;
;; Credentials and test data:
;;   INFERNO_CLIENT_ID / INFERNO_CLIENT_SECRET  use this client_credentials
;;                            client instead of registering one (both or neither)
;;   INFERNO_ACCESS_TOKEN     use this bearer token instead of obtaining one
;;   INFERNO_PATIENT_IDS      patient ids (default "123")
;;   INFERNO_GROUP_ID         bulk Group id (default "1")
;;   INFERNO_AUTH_MODE        `backend_services` runs the private_key_jwt flow
;;
;; Suite selection:
;;   INFERNO_SUITE            suite id (default per kit)
;;   INFERNO_GROUPS           space/comma group short ids, or all/"" for the
;;                            whole suite (default per kit)
;;   INFERNO_SUITE_OPTIONS    space/comma `option:value` pairs (g10 default:
;;                            US Core 7, SMART App Launch 2.2, Bulk Data 2.0)
;;   INFERNO_PRESET_FILE      preset path inside the kit container
;;   INFERNO_INPUTS           full replacement for the --inputs tokens; the
;;                            tokens {{token}}, {{cred_json}} and
;;                            {{backend_auth}} expand to the access token, its
;;                            JSON envelope and the backend smart_auth_info.

(def default-dromon-fhir-url "https://fhir.local:3001/default/fhir")
(def default-oauth-base-url "https://fhir.local:4443")
(def default-hydra-admin-url "http://127.0.0.1:4445")
(def default-dromon-keto-write-url "http://127.0.0.1:4467")
;; dromon mode issues its own client_credentials token against the plain-HTTP
;; Hydra port; the TLS terminator on 4443 only runs in backend-services mode.
(def ^:private dromon-runner-token-url "http://127.0.0.1:4444/oauth2/token")

(def g10-suite-option-values
  "The suite options onc-certification-g10-test-kit (setup/g10-kit-tag) offers.
   US Core 8.0.1 is not among them."
  {"us_core_version"          #{"us_core_6" "us_core_7"}
   "smart_app_launch_version" #{"smart_app_launch_2" "smart_app_launch_2_2"}
   "multi_patient_version"    #{"multi_patient_api_stu1" "multi_patient_api_stu2"}})

(def kits
  {:us-core {:dir            setup/us-core-kit-dir
             :default-suite  "us_core_v610"
             :default-groups "2"}
   :g10     {:dir            setup/g10-kit-dir
             :default-suite  "g10_certification"
             ;; The non-interactive discovery groups: standalone (1.8) and EHR
             ;; (3.7) .well-known/smart-configuration checks, SMART 2.2. The
             ;; Multi-Patient API (8) needs INFERNO_AUTH_MODE=backend_services.
             :default-groups "1.8 3.7"
             :default-suite-options {"us_core_version"          "us_core_7"
                                     "smart_app_launch_version" "smart_app_launch_2_2"
                                     "multi_patient_version"    "multi_patient_api_stu2"}
             :suite-option-values g10-suite-option-values}})

(defn- env-str [env k] (not-empty (str/trim (str (get env k "")))))

(defn- strip-trailing-slash [s] (str/replace (str s) #"/+$" ""))

(defn- parse-enum [env k allowed default]
  (if-let [v (env-str env k)]
    (or (get allowed v)
        (throw (ex-info (str k " must be one of " (str/join ", " (sort (keys allowed)))
                             ", got " (pr-str v))
                        {:var k :value v})))
    default))

(defn parse-suite-options
  "INFERNO_SUITE_OPTIONS (space/comma `option:value` pairs) as a map. A token
   without a colon, an option the kit does not offer, or a value the kit does
   not list for it is rejected: Inferno would otherwise run the default
   option set and report it as the requested one."
  [s allowed]
  (into {}
        (map (fn [token]
               (let [[k v] (str/split token #":" 2)]
                 (when (or (str/blank? k) (str/blank? v))
                   (throw (ex-info (str "INFERNO_SUITE_OPTIONS entry is not option:value: " (pr-str token))
                                   {:entry token})))
                 (when (and allowed (not (contains? (get allowed k) v)))
                   (throw (ex-info (str "INFERNO_SUITE_OPTIONS " (pr-str token) " is not offered by this kit; "
                                        "options: " (pr-str (into (sorted-map) (update-vals allowed sort))))
                                   {:entry token :allowed allowed})))
                 [k v])))
        (setup/split-list s)))

(defn url->realm
  "The tenant segment of a FHIR base url, the path segment right before
   /fhir: \"https://fhir.local:3001/default/fhir\" -> \"default\",
   \"https://host/realm/dev/fhir\" -> \"dev\".

   Keto objects in the `fhir` namespace carry the realm as their first segment,
   so a client provisioned against one tenant no longer authorizes every other
   tenant the server hosts. Derived from the url the suite is actually pointed
   at rather than hard-coded, so INFERNO_FHIR_URL keeps working."
  [url]
  (second (re-find #"://[^/]+/(?:[^/]+/)*?([^/]+)/fhir/?$" (str url))))

(defn runner-config
  "The runner's whole configuration from an env map (see the table above).
   Malformed values throw rather than falling back to a default."
  [env]
  (let [target   (parse-enum env "INFERNO_TARGET" {"dromon" :dromon "external" :external} :dromon)
        kit      (parse-enum env "INFERNO_KIT" {"us-core" :us-core "g10" :g10} :us-core)
        kit-spec (get kits kit)
        dromon?  (= target :dromon)
        fhir-url (or (some-> (env-str env "INFERNO_FHIR_URL") strip-trailing-slash)
                     (if dromon?
                       default-dromon-fhir-url
                       (throw (ex-info "INFERNO_TARGET=external needs INFERNO_FHIR_URL (the FHIR base to test)."
                                       {:var "INFERNO_FHIR_URL"}))))
        oauth    (strip-trailing-slash (or (env-str env "INFERNO_OAUTH_BASE_URL") default-oauth-base-url))
        token    (or (env-str env "INFERNO_TOKEN_URL")
                     (env-str env "BACKEND_TOKEN_URL")
                     (str oauth "/oauth2/token"))
        cid      (env-str env "INFERNO_CLIENT_ID")
        csecret  (env-str env "INFERNO_CLIENT_SECRET")
        _        (when (not= (some? cid) (some? csecret))
                   (throw (ex-info "Set both INFERNO_CLIENT_ID and INFERNO_CLIENT_SECRET, or neither."
                                   {:client-id? (some? cid)})))
        groups   (str/trim (str (get env "INFERNO_GROUPS" (:default-groups kit-spec))))
        options  (if-let [s (env-str env "INFERNO_SUITE_OPTIONS")]
                   (parse-suite-options s (:suite-option-values kit-spec))
                   (:default-suite-options kit-spec {}))]
    {:target            target
     :kit               kit
     :kit-dir           (:dir kit-spec)
     :suite             (or (env-str env "INFERNO_SUITE") (:default-suite kit-spec))
     :groups            (if (contains? #{"" "all" "ALL"} groups) [] (setup/split-list groups))
     :suite-options     options
     :preset-file       (env-str env "INFERNO_PRESET_FILE")
     :inputs-override   (env-str env "INFERNO_INPUTS")
     :fhir-url          fhir-url
     :realm             (or (env-str env "INFERNO_REALM") (url->realm fhir-url) "default")
     :oauth-base-url    oauth
     :token-url         token
     :runner-token-url  (if dromon? dromon-runner-token-url token)
     :hydra-admin-url   (strip-trailing-slash (or (env-str env "INFERNO_HYDRA_ADMIN_URL") default-hydra-admin-url))
     :keto-write-url    (some-> (or (env-str env "INFERNO_KETO_WRITE_URL")
                                    (when dromon? default-dromon-keto-write-url))
                                strip-trailing-slash)
     :client-id         cid
     :client-secret     csecret
     :access-token      (env-str env "INFERNO_ACCESS_TOKEN")
     :patient-ids       (or (env-str env "INFERNO_PATIENT_IDS") "123")
     :group-id          (or (env-str env "INFERNO_GROUP_ID") "1")
     :backend?          (= "backend_services" (env-str env "INFERNO_AUTH_MODE"))}))

(defn- cfg [] (runner-config (System/getenv)))

;; ── Pre-flight helpers ────────────────────────────────────────────────────────

(def inferno-dir
  "The US Core kit clone. Kept for callers that predate INFERNO_KIT; the runner
   itself uses the configured kit's :kit-dir."
  setup/us-core-kit-dir)

(defn ensure-inferno-containers!
  ([] (ensure-inferno-containers! (cfg)))
  ([{:keys [kit-dir]}]
   (println "Ensuring Inferno containers are up..." kit-dir)
   (shell {:dir kit-dir}
          "docker" "compose" "up" "-d"
          "--wait"        ; wait for healthchecks where defined
          "inferno" "worker" "hl7_validator_service"
          "nginx" "redis")))

(defn- validator-ready?
  "The US Core kit's nginx proxies the HL7 validator on the host; the g10 kit's
   does not, so its validator is probed from inside the inferno container."
  [{:keys [kit kit-dir]}]
  (case kit
    :us-core (let [resp (try (curl/get (str "http://localhost:" setup/us-core-nginx-port "/hl7validatorapi/")
                                       {:timeout 3000 :throw false})
                             (catch Exception _ nil))]
               (= 200 (:status resp)))
    :g10 (-> (shell {:dir kit-dir :out :string :err :string :continue true}
                    "docker" "compose" "exec" "-T" "inferno"
                    "curl" "-s" "-o" "/dev/null" "-w" "%{http_code}" "--max-time" "3"
                    "http://hl7_validator_service:3500/")
             :out str/trim (= "200"))))

(defn wait-for-validator
  "Polls the HL7 validator until it responds with 200 or timeout.
   The hl7_validator_service takes 30-90s to load FHIR packages on cold start."
  ([timeout-seconds] (wait-for-validator (cfg) timeout-seconds))
  ([config timeout-seconds]
   (println "Waiting for HL7 validator to be ready (may take up to 90s on cold start)...")
   (loop [left timeout-seconds]
     (cond
       (validator-ready? config)
       (println "HL7 validator is ready!")

       (<= left 0)
       (do (println "ERROR: HL7 validator did not become ready within timeout.")
           (println "Check: docker compose -f" (str (:kit-dir config) "/docker-compose.yml") "logs hl7_validator_service")
           (System/exit 1))

       :else
       (do (print ".") (flush)
           (Thread/sleep 3000)
           (recur (- left 3)))))
   (println)))

;; ── FHIR server helpers ───────────────────────────────────────────────────────

(defn- metadata-status [fhir-url]
  (try
    (:status (curl/get (str fhir-url "/metadata")
                       {:timeout 2000 :throw false :insecure? true}))
    (catch Exception _ nil)))

(defn wait-for-server
  ([timeout-seconds] (wait-for-server (cfg) timeout-seconds))
  ([{:keys [fhir-url]} timeout-seconds]
   (println "Waiting for FHIR server at" (str fhir-url "/metadata") "...")
   (loop [left timeout-seconds]
     (if (= 200 (metadata-status fhir-url))
       (println "Server is up!")
       (if (<= left 0)
         (do
           (println "ERROR: FHIR server did not answer" (str fhir-url "/metadata") "within timeout. Check server.log.")
           (System/exit 1))
         (do
           (Thread/sleep 1000)
           (recur (dec left))))))))

;; ── Hydra / Keto setup ────────────────────────────────────────────────────────

(defn- parse-json-body [resp]
  (try (json/parse-string (:body resp) true) (catch Exception _ nil)))

(defn create-client
  ([] (create-client (cfg)))
  ([{:keys [hydra-admin-url]}]
   (println "Creating Hydra client...")
   (let [resp (try
                (curl/post (str hydra-admin-url "/admin/clients")
                           {:headers {"Content-Type" "application/json"}
                            :body (json/generate-string {:client_name "test"
                                                         :grant_types ["client_credentials"]
                                                         :token_endpoint_auth_method "client_secret_basic"})
                            :timeout 10000
                            :throw false})
                (catch Exception e {:status 500 :body (.getMessage e)}))]
     (when (not= 201 (:status resp))
       (println "Failed to create client:" (:body resp))
       (System/exit 1))
     (parse-json-body resp))))

(defn get-token
  ([client-id client-secret] (get-token (cfg) client-id client-secret))
  ([{:keys [runner-token-url]} client-id client-secret]
   (println "Getting access token from" runner-token-url "...")
   (let [resp (try
                (curl/post runner-token-url
                           {:basic-auth [client-id client-secret]
                            :form-params {"grant_type" "client_credentials"}
                            :timeout 10000
                            :throw false})
                (catch Exception e {:status 500 :body (.getMessage e)}))
         body (parse-json-body resp)]
     (when (not= 200 (:status resp))
       (println "Failed to get token: HTTP" (:status resp) (:error body) (:error_description body))
       (System/exit 1))
     (:access_token body))))

(def keto-type-objects
  ["Patient/123" "Patient" "Observation" "AllergyIntolerance" "CarePlan" "CareTeam" "Condition"
   "Coverage" "Device" "DiagnosticReport" "DocumentReference" "Encounter" "Goal"
   "Immunization" "MedicationDispense" "MedicationRequest" "Procedure"
   "QuestionnaireResponse" "RelatedPerson" "ServiceRequest" "Specimen"
   "Practitioner" "PractitionerRole" "Organization" "Location" "Provenance" "Endpoint"
   "system"])

(defn grant-keto-permissions
  ([client-id] (grant-keto-permissions (cfg) client-id))
  ([{:keys [realm keto-write-url]} client-id]
   (if-not keto-write-url
     (println "No INFERNO_KETO_WRITE_URL -- skipping Keto grants for" client-id)
     (do
       (println "Granting Keto permissions...")
       (println "  realm:" realm)
       (doseq [obj keto-type-objects
               relation ["read" "write" "search-type"]]
         (let [resp (try
                      (curl/put (str keto-write-url "/admin/relation-tuples")
                                {:headers {"Content-Type" "application/json"}
                                 :body (json/generate-string {:namespace "fhir"
                                                              :object (str realm "/" obj)
                                                              :relation relation
                                                              :subject_id client-id})
                                 :timeout 10000
                                 :throw false})
                      (catch Exception e {:status 500 :body (.getMessage e)}))]
           (when (not= 201 (:status resp))
             (println "Failed to grant" relation "permission for" obj ":" (:body resp))
             (System/exit 1))))))))

;; ── SMART Backend Services (private_key_jwt) ──────────────────────────────────
;; The bulk_data_v200 auth group (and g10's Multi-Patient API group) runs the
;; real RFC 7523 client-credentials flow: Inferno signs a client assertion with
;; a private JWK and posts it to the token endpoint. Wiring:
;;   1. The issuer must be reachable under the same name from the host and
;;      from inside the Inferno container (host-gateway). For the dromon target
;;      docker/hydra.yml pins it to https://fhir.local:4443/;
;;      `ensure-hydra-issuer!` recreates a stale container so the running
;;      issuer matches. An external target's issuer is never touched.
;;   2. Register a Hydra client (token_endpoint_auth_method private_key_jwt) with
;;      the PUBLIC JWK so Hydra can verify the assertion. The US Core kit's
;;      group takes a key minted per run; the g10 kit locks its own committed
;;      test key set, so its public half is registered instead.
;;   3. Grant that client subject the Keto "system" read tuple so the RS256
;;      access token Hydra issues passes dromon's authorization.
;;   4. Hand Inferno the backend-services `smart_auth_info` (token_url, client_id,
;;      alg, kid, and the PRIVATE JWK set it signs the assertion with).

(def backend-alg "ES384")
(def ^:private desired-hydra-issuer (str default-oauth-base-url "/"))

(defn backend-mode? [] (:backend? (cfg)))

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

(defn backend-signing-kid
  "The kid of the signing key for `alg` in a JWK set."
  ([jwks] (backend-signing-kid jwks backend-alg))
  ([jwks alg]
   (:kid (some #(when (and (some #{"sign"} (:key_ops %)) (= alg (:alg %))) %) (:keys jwks)))))

(defn backend-public-jwk
  "The public JWK Hydra registers to verify the client assertion: the verify key
   for `alg` (or any key) reduced to its public components."
  ([jwks] (backend-public-jwk jwks backend-alg))
  ([jwks alg]
   (let [ks (filter #(= alg (:alg %)) (:keys jwks))
         k  (or (some #(when (some #{"verify"} (:key_ops %)) %) ks)
                (some #(when-not (:d %) %) ks)
                (first ks)
                (throw (ex-info (str "No " alg " key in the JWK set") {:alg alg})))]
     (case (:kty k)
       "EC"  {:kty "EC" :crv (:crv k) :x (:x k) :y (:y k) :kid (:kid k) :alg alg :use "sig"}
       "RSA" {:kty "RSA" :n (:n k) :e (:e k) :kid (:kid k) :alg alg :use "sig"}))))

(def g10-bulk-jwks-path
  "The g10 kit's committed backend-services test key set. Its Multi-Patient
   API group locks `jwks` to this file (G10_BULK_DATA_JWKS overrides it inside
   the container), so the matching public key is what Hydra must hold."
  (str setup/g10-kit-dir "/lib/onc_certification_g10_test_kit/bulk_data_jwks.json"))

(defn- g10-bulk-jwks []
  (let [f (io/file g10-bulk-jwks-path)]
    (when-not (.isFile f)
      (throw (ex-info (str g10-bulk-jwks-path " not found; run bb inferno-g10-setup first.")
                      {:path g10-bulk-jwks-path})))
    (json/parse-string (slurp f) true)))

(defn live-hydra-issuer []
  (try
    (-> (curl/get "http://127.0.0.1:4444/.well-known/openid-configuration"
                  {:timeout 3000 :throw false})
        :body (json/parse-string true) :issuer)
    (catch Exception _ nil)))

(defn ensure-hydra-issuer!
  "Ensures the running local Hydra advertises `desired-hydra-issuer`. Recreates
   the container from docker/hydra.yml (idempotently) if it does not, then
   waits for the token endpoint to come back. dromon target only."
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

(defn backend-client-payload
  "Hydra admin body for a SMART Backend Services client verified by the inline
   `public-jwk`."
  [public-jwk]
  {:client_name "inferno-backend-services"
   :grant_types ["client_credentials"]
   :response_types []
   :token_endpoint_auth_method "private_key_jwt"
   :token_endpoint_auth_signing_alg (:alg public-jwk)
   :jwks {:keys [public-jwk]}
   :scope "system/*.read"})

(defn create-backend-client
  "Registers a Hydra client for the SMART Backend Services flow: private_key_jwt
   auth with the inline PUBLIC JWK. Returns the client_id."
  ([public-jwk] (create-backend-client (cfg) public-jwk))
  ([{:keys [hydra-admin-url]} public-jwk]
   (println "Creating Hydra backend-services (private_key_jwt) client...")
   (let [resp (try
                (curl/post (str hydra-admin-url "/admin/clients")
                           {:headers {"Content-Type" "application/json"}
                            :body (json/generate-string (backend-client-payload public-jwk))
                            :timeout 10000
                            :throw false})
                (catch Exception e {:status 500 :body (.getMessage e)}))]
     (when (not= 201 (:status resp))
       (println "Failed to create backend-services client:" (:body resp))
       (System/exit 1))
     (:client_id (parse-json-body resp)))))

(defn backend-smart-auth-info
  "The `smart_auth_info` JSON string Inferno consumes for a backend-services run.
   `jwks-str` is the PRIVATE JWK set (as a string) Inferno signs the assertion
   with; `token_url` is both the assertion `aud` and the POST target."
  ([client-id jwks-str kid] (backend-smart-auth-info (cfg) client-id jwks-str kid))
  ([{:keys [token-url]} client-id jwks-str kid]
   (json/generate-string
    {:auth_type "backend_services"
     :use_discovery "false"
     :token_url token-url
     :client_id client-id
     :requested_scopes "system/*.read"
     :encryption_algorithm backend-alg
     :kid kid
     :jwks jwks-str})))

(defn- backend-auth!
  "Registers the backend-services client for this run and returns its
   smart_auth_info JSON string."
  [config]
  (let [jwks (if (= :g10 (:kit config)) (g10-bulk-jwks) (generate-backend-jwks))
        bcid (create-backend-client config (backend-public-jwk jwks))]
    (println "Backend-services client ID:" bcid)
    (grant-keto-permissions config bcid)
    (backend-smart-auth-info config bcid (json/generate-string jwks) (backend-signing-kid jwks))))

;; Store warmup used to happen here via an authenticated `GET /Patient?_count=1`
;; to force per-tenant cold-start (XTDB node start, Datomic peer connect, JIT of
;; the read path). That is now handled in-process by `test-server/seeder`, which
;; calls `(db/create-tenant store "default" {:if-exists :ignore})` and
;; `(db/warmup-tenant store "default")` before Jetty starts accepting traffic.
;; By the time `wait-for-server` sees a 200 on `/metadata`, the default tenant
;; is already warm, so the runner has nothing left to do here.

(defn insert-patient
  ([token] (insert-patient (cfg) token))
  ([{:keys [fhir-url]} token]
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
                (curl/put (str fhir-url "/Patient/123")
                          {:headers {"Authorization" (str "Bearer " token)
                                     "Content-Type" "application/json"}
                           :body (json/generate-string patient-body)
                           :timeout 10000
                           :throw false
                           :insecure? true})
                (catch Exception e {:status 500 :body (.getMessage e)}))]
     (when (not (#{200 201} (:status resp)))
       (println "Failed to insert patient. Status:" (:status resp) "Body:" (:body resp))
       (System/exit 1)))))

(defn insert-test-data
  ([token] (insert-test-data (cfg) token))
  ([{:keys [fhir-url]} token]
   (println "Inserting test data bundle...")
   (let [resp (try
                (let [result (shell {:out :string :err :string :continue true}
                                    "curl" "-s" "-k" "--max-time" "30"
                                    "-X" "POST"
                                    "-H" (str "Authorization: Bearer " token)
                                    "-H" "Content-Type: application/json"
                                    "-d" "@bb/resources/inferno-test-bundle.json"
                                    "-w" "\n%{http_code}"
                                    fhir-url)
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
         (System/exit 1))))))

;; ── Suite inputs ──────────────────────────────────────────────────────────────

(def ^:private g10-discovery-auth-info
  (json/generate-string {:auth_type "symmetric" :use_discovery "true"}))

(defn input-tokens
  "The `key:value` tokens passed after --inputs. INFERNO_INPUTS fully replaces
   the defaults, with {{token}}, {{cred_json}} and {{backend_auth}} expanded."
  [{:keys [kit backend? fhir-url patient-ids group-id inputs-override]} token backend-auth]
  (let [cred-json (json/generate-string {:access_token (or token "")})
        subst (fn [s] (-> s
                          (str/replace "{{cred_json}}" cred-json)
                          (str/replace "{{backend_auth}}" (or backend-auth ""))
                          (str/replace "{{token}}" (or token ""))))]
    (cond
      inputs-override
      (mapv subst (remove str/blank? (str/split inputs-override #"\s+")))

      ;; The (g)(10) Multi-Patient API group: same backend-services flow, its
      ;; own input names.
      (and (= kit :g10) backend?)
      [(str "url:" fhir-url)
       (str "bulk_server_url:" fhir-url)
       (str "bulk_smart_auth_info:" backend-auth)
       (str "group_id:" group-id)
       (str "bulk_patient_ids_in_group:" patient-ids)
       "bulk_timeout:180"
       "since_timestamp:2020-01-01T00:00:00Z"]

      ;; The g10 discovery groups read only the FHIR base, but declare the
      ;; launch credentials as required inputs (discovery writes the
      ;; endpoints it finds into them). Launches and their real credentials
      ;; come from a preset or the web UI.
      (= kit :g10)
      [(str "url:" fhir-url)
       (str "standalone_smart_auth_info:" g10-discovery-auth-info)
       (str "ehr_smart_auth_info:" g10-discovery-auth-info)]

      ;; Backend-services default input set for the bulk_data suites: the FHIR
      ;; base is `bulk_server_url` and auth is the real private_key_jwt flow
      ;; driven from `smart_auth_info`.
      backend?
      [(str "bulk_server_url:" fhir-url)
       (str "smart_auth_info:" backend-auth)
       (str "group_id:" group-id)
       "bulk_timeout:180"
       "since_timestamp:2020-01-01T00:00:00Z"]

      :else
      [(str "url:" fhir-url)
       (str "patient_ids:" patient-ids)
       (str "smart_auth_info:" cred-json)])))

(defn execute-command
  "The `inferno execute` command vector run inside the kit's inferno container."
  [{:keys [suite suite-options groups preset-file] :as config} token backend-auth]
  (-> ["docker" "compose" "exec" "-T" "inferno" "bundle" "exec" "inferno" "execute"
       "--suite" suite]
      (cond-> (seq suite-options)
        (into (cons "--suite-options" (map (fn [[k v]] (str k ":" v)) (sort suite-options)))))
      (cond-> (seq groups) (into (cons "--groups" groups)))
      (cond-> preset-file (into ["--preset-file" preset-file]))
      (into (cons "--inputs" (input-tokens config token backend-auth)))
      (into ["--outputter" "json"])))

(def ^:private secret-input-re
  #"(?i)^([A-Za-z0-9_]*(?:auth|token|secret|jwks|cred)[A-Za-z0-9_]*):.*$")

(defn redact-command
  "`cmd` safe to print: input values that carry credentials (access tokens,
   client secrets, private JWKs) are replaced."
  [cmd]
  (mapv #(str/replace % secret-input-re "$1:<redacted>") cmd))

(defn- needs-runner-token?
  "Whether the run needs a bearer token minted by the runner itself: the
   dromon target seeds data with it, and the US Core inputs carry it. The g10
   groups obtain their own tokens, and an INFERNO_INPUTS that never references
   one does not need it either."
  [{:keys [target kit backend? inputs-override]}]
  (or (= target :dromon)
      (if inputs-override
        (boolean (re-find #"\{\{(token|cred_json)\}\}" inputs-override))
        (and (= kit :us-core) (not backend?)))))

;; ── Test runner ───────────────────────────────────────────────────────────────

(defn- print-report [report]
  (let [passes (count (filter #(= "pass" (:result %)) report))
        fails  (count (filter #(= "fail" (:result %)) report))
        errors (count (filter #(= "error" (:result %)) report))
        green  "\033[32m"
        red    "\033[31m"
        yellow "\033[33m"
        reset  "\033[0m"
        color  (fn [r] (case r "pass" green "fail" red "error" yellow reset))
        badge  (fn [r] (case r "pass" "PASS" "fail" "FAIL" "error" "ERR " "skip" "SKIP" "omit" "OMIT" "SKIP"))]
    (println "\n╔══════════════════════════════════════════════════════════════╗")
    (println "║                     Inferno Test Results                     ║")
    (println "╠══════════════════════════════════════════════════════════════╣")
    (doseq [t report]
      (let [r   (:result t "skip")
            id  (or (:test_id t) (:test_group_id t) (:test_suite_id t) "unknown")
            msg (str/replace (or (:result_message t) "") #"\s+" " ")]
        (println (format "%s[%s]%s %-45s %s"
                         (color r) (badge r) reset
                         (if (> (count id) 45) (str "..." (subs id (- (count id) 42))) id)
                         (if (> (count msg) 60) (str (subs msg 0 57) "...") msg)))))
    (println "╠══════════════════════════════════════════════════════════════╣")
    (println (format "║ %s%d passed%s  %s%d failed%s  %s%d errors%s"
                     green passes reset
                     red   fails  reset
                     yellow errors reset))
    (println "╚══════════════════════════════════════════════════════════════╝\n")))

(defn run-inferno-tests
  "Runs `inferno execute` in the kit container and writes the report. Returns
   the parsed result list, or nil when Inferno printed no JSON report (it
   refused the run, e.g. for a missing input)."
  ([token backend-auth] (run-inferno-tests (cfg) token backend-auth))
  ([{:keys [suite kit-dir] :as config} token backend-auth]
   (println "Running Inferno tests for suite" suite "in" kit-dir "...")
   (.mkdirs (io/file "target"))
   (let [cmd (execute-command config token backend-auth)]
     (println "Executing:" (str/join " " (redact-command cmd)))
     (try
       (let [proc   (process cmd {:dir kit-dir :out :string :err :string})
             ;; 10-minute hard timeout
             result (deref (future @proc) 600000 ::timeout)]
         (if (= ::timeout result)
           (do
             (println "ERROR: Inferno test suite timed out after 10 minutes.")
             (println "Check worker logs: docker compose -f" (str kit-dir "/docker-compose.yml") "logs worker")
             (p/destroy-tree proc)
             (System/exit 1))
           (do
             (when (seq (:err result))
               (println "Inferno stderr:" (:err result)))
             (let [report-file (str "target/inferno-report-" suite ".json")]
               (spit "target/inferno-report.json" (:out result))
               (spit report-file (:out result))
               (println "Inferno run completed. Report written to" report-file
                        "(and target/inferno-report.json)"))
             (let [report (try (json/parse-string (:out result) true) (catch Exception _ nil))]
               (if (sequential? report)
                 (do (print-report report) report)
                 (do
                   (println "WARN: Could not parse JSON report. Raw output:")
                   (println (:out result))
                   nil))))))
       (catch Exception e
         (println "Error running inferno tests:" (.getMessage e))
         (when-let [edata (ex-data e)]
           (println "Stdout:" (:out edata))
           (println "Stderr:" (:err edata)))
         (System/exit 1))))))

;; ── Entry points ──────────────────────────────────────────────────────────────

(defn kill-stale-inferno-processes!
  "Stops `inferno execute` runs left inside the kit's own inferno container by
   an interrupted run. Scoped to the container; nothing on the host is
   touched."
  ([] (kill-stale-inferno-processes! (cfg)))
  ([{:keys [kit-dir]}]
   (println "Killing any stale 'inferno execute' processes in the container...")
   (try
     (shell {:dir kit-dir :out :string :err :string}
            "sh" "-c" "docker compose exec -T inferno pkill -f 'inferno execute' 2>/dev/null || true")
     (catch Exception _
       ;; Container may not be running yet; that is fine.
       nil))))

(defn check!
  "Smoke-test: verify the kit's containers are up and the FHIR server answers
   GET <INFERNO_FHIR_URL>/metadata. Does NOT run the suite."
  ([] (check! (cfg)))
  ([{:keys [kit-dir fhir-url target] :as config}]
   (println "=== Inferno Environment Check ===")

   (println "\n[1/3] Checking Inferno containers in" kit-dir "...")
   (let [result (try
                  (shell {:dir kit-dir :out :string :err :string}
                         "docker" "compose" "ps" "--services" "--filter" "status=running")
                  (catch Exception e {:exit 1 :out "" :err (.getMessage e)}))]
     (if (zero? (:exit result 0))
       (println "  ok: containers responding to docker compose ps")
       (do (println "  FAIL: could not query container status:" (:err result))
           (System/exit 1))))

   (println "\n[2/3] Checking FHIR server metadata endpoint...")
   (let [status (metadata-status fhir-url)]
     (if (= 200 status)
       (println "  ok: 200 from" (str fhir-url "/metadata"))
       (do (println "  FAIL: no 200 from" (str fhir-url "/metadata") "(status:" status ")")
           (when (= :dromon target)
             (println "    Start the server with: clojure -X:test test-server.core/-main :port 3000 :ssl-port 3001"))
           (System/exit 1))))

   (println "\n[3/3] Checking HL7 validator service...")
   (if (validator-ready? config)
     (println "  ok: HL7 validator responded with 200")
     (println "  warn: HL7 validator not reachable (may still be warming up)"))

   (println "\n=== Check complete. Environment looks good! ===")))

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

(def ^:private test-server-ports [3000 3001])

(defn- listening-pids
  "Descriptions of the listeners on `port` (ss output lines), or nil."
  [port]
  (let [out (:out (shell {:out :string :err :string :continue true}
                         "ss" "-ltnpH" (str "sport = :" port)))]
    (not-empty (str/trim out))))

(defn- ensure-test-server-ports-free!
  "Refuses to start when the test-server ports are taken. Whatever holds them
   may belong to another session, so it is reported, never killed."
  []
  (doseq [port test-server-ports]
    (when-let [holder (listening-pids port)]
      (println "ERROR: port" port "is already in use; stop that process first:")
      (println " " holder)
      (System/exit 1))))

(defn- start-test-server!
  "Starts the dromon test-server and returns its process handle."
  [config]
  (ensure-test-server-ports-free!)
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
                                (when (:backend? config)
                                  {"OAUTH_BASE_URL" (:oauth-base-url config)})
                                otel-env)})))

(defn- runner-token!
  "The bearer token the run uses: INFERNO_ACCESS_TOKEN, else a
   client_credentials token for INFERNO_CLIENT_ID/SECRET, else for a client
   registered through the Hydra admin API (and granted Keto tuples when a
   Keto write url is configured)."
  [{:keys [access-token client-id client-secret] :as config}]
  (or access-token
      (let [[cid secret registered?]
            (if client-id
              [client-id client-secret false]
              (let [{:keys [client_id client_secret]} (create-client config)]
                [client_id client_secret true]))]
        (println "Client ID:" cid)
        (when registered? (grant-keto-permissions config cid))
        (get-token config cid secret))))

(defn run-suite!
  "Runs the configured suite. Exits the process: 0 when Inferno produced a
   report (whatever the test results; read it), 1 on a setup failure or when
   Inferno refused the run."
  [config]
  (let [{:keys [target backend? kit suite]} config
        dromon? (= target :dromon)]
    (println "Starting test run:" (name kit) "kit, suite" suite "against" (name target)
             "target" (:fhir-url config))

    ;; 0. Stop interrupted `inferno execute` runs in the kit container.
    (kill-stale-inferno-processes! config)

    ;; 1. Ensure the kit's Docker stack is up, and its validator warm.
    (ensure-inferno-containers! config)
    (wait-for-validator config 120)

    ;; 2. The server under test: booted here for dromon, already running for
    ;;    an external target.
    (let [server (when dromon? (start-test-server! config))]
      ;; A shutdown hook rather than `finally`: the failure paths below end in
      ;; System/exit, which skips `finally` but runs hooks.
      (when server
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. (fn []
                                     (println "Stopping the FHIR server this run started...")
                                     (p/destroy-tree server)))))
      (wait-for-server config (if dromon? 30 10))

      ;; Backend-services auth against the local dromon stack needs Hydra's
      ;; issuer to match the container-reachable TLS token endpoint, plus the
      ;; nginx TLS terminator that serves it. Never for an external target:
      ;; its issuer belongs to its own deployment.
      (when (and dromon? backend?)
        (ensure-hydra-issuer!)
        (env/ensure-hydra-tls-terminator!))

      (let [token (when (needs-runner-token? config) (runner-token! config))]
        (when dromon?
          (insert-patient config token)
          (insert-test-data config token))
        (let [backend-auth (when backend? (backend-auth! config))]
          (System/exit (if (run-inferno-tests config token backend-auth) 0 1)))))))

(defn run-check!
  "Entry point of `bb inferno-test`: [[run-suite!]] with the configuration from the
   process environment."
  []
  (run-suite! (cfg)))

(defn run-with-defaults!
  "[[run-suite!]] with `defaults` (an env map) under the process environment, which
   wins. `bb inferno-g10` uses it to default to the g10 kit, external target."
  [defaults]
  (run-suite! (runner-config (merge defaults (System/getenv)))))

(when (= *file* (System/getProperty "babashka.file"))
  (run-check!))
