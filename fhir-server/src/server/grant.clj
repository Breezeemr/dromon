(ns server.grant
  "SMART on FHIR patient-set grants backed by Ory Hydra and Ory Keto.

   A grant gives an OAuth2 subject (a Hydra client id or end-user sub) access
   to a set of patients. It is recorded as Keto relation tuples in the `fhir`
   namespace:

   - {:object \"Patient/<id>\" :relation \"launch\"} -- the subject may obtain
     a token whose SMART launch context is that patient. Never consulted by
     the data-access middleware, so it grants no data access by itself.
   - {:object \"Patient/<id>\" :relation \"read\"|...} -- instance-level access
     to the Patient resource itself (checked by server.keto).
   - {:object \"Patient/<id>\" :relation \"request-change\"} -- instance-level
     authorization for the Patient/$request-demographic-change operation.
   - {:object <MemberType> :relation \"read\"|...} -- type-level access to the
     Patient-compartment member types. Safe in combination with a patient/
     scoped token because server.compartment confines every query to the
     launch patient's compartment.

   Hydra integration: `token-hook` is an endpoint for Hydra's
   OAUTH2_TOKEN_HOOK_URL webhook. During token issuance it resolves the launch
   patient for the requesting subject -- an explicit `patient` form param on
   the token request, or the subject's single granted patient -- verifies the
   `launch` tuple in Keto, and injects the `patient` claim into the access
   token. Patient-scoped token requests that cannot be resolved to a granted
   patient are rejected, so a token can never carry a patient context that
   Keto does not back."
  (:require [clojure.string :as str]
            [hato.client :as hc]
            [jsonista.core :as json]
            [server.compartment :as compartment]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; Keto endpoints
;; ---------------------------------------------------------------------------

(defn- keto-read-url []
  (or (System/getenv "KETO_URL") "http://localhost:4466"))

(defn- keto-admin-url []
  (or (System/getenv "KETO_ADMIN_URL") "http://localhost:4467"))

(def ^:const launch-relation
  "Relation recording that a subject may launch with a patient context."
  "launch")

;; "request-change" authorizes Patient/$request-demographic-change. It is only
;; consulted on the instance tuple Patient/<id>; the type-level tuples it also
;; mints on member types are inert.
(def ^:private default-relations ["read" "request-change"])

(defn- put-tuple! [tuple]
  (let [resp (hc/put (str (keto-admin-url) "/admin/relation-tuples")
                     {:headers {"content-type" "application/json"}
                      :body (json/write-value-as-string tuple)
                      :throw-exceptions false})]
    (when-not (contains? #{200 201} (:status resp))
      (throw (ex-info "Keto relation-tuple write failed"
                      {:tuple tuple :status (:status resp) :body (:body resp)})))))

(defn- delete-tuples!
  "Deletes all tuples matching the query params (namespace/object/relation/
   subject_id subset)."
  [query]
  (hc/delete (str (keto-admin-url) "/admin/relation-tuples")
             {:query-params query :throw-exceptions false}))

(defn- list-tuples [query]
  (let [resp (hc/get (str (keto-read-url) "/relation-tuples")
                     {:query-params (assoc query "page_size" 500)
                      :as :json
                      :throw-exceptions false})]
    (when (= 200 (:status resp))
      (get-in resp [:body :relation_tuples]))))

(defn- launch-authorized? [subject patient-id]
  (let [resp (hc/get (str (keto-read-url) "/relation-tuples/check")
                     {:query-params {"namespace" "fhir"
                                     "object" (str "Patient/" patient-id)
                                     "relation" launch-relation
                                     "subject_id" subject}
                      :as :json
                      :throw-exceptions false})]
    (boolean (and (= 200 (:status resp)) (get-in resp [:body :allowed])))))

;; ---------------------------------------------------------------------------
;; Grant model
;; ---------------------------------------------------------------------------

(defn patient-member-types
  "Resource types belonging to the Patient compartment (excluding Patient)."
  []
  (->> (get compartment/compartment-definitions "Patient")
       keys
       sort
       vec))

(defn grant-tuples
  "The full set of Keto tuples that a patient-set grant comprises."
  [subject patient-ids relations]
  (-> []
      (into (for [pid patient-ids]
              {:namespace "fhir" :object (str "Patient/" pid)
               :relation launch-relation :subject_id subject}))
      (into (for [pid patient-ids, rel relations]
              {:namespace "fhir" :object (str "Patient/" pid)
               :relation rel :subject_id subject}))
      (into (for [t (patient-member-types), rel relations]
              {:namespace "fhir" :object t :relation rel :subject_id subject}))))

(defn grant-patient-set!
  "Grants `subject` access to the given patient ids with the given relations
   (default read-only). Idempotent: Keto tuple writes are upserts."
  [subject patient-ids & {:keys [relations] :or {relations default-relations}}]
  (run! put-tuple! (grant-tuples subject patient-ids relations))
  (t/event! :grant/patient-set-granted
            {:data {:subject subject :patients (vec patient-ids)
                    :relations (vec relations)}})
  {:subject subject :patients (vec patient-ids) :relations (vec relations)})

(defn granted-patients
  "Patient ids the subject holds a launch tuple for."
  [subject]
  (->> (list-tuples {"namespace" "fhir"
                     "relation" launch-relation
                     "subject_id" subject})
       (keep (fn [{:keys [object]}]
               (second (re-matches #"Patient/(.+)" (str object)))))
       sort
       vec))

(defn revoke-patient-set!
  "Removes the subject's tuples for the given patient ids (all relations).
   Type-level compartment tuples are removed once the subject's last patient
   grant is gone."
  [subject patient-ids]
  (doseq [pid patient-ids]
    (delete-tuples! {"namespace" "fhir"
                     "object" (str "Patient/" pid)
                     "subject_id" subject}))
  (when (empty? (granted-patients subject))
    (doseq [t (patient-member-types)]
      (delete-tuples! {"namespace" "fhir" "object" t "subject_id" subject})))
  (t/event! :grant/patient-set-revoked
            {:data {:subject subject :patients (vec patient-ids)}})
  {:subject subject :revoked (vec patient-ids)})

;; ---------------------------------------------------------------------------
;; HTTP handlers -- grant administration
;;
;; Routed under /auth/grants. The URL has no FHIR resource-type segment, so
;; server.keto derives the object \"system\": callers need a
;; {namespace fhir, object system, relation read|write} tuple, which makes
;; grant administration an explicitly privileged operation.
;; ---------------------------------------------------------------------------

(defn- bad-request [diagnostics]
  {:status 400
   :body {:resourceType "OperationOutcome"
          :issue [{:severity "error" :code "invalid" :diagnostics diagnostics}]}})

(defn create-grant
  "POST /auth/grants {subject, patients [...], relations? [...]}"
  [req]
  (let [{:keys [subject patients relations]} (:body-params req)]
    (cond
      (str/blank? (str subject)) (bad-request "subject is required")
      (empty? patients) (bad-request "patients must be a non-empty list")
      :else {:status 201
             :body (grant-patient-set! subject patients
                                       :relations (or (not-empty relations)
                                                      default-relations))})))

(defn read-grant
  "GET /auth/grants?subject=<id>"
  [req]
  (let [subject (get-in req [:query-params "subject"])]
    (if (str/blank? (str subject))
      (bad-request "subject query parameter is required")
      {:status 200
       :body {:subject subject :patients (granted-patients subject)}})))

(defn delete-grant
  "DELETE /auth/grants {subject, patients [...]}"
  [req]
  (let [{:keys [subject patients]} (:body-params req)]
    (cond
      (str/blank? (str subject)) (bad-request "subject is required")
      (empty? patients) (bad-request "patients must be a non-empty list")
      :else {:status 200 :body (revoke-patient-set! subject patients)})))

(defn my-patients
  "GET /auth/my-patients -- the authenticated subject's granted patient set.
   The route is :public? (Keto's system gate must not apply to end users),
   so authentication is enforced here."
  [req]
  (if-let [subject (get-in req [:identity :sub])]
    {:status 200
     :body {:subject subject :patients (granted-patients subject)}}
    {:status 401
     :body {:resourceType "OperationOutcome"
            :issue [{:severity "error" :code "login"
                     :diagnostics "Valid bearer token required"}]}}))

;; ---------------------------------------------------------------------------
;; HTTP handler -- Hydra token hook
;; ---------------------------------------------------------------------------

(def ^:const launch-scope-prefix
  "Per-patient launch-context selection scope. Clients are registered with the
   wildcard scope `launch/patient.*` (Hydra's default wildcard scope strategy)
   and request `launch/patient.<id>` to select a launch patient per token.
   Hydra forwards granted scopes to the token hook; plain token-request form
   params are NOT forwarded for client_credentials, so scope is the only
   client-controlled per-token channel."
  "launch/patient.")

(defn- requested-patient
  "The patient id explicitly requested for the token's launch context: a
   `launch/patient.<id>` granted scope, or a `patient` form param when the
   flow forwards it in :request :payload."
  [payload]
  (or (some (fn [s]
              (let [s (str s)]
                (when (and (str/starts-with? s launch-scope-prefix)
                           (> (count s) (count launch-scope-prefix)))
                  (subs s (count launch-scope-prefix)))))
            (get-in payload [:request :granted_scopes]))
      (let [p (get-in payload [:request :payload :patient])]
        (cond
          (string? p) p
          (sequential? p) (first p)
          :else nil))))

(defn- patient-scoped?
  "True when the issued token will carry at least one patient/ scope."
  [payload]
  (boolean (some #(str/starts-with? (str %) "patient/")
                 (get-in payload [:request :granted_scopes]))))

(defn end-user-subject
  "The authenticated end user's subject, or nil for grant-based (machine)
   token issuance. authorization_code/refresh_token hook payloads carry it
   only inside the id_token session (observed on Hydra v2.2.0), where
   session.subject is empty."
  [payload]
  (or (let [s (get-in payload [:session :subject])]
        (when-not (str/blank? (str s)) s))
      (let [s (get-in payload [:session :id_token :subject])]
        (when-not (str/blank? (str s)) s))))

(defn resolve-launch-patient
  "Decides the launch patient for a token issuance, from the hook payload and
   the Keto grant state. Returns
   {:patient <id>} to inject, {} to issue without a patient context, or
   {:deny <diagnostics>} to reject issuance. Fail-closed: a patient-scoped
   token is never issued without a Keto-backed launch patient."
  [payload granted-patients-fn launch-authorized?-fn]
  (let [end-user (end-user-subject payload)
        subject (or end-user
                    (get-in payload [:request :client_id])
                    (get-in payload [:session :client_id]))
        req-pid (requested-patient payload)
        scoped? (patient-scoped? payload)]
    (cond
      (nil? subject)
      (if scoped? {:deny "token hook payload carries no client_id"} {})

      req-pid
      (if (launch-authorized?-fn subject req-pid)
        {:patient req-pid}
        {:deny (str "subject " subject " has no launch grant for Patient/" req-pid)})

      ;; Interactive (end-user) tokens: Hydra v2.2.0 sends an empty
      ;; request.granted_scopes for authorization_code and refresh_token
      ;; issuance, so scope inspection cannot drive the decision. Patient
      ;; context follows the subject's Keto launch grants instead: exactly
      ;; one -> inject, zero or ambiguous -> issue without patient context.
      ;; Fail-closed is preserved upstream: the consent provider rejects
      ;; patient-scoped consents for identities with no linked Patient
      ;; before any token is requested, and data access still requires
      ;; Keto read tuples regardless of claims.
      end-user
      (let [granted (granted-patients-fn subject)]
        (if (= 1 (count granted))
          {:patient (first granted)}
          {}))

      (not scoped?)
      {}

      :else
      (let [granted (granted-patients-fn subject)]
        (case (count granted)
          0 {:deny (str "subject " subject " has no patient grants")}
          1 {:patient (first granted)}
          {:deny (str "subject " subject " is granted multiple patients; "
                      "pass patient=<id> on the token request")})))))

;; ---------------------------------------------------------------------------
;; First-party claims (mast BFF)
;;
;; First-party clients (the mast backend-for-frontend) get realm/role claims
;; resolved from the same Keto namespaces mast's cookie minting used:
;; breezeehr-role `has-role` on "<realm>/<role>" and practitioner-id `isa` on
;; "<realm>/<practitioner-uuid>". The claims land under `breeze` in the
;; access token's ext, next to the SMART `patient` claim this hook already
;; owns, so one hook serves both consumers.
;; ---------------------------------------------------------------------------

(defn first-party-client-ids
  "Client ids that receive first-party breeze claims, from the
   FIRST_PARTY_CLIENT_IDS env var (comma separated)."
  []
  (into #{}
        (comp (map str/trim) (remove str/blank?))
        (str/split (str (System/getenv "FIRST_PARTY_CLIENT_IDS")) #",")))

(defn resolve-first-party-claims
  "Pure: Keto relation tuples -> the `breeze` access-token claim
   {:realms [..] :roles {realm [..]} :practitioners {realm uuid}}.
   :realms uses the same rule as mast's legacy cookie minting: the
   intersection of realms granted a role and realms granting a
   practitioner identity."
  [role-tuples practitioner-tuples]
  (let [split2        (fn [o] (some-> o str (str/split #"/" 2)))
        roles         (reduce (fn [acc {:keys [object]}]
                                (let [[realm role] (split2 object)]
                                  (cond-> acc
                                    (and realm role)
                                    (update realm (fnil conj (sorted-set)) role))))
                              {}
                              role-tuples)
        practitioners (reduce (fn [acc {:keys [object]}]
                                (let [[realm practitioner-uuid] (split2 object)]
                                  (cond-> acc
                                    (and realm practitioner-uuid)
                                    (assoc realm practitioner-uuid))))
                              {}
                              practitioner-tuples)]
    {:realms        (vec (sort (filter (set (keys roles)) (keys practitioners))))
     :roles         (into {} (map (fn [[realm role-set]] [realm (vec role-set)])) roles)
     :practitioners practitioners}))

(defn- breeze-claims-for
  "Fetches and shapes the first-party claims for a Kratos subject. Keto
   subject ids carry the user:/ prefix, as mast writes them."
  [subject]
  (let [keto-subject (str "user:/" subject)]
    (resolve-first-party-claims
      (list-tuples {"namespace" "breezeehr-role"
                    "relation" "has-role"
                    "subject_id" keto-subject})
      (list-tuples {"namespace" "practitioner-id"
                    "relation" "isa"
                    "subject_id" keto-subject}))))

(defn token-hook
  "POST /auth/token-hook -- Ory Hydra token webhook (OAUTH2_TOKEN_HOOK_URL).
   Injects the SMART `patient` launch claim into the access token when Keto
   authorizes it, and `breeze` realm/role claims for first-party clients;
   rejects issuance otherwise. When TOKEN_HOOK_SECRET is set, requires a
   matching X-Token-Hook-Secret header."
  [req]
  (let [secret (System/getenv "TOKEN_HOOK_SECRET")]
    (if (and secret (not= secret (get-in req [:headers "x-token-hook-secret"])))
      {:status 401 :body {:error "invalid token hook secret"}}
      (let [payload (:body-params req)
            client-id (or (get-in payload [:request :client_id])
                          (get-in payload [:session :client_id]))
            subject (end-user-subject payload)
            first-party? (and subject (contains? (first-party-client-ids) client-id))
            decision (resolve-launch-patient payload granted-patients launch-authorized?)
            breeze (when (and first-party? (not (:deny decision)))
                     (breeze-claims-for subject))]
        (t/event! :grant/token-hook
                  {:data {:client-id client-id
                          :decision decision
                          :first-party (boolean first-party?)}})
        (cond
          (:deny decision) {:status 403 :body {:error (:deny decision)}}
          :else
          (let [ext (cond-> {}
                      (:patient decision) (assoc :patient (:patient decision))
                      breeze (assoc :breeze breeze))]
            (if (empty? ext)
              {:status 200 :body {}}
              {:status 200 :body {:session {:access_token ext}}})))))))
