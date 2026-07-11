(ns test-server.schemas.uscore8
  "Schema specs for the US Core STU8 capability profile.

   These are config — `test-server.core` passes them to the
   `:fhir/schemas` integrant component, which resolves the symbols at
   system start via `server.core/resolve-schemas`. Loading this namespace
   does NOT load any malli schema namespaces; the requiring-resolve happens
   only when integrant initializes the component.")

(def specs
  "Vector of schema specs (see `server.core/resolve-schema`).

   Each entry is either a fully qualified symbol naming a `capability` Var,
   or a map `{:schema <fq-sym> :interactions [..]}` for resources whose
   capability schemas don't carry interactions out of the box."
  ['us-core.capability.v8-0-1.AllergyIntolerance/capability
   'us-core.capability.v8-0-1.CarePlan/capability
   'us-core.capability.v8-0-1.CareTeam/capability
   'us-core.capability.v8-0-1.Condition/capability
   'us-core.capability.v8-0-1.Coverage/capability
   'us-core.capability.v8-0-1.Device/capability
   'us-core.capability.v8-0-1.DiagnosticReport/capability
   'us-core.capability.v8-0-1.DocumentReference/capability
   'us-core.capability.v8-0-1.Encounter/capability
   'us-core.capability.v8-0-1.Endpoint/capability
   'us-core.capability.v8-0-1.Goal/capability
   'us-core.capability.v8-0-1.HealthcareService/capability
   'us-core.capability.v8-0-1.Immunization/capability
   'us-core.capability.v8-0-1.Location/capability
   'us-core.capability.v8-0-1.Media/capability
   'us-core.capability.v8-0-1.Medication/capability
   'us-core.capability.v8-0-1.MedicationDispense/capability
   'us-core.capability.v8-0-1.MedicationRequest/capability
   'us-core.capability.v8-0-1.Observation/capability
   'us-core.capability.v8-0-1.Organization/capability
   'us-core.capability.v8-0-1.Patient/capability
   'us-core.capability.v8-0-1.Practitioner/capability
   'us-core.capability.v8-0-1.PractitionerRole/capability
   'us-core.capability.v8-0-1.Procedure/capability
   'us-core.capability.v8-0-1.Provenance/capability
   ;; Questionnaire: base R4B fallback (SDC profile generation fails)
   {:schema 'org.hl7.fhir.StructureDefinition.Questionnaire.v4-3-0/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "delete" "history-instance" "history-type"]}
   'us-core.capability.v8-0-1.QuestionnaireResponse/capability
   'us-core.capability.v8-0-1.RelatedPerson/capability
   'us-core.capability.v8-0-1.ServiceRequest/capability
   'us-core.capability.v8-0-1.Specimen/capability
   ;; Resources not in US Core CapabilityStatement — add basic interactions
   ;; Appointment: base R4B fallback (no US Core profile); Patient-compartment
   ;; member via `actor`, used by the cabotage2 home screen.
   {:schema 'org.hl7.fhir.StructureDefinition.Appointment.v4-3-0/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "patch" "delete" "history-instance" "history-type"]}
   ;; Group: base R4B fallback (no US Core profile). Registered so the store
   ;; builds a schema-driven encoder/decoder that round-trips
   ;; Group.member.entity references faithfully — the :default open-map decoder
   ;; used for unregistered types does not keywordize nested struct keys, which
   ;; the group-level Bulk Data export relies on. Group/[id]/$export reads the
   ;; Group directly from the store to resolve its member patients.
   {:schema 'org.hl7.fhir.StructureDefinition.Group.v4-3-0/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "delete" "history-instance" "history-type"]}
   {:schema 'us-core.capability.v8-0-1.ValueSet/capability
    :interactions ["create" "search-type" "read" "vread"
                   "update" "patch" "delete" "history-instance"]}
   {:schema 'org.hl7.fhir.StructureDefinition.SearchParameter.v4-3-0/full-sch
    :interactions ["create" "search-type" "read" "vread"
                   "update" "patch" "delete" "history-instance"]}])
