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
