(ns com.breezeehr.primitive-absence-generation-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.breezeehr.fhir-defintions-to-malli :as fdm]
            [com.breezeehr.fhir-primitives :as fp]
            [malli.core :as m]
            [malli.util :as mu]))

(def dar {:extension [{:url "http://hl7.org/fhir/StructureDefinition/data-absent-reason"
                       :valueCode "unknown"}]})

(defn base [code]
  (m/schema [:map [:name {:optional true} [:string {:fhir/primitive code}]]
             [:_name {:optional true} :map]] fp/fhir-registry-options))

(defn patch [parent code attr]
  (let [result (binding [fdm/*schema-atom* (atom {})
                         fdm/*references-atom* (atom #{})
                         fdm/*recursive-references* #{}
                         fdm/*base-refs* (atom {})]
                 (fdm/apply-element-patch
                   {:sch parent :form [] :shape {:name {:type code :max "1"}}}
                   "Organization.name" :name nil (merge {:id "Organization.name"} attr)
                   [] ["Organization" "name"] "4.3.0"))
        apply-form (binding [*ns* (the-ns 'com.breezeehr.fhir-defintions-to-malli)]
                     (eval (list 'fn '[parent] (apply list '-> 'parent (:form result)))))]
    (apply-form parent)))

(deftest inherited-mandatory-string-accepts-absence
  (doseq [code ["string" "markdown"]]
    (let [s (patch (base code) code {:min 1})]
      (is (= :map (m/type s)))
      (is (m/validate s {:name "Clinic"}))
      (is (m/validate s {:_name dar}))
      (is (not (m/validate s {})))
      (is (not (m/validate s {:_name {}})))
      (is (= :string (m/type (mu/get s :name)))))))

(deftest required-code-and-cda-attributes-remain-values
  (is (not (m/validate (patch (base "code") "code" {:min 1}) {:_name dar})))
  (is (not (m/validate (patch (base "string") "string" {:min 1 :representation ["xmlAttr"]}) {:_name dar}))))

(deftest derived-value-requirement-cannot-be-relaxed-again
  (doseq [restriction [{:mustHaveValue true} {:patternString "Clinic"}]]
    (let [s (patch (base "string") "string" {:min 1})
          restricted (patch s "string" restriction)
          derived (patch restricted "string" {:min 1})]
      (is (not (m/validate restricted {:_name dar})))
      (is (not (m/validate derived {:_name dar})))
      (is (m/validate derived {:name "Clinic"}))))
  (let [s (patch (base "string") "string" {:min 1 :mustHaveValue true})]
    (is (not (m/validate (patch s "string" {:min 1}) {:_name dar}))))
)
