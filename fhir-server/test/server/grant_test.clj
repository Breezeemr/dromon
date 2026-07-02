(ns server.grant-test
  (:require [clojure.test :refer [deftest is testing]]
            [server.grant :as grant]))

(deftest grant-tuples-shape
  (let [tuples (grant/grant-tuples "client-1" ["pa" "pb"] ["read"])]
    (testing "launch tuple per patient"
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "launch" :subject_id "client-1"}) tuples))
      (is (some #(= % {:namespace "fhir" :object "Patient/pb"
                       :relation "launch" :subject_id "client-1"}) tuples)))
    (testing "instance-level read per patient"
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "read" :subject_id "client-1"}) tuples)))
    (testing "type-level read on compartment member types"
      (is (some #(= % {:namespace "fhir" :object "Observation"
                       :relation "read" :subject_id "client-1"}) tuples))
      (is (some #(= % {:namespace "fhir" :object "Condition"
                       :relation "read" :subject_id "client-1"}) tuples)))
    (testing "no type-level Patient tuple (instance-confined)"
      (is (not-any? #(= (:object %) "Patient") tuples)))
    (testing "no write relations unless requested"
      (is (not-any? #(= (:relation %) "write") tuples)))))

(defn- hook-payload [& {:keys [client-id scopes patient]}]
  (cond-> {:request (cond-> {:client_id client-id
                             :granted_scopes (or scopes [])}
                      patient (assoc :payload {:patient patient}))}
    true identity))

(deftest resolve-launch-patient-explicit-request
  (testing "launch/patient.<id> scope selects the launch patient"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            (hook-payload :client-id "c1"
                          :scopes ["launch/patient.pa" "patient/*.read"])
            (constantly ["pa" "pb"])
            (fn [subject pid] (and (= subject "c1") (= pid "pa")))))))
  (testing "launch scope for an ungranted patient denies issuance"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1"
                              :scopes ["launch/patient.px" "patient/*.read"])
                (constantly ["pa"])
                (constantly false)))))
  (testing "explicit granted patient is injected"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            (hook-payload :client-id "c1" :scopes ["patient/*.read"] :patient "pa")
            (constantly ["pa" "pb"])
            (fn [subject pid] (and (= subject "c1") (= pid "pa")))))))
  (testing "explicit ungranted patient denies issuance"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1" :scopes ["patient/*.read"] :patient "px")
                (constantly ["pa"])
                (constantly false)))))
  (testing "array-valued form param is unwrapped"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            {:request {:client_id "c1" :granted_scopes ["patient/*.read"]
                       :payload {:patient ["pa"]}}}
            (constantly ["pa"])
            (constantly true))))))

(deftest resolve-launch-patient-implicit
  (testing "single granted patient is used when none requested"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            (hook-payload :client-id "c1" :scopes ["patient/*.read"])
            (constantly ["pa"])
            (constantly true)))))
  (testing "multiple granted patients without explicit request denies"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1" :scopes ["patient/*.read"])
                (constantly ["pa" "pb"])
                (constantly true)))))
  (testing "no grants at all denies a patient-scoped token"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1" :scopes ["patient/*.read"])
                (constantly [])
                (constantly true))))))

(deftest resolve-launch-patient-unscoped
  (testing "non-patient-scoped tokens pass through without a claim"
    (is (= {} (grant/resolve-launch-patient
               (hook-payload :client-id "c1" :scopes ["user/*.read"])
               (constantly ["pa" "pb"])
               (constantly true))))
    (is (= {} (grant/resolve-launch-patient
               (hook-payload :client-id "c1" :scopes [])
               (constantly [])
               (constantly false))))))
