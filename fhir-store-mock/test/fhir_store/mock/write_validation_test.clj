(ns fhir-store.mock.write-validation-test
  "Given :resource/schemas, the mock refuses a write the router's request
   coercion would have refused: a value still in wire form, or one the
   transformer never decoded."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.util :as mu]
            [com.breezeehr.fhir-primitives :as fprim]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as protocol]))

(def ^:private observation-schema
  (m/schema
   [:map {:resourceType "Observation"}
    [:resourceType [:= "Observation"]]
    [:id {:optional true} :string]
    [:status :string]
    [:effectiveDate {:optional true} :time/local-date]
    [:valueQuantity {:optional true}
     [:map [:value :decimal] [:unit {:optional true} :string]]]]
   fprim/fhir-registry-options))

(def ^:private valid
  {:resourceType  "Observation"
   :status        "final"
   :effectiveDate (java.time.LocalDate/of 2026 11 9)
   :valueQuantity {:value 4242M :unit "mg"}})

(def ^:private tenant "write-validation")

(defn- validating-store []
  (mock/create-mock-store {:resource/schemas [observation-schema]}))

(defn- refusal
  "The ex-data carrying `:fhir/status` in what `f` throws, walking the cause
   chain as the server's exception middleware does (a store span wraps the
   transaction's), or nil when it returns."
  [f]
  (try (f) nil
       (catch Exception e
         (loop [x e]
           (when x
             (or (some-> (ex-data x) (#(when (:fhir/status %) %)))
                 (recur (.getCause x))))))))

(defn- paths [refused] (set (:fhir/expression refused)))

(deftest a-store-given-no-schemas-checks-nothing
  (let [store (mock/create-mock-store {})]
    (is (some? (protocol/create-resource store tenant "Observation" "o1"
                                         (assoc-in valid [:valueQuantity :value] 4242))))))

(deftest a-decoded-write-is-accepted
  (let [store (validating-store)]
    (is (some? (protocol/create-resource store tenant "Observation" "o1" valid)))
    (is (some? (protocol/update-resource store tenant "Observation" "o1" (assoc valid :status "amended"))))))

(deftest an-undecoded-write-is-a-422
  (let [store (validating-store)]
    (testing "a Long where the schema names a decimal"
      (let [refused (refusal #(protocol/create-resource store tenant "Observation" "o1"
                                                        (assoc-in valid [:valueQuantity :value] 4242)))]
        (is (= 422 (:fhir/status refused)))
        (is (= "invalid" (:fhir/code refused)))
        (is (= "Observation" (:resource-type refused)))
        (is (= #{"Observation.valueQuantity.value"} (paths refused)))
        (is (= [{:path "Observation.valueQuantity.value" :schema-type :decimal}] (:errors refused)))
        (is (not (str/includes? (pr-str refused) "4242")) "no value of the resource in the refusal")
        (is (nil? (protocol/read-resource store tenant "Observation" "o1")) "nothing written")))
    (testing "a date still in wire form"
      (is (= #{"Observation.effectiveDate"}
             (paths (refusal #(protocol/create-resource store tenant "Observation" "o2"
                                                        (assoc valid :effectiveDate "2026-11-09")))))))
    (testing "a required element missing"
      (is (= #{"Observation.status"}
             (paths (refusal #(protocol/create-resource store tenant "Observation" "o3"
                                                        (dissoc valid :status)))))))))

(deftest an-update-is-checked-and-leaves-the-current-version
  (let [store (validating-store)]
    (protocol/create-resource store tenant "Observation" "o1" valid)
    (is (= 422 (:fhir/status (refusal #(protocol/update-resource store tenant "Observation" "o1"
                                                                 (assoc-in valid [:valueQuantity :value] 1))))))
    (let [current (protocol/read-resource store tenant "Observation" "o1")]
      (is (= "1" (get-in current [:meta :versionId])))
      (is (= 4242M (get-in current [:valueQuantity :value]))))))

(deftest one-invalid-entry-rolls-back-the-transaction
  (let [store (validating-store)]
    (is (= 422 (:fhir/status
                (refusal #(protocol/transact-transaction
                           store tenant
                           [{:request {:method "PUT" :url "Observation/t1"} :resource valid}
                            {:request {:method "POST" :url "Observation"}
                             :resource (assoc-in valid [:valueQuantity :value] 1)}])))))
    (is (nil? (protocol/read-resource store tenant "Observation" "t1")))))

(deftest a-type-with-no-schema-is-not-checked
  (let [store (validating-store)]
    (is (some? (protocol/create-resource store tenant "Patient" "p1" {:resourceType "Patient" :birthDate "1990-01-01"})))))

(deftest the-cap-schema-property-is-the-one-checked
  (let [lenient (mu/update-properties (m/schema [:map {:resourceType "Observation"}] fprim/fhir-registry-options)
                                      assoc :fhir/cap-schema observation-schema)
        store   (mock/create-mock-store {:resource/schemas [lenient]})]
    (is (= 422 (:fhir/status (refusal #(protocol/create-resource store tenant "Observation" "o1"
                                                                 (dissoc valid :status))))))))
