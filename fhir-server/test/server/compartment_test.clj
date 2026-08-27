(ns server.compartment-test
  "Tests for R4B patient-compartment row-level enforcement: helpers, the union
   query construction, the CompartmentFilteringStore decorator, and the
   wrap-patient-compartment middleware."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.compartment :as compartment]
            [server.handlers :as handlers]
            [server.scope :as scope])
  (:import [server.compartment CompartmentFilteringStore]))

(def ^:private tenant "default")

;; A minimal registry mirroring how the real search registry resolves the R4B
;; Patient-compartment link params for Observation: `subject` and `performer`.
(def ^:private registries
  {"Observation" {"subject"   {:type "reference" :columns [{:col "subject"}]}
                  "performer" {:type "reference" :columns [{:col "performer" :array? true}]}}})

(defn- obs [id patient-ref]
  {:resourceType "Observation" :id id :subject {:reference patient-ref}})

(defn- obs-performed-by [id patient-ref]
  {:resourceType "Observation" :id id :performer [{:reference patient-ref}]})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(deftest launch-patient-from-identity
  (is (= "123" (compartment/launch-patient {:identity {:patient "123"}})))
  (is (nil? (compartment/launch-patient {:identity {:sub "u"}}))))

(deftest member-detection
  (testing "Patient and its members are in the Patient compartment"
    (is (compartment/patient-compartment-member? "Patient"))
    (is (compartment/patient-compartment-member? "Observation"))
    (is (compartment/patient-compartment-member? "Condition")))
  (testing "unrelated types are not members"
    (is (not (compartment/patient-compartment-member? "Medication")))
    (is (not (compartment/patient-compartment-member? "Location")))))

(deftest r4b-link-params-are-faithful
  (testing "canonical R4B param names, including subject and multi-param unions"
    (is (= ["subject" "performer"] (compartment/compartment-link-params "Patient" "Observation")))
    (is (= ["patient" "asserter"] (compartment/compartment-link-params "Patient" "Condition")))
    (is (= ["subject"] (compartment/compartment-link-params "Patient" "MedicationRequest"))))
  (testing "Device and Coverage are not patient-compartment members via `patient`"
    (is (nil? (compartment/compartment-link-params "Patient" "Device")))
    (is (= ["policy-holder" "subscriber" "beneficiary" "payor"]
           (compartment/compartment-link-params "Patient" "Coverage")))))

(deftest descriptor-unions-all-registered-columns
  (testing "the synthetic descriptor spans every registered link param's columns"
    (let [desc (compartment/compartment-descriptor "Patient" "Observation" (get registries "Observation"))]
      (is (= "reference" (:type desc)))
      (is (= ["Patient"] (:target desc)))
      (is (= [{:col "subject"} {:col "performer" :array? true}] (:columns desc)))))
  (testing "a member type with no registered link param yields nil (caller fails closed)"
    (is (nil? (compartment/compartment-descriptor "Patient" "Observation" {})))))

(deftest confine-dispatch
  (let [reg (get registries "Observation")]
    (testing "owner type confines by _id"
      (is (= [:run {"_id" "123"} reg] (compartment/confine "Patient" "123" "Patient" {} reg))))
    (testing "member type injects the synthetic union param"
      (let [[tag params registry] (compartment/confine "Patient" "123" "Observation" {} reg)]
        (is (= :run tag))
        (is (= "Patient/123" (get params compartment/compartment-search-param)))
        (is (some? (get registry compartment/compartment-search-param)))))
    (testing "non-member type passes through"
      (is (= :passthrough (compartment/confine "Patient" "123" "Medication" {} reg))))
    (testing "member type with no registered link param is denied"
      (is (= :deny (compartment/confine "Patient" "123" "Observation" {} {}))))))

(deftest token-restriction-detection
  (testing "patient-only token is restricted"
    (is (compartment/token-patient-restricted? (scope/parse-scopes "patient/Observation.rs")))
    (is (compartment/token-patient-restricted? (scope/parse-scopes "patient/*.read"))))
  (testing "any user/system scope makes the token unrestricted"
    (is (not (compartment/token-patient-restricted? (scope/parse-scopes "user/*.read"))))
    (is (not (compartment/token-patient-restricted? (scope/parse-scopes "patient/Observation.rs user/Patient.read"))))
    (is (not (compartment/token-patient-restricted? (scope/parse-scopes "system/*.cruds")))))
  (testing "no resource scopes is not restricted"
    (is (not (compartment/token-patient-restricted? (scope/parse-scopes "openid profile"))))))

;; ---------------------------------------------------------------------------
;; CompartmentFilteringStore
;; ---------------------------------------------------------------------------

(defn- seeded-store []
  (let [store (mock/create-mock-store {})]
    (db/create-resource store tenant :Observation "obs-mine" (obs "obs-mine" "Patient/123"))
    (db/create-resource store tenant :Observation "obs-perf" (obs-performed-by "obs-perf" "Patient/123"))
    (db/create-resource store tenant :Observation "obs-other" (obs "obs-other" "Patient/999"))
    store))

(defn- fstore [base]
  (compartment/filtering-store base {:patient-id "123" :all-registries registries}))

(deftest search-confined-to-compartment-union
  (let [store (fstore (seeded-store))
        results (db/search store tenant :Observation {} (get registries "Observation"))]
    (testing "the union returns resources linked via subject OR performer, but not another patient's"
      (is (= #{"obs-mine" "obs-perf"} (set (map :id results)))))))

(deftest search-cannot-be-widened-by-client
  (let [store (fstore (seeded-store))
        ;; Client tries to target another patient. The compartment predicate is
        ;; ANDed with the client's filter, so this intersects to nothing — the
        ;; client can never reach Patient/999's data.
        results (db/search store tenant :Observation {"subject" "Patient/999"}
                          (get registries "Observation"))]
    (is (empty? results))))

(deftest count-confined-to-compartment
  (let [store (fstore (seeded-store))]
    (is (= 2 (db/count-resources store tenant :Observation {} (get registries "Observation"))))))

(deftest bulk-export-basis-methods-delegate-to-base
  (testing "current-basis / scan-type-as-of / count-as-of pass through to the
            base store unconfined: compartment confinement of the export scan
            is the export layer's responsibility, and a missing delegation
            would throw AbstractMethodError here"
    (let [store (fstore (seeded-store))
          basis (db/current-basis store tenant)]
      (is (instance? java.time.Instant (:system-time basis)))
      (is (= #{"obs-mine" "obs-perf" "obs-other"}
             (into #{} (map :id)
                   (db/scan-type-as-of store tenant :Observation basis))))
      (is (= 3 (db/count-as-of store tenant :Observation basis))))))

(deftest read-confined-to-compartment
  (let [store (fstore (seeded-store))]
    (testing "in-compartment instance is readable (subject link)"
      (is (= "obs-mine" (:id (db/read-resource store tenant :Observation "obs-mine")))))
    (testing "in-compartment instance is readable (performer link)"
      (is (= "obs-perf" (:id (db/read-resource store tenant :Observation "obs-perf")))))
    (testing "out-of-compartment instance reads as nil (handler -> 404)"
      (is (nil? (db/read-resource store tenant :Observation "obs-other"))))))

(deftest writes-confined-to-compartment
  (let [store (fstore (seeded-store))]
    (testing "creating a resource for another patient is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside the patient compartment"
            (db/create-resource store tenant :Observation "x" (obs "x" "Patient/999")))))
    (testing "creating a resource for the launch patient succeeds (subject)"
      (is (= "ok" (:id (db/create-resource store tenant :Observation "ok" (obs "ok" "Patient/123"))))))
    (testing "creating a resource where the patient is performer succeeds (union)"
      (is (= "okp" (:id (db/create-resource store tenant :Observation "okp" (obs-performed-by "okp" "Patient/123"))))))
    (testing "deleting an out-of-compartment resource is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside the patient compartment"
            (db/delete-resource store tenant :Observation "obs-other"))))
    (testing "deleting an in-compartment resource succeeds"
      (is (true? (db/delete-resource store tenant :Observation "obs-mine"))))))

(deftest cross-patient-write-rejection-carries-403
  (let [store (fstore (seeded-store))]
    (is (= 403 (try (db/create-resource store tenant :Observation "x" (obs "x" "Patient/999"))
                    (catch clojure.lang.ExceptionInfo e (:fhir/status (ex-data e))))))))

(deftest non-member-type-search-passes-through
  (let [base (mock/create-mock-store {})
        _    (db/create-resource base tenant :Medication "m1" {:resourceType "Medication" :id "m1"})
        store (fstore base)
        results (db/search store tenant :Medication {} nil)]
    (is (= ["m1"] (mapv :id results))
        "non-member types are linked context and are not filtered")))

;; ---------------------------------------------------------------------------
;; Real handlers driven through the wrapped store: the definitive
;; "cannot access another patient's resources" check.
;; ---------------------------------------------------------------------------

(defn- read-req [store id]
  {:fhir/store store :fhir/resource-type "Observation"
   :path-params {:tenant-id tenant :id id}})

(deftest real-read-handler-blocks-other-patient
  (let [store (fstore (seeded-store))]
    (testing "reading the launch patient's own resource succeeds"
      (let [resp (handlers/read-resource (read-req store "obs-mine"))]
        (is (= 200 (:status resp)))
        (is (= "obs-mine" (get-in resp [:body :id])))))
    (testing "reading another patient's resource is 404, not 200"
      (let [resp (handlers/read-resource (read-req store "obs-other"))]
        (is (= 404 (:status resp)))
        (is (nil? (get-in resp [:body :id])))))))

(deftest real-vread-handler-blocks-other-patient
  (let [store (fstore (seeded-store))
        vreq  (fn [id] (assoc-in (read-req store id) [:path-params :vid] "1"))]
    (is (= "obs-mine" (get-in (handlers/vread-resource (vreq "obs-mine")) [:body :id])))
    (is (= 404 (:status (handlers/vread-resource (vreq "obs-other")))))))

(deftest real-search-handler-returns-only-launch-patient
  (let [store (fstore (seeded-store))
        resp  (handlers/search-type
                {:fhir/store store
                 :fhir/resource-type "Observation"
                 :fhir/search-registry (get registries "Observation")
                 :fhir/all-registries registries
                 :path-params {:tenant-id tenant}
                 :query-params {}})]
    (is (= 200 (:status resp)))
    (is (= 2 (get-in resp [:body :total])))
    (is (= #{"obs-mine" "obs-perf"} (set (map (comp :id :resource) (get-in resp [:body :entry]))))
        "another patient's Observation never appears in the searchset")))

(deftest real-update-handler-blocks-cross-patient-write
  (let [store (fstore (seeded-store))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside the patient compartment"
          (db/update-resource store tenant :Observation "moved"
                              (obs "moved" "Patient/999"))))))

;; ---------------------------------------------------------------------------
;; wrap-patient-compartment middleware
;; ---------------------------------------------------------------------------

(defn- echo-store-handler [req] {:status 200 :body (:fhir/store req)})

(defn- base-req [scope-claim & {:keys [patient uri method id target-type store]
                                :or   {uri "/default/fhir/Observation" method :get}}]
  (cond-> {:identity {:sub "u" :scope scope-claim}
           :request-method method
           :uri uri
           :fhir/store (or store :original)
           :path-params (cond-> {:tenant-id tenant}
                          id (assoc :id id)
                          target-type (assoc :target-type target-type))
           :reitit.core/match {:data {:fhir/all-registries registries}}}
    patient (assoc-in [:identity :patient] patient)))

(defn- wrapped [] (compartment/wrap-patient-compartment echo-store-handler {}))

(deftest middleware-public-route-bypass
  (let [resp ((wrapped) {:reitit.core/match {:data {:public? true}} :fhir/store :original})]
    (is (= 200 (:status resp)))
    (is (= :original (:body resp)))))

(deftest middleware-unrestricted-token-not-wrapped
  (testing "user/system token passes through with the original store"
    (let [resp ((wrapped) (base-req "user/*.read" :patient "123"))]
      (is (= 200 (:status resp)))
      (is (= :original (:body resp))))))

(deftest middleware-requires-launch-patient
  (testing "patient-restricted token with no patient claim is forbidden"
    (let [resp ((wrapped) (base-req "patient/Observation.rs"))]
      (is (= 403 (:status resp)))
      (is (= "OperationOutcome" (get-in resp [:body :resourceType]))))))

(deftest middleware-denies-non-member-type
  (testing "patient/ scope cannot reach a type outside the Patient compartment"
    (let [resp ((wrapped) (base-req "patient/*.rs" :patient "123"
                                    :uri "/default/fhir/Medication"))]
      (is (= 403 (:status resp))))))

(deftest middleware-wraps-store-for-member-type
  (testing "in-compartment request gets a CompartmentFilteringStore"
    (let [resp ((wrapped) (base-req "patient/Observation.rs" :patient "123"))]
      (is (= 200 (:status resp)))
      (is (instance? CompartmentFilteringStore (:body resp))))))

(deftest middleware-honors-scp-array-claim
  (testing "scopes carried in the Hydra/RFC-9068 `scp` array are enforced"
    (let [req (-> (base-req nil :patient "123")
                  (update :identity dissoc :scope)
                  (assoc-in [:identity :scp] ["patient/Observation.rs"]))
          resp ((wrapped) req)]
      (is (= 200 (:status resp)))
      (is (instance? CompartmentFilteringStore (:body resp))))))

(deftest middleware-compartment-route-id-must-match
  (testing "browsing another patient's compartment is not found"
    (let [resp ((wrapped) (base-req "patient/Observation.rs" :patient "123"
                                    :uri "/default/fhir/Patient/999/Observation"
                                    :id "999" :target-type "Observation"))]
      (is (= 404 (:status resp)))))
  (testing "browsing the launch patient's own compartment is allowed (handler confines)"
    (let [resp ((wrapped) (base-req "patient/Observation.rs" :patient "123"
                                    :uri "/default/fhir/Patient/123/Observation"
                                    :id "123" :target-type "Observation"))]
      (is (= 200 (:status resp)))
      (is (= :original (:body resp)) "compartment-search route is not store-wrapped; the handler confines by id")))
  (testing "a patient token cannot browse a non-Patient compartment"
    (let [resp ((wrapped) (base-req "patient/Observation.rs" :patient "123"
                                    :uri "/default/fhir/Encounter/enc1/Observation"
                                    :id "enc1" :target-type "Observation"))]
      (is (= 403 (:status resp))))))

(deftest middleware-end-to-end-read-confinement
  (testing "through the middleware, an out-of-compartment read resolves to nil"
    (let [base (seeded-store)
          handler (compartment/wrap-patient-compartment
                    (fn [req]
                      (let [s (:fhir/store req)]
                        {:status (if (db/read-resource s tenant :Observation "obs-other") 200 404)}))
                    {})
          resp (handler (base-req "patient/Observation.rs" :patient "123"
                                  :uri "/default/fhir/Observation/obs-other"
                                  :id "obs-other" :store base))]
      (is (= 404 (:status resp))))))
