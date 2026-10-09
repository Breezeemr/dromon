(ns fhir-store.mock.search-test
  "The search shapes furl's eRx tests registered by hand against pyr's mock
   client, evaluated by the mock store itself. The registries are the ones
   fhir-server builds for the Breeze capability (printed from
   `server.core/resolve-schemas`), so the columns are the real ones: List
   `code` is a single CodeableConcept, Observation `date` is `effective[x]`,
   MedicationRequest `patient` is `subject`. test-server's
   mock-search-registry-test fails when a copy drifts from the built one."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as protocol]))

(def ^:private tenant "realm")

(def ^:private identifier-param
  {:type "token" :target nil
   :columns [{:col "identifier" :fhir-type "Identifier" :array? true}]})

(def ^:private registries
  {:Organization {"identifier" identifier-param}
   :PractitionerRole {"active" {:type "token" :target nil
                                :columns [{:col "active" :fhir-type "boolean" :array? false}]}
                      "identifier" identifier-param}
   :List {"code" {:type "token" :target nil
                  :columns [{:col "code" :fhir-type "CodeableConcept" :array? false}]}
          "encounter" {:type "reference" :target ["Encounter"]
                       :columns [{:col "encounter" :fhir-type "Reference" :array? false}]}
          "date" {:type "date" :target nil
                  :columns [{:col "date" :fhir-type "dateTime" :array? false}]}}
   :Observation {"subject" {:type "reference"
                            :target ["Practitioner" "Group" "Organization" "Device" "Medication"
                                     "Patient" "Procedure" "Substance" "Location"]
                            :columns [{:col "subject" :fhir-type "Reference" :array? false}]}
                 "code" {:type "token" :target nil
                         :columns [{:col "code" :fhir-type "CodeableConcept" :array? false}]}
                 "date" {:type "date" :target nil
                         :columns [{:fhir-type "dateTime" :array? false :col "effectiveDateTime"}
                                   {:fhir-type "Period" :array? false :col "effectivePeriod"}
                                   {:fhir-type "instant" :array? false :col "effectiveInstant"}]}}
   :MedicationRequest {"patient" {:type "reference" :target ["Patient"]
                                  :columns [{:col "subject" :fhir-type "Reference" :array? false}]}
                       "status" {:type "token" :target nil
                                 :columns [{:col "status" :fhir-type "code" :array? false}]}
                       "identifier" identifier-param}
   :Location {"organization" {:type "reference" :target ["Organization"]
                              :columns [{:col "managingOrganization" :fhir-type "Reference"
                                         :array? false}]}}
   :Patient {"phone" {:type "token" :target nil
                      :columns [{:col "telecom" :fhir-type "ContactPoint" :array? true
                                 :fixed {:system "phone"}}]}
             "name" {:type "string" :target nil
                     :columns [{:col "name" :fhir-type "HumanName" :array? true}]}
             "family" {:type "string" :target nil
                       :columns [{:col "name" :fhir-type "HumanName" :array? true
                                  :sub-col "family"}]}}})

(defn- ids
  "Ids `params` finds, in result order. `registry?` false searches with no
   registry, the field-name fallback."
  ([store rt params] (ids store rt params true))
  ([store rt params registry?]
   (mapv :id (protocol/search store tenant rt params (when registry? (get registries rt))))))

(defn- seeded [& resources]
  (let [store (mock/create-mock-store {})]
    (doseq [r resources]
      (protocol/create-resource store tenant (keyword (:resourceType r)) (:id r) r))
    store))

(def ^:private spi "http://localhost/Surescripts/SPI")
(def ^:private message-id "http://localhost/Surescripts/MessageID")
(def ^:private cancel-message-id "http://localhost/Surescripts/CancelMessageID")

(deftest identifier-system-value
  (let [store (seeded {:resourceType "MedicationRequest" :id "mr1"
                       :identifier [{:system message-id :value "M1"}]}
                      {:resourceType "MedicationRequest" :id "mr2"
                       :identifier [{:system cancel-message-id
                                     :value "M1"}]}
                      {:resourceType "MedicationRequest" :id "mr3"
                       :identifier [{:value "M1"}]})]
    (testing "system|value constrains both"
      (is (= ["mr1"] (ids store :MedicationRequest {"identifier" (str message-id "|M1")}))))
    (testing "a bare value matches under any system"
      (is (= ["mr1" "mr2" "mr3"] (ids store :MedicationRequest {"identifier" "M1"}))))
    (testing "|value matches only an identifier with no system"
      (is (= ["mr3"] (ids store :MedicationRequest {"identifier" "|M1"}))))
    (testing "system| matches any value under the system"
      (is (= ["mr1"] (ids store :MedicationRequest {"identifier" (str message-id "|")}))))
    (testing "a comma ORs two system|value tokens"
      (is (= ["mr1" "mr2"]
             (ids store :MedicationRequest
                  {"identifier" (str message-id "|M1," cancel-message-id "|M1")}))))
    (testing "the fallback (no registry) evaluates the same token"
      (is (= ["mr1"] (ids store :MedicationRequest {"identifier" (str message-id "|M1")} false))))
    (testing "a value no identifier holds finds nothing"
      (is (= [] (ids store :MedicationRequest {"identifier" (str message-id "|M2")}))))))

(deftest list-encounter-bare-id-and-single-codeable-concept
  (let [allergies {:coding [{:system "http://localhost/list" :code "allergies"}]}
        store (seeded {:resourceType "List" :id "l1" :code allergies
                       :encounter {:reference "Encounter/e1"}}
                      {:resourceType "List" :id "l2"
                       :code {:coding [{:system "http://localhost/list" :code "medications"}]}
                       :encounter {:reference "Encounter/e1"}}
                      {:resourceType "List" :id "l3" :code allergies
                       :encounter {:reference "Encounter/e2"}})]
    (testing "encounter=<bare id>&code=allergies"
      (is (= ["l1"] (ids store :List {"encounter" "e1" "code" "allergies"}))))
    (testing "Type/id and an absolute URL name the same encounter"
      (is (= ["l1"] (ids store :List {"encounter" "Encounter/e1" "code" "allergies"})))
      (is (= ["l1"] (ids store :List {"encounter" "https://api.example.com/realm/fhir/Encounter/e1"
                                      "code" "allergies"}))))
    (testing "a reference to another type with the same id does not match"
      (is (= [] (ids store :List {"encounter" "Patient/e1"}))))
    (testing "system|code on the single CodeableConcept"
      (is (= ["l1" "l3"] (ids store :List {"code" "http://localhost/list|allergies"}))))
    (testing "the fallback matches the single CodeableConcept too"
      (is (= ["l1"] (ids store :List {"encounter" "e1" "code" "allergies"} false))))))

(deftest list-count-sort-date-code
  (let [problems {:coding [{:system "http://snomed.info/sct" :code "182836005"}]}
        store (seeded {:resourceType "List" :id "old" :code problems :date "2024-01-02T10:00:00Z"}
                      {:resourceType "List" :id "new" :code problems :date "2025-06-01T10:00:00Z"}
                      {:resourceType "List" :id "none" :code problems}
                      {:resourceType "List" :id "other" :date "2023-01-01T00:00:00Z"
                       :code {:coding [{:code "allergies"}]}})]
    (testing "_count=1&_sort=date&code=182836005 is the earliest dated list"
      (is (= ["old"] (ids store :List {"_count" "1" "_sort" "date" "code" "182836005"}))))
    (testing "-date puts the latest first; undated sorts last either way"
      (is (= ["new" "old" "none"] (ids store :List {"_sort" "-date" "code" "182836005"})))
      (is (= ["old" "new" "none"] (ids store :List {"_sort" "date" "code" "182836005"}))))))

(deftest observation-subject-code-sort-by-effective
  (let [height {:coding [{:system "http://loinc.org" :code "8302-2"}]}
        store (seeded {:resourceType "Observation" :id "o-late" :code height
                       :subject {:reference "Patient/p1"} :effectiveDateTime "2025-03-01"}
                      {:resourceType "Observation" :id "o-early" :code height
                       :subject {:reference "Patient/p1"}
                       :effectivePeriod {:start "2024-01-01" :end "2024-01-02"}}
                      {:resourceType "Observation" :id "o-other-patient" :code height
                       :subject {:reference "Patient/p2"} :effectiveDateTime "2020-01-01"}
                      {:resourceType "Observation" :id "o-weight"
                       :code {:coding [{:system "http://loinc.org" :code "29463-7"}]}
                       :subject {:reference "Patient/p1"} :effectiveDateTime "2019-01-01"}
                      ;; A field named like the parameter must not be what is sorted by.
                      {:resourceType "Observation" :id "o-decoy" :code height
                       :subject {:reference "Patient/p1"} :date "1900-01-01"
                       :effectiveDateTime "2026-01-01"})]
    (testing "subject=<bare id>&code=8302-2&_sort=date&_count=1 sorts by effective[x]"
      (is (= ["o-early"] (ids store :Observation {"subject" "p1" "code" "8302-2"
                                                  "_sort" "date" "_count" "1"}))))
    (testing "every match, ascending by effective[x]"
      (is (= ["o-early" "o-late" "o-decoy"]
             (ids store :Observation {"subject" "p1" "code" "8302-2" "_sort" "date"}))))
    (testing "date filters with prefixes over dateTime and Period"
      (is (= ["o-late" "o-decoy"]
             (ids store :Observation {"subject" "p1" "code" "8302-2" "date" "ge2025"})))
      (is (= ["o-early"] (ids store :Observation {"subject" "p1" "date" "2024-01"}))))))

(deftest practitioner-role-active-and-spi
  (let [store (seeded {:resourceType "PractitionerRole" :id "r1" :active true
                       :identifier [{:system spi :value "123"}]}
                      {:resourceType "PractitionerRole" :id "r2" :active false
                       :identifier [{:system spi :value "123"}]}
                      {:resourceType "PractitionerRole" :id "r3" :active true
                       :identifier [{:system spi :value "456"}]})]
    (testing "active=true&identifier=<SPI system>|<value>"
      (is (= ["r1"] (ids store :PractitionerRole {"active" "true" "identifier" (str spi "|123")}))))
    (testing "active=false"
      (is (= ["r2"] (ids store :PractitionerRole {"active" "false"}))))
    (testing "the fallback compares a boolean by its string form"
      (is (= ["r1" "r3"] (ids store :PractitionerRole {"active" "true"} false))))))

(deftest medication-request-patient-and-status-or
  (let [store (seeded {:resourceType "MedicationRequest" :id "a" :status "active"
                       :subject {:reference "Patient/p1"}}
                      {:resourceType "MedicationRequest" :id "u" :status "unknown"
                       :subject {:reference "Patient/p1"}}
                      {:resourceType "MedicationRequest" :id "s" :status "stopped"
                       :subject {:reference "Patient/p1"}}
                      {:resourceType "MedicationRequest" :id "x" :status "active"
                       :subject {:reference "Patient/p2"}})]
    (testing "patient=<bare id>&status=active,unknown"
      (is (= ["a" "u"] (ids store :MedicationRequest {"patient" "p1" "status" "active,unknown"}))))
    (testing "patient=Patient/<id>"
      (is (= ["a" "u" "s"] (ids store :MedicationRequest {"patient" "Patient/p1"}))))
    (testing "a vector is the parameter repeated: an AND of its values"
      (is (= [] (ids store :MedicationRequest {"status" ["active" "unknown"]})))
      (is (= ["a" "u"] (ids store :MedicationRequest {"status" ["active,unknown" "active,unknown,stopped"]
                                                      "patient" "p1"}))))
    (testing "keyword parameter names are the same parameters"
      (is (= ["a"] (ids store :MedicationRequest {:patient "p1" :status "active"}))))))

(deftest location-managing-organization
  (let [store (seeded {:resourceType "Location" :id "loc1"
                       :managingOrganization {:reference "Organization/o1"}}
                      {:resourceType "Location" :id "loc2"
                       :managingOrganization {:reference "Organization/o2"}})]
    (testing "furl's managingOrganization=Organization/x, which no registry declares, by the field"
      (is (= ["loc1"] (ids store :Location {"managingOrganization" "Organization/o1"}))))
    (testing "the declared organization parameter over the same column"
      (is (= ["loc2"] (ids store :Location {"organization" "o2"}))))))

(deftest verification-result-status-and-absolute-target
  (let [store (seeded {:resourceType "VerificationResult" :id "v1" :status "attested"
                       :target [{:reference "Practitioner/pr1"}]}
                      {:resourceType "VerificationResult" :id "v2" :status "in-process"
                       :target [{:reference "Practitioner/pr1"}]}
                      {:resourceType "VerificationResult" :id "v3" :status "attested"
                       :target [{:reference "Practitioner/pr2"}]})]
    (testing "status=attested&target=<absolute url>, with no registry for the type"
      (is (= ["v1"] (ids store :VerificationResult
                         {"status" "attested"
                          "target" "https://localhost/fhir/realm/fhir/Practitioner/pr1"}
                         false))))
    (testing "the same with an R4 registry"
      (let [registry {"status" {:type "token" :columns [{:col "status" :fhir-type "code"}]}
                      "target" {:type "reference" :target nil
                                :columns [{:col "target" :fhir-type "Reference" :array? true}]}}]
        (is (= ["v1"] (mapv :id (protocol/search store tenant :VerificationResult
                                                 {"status" "attested"
                                                  "target" "https://x.example/fhir/Practitioner/pr1"}
                                                 registry))))))))

(deftest id-comma-or-and-furl-id-parameter
  (let [store (seeded {:resourceType "Organization" :id "o1"}
                      {:resourceType "Organization" :id "o2"}
                      {:resourceType "Organization" :id "o3"})]
    (is (= ["o1" "o3"] (ids store :Organization {"_id" "o1,o3"})))
    (testing "furl's id parameter, by the field"
      (is (= ["o2"] (ids store :Organization {"id" "o2"})))
      (is (= ["o2" "o3"] (ids store :Organization {"id" "o2,o3"}))))))

(deftest fixed-system-and-strings
  (let [store (seeded {:resourceType "Patient" :id "p1"
                       :name [{:family "Smith" :given ["Ann"]}]
                       :telecom [{:system "phone" :value "555"} {:system "email" :value "a@x"}]}
                      {:resourceType "Patient" :id "p2"
                       :name [{:family "Smithers"}]
                       :telecom [{:system "fax" :value "555"}]})]
    (testing "the registry's :fixed system narrows the ContactPoint"
      (is (= ["p1"] (ids store :Patient {"phone" "555"}))))
    (testing "string: case-insensitive starts-with, :exact, :contains"
      (is (= ["p1" "p2"] (ids store :Patient {"family" "smith"})))
      (is (= ["p1"] (ids store :Patient {"family:exact" "Smith"})))
      (is (= ["p2"] (ids store :Patient {"name:contains" "ther"})))
      (is (= ["p1"] (ids store :Patient {"name" "ann"}))))
    (testing ":missing"
      (is (= ["p2"] (ids store :Patient {"phone:missing" "true"})) "a fax is not a phone")
      (is (= ["p1" "p2"] (ids store :Patient {"family:missing" "false"}))))))

(deftest comma-or-on-references-as-revinclude-sends-them
  (let [store (seeded {:resourceType "MedicationRequest" :id "a" :subject {:reference "Patient/p1"}}
                      {:resourceType "MedicationRequest" :id "b" :subject {:reference "Patient/p2"}}
                      {:resourceType "MedicationRequest" :id "c" :subject {:reference "Patient/p3"}})]
    (is (= ["a" "c"] (ids store :MedicationRequest {"patient" "Patient/p1,Patient/p3"})))))

(deftest meta-parameters
  (let [erx "http://localhost/fhir/StructureDefinition/test-medicationrequest"
        store (seeded {:resourceType "MedicationRequest" :id "a"
                       :meta {:profile [erx]
                              :tag [{:system "http://localhost/tags" :code "erx"}]}}
                      {:resourceType "MedicationRequest" :id "b"})]
    (is (= ["a"] (ids store :MedicationRequest {"_tag" "http://localhost/tags|erx"})))
    (is (= ["a"] (ids store :MedicationRequest {"_profile" erx})))
    (is (= ["a" "b"] (ids store :MedicationRequest {"_lastUpdated" "gt2000-01-01"})))))
