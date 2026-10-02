(ns server.compartment-test
  "Tests for R4B patient-compartment row-level enforcement: helpers, the union
   query construction, the CompartmentFilteringStore decorator, and the
   wrap-patient-compartment middleware."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.compartment :as compartment]
            [server.handlers :as handlers]
            [server.scope :as scope])
  (:import [server.compartment CompartmentFilteringStore]))

(def ^:private tenant "default")

;; A minimal registry mirroring how the real search registry resolves the R4B
;; Patient-compartment link params: `subject`/`performer` for Observation, and
;; for Task the four link params plus a non-link param the capability pack also
;; registers. Task's `patient` and `subject` both resolve to Task.for, so the
;; union must dedupe them.
(def ^:private registries
  {"Observation" {"subject"   {:type "reference" :columns [{:col "subject"}]}
                  "performer" {:type "reference" :columns [{:col "performer" :array? true}]}}
   "Task"        {"patient"   {:type "reference" :columns [{:col "for"}]}
                  "subject"   {:type "reference" :columns [{:col "for"}]}
                  "owner"     {:type "reference" :columns [{:col "owner"}]}
                  "requester" {:type "reference" :columns [{:col "requester"}]}
                  "status"    {:type "token" :columns [{:col "status"}]}}})

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

;; Appointment's only Patient-compartment link param, `actor`, resolves to the
;; nested Appointment.participant.actor, which the registry describes as a
;; parent column plus a :sub-col.
(def ^:private appointment-registry
  {"actor" {:type "reference"
            :columns [{:col "participant" :array? true :sub-col "actor"}]}})

(defn- appt [id & actor-refs]
  {:resourceType "Appointment" :id id :status "booked"
   :participant (mapv (fn [r] {:actor {:reference r} :status "accepted"}) actor-refs)})

(deftest writes-follow-nested-link-columns
  (let [store (compartment/filtering-store
               (mock/create-mock-store {})
               {:patient-id "123"
                :all-registries (assoc registries "Appointment" appointment-registry)})]
    (testing "an Appointment naming the launch patient as a participant actor is writable"
      (is (= "a1" (:id (db/create-resource store tenant :Appointment "a1"
                                           (appt "a1" "Practitioner/9" "Patient/123"))))))
    (testing "an Appointment whose participants are all someone else is refused"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside the patient compartment"
            (db/create-resource store tenant :Appointment "a2"
                                (appt "a2" "Practitioner/9" "Patient/999")))))
    (testing "a participant without an actor does not break the match"
      (is (= "a3" (:id (db/create-resource store tenant :Appointment "a3"
                                           (update (appt "a3" "Patient/123") :participant
                                                   conj {:type [{:text "location"}]}))))))))

(deftest in-memory-membership-follows-nested-link-columns
  (testing "bulk export's membership test reaches participant.actor too"
    (is (true? (compartment/resource-in-any-compartment?
                "Patient" #{"123"} "Appointment" (appt "a1" "Patient/123") appointment-registry)))
    (is (false? (compartment/resource-in-any-compartment?
                 "Patient" #{"123"} "Appointment" (appt "a2" "Patient/999") appointment-registry)))))

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
;; Task: a deliberate, narrowed forward-port of R5's Patient/Task compartment
;; entry. Only `requester` is a link, so patient-filed demographic change
;; requests stay visible to their author while staff workflow Tasks (which name
;; the patient in Task.for or Task.owner) are not readable by a patient token.
;; ---------------------------------------------------------------------------

(defn- task [id & {:keys [for requester owner]}]
  (cond-> {:resourceType "Task" :id id :status "requested" :intent "proposal"}
    for       (assoc :for {:reference for})
    requester (assoc :requester {:reference requester})
    owner     (assoc :owner {:reference owner})))

(defn- seeded-task-store []
  (let [store (mock/create-mock-store {})
        put!  (fn [t] (db/create-resource store tenant :Task (:id t) t))]
    ;; the shape Patient/$request-demographic-change stages
    (put! (task "task-authored" :for "Patient/123" :requester "Patient/123"))
    ;; staff workflow Tasks that name the patient in the other roles
    (put! (task "task-staff-for" :for "Patient/123" :requester "Practitioner/p1"))
    (put! (task "task-staff-owner" :for "Patient/999" :owner "Patient/123"))
    (put! (task "task-staff-unlinked" :for "Patient/123"))
    (put! (task "task-other" :for "Patient/999" :requester "Patient/999"))
    store))

(defn- search-tasks [store params]
  (handlers/search-type
    {:fhir/store store
     :fhir/resource-type "Task"
     :fhir/search-registry (get registries "Task")
     :fhir/all-registries registries
     :path-params {:tenant-id tenant}
     :query-params params}))

(defn- task-ids [resp]
  (set (map (comp :id :resource) (get-in resp [:body :entry]))))

(deftest task-is-a-patient-compartment-member
  (is (compartment/patient-compartment-member? "Task"))
  (is (= ["requester"] (compartment/compartment-link-params "Patient" "Task"))))

(deftest task-descriptor-covers-only-the-authored-link
  (let [desc (compartment/compartment-descriptor "Patient" "Task" (get registries "Task"))]
    (is (= "reference" (:type desc)))
    (is (= ["Patient"] (:target desc)))
    (is (= [{:col "requester"}] (:columns desc))
        "for and owner are registered but are not compartment links for Task")))

(deftest confine-task-search-under-patient-token
  (let [outcome (compartment/confine "Patient" "123" "Task" {} (get registries "Task"))]
    (testing "a Task search is confined, never denied, once the link param resolves"
      (is (not= :deny outcome))
      (let [[tag params registry] outcome]
        (is (= :run tag))
        (is (= "Patient/123" (get params compartment/compartment-search-param)))
        (is (= [{:col "requester"}]
               (:columns (get registry compartment/compartment-search-param))))))))

(deftest patient-token-reads-own-change-request-task-only
  (let [store (fstore (seeded-task-store))
        read  (fn [id] (handlers/read-resource
                         {:fhir/store store :fhir/resource-type "Task"
                          :path-params {:tenant-id tenant :id id}}))]
    (testing "the Task the patient authored is readable"
      (is (= 200 (:status (read "task-authored")))))
    (testing "staff Tasks naming the patient as for, or as owner, are not"
      (is (= 404 (:status (read "task-staff-for"))))
      (is (= 404 (:status (read "task-staff-owner"))))
      (is (= 404 (:status (read "task-staff-unlinked")))))
    (testing "another patient's Task is not"
      (is (= 404 (:status (read "task-other")))))))

(deftest patient-token-task-search-returns-only-authored
  (let [store (fstore (seeded-task-store))]
    (testing "an unfiltered search"
      (is (= #{"task-authored"} (task-ids (search-tasks store {})))))
    (testing "the portal's Task?patient=<self> query"
      (is (= #{"task-authored"}
             (task-ids (search-tasks store {"patient" "Patient/123"})))))
    (testing "a client cannot widen the search through the owner or requester filters"
      (is (empty? (task-ids (search-tasks store {"owner" "Patient/123"}))))
      (is (empty? (task-ids (search-tasks store {"requester" "Practitioner/p1"})))))))

(deftest patient-token-cannot-write-task-outside-its-compartment
  (let [store (fstore (seeded-task-store))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"outside the patient compartment"
          (db/create-resource store tenant :Task "t-x"
                              (task "t-x" :for "Patient/123" :requester "Practitioner/p1"))))))

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

;; ---------------------------------------------------------------------------
;; Writes that can move or overwrite another patient's resource
;;
;; `create-resource`, `update-resource` and `delete-resource` are confined, but
;; the post-image check alone does not stop a write from landing on a resource
;; the launch patient cannot read, and the two Bundle verbs are not confined at
;; all. Every write verb is a write, whichever verb it arrives through: an
;; operation implementation writes through `transact-transaction`, and a
;; system-level transaction Bundle reaches the store through it too.
;; ---------------------------------------------------------------------------

(defn- new-obs [patient-ref]
  {:resourceType "Observation" :subject {:reference patient-ref}})

(defn- entry
  ([method url] {:request {:method method :url url}})
  ([method url resource] {:request {:method method :url url} :resource resource}))

(defn- refusal-status
  "The :fhir/status a refused write throws with, or nil when `f` returned."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:fhir/status (ex-data e)))))

(defn- observation-subjects
  "Every Observation the BASE store holds, as {id subject-reference}. Read from
   the base rather than through the decorator, so a write the decorator let
   through to another patient's compartment is visible here."
  [base]
  (into {}
        (map (juxt :id #(get-in % [:subject :reference])))
        (db/search base tenant :Observation {} nil)))

(def ^:private seeded-subjects
  {"obs-mine" "Patient/123" "obs-perf" nil "obs-other" "Patient/999"})

(deftest update-cannot-overwrite-another-patients-resource
  (testing "re-pointing another patient's Observation at the launch patient is refused"
    (let [base (seeded-store)
          store (fstore base)]
      (is (= 403 (refusal-status
                  #(db/update-resource store tenant :Observation "obs-other"
                                       (obs "obs-other" "Patient/123")
                                       {:if-match db/if-match-any}))))
      (is (= "Patient/999" (get (observation-subjects base) "obs-other"))
          "obs-other still belongs to Patient/999")))
  (testing "through the real PUT handler, with the If-Match a client can always send"
    (let [base (seeded-store)
          resp (try (handlers/update-resource
                     {:fhir/store (fstore base)
                      :fhir/resource-type "Observation"
                      :path-params {:tenant-id tenant :id "obs-other"}
                      :headers {"if-match" "*"}
                      :parameters {:body (obs "obs-other" "Patient/123")}})
                    (catch clojure.lang.ExceptionInfo e
                      {:status (:fhir/status (ex-data e))}))]
      (is (= 403 (:status resp)))
      (is (= "Patient/999" (get (observation-subjects base) "obs-other"))))))

(deftest transaction-confines-every-entry
  (testing "a POST entry for another patient fails the whole Bundle with a 403"
    (let [base (seeded-store)
          store (fstore base)]
      (is (= 403 (refusal-status
                  #(db/transact-transaction store tenant
                                            [(entry "POST" "Observation" (new-obs "Patient/123"))
                                             (entry "POST" "Observation" (new-obs "Patient/999"))]))))
      (is (= seeded-subjects (observation-subjects base))
          "nothing was written, the in-compartment entry included")))
  (testing "a PUT entry cannot overwrite another patient's resource"
    (let [base (seeded-store)
          store (fstore base)]
      (is (= 403 (refusal-status
                  #(db/transact-transaction store tenant
                                            [(entry "PUT" "Observation/obs-other"
                                                    (obs "obs-other" "Patient/123"))]))))
      (is (= seeded-subjects (observation-subjects base)))))
  (testing "a DELETE entry cannot remove another patient's resource"
    (let [base (seeded-store)
          store (fstore base)]
      (is (= 403 (refusal-status
                  #(db/transact-transaction store tenant
                                            [(entry "DELETE" "Observation/obs-other")]))))
      (is (= seeded-subjects (observation-subjects base)))))
  (testing "a GET entry does not hand back another patient's resource"
    (let [store (fstore (seeded-store))
          res (try (db/transact-transaction store tenant
                                            [(entry "GET" "Observation/obs-other")])
                   (catch clojure.lang.ExceptionInfo _ nil))]
      (is (not-any? #(= "obs-other" (get-in % [:resource :id])) (:entry res)))))
  (testing "an all-in-compartment Bundle still commits"
    (let [base (seeded-store)
          store (fstore base)
          res (db/transact-transaction store tenant
                                       [(entry "POST" "Observation" (new-obs "Patient/123"))
                                        (entry "PUT" "Observation/obs-mine"
                                               (obs "obs-mine" "Patient/123"))])]
      (is (= "transaction-response" (:type res)))
      (is (= 4 (count (observation-subjects base)))))))

(deftest batch-confines-each-entry-on-its-own
  (let [base (seeded-store)
        store (fstore base)
        res (db/transact-bundle store tenant
                                ;; The GET precedes the writes so that it reads
                                ;; obs-other as seeded, not as a later entry left it.
                                [(entry "POST" "Observation" (new-obs "Patient/123"))
                                 (entry "POST" "Observation" (new-obs "Patient/999"))
                                 (entry "GET" "Observation/obs-other")
                                 (entry "PUT" "Observation/obs-other"
                                        (obs "obs-other" "Patient/123"))
                                 (entry "DELETE" "Observation/obs-other")])
        statuses (mapv #(get-in % [:response :status]) (:entry res))]
    (testing "every entry answers, in input order"
      (is (= 5 (count statuses))))
    (testing "the in-compartment entry lands"
      (is (str/starts-with? (str (get statuses 0)) "201")))
    (testing "the out-of-compartment read answers as a read does: not found"
      (is (str/starts-with? (str (get statuses 2)) "404"))
      (is (nil? (get-in res [:entry 2 :resource]))))
    (testing "each cross-patient write is refused in place"
      (is (str/starts-with? (str (get statuses 1)) "403"))
      (is (str/starts-with? (str (get statuses 3)) "403"))
      (is (str/starts-with? (str (get statuses 4)) "403")))
    (testing "only the in-compartment write reached the store"
      (let [subjects (observation-subjects base)]
        (is (= 4 (count subjects)))
        (is (= "Patient/999" (get subjects "obs-other")))
        (is (= 1 (count (filter #{"Patient/999"} (vals subjects)))))))))

(deftest every-write-verb-takes-the-opts-arity
  ;; fhir-store.protocol/IFHIRStore rules 1 and 2: the plain arity is the opts
  ;; arity with nil, and a decorator forwards opts iff it is non-nil. An arity
  ;; the decorator does not define throws AbstractMethodError on the call, so a
  ;; caller threading :tx-metadata through a patient-scoped request dies rather
  ;; than writing.
  (let [base (seeded-store)
        store (fstore base)]
    (testing "create-resource"
      (is (= "c1" (:id (db/create-resource store tenant :Observation "c1"
                                           (obs "c1" "Patient/123") nil)))))
    (testing "transact-transaction, and the opts arity is confined too"
      (is (= "transaction-response"
             (:type (db/transact-transaction store tenant
                                             [(entry "POST" "Observation" (new-obs "Patient/123"))]
                                             nil))))
      (is (= 403 (refusal-status
                  #(db/transact-transaction store tenant
                                            [(entry "POST" "Observation" (new-obs "Patient/999"))]
                                            {:tx-metadata {:device {:name "compartment-test"}}})))))
    (testing "transact-bundle"
      (is (= "batch-response"
             (:type (db/transact-bundle store tenant
                                        [(entry "POST" "Observation" (new-obs "Patient/123"))]
                                        nil)))))))

;; ---------------------------------------------------------------------------
;; System-level endpoints
;;
;; POST /:tenant-id/fhir resolves to no resource type, so `wrap-smart-scope`
;; and the type gates in `wrap-patient-compartment` have nothing to judge and
;; pass it on. The entries are the only place the patient link can be judged,
;; so the handler must see the filtering store. This composes the two
;; middlewares that could confine it in their router order; Keto, the only
;; other gate, checks `write` on "<realm>/system" and knows nothing of the
;; entries.
;; ---------------------------------------------------------------------------

(defn- system-bundle-app []
  (-> (handlers/transaction {})
      (compartment/wrap-patient-compartment {})
      (scope/wrap-smart-scope {})))

(defn- post-bundle [base bundle-type entries]
  (try ((system-bundle-app)
        {:identity {:sub "u" :scope "patient/*.*" :patient "123"}
         :request-method :post
         :uri "/default/fhir"
         :fhir/store base
         :path-params {:tenant-id tenant}
         :reitit.core/match {:data {:fhir/all-registries registries}}
         :body-params {:resourceType "Bundle" :type bundle-type :entry entries}})
       (catch clojure.lang.ExceptionInfo e
         {:status (:fhir/status (ex-data e))})))

(deftest system-transaction-is-confined-to-the-launch-patient
  (testing "a patient token cannot overwrite another patient's Patient record"
    (let [base (seeded-store)
          _ (db/create-resource base tenant :Patient "999"
                                {:resourceType "Patient" :id "999" :gender "female"})
          resp (post-bundle base "transaction"
                            [(entry "PUT" "Patient/999"
                                    {:resourceType "Patient" :id "999" :gender "male"})])]
      (is (= 403 (:status resp)))
      (is (= "female" (:gender (db/read-resource base tenant :Patient "999"))))))
  (testing "a patient token cannot file an Observation in another patient's chart"
    (let [base (seeded-store)
          resp (post-bundle base "transaction"
                            [(entry "POST" "Observation" (new-obs "Patient/999"))])]
      (is (= 403 (:status resp)))
      (is (= seeded-subjects (observation-subjects base)))))
  (testing "a batch GET does not read another patient's resource"
    (let [resp (post-bundle (seeded-store) "batch"
                            [(entry "GET" "Observation/obs-other")])]
      (is (not-any? #(= "obs-other" (get-in % [:resource :id]))
                    (get-in resp [:body :entry]))))))

;; ---------------------------------------------------------------------------
;; History reads
;;
;; Not a write, but the same omission: `history` and `history-type` forward to
;; the base unconfined, and `GET /[type]/_history` is authorized by the
;; type-level `read` tuple every portal patient holds on every member type.
;; ---------------------------------------------------------------------------

(deftest history-is-confined-to-the-compartment
  (let [store (fstore (seeded-store))]
    (testing "type history returns only the launch patient's versions"
      (is (= #{"obs-mine" "obs-perf"}
             (set (map :id (db/history-type store tenant :Observation {}))))))
    (testing "instance history of another patient's resource is empty"
      (is (empty? (db/history store tenant :Observation "obs-other"))))
    (testing "through the real handler"
      (let [resp (handlers/history-type {:fhir/store store
                                         :fhir/resource-type "Observation"
                                         :path-params {:tenant-id tenant}
                                         :query-params {}})]
        (is (not-any? #(= "obs-other" (get-in % [:resource :id]))
                      (get-in resp [:body :entry])))))))

;; ---------------------------------------------------------------------------
;; Declared exemptions
;;
;; An operation may need to write outside the compartment -- a Slot it books,
;; a server-owned counter -- and says so in its route data. The exemption is
;; by resource type and never reaches another patient's compartment.
;; ---------------------------------------------------------------------------

(def ^:private basic-registry
  {"patient" {:type "reference" :columns [{:col "subject"}]}
   "author"  {:type "reference" :columns [{:col "author"}]}})

(defn- exempt-store [base & types]
  (compartment/filtering-store base {:patient-id "123"
                                     :all-registries (assoc registries "Basic" basic-registry)
                                     :exempt-write-types types}))

(deftest types-outside-the-compartment-are-not-writable-by-default
  (let [store (fstore (seeded-store))]
    (is (= 403 (refusal-status
                #(db/create-resource store tenant :Slot "s1"
                                     {:resourceType "Slot" :id "s1" :status "busy"}))))
    (is (= 403 (refusal-status
                #(db/transact-transaction store tenant
                                          [(entry "PUT" "Practitioner/p1"
                                                  {:resourceType "Practitioner" :id "p1"})]))))))

(deftest a-declared-exemption-admits-shared-records
  (let [base (seeded-store)
        store (exempt-store base "Slot" "Basic")]
    (testing "a type outside the compartment"
      (is (= "transaction-response"
             (:type (db/transact-transaction store tenant
                                             [(entry "PUT" "Slot/s1"
                                                     {:resourceType "Slot" :id "s1" :status "busy"})]))))
      (is (= "busy" (:status (db/read-resource base tenant :Slot "s1")))))
    (testing "a member type holding no patient link, such as a server-owned counter"
      (is (= "counter" (:id (db/create-resource store tenant :Basic "counter"
                                                {:resourceType "Basic" :id "counter"}))))
      (is (= "Basic" (:resourceType (db/update-resource store tenant :Basic "counter"
                                                        {:resourceType "Basic" :id "counter"
                                                         :code {:text "next"}}))))))
  (testing "the exemption is per route: another type is still refused"
    (let [store (exempt-store (seeded-store) "Slot")]
      (is (= 403 (refusal-status
                  #(db/create-resource store tenant :Practitioner "p1"
                                       {:resourceType "Practitioner" :id "p1"})))))))

(deftest a-declared-exemption-never-reaches-another-patient
  (testing "a new record linking another patient is refused"
    (let [base (seeded-store)
          store (exempt-store base "Observation")]
      (is (= 403 (refusal-status
                  #(db/transact-transaction store tenant
                                            [(entry "POST" "Observation" (new-obs "Patient/999"))]))))
      (is (= seeded-subjects (observation-subjects base)))))
  (testing "replacing another patient's record is refused, whatever the new body links"
    (let [base (seeded-store)
          store (exempt-store base "Observation")]
      (is (= 403 (refusal-status
                  #(db/update-resource store tenant :Observation "obs-other"
                                       {:resourceType "Observation" :id "obs-other"}))))
      (is (= "Patient/999" (get (observation-subjects base) "obs-other")))))
  (testing "deleting another patient's record is refused"
    (let [base (seeded-store)
          store (exempt-store base "Observation")]
      (is (= 403 (refusal-status #(db/delete-resource store tenant :Observation "obs-other"))))
      (is (= "Patient/999" (get (observation-subjects base) "obs-other")))))
  (testing "another patient's Patient record is refused"
    (let [store (exempt-store (seeded-store) "Patient")]
      (is (= 403 (refusal-status
                  #(db/create-resource store tenant :Patient "999"
                                       {:resourceType "Patient" :id "999"}))))))
  (testing "a member type with no registered link parameter cannot be judged, so it is refused"
    (let [store (compartment/filtering-store (seeded-store)
                                             {:patient-id "123" :all-registries registries
                                              :exempt-write-types ["Basic"]})]
      (is (= 403 (refusal-status
                  #(db/create-resource store tenant :Basic "b1"
                                       {:resourceType "Basic" :id "b1"})))))))

(deftest middleware-passes-the-routes-exemptions-to-the-store
  (let [resp ((wrapped) (-> (base-req "patient/*.*" :patient "123" :method :post
                                      :uri "/default/fhir/Appointment/$book")
                            (assoc-in [:reitit.core/match :data :compartment/exempt-write-types]
                                      ["Slot"])))]
    (is (instance? CompartmentFilteringStore (:body resp)))
    (is (= #{"Slot"} (:exempt-write-types (:body resp))))))

(deftest middleware-wraps-the-store-for-a-system-endpoint
  (let [resp ((wrapped) (base-req "patient/*.*" :patient "123" :method :post
                                  :uri "/default/fhir"))]
    (is (instance? CompartmentFilteringStore (:body resp)))))

(deftest malformed-entries-are-refused
  (let [base (seeded-store)
        store (exempt-store base "Slot")]
    (testing "a body of another type cannot ride under an exempt url"
      (is (= 403 (refusal-status
                  #(db/transact-transaction store tenant
                                            [(entry "POST" "Slot" (new-obs "Patient/999"))]))))
      (is (= seeded-subjects (observation-subjects base))))
    (testing "an entry naming no type"
      (is (= 403 (refusal-status
                  #(db/transact-transaction store tenant
                                            [(entry "POST" "" (new-obs "Patient/123"))])))))
    (testing "a conditional entry"
      (is (= 403 (refusal-status
                  #(db/transact-transaction store tenant
                                            [(entry "PUT" "Observation?code=x"
                                                    (new-obs "Patient/123"))])))))))

;; ---------------------------------------------------------------------------
;; Version reads
;;
;; A resource's current version deciding whether ANY of its versions may be
;; read lets a version from before the resource entered the compartment leak:
;; an Observation refiled from another patient's chart, or a Task whose
;; requester changed. Each version is judged on its own, as history judges it.
;; ---------------------------------------------------------------------------

(defn- refiled-store
  "A base store holding an Observation first filed for Patient/999 (version 1)
   and then refiled to the launch patient (version 2), and a Task first
   requested by Patient/999 (version 1) whose requester became the launch
   patient (version 2)."
  []
  (let [base (seeded-store)]
    (db/create-resource base tenant :Observation "refiled" (obs "refiled" "Patient/999"))
    (db/update-resource base tenant :Observation "refiled" (obs "refiled" "Patient/123"))
    (db/create-resource base tenant :Task "t1"
                        {:resourceType "Task" :id "t1" :status "requested"
                         :requester {:reference "Patient/999"}})
    (db/update-resource base tenant :Task "t1"
                        {:resourceType "Task" :id "t1" :status "requested"
                         :requester {:reference "Patient/123"}})
    base))

(deftest vread-judges-each-version-on-its-own
  (let [store (fstore (refiled-store))]
    (testing "the version filed for the launch patient is readable"
      (is (= "Patient/123" (get-in (db/vread-resource store tenant :Observation "refiled" "2")
                                   [:subject :reference]))))
    (testing "the version filed for another patient reads as nil (handler -> 404)"
      (is (nil? (db/vread-resource store tenant :Observation "refiled" "1"))))
    (testing "a Task version from before its requester changed reads as nil"
      (is (= "2" (get-in (db/vread-resource store tenant :Task "t1" "2") [:meta :versionId])))
      (is (nil? (db/vread-resource store tenant :Task "t1" "1"))))
    (testing "vread agrees with history on which versions belong to the patient"
      (is (= #{"2"} (set (map #(get-in % [:meta :versionId])
                              (db/history store tenant :Observation "refiled"))))))))

(deftest real-vread-handler-blocks-a-version-outside-the-compartment
  (let [store (fstore (refiled-store))
        vreq  (fn [vid] (assoc-in (read-req store "refiled") [:path-params :vid] vid))]
    (is (= 200 (:status (handlers/vread-resource (vreq "2")))))
    (let [resp (handlers/vread-resource (vreq "1"))]
      (is (= 404 (:status resp)))
      (is (nil? (get-in resp [:body :subject]))))))

(deftest vread-of-a-type-outside-the-compartment-passes-through
  (let [base (mock/create-mock-store {})
        _    (db/create-resource base tenant :Medication "m1" {:resourceType "Medication" :id "m1"})]
    (is (= "m1" (:id (db/vread-resource (fstore base) tenant :Medication "m1" "1"))))))
