(ns com.breezeehr.fhir-defintions-to-malli-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.breezeehr.fhir-defintions-to-malli :as fdm]
            [com.breezeehr.fhir-shape :as shape]
            [com.breezeehr.fhir-schema-gen :as gen]))

(deftest representation-props-test
  (testing "xmlAttr"
    (is (= {:xml/attr true
            :fhir/representation ["xmlAttr"]}
           (fdm/representation-props {:representation ["xmlAttr"]}))))
  (testing "xmlText + typeAttr"
    (is (= {:xml/text true
            :xml/type-attr true
            :fhir/representation ["typeAttr" "xmlText"]}
           (fdm/representation-props {:representation ["xmlText" "typeAttr"]}))))
  (testing "xml-choice-group extension"
    (is (= {:xml/choice-group true}
           (fdm/representation-props
            {:extension [{:url "http://hl7.org/fhir/tools/StructureDefinition/xml-choice-group"
                          :valueBoolean true}]}))))
  (testing "empty"
    (is (= {} (fdm/representation-props {})))))

(deftest uri->kw2-cda-test
  (is (= :org.hl7.cda.stds.core.StructureDefinition.IVL-TS/v2-1
         (fdm/uri->kw2 "http://hl7.org/cda/stds/core/StructureDefinition/IVL-TS" "2.1")))
  (is (= :org.hl7.cda.us.ccda.StructureDefinition.USRealmAddress/v2-1
         (fdm/uri->kw2 "http://hl7.org/cda/us/ccda/StructureDefinition/USRealmAddress" "2.1"))))

(deftest shape-url-type-is-ref-test
  (testing "full URL type codes are treated as refs"
    (let [info (shape/field-info
                {:code "http://hl7.org/cda/stds/core/StructureDefinition/ADXP"}
                "1")]
      (is (shape/ref? info))))
  (testing "short FHIR complex types remain refs"
    (is (shape/ref? (shape/field-info {:code "CodeableConcept"} "1"))))
  (testing "primitives are not refs"
    (is (not (shape/ref? (shape/field-info {:code "string"} "1"))))))

(deftest generatable-kinds-rebindable-test
  (is (contains? gen/*generatable-kinds* "resource"))
  (is (not (contains? gen/*generatable-kinds* "logical")))
  (binding [gen/*generatable-kinds* (conj gen/*generatable-kinds* "logical")]
    (is (contains? gen/*generatable-kinds* "logical"))))

(deftest profile-discriminator-dispatch-value-test
  (testing "type=profile reads type.profile, not fixed/pattern"
    (let [slice-path ["Observation" "entryRelationship"]
          sub-elements [{:path ["Observation" "entryRelationship" "observation"]
                         :type [{:code "http://hl7.org/cda/stds/core/StructureDefinition/Observation"
                                 :profile ["http://hl7.org/cda/us/ccda/StructureDefinition/AgeObservation"]}]}
                        {:path ["Observation" "entryRelationship" "act"]
                         :type [{:code "http://hl7.org/cda/stds/core/StructureDefinition/Act"}]}]
          discs [{:type "profile" :path "observation"}
                 {:type "profile" :path "act"}]
          ;; #' via ns-resolve — private helpers under test
          extract (ns-resolve 'com.breezeehr.fhir-defintions-to-malli 'extract-dispatch-value)
          result (extract discs sub-elements slice-path "age")]
      (is (= ["http://hl7.org/cda/us/ccda/StructureDefinition/AgeObservation" nil]
             (:dispatch-value result)))))
  (testing "all-nil profile extraction falls back to slice name"
    (let [extract (ns-resolve 'com.breezeehr.fhir-defintions-to-malli 'extract-dispatch-value)
          result (extract [{:type "profile" :path "observation"}
                           {:type "profile" :path "act"}]
                          []
                          ["Observation" "entryRelationship"]
                          "woundMeasurementObservation")]
      (is (= :woundMeasurementObservation (:dispatch-value result)))))
  (testing "standalone dispatch prefers single profile URL"
    (let [standalone (ns-resolve 'com.breezeehr.fhir-defintions-to-malli 'standalone-dispatch-value)
          v (standalone
             [{:path ["Observation" "entryRelationship" "observation"]
               :type [{:profile ["http://hl7.org/cda/us/ccda/StructureDefinition/WoundMeasurementObservation"]}]}]
             ["Observation" "entryRelationship"]
             "woundMeasurementObservation")]
      (is (= "http://hl7.org/cda/us/ccda/StructureDefinition/WoundMeasurementObservation" v)))))

(deftest canonical-version-test
  (testing "pinned canonical"
    (is (= "http://hl7.org/fhir/StructureDefinition/alternate-reference"
           (fdm/strip-canonical-version
            "http://hl7.org/fhir/StructureDefinition/alternate-reference|5.2.0")))
    (is (= "5.2.0"
           (fdm/canonical-version
            "http://hl7.org/fhir/StructureDefinition/alternate-reference|5.2.0"))))
  (testing "unpinned canonical is returned whole"
    (is (= "http://hl7.org/fhir/StructureDefinition/Patient"
           (fdm/strip-canonical-version "http://hl7.org/fhir/StructureDefinition/Patient")))
    (is (nil? (fdm/canonical-version "http://hl7.org/fhir/StructureDefinition/Patient")))))

(deftest canonical-index-test
  (let [r4b-plan [{:url "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden" :version "4.3.0"}
                  {:url "http://hl7.org/fhir/StructureDefinition/Patient" :version "4.3.0"}]
        fx-plan  [{:url "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden" :version "5.3.0-ballot-tc1"}
                  {:url "http://hl7.org/fhir/StructureDefinition/alternate-reference" :version "5.3.0-ballot-tc1"}]
        index    (gen/canonical-index [r4b-plan fx-plan])]
    (testing "a canonical defined by two packages keeps pipeline order"
      (is (= [{:version "4.3.0"
               :kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0}
              {:version "5.3.0-ballot-tc1"
               :kw :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v5-3-0-ballot-tc1}]
             (get index "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden"))))
    (testing "index-kws collects every promised keyword"
      (is (= #{:org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0
               :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v5-3-0-ballot-tc1
               :org.hl7.fhir.StructureDefinition.Patient/v4-3-0
               :org.hl7.fhir.StructureDefinition.alternate-reference/v5-3-0-ballot-tc1}
             (gen/index-kws index))))

    (testing "resolution"
      ;; Only the R4B half of the run has been generated so far.
      (binding [fdm/*canonical-index* index
                fdm/*schema-atom* (atom {:org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0 {}
                                         :org.hl7.fhir.StructureDefinition.Patient/v4-3-0 {}})]
        (testing "a pin picks the version that publishes it, not the earliest package"
          (is (= :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v5-3-0-ballot-tc1
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden|5.3.0-ballot-tc1"))))
        (testing "no pin prefers an already-generated definition"
          (is (= :org.hl7.fhir.StructureDefinition.questionnaire-hidden/v4-3-0
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/questionnaire-hidden"))))
        (testing "a forward reference resolves to the package that will define it"
          (is (= :org.hl7.fhir.StructureDefinition.alternate-reference/v5-3-0-ballot-tc1
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/alternate-reference|5.2.0"))))
        (testing "a pin no package publishes still resolves to a real definition"
          (is (= :org.hl7.fhir.StructureDefinition.Patient/v4-3-0
                 (fdm/resolve-canonical-kw
                  "http://hl7.org/fhir/StructureDefinition/Patient|1.0.0"))))
        (testing "a canonical no package defines does not resolve"
          (is (nil? (fdm/resolve-canonical-kw
                     "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-timeout")))))
      (testing "without an index bound, resolution is inert"
        (binding [fdm/*canonical-index* nil
                  fdm/*schema-atom* (atom {})]
          (is (nil? (fdm/resolve-canonical-kw
                     "http://hl7.org/fhir/StructureDefinition/Patient"))))))))
