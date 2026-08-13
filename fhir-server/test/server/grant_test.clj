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

(defn- authorization-code-payload
  "The hook payload shape Hydra v2.2.0 sends when exchanging an
   authorization code: the end-user subject only inside the id_token
   session and an EMPTY request.granted_scopes."
  [subject client-id]
  {:session {:id_token {:subject subject
                        :id_token_claims {:sub subject}}
             :client_id client-id
             :consent_challenge "ch-1"}
   :request {:client_id client-id
             :granted_scopes []
             :granted_audience []
             :grant_types ["authorization_code"]
             :payload {}}})

(deftest resolve-launch-patient-authorization-code
  (testing "single launch grant injects the patient for interactive tokens"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            (authorization-code-payload "user-1" "c1")
            (constantly ["pa"])
            (constantly true)))))
  (testing "no launch grant issues without patient context (consent-side
            linkage already fail-closed patient-scoped grants)"
    (is (= {} (grant/resolve-launch-patient
               (authorization-code-payload "user-1" "c1")
               (constantly [])
               (constantly true)))))
  (testing "ambiguous grants issue without patient context"
    (is (= {} (grant/resolve-launch-patient
               (authorization-code-payload "user-1" "c1")
               (constantly ["pa" "pb"])
               (constantly true)))))
  (testing "client_credentials fail-closed behavior is unchanged"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1" :scopes ["patient/*.read"])
                (constantly [])
                (constantly true))))))

(deftest resolve-first-party-claims-shape
  (let [role-tuples [{:object "pgoa/physcian"} {:object "pgoa/admin"}
                     {:object "demo/admin"} {:object "no-practitioner/admin"}]
        practitioner-tuples [{:object "pgoa/9f9e6bd2-0000-4000-8000-000000000001"}
                             {:object "demo/aaaa6bd2-0000-4000-8000-000000000002"}
                             {:object "no-role-realm/bbbb6bd2-0000-4000-8000-000000000003"}]
        claims (grant/resolve-first-party-claims role-tuples practitioner-tuples)]
    (is (= ["demo" "pgoa"] (:realms claims))
        "realms are the role/practitioner intersection, mast's legacy rule")
    (is (= {"pgoa" ["admin" "physcian"] "demo" ["admin"]
            "no-practitioner" ["admin"]}
           (:roles claims))
        "roles keep every granted realm; the intersection only shapes :realms")
    (is (= {"pgoa" "9f9e6bd2-0000-4000-8000-000000000001"
            "demo" "aaaa6bd2-0000-4000-8000-000000000002"
            "no-role-realm" "bbbb6bd2-0000-4000-8000-000000000003"}
           (:practitioners claims)))))

(deftest resolve-first-party-claims-edge-shapes
  (is (= {:realms [] :roles {} :practitioners {}}
         (grant/resolve-first-party-claims [] []))
      "a subject with no relations gets empty claims, not a denial")
  (is (= {:realms [] :roles {"pgoa" ["admin"]} :practitioners {}}
         (grant/resolve-first-party-claims [{:object "pgoa/admin"}] []))
      "roles without a practitioner identity grant no realm")
  (is (= {:realms [] :roles {} :practitioners {}}
         (grant/resolve-first-party-claims [{:object "malformed"}] [{:object nil}]))
      "objects without a realm/value split are ignored"))
