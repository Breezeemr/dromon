(ns server.grant
  "SMART on FHIR patient-set grants backed by Ory Hydra and Ory Keto.

   A grant gives an OAuth2 subject (a Hydra client id or end-user sub) access
   to a set of patients within one realm. It is recorded as Keto relation
   tuples in the `fhir` namespace, every object carrying the realm as its
   first segment (see docs/keto-realm-scoping.md):

   - {:object \"<realm>/Patient/<id>\" :relation \"launch\"} -- the subject may
     obtain a token whose SMART launch context is that patient. Never
     consulted by the data-access middleware, so it grants no data access by
     itself.
   - {:object \"<realm>/Patient/<id>\" :relation \"read\"|...} -- instance-level
     access to the Patient resource itself (checked by server.keto).
   - {:object \"<realm>/Patient/<id>\" :relation \"request-change\"} --
     instance-level authorization for the
     Patient/$request-demographic-change operation.
   - {:object \"<realm>/<MemberType>\" :relation \"read\"|...} -- type-level
     access to the Patient-compartment member types. Safe in combination with
     a patient/ scoped token because server.compartment confines every query
     to the launch patient's compartment.

   During the realm-scoping migration each of these is written twice, once
   scoped and once in the legacy realm-blind shape, so a rollback to the
   previous reader still finds grants it understands.

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

;; ---------------------------------------------------------------------------
;; Realm scoping
;;
;; Objects in the `fhir` namespace carry the realm as their first segment --
;; "<realm>/Patient", "<realm>/Patient/<id>" -- matching the convention the
;; breezeehr-role and practitioner-id namespaces already use. See
;; docs/keto-realm-scoping.md for why the object is the only slot available.
;;
;; Writers DUAL-WRITE the realm-scoped and the legacy realm-blind shape for the
;; duration of the migration, so a rollback to the previous reader still finds
;; grants it understands. `dual-write-legacy?` is what step 4 turns off.
;; ---------------------------------------------------------------------------

(def default-dual-write-legacy?
  "Whether a grant also writes the pre-realm-scoping realm-blind tuple.
   True until the backfill is confirmed complete; see
   docs/keto-realm-scoping.md. `KETO_DUAL_WRITE_LEGACY=0` disables it."
  true)

(defn- dual-write-legacy? []
  (if-some [v (System/getenv "KETO_DUAL_WRITE_LEGACY")]
    (not= "0" v)
    default-dual-write-legacy?))

(defn scoped-object
  "`object` prefixed with `realm`, or `object` unchanged when there is no realm.

   A realm can never contain a slash -- it arrives as a single URL path
   segment -- so the prefix stays unambiguous however many segments `object`
   already has."
  [realm object]
  (if (str/blank? (str realm))
    object
    (str realm "/" object)))

(def ^:private launch-object-re
  "Matches both shapes of a launch tuple's object, capturing [realm patient-id]:
   the realm-scoped `<realm>/Patient/<id>` and the legacy realm-blind
   `Patient/<id>`, whose realm group is then nil.

   Matched with an optional leading segment rather than by counting segments,
   because `Patient/123` and `<realm>/Patient` have the same segment count, and
   rather than by testing the first segment for a resource-type shape, because
   that would misread a realm whose name happens to be capitalized."
  #"(?:([^/]+)/)?Patient/(.+)")

(defn launch-object->patient
  "[realm patient-id] for a launch tuple's object, realm nil for the legacy
   realm-blind shape, or nil when the object names no patient.

   Both shapes have to be readable at once: during the migration a subject's
   launch tuples are a mix of the two, and a reader that understands only one
   of them reports a patient set missing half the grants -- which the token
   hook turns into a refusal to issue any patient-scoped token."
  [object]
  (when-let [[_ realm pid] (re-matches launch-object-re (str object))]
    [realm pid]))

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

(defn- grant-object+relations
  "The [object relation] pairs a patient-set grant comprises, realm-blind.
   `grant-tuples` scopes each object to the realm."
  [patient-ids relations]
  (-> []
      (into (for [pid patient-ids]
              [(str "Patient/" pid) launch-relation]))
      (into (for [pid patient-ids, rel relations]
              [(str "Patient/" pid) rel]))
      (into (for [t (patient-member-types), rel relations]
              [t rel]))))

(defn grant-tuples
  "The full set of Keto tuples that a patient-set grant comprises, with every
   object scoped to `realm`.

   `legacy?` additionally emits the pre-realm-scoping realm-blind tuple for
   each object. Both are written during the migration so that rolling the
   reader back to the previous release still finds grants it understands; a
   grant written scoped-only would be invisible to it. It is a no-op when
   `realm` is blank, where the scoped object already IS the legacy object.
   See docs/keto-realm-scoping.md."
  [realm subject patient-ids relations legacy?]
  (vec
   (for [[object relation] (grant-object+relations patient-ids relations)
         obj (cond-> [(scoped-object realm object)]
               (and legacy? (not (str/blank? (str realm)))) (conj object))]
     {:namespace "fhir" :object obj :relation relation :subject_id subject})))

(defn grant-patient-set!
  "Grants `subject` access to the given patient ids within `realm`, with the
   given relations (default read-only).

   NOT idempotent, contrary to what this docstring said before: Keto's tuple
   write is not an upsert. Three identical PUTs produce three rows (verified
   against Keto v0.12.0; `PATCH ... insert` behaves the same), because a
   relationship's primary key is a generated id rather than the tuple itself.
   Re-granting therefore duplicates rows. Checks still answer true, so nothing
   breaks visibly -- but `list-tuples` takes one page of 500 and stops, so a
   subject re-granted often enough eventually has real grants pushed off the
   end of the page `granted-patients` reads. Deduplicating the writes is a
   separate change; `granted-patients` de-duplicates on the read side.

   A nil `realm` grants the legacy realm-blind shape, which is access in EVERY
   realm the deployment hosts. It stays callable so pre-existing callers keep
   working during the migration, and it is recorded on the event so such a
   grant is findable afterwards."
  [realm subject patient-ids & {:keys [relations] :or {relations default-relations}}]
  (run! put-tuple! (grant-tuples realm subject patient-ids relations
                                 (dual-write-legacy?)))
  (t/event! :grant/patient-set-granted
            {:data {:realm realm :realm-blind? (str/blank? (str realm))
                    :subject subject :patients (vec patient-ids)
                    :relations (vec relations)}})
  {:realm realm :subject subject :patients (vec patient-ids)
   :relations (vec relations)})

(defn granted-patient-grants
  "[{:realm <realm-or-nil> :patient-id <id>}] for every launch tuple the
   subject holds, in either object shape.

   The listing is the one Keto call that sees both shapes at once: there is no
   prefix filter on `getRelationships`, so a realm cannot be queried for
   directly and the narrowing is client-side anyway."
  [subject]
  (->> (list-tuples {"namespace" "fhir"
                     "relation" launch-relation
                     "subject_id" subject})
       (keep (fn [{:keys [object]}]
               (when-let [[realm pid] (launch-object->patient object)]
                 {:realm realm :patient-id pid})))
       (sort-by (juxt :patient-id :realm))
       vec))

(defn granted-patients
  "Patient ids the subject holds a launch tuple for, in ANY realm.

   Deliberately realm-blind, and it has to be: the only caller that matters is
   the Hydra token hook, and a token request carries no realm -- Hydra knows
   about clients and scopes, not about this server's tenants. Unioning the
   realms is what keeps behaviour identical to before realm-scoping. Nothing
   downstream needs the realm either, because the SMART `patient` claim is a
   bare id.

   Pre-existing limitation, unchanged here: `list-tuples` fetches one page of
   500 and does not follow `next_page_token`, so a subject granted more than
   500 launch tuples has the excess silently invisible."
  [subject]
  (->> (granted-patient-grants subject)
       (map :patient-id)
       distinct
       sort
       vec))

(defn- launch-authorized?
  "Whether the subject holds a launch tuple for `patient-id` in any realm.

   Reads the listing rather than Keto's check endpoint, because a check needs
   an exact object and during the migration the tuple may carry either shape,
   in any realm -- there is nothing to build an exact object from. This is also
   the source `resolve-launch-patient`'s other branch already trusts, so both
   branches now agree by construction instead of by coincidence.

   What this gives up is Keto's subject-SET expansion: a launch grant made to a
   userset rather than to a subject id would not be seen. No writer in the
   estate makes one -- `grant-tuples` and `linkage/launch-tuple` both write
   `subject_id` -- and the userset refactor is blocked on the subject-spelling
   split (docs/keto-realm-scoping.md), so nothing is lost today. It has to be
   revisited the day launch grants move to usersets."
  [subject patient-id]
  (contains? (set (granted-patients subject)) (str patient-id)))

(defn revoke-patient-set!
  "Removes the subject's tuples for the given patient ids (all relations, all
   realms). Type-level compartment tuples are removed once the subject's last
   patient grant is gone.

   Revocation deletes EVERY shape it can find, not the ones a caller names:
   the legacy realm-blind object, and the realm-scoped object for every realm
   the subject holds a launch tuple for. Over-deleting is the only acceptable
   direction here -- a revoke that leaves one shape behind leaves the access it
   was called to remove, and reports success."
  [subject patient-ids]
  (let [;; Read before deleting: this is what tells us which realms the
        ;; subject's tuples live in, and they are deleted below.
        ;;
        ;; Every instance tuple is scanned, not just the launch ones, because
        ;; a realm whose launch tuple has already been revoked can still hold
        ;; read and request-change tuples. Missing such a realm would leave
        ;; exactly the access this call exists to remove.
        realms (into #{}
                     (comp (map :object)
                           (keep #(first (launch-object->patient %))))
                     (list-tuples {"namespace" "fhir" "subject_id" subject}))]
    (doseq [pid patient-ids
            object (cons (str "Patient/" pid)
                         (map #(scoped-object % (str "Patient/" pid)) realms))]
      (delete-tuples! {"namespace" "fhir"
                       "object" object
                       "subject_id" subject}))
    (when (empty? (granted-patients subject))
      (doseq [t (patient-member-types)
              object (cons t (map #(scoped-object % t) realms))]
        (delete-tuples! {"namespace" "fhir" "object" object "subject_id" subject})))
    (t/event! :grant/patient-set-revoked
              {:data {:subject subject :patients (vec patient-ids)
                      :realms (vec (sort realms))}})
    {:subject subject :revoked (vec patient-ids)}))

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
  "POST /auth/grants {subject, patients [...], realm?, relations? [...]}

   `realm` confines the grant to one tenant. It is optional so that callers
   predating realm-scoping keep working, but omitting it grants access in
   EVERY realm the deployment hosts, and the response says so in
   `:realm-blind?` rather than leaving the caller to infer it."
  [req]
  (let [{:keys [subject patients relations realm]} (:body-params req)]
    (cond
      (str/blank? (str subject)) (bad-request "subject is required")
      (empty? patients) (bad-request "patients must be a non-empty list")
      :else {:status 201
             :body (assoc (grant-patient-set! realm subject patients
                                              :relations (or (not-empty relations)
                                                             default-relations))
                          :realm-blind? (str/blank? (str realm)))})))

(defn read-grant
  "GET /auth/grants?subject=<id>"
  [req]
  (let [subject (get-in req [:query-params "subject"])]
    (if (str/blank? (str subject))
      (bad-request "subject query parameter is required")
      {:status 200
       :body {:subject subject
              :patients (granted-patients subject)
              :grants (granted-patient-grants subject)}})))

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
