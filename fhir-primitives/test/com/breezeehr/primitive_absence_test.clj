(ns com.breezeehr.primitive-absence-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.breezeehr.fhir-primitives :as fp]
            [malli.core :as m]
            [malli.util :as mu]
            [malli.transform :as mt]))

(def dar {:extension [{:url "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
                       :valueCode "unknown"}]})
(defn schema [props value]
  (m/schema [:map [:name props value]
             [:_name {:optional true} [:map [:extension {:optional true} [:vector [:map [:url :string] [:valueCode :string]]]]]]]
            fp/fhir-registry-options))

(deftest required-primitive-pair
  (let [s (schema {:fhir/primitive-absence true} :string)]
    (doseq [x [{:name "Clinic"} {:_name dar} {:name "Clinic" :_name dar}]]
      (is (m/validate s x))
      (is (= x (m/parse s x)))
      (is (= x (m/unparse s x))))
    (doseq [x [{} {:name nil} {:name 42} {:_name {}} {:_name {:id "x"}}
               {:_name {:extension []}} {:_name (assoc-in dar [:extension 0 :valueCode] "invented")}
               {:_name (assoc-in dar [:extension 0 :url] "http://example.org/unknown")}
               {:_name (assoc-in dar [:extension 0 :valueString] "extra")}
               {:_name (assoc-in dar [:extension 0 :extension] [{:url "extra" :valueCode "x"}])}]]
      (is (false? (m/validate s x)) (pr-str x))
      (is (seq (:errors (m/explain s x))))
      (is (= :malli.core/invalid (m/parse s x)))
      (is (= :malli.core/invalid (m/unparse s x)))))
  (testing "ordinary required strings and fixed/coded values stay mandatory"
    (doseq [s [(schema {} :string)
               (schema {:fhir/primitive-absence false} :string)
               (schema {:fhir/primitive-absence true :xml/attr true} :string)
               (schema {:fhir/primitive-absence true} [:enum "fixed"])]]
      (is (not (m/validate s {:_name dar}))))))

(deftest required-primitive-structural-compatibility
  (let [base (schema {:optional true} :string)
        s (mu/update-entry-properties base :name merge {:optional false :fhir/primitive-absence true})]
    (is (= :map (m/type s)))
    (is (= :string (m/type (mu/get s :name))))
    (is (= [:name :_name] (mapv first (m/entries s))))
    (is (false? (:optional (second (first (m/children s))))))
    (doseq [s [s (m/schema (m/form s) fp/fhir-registry-options)
               (m/from-ast (m/ast s) fp/fhir-registry-options)
               (mu/assoc s :other [:string])]]
      (let [x (cond-> {:_name dar} (mu/get s :other) (assoc :other "x"))]
        (is (m/validate s x))
        (is (= x (m/decode s x mt/json-transformer)))
        (is (= x (m/encode s x mt/json-transformer)))))
    (is (not (m/validate (mu/dissoc s :_name) {:_name dar})))
    (is (m/validate (mu/optional-keys s [:name]) {}))))

(deftest ordinary-map-retains-lazy-compilation-and-independent-cache
  (let [s (m/schema [:map [:name :string]] fp/fhir-registry-options)]
    (dotimes [_ 3]
      (is (m/validate s {:name "Clinic"}))
      (is (not (m/validate s {})))
      (is (= {:name "Clinic"} (m/parse s {:name "Clinic"}))))))

(deftest unrelated-map-keys-retain-malli-behavior
  (let [s (m/schema [:map [1 :string] ["key" :int]] fp/fhir-registry-options)]
    (is (m/validate s {1 "one" "key" 2}))
    (is (not (m/validate s {1 "one"})))))
