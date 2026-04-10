(ns fhir-store-xtdb2.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [xtdb.api :as xt]
            [xtdb.node :as xtn]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]))

(defn- close-store-nodes!
  "Closes all tenant nodes in a store's nodes atom."
  [store]
  (doseq [[_ node] @(:nodes store)]
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

(deftest test-transact-bundle
  (testing "Can transact a FHIR Bundle of type batch/transaction"
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
        (let [response (db/transact-bundle store tenant-id (:entry test-bundle))]
          (is (= "Bundle" (:resourceType response)))
          (is (= "transaction-response" (:type response)))
          (is (= 3 (count (:entry response))))

          ;; First POST -> 201 Created with location and etag
          (let [post-entry (-> response :entry first)]
            (is (= "201 Created" (-> post-entry :response :status)))
            (is (some? (-> post-entry :response :location)))
            (is (string? (-> post-entry :response :etag)))
            (is (some? (-> post-entry :resource))))

          ;; Second PUT -> 200 OK with etag
          (let [put-entry (-> response :entry second)]
            (is (= "200 OK" (-> put-entry :response :status)))
            (is (some? (-> put-entry :response :etag)))
            (is (some? (-> put-entry :resource))))

          ;; Third DELETE -> 204 No Content
          (is (= "204 No Content" (-> response :entry last :response :status))))

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
