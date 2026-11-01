(ns server.inferno-g10-clients
  "`bb inferno-g10-clients`: registers the OAuth clients the ONC (g)(10) suite
   drives, through the Hydra admin API (INFERNO_HYDRA_ADMIN_URL), then renders
   the suite preset from inferno-custom/g10-presets.

   Clients (fixed ids under INFERNO_G10_CLIENT_PREFIX, default `inferno-g10`,
   so a rerun updates them in place instead of piling up new ones):

   | id suffix     | auth                     | used by                                  |
   |---------------|--------------------------|------------------------------------------|
   | `-public`     | none (PKCE S256)         | public standalone launch (9.16)          |
   | `-symmetric`  | client_secret_basic      | standalone, limited, v1, granular groups |
   | `-ehr`        | client_secret_basic      | EHR practitioner and EHR patient launch  |
   | `-asymmetric` | private_key_jwt (ES384)  | asymmetric launch (9.21)                 |
   | `-backend`    | private_key_jwt (ES384)  | Multi-Patient API / bulk (8)             |

   Every client has redirect_uris [<inferno base>/custom/smart/redirect] and
   subject_type public (an introspection `sub` must equal the id_token `sub`).
   The EHR client carries metadata.smart {ehr_launch true, launch_uri
   <inferno base>/custom/smart/launch}, which a host's EHR launch minting
   can check. Hydra matches requested scopes against the registered string
   literally (wildcards do not expand to resource types), so every scope the
   suite requests is listed: v1 and v2 forms of each resource type for both
   patient/ and user/, the wildcard forms, and the US Core granular category
   scopes.

   The asymmetric client is verified with the public half of the SMART test
   kit's own key set, fetched from the running Inferno (start the g10 kit
   first); the backend client with the g10 kit's committed bulk key set,
   which its Multi-Patient API group locks.

   Client secrets are generated here, written only to the gitignored
   [[credentials-path]] (mode 600) and the rendered preset inside the
   gitignored kit clone, and never printed."
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [server.inferno-runner :as runner]
            [server.inferno-setup :as setup]))

(def resource-types
  "The resource types the (g)(10) kit requests access to (its
   scope_constants.rb, v8.1.0)."
  ["Medication" "AllergyIntolerance" "CarePlan" "CareTeam" "Condition" "Coverage" "Device"
   "DiagnosticReport" "DocumentReference" "Encounter" "Goal" "Immunization" "Location"
   "MedicationDispense" "MedicationRequest" "Observation" "Organization" "Patient"
   "Practitioner" "PractitionerRole" "Procedure" "Provenance" "QuestionnaireResponse"
   "RelatedPerson" "ServiceRequest" "Specimen"])

(def granular-scopes
  "The US Core category scopes the fine-grained scope groups request."
  ["patient/Condition.rs?category=http://terminology.hl7.org/CodeSystem/condition-category|encounter-diagnosis"
   "patient/Condition.rs?category=http://terminology.hl7.org/CodeSystem/condition-category|problem-list-item"
   "patient/Condition.rs?category=http://hl7.org/fhir/us/core/CodeSystem/condition-category|health-concern"
   "patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|laboratory"
   "patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|social-history"
   "patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|vital-signs"
   "patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|survey"
   "patient/Observation.rs?category=http://hl7.org/fhir/us/core/CodeSystem/us-core-category|sdoh"])

(def launch-scopes ["openid" "fhirUser" "profile" "launch" "launch/patient" "offline_access"])

(defn smart-scopes
  "Every scope the interactive (g)(10) groups request, in a stable order."
  []
  (vec (distinct
        (concat launch-scopes
                ["patient/*.read" "patient/*.rs" "user/*.read" "user/*.rs"]
                (for [ctx ["patient" "user"]
                      access ["read" "rs"]
                      t resource-types]
                  (str ctx "/" t "." access))
                granular-scopes))))

(def backend-scopes ["system/*.read" "system/*.rs"])

(def signing-alg runner/backend-alg)

(defn inferno-urls
  "The redirect and launch URIs the g10 kit derives from its base url."
  [inferno-base]
  {:redirect-uri (str inferno-base "/custom/smart/redirect")
   :launch-uri   (str inferno-base "/custom/smart/launch")})

(defn- user-facing-client [id name redirect-uri]
  {:client_id      id
   :client_name    name
   :grant_types    ["authorization_code" "refresh_token"]
   :response_types ["code"]
   :redirect_uris  [redirect-uri]
   :scope          (str/join " " (smart-scopes))
   :subject_type   "public"})

(defn client-payloads
  "Hydra admin bodies for every client, keyed by role. `secrets` holds the
   generated secrets of the symmetric clients; `asymmetric-jwk` and
   `backend-jwk` are the public verification keys."
  [{:keys [prefix inferno-base secrets asymmetric-jwk backend-jwk]}]
  (let [{:keys [redirect-uri launch-uri]} (inferno-urls inferno-base)
        id #(str prefix "-" (name %))]
    {:public     (assoc (user-facing-client (id :public) "Inferno (g)(10) public" redirect-uri)
                        :token_endpoint_auth_method "none")
     :symmetric  (assoc (user-facing-client (id :symmetric) "Inferno (g)(10) confidential symmetric" redirect-uri)
                        :token_endpoint_auth_method "client_secret_basic"
                        :client_secret (:symmetric secrets))
     :ehr        (assoc (user-facing-client (id :ehr) "Inferno (g)(10) EHR launch" redirect-uri)
                        :token_endpoint_auth_method "client_secret_basic"
                        :client_secret (:ehr secrets)
                        :metadata {:smart {:ehr_launch true :launch_uri launch-uri}})
     :asymmetric (assoc (user-facing-client (id :asymmetric) "Inferno (g)(10) confidential asymmetric" redirect-uri)
                        :token_endpoint_auth_method "private_key_jwt"
                        :token_endpoint_auth_signing_alg signing-alg
                        :jwks {:keys [asymmetric-jwk]})
     :backend    {:client_id                       (id :backend)
                  :client_name                     "Inferno (g)(10) backend services"
                  :grant_types                     ["client_credentials"]
                  :response_types                  []
                  :token_endpoint_auth_method      "private_key_jwt"
                  :token_endpoint_auth_signing_alg signing-alg
                  :jwks                            {:keys [backend-jwk]}
                  :scope                           (str/join " " backend-scopes)
                  :subject_type                    "public"}}))

(defn- random-secret []
  (let [bs (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) bs)
    (.encodeToString (.withoutPadding (java.util.Base64/getUrlEncoder)) bs)))

(defn- hydra-call [method url body]
  (try
    (curl/request {:method  method
                   :url     url
                   :headers {"Content-Type" "application/json" "Accept" "application/json"}
                   :body    (some-> body json/generate-string)
                   :timeout 10000
                   :throw   false})
    (catch Exception e
      (throw (ex-info (str "Hydra admin API unreachable at " url ": " (.getMessage e)) {:url url} e)))))

(defn- upsert-client!
  "Creates the client, or replaces it when its id already exists. Returns the
   stored client_id. Hydra's error body is reported, the request body (which
   may hold a secret) never is."
  [hydra-admin-url {:keys [client_id] :as payload}]
  (let [url    (str hydra-admin-url "/admin/clients/" (java.net.URLEncoder/encode ^String client_id "UTF-8"))
        exists (= 200 (:status (hydra-call :get url nil)))
        resp   (if exists
                 (hydra-call :put url payload)
                 (hydra-call :post (str hydra-admin-url "/admin/clients") payload))]
    (when-not (#{200 201} (:status resp))
      (throw (ex-info (str "Hydra refused client " client_id ": HTTP " (:status resp) " " (:body resp))
                      {:client-id client_id :status (:status resp)})))
    (println (if exists "  updated" "  created") client_id)
    client_id))

(defn- fetch-asymmetric-jwk
  "The public ES384 key of the SMART test kit's client key set, as served by
   the running g10 Inferno."
  [inferno-base]
  (let [url  (str inferno-base "/custom/smart_stu2/.well-known/jwks.json")
        resp (try (curl/get url {:timeout 5000 :throw false})
                  (catch Exception e {:status nil :body (.getMessage e)}))]
    (when-not (= 200 (:status resp))
      (throw (ex-info (str "Could not read the SMART kit JWKS from " url
                           " (start the g10 kit: cd " setup/g10-kit-dir " && docker compose up -d)")
                      {:url url :status (:status resp)})))
    (runner/backend-public-jwk (json/parse-string (:body resp) true) signing-alg)))

(def credentials-path
  "Gitignored with the kit clone."
  (str setup/g10-kit-dir "/clients.local.json"))

(def preset-template-path "inferno-custom/g10-presets/external-local.json.template")
(def rendered-preset-path (str setup/g10-kit-dir "/config/presets/external-local.json"))

(defn render-template
  "Replaces each ${NAME} in `template` with the JSON-string-escaped value of
   `values` (a map of NAME to string). A placeholder without a value throws:
   a preset with a literal placeholder would send it to the server."
  [template values]
  (str/replace template #"\$\{([A-Z0-9_]+)\}"
               (fn [[_ k]]
                 (let [v (get values k)]
                   (when (nil? v)
                     (throw (ex-info (str "No value for preset placeholder ${" k "}") {:placeholder k})))
                   (let [quoted (json/generate-string (str v))]
                     (subs quoted 1 (dec (count quoted))))))))

(defn preset-values
  [{:keys [fhir-url token-url group-id patient-ids]} ids secrets backend-kid]
  {"FHIR_URL"                fhir-url
   "TOKEN_URL"               token-url
   "GROUP_ID"                group-id
   "PATIENT_IDS"             patient-ids
   "PUBLIC_CLIENT_ID"        (:public ids)
   "SYMMETRIC_CLIENT_ID"     (:symmetric ids)
   "SYMMETRIC_CLIENT_SECRET" (:symmetric secrets)
   "EHR_CLIENT_ID"           (:ehr ids)
   "EHR_CLIENT_SECRET"       (:ehr secrets)
   "ASYMMETRIC_CLIENT_ID"    (:asymmetric ids)
   "BACKEND_CLIENT_ID"       (:backend ids)
   "BACKEND_ALG"             signing-alg
   "BACKEND_KID"             backend-kid})

(defn- write-private! [path content]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f "")
    (.setReadable f false false) (.setReadable f true true)
    (.setWritable f false false) (.setWritable f true true)
    (spit f content)))

(defn -main [env]
  (let [config       (runner/runner-config (merge {"INFERNO_KIT" "g10" "INFERNO_TARGET" "external"} env))
        prefix       (or (not-empty (str/trim (str (get env "INFERNO_G10_CLIENT_PREFIX" "")))) "inferno-g10")
        inferno-base (setup/g10-inferno-base-url env)
        bulk-jwks    (json/parse-string (slurp (io/file runner/g10-bulk-jwks-path)) true)
        secrets      {:symmetric (random-secret) :ehr (random-secret)}
        payloads     (client-payloads {:prefix         prefix
                                       :inferno-base   inferno-base
                                       :secrets        secrets
                                       :asymmetric-jwk (fetch-asymmetric-jwk inferno-base)
                                       :backend-jwk    (runner/backend-public-jwk bulk-jwks signing-alg)})
        _            (println "Registering (g)(10) clients at" (:hydra-admin-url config) "...")
        ids          (update-vals payloads #(upsert-client! (:hydra-admin-url config) %))
        backend-kid  (runner/backend-signing-kid bulk-jwks signing-alg)]
    (runner/grant-keto-permissions config (:backend ids))
    (write-private! credentials-path
                    (json/generate-string {:clients (into (sorted-map)
                                                          (map (fn [[role id]]
                                                                 [role (cond-> {:client_id id}
                                                                         (get secrets role) (assoc :client_secret (get secrets role)))]))
                                                          ids)
                                           :redirect_uri (:redirect-uri (inferno-urls inferno-base))
                                           :launch_uri   (:launch-uri (inferno-urls inferno-base))}
                                          {:pretty true}))
    (println "Client credentials written to" credentials-path)
    (write-private! rendered-preset-path
                    (render-template (slurp preset-template-path)
                                     (preset-values config ids secrets backend-kid)))
    (println "Preset rendered to" rendered-preset-path)
    (println "Rebuild the kit image so the web UI lists it: bb inferno-g10-setup")
    (println "Client ids:" (str/join " " (vals ids)))))
