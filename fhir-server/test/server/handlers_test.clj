(ns server.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.handlers :as handlers]
            [server.middleware :as middleware]
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


;; ---------------------------------------------------------------------------
;; If-Match preconditions on PUT / PATCH / DELETE
;;
;; The store-level :if-match tests hand the option straight to the protocol,
;; so nothing there exercises the header. These do. The assertion that
;; matters throughout is the version left in the store, not the status code:
;; an If-Match the server drops on the floor answers a cheerful 200 while
;; overwriting whatever a concurrent writer put there.
;; ---------------------------------------------------------------------------

(defn- run
  "Invoke a handler through the same exception middleware the router wraps it
   in, so a store's 412 ex-info arrives as a response rather than escaping."
  [handler req]
  ((middleware/wrap-fhir-exceptions handler) req))

(defn- stored-version
  "The versionId currently in the store, or nil if nothing is there."
  [store id]
  (get-in (db/read-resource store tenant (keyword resource-type) id)
          [:meta :versionId]))

(defn- seed-patient!
  "A Patient in the store, returned as [id current-version]."
  [store]
  (let [id (get-in (create-patient! store
                                    :body {:resourceType "Patient"
                                           :gender "male"
                                           :name [{:family "Test"}]})
                   [:body :id])]
    [id (stored-version store id)]))

(defn- if-match-headers [if-match]
  (if (some? if-match) {"if-match" if-match} {}))

(defn- put!
  [store id if-match]
  (run handlers/update-resource
       (base-request store :id id
                     :headers (if-match-headers if-match)
                     :body {:resourceType "Patient" :id id :gender "female"})))

(defn- patch!
  [store id if-match]
  (run handlers/patch-resource
       (base-request store :id id
                     :headers (if-match-headers if-match)
                     :body [{:op "replace" :path "/gender" :value "female"}])))

(defn- delete!
  [store id if-match]
  (run handlers/delete-resource
       (base-request store :id id :headers (if-match-headers if-match))))

(def ^:private write-verbs
  "The three verbs that accept a version guard, each as [label fn]."
  [["PUT" put!] ["PATCH" patch!] ["DELETE" delete!]])

(defn- deleted-or-advanced?
  "A write that went through: the resource is gone (DELETE) or carries a
   version other than the one guarded on."
  [store id before]
  (let [after (stored-version store id)]
    (or (nil? after) (not= before after))))

;; --- forms of a matching guard: all three must be honoured, not dropped ---

(deftest if-match-accepts-the-weak-etag-form
  (doseq [[verb write!] write-verbs]
    (testing verb
      (let [store (make-store)
            [id v1] (seed-patient! store)
            resp (write! store id (str "W/\"" v1 "\""))]
        (is (contains? #{200 204} (:status resp)))
        (is (deleted-or-advanced? store id v1))))))

(deftest if-match-accepts-the-strong-etag-form
  (doseq [[verb write!] write-verbs]
    (testing verb
      (let [store (make-store)
            [id v1] (seed-patient! store)
            resp (write! store id (str "\"" v1 "\""))]
        (is (contains? #{200 204} (:status resp)))
        (is (deleted-or-advanced? store id v1))))))

(deftest if-match-accepts-a-bare-version-id
  (doseq [[verb write!] write-verbs]
    (testing verb
      (let [store (make-store)
            [id v1] (seed-patient! store)
            resp (write! store id v1)]
        (is (contains? #{200 204} (:status resp)))
        (is (deleted-or-advanced? store id v1))))))

;; --- If-Match: * (RFC 7232 §3.1) ---

(deftest if-match-star-matches-any-existing-representation
  (doseq [[verb write!] write-verbs]
    (testing verb
      (let [store (make-store)
            [id v1] (seed-patient! store)
            resp (write! store id "*")]
        (is (contains? #{200 204} (:status resp)))
        (is (deleted-or-advanced? store id v1))))))

(deftest if-match-star-against-a-missing-resource-is-412
  (doseq [[verb write!] write-verbs]
    (testing verb
      (let [store (make-store)
            resp (write! store "no-such-patient" "*")]
        (is (= 412 (:status resp)))
        (is (nil? (stored-version store "no-such-patient"))
            "* must not create the resource it was guarding against")))))

(deftest if-match-star-does-not-revive-a-deleted-resource
  (let [store (make-store)
        [id _] (seed-patient! store)]
    (delete! store id nil)
    (let [resp (put! store id "*")]
      (is (= 412 (:status resp)))
      (is (nil? (stored-version store id))
          "a deleted resource has no current representation for * to match"))))

;; --- a guard the server cannot act on must fail, never be ignored ---

(deftest malformed-if-match-is-rejected-and-writes-nothing
  (doseq [[verb write!] write-verbs
          bad ["not-an-etag!" "W/\"1" "\"1" "W/\"\"" "  " "W/\"a b\""]]
    (testing (str verb " " (pr-str bad))
      (let [store (make-store)
            [id v1] (seed-patient! store)
            resp (write! store id bad)]
        (is (= 400 (:status resp))
            "a malformed guard is a request-syntax fault, not a stale version")
        (is (= "OperationOutcome" (get-in resp [:body :resourceType])))
        (is (= v1 (stored-version store id))
            "the write must not have happened")))))

(deftest a-list-of-etags-is-rejected-rather-than-silently-narrowed
  (doseq [[verb write!] write-verbs]
    (testing verb
      (let [store (make-store)
            [id v1] (seed-patient! store)
            resp (write! store id (str "W/\"" v1 "\", W/\"99\""))]
        (is (= 400 (:status resp)))
        (is (= v1 (stored-version store id))
            "taking the first ETag would drop the rest of the precondition")))))

(deftest stale-but-well-formed-if-match-is-412
  (doseq [[verb write!] write-verbs
          spelling [(fn [_] "99") (fn [_] "W/\"99\"") (fn [_] "\"99\"")]]
    (testing verb
      (let [store (make-store)
            [id v1] (seed-patient! store)
            resp (write! store id (spelling v1))]
        (is (= 412 (:status resp)))
        (is (= v1 (stored-version store id)))))))

(deftest if-match-against-a-missing-resource-is-412
  (doseq [[verb write!] write-verbs]
    (testing verb
      (let [store (make-store)
            resp (write! store "no-such-patient" "W/\"1\"")]
        (is (= 412 (:status resp)))
        (is (nil? (stored-version store "no-such-patient"))
            "PUT upserts only when the client asked for no precondition")))))

;; --- the regression itself ---

(deftest a-supplied-if-match-never-degrades-to-an-unconditional-write
  (testing "every spelling of a guard on a version that is not current is refused"
    (doseq [[verb write!] write-verbs
            ;; Each of these once parsed to nil, which read as "no
            ;; precondition supplied" and let the write through unguarded.
            guard ["\"99\"" "99" "*junk*" "W/99" "etag-99"]]
      (testing (str verb " " (pr-str guard))
        (let [store (make-store)
              [id v1] (seed-patient! store)
              resp (write! store id guard)]
          (is (not (contains? #{200 204} (:status resp)))
              "a guard the client set must never come back as success")
          (is (= v1 (stored-version store id))
              "and must never let the write land"))))))

(deftest no-if-match-header-still-writes-unconditionally
  (testing "PUT upserts a resource that is not there"
    (let [store (make-store)
          resp (put! store "client-chosen-id" nil)]
      (is (= 201 (:status resp)))
      (is (some? (stored-version store "client-chosen-id")))))
  (doseq [[verb write!] write-verbs]
    (testing verb
      (let [store (make-store)
            [id v1] (seed-patient! store)
            resp (write! store id nil)]
        (is (contains? #{200 204} (:status resp)))
        (is (deleted-or-advanced? store id v1))))))

;; ---------------------------------------------------------------------------
;; If-None-Match on reads
;;
;; Unlike If-Match this is allowed to fail open: a validator the server
;; cannot read costs a 304 it could have served, and answering the full 200
;; is always correct. So the reasoning here is the opposite one — breadth of
;; what is understood, and never an error.
;; ---------------------------------------------------------------------------

(defn- read-with-if-none-match [store id header]
  (handlers/read-resource
    (base-request store :id id :headers (if (some? header)
                                          {"if-none-match" header}
                                          {}))))

(deftest if-none-match-recognises-every-etag-spelling
  (let [store (make-store)
        [id v1] (seed-patient! store)]
    (doseq [header [(str "W/\"" v1 "\"") (str "\"" v1 "\"") v1 "*"
                    (str "W/\"99\", W/\"" v1 "\"")]]
      (testing (pr-str header)
        (is (= 304 (:status (read-with-if-none-match store id header))))))))

(deftest if-none-match-that-does-not-match-serves-the-resource
  (let [store (make-store)
        [id _] (seed-patient! store)]
    (doseq [header ["W/\"99\"" "\"99\"" "99" "W/\"98\", W/\"99\""]]
      (testing (pr-str header)
        (is (= 200 (:status (read-with-if-none-match store id header))))))))

(deftest an-unreadable-if-none-match-serves-the-resource-rather-than-erroring
  (let [store (make-store)
        [id _] (seed-patient! store)]
    (doseq [header ["garbage!" "W/\"1" "" "  "]]
      (testing (pr-str header)
        (is (= 200 (:status (read-with-if-none-match store id header)))
            "a cache validator is not worth failing a GET over")))))
