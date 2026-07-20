(ns test-server.schemas.erx
  "Trimmed schema specs for running the furl surescripts (tc*) integration
   tests against the datomic store: only the resource types those tests and
   the furl handlers touch. Smaller than uscore8 to keep the datomic schema
   generation within a modest heap.

   LOCAL-ONLY preset; not used by inferno or the e2e runners.")

(def ^:private r4b-interactions
  ["create" "search-type" "read" "vread"
   "update" "patch" "delete" "history-instance" "history-type"])

(def specs
  ['us-core.capability.v8-0-1.AllergyIntolerance/capability
   'us-core.capability.v8-0-1.Coverage/capability
   'us-core.capability.v8-0-1.Encounter/capability
   ;; furl's directory sync searches Location by the nonstandard param
   ;; name managingOrganization (standard name: organization); same
   ;; column via the standard definition.
   {:schema 'us-core.capability.v8-0-1.Location/capability
    :extra-search-params [{:name "managingOrganization"
                           :type "reference"
                           :definition "http://hl7.org/fhir/SearchParameter/Location-organization"}]}
   'us-core.capability.v8-0-1.Medication/capability
   'us-core.capability.v8-0-1.MedicationRequest/capability
   'us-core.capability.v8-0-1.Observation/capability
   'us-core.capability.v8-0-1.Organization/capability
   'us-core.capability.v8-0-1.Patient/capability
   'us-core.capability.v8-0-1.Practitioner/capability
   'us-core.capability.v8-0-1.PractitionerRole/capability
   ;; Types without a US Core profile: base R4B schemas with explicit
   ;; interactions (same pattern as Appointment/Questionnaire in uscore8).
   ;; Raw full-sch entries declare no search params, so any the furl
   ;; handlers rely on must be spelled out (the NewRx allergy inline
   ;; searches List by encounter + code).
   {:schema 'org.hl7.fhir.StructureDefinition.List.v4-3-0/full-sch
    :interactions r4b-interactions
    :search-params [{:name "encounter"
                     :type "reference"
                     :definition "http://hl7.org/fhir/SearchParameter/clinical-encounter"}
                    {:name "code"
                     :type "token"
                     :definition "http://hl7.org/fhir/SearchParameter/clinical-code"}
                    {:name "patient"
                     :type "reference"
                     :definition "http://hl7.org/fhir/SearchParameter/clinical-patient"}]}
   {:schema 'org.hl7.fhir.StructureDefinition.MedicationStatement.v4-3-0/full-sch
    :interactions r4b-interactions}
   {:schema 'org.hl7.fhir.StructureDefinition.CoverageEligibilityRequest.v4-3-0/full-sch
    :interactions r4b-interactions}
   {:schema 'org.hl7.fhir.StructureDefinition.CoverageEligibilityResponse.v4-3-0/full-sch
    :interactions r4b-interactions}
   {:schema 'org.hl7.fhir.StructureDefinition.VerificationResult.v4-3-0/full-sch
    :interactions r4b-interactions
    ;; The directory workflow looks up attestations by
    ;; VerificationResult?target=Practitioner/<id>.
    :search-params [{:name "target"
                     :type "reference"
                     :definition "http://hl7.org/fhir/SearchParameter/VerificationResult-target"}]}])
