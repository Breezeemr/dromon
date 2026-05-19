(ns test-server.validator-compile-test
  "Test which FHIR cap-schemas can compile Malli validators within a timeout.
   Run with: clj -M:test -m test-server.validator-compile-test"
  (:require [malli.core :as m]
            [us-core.capability.v8-0-1.AllergyIntolerance :as cap-allergy-intolerance]
            [us-core.capability.v8-0-1.CarePlan :as cap-care-plan]
            [us-core.capability.v8-0-1.CareTeam :as cap-care-team]
            [us-core.capability.v8-0-1.Condition :as cap-condition]
            [us-core.capability.v8-0-1.Coverage :as cap-coverage]
            [us-core.capability.v8-0-1.Device :as cap-device]
            [us-core.capability.v8-0-1.DiagnosticReport :as cap-diagnostic-report]
            [us-core.capability.v8-0-1.DocumentReference :as cap-document-reference]
            [us-core.capability.v8-0-1.Encounter :as cap-encounter]
            [us-core.capability.v8-0-1.Endpoint :as cap-endpoint]
            [us-core.capability.v8-0-1.Goal :as cap-goal]
            [us-core.capability.v8-0-1.HealthcareService :as cap-healthcare-service]
            [us-core.capability.v8-0-1.Immunization :as cap-immunization]
            [us-core.capability.v8-0-1.Location :as cap-location]
            [us-core.capability.v8-0-1.Media :as cap-media]
            [us-core.capability.v8-0-1.Medication :as cap-medication]
            [us-core.capability.v8-0-1.MedicationDispense :as cap-medication-dispense]
            [us-core.capability.v8-0-1.MedicationRequest :as cap-medication-request]
            [us-core.capability.v8-0-1.Observation :as cap-observation]
            [us-core.capability.v8-0-1.Organization :as cap-organization]
            [us-core.capability.v8-0-1.Patient :as cap-patient]
            [us-core.capability.v8-0-1.Practitioner :as cap-practitioner]
            [us-core.capability.v8-0-1.PractitionerRole :as cap-practitioner-role]
            [us-core.capability.v8-0-1.Procedure :as cap-procedure]
            [us-core.capability.v8-0-1.Provenance :as cap-provenance]
            [us-core.capability.v8-0-1.ValueSet :as cap-valueset]
            [us-core.capability.v8-0-1.QuestionnaireResponse :as cap-questionnaire-response]
            [us-core.capability.v8-0-1.RelatedPerson :as cap-related-person]
            [us-core.capability.v8-0-1.ServiceRequest :as cap-service-request]
            [us-core.capability.v8-0-1.Specimen :as cap-specimen]
            [org.hl7.fhir.StructureDefinition.Questionnaire.v4-3-0 :as questionnaire-r4b]
            [org.hl7.fhir.StructureDefinition.SearchParameter.v4-3-0 :as search-parameter]))

(def schemas
  [["AllergyIntolerance" cap-allergy-intolerance/capability]
   ["CarePlan" cap-care-plan/capability]
   ["CareTeam" cap-care-team/capability]
   ["Condition" cap-condition/capability]
   ["Coverage" cap-coverage/capability]
   ["Device" cap-device/capability]
   ["DiagnosticReport" cap-diagnostic-report/capability]
   ["DocumentReference" cap-document-reference/capability]
   ["Encounter" cap-encounter/capability]
   ["Endpoint" cap-endpoint/capability]
   ["Goal" cap-goal/capability]
   ["HealthcareService" cap-healthcare-service/capability]
   ["Immunization" cap-immunization/capability]
   ["Location" cap-location/capability]
   ["Media" cap-media/capability]
   ["Medication" cap-medication/capability]
   ["MedicationDispense" cap-medication-dispense/capability]
   ["MedicationRequest" cap-medication-request/capability]
   ["Observation" cap-observation/capability]
   ["Organization" cap-organization/capability]
   ["Patient" cap-patient/capability]
   ["Practitioner" cap-practitioner/capability]
   ["PractitionerRole" cap-practitioner-role/capability]
   ["Procedure" cap-procedure/capability]
   ["Provenance" cap-provenance/capability]
   ["ValueSet" cap-valueset/capability]
   ["QuestionnaireResponse" cap-questionnaire-response/capability]
   ["RelatedPerson" cap-related-person/capability]
   ["ServiceRequest" cap-service-request/capability]
   ["Specimen" cap-specimen/capability]
   ["Questionnaire" questionnaire-r4b/full-sch]
   ["SearchParameter" search-parameter/full-sch]])

(defn- ->validator-schema
  "Coerces a generated capability spec into a malli schema. The regenerated
   capability namespaces export a Clojure data map instead of a compiled
   `:multi`; for those we build the `:multi` via
   `server.core/cap-data->multi-schema` (the same path the runtime uses).
   Pre-compiled malli schemas (base FHIR resources like Questionnaire,
   SearchParameter) pass through unchanged."
  [spec]
  (if (and (map? spec) (contains? spec :branches))
    ((requiring-resolve 'server.core/cap-data->multi-schema)
     spec (:registry spec))
    spec))

(defn try-compile-validator
  "Try to compile a Malli validator for the given schema within timeout-ms.
   Returns {:name name :status :ok/:timeout/:error :time-ms ms :error err}"
  [schema-name schema timeout-ms]
  (let [result (promise)
        thread (Thread.
                 (fn []
                   (try
                     (let [start (System/nanoTime)
                           _validator (m/validator (->validator-schema schema))
                           elapsed (/ (- (System/nanoTime) start) 1e6)]
                       (deliver result {:name schema-name :status :ok :time-ms elapsed}))
                     (catch Exception e
                       (deliver result {:name schema-name :status :error :error (str e)})))))]
    (.start thread)
    (let [r (deref result timeout-ms nil)]
      (if r
        r
        (do
          (.interrupt thread)
          {:name schema-name :status :timeout :time-ms timeout-ms})))))

(defn -main [& _args]
  (println "=== Malli Validator Compilation Test ===")
  (println "Timeout: 30 seconds per schema\n")
  (let [timeout-ms 30000
        results (doall
                  (for [[schema-name schema] schemas]
                    (do
                      (print (format "%-25s " schema-name))
                      (flush)
                      (let [r (try-compile-validator schema-name schema timeout-ms)]
                        (case (:status r)
                          :ok      (println (format "OK     %8.1f ms" (:time-ms r)))
                          :timeout (println (format "TIMEOUT (>%ds)" (/ timeout-ms 1000)))
                          :error   (println (format "ERROR  %s" (:error r))))
                        r))))
        ok (filter #(= :ok (:status %)) results)
        timeout (filter #(= :timeout (:status %)) results)
        error (filter #(= :error (:status %)) results)]
    (println "\n=== Summary ===")
    (println (format "OK:      %d (avg %.1f ms)" (count ok)
                     (if (seq ok) (/ (reduce + (map :time-ms ok)) (double (count ok))) 0.0)))
    (println (format "TIMEOUT: %d" (count timeout)))
    (when (seq timeout)
      (println "  Timed out:" (mapv :name timeout)))
    (println (format "ERROR:   %d" (count error)))
    (when (seq error)
      (doseq [e error]
        (println "  " (:name e) "-" (:error e))))
    ;; Exit with proper code
    (System/exit (if (seq (concat timeout error)) 1 0))))
