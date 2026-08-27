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
