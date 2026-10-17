(ns fhir-store-xtdb2.minted-id-create-test
  "create-resource's :id-minted? skips the existence ASSERT for an id the
   caller generated itself; without it an existing id is still refused."
  (:require [clojure.test :refer [deftest is testing]]
            [xtdb.api]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]))

(defn- fhir-status
  "The :fhir/status on the cause chain; the store's trace spans wrap what it
   throws, and the status sits above XTDB's own assertion error."
  [^Throwable e]
  (some #(:fhir/status (ex-data %)) (take-while some? (iterate ex-cause e))))

(deftest minted-ids-skip-the-existence-check
  (let [store (core-db/create-xtdb-store {})]
    (try
      (testing "a minted create writes version 1 and reads back"
        (let [res (db/create-resource store "t" :Patient "m1"
                                      {:resourceType "Patient" :id "m1" :gender "female"}
                                      {:id-minted? true})]
          (is (= "1" (get-in res [:meta :versionId])))
          (is (= "female" (:gender (db/read-resource store "t" :Patient "m1"))))))
      (testing "without the flag, an existing id is still refused with a 409"
        (db/create-resource store "t" :Patient "c1" {:resourceType "Patient" :id "c1"})
        (is (= 409 (try (db/create-resource store "t" :Patient "c1" {:resourceType "Patient" :id "c1"})
                        nil
                        (catch Exception e (fhir-status e))))))
      (testing "a minted create that fails for another reason is not reported as a 409"
        (let [boom (ex-info "boom" {})]
          (with-redefs [xtdb.api/execute-tx (fn [& _] (throw boom))]
            (is (not= 409 (try (db/create-resource store "t" :Patient "m2"
                                                   {:resourceType "Patient" :id "m2"}
                                                   {:id-minted? true})
                               nil
                               (catch Exception e (fhir-status e))))))))
      (finally
        (db/delete-tenant store "t" {:if-absent :ignore})))))
