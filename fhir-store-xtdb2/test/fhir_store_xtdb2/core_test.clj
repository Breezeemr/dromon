(ns fhir-store-xtdb2.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [malli.core :as m]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]))

(defn- close-store-nodes!
  "Closes all tenant pools + nodes in a store's nodes atom."
  [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(deftest test-fhir-update-and-read
  (testing "Can insert and read a FHIR resource for a specific tenant"
    (let [store (core-db/create-xtdb-store {})
          tenant-id "tenant-a"
          patient-doc {:active true
                       :name [{"family" "Doe"}]}]
      (try
        ;; Execute the transaction using the IFHIRStore protocol
        (println "Creating resource...")
        (db/create-resource store tenant-id :Patient "123" patient-doc)

        ;; Test basic query.
        (println "Reading resource...")
        (let [result (db/read-resource store tenant-id :Patient "123")]
          (is (some? result))
          (is (= "123" (:id result))) ;; _id maps to :id
          (is (= true (:active result)))
          (is (= [{:family "Doe"}] (:name result))))

        ;; Test search
        (println "Searching resources...")
        (let [search-results (db/search store tenant-id :Patient {:active true} nil)]
          (is (= 1 (count search-results)))
          (is (= "123" (:id (first search-results)))))

        (let [empty-search (db/search store tenant-id :Patient {:active false} nil)]
          (is (= 0 (count empty-search))))

        ;; Test tenant isolation: data written to tenant-a should not be visible from tenant-b
        (println "Testing tenant isolation...")
        (let [isolated-result (db/read-resource store "tenant-b" :Patient "123")]
          (is (nil? isolated-result) "Tenant-b should NOT see tenant-a's data"))

        ;; Test update and history
        (println "Updating resource...")
        (let [time-before-update (str (java.time.Instant/now))]
          (Thread/sleep 10) ;; Ensure time progresses
          (db/update-resource store tenant-id :Patient "123" (assoc patient-doc :active false))

          (println "Testing history...")
          (let [hist (db/history store tenant-id :Patient "123")]
            (is (= 2 (count hist)))
            ;; History contains both the original and updated versions
            (let [active-versions (filter :active hist)
                  inactive-versions (remove :active hist)]
              (is (= 1 (count active-versions)))
              (is (= 1 (count inactive-versions)))))

          ;; Test vread
          ;; We captured time-before-update, so vread should return the active version
          (println "Testing vread...")
          (let [vread-res (db/vread-resource store tenant-id :Patient "123" time-before-update)]
            (println "Done testing vread.")
            (is (some? vread-res))
            (is (= true (:active vread-res)))))
        (finally
          (close-store-nodes! store))))))

(def ^:private patient-schema-without-race
  "Mimics the us-core capability :multi dispatching a Patient without a
   us-core meta.profile onto the base R4B branch: the schema carries no entry
   for the promoted :race extension struct, so the schema-driven keyword->
   string key conversion never reaches it and the post-encode stringify walk
   must protect the struct field names from XTDB case folding."
  (m/schema
    [:map {:resourceType "Patient"}
     [:id {:optional true} :string]
     [:active {:optional true} :boolean]
     [:name {:optional true} [:sequential [:map [:family {:optional true} :string]]]]]))

(deftest test-promoted-extension-struct-key-case
  (testing "schema-uncovered nested struct keys round-trip case-exactly (us-core-race)"
    (let [store (core-db/create-xtdb-store {:resource/schemas [patient-schema-without-race]})
          tenant-id "tenant-race"
          ;; Promoted us-core-race / us-core-ethnicity extension shape as the
          ;; FHIR JSON transform produces it: camelCase sub-extension keys.
          race {:ombCategory [{:system "urn:oid:2.16.840.1.113883.6.238"
                               :code "2106-3"
                               :display "White"}]
                :text ["White"]}]
      (try
        (db/create-resource store tenant-id :Patient "p-race"
                            {:active true
                             :name [{:family "Doe"}]
                             :race race})
        (let [result (db/read-resource store tenant-id :Patient "p-race")]
          (is (= race (:race result))
              "camelCase struct field names must survive the XTDB round trip")
          (is (not (contains? (:race result) :ombcategory))
              "XTDB must not case-fold the ombCategory struct field"))
        (finally (close-store-nodes! store))))))

(defn- root-ex-data
  "Walk the cause chain and return the first ex-data that carries :fhir/status.
   Required because store-layer exceptions are wrapped by telemere `trace!`,
   whose own ex-data hides the inner FHIR status."
  [ex]
  (loop [e ex]
    (cond
      (nil? e) nil
      (some-> e ex-data :fhir/status) (ex-data e)
      :else (recur (.getCause e)))))

(deftest test-if-match-update
  (testing "Store enforces :if-match atomically for update-resource"
    (let [store (core-db/create-xtdb-store {})
          tenant-id "tenant-ifmatch"
          patient {:active true :name [{"family" "Doe"}]}]
      (try
        (db/create-resource store tenant-id :Patient "p1" patient)
        (testing "matching if-match succeeds"
          (let [res (db/update-resource store tenant-id :Patient "p1"
                                        (assoc patient :active false)
                                        {:if-match "1"})]
            (is (= "2" (get-in res [:meta :versionId])))))
        (testing "mismatched if-match -> 412"
          (let [e (try
                    (db/update-resource store tenant-id :Patient "p1"
                                        (assoc patient :active true)
                                        {:if-match "1"})
                    nil
                    (catch Throwable ex ex))]
            (is (some? e))
            (is (= 412 (:fhir/status (root-ex-data e))))))
        (testing "if-match against nonexistent -> 412"
          (let [e (try
                    (db/update-resource store tenant-id :Patient "missing"
                                        patient {:if-match "1"})
                    nil
                    (catch Throwable ex ex))]
            (is (some? e))
            (is (= 412 (:fhir/status (root-ex-data e))))))
        (finally (close-store-nodes! store))))))

(deftest test-transact-transaction
  (testing "Can transact a FHIR Bundle of type transaction (atomic)"
    (let [store (core-db/create-xtdb-store {})
          tenant-id "tenant-tx"
          ;; Create a bundle with a POST, PUT, and DELETE
          test-bundle
          {:resourceType "Bundle"
           :type "transaction"
           :entry
           [{:request {:method "POST" :url "Patient"}
             :resource {:resourceType "Patient" :name [{"family" "BundleNew"}]}}
            {:request {:method "PUT" :url "Patient/tx-upd"}
             :resource {:resourceType "Patient" :id "tx-upd" :active true}}
            ;; We'll just do a dummy DELETE for a resource that might not exist,
            ;; or we assume the transaction applies operations in order-ish or atomically.
            {:request {:method "DELETE" :url "Patient/tx-del"}}]}]
      (try
        ;; Ensure tx-del exists so DELETE does something (though XTDB won't complain if it doesn't)
        (db/create-resource store tenant-id :Patient "tx-del" {:resourceType "Patient" :active false})
        (is (some? (db/read-resource store tenant-id :Patient "tx-del")))

        ;; Transact!
        (let [response (db/transact-transaction store tenant-id (:entry test-bundle))]
          (is (= "Bundle" (:resourceType response)))
          (is (= "transaction-response" (:type response)))
          (is (= 3 (count (:entry response))))

          ;; Entries are returned in processed order per FHIR §3.1.0.11.2:
          ;; DELETE -> POST -> PUT/PATCH -> GET/HEAD.
          ;; Input order was POST, PUT, DELETE, so the response is
          ;; DELETE, POST, PUT.

          ;; First DELETE -> 204 No Content
          (is (= "204 No Content" (-> response :entry first :response :status)))

          ;; Second POST -> 201 Created with location and etag
          (let [post-entry (-> response :entry second)]
            (is (= "201 Created" (-> post-entry :response :status)))
            (is (some? (-> post-entry :response :location)))
            (is (string? (-> post-entry :response :etag)))
            (is (some? (-> post-entry :resource))))

          ;; Third PUT -> 200 OK with etag
          (let [put-entry (-> response :entry last)]
            (is (= "200 OK" (-> put-entry :response :status)))
            (is (some? (-> put-entry :response :etag)))
            (is (some? (-> put-entry :resource)))))

        ;; Verify results in DB
        (println "Verifying Bundle Results...")

        ;; Verify PUT
        (let [upd-patient (db/read-resource store tenant-id :Patient "tx-upd")]
          (is (some? upd-patient))
          (is (= true (:active upd-patient))))

        ;; Verify DELETE
        (let [del-patient (db/read-resource store tenant-id :Patient "tx-del")]
          (is (nil? del-patient)))

        ;; Verify POST (should be 1 new patient with family BundleNew)
        (let [search-res (db/search store tenant-id :Patient {} nil)
              new-patients (filter #(some (fn [n] (= "BundleNew" (:family n))) (:name %)) search-res)]
          (is (= 1 (count new-patients))))
        (finally
          (close-store-nodes! store))))))

(deftest test-search-nested-codeable-concept
  (testing "Token search on a nested CodeableConcept inside a single-cardinality
            BackboneElement (Encounter.hospitalization.dischargeDisposition)
            should match resources stored via the schema-aware encoder."
    (let [enc-schema (m/schema [:map {:resourceType "Encounter"}
                                [:resourceType :string]
                                [:status :string]
                                [:subject :map]
                                [:hospitalization
                                 [:map
                                  [:dischargeDisposition
                                   [:map
                                    [:coding
                                     [:sequential
                                      [:map
                                       [:system :string]
                                       [:code :string]]]]]]]]])
          store (core-db/create-xtdb-store {:resource/schemas [enc-schema]})
          tenant "probe-tok"
          enc {:resourceType "Encounter"
               :status "finished"
               :subject {:reference "Patient/123"}
               :hospitalization {:dischargeDisposition
                                 {:coding [{:system "http://terminology.hl7.org/CodeSystem/discharge-disposition"
                                            :code "home"}]}}}
          ;; Search registry built from the canonical FHIRPath
          ;; (Encounter.hospitalization.dischargeDisposition) — sub-col preserves
          ;; camelCase to match the schema-aware storage shape.
          enc-reg {"discharge-disposition"
                   {:type "token" :target nil
                    :columns [{:col "hospitalization"
                               :fhir-type "BackboneElement"
                               :array? false
                               :sub-col "dischargeDisposition"
                               :sub-fhir-type "CodeableConcept"
                               :sub-array? false}]}}]
      (try
        (db/create-resource store tenant :Encounter "enc1" enc)
        (let [results (db/search store tenant :Encounter
                                 {"discharge-disposition" "home"} enc-reg)]
          (is (= 1 (count results)))
          (is (= "enc1" (:id (first results)))))
        (finally
          (close-store-nodes! store))))))

(deftest test-search-value-quantity
  (testing "value-quantity applies the FHIR prefix numerically against the
            nested Quantity .value, with an optional |system|code unit filter."
    (let [obs-schema (m/schema [:map {:resourceType "Observation"}
                                [:resourceType :string]
                                [:status :string]
                                [:code [:map
                                        [:coding [:sequential
                                                  [:map
                                                   [:system :string]
                                                   [:code :string]]]]]]
                                [:valueQuantity [:map
                                                 [:value :double]
                                                 [:unit :string]
                                                 [:system :string]
                                                 [:code :string]]]])
          store (core-db/create-xtdb-store {:resource/schemas [obs-schema]})
          tenant "probe-vq"
          reg {"code" {:type "token" :target nil
                       :columns [{:col "code" :fhir-type "CodeableConcept" :array? false}]}
               "value-quantity" {:type "quantity" :target nil
                                 :columns [{:col "valueQuantity" :fhir-type "Quantity" :array? false}]}}
          mk (fn [id code v]
               (db/create-resource store tenant :Observation id
                 {:resourceType "Observation" :status "final"
                  :code {:coding [{:system "http://loinc.org" :code code}]}
                  :valueQuantity {:value v :unit "kg"
                                  :system "http://unitsofmeasure.org" :code "kg"}}))
          ids (fn [params] (set (map :id (db/search store tenant :Observation params reg))))]
      (try
        (mk "w30" "29463-7" 30.0)
        (mk "w50" "29463-7" 50.0)
        (mk "w72" "29463-7" 72.5)
        (mk "w90" "29463-7" 90.0)
        (mk "h60" "8302-2"  60.0)
        (testing "ge is inclusive across codes"
          (is (= #{"w50" "w72" "w90" "h60"} (ids {"value-quantity" "ge50"}))))
        (testing "gt is exclusive"
          (is (= #{"w72" "w90" "h60"} (ids {"value-quantity" "gt50"}))))
        (testing "le is inclusive"
          (is (= #{"w30" "w50"} (ids {"value-quantity" "le50"}))))
        (testing "no prefix means equality"
          (is (= #{"w72"} (ids {"value-quantity" "72.5"}))))
        (testing "ap matches within +/-10%"
          (is (= #{"w72"} (ids {"value-quantity" "ap72.5"}))))
        (testing "code token + value-quantity AND together"
          (is (= #{"w50" "w72" "w90"} (ids {"code" "29463-7" "value-quantity" "ge50"}))))
        (testing "matching |system|code unit keeps the result"
          (is (= #{"w50" "w72" "w90"}
                 (ids {"code" "29463-7"
                       "value-quantity" "ge50|http://unitsofmeasure.org|kg"}))))
        (testing "a non-matching unit code excludes everything"
          (is (= #{} (ids {"code" "29463-7"
                           "value-quantity" "ge50|http://unitsofmeasure.org|g"}))))
        (finally
          (close-store-nodes! store))))))

(deftest test-search-token-flat-codeableconcept
  (testing "Token search on top-level Coding/CodeableConcept fields uses the
            denormalized <field>_tokens array: bare code, system|code, comma-OR,
            and the category array all match; the tokens column never leaks."
    (let [obs-schema (m/schema [:map {:resourceType "Observation"}
                                [:resourceType :string]
                                [:status :string]
                                [:category [:sequential
                                            [:map [:coding [:sequential
                                                            [:map [:system :string] [:code :string]]]]]]]
                                [:code [:map [:coding [:sequential
                                                       [:map [:system :string] [:code :string]]]]]]])
          store (core-db/create-xtdb-store {:resource/schemas [obs-schema]})
          tenant "probe-flat-tok"
          reg {"code" {:type "token" :target nil
                       :columns [{:col "code" :fhir-type "CodeableConcept" :array? false}]}
               "category" {:type "token" :target nil
                           :columns [{:col "category" :fhir-type "CodeableConcept" :array? true}]}}
          mk (fn [id code]
               (db/create-resource store tenant :Observation id
                 {:resourceType "Observation" :status "final"
                  :category [{:coding [{:system "http://terminology.hl7.org/CodeSystem/observation-category"
                                        :code "vital-signs"}]}]
                  :code {:coding [{:system "http://loinc.org" :code code}]}}))
          ids (fn [params] (set (map :id (db/search store tenant :Observation params reg))))]
      (try
        (mk "o1" "29463-7")
        (mk "o2" "8302-2")
        (mk "o3" "8867-4")
        (testing "bare code"
          (is (= #{"o1"} (ids {"code" "29463-7"}))))
        (testing "system|code"
          (is (= #{"o1"} (ids {"code" "http://loinc.org|29463-7"}))))
        (testing "wrong system excludes"
          (is (= #{} (ids {"code" "http://snomed.info/sct|29463-7"}))))
        (testing "comma-OR collapses to one IN"
          (is (= #{"o1" "o2"} (ids {"code" "29463-7,8302-2"}))))
        (testing "array CodeableConcept (category)"
          (is (= #{"o1" "o2" "o3"} (ids {"category" "vital-signs"}))))
        (testing "code + category AND"
          (is (= #{"o3"} (ids {"code" "8867-4" "category" "vital-signs"}))))
        (testing "the denormalized token columns do not leak into results"
          (let [r (first (db/search store tenant :Observation {"code" "29463-7"} reg))]
            (is (some? r))
            (is (empty? (filter #(re-find #"tokens$" (name %)) (keys r)))
                "no *_tokens / *-tokens key should survive into the resource")
            (is (= "29463-7" (get-in r [:code :coding 0 :code])))))
        (finally
          (close-store-nodes! store))))))

(deftest test-search-sort-limit-two-phase
  (testing "_sort + _count returns the correctly ordered page of full resources
            (two-phase: sort a narrow _id projection, then fetch the page)."
    ;; effectiveDateTime kept as :string here -- ISO-8601 strings sort
    ;; lexicographically == chronologically, and the two-phase fetch/reorder
    ;; logic under test is independent of the column's storage type.
    (let [obs-schema (m/schema [:map {:resourceType "Observation"}
                                [:resourceType :string]
                                [:status :string]
                                [:effectiveDateTime :string]
                                [:code [:map [:coding [:sequential
                                                       [:map [:system :string] [:code :string]]]]]]])
          store (core-db/create-xtdb-store {:resource/schemas [obs-schema]})
          tenant "probe-sort"
          reg {"date" {:type "date" :target nil
                       :columns [{:col "effectiveDateTime" :fhir-type "dateTime" :array? false}]}}
          mk (fn [id d]
               (db/create-resource store tenant :Observation id
                 {:resourceType "Observation" :status "final"
                  :effectiveDateTime d
                  :code {:coding [{:system "http://loinc.org" :code "29463-7"}]}}))]
      (try
        (mk "a" "2011-01-01T00:00:00Z")
        (mk "b" "2015-01-01T00:00:00Z")
        (mk "c" "2013-01-01T00:00:00Z")
        (mk "d" "2012-01-01T00:00:00Z")
        (testing "descending date page of 2 returns newest two in order, with full bodies"
          (let [res (db/search store tenant :Observation {"_sort" "-date,_id" "_count" "2"} reg)]
            (is (= ["b" "c"] (mapv :id res)))
            (is (= "2015-01-01T00:00:00Z" (:effectiveDateTime (first res))))
            (is (= "final" (:status (first res))))
            (is (= "29463-7" (get-in (first res) [:code :coding 0 :code])))))
        (testing "ascending date returns oldest first"
          (is (= ["a" "d" "c" "b"]
                 (mapv :id (db/search store tenant :Observation {"_sort" "date,_id" "_count" "10"} reg)))))
        (testing "_skip pages through the sorted set"
          ;; desc order is b(2015) c(2013) d(2012) a(2011); skip 2 -> [d a]
          (is (= ["d" "a"]
                 (mapv :id (db/search store tenant :Observation
                                      {"_sort" "-date,_id" "_count" "2" "_skip" "2"} reg)))))
        (finally
          (close-store-nodes! store))))))

(deftest test-transact-bundle-batch
  (testing "Batch bundle: per-entry success/failure, no rollback between entries"
    (let [store (core-db/create-xtdb-store {})
          tenant-id "tenant-batch"]
      (try
        (db/create-resource store tenant-id :Patient "alive" {:resourceType "Patient" :active true})
        (let [entries [{:request {:method "POST" :url "Patient"}
                        :resource {:resourceType "Patient" :name [{"family" "Batchy"}]}}
                       {:request {:method "GET" :url "Patient/alive"}}
                       {:request {:method "GET" :url "Patient/does-not-exist"}}
                       {:request {:method "BOGUS" :url "Patient/x"}}]
              result (db/transact-bundle store tenant-id entries)]
          (is (= "Bundle" (:resourceType result)))
          (is (= "batch-response" (:type result)))
          (is (= 4 (count (:entry result))) "result preserves input order")
          (let [[post get-ok get-missing bogus] (:entry result)]
            (is (= "201 Created" (get-in post [:response :status])))
            (is (some? (:resource post)))
            (is (= "200 OK" (get-in get-ok [:response :status])))
            (is (= "alive" (get-in get-ok [:resource :id])))
            (is (= "404 Not Found" (get-in get-missing [:response :status])))
            (is (= "400 Bad Request" (get-in bogus [:response :status]))))
          (testing "alive entry was untouched despite a sibling failing"
            (is (some? (db/read-resource store tenant-id :Patient "alive")))))
        (finally
          (close-store-nodes! store))))))

(deftest test-versionid-monotonic
  (testing "Server-managed integer-monotonic versionIds on create/update/read"
    (let [store (core-db/create-xtdb-store {})
          tenant-id "tenant-ver"
          patient {:resourceType "Patient" :active true :name [{"family" "Ver"}]}]
      (try
        (let [created (db/create-resource store tenant-id :Patient "vp1" patient)]
          (is (= "1" (get-in created [:meta :versionId]))
              "create-resource returns versionId '1'"))

        (let [read1 (db/read-resource store tenant-id :Patient "vp1")]
          (is (= "1" (get-in read1 [:meta :versionId]))
              "read after create exposes versionId '1'"))

        (let [updated (db/update-resource store tenant-id :Patient "vp1"
                                          (assoc patient :active false))]
          (is (= "2" (get-in updated [:meta :versionId]))
              "update-resource returns bumped versionId '2'"))

        (let [read2 (db/read-resource store tenant-id :Patient "vp1")]
          (is (= "2" (get-in read2 [:meta :versionId]))
              "read after update exposes versionId '2'")
          (is (= false (:active read2))
              "read after update returns the updated payload"))
        (finally
          (close-store-nodes! store))))))

(defn- fhir-instant?
  "True when s is a parseable ISO instant with no zone-name suffix, i.e. a
   valid FHIR instant for :meta :lastUpdated (guards against the raw
   ZonedDateTime str form \"...Z[UTC]\" leaking through)."
  [s]
  (and (string? s)
       (not (str/includes? s "["))
       (some? (try (java.time.Instant/parse s)
                   (catch Exception _ nil)))))

(deftest test-meta-last-updated-populated
  (testing "read/vread/search/history all inject :meta :lastUpdated from _system_from"
    (let [store (core-db/create-xtdb-store {})
          tenant-id "tenant-lastupdated"
          patient {:resourceType "Patient" :active true :name [{"family" "Stamp"}]}]
      (try
        (db/create-resource store tenant-id :Patient "lu1" patient)
        (let [time-before-update (str (java.time.Instant/now))]
          (Thread/sleep 10)
          (db/update-resource store tenant-id :Patient "lu1" (assoc patient :active false))

          (testing "read"
            (let [res (db/read-resource store tenant-id :Patient "lu1")]
              (is (fhir-instant? (get-in res [:meta :lastUpdated])))))

          (testing "vread"
            (let [res (db/vread-resource store tenant-id :Patient "lu1" time-before-update)]
              (is (some? res))
              (is (fhir-instant? (get-in res [:meta :lastUpdated])))))

          (testing "search without sort (single SELECT branch)"
            (let [res (db/search store tenant-id :Patient {:active false} nil)]
              (is (= 1 (count res)))
              (is (fhir-instant? (get-in (first res) [:meta :lastUpdated])))))

          (testing "search with sort (two-phase fetch-by-ids branch)"
            (let [res (db/search store tenant-id :Patient {"_sort" "_id"} nil)]
              (is (= 1 (count res)))
              (is (fhir-instant? (get-in (first res) [:meta :lastUpdated])))))

          (testing "instance history"
            (let [hist (db/history store tenant-id :Patient "lu1")]
              (is (= 2 (count hist)))
              (is (every? #(fhir-instant? (get-in % [:meta :lastUpdated])) hist))))

          (testing "type history"
            (let [hist (db/history-type store tenant-id :Patient {})]
              (is (= 2 (count hist)))
              (is (every? #(fhir-instant? (get-in % [:meta :lastUpdated])) hist)))))
        (finally
          (close-store-nodes! store))))))

(defn- delete-recursive!
  [^java.io.File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (delete-recursive! c)))
    (.delete f)))

(deftest test-create-tenant-basic
  (testing "create-tenant makes the tenant queryable"
    (let [store (core-db/create-xtdb-store {})]
      (try
        (db/create-tenant store "t1")
        (is (nil? (db/read-resource store "t1" :Patient "nope"))
            "read against a freshly-created tenant returns nil, not an error")
        (finally (close-store-nodes! store))))))

(deftest test-create-tenant-conflict
  (testing "create-tenant twice with defaults throws 409"
    (let [store (core-db/create-xtdb-store {})]
      (try
        (db/create-tenant store "t1")
        (let [e (try (db/create-tenant store "t1") nil
                     (catch Throwable ex ex))]
          (is (some? e))
          (is (= 409 (:fhir/status (root-ex-data e)))))
        (finally (close-store-nodes! store))))))

(deftest test-create-tenant-if-exists-ignore
  (testing "create-tenant with :if-exists :ignore is a no-op"
    (let [store (core-db/create-xtdb-store {})]
      (try
        (db/create-tenant store "t1")
        (db/create-resource store "t1" :Patient "p1" {:active true})
        (db/create-tenant store "t1" {:if-exists :ignore})
        (is (some? (db/read-resource store "t1" :Patient "p1"))
            "prior resource still present after :ignore recreate")
        (finally (close-store-nodes! store))))))

(deftest test-create-tenant-if-exists-replace
  (testing "create-tenant with :if-exists :replace drops prior data"
    (let [store (core-db/create-xtdb-store {})]
      (try
        (db/create-tenant store "t1")
        (db/create-resource store "t1" :Patient "p1" {:active true})
        (is (some? (db/read-resource store "t1" :Patient "p1")))
        (db/create-tenant store "t1" {:if-exists :replace})
        (is (nil? (db/read-resource store "t1" :Patient "p1"))
            "prior resource gone after :replace")
        (finally (close-store-nodes! store))))))

(deftest test-delete-tenant-clears-data
  (testing "delete-tenant removes resources"
    (let [store (core-db/create-xtdb-store {})]
      (try
        (db/create-tenant store "t1")
        (db/create-resource store "t1" :Patient "p1" {:active true})
        (db/delete-tenant store "t1")
        (db/create-tenant store "t1")
        (let [results (db/search store "t1" :Patient {} nil)]
          (is (empty? results)
              "search on recreated tenant returns empty"))
        (finally (close-store-nodes! store))))))

(deftest test-delete-tenant-if-absent-ignore
  (testing "delete-tenant with :if-absent :ignore on missing tenant is a no-op"
    (let [store (core-db/create-xtdb-store {})]
      (try
        (is (nil? (db/delete-tenant store "does-not-exist"
                                    {:if-absent :ignore})))
        (finally (close-store-nodes! store))))))

(deftest test-delete-tenant-missing-throws
  (testing "delete-tenant with defaults on missing tenant throws 404"
    (let [store (core-db/create-xtdb-store {})]
      (try
        (let [e (try (db/delete-tenant store "nope") nil
                     (catch Throwable ex ex))]
          (is (some? e))
          (is (= 404 (:fhir/status (root-ex-data e)))))
        (finally (close-store-nodes! store))))))

(deftest test-delete-tenant-close-storage
  (testing "delete-tenant with :close-storage? true removes on-disk files"
    (let [base (java.nio.file.Files/createTempDirectory
                 "dromon-xtdb2-tenant-"
                 (into-array java.nio.file.attribute.FileAttribute []))
          base-str (.toString base)
          log-path (str base-str "/log")
          storage-path (str base-str "/storage")
          store (core-db/create-xtdb-store
                  {:node-config {:log [:local {:path log-path}]
                                 :storage [:local {:path storage-path}]}})]
      (try
        (db/create-tenant store "t1")
        (is (.exists (java.io.File. log-path))
            "log directory created on tenant start")
        (is (.exists (java.io.File. storage-path))
            "storage directory created on tenant start")
        (db/delete-tenant store "t1" {:close-storage? true})
        (is (not (.exists (java.io.File. log-path)))
            "log directory removed")
        (is (not (.exists (java.io.File. storage-path)))
            "storage directory removed")
        (finally
          (close-store-nodes! store)
          (delete-recursive! (java.io.File. base-str)))))))

(deftest test-warmup-tenant-idempotent
  (testing "warmup-tenant is idempotent on a fresh in-memory store"
    (let [store (core-db/create-xtdb-store {})]
      (try
        (db/warmup-tenant store "t1")
        (db/warmup-tenant store "t1")
        (is (nil? (db/read-resource store "t1" :Patient "nope")))
        (finally (close-store-nodes! store))))))
