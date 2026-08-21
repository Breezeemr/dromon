(ns test-server.schemas.breeze
  "Schema specs for the Breeze IG capability surface.

   Uses `breeze.capability.v1-0-0.*` from the breeze-ig malli package
   (`:malli/breeze` alias). `server.core/resolve-schemas` applies the
   Breeze storage registry overlay when present, so HumanName/Address
   multi-string fields install as USEP card-one strings on the Datomic
   store.

   These are config — `test-server.core` passes them to the
   `:fhir/schemas` integrant component, which resolves the symbols at
   system start. Loading this namespace does NOT load any malli schema
   namespaces.")

(def specs
  "Vector of schema specs (see `server.core/resolve-schema`).

   Prefer Breeze capability namespaces; fall back to R4B full-sch maps
   only where breeze-ig has no capability (rare)."
  ['breeze.capability.v1-0-0.AllergyIntolerance/capability
   'breeze.capability.v1-0-0.Appointment/capability
   'breeze.capability.v1-0-0.Basic/capability
   'breeze.capability.v1-0-0.CarePlan/capability
   'breeze.capability.v1-0-0.CareTeam/capability
   'breeze.capability.v1-0-0.Claim/capability
   'breeze.capability.v1-0-0.Composition/capability
   'breeze.capability.v1-0-0.Condition/capability
   'breeze.capability.v1-0-0.Consent/capability
   'breeze.capability.v1-0-0.Coverage/capability
   'breeze.capability.v1-0-0.Device/capability
   'breeze.capability.v1-0-0.DiagnosticReport/capability
   'breeze.capability.v1-0-0.DocumentReference/capability
   'breeze.capability.v1-0-0.Encounter/capability
   'breeze.capability.v1-0-0.Endpoint/capability
   'breeze.capability.v1-0-0.FamilyMemberHistory/capability
   'breeze.capability.v1-0-0.Goal/capability
   'breeze.capability.v1-0-0.HealthcareService/capability
   'breeze.capability.v1-0-0.Immunization/capability
   'breeze.capability.v1-0-0.List/capability
   'breeze.capability.v1-0-0.Location/capability
   'breeze.capability.v1-0-0.Media/capability
   'breeze.capability.v1-0-0.Medication/capability
   'breeze.capability.v1-0-0.MedicationDispense/capability
   'breeze.capability.v1-0-0.MedicationRequest/capability
   'breeze.capability.v1-0-0.MedicationStatement/capability
   'breeze.capability.v1-0-0.Observation/capability
   'breeze.capability.v1-0-0.Organization/capability
   'breeze.capability.v1-0-0.Patient/capability
   'breeze.capability.v1-0-0.Person/capability
   'breeze.capability.v1-0-0.Practitioner/capability
   'breeze.capability.v1-0-0.PractitionerRole/capability
   'breeze.capability.v1-0-0.Procedure/capability
   'breeze.capability.v1-0-0.Provenance/capability
   'breeze.capability.v1-0-0.Questionnaire/capability
   'breeze.capability.v1-0-0.QuestionnaireResponse/capability
   'breeze.capability.v1-0-0.RelatedPerson/capability
   'breeze.capability.v1-0-0.Schedule/capability
   'breeze.capability.v1-0-0.ServiceRequest/capability
   'breeze.capability.v1-0-0.Slot/capability
   'breeze.capability.v1-0-0.Specimen/capability
   'breeze.capability.v1-0-0.Task/capability
   'breeze.capability.v1-0-0.ValueSet/capability
   ;; Group: no breeze capability; base R4B for bulk export group membership
   {:schema 'org.hl7.fhir.StructureDefinition.Group.v4-3-0/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "delete" "history-instance" "history-type"]
    :search-params [{:name "member" :type "reference"
                     :definition "http://hl7.org/fhir/SearchParameter/Group-member"}
                    {:name "identifier" :type "token"
                     :definition "http://hl7.org/fhir/SearchParameter/Group-identifier"}
                    {:name "type" :type "token"
                     :definition "http://hl7.org/fhir/SearchParameter/Group-type"}
                    {:name "code" :type "token"
                     :definition "http://hl7.org/fhir/SearchParameter/Group-code"}]}
   {:schema 'org.hl7.fhir.StructureDefinition.SearchParameter.v4-3-0/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "patch" "delete" "history-instance"]}])
