(ns test-server.search-param-contract-test
  "End-to-end guards on the search parameter contract.

   A type-level search whose filter is not a declared search parameter used to
   be discarded on the way to the query builder: the parameter never became a
   query constraint, and the response was an ordinary 200 searchset with no
   hint that the filter had been ignored. On a store whose in-memory fallback
   happened to match the dropped predicate loosely, that turns
   `GET /<tenant>/fhir/Consent?patient=X` into a whole-tenant read.

   These tests pin the two halves of the fix:

   1. Every parameter a search declares is either honoured or reported. The
      default is a 400 OperationOutcome; `Prefer: handling=lenient` downgrades
      it to an OperationOutcome warning carried in the searchset itself.
   2. The parameters that scope a resource to a patient really are declared.
      They are not written out in
      `fhir-igs/breeze-ig/package/CapabilityStatement-breeze-server.json` —
      `com.breezeehr.capability-statement/rest-resources` merges every base
      R4B SearchParameter whose `base` list names the type into any resource
      declaring `search-type`, so `Consent.patient` arrives via the shared
      `clinical-patient` definition. That merge is invisible in the source
      JSON, so it is asserted here."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [jsonista.core :as json]
            [malli.core :as m]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.head :refer [wrap-head]]
            [server.compartment :as compartment]
            [server.core :as core]
            [server.fhir-coercion :as fhir-coercion]
            [server.middleware :as middleware]
            [server.routing :as routing]
            [test-server.schemas.breeze :as breeze]))

(def ^:private tenant "t1")

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def ^:private all-schemas
  (delay (core/resolve-schemas breeze/specs)))

(def ^:private served-types
  "Every resource type the Breeze surface serves, capability namespaces and
   plain-schema specs (Group, SearchParameter) alike."
  (delay (mapv #(:resourceType (m/properties %)) @all-schemas)))

(defn- schema-for [resource-type]
  (first (filter #(= resource-type (:resourceType (m/properties %))) @all-schemas)))

(defn- registry-for [resource-type]
  (:fhir/search-registry (m/properties (schema-for resource-type))))

(defn- searchable? [resource-type]
  (contains? (:fhir/interactions (m/properties (schema-for resource-type))) :search-type))

;; ---------------------------------------------------------------------------
;; Ring app
;; ---------------------------------------------------------------------------

(def ^:private json-mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- app [store]
  (ring/ring-handler
   (ring/router
    (routing/build-fhir-routes @all-schemas)
    {:conflicts nil
     :data {:coercion fhir-coercion/coercion
            :muuntaja core/muuntaja-instance
            :middleware [wrap-head
                         parameters/parameters-middleware
                         muuntaja/format-negotiate-middleware
                         muuntaja/format-response-middleware
                         middleware/wrap-fhir-exceptions
                         muuntaja/format-request-middleware
                         rrc/coerce-request-middleware
                         rrc/coerce-response-middleware
                         rrc/coerce-exceptions-middleware
                         [core/wrap-fhir-store store]]}})
   (ring/create-default-handler
    {:not-found (constantly {:status 404 :body {:error "Not Found"}})})))

(defn- parse-body [body]
  (cond
    (map? body) body
    (instance? java.io.InputStream body) (json/read-value body json-mapper)
    (bytes? body) (json/read-value body json-mapper)
    :else body))

(defn- send!
  ([handler method uri] (send! handler method uri nil))
  ([handler method uri headers]
   (let [[path qs] (str/split uri #"\?" 2)
         resp (handler (cond-> {:request-method method
                                :uri path
                                :headers (merge {"accept" "application/json"} headers)}
                         qs (assoc :query-string qs)))]
     (update resp :body parse-body))))

(defn- matched-ids [resp]
  (into #{}
        (comp (filter #(= "match" (get-in % [:search :mode])))
              (map #(get-in % [:resource :id])))
        (get-in resp [:body :entry])))

(defn- outcome-issues [resp]
  (let [body (:body resp)]
    (if (= "OperationOutcome" (:resourceType body))
      (:issue body)
      (into []
            (comp (filter #(= "OperationOutcome" (get-in % [:resource :resourceType])))
                  (mapcat #(get-in % [:resource :issue])))
            (:entry body)))))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(defn- seeded-store
  "Three Consents across two patients, plus two Endpoints (a type with no
   patient-scoping parameter at all)."
  []
  (let [store (mock/create-mock-store {})]
    (doseq [pid ["p1" "p2"]]
      (db/create-resource store tenant :Patient pid
                          {:resourceType "Patient" :id pid
                           :name [{:family (str "Family-" pid)}]}))
    (doseq [[id pid] [["c1" "p1"] ["c2" "p1"] ["c3" "p2"]]]
      (db/create-resource store tenant :Consent id
                          {:resourceType "Consent" :id id
                           :status "active"
                           :scope {:coding [{:code "patient-privacy"}]}
                           :category [{:coding [{:code "acd"}]}]
                           :patient {:reference (str "Patient/" pid)}}))
    (doseq [id ["e1" "e2"]]
      (db/create-resource store tenant :Endpoint id
                          {:resourceType "Endpoint" :id id :status "active"
                           :connectionType {:code "hl7-fhir-rest"}
                           :payloadType [{:coding [{:code "any"}]}]
                           :address (str "http://example.test/" id)}))
    store))

;; ---------------------------------------------------------------------------
;; The reported defect: a patient filter that does not filter
;; ---------------------------------------------------------------------------

(deftest consent-patient-search-is-confined-to-that-patient
  (let [handler (app (seeded-store))]
    (testing "the unfiltered search sees the whole tenant"
      (is (= #{"c1" "c2" "c3"}
             (matched-ids (send! handler :get (str "/" tenant "/fhir/Consent"))))))
    (testing "?patient= returns only that patient's Consents"
      (let [resp (send! handler :get (str "/" tenant "/fhir/Consent?patient=Patient/p1"))]
        (is (= 200 (:status resp)))
        (is (= #{"c1" "c2"} (matched-ids resp)))
        (is (= 2 (get-in resp [:body :total])))))
    (testing "a patient with no Consents gets none of anyone else's"
      (is (= #{} (matched-ids
                  (send! handler :get (str "/" tenant "/fhir/Consent?patient=Patient/nobody"))))))))

(deftest unregistered-parameter-never-yields-an-unfiltered-result
  (let [handler (app (seeded-store))]
    (testing "an undeclared parameter is rejected rather than dropped"
      (let [resp (send! handler :get (str "/" tenant "/fhir/Consent?not-a-search-param=zzz"))]
        (is (= 400 (:status resp)))
        (is (= "OperationOutcome" (get-in resp [:body :resourceType])))
        (is (= #{} (matched-ids resp)) "no resources are returned")
        (is (= ["not-supported"] (mapv :code (outcome-issues resp))))
        (is (some #(str/includes? (:diagnostics %) "not-a-search-param")
                  (outcome-issues resp)))))

    (testing "a parameter that exists on other types but not this one is rejected"
      (let [resp (send! handler :get (str "/" tenant "/fhir/Endpoint?patient=Patient/p1"))]
        (is (= 400 (:status resp)))
        (is (= #{} (matched-ids resp)))))

    (testing "handling=lenient widens the result set but says so in the Bundle"
      (let [resp (send! handler :get (str "/" tenant "/fhir/Consent?not-a-search-param=zzz")
                        {"prefer" "handling=lenient"})
            issues (outcome-issues resp)]
        (is (= 200 (:status resp)))
        (is (= #{"c1" "c2" "c3"} (matched-ids resp))
            "the ignored parameter no longer constrains the search")
        (is (seq issues) "the searchset carries an OperationOutcome")
        (is (= ["warning"] (mapv :severity issues)))
        (is (some #(str/includes? (:diagnostics %) "not-a-search-param") issues))
        (is (some #(= "outcome" (get-in % [:search :mode])) (get-in resp [:body :entry]))
            "the outcome travels as a search.mode=outcome entry")
        (is (= 3 (get-in resp [:body :total]))
            "total counts matches only, not the outcome entry")))

    (testing "the self link describes the search that actually ran"
      (let [resp (send! handler :get (str "/" tenant "/fhir/Consent?not-a-search-param=zzz")
                        {"prefer" "handling=lenient"})
            self (->> (get-in resp [:body :link])
                      (filter #(= "self" (:relation %)))
                      first)]
        (is (not (str/includes? (:url self) "not-a-search-param")))))))

(deftest every-searchable-type-rejects-an-unknown-parameter
  (let [handler (app (mock/create-mock-store {}))]
    (doseq [rt @served-types
            :when (searchable? rt)]
      (testing rt
        (let [resp (send! handler :get (str "/" tenant "/fhir/" rt "?zzz-unknown-param=1"))]
          (is (= 400 (:status resp)) (str rt " must reject an unknown search parameter"))
          (is (= "OperationOutcome" (get-in resp [:body :resourceType]))))))))

(deftest conditional-delete-with-unknown-criteria-deletes-nothing
  (let [store (seeded-store)
        handler (app store)
        resp (send! handler :delete (str "/" tenant "/fhir/Consent?not-a-search-param=zzz"))]
    (is (= 400 (:status resp))
        "a conditional delete whose criteria cannot be honoured must not run")
    (is (= 3 (count (db/search store tenant :Consent {:_count 50 :_skip 0}
                               (registry-for "Consent")))))))

(deftest conditional-delete-requires-criteria
  (let [store (seeded-store)
        handler (app store)
        resp (send! handler :delete (str "/" tenant "/fhir/Consent"))]
    (is (= 400 (:status resp))
        "a conditional delete with no criteria at all must not pick a resource")
    (is (= 3 (count (db/search store tenant :Consent {:_count 50 :_skip 0}
                               (registry-for "Consent")))))))

;; ---------------------------------------------------------------------------
;; The declared contract: patient-scoping parameters exist for every type
;; that can carry patient data
;; ---------------------------------------------------------------------------

(deftest patient-compartment-members-declare-a-link-parameter
  (testing "every searchable member of the R4B Patient compartment registers at
            least one of the parameters that links it to a Patient, so a
            patient-scoped search on it can be expressed at all"
    (doseq [rt @served-types
            :let [links (compartment/compartment-link-params "Patient" rt)]
            :when (and (searchable? rt) (seq links))]
      (let [registry (registry-for rt)
            registered (filterv #(contains? registry %) links)]
        (is (seq registered)
            (str rt " is in the Patient compartment via " (pr-str (vec links))
                 " but registers none of them; a patient-scoped search on "
                 rt " cannot be expressed, and the compartment route fails closed"))))))

(deftest consent-registers-the-shared-clinical-patient-parameter
  (testing "Consent declares no searchParam of its own in the CapabilityStatement
            JSON; `patient` reaches the registry through the base R4B merge"
    (let [registry (registry-for "Consent")]
      (is (contains? registry "patient"))
      (is (= "reference" (:type (get registry "patient"))))
      (is (some #(= "patient" (:col %)) (:columns (get registry "patient")))))))

(deftest metadata-advertises-only-parameters-search-will-honour
  (let [handler (app (mock/create-mock-store {}))
        resp (send! handler :get (str "/" tenant "/fhir/metadata"))
        by-type (into {} (map (juxt :type identity))
                      (get-in resp [:body :rest 0 :resource]))]
    (is (= 200 (:status resp)))

    (testing "the types whose IG entry carries no searchParam block still
              advertise the parameters the base R4B merge gave them"
      (doseq [rt ["Consent" "Appointment" "Basic" "Claim" "Composition" "Endpoint"
                  "FamilyMemberHistory" "HealthcareService" "List" "Media"
                  "Medication" "MedicationStatement" "Person" "Provenance"
                  "Questionnaire" "Schedule" "Slot" "Task"]]
        (is (seq (:searchParam (get by-type rt)))
            (str rt " advertises no search parameters"))))

    (testing "nothing the CapabilityStatement advertises would be rejected by
              the search handler: every advertised parameter resolves in the
              registry the same schema carries"
      (doseq [rt @served-types
              :when (searchable? rt)]
        (let [advertised (into #{} (map :name) (:searchParam (get by-type rt)))
              registry (or (registry-for rt) {})]
          (is (empty? (remove registry advertised))
              (str rt " advertises " (pr-str (vec (sort (remove registry advertised))))
                   ", which the registry does not resolve, so search would answer 400")))))))
