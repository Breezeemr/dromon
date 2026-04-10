(ns test-server.schemas.uscore8
  "Schema specs for the US Core STU8 capability profile.

   These are config — `test-server.core` passes them to the
   `:fhir/schemas` integrant component, which resolves the symbols at
   system start via `server.core/resolve-schemas`. Loading this namespace
   does NOT load any malli schema namespaces; the requiring-resolve happens
   only when integrant initializes the component.")

(def specs
  "Vector of schema specs (see `server.core/resolve-schema`).

   Each entry is either a fully qualified symbol naming a `full-sch` Var,
   or a map `{:schema <fq-sym> :interactions [..]}` for resources whose
   capability schemas don't carry interactions out of the box."
  ['us-core.capability.v8-0-1.AllergyIntolerance/full-sch
   'us-core.capability.v8-0-1.CarePlan/full-sch
   'us-core.capability.v8-0-1.CareTeam/full-sch
   'us-core.capability.v8-0-1.Condition/full-sch
   'us-core.capability.v8-0-1.Coverage/full-sch
   'us-core.capability.v8-0-1.Device/full-sch
   'us-core.capability.v8-0-1.DiagnosticReport/full-sch
   'us-core.capability.v8-0-1.DocumentReference/full-sch
   'us-core.capability.v8-0-1.Encounter/full-sch
   'us-core.capability.v8-0-1.Endpoint/full-sch
   'us-core.capability.v8-0-1.Goal/full-sch
   'us-core.capability.v8-0-1.HealthcareService/full-sch
   'us-core.capability.v8-0-1.Immunization/full-sch
   'us-core.capability.v8-0-1.Location/full-sch
   'us-core.capability.v8-0-1.Media/full-sch
   'us-core.capability.v8-0-1.Medication/full-sch
   'us-core.capability.v8-0-1.MedicationDispense/full-sch
   'us-core.capability.v8-0-1.MedicationRequest/full-sch
   'us-core.capability.v8-0-1.Observation/full-sch
   'us-core.capability.v8-0-1.Organization/full-sch
   'us-core.capability.v8-0-1.Patient/full-sch
   'us-core.capability.v8-0-1.Practitioner/full-sch
   'us-core.capability.v8-0-1.PractitionerRole/full-sch
   'us-core.capability.v8-0-1.Procedure/full-sch
   'us-core.capability.v8-0-1.Provenance/full-sch
   ;; Questionnaire: base R4B fallback (SDC profile generation fails)
   {:schema 'org.hl7.fhir.StructureDefinition.Questionnaire.v4-3-0/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "delete" "history-instance" "history-type"]}
   'us-core.capability.v8-0-1.QuestionnaireResponse/full-sch
   'us-core.capability.v8-0-1.RelatedPerson/full-sch
   'us-core.capability.v8-0-1.ServiceRequest/full-sch
   'us-core.capability.v8-0-1.Specimen/full-sch
   ;; Resources not in US Core CapabilityStatement — add basic interactions
   {:schema 'us-core.capability.v8-0-1.ValueSet/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "patch" "delete" "history-instance"]}
   {:schema 'org.hl7.fhir.StructureDefinition.SearchParameter.v4-3-0/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "patch" "delete" "history-instance"]}])
