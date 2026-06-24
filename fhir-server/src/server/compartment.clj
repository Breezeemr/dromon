(ns server.compartment
  "FHIR R4B compartment definitions and patient-compartment row-level enforcement.

   `server.scope` gates the resource-type + interaction a SMART scope grants but
   does NOT confine a `patient/` scope to the in-context launch patient. This
   namespace closes that gap. When a token is restricted to the patient
   compartment (only `patient/` scopes, with a `patient` launch claim), the
   launch-patient filter is added to EVERY query the request issues — searches,
   instance reads, `_include`/`_revinclude` lookups and counts alike — by
   swapping the request store for a `CompartmentFilteringStore`. Writes that
   reference another patient are rejected, and `patient/` access to resource
   types outside the Patient compartment is denied.

   Compartment membership is the UNION of the search parameters the R4B
   CompartmentDefinition lists for a resource type (e.g. an Observation is in a
   Patient's compartment when `subject` OR `performer` references that patient).
   The union is expressed as a single synthetic `reference` search parameter
   whose columns span every link parameter, so the store evaluates it in one
   query (preserving sorting and pagination) rather than via multiple merged
   queries. The compartment owner resource itself (the Patient record) is
   confined by `_id`.

   The resource->parameter tables below are transcribed verbatim from the R4B
   CompartmentDefinitions:
   https://hl7.org/fhir/R4B/compartmentdefinition-{patient,practitioner,encounter,relatedperson,device}.json
   The owner self-entry (and the non-searchable `{def}` placeholder) is omitted
   from each table; the owner type is handled by `_id`."
  (:require [fhir-store.protocol :as db]
            [server.scope :as scope]
            [taoensso.telemere :as t]))

;; ---------------------------------------------------------------------------
;; R4B compartment definitions (https://hl7.org/fhir/R4B/compartmentdefinition.html)
;; compartment-type -> member-resource-type -> [linking search parameter ...]
;; ---------------------------------------------------------------------------

(def compartment-definitions
  {"Patient"
   {"Account"                      ["subject"]
    "AdverseEvent"                 ["subject"]
    "AllergyIntolerance"           ["patient" "recorder" "asserter"]
    "Appointment"                  ["actor"]
    "AppointmentResponse"          ["actor"]
    "AuditEvent"                   ["patient"]
    "Basic"                        ["patient" "author"]
    "BodyStructure"                ["patient"]
    "CarePlan"                     ["patient" "performer"]
    "CareTeam"                     ["patient" "participant"]
    "ChargeItem"                   ["subject"]
    "Claim"                        ["patient" "payee"]
    "ClaimResponse"                ["patient"]
    "ClinicalImpression"           ["subject"]
    "Communication"                ["subject" "sender" "recipient"]
    "CommunicationRequest"         ["subject" "sender" "recipient" "requester"]
    "Composition"                  ["subject" "author" "attester"]
    "Condition"                    ["patient" "asserter"]
    "Consent"                      ["patient"]
    "Coverage"                     ["policy-holder" "subscriber" "beneficiary" "payor"]
    "CoverageEligibilityRequest"   ["patient"]
    "CoverageEligibilityResponse"  ["patient"]
    "DetectedIssue"                ["patient"]
    "DeviceRequest"                ["subject" "performer"]
    "DeviceUseStatement"           ["subject"]
    "DiagnosticReport"             ["subject"]
    "DocumentManifest"             ["subject" "author" "recipient"]
    "DocumentReference"            ["subject" "author"]
    "Encounter"                    ["patient"]
    "EnrollmentRequest"            ["subject"]
    "EpisodeOfCare"                ["patient"]
    "ExplanationOfBenefit"         ["patient" "payee"]
    "FamilyMemberHistory"          ["patient"]
    "Flag"                         ["patient"]
    "Goal"                         ["patient"]
    "Group"                        ["member"]
    "ImagingStudy"                 ["patient"]
    "Immunization"                 ["patient"]
    "ImmunizationEvaluation"       ["patient"]
    "ImmunizationRecommendation"   ["patient"]
    "Invoice"                      ["subject" "patient" "recipient"]
    "List"                         ["subject" "source"]
    "MeasureReport"                ["patient"]
    "Media"                        ["subject"]
    "MedicationAdministration"     ["patient" "performer" "subject"]
    "MedicationDispense"           ["subject" "patient" "receiver"]
    "MedicationRequest"            ["subject"]
    "MedicationStatement"          ["subject"]
    "MolecularSequence"            ["patient"]
    "NutritionOrder"               ["patient"]
    "Observation"                  ["subject" "performer"]
    "Person"                       ["patient"]
    "Procedure"                    ["patient" "performer"]
    "Provenance"                   ["patient"]
    "QuestionnaireResponse"        ["subject" "author"]
    "RelatedPerson"                ["patient"]
    "RequestGroup"                 ["subject" "participant"]
    "ResearchSubject"              ["individual"]
    "RiskAssessment"               ["subject"]
    "Schedule"                     ["actor"]
    "ServiceRequest"               ["subject" "performer"]
    "Specimen"                     ["subject"]
    "SupplyDelivery"               ["patient"]
    "SupplyRequest"                ["subject"]
    "VisionPrescription"           ["patient"]}

   "Practitioner"
   {"Account"                      ["subject"]
    "AdverseEvent"                 ["recorder"]
    "AllergyIntolerance"           ["recorder" "asserter"]
    "Appointment"                  ["actor"]
    "AppointmentResponse"          ["actor"]
    "AuditEvent"                   ["agent"]
    "Basic"                        ["author"]
    "CarePlan"                     ["performer"]
    "CareTeam"                     ["participant"]
    "ChargeItem"                   ["enterer" "performer-actor"]
    "Claim"                        ["enterer" "provider" "payee" "care-team"]
    "ClaimResponse"                ["requestor"]
    "ClinicalImpression"           ["assessor"]
    "Communication"                ["sender" "recipient"]
    "CommunicationRequest"         ["sender" "recipient" "requester"]
    "Composition"                  ["subject" "author" "attester"]
    "Condition"                    ["asserter"]
    "CoverageEligibilityRequest"   ["enterer" "provider"]
    "CoverageEligibilityResponse"  ["requestor"]
    "DetectedIssue"                ["author"]
    "DeviceRequest"                ["requester" "performer"]
    "DiagnosticReport"             ["performer"]
    "DocumentManifest"             ["subject" "author" "recipient"]
    "DocumentReference"            ["subject" "author" "authenticator"]
    "Encounter"                    ["practitioner" "participant"]
    "EpisodeOfCare"                ["care-manager"]
    "ExplanationOfBenefit"         ["enterer" "provider" "payee" "care-team"]
    "Flag"                         ["author"]
    "Group"                        ["member"]
    "Immunization"                 ["performer"]
    "Invoice"                      ["participant"]
    "Linkage"                      ["author"]
    "List"                         ["source"]
    "Media"                        ["subject" "operator"]
    "MedicationAdministration"     ["performer"]
    "MedicationDispense"           ["performer" "receiver"]
    "MedicationRequest"            ["requester"]
    "MedicationStatement"          ["source"]
    "MessageHeader"                ["receiver" "author" "responsible" "enterer"]
    "NutritionOrder"               ["provider"]
    "Observation"                  ["performer"]
    "Patient"                      ["general-practitioner"]
    "PaymentNotice"                ["provider"]
    "PaymentReconciliation"        ["requestor"]
    "Person"                       ["practitioner"]
    "PractitionerRole"             ["practitioner"]
    "Procedure"                    ["performer"]
    "Provenance"                   ["agent"]
    "QuestionnaireResponse"        ["author" "source"]
    "RequestGroup"                 ["participant" "author"]
    "ResearchStudy"                ["principalinvestigator"]
    "RiskAssessment"               ["performer"]
    "Schedule"                     ["actor"]
    "ServiceRequest"               ["performer" "requester"]
    "Specimen"                     ["collector"]
    "SupplyDelivery"               ["supplier" "receiver"]
    "SupplyRequest"                ["requester"]
    "VisionPrescription"           ["prescriber"]}

   "Encounter"
   {"CarePlan"                  ["encounter"]
    "CareTeam"                  ["encounter"]
    "ChargeItem"                ["context"]
    "Claim"                     ["encounter"]
    "ClinicalImpression"        ["encounter"]
    "Communication"             ["encounter"]
    "CommunicationRequest"      ["encounter"]
    "Composition"               ["encounter"]
    "Condition"                 ["encounter"]
    "DeviceRequest"             ["encounter"]
    "DiagnosticReport"          ["encounter"]
    "DocumentManifest"          ["related-ref"]
    "DocumentReference"         ["encounter"]
    "ExplanationOfBenefit"      ["encounter"]
    "Media"                     ["encounter"]
    "MedicationAdministration"  ["context"]
    "MedicationRequest"         ["encounter"]
    "NutritionOrder"            ["encounter"]
    "Observation"               ["encounter"]
    "Procedure"                 ["encounter"]
    "QuestionnaireResponse"     ["encounter"]
    "RequestGroup"              ["encounter"]
    "ServiceRequest"            ["encounter"]
    "VisionPrescription"        ["encounter"]}

   "RelatedPerson"
   {"AdverseEvent"              ["recorder"]
    "AllergyIntolerance"        ["asserter"]
    "Appointment"               ["actor"]
    "AppointmentResponse"       ["actor"]
    "Basic"                     ["author"]
    "CarePlan"                  ["performer"]
    "CareTeam"                  ["participant"]
    "ChargeItem"                ["enterer" "performer-actor"]
    "Claim"                     ["payee"]
    "Communication"             ["sender" "recipient"]
    "CommunicationRequest"      ["sender" "recipient" "requester"]
    "Composition"               ["author"]
    "Condition"                 ["asserter"]
    "Coverage"                  ["policy-holder" "subscriber" "payor"]
    "DocumentManifest"          ["author" "recipient"]
    "DocumentReference"         ["author"]
    "Encounter"                 ["participant"]
    "ExplanationOfBenefit"      ["payee"]
    "Invoice"                   ["recipient"]
    "MedicationAdministration"  ["performer"]
    "MedicationStatement"       ["source"]
    "Observation"               ["performer"]
    "Patient"                   ["link"]
    "Person"                    ["link"]
    "Procedure"                 ["performer"]
    "Provenance"                ["agent"]
    "QuestionnaireResponse"     ["author" "source"]
    "RequestGroup"              ["participant"]
    "Schedule"                  ["actor"]
    "ServiceRequest"            ["performer"]
    "SupplyRequest"             ["requester"]}

   "Device"
   {"Account"                   ["subject"]
    "Appointment"               ["actor"]
    "AppointmentResponse"       ["actor"]
    "AuditEvent"                ["agent"]
    "ChargeItem"                ["enterer" "performer-actor"]
    "Claim"                     ["procedure-udi" "item-udi" "detail-udi" "subdetail-udi"]
    "Communication"             ["sender" "recipient"]
    "CommunicationRequest"      ["sender" "recipient"]
    "Composition"               ["author"]
    "DetectedIssue"             ["author"]
    "DeviceRequest"             ["device" "subject" "requester" "performer"]
    "DeviceUseStatement"        ["device"]
    "DiagnosticReport"          ["subject"]
    "DocumentManifest"          ["subject" "author"]
    "DocumentReference"         ["subject" "author"]
    "ExplanationOfBenefit"      ["procedure-udi" "item-udi" "detail-udi" "subdetail-udi"]
    "Flag"                      ["author"]
    "Group"                     ["member"]
    "Invoice"                   ["participant"]
    "List"                      ["subject" "source"]
    "Media"                     ["subject"]
    "MedicationAdministration"  ["device"]
    "MessageHeader"             ["target"]
    "Observation"               ["subject" "device"]
    "Provenance"                ["agent"]
    "QuestionnaireResponse"     ["author"]
    "RequestGroup"              ["author"]
    "RiskAssessment"            ["performer"]
    "Schedule"                  ["actor"]
    "ServiceRequest"            ["performer" "requester"]
    "Specimen"                  ["subject"]
    "SupplyRequest"             ["requester"]}})

(def valid-compartment-types
  "Set of resource types that define FHIR compartments."
  (set (keys compartment-definitions)))

(def ^:const compartment-search-param
  "Reserved synthetic search parameter name used to inject the compartment
   union predicate. Underscore-prefixed so it cannot collide with a real FHIR
   search parameter."
  "_compartment")

;; ---------------------------------------------------------------------------
;; Compartment query construction
;; ---------------------------------------------------------------------------

(defn compartment-link-params
  "The R4B search parameter names that link `fhir-type` to `compartment-type`,
   or nil when `fhir-type` is not a member."
  [compartment-type fhir-type]
  (get-in compartment-definitions [compartment-type fhir-type]))

(defn member?
  "True when `fhir-type` belongs to `compartment-type` (the owner resource is a
   member of its own compartment, confined by id)."
  [compartment-type fhir-type]
  (boolean (or (= fhir-type compartment-type)
               (compartment-link-params compartment-type fhir-type))))

(defn patient-compartment-member? [fhir-type]
  (member? "Patient" fhir-type))

(defn compartment-descriptor
  "A synthetic `reference` search-parameter descriptor whose columns are the
   union of every link parameter's columns for `fhir-type` in `compartment-type`,
   resolved against `registry`. Returns nil when none of the link parameters are
   registered for this type (so callers can fail closed). Passing this descriptor
   under `compartment-search-param` makes the store OR the predicate across all
   columns in a single query."
  [compartment-type fhir-type registry]
  (let [params (compartment-link-params compartment-type fhir-type)
        columns (->> params (keep #(get registry %)) (mapcat :columns) distinct vec)]
    (when (seq columns)
      {:type "reference" :target [compartment-type] :columns columns})))

(defn confine
  "Computes how to confine a query on `fhir-type` to the
   `compartment-type`/`compartment-id` instance. Returns one of:
     [:run params registry] — run the search with these (filter injected),
     :passthrough           — `fhir-type` is outside the compartment (e.g. an
                              _include target); run unchanged,
     :deny                  — `fhir-type` is a member but no link parameter is
                              registered; the caller must fail closed."
  [compartment-type compartment-id fhir-type params registry]
  (cond
    (= fhir-type compartment-type)
    [:run (assoc params "_id" compartment-id) registry]

    (not (member? compartment-type fhir-type))
    :passthrough

    :else
    (if-let [desc (compartment-descriptor compartment-type fhir-type registry)]
      [:run (assoc params compartment-search-param (str compartment-type "/" compartment-id))
            (assoc registry compartment-search-param desc)]
      :deny)))

;; ---------------------------------------------------------------------------
;; Patient launch context and scope restriction
;; ---------------------------------------------------------------------------

(defn launch-patient
  "The in-context SMART launch patient id (a bare Patient logical id) from the
   validated JWT identity, or nil when absent."
  [request]
  (get-in request [:identity :patient]))

(defn token-patient-restricted?
  "True when the token is confined to the patient compartment: it carries at
   least one `patient/` scope and no broader `user/` or `system/` scope. A
   token with any user/system scope is treated as unrestricted here and relies
   on `server.scope` + keto for authorization."
  [parsed-scopes]
  (and (some #(= "patient" (:compartment %)) parsed-scopes)
       (not-any? #(#{"user" "system"} (:compartment %)) parsed-scopes)))

;; ---------------------------------------------------------------------------
;; Compartment-filtering store decorator
;; ---------------------------------------------------------------------------

(defn- reference-matches?
  "True when any of the given column descriptors holds a Reference to `target`
   (e.g. \"Patient/123\") in `resource`. Supports the common
   {:reference \"Patient/<id>\"} shape, single or array-valued."
  [resource columns target]
  (boolean
    (some (fn [{:keys [col]}]
            (let [v (get resource (keyword col))]
              (cond
                (map? v)        (= (:reference v) target)
                (sequential? v) (some #(and (map? %) (= (:reference %) target)) v)
                :else           false)))
          columns)))

(defn- write-in-compartment?
  "True when writing `resource` (logical id `id`) to `resource-type` stays within
   `patient-id`'s compartment. Patient writes must target the launch patient;
   member writes must reference Patient/<patient-id> via a registered link param."
  [all-registries patient-id resource-type id resource]
  (let [ft (name resource-type)]
    (cond
      (= ft "Patient")
      (= (or id (:id resource)) patient-id)

      (not (member? "Patient" ft))
      true

      :else
      (let [desc (compartment-descriptor "Patient" ft (get all-registries ft))]
        (and desc (reference-matches? resource (:columns desc) (str "Patient/" patient-id)))))))

(defn- forbidden-write! []
  (throw (ex-info "Resource is outside the patient compartment."
                  {:fhir/status 403 :fhir/code "forbidden"})))

(defrecord CompartmentFilteringStore [base patient-id all-registries]
  db/IFHIRStore
  (search [_ tenant-id resource-type params search-registry]
    (let [registry (or search-registry (get all-registries (name resource-type)))
          outcome (confine "Patient" patient-id (name resource-type) params registry)]
      (cond
        (= :passthrough outcome) (db/search base tenant-id resource-type params registry)
        (= :deny outcome) []
        :else (let [[_ p r] outcome] (db/search base tenant-id resource-type p r)))))

  (count-resources [_ tenant-id resource-type params search-registry]
    (let [registry (or search-registry (get all-registries (name resource-type)))
          outcome (confine "Patient" patient-id (name resource-type) params registry)]
      (cond
        (= :passthrough outcome) (db/count-resources base tenant-id resource-type params registry)
        (= :deny outcome) 0
        :else (let [[_ p r] outcome] (db/count-resources base tenant-id resource-type p r)))))

  (read-resource [_ tenant-id resource-type id]
    (let [ft (name resource-type)
          registry (get all-registries ft)]
      (cond
        (= ft "Patient")
        (when (= id patient-id) (db/read-resource base tenant-id resource-type id))

        (not (member? "Patient" ft))
        (db/read-resource base tenant-id resource-type id)

        :else
        (when-let [desc (compartment-descriptor "Patient" ft registry)]
          (first (db/search base tenant-id resource-type
                            {"_id" id
                             compartment-search-param (str "Patient/" patient-id)
                             :_count 1 :_skip 0}
                            (assoc registry compartment-search-param desc)))))))

  (vread-resource [this tenant-id resource-type id vid]
    (when (db/read-resource this tenant-id resource-type id)
      (db/vread-resource base tenant-id resource-type id vid)))

  (resource-deleted? [_ tenant-id resource-type id]
    (db/resource-deleted? base tenant-id resource-type id))

  (create-resource [_ tenant-id resource-type id resource]
    (if (write-in-compartment? all-registries patient-id resource-type id resource)
      (db/create-resource base tenant-id resource-type id resource)
      (forbidden-write!)))

  (update-resource [this tenant-id resource-type id resource]
    (db/update-resource this tenant-id resource-type id resource nil))

  (update-resource [_ tenant-id resource-type id resource opts]
    (if (write-in-compartment? all-registries patient-id resource-type id resource)
      (if opts
        (db/update-resource base tenant-id resource-type id resource opts)
        (db/update-resource base tenant-id resource-type id resource))
      (forbidden-write!)))

  (delete-resource [this tenant-id resource-type id]
    (db/delete-resource this tenant-id resource-type id nil))

  (delete-resource [this tenant-id resource-type id opts]
    (if (db/read-resource this tenant-id resource-type id)
      (if opts
        (db/delete-resource base tenant-id resource-type id opts)
        (db/delete-resource base tenant-id resource-type id))
      (forbidden-write!)))

  (history [_ tenant-id resource-type id]
    (db/history base tenant-id resource-type id))

  (history-type [_ tenant-id resource-type params]
    (db/history-type base tenant-id resource-type params))

  (transact-transaction [_ tenant-id entries]
    (db/transact-transaction base tenant-id entries))

  (transact-bundle [_ tenant-id entries]
    (db/transact-bundle base tenant-id entries))

  (create-tenant [_ tenant-id] (db/create-tenant base tenant-id))
  (create-tenant [_ tenant-id opts] (db/create-tenant base tenant-id opts))
  (delete-tenant [_ tenant-id] (db/delete-tenant base tenant-id))
  (delete-tenant [_ tenant-id opts] (db/delete-tenant base tenant-id opts))
  (warmup-tenant [_ tenant-id] (db/warmup-tenant base tenant-id))
  (warmup-tenant [_ tenant-id opts] (db/warmup-tenant base tenant-id opts)))

(defn filtering-store
  "Wraps `base` store so every query is confined to `patient-id`'s Patient
   compartment."
  [base {:keys [patient-id all-registries]}]
  (->CompartmentFilteringStore base patient-id all-registries))

;; ---------------------------------------------------------------------------
;; Enforcement middleware
;; ---------------------------------------------------------------------------

(defn- forbidden [diagnostics]
  {:status 403
   :body   {:resourceType "OperationOutcome"
            :issue [{:severity "error"
                     :code "forbidden"
                     :diagnostics diagnostics}]}})

(defn- not-found [diagnostics]
  {:status 404
   :body   {:resourceType "OperationOutcome"
            :issue [{:severity "error"
                     :code "not-found"
                     :diagnostics diagnostics}]}})

(defn wrap-patient-compartment
  "Middleware that confines patient-restricted tokens to the launch patient's
   compartment. Runs AFTER server.scope/wrap-smart-scope (which has already
   authorized the resource-type + interaction) and so assumes the request is
   otherwise permitted. Bypasses `:public?` routes.

   For a patient-restricted token it requires a launch patient, denies access
   to resource types outside the Patient compartment, and installs a
   CompartmentFilteringStore so every query the handler issues is confined to
   the launch patient's compartment (the UNION of the R4B link parameters).
   On a compartment-search route it permits only the launch patient's own
   Patient compartment (the handler then confines by compartment id)."
  [handler _opts]
  (fn [request]
    (let [route-data (get-in request [:reitit.core/match :data])]
      (if (:public? route-data)
        (handler request)
        (let [scopes         (scope/request-scopes request)
              fhir-type      (scope/request->fhir-type request)
              target-type    (get-in request [:path-params :target-type])
              compartment-id (get-in request [:path-params :id])
              pid            (launch-patient request)
              all-registries (:fhir/all-registries route-data)
              wrap-store     (fn [req]
                               (assoc req :fhir/store
                                      (filtering-store (:fhir/store req)
                                                       {:patient-id pid
                                                        :all-registries all-registries})))]
          (cond
            ;; Unrestricted token (user/system, or no patient scope): leave the
            ;; scope/keto decisions to stand, no compartment narrowing.
            (not (token-patient-restricted? scopes))
            (handler request)

            ;; A patient-restricted token must carry a launch patient context.
            (nil? pid)
            (forbidden "patient/ scope requires a launch patient context (no `patient` claim in token).")

            ;; Compartment-search route: a patient token may browse only its own
            ;; Patient compartment. The handler then confines by compartment id.
            (some? target-type)
            (cond
              (not= fhir-type "Patient")
              (forbidden "patient/ scope may only browse the Patient compartment.")
              (not= compartment-id pid)
              (not-found (str "Patient/" compartment-id " is outside the patient compartment."))
              :else (handler request))

            ;; System endpoints that resolve to no resource type.
            (nil? fhir-type)
            (handler request)

            ;; Deny patient/ access to types outside the Patient compartment.
            (not (patient-compartment-member? fhir-type))
            (forbidden (str fhir-type " is not part of the Patient compartment; "
                            "a patient/ scope cannot access it."))

            :else
            (t/trace!
              {:id :authz/patient-compartment.confine
               :data {:fhir-type fhir-type :patient pid}}
              (handler (wrap-store request)))))))))
