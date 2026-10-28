(ns fhir-store.mock.wire-form-test
  "A resource written as the router hands it over, decoded by the FHIR JSON
   transformer, reads back in the wire form the Datomic and XTDB stores answer
   with."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [com.breezeehr.fhir-primitives :as fprim]
            [com.breezeehr.fhir-json-transform :as fjt]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as protocol]))

(def ^:private patient-schema
  [:map
   [:resourceType :string]
   [:birthDate {:optional true} :time/local-date]
   [:deceasedDateTime {:optional true} :time/offset-date-time]])

(def ^:private schema
  (m/schema
   [:map
    [:resourceType :string]
    [:id {:optional true} :string]
    [:authoredOn :time/offset-date-time]
    [:issued :time/instant]
    [:startYear :time/year]
    [:startMonth :time/year-month]
    [:at :time/local-time]
    [:token :uuid]
    [:quantity :double]
    [:contained [:vector patient-schema]]]
   fprim/fhir-registry-options))

(def ^:private wire
  {:resourceType "MedicationRequest"
   :authoredOn "2015-06-22T10:00:00-05:00"
   :issued "2015-06-22T15:00:00.123Z"
   :startYear "2015"
   :startMonth "2015-06"
   :at "10:30:00"
   :token "urn:uuid:6f1c2d3e-0000-4000-8000-000000000001"
   :quantity 22.5
   :contained [{:resourceType "Patient"
                :birthDate "1990-01-01"
                :deceasedDateTime "2020-02-03T04:05:06Z"}]})

(defn- decoded
  "`wire` as the router's request coercion hands it to a store."
  []
  (m/decode schema wire (fjt/fhir-json-transformer)))

(defn- body [resource] (dissoc resource :id :meta))

(deftest the-decoded-form-is-what-a-write-receives
  (let [d (decoded)]
    (is (instance? java.time.LocalDate (get-in d [:contained 0 :birthDate])))
    (is (instance? java.time.OffsetDateTime (:authoredOn d)))
    (is (instance? java.util.UUID (:token d)))))

(deftest a-decoded-write-reads-back-in-wire-form
  (let [store (mock/create-mock-store {})
        tenant "wire-form"]
    (testing "create, read and vread"
      (let [created (protocol/create-resource store tenant "MedicationRequest" "m1" (decoded))
            vid (get-in created [:meta :versionId])]
        (is (= wire (body created)) "the write's own answer")
        (is (= wire (body (protocol/read-resource store tenant "MedicationRequest" "m1"))))
        (is (= wire (body (protocol/vread-resource store tenant "MedicationRequest" "m1" vid))))))
    (testing "update, the contained Patient's birthDate included"
      (protocol/update-resource store tenant "MedicationRequest" "m1" (decoded))
      (let [read (protocol/read-resource store tenant "MedicationRequest" "m1")]
        (is (= "1990-01-01" (get-in read [:contained 0 :birthDate])))
        (is (= wire (body read)))))
    (testing "a transaction Bundle's PUT"
      (protocol/transact-transaction store tenant
                                     [{:request {:method "PUT" :url "MedicationRequest/m2"}
                                       :resource (decoded)}])
      (is (= wire (body (protocol/read-resource store tenant "MedicationRequest" "m2")))))
    (testing "the wire form is what the transformer encodes the decoded form back to"
      (is (= wire (m/encode schema (decoded) (fjt/fhir-json-transformer)))))
    (testing "meta.lastUpdated stays the Instant the mock stamps"
      (is (instance? java.time.Instant
                     (get-in (protocol/read-resource store tenant "MedicationRequest" "m1")
                             [:meta :lastUpdated]))))))
