(ns server.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.handlers :as handlers]
            [clojure.string]))

(def ^:private tenant "default")
(def ^:private resource-type "Patient")

(defn- make-store []
  (mock/create-mock-store {}))

(def ^:private search-registry
  "The slice of Patient's enriched search registry these tests search on.
   Routing always injects a registry (`server.core/capability-schema->server-schema`
   builds one for every routed type), and the search handlers reject any
   parameter it does not declare, so the tests supply one too."
  {"identifier" {:type "token" :columns [{:col "identifier" :array? true
                                          :sub-col "value"}]}
   "name"       {:type "string" :columns [{:col "name" :array? true
                                           :sub-col "family"}]}})

(defn- base-request
  "Build a minimal request map that the handlers expect."
  [store & {:keys [id vid params body form-params headers registry]
            :or   {params {} headers {} registry search-registry}}]
  (cond-> {:fhir/store         store
           :fhir/resource-type resource-type
           :fhir/search-registry registry
           :path-params        {:tenant-id tenant}
           :query-params       params
           :headers            headers}
    id          (assoc-in [:path-params :id] id)
    vid         (assoc-in [:path-params :vid] vid)
    body        (assoc-in [:parameters :body] body)
    form-params (assoc :form-params form-params)))

(defn- create-patient!
  "Helper: create a Patient via the handler and return the response."
  [store & {:keys [body] :or {body {:resourceType "Patient" :name [{:family "Test"}]}}}]
  (handlers/create-resource (base-request store :body body)))

;; ---------------------------------------------------------------------------
;; read-resource
;; ---------------------------------------------------------------------------

(deftest read-existing-resource-returns-200
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/read-resource (base-request store :id id))]
    (is (= 200 (:status resp)))
    (is (= id (get-in resp [:body :id])))))

(deftest read-missing-resource-returns-404
  (let [store (make-store)
        resp  (handlers/read-resource (base-request store :id "nonexistent"))]
    (is (= 404 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; vread-resource
;; ---------------------------------------------------------------------------

(deftest vread-existing-version-returns-200
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        vid    (get-in create [:body :meta :versionId])
        resp   (handlers/vread-resource (base-request store :id id :vid vid))]
    (is (= 200 (:status resp)))
    (is (= vid (get-in resp [:body :meta :versionId])))))

(deftest vread-missing-version-returns-404
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/vread-resource (base-request store :id id :vid "999"))]
    (is (= 404 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; create-resource
;; ---------------------------------------------------------------------------

(deftest create-returns-201-with-location
  (let [store (make-store)
        resp  (create-patient! store)]
    (is (= 201 (:status resp)))
    (is (some? (get-in resp [:headers "Location"])))
    (is (clojure.string/includes? (get-in resp [:headers "Location"]) "/_history/"))
    (is (= "Patient" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; update-resource
;; ---------------------------------------------------------------------------

(deftest update-existing-returns-200
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/update-resource
                 (base-request store
                               :id id
                               :body {:resourceType "Patient"
                                      :id id
                                      :gender "female"}))]
    (is (= 200 (:status resp)))
    (is (= "female" (get-in resp [:body :gender])))))

(deftest update-id-mismatch-returns-400
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/update-resource
                 (base-request store
                               :id id
                               :body {:resourceType "Patient"
                                      :id "wrong-id"
                                      :gender "male"}))]
    (is (= 400 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; delete-resource
;; ---------------------------------------------------------------------------

(deftest delete-returns-204
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/delete-resource (base-request store :id id))]
    (is (= 204 (:status resp)))
    (is (nil? (:body resp)))))

(deftest delete-then-read-returns-410
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])]
    (handlers/delete-resource (base-request store :id id))
    (let [resp (handlers/read-resource (base-request store :id id))]
      (is (= 410 (:status resp)))
      (is (= "OperationOutcome" (get-in resp [:body :resourceType]))))))

;; ---------------------------------------------------------------------------
;; search-type
;; ---------------------------------------------------------------------------

(deftest search-type-returns-bundle
  (let [store (make-store)]
    (create-patient! store)
    (create-patient! store :body {:resourceType "Patient" :name [{:family "Other"}]})
    (let [resp (handlers/search-type (base-request store))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (get-in resp [:body :resourceType])))
      (is (= "searchset" (get-in resp [:body :type])))
      (is (= 2 (count (get-in resp [:body :entry])))))))

(deftest search-type-count-zero-returns-total-only
  (let [store (make-store)]
    (create-patient! store)
    (create-patient! store :body {:resourceType "Patient" :name [{:family "Other"}]})
    (let [resp (handlers/search-type (base-request store :params {"_count" "0"}))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (get-in resp [:body :resourceType])))
      (is (= 2 (get-in resp [:body :total])))
      (is (nil? (get-in resp [:body :entry]))))))

(deftest search-type-negative-skip-returns-400
  (let [store (make-store)
        resp  (handlers/search-type (base-request store :params {"_skip" "-1"}))]
    (is (= 400 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; history-instance
;; ---------------------------------------------------------------------------

;; ---------------------------------------------------------------------------
;; conditional create (If-None-Exist)
;; ---------------------------------------------------------------------------

(deftest conditional-create-serializes-concurrent-requests
  (testing "N concurrent POSTs with the same If-None-Exist produce exactly one 201"
    (let [store (make-store)
          n 20
          body  {:resourceType "Patient"
                 :identifier [{:value "abc"}]
                 :name [{:family "Concurrent"}]}
          start (java.util.concurrent.CountDownLatch. 1)
          tasks (repeatedly n
                  (fn []
                    (fn []
                      (.await start)
                      (handlers/create-resource
                        (base-request store
                                      :body body
                                      :headers {"if-none-exist" "identifier=abc"})))))
          pool  (java.util.concurrent.Executors/newFixedThreadPool n)
          futures (mapv #(.submit pool ^Callable %) tasks)]
      (.countDown start)
      (let [responses (mapv #(.get ^java.util.concurrent.Future %) futures)
            statuses  (mapv :status responses)
            n-201 (count (filter #{201} statuses))
            n-200 (count (filter #{200} statuses))]
        (.shutdown pool)
        (is (= 1 n-201) (str "expected exactly one 201, got statuses: " statuses))
        (is (= (dec n) n-200) (str "expected " (dec n) " 200s, got statuses: " statuses))
        (is (= n (+ n-201 n-200)))
        ;; Confirm the store actually holds a single Patient with identifier=abc.
        (let [search-resp (handlers/search-type
                            (base-request store :params {"identifier" "abc"}))]
          (is (= 1 (count (get-in search-resp [:body :entry])))))))))

(deftest history-instance-returns-bundle-with-entries
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])]
    ;; Create a second version via update
    (handlers/update-resource
      (base-request store
                    :id id
                    :body {:resourceType "Patient" :id id :gender "male"}))
    (let [resp (handlers/history-instance (base-request store :id id))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (get-in resp [:body :resourceType])))
      (is (= "history" (get-in resp [:body :type])))
      (is (= 2 (get-in resp [:body :total])))
      (is (= 2 (count (get-in resp [:body :entry])))))))

;; ---------------------------------------------------------------------------
;; compartment-search (GET /:compartment-type/:id/:target-type)
;; ---------------------------------------------------------------------------

;; Minimal registries mirroring the R4B Patient-compartment link params:
;; Observation via subject|performer, Condition via patient (-> subject column).
(def ^:private compartment-registries
  {"Observation" {"subject"   {:type "reference" :columns [{:col "subject"}]}
                  "performer" {:type "reference" :columns [{:col "performer" :array? true}]}}
   "Condition"   {"patient"   {:type "reference" :columns [{:col "subject"}]}}})

(defn- compartment-store
  "A store seeded with Observations/Conditions for two patients."
  []
  (let [store (make-store)
        ref   (fn [pid] {:reference (str "Patient/" pid)})]
    (db/create-resource store tenant :Observation "o1" {:resourceType "Observation" :id "o1" :subject (ref "p1")})
    (db/create-resource store tenant :Observation "o2" {:resourceType "Observation" :id "o2" :subject (ref "p1")})
    (db/create-resource store tenant :Observation "o3" {:resourceType "Observation" :id "o3" :subject (ref "p2")})
    (db/create-resource store tenant :Condition   "c1" {:resourceType "Condition"   :id "c1" :subject (ref "p1")})
    store))

(defn- compartment-request
  [store compartment-id target-type & {:keys [params]}]
  {:fhir/store store
   :fhir/all-registries compartment-registries
   :query-params (or params {})
   :path-params {:tenant-id tenant
                 :compartment-type "Patient"
                 :compartment-id compartment-id
                 :target-type target-type}})

(deftest compartment-search-returns-only-members-of-the-compartment
  (let [resp (handlers/compartment-search (compartment-request (compartment-store) "p1" "Observation"))]
    (is (= 200 (:status resp)))
    (is (= "searchset" (get-in resp [:body :type])))
    (is (= 2 (get-in resp [:body :total])))
    (is (= #{"o1" "o2"} (set (map (comp :id :resource) (get-in resp [:body :entry])))))))

(deftest compartment-search-excludes-other-patients
  (let [resp (handlers/compartment-search (compartment-request (compartment-store) "p2" "Observation"))]
    (is (= 1 (get-in resp [:body :total])))
    (is (= ["o3"] (mapv (comp :id :resource) (get-in resp [:body :entry]))))))

(deftest compartment-search-wildcard-spans-all-member-types
  (let [resp (handlers/compartment-search (compartment-request (compartment-store) "p1" "*"))]
    (is (= 200 (:status resp)))
    (is (= 3 (get-in resp [:body :total]))
        "two Observations and one Condition for p1")))

(deftest compartment-search-unknown-compartment-type-is-400
  (let [resp (handlers/compartment-search
               (assoc-in (compartment-request (compartment-store) "p1" "Observation")
                         [:path-params :compartment-type] "Bogus"))]
    (is (= 400 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

(deftest compartment-search-non-member-target-is-400
  (let [resp (handlers/compartment-search (compartment-request (compartment-store) "p1" "Medication"))]
    (is (= 400 (:status resp)))
    (is (re-find #"not a member" (get-in resp [:body :issue 0 :diagnostics])))))

(deftest compartment-search-empty-result-is-empty-bundle
  (let [resp (handlers/compartment-search (compartment-request (compartment-store) "nobody" "Observation"))]
    (is (= 200 (:status resp)))
    (is (= 0 (get-in resp [:body :total])))
    (is (empty? (get-in resp [:body :entry])))))

(deftest compartment-search-paginates-with-next-link
  (let [resp (handlers/compartment-search
               (compartment-request (compartment-store) "p1" "Observation"
                                    :params {"_count" "1" "_skip" "0"}))]
    (is (= 1 (count (get-in resp [:body :entry]))))
    (is (some #(= "next" (:relation %)) (get-in resp [:body :link]))
        "a full page yields a next link")))

;; ---------------------------------------------------------------------------
;; _include over nested reference columns (:sub-col)
;; ---------------------------------------------------------------------------

(def ^:private appointment-registries
  "Appointment's `actor` is the R4B search parameter whose FHIRPath is
   `Appointment.participant.actor`, so the registry resolves it to a nested
   column. `service-provider` is the flat shape, kept here so the two paths are
   covered by the same fixture."
  {"Appointment"  {"actor"            {:type "reference"
                                       :target ["Practitioner" "Location"]
                                       :columns [{:col "participant"
                                                  :fhir-type "BackboneElement"
                                                  :array? true
                                                  :sub-col "actor"
                                                  :sub-fhir-type "Reference"
                                                  :sub-array? false}]}
                   "service-provider" {:type "reference"
                                       :target ["Organization"]
                                       :columns [{:col "serviceProvider"
                                                  :fhir-type "Reference"
                                                  :array? false}]}}
   "Practitioner" {}
   "Location"     {}
   "Organization" {}})

(defn- appointment-store
  "One Appointment with two participants and an organization, plus the three
   resources they reference."
  []
  (let [store (make-store)]
    (db/create-resource store tenant :Practitioner "pr1"
                        {:resourceType "Practitioner" :id "pr1"
                         :name [{:family "Reyes"}]})
    (db/create-resource store tenant :Location "loc1"
                        {:resourceType "Location" :id "loc1" :name "Clinic B"})
    (db/create-resource store tenant :Organization "org1"
                        {:resourceType "Organization" :id "org1" :name "Breeze"})
    (db/create-resource store tenant :Appointment "a1"
                        {:resourceType "Appointment" :id "a1"
                         :participant [{:actor {:reference "Practitioner/pr1"}}
                                       {:actor {:reference "Location/loc1"}}]
                         :serviceProvider {:reference "Organization/org1"}})
    store))

(defn- appointment-search-request
  [store params]
  {:fhir/store           store
   :fhir/resource-type   "Appointment"
   :fhir/search-registry (get appointment-registries "Appointment")
   :fhir/all-registries  appointment-registries
   :path-params          {:tenant-id tenant}
   :query-params         params
   :headers              {}})

(defn- included-ids [resp]
  (->> (get-in resp [:body :entry])
       (filter #(= "include" (get-in % [:search :mode])))
       (map (comp :id :resource))
       set))

(deftest include-resolves-a-nested-reference-column
  (let [resp (handlers/search-type
               (appointment-search-request (appointment-store)
                                           {"_include" "Appointment:actor"}))]
    (is (= 200 (:status resp)))
    (is (= #{"pr1" "loc1"} (included-ids resp))
        "Appointment.participant.actor is a :sub-col; reading the participant
         elements alone finds no :reference and yields no include entries")))

(deftest include-resolves-every-entry-of-a-repeating-column
  (let [resp (handlers/search-type
               (appointment-search-request (appointment-store)
                                           {"_include" "Appointment:actor"}))]
    (is (= 2 (count (included-ids resp)))
        "one include entry per participant, not just the first")))

(deftest include-still-resolves-a-top-level-reference-column
  (let [resp (handlers/search-type
               (appointment-search-request (appointment-store)
                                           {"_include" "Appointment:service-provider"}))]
    (is (= #{"org1"} (included-ids resp)))))

(deftest include-entries-do-not-displace-the-matches
  (let [resp (handlers/search-type
               (appointment-search-request (appointment-store)
                                           {"_include" "Appointment:actor"}))
        matches (->> (get-in resp [:body :entry])
                     (filter #(= "match" (get-in % [:search :mode])))
                     (map (comp :id :resource)))]
    (is (= ["a1"] (vec matches)))))

(deftest column-references-reads-a-nested-reference
  (let [column-references #'handlers/column-references
        appt {:participant [{:actor {:reference "Practitioner/pr1"}}
                            {:actor {:reference "Location/loc1"}}]}]
    (is (= ["Practitioner/pr1" "Location/loc1"]
           (vec (column-references appt {:col "participant" :sub-col "actor"}))))))

(deftest column-references-tolerates-a-single-element-and-an-absent-one
  (let [column-references #'handlers/column-references]
    (is (= ["Practitioner/pr1"]
           (vec (column-references {:participant {:actor {:reference "Practitioner/pr1"}}}
                                   {:col "participant" :sub-col "actor"})))
        "a repeating element stored as a bare map")
    (is (empty? (column-references {} {:col "participant" :sub-col "actor"})))
    (is (empty? (column-references {:participant [{}]} {:col "participant" :sub-col "actor"}))
        "a participant carrying no actor")))

;; ---------------------------------------------------------------------------
;; Bundle.total on a paginated searchset
;; ---------------------------------------------------------------------------

(defn- patients! [store n]
  (dotimes [i n]
    (db/create-resource store tenant :Patient (str "p" i)
                        {:resourceType "Patient" :id (str "p" i)
                         :name [{:family (str "F" i)}]})))

(defn- search-patients [store params]
  (handlers/search-type (base-request store :params params)))

(deftest search-total-is-the-match-count-not-the-page-size
  (let [store (make-store)]
    (patients! store 7)
    (let [resp (search-patients store {"_count" "3"})]
      (is (= 3 (count (get-in resp [:body :entry]))))
      (is (= 7 (get-in resp [:body :total]))
          "the defect this replaces reported 3, the _count"))))

(deftest search-total-on-a-short-page-needs-no-count
  (let [store (make-store)]
    (patients! store 4)
    (is (= 4 (get-in (search-patients store {"_count" "50"}) [:body :total])))))

(deftest search-total-on-a-short-page-accounts-for-the-skip
  (let [store (make-store)]
    (patients! store 5)
    (is (= 5 (get-in (search-patients store {"_count" "3" "_skip" "3"}) [:body :total]))
        "the last page holds 2, and 3 were skipped")))

(deftest search-total-none-omits-the-element
  (let [store (make-store)]
    (patients! store 7)
    (let [resp (search-patients store {"_count" "3" "_total" "none"})]
      (is (= 200 (:status resp)))
      (is (not (contains? (:body resp) :total))
          "Bundle.total is 0..1: omitted says nothing, a wrong number lies")
      (is (some #(= "next" (:relation %)) (get-in resp [:body :link]))
          "without a total the older full-page rule still drives the next link"))))

(deftest search-next-link-is-withheld-on-an-exact-final-page
  (let [store (make-store)]
    (patients! store 4)
    (let [resp (search-patients store {"_count" "2" "_skip" "2"})]
      (is (= 4 (get-in resp [:body :total])))
      (is (= 2 (count (get-in resp [:body :entry]))))
      (is (nil? (some #(= "next" (:relation %)) (get-in resp [:body :link])))
          "a full page that exhausts the total has nothing after it"))))

(deftest search-next-link-survives-when-more-pages-remain
  (let [store (make-store)]
    (patients! store 5)
    (let [resp (search-patients store {"_count" "2" "_skip" "2"})]
      (is (= 5 (get-in resp [:body :total])))
      (is (some #(= "next" (:relation %)) (get-in resp [:body :link]))))))
