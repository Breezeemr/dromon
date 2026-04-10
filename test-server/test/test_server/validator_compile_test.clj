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
  [["AllergyIntolerance" cap-allergy-intolerance/full-sch]
   ["CarePlan" cap-care-plan/full-sch]
   ["CareTeam" cap-care-team/full-sch]
   ["Condition" cap-condition/full-sch]
   ["Coverage" cap-coverage/full-sch]
   ["Device" cap-device/full-sch]
   ["DiagnosticReport" cap-diagnostic-report/full-sch]
   ["DocumentReference" cap-document-reference/full-sch]
   ["Encounter" cap-encounter/full-sch]
   ["Endpoint" cap-endpoint/full-sch]
   ["Goal" cap-goal/full-sch]
   ["HealthcareService" cap-healthcare-service/full-sch]
   ["Immunization" cap-immunization/full-sch]
   ["Location" cap-location/full-sch]
   ["Media" cap-media/full-sch]
   ["Medication" cap-medication/full-sch]
   ["MedicationDispense" cap-medication-dispense/full-sch]
   ["MedicationRequest" cap-medication-request/full-sch]
   ["Observation" cap-observation/full-sch]
   ["Organization" cap-organization/full-sch]
   ["Patient" cap-patient/full-sch]
   ["Practitioner" cap-practitioner/full-sch]
   ["PractitionerRole" cap-practitioner-role/full-sch]
   ["Procedure" cap-procedure/full-sch]
   ["Provenance" cap-provenance/full-sch]
   ["ValueSet" cap-valueset/full-sch]
   ["QuestionnaireResponse" cap-questionnaire-response/full-sch]
   ["RelatedPerson" cap-related-person/full-sch]
   ["ServiceRequest" cap-service-request/full-sch]
   ["Specimen" cap-specimen/full-sch]
   ["Questionnaire" questionnaire-r4b/full-sch]
   ["SearchParameter" search-parameter/full-sch]])

(defn try-compile-validator
  "Try to compile a Malli validator for the given schema within timeout-ms.
   Returns {:name name :status :ok/:timeout/:error :time-ms ms :error err}"
  [schema-name schema timeout-ms]
  (let [result (promise)
        thread (Thread.
                 (fn []
                   (try
                     (let [start (System/nanoTime)
                           _validator (m/validator schema)
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
