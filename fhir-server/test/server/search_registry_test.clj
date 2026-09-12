(ns server.search-registry-test
  "Tests for the search parameter classification the search handlers use to
   decide which query parameters they can actually honour."
  (:require [clojure.test :refer [deftest is testing]]
            [server.search-registry :as sr]))

(def ^:private registry
  "Stand-in for the enriched registry `build-resource-registry` produces for a
   resource type that declares `patient` and `status`."
  {"patient" {:type "reference" :columns [{:col "patient"}]}
   "status"  {:type "token" :columns [{:col "status"}]}})

(deftest result-param-classification
  (testing "search result parameters are recognised"
    (is (sr/result-param? "_count"))
    (is (sr/result-param? "_sort"))
    (is (sr/result-param? "_include"))
    (is (sr/result-param? "_revinclude")))
  (testing "a modified result parameter classifies with its base name"
    (is (sr/result-param? "_include:iterate")))
  (testing "filter parameters are not result parameters"
    (is (not (sr/result-param? "patient")))
    (is (not (sr/result-param? "_id")))
    (is (not (sr/result-param? "_lastUpdated")))))

(deftest filter-params-drops-only-result-params
  (is (= {"patient" "Patient/1" "_id" "x"}
         (sr/filter-params {"patient" "Patient/1"
                            "_id" "x"
                            "_count" "50"
                            "_sort" "-_lastUpdated"
                            "_include" "Consent:patient"})))
  (testing "keyword keys are preserved and classified by name"
    (is (= {:patient "Patient/1"}
           (sr/filter-params {:patient "Patient/1" :_count 50 :_skip 0})))))

(deftest unsupported-filter-params-detection
  (testing "declared parameters are supported"
    (is (= [] (sr/unsupported-filter-params registry {"patient" "Patient/1"
                                                      "status" "active"}))))
  (testing "result parameters are never reported"
    (is (= [] (sr/unsupported-filter-params registry {"_count" "50"
                                                      "_sort" "status"
                                                      "_elements" "id"}))))
  (testing "resource-level parameters are supported whatever the registry says"
    (is (= [] (sr/unsupported-filter-params registry {"_id" "abc"
                                                      "_tag" "sys|code"
                                                      "_security" "sys|code"
                                                      "_profile" "http://example/p"}))))
  (testing "an undeclared parameter is reported"
    (is (= ["subject"] (sr/unsupported-filter-params registry {"subject" "Patient/1"}))))
  (testing "modifiers and chains are reported: the store matches registry
            entries by exact name, so neither reaches the query builder"
    (is (= ["patient:identifier"]
           (sr/unsupported-filter-params registry {"patient:identifier" "sys|1"})))
    (is (= ["patient.name"]
           (sr/unsupported-filter-params registry {"patient.name" "Smith"})))
    (is (= ["_has:Observation:patient:code"]
           (sr/unsupported-filter-params registry {"_has:Observation:patient:code" "1234-5"}))))
  (testing "_lastUpdated is only supported where the registry declares it"
    (is (= ["_lastUpdated"] (sr/unsupported-filter-params registry {"_lastUpdated" "gt2020-01-01"})))
    (is (= [] (sr/unsupported-filter-params (assoc registry "_lastUpdated" {:type "date"})
                                            {"_lastUpdated" "gt2020-01-01"}))))
  (testing "results are sorted and de-duplicated"
    (is (= ["aaa" "zzz"] (sr/unsupported-filter-params registry {"zzz" "1" "aaa" "2"}))))
  (testing "a nil registry supports only the resource-level parameters"
    (is (= [] (sr/unsupported-filter-params nil {"_id" "abc" "_count" "10"})))
    (is (= ["patient"] (sr/unsupported-filter-params nil {"patient" "Patient/1"})))))

(deftest text-param-classification
  (is (sr/text-param? "_text"))
  (testing "a modified _text is not the parameter an index answers"
    (is (not (sr/text-param? "_text:exact")))
    (is (not (sr/text-param? "_text:contains"))))
  (testing "_text is neither a result parameter nor a resource-level one"
    (is (not (sr/result-param? "_text")))
    (is (contains? (sr/filter-params {"_text" "smith" "_count" "5"}) "_text"))))

(deftest unsupported-filter-params-grants-text-only-when-told-to
  (testing "by default _text is reported like any name the registry lacks"
    (is (= ["_text"] (sr/unsupported-filter-params registry {"_text" "smith"})))
    (is (= ["_text"] (sr/unsupported-filter-params registry {"_text" "smith"} {})))
    (is (= ["_text"] (sr/unsupported-filter-params registry {"_text" "smith"}
                                                   {:text-search? false}))))
  (testing "the 2-arity and the opts-less 3-arity agree byte for byte"
    (let [params {"_text" "smith" "subject" "Patient/1" "status" "active" "_count" "3"}]
      (is (= (sr/unsupported-filter-params registry params)
             (sr/unsupported-filter-params registry params {})
             (sr/unsupported-filter-params registry params {:text-search? false})
             ["_text" "subject"]))))
  (testing "with the store's grant _text drops out and nothing else changes"
    (is (= [] (sr/unsupported-filter-params registry {"_text" "smith"}
                                            {:text-search? true})))
    (is (= ["subject"]
           (sr/unsupported-filter-params registry {"_text" "smith" "subject" "Patient/1"}
                                         {:text-search? true}))))
  (testing "the grant covers bare _text only; a modified form stays unsupported"
    (is (= ["_text:exact"]
           (sr/unsupported-filter-params registry {"_text:exact" "smith"}
                                         {:text-search? true}))))
  (testing "a keyword key classifies by name like every other parameter"
    (is (= [] (sr/unsupported-filter-params registry {:_text "smith"} {:text-search? true})))
    (is (= ["_text"] (sr/unsupported-filter-params registry {:_text "smith"}))))
  (testing "the grant does not need a registry"
    (is (= [] (sr/unsupported-filter-params nil {"_text" "smith"} {:text-search? true})))))

(deftest where-resolve-dotted-path-delegates-to-nested-resolution
  (let [resolve-expression #'sr/resolve-expression
        field-map {"participant" {:fhir-type "BackboneElement" :array? true
                                  :children {"actor" {:fhir-type "Reference" :array? false}}}
                   "subject" {:fhir-type "Reference" :array? false}}]
    (testing "a dotted .where(resolve() is X) path yields a :sub-col descriptor
              the store can translate to Datalog, not a dotted column"
      (is (= [{:col "participant" :fhir-type "BackboneElement" :array? true
               :sub-col "actor" :sub-fhir-type "Reference" :sub-array? false}]
             (resolve-expression "Appointment.participant.actor.where(resolve() is Patient)"
                                 field-map "reference"))))
    (testing "an un-dotted path keeps the plain reference descriptor"
      (is (= [{:col "subject" :fhir-type "Reference" :array? false}]
             (resolve-expression "Observation.subject.where(resolve() is Patient)"
                                 field-map "reference"))))))

(deftest where-system-resolves-to-a-fixed-system-constraint
  ;; `phone` and `email` are `telecom.where(system='phone')` and
  ;; `telecom.where(system='email')`. Before this branch existed the
  ;; expression fell into the nested-path case and emitted
  ;; `:sub-col "where(system='phone')"`, a column no store could translate,
  ;; so both parameters silently degraded to an in-memory match that
  ;; ignored the system.
  (let [resolve-expression #'sr/resolve-expression
        field-map {"telecom" {:fhir-type "ContactPoint" :array? true}
                   "contact" {:fhir-type "BackboneElement" :array? true
                              :children {"telecom" {:fhir-type "ContactPoint" :array? true}}}}]
    (testing "the telecom column carries the system as a fixed constraint"
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true
               :fixed {:system "phone"}}]
             (resolve-expression "Person.telecom.where(system='phone')" field-map "token"))))
    (testing "whitespace around the equals sign is tolerated"
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true
               :fixed {:system "email"}}]
             (resolve-expression "Patient.telecom.where(system = 'email')" field-map "token"))))
    (testing "a dotted path resolves through the nested machinery and keeps the constraint"
      (is (= [{:col "contact" :fhir-type "BackboneElement" :array? true
               :sub-col "telecom" :sub-fhir-type "ContactPoint" :sub-array? true
               :fixed {:system "phone"}}]
             (resolve-expression "Patient.contact.telecom.where(system='phone')" field-map "token"))))
    (testing "the un-narrowed telecom parameter is unchanged"
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true}]
             (resolve-expression "Person.telecom" field-map "token"))))
    (testing "no descriptor leaks an unparsed FHIRPath fragment as a column name"
      (doseq [col (resolve-expression "Person.telecom.where(system='phone')" field-map "token")]
        (is (not (re-find #"\(" (str (:col col) (:sub-col col)))))))))
