(ns com.breezeehr.fhir-json-transform-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.transform :as mt]
            [com.breezeehr.fhir-json-transform :as fjt]
            [org.hl7.fhir.us.core.StructureDefinition.us-core-patient.v8-0-1 :as us-core-patient])
  (:import (java.time LocalDate OffsetDateTime)))

(def sample-patient-fhir-json
  "The same patient as parsed directly from FHIR JSON — dates are strings,
   extensions live in :extension arrays with :url/:valueCoding/:valueString."
  {:resourceType "Patient"
   :id "example-1"
   :meta {:profile ["http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient|8.0.1"]}
   :extension [{:url       "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race"
                :extension [{:url         "ombCategory"
                             :valueCoding {:system  "urn:oid:2.16.840.1.113883.6.238"
                                           :code    "2106-3"
                                           :display "White"}}
                            {:url         "ombCategory"
                             :valueCoding {:system  "urn:oid:2.16.840.1.113883.6.238"
                                           :code    "1002-5"
                                           :display "American Indian or Alaska Native"}}
                            {:url         "text"
                             :valueString "White, American Indian"}]}
               {:url       "http://hl7.org/fhir/us/core/StructureDefinition/us-core-ethnicity"
                :extension [{:url         "ombCategory"
                             :valueCoding {:system  "urn:oid:2.16.840.1.113883.6.238"
                                           :code    "2135-2"
                                           :display "Hispanic or Latino"}}
                            {:url         "detailed"
                             :valueCoding {:system  "urn:oid:2.16.840.1.113883.6.238"
                                           :code    "2184-0"
                                           :display "Dominican"}}
                            {:url         "text"
                             :valueString "Hispanic or Latino"}]}]
   :identifier [{:system "http://hospital.example.org"
                 :value  "12345"}]
   :name [{:family "Smith"
           :given  ["John" "Jacob"]}]
   :gender "male"
   :birthDate "1980-01-15"
   :deceasedDateTime "2025-03-20T14:30:00-05:00"
   :address [{:line       ["123 Main St"]
              :city       "Springfield"
              :state      "IL"
              :postalCode "62704"
              :country    "US"}]})

(def sample-patient
  "A US Core Patient with race, ethnicity, birthDate and deceasedDateTime filled in."
  {:resourceType "Patient"
   :id "example-1"
   :meta {:profile ["http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient|8.0.1"]}
   :identifier [{:system "http://hospital.example.org"
                 :value "12345"}]
   :name [{:family "Smith"
           :given ["John" "Jacob"]}]
   :gender "male"
   :birthDate (LocalDate/of 1980 1 15)
   :deceasedDateTime (OffsetDateTime/parse "2025-03-20T14:30:00-05:00")
   :address [{:line       ["123 Main St"]
              :city       "Springfield"
              :state      "IL"
              :postalCode "62704"
              :country    "US"}]
   :race {:url         "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race"
          :ombCategory [{:system  "urn:oid:2.16.840.1.113883.6.238"
                         :code    "2106-3"
                         :display "White"}
                        {:system  "urn:oid:2.16.840.1.113883.6.238"
                         :code    "1002-5"
                         :display "American Indian or Alaska Native"}]
          :text        {:url         "text"
                        :valueString "White, American Indian"}}
   :ethnicity {:url         "http://hl7.org/fhir/us/core/StructureDefinition/us-core-ethnicity"
               :ombCategory [{:system  "urn:oid:2.16.840.1.113883.6.238"
                              :code    "2135-2"
                              :display "Hispanic or Latino"}]
               :detailed    [{:system  "urn:oid:2.16.840.1.113883.6.238"
                              :code    "2184-0"
                              :display "Dominican"}]
               :text        {:url         "text"
                             :valueString "Hispanic or Latino"}}})


(deftest patient-validates-test
  (testing "sample patient validates against US Core Patient schema"
    (let [valid? (m/validate us-core-patient/full-sch sample-patient)]
      (is (= valid? true))
      (when-not valid?
        (is (= nil (:errors (m/explain us-core-patient/full-sch sample-patient))))))))

(deftest fhir-json-transformer-decodes-patient-test
  (let [transformer (fjt/fhir-json-transformer)
        decoded     (m/decode us-core-patient/full-sch sample-patient transformer)]

    (testing "birthDate decoded to LocalDate"
      (is (instance? LocalDate (:birthDate decoded)))
      (is (= (LocalDate/of 1980 1 15) (:birthDate decoded))))

    (testing "deceasedDateTime decoded to OffsetDateTime"
      (is (instance? OffsetDateTime (:deceasedDateTime decoded)))
      (is (= "2025-03-20T14:30-05:00" (str (:deceasedDateTime decoded)))))

    (testing "string fields remain strings"
      (is (string? (:id decoded)))
      (is (= "male" (:gender decoded)))
      (is (= "Smith" (-> decoded :name first :family))))

    (testing "race extension preserved"
      (is (= "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race"
             (-> decoded :race :url)))
      (is (= 2 (count (-> decoded :race :ombCategory))))
      (is (= "White" (-> decoded :race :ombCategory first :display))))

    (testing "ethnicity extension preserved"
      (is (= "Hispanic or Latino"
             (-> decoded :ethnicity :ombCategory first :display)))
      (is (= "Dominican"
             (-> decoded :ethnicity :detailed first :display))))))

(deftest fhir-json-decode-round-trip-test
  (testing "decoding FHIR JSON produces the Clojure-native representation"
    (let [decoded (m/decode us-core-patient/full-sch sample-patient-fhir-json
                           (fjt/fhir-json-transformer))]
      (is (= sample-patient decoded)))))

(deftest fhir-json-encode-test
  (testing "encoding Clojure-native produces FHIR JSON wire format"
    (let [encoded (m/encode us-core-patient/full-sch sample-patient
                            (fjt/fhir-json-transformer))]
      (is (= sample-patient-fhir-json encoded)))))

(deftest fhir-json-round-trip-test
  (testing "decode then encode is identity (FHIR JSON → Clojure → FHIR JSON)"
    (let [transformer (fjt/fhir-json-transformer)
          decoded (m/decode us-core-patient/full-sch sample-patient-fhir-json transformer)
          re-encoded (m/encode us-core-patient/full-sch decoded transformer)]
      (is (= sample-patient-fhir-json re-encoded))))

  (testing "encode then decode is identity (Clojure → FHIR JSON → Clojure)"
    (let [transformer (fjt/fhir-json-transformer)
          encoded (m/encode us-core-patient/full-sch sample-patient transformer)
          re-decoded (m/decode us-core-patient/full-sch encoded transformer)]
      (is (= sample-patient re-decoded)))))

;; ---------------------------------------------------------------------------
;; Extension slice cardinality
;;
;; A slice's Clojure-native shape follows its schema: a [:sequential ...] child
;; holds a vector of bare values, a scalar child holds a single one. Slices
;; whose extension has a complex value (Reference, CodeableConcept, Address)
;; carry a :fhir/value-key just as primitive-valued ones do, so both fold.
;; ---------------------------------------------------------------------------

(def reference-sch
  [:map {:closed true} [:reference {:optional true} :string]])

(def sliced-sch
  "Two Reference-valued extension slices on one resource: one 0..1, one 0..*."
  [:map {:closed true}
   [:resourceType :string]
   [:extension {:optional true} [:sequential [:map]]]
   [:lockedTo {:optional       true
               :fhir/extension true
               :url            "https://example.org/locked-to"
               :fhir/value-key :valueReference}
    reference-sch]
   [:roleLocation {:optional       true
                   :fhir/extension true
                   :url            "https://example.org/role-location"
                   :fhir/value-key :valueReference}
    [:sequential reference-sch]]])

(def sliced-fhir-json
  {:resourceType "Practitioner"
   :extension    [{:url            "https://example.org/locked-to"
                   :valueReference {:reference "Organization/o1"}}
                  {:url            "https://example.org/role-location"
                   :valueReference {:reference "Location/l1"}}
                  {:url            "https://example.org/role-location"
                   :valueReference {:reference "Location/l2"}}]})

(deftest extension-slice-cardinality-decode-test
  (let [decoded (m/decode sliced-sch sliced-fhir-json (fjt/fhir-json-transformer))]
    (testing "a 0..1 slice folds to a bare value, not a one-element vector"
      (is (= {:reference "Organization/o1"} (:lockedTo decoded))))
    (testing "a 0..* slice folds to a vector of bare values"
      (is (= [{:reference "Location/l1"} {:reference "Location/l2"}]
             (:roleLocation decoded))))
    (testing "consumed entries leave no :extension remainder"
      (is (nil? (:extension decoded))))
    (testing "the folded result validates against the profile declaring the slices"
      (is (true? (m/validate sliced-sch decoded))))))

(deftest extension-slice-cardinality-encode-test
  (testing "a scalar slice unfolds to one entry, not one per key of its value"
    (let [decoded (m/decode sliced-sch sliced-fhir-json (fjt/fhir-json-transformer))
          encoded (m/encode sliced-sch decoded (fjt/fhir-json-transformer))]
      (is (= sliced-fhir-json encoded)))))

(deftest complex-extension-entry-preserved-test
  (testing "a slice with no :fhir/value-key keeps its whole entry"
    (let [sch     [:map {:closed true}
                   [:resourceType :string]
                   [:nested {:optional       true
                             :fhir/extension true
                             :url            "https://example.org/nested"}
                    [:map {:closed true}
                     [:url :string]
                     [:extension [:sequential [:map]]]]]]
          entry   {:url       "https://example.org/nested"
                   :extension [{:url "part" :valueString "x"}]}
          decoded (m/decode sch {:resourceType "Basic" :extension [entry]}
                            (fjt/fhir-json-transformer))]
      (is (= entry (:nested decoded)))
      (is (true? (m/validate sch decoded))))))
