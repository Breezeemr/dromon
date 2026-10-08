(ns server.compartment
  "FHIR R4B compartment definitions and patient-compartment row-level enforcement.

   `server.scope` gates the resource-type + interaction a SMART scope grants but
   does NOT confine a `patient/` scope to the in-context launch patient. This
   namespace closes that gap. When a token is restricted to the patient
   compartment (only `patient/` scopes, with a `patient` launch claim), the
   launch-patient filter is added to EVERY query the request issues — searches,
   instance reads, `_include`/`_revinclude` lookups and counts alike — by
   swapping the request store for a `CompartmentFilteringStore`. Every write
   verb is confined the same way, the Bundle verbs entry by entry: a write
   must leave its resource in the launch patient's compartment and may not
   land on a resource the token cannot read, and `patient/` access to resource
   types outside the Patient compartment is denied. A route may name resource
   types it writes outside the compartment (`:compartment/exempt-write-types`,
   see `wrap-patient-compartment`); such a write still may not touch another
   patient's compartment.

   Compartment membership is the UNION of the search parameters the R4B
   CompartmentDefinition lists for a resource type (e.g. an Observation is in a
   Patient's compartment when `subject` OR `performer` references that patient).
   The union is expressed as a single synthetic `reference` search parameter
   whose columns span every link parameter, so the store evaluates it in one
   query (preserving sorting and pagination) rather than via multiple merged
   queries. The compartment owner resource itself (the Patient record) is
   confined by `_id`.

   The resource->parameter tables below are transcribed from the R4B
   CompartmentDefinitions:
   https://hl7.org/fhir/R4B/compartmentdefinition-{patient,practitioner,encounter,relatedperson,device}.json
   The owner self-entry (and the non-searchable `{def}` placeholder) is omitted
   from each table; the owner type is handled by `_id`. One entry (Patient/Task)
   is a documented forward-port from R5; see the comment at its site."
  (:require [clojure.string :as str]
            [fhir-store.protocol :as db]
            [server.scope :as scope]
            [taoensso.telemere :as t]
            [fhir-store.trace :as ftrace]))

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
    ;; Deliberate forward-port from R5, narrowed. R4B's Patient
    ;; CompartmentDefinition omits Task (R5 adds it with patient, subject,
    ;; owner and requester). Breeze files patient-authored Tasks (demographic
    ;; change requests) with Task.for = the subject and Task.requester = the
    ;; filing patient, and no owner. Staff workflow Tasks also point Task.for
    ;; at the patient, so `patient`, `subject` and `owner` would expose them to
    ;; a patient token. Only `requester` marks the authored case.
    "Task"                         ["requester"]
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

(declare reference-matches?)

(defn resource-in-any-compartment?
  "In-memory (no store query) membership test for the bulk export streamer:
   true when `resource` of `fhir-type` belongs to the `compartment-type`
   compartment of ANY owner id in `owner-ids` (a set of bare logical ids).

   The owner resource type itself matches by id (its logical id is in
   `owner-ids`). A member type matches when one of its registered R4B link
   columns (resolved via `registry`) holds a Reference to `compartment-type/<id>`
   for some id in the set. A non-member type never matches. This mirrors the
   `_compartment` push-down that `confine` applies to `db/search`, but evaluated
   locally against an already-read resource so it can be applied while streaming
   a `scan-type-as-of` snapshot."
  [compartment-type owner-ids fhir-type resource registry]
  (cond
    (= fhir-type compartment-type)
    (contains? owner-ids (:id resource))

    (not (member? compartment-type fhir-type))
    false

    :else
    (boolean
     (when-let [desc (compartment-descriptor compartment-type fhir-type registry)]
       (let [cols (:columns desc)]
         (some (fn [owner-id]
                 (reference-matches? resource cols (str compartment-type "/" owner-id)))
               owner-ids))))))

;; ---------------------------------------------------------------------------
;; Patient launch context and scope restriction
;; ---------------------------------------------------------------------------

(defn launch-patient
  "The in-context SMART launch patient id (a bare Patient logical id) from the
   validated JWT identity, or nil when absent.

   Read from the top-level `patient` claim, falling back to `ext.patient`.
   Hydra only promotes a claim to the top level when it is listed in
   OAUTH2_ALLOWED_TOP_LEVEL_CLAIMS, and nests it under `ext` otherwise, so the
   same authorization server issues one shape or the other depending on how it
   was configured. Reading only the top level meant a token from a Hydra
   without that setting had no launch patient here and was refused for lacking
   one -- while the operation implementations, which already accept both
   shapes, would have honoured it. One definition of the token's patient, used
   by every layer that gates on it."
  [request]
  (let [claims (:identity request)]
    (or (:patient claims) (get-in claims [:ext :patient]))))

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

(defn- column-values
  "The elements a column descriptor addresses in `resource`, as a seq of maps.

   A descriptor names a top-level element (`:col`) and, for a nested link
   parameter, the path beneath it (`:sub-col`, dot-separated). Every step may
   be a single element or an array, so arrays are flattened at each level:
   Appointment's `actor` is {:col \"participant\" :sub-col \"actor\"} and yields
   the actor of every participant. Reading only `:col` would find no `actor` on
   an Appointment and fail every write closed."
  [resource {:keys [col sub-col]}]
  (let [elements (fn [v] (cond (map? v) [v] (sequential? v) (filter map? v) :else []))]
    (reduce (fn [values k] (mapcat #(elements (get % k)) values))
            (elements (get resource (keyword col)))
            (when sub-col (map keyword (str/split sub-col #"\."))))))

(defn- reference-matches?
  "True when any of the given column descriptors holds a Reference to `target`
   (e.g. \"Patient/123\") in `resource`, at the top level or nested beneath it."
  [resource columns target]
  (boolean
    (some (fn [column]
            (some #(= (:reference %) target) (column-values resource column)))
          columns)))

(defn- write-in-compartment?
  "True when writing `resource` (logical id `id`) to `resource-type` stays within
   `patient-id`'s compartment. Patient writes must target the launch patient;
   member writes must reference Patient/<patient-id> via a registered link param.
   A type outside the compartment is never inside it: REST refuses a `patient/`
   token those types before the store is reached, and a Bundle entry or an
   operation must not reach further than the same request issued alone."
  [all-registries patient-id resource-type id resource]
  (let [ft (name resource-type)]
    (cond
      (= ft "Patient")
      (= (or id (:id resource)) patient-id)

      (not (member? "Patient" ft))
      false

      :else
      (let [desc (compartment-descriptor "Patient" ft (get all-registries ft))]
        (and desc (reference-matches? resource (:columns desc) (str "Patient/" patient-id)))))))

(defn- links-another-patient?
  "True when `resource` of `resource-type` belongs to the compartment of a
   Patient other than `patient-id`: the Patient record itself, or a member
   holding a Patient reference in one of its link columns. A type outside the
   compartment links no patient, and neither does a nil `resource`. A member
   type with no registered link parameter cannot be judged, so it is assumed
   to link one."
  [all-registries patient-id resource-type resource]
  (let [ft (name resource-type)
        own (str "Patient/" patient-id)]
    (boolean
     (when resource
       (cond
         (= ft "Patient")
         (not= (:id resource) patient-id)

         (not (member? "Patient" ft))
         false

         :else
         (if-let [desc (compartment-descriptor "Patient" ft (get all-registries ft))]
           (some (fn [column]
                   (some #(let [r (:reference %)]
                            (and (string? r) (str/starts-with? r "Patient/") (not= r own)))
                         (column-values resource column)))
                 (:columns desc))
           true))))))

(defn- refusal [status code diagnostics]
  {:status status :code code :diagnostics diagnostics})

(def ^:private outside-compartment
  (refusal 403 "forbidden" "Resource is outside the patient compartment."))

(defn- refuse! [{:keys [status code diagnostics]}]
  (throw (ex-info diagnostics {:fhir/status status :fhir/code code})))

(defn- write-refusal
  "nil when `method` (:create, :update or :delete) on `resource-type`/`id`
   with post-image `resource` may reach the base store, else the refusal.

   The post-image check alone is not enough: an update addressed to a resource
   the token cannot read would overwrite another patient's record while
   re-pointing it at the launch patient, so the resource being replaced must
   be readable here or absent from the base. A delete must name a readable
   resource, as it always has.

   A type the route declares exempt skips the compartment check, but neither
   the post-image nor the resource it replaces may link another patient: an
   exemption lets an operation write shared or server-owned records, never
   reach into another patient's chart."
  [{:keys [base patient-id all-registries exempt-write-types] :as store}
   tenant-id method resource-type id resource]
  (let [ft (name resource-type)
        existing (when id (db/read-resource base tenant-id resource-type id))]
    (if (contains? exempt-write-types ft)
      (if (or (links-another-patient? all-registries patient-id ft resource)
              (links-another-patient? all-registries patient-id ft existing))
        outside-compartment
        (do (t/event! :authz/patient-compartment.exempt-write
                      {:data {:resource-type ft :method method}})
            nil))
      (let [readable? (fn [] (some? (db/read-resource store tenant-id resource-type id)))]
        (case method
          :delete (when-not (and id (readable?)) outside-compartment)
          (when-not (and (write-in-compartment? all-registries patient-id ft id resource)
                         (or (nil? existing) (readable?)))
            outside-compartment))))))

(defn- entry-refusal
  "nil when a Bundle `entry` may reach the base store, else the refusal.

   Entries are judged against the state before the Bundle runs. Two shapes are
   refused outright because the base would resolve them with a search this
   store never sees: a conditional entry (a `?` in the url, or `ifNoneExist`)
   and an unresolved PATCH, whose post-image does not exist until the base
   applies it. dromon's own Bundle handler resolves PATCH into a guarded PUT
   before the store is called. A read of a resource the token cannot read
   answers as a read does: not found."
  [store tenant-id entry]
  (let [request (:request entry)
        method (some-> (:method request) str/upper-case)
        url (str (:url request))
        [type id] (str/split url #"/")
        ;; Keyword, as every other verb passes it: a store may key its state
        ;; by the keyword form, and a string would read as absent.
        rt (when-not (str/blank? type) (keyword type))]
    (cond
      (or (str/includes? url "?") (:ifNoneExist request) (get request "ifNoneExist"))
      (refusal 403 "forbidden"
               "Conditional Bundle entries are not supported for a patient-compartment token.")

      (= "PATCH" method)
      (refusal 403 "forbidden"
               "An unresolved PATCH entry is not supported for a patient-compartment token.")

      (#{"GET" "HEAD"} method)
      (when-not (and rt id (db/read-resource store tenant-id rt id))
        (refusal 404 "not-found" (str url " not found")))

      (nil? rt)
      (refusal 403 "forbidden" "A Bundle entry must name a resource type.")

      ;; The check is made against the url's type, so a body of another type
      ;; must not ride under it.
      (and (:resource entry) (not= type (:resourceType (:resource entry))))
      (refusal 403 "forbidden"
               (str "Bundle entry resource type does not match its url: " url))

      (= "POST" method)
      (write-refusal store tenant-id :create rt nil (:resource entry))

      (= "PUT" method)
      (write-refusal store tenant-id :update rt id (:resource entry))

      (= "DELETE" method)
      (write-refusal store tenant-id :delete rt id nil)

      ;; Any other method the base refuses itself.
      :else nil)))

(defn- refused-entry
  "A batch-response entry answering a refused input entry in place."
  [{:keys [status code diagnostics]}]
  {:response {:status (str status " " (if (= 404 status) "Not Found" "Forbidden"))
              :outcome {:resourceType "OperationOutcome"
                        :issue [{:severity "error" :code code :diagnostics diagnostics}]}}})

(defn- in-compartment?
  "Whether an already-read version of `resource-type` belongs to the launch
   patient's compartment, for confining history and vread. A type outside the
   compartment is linked context and passes, as it does for reads."
  [{:keys [patient-id all-registries]} resource-type resource]
  (let [ft (name resource-type)]
    (or (not (member? "Patient" ft))
        (resource-in-any-compartment? "Patient" #{patient-id} ft resource
                                      (get all-registries ft)))))

(defrecord CompartmentFilteringStore [base patient-id all-registries exempt-write-types]
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

  ;; The current version being readable does not make every version readable:
  ;; one from before the resource entered the compartment (refiled from
  ;; another chart, a Task whose requester changed) is judged on its own, as
  ;; history judges it, and reads as absent.
  (vread-resource [this tenant-id resource-type id vid]
    (when (db/read-resource this tenant-id resource-type id)
      (let [version (db/vread-resource base tenant-id resource-type id vid)]
        (when (and version (in-compartment? this resource-type version))
          version))))

  (resource-deleted? [_ tenant-id resource-type id]
    (db/resource-deleted? base tenant-id resource-type id))

  (create-resource [this tenant-id resource-type id resource]
    (db/create-resource this tenant-id resource-type id resource nil))

  (create-resource [this tenant-id resource-type id resource opts]
    (when-let [r (write-refusal this tenant-id :create resource-type id resource)]
      (refuse! r))
    (if opts
      (db/create-resource base tenant-id resource-type id resource opts)
      (db/create-resource base tenant-id resource-type id resource)))

  (update-resource [this tenant-id resource-type id resource]
    (db/update-resource this tenant-id resource-type id resource nil))

  (update-resource [this tenant-id resource-type id resource opts]
    (when-let [r (write-refusal this tenant-id :update resource-type id resource)]
      (refuse! r))
    (if opts
      (db/update-resource base tenant-id resource-type id resource opts)
      (db/update-resource base tenant-id resource-type id resource)))

  (delete-resource [this tenant-id resource-type id]
    (db/delete-resource this tenant-id resource-type id nil))

  (delete-resource [this tenant-id resource-type id opts]
    (when-let [r (write-refusal this tenant-id :delete resource-type id nil)]
      (refuse! r))
    (if opts
      (db/delete-resource base tenant-id resource-type id opts)
      (db/delete-resource base tenant-id resource-type id)))

  (history [this tenant-id resource-type id]
    (filterv #(in-compartment? this resource-type %)
             (db/history base tenant-id resource-type id)))

  (history-type [this tenant-id resource-type params]
    (filterv #(in-compartment? this resource-type %)
             (db/history-type base tenant-id resource-type params)))

  (transact-transaction [this tenant-id entries]
    (db/transact-transaction this tenant-id entries nil))

  ;; Atomic: every entry is judged before any reaches the base, so one refusal
  ;; fails the whole Bundle with nothing written.
  (transact-transaction [this tenant-id entries opts]
    (doseq [entry entries]
      (when-let [r (entry-refusal this tenant-id entry)]
        (refuse! r)))
    (if opts
      (db/transact-transaction base tenant-id entries opts)
      (db/transact-transaction base tenant-id entries)))

  (transact-bundle [this tenant-id entries]
    (db/transact-bundle this tenant-id entries nil))

  ;; Batch: a refused entry is answered in place and the rest go to the base,
  ;; whose responses are woven back into input order.
  (transact-bundle [this tenant-id entries opts]
    (let [checked (mapv (fn [entry]
                          (if-let [r (entry-refusal this tenant-id entry)]
                            {:refusal r}
                            {:entry entry}))
                        entries)
          admitted (into [] (keep :entry) checked)
          res (cond
                (empty? admitted) {:resourceType "Bundle" :type "batch-response" :entry []}
                opts (db/transact-bundle base tenant-id admitted opts)
                :else (db/transact-bundle base tenant-id admitted))
          from-base (volatile! (seq (:entry res)))]
      (assoc res :entry (mapv (fn [{:keys [refusal]}]
                                (if refusal
                                  (refused-entry refusal)
                                  (let [[head & tail] @from-base]
                                    (vreset! from-base tail)
                                    head)))
                              checked))))

  (create-tenant [_ tenant-id] (db/create-tenant base tenant-id))
  (create-tenant [_ tenant-id opts] (db/create-tenant base tenant-id opts))
  (delete-tenant [_ tenant-id] (db/delete-tenant base tenant-id))
  (delete-tenant [_ tenant-id opts] (db/delete-tenant base tenant-id opts))
  (warmup-tenant [_ tenant-id] (db/warmup-tenant base tenant-id))
  (warmup-tenant [_ tenant-id opts] (db/warmup-tenant base tenant-id opts))

  ;; Bulk-export snapshot reads pass through unconfined: per the protocol,
  ;; compartment confinement of scan-type-as-of is the export layer's
  ;; responsibility (server.bulk-export applies it while streaming).
  (current-basis [_ tenant-id] (db/current-basis base tenant-id))
  (scan-type-as-of [_ tenant-id resource-type basis]
    (db/scan-type-as-of base tenant-id resource-type basis))
  (count-as-of [_ tenant-id resource-type basis]
    (db/count-as-of base tenant-id resource-type basis))

  ;; A decorator answers both questions by delegating (fhir-store.protocol,
  ;; rule 2): the write verbs above already forward a stamp to the base, so
  ;; whether it is kept is the base's answer. Saying so lets a handler pass the
  ;; host's stamp on a patient-scoped request, which the base's lifecycle sees
  ;; on its write map; the compartment's write refusals run first either way.
  db/ITxMetadataStore
  (tx-metadata-supported? [_]
    (db/supports-tx-metadata? base))
  (tx-metadata-of [this tenant-id resource-type id vid]
    ;; A stamp is read only for a version this store would serve.
    (when (and (db/supports-tx-metadata? base)
               (db/vread-resource this tenant-id resource-type id vid))
      (db/tx-metadata-of base tenant-id resource-type id vid))))

(defn filtering-store
  "Wraps `base` store so every query and write is confined to `patient-id`'s
   Patient compartment. `exempt-write-types` is a collection of resource type
   names the caller may write outside it; see `write-refusal`."
  [base {:keys [patient-id all-registries exempt-write-types]}]
  (->CompartmentFilteringStore base patient-id all-registries
                               (set (map name exempt-write-types))))

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
   Patient compartment (the handler then confines by compartment id).

   A system endpoint that resolves to no resource type -- a transaction or
   batch Bundle POSTed to the base -- has no type to gate, so its store is
   wrapped as well: the entries are the only place the patient link can be
   judged, and the filtering store judges them one by one.

   Route data may carry `:compartment/exempt-write-types`, the resource type
   names an operation writes outside the compartment. It is the one way to
   cross the boundary, declared where the route is built rather than decided
   at the call site, and it never admits a write into another patient's
   compartment (see `write-refusal`)."
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
                                                        :all-registries all-registries
                                                        :exempt-write-types
                                                        (:compartment/exempt-write-types route-data)})))]
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
            (handler (wrap-store request))

            ;; Deny patient/ access to types outside the Patient compartment.
            (not (patient-compartment-member? fhir-type))
            (forbidden (str fhir-type " is not part of the Patient compartment; "
                            "a patient/ scope cannot access it."))

            :else
            (ftrace/trace!
              {:id :authz/patient-compartment.confine
               :data {:fhir-type fhir-type :patient pid}}
              (handler (wrap-store request)))))))))
