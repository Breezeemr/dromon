(ns com.breezeehr.fhir-xml-test
  "Each test pins one construct the R4B example corpus proves is real. The full
  1156-file gate lives in dev/gate.clj (clj -M:gate); these fixtures are the
  representative hard cases drawn from it."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [malli.core :as m]
            [com.breezeehr.fhir-xml :as fx]
            [com.breezeehr.fhir-xml-canonical :as canon]))

(def resource-schema
  (memoize
   (fn [type-name]
     (some-> (requiring-resolve
              (symbol (str "org.hl7.fhir.StructureDefinition." type-name ".v4-3-0") "full-sch"))
             deref))))

(defn- fixture [n] (slurp (str "dev-resources/" n)))

(defn- root-type [^String xml]
  (second (re-find #"<([A-Za-z][A-Za-z0-9]*)[\s>]" (str/replace xml #"<\?xml[^>]*\?>" ""))))

(defn round-trip [xml]
  (let [t (root-type xml)
        sch (resource-schema t)
        data ((fx/parser sch resource-schema) xml)]
    {:data data :out ((fx/unparser sch resource-schema) data) :schema sch}))

(defn- diff-of [xml] (canon/diff xml (:out (round-trip xml))))

(deftest round-trips-representative-resources
  (doseq [f ["citation-example.xml" "patient-example.xml" "observation-decimal.xml"
             "medicationrequest-contained.xml" "bundle-transaction.xml"
             "basic-narrative.xml" "activitydefinition-valueless-primitive.xml"
             "examplescenario-example.xml" "operation-newlines-in-attrs.xml"]]
    (testing f
      (is (empty? (diff-of (fixture f)))))))

(deftest element-order-follows-the-structure-definition
  ;; malli entry order is alphabetical; XML order comes from the SD snapshot,
  ;; carried on the schema as :fhir/element-order.
  (let [order (vec (:fhir/element-order (m/properties (m/schema (resource-schema "Patient")))))]
    (is (= [:id :meta :implicitRules :language :text :contained :extension :modifierExtension
            :identifier :active :name :telecom :gender :birthDate]
           (subvec order 0 14)))
    (is (< (.indexOf order :identifier) (.indexOf order :active))
        "identifier precedes active in FHIR order, but not alphabetically")))

(deftest primitive-carries-its-value-in-an-attribute
  (let [{:keys [data]} (round-trip (fixture "patient-example.xml"))]
    (is (= "true" (:active data)) "wire shape keeps the lexical form")
    (is (= ["Peter" "James"] (get-in data [:name 0 :given])))))

(deftest primitive-may-carry-both-a-value-and-extensions
  ;; <family value="du Marché"><extension .../></family>: the value stays on
  ;; :family, the extension moves to the :_family companion.
  (let [{:keys [data]} (round-trip (fixture "patient-example.xml"))
        n (get-in data [:contact 0 :name])]
    (is (= "du Marché" (:family n)))
    (is (= "http://hl7.org/fhir/StructureDefinition/humanname-own-prefix"
           (get-in n [:_family :extension 0 :url])))
    (is (= "VV" (get-in n [:_family :extension 0 :valueString])))))

(deftest primitive-may-carry-extensions-with-no-value
  ;; Timing.event is a repeating dateTime used here with only a cqf-expression.
  (let [{:keys [data]} (round-trip (fixture "activitydefinition-valueless-primitive.xml"))]
    (is (= [nil] (get-in data [:timingTiming :event]))
        "a valueless repeat is nil in the value array")
    (is (= "http://hl7.org/fhir/StructureDefinition/cqf-expression"
           (get-in data [:timingTiming :_event 0 :extension 0 :url]))
        "and its extensions live in the parallel companion array")))

(deftest contained-resources-are-named-by-their-type
  (let [{:keys [data]} (round-trip (fixture "medicationrequest-contained.xml"))]
    (is (= "Medication" (:resourceType (first (:contained data)))))))

(deftest bundle-entries-nest-a-typed-resource
  (let [{:keys [data]} (round-trip (fixture "bundle-transaction.xml"))]
    (is (= "Patient" (get-in data [:entry 0 :resource :resourceType])))))

(deftest narrative-is-preserved-as-xhtml
  (let [div (get-in (:data (round-trip (fixture "basic-narrative.xml"))) [:text :div])]
    (is (str/starts-with? div "<div xmlns=\"http://www.w3.org/1999/xhtml\""))
    (is (str/includes? div "<table"))))

(deftest decimal-precision-survives
  ;; The corpus torture case: 1.0, 1.00, 1.0e0, 1e-245 in one resource.
  (let [xml (fixture "observation-decimal.xml")
        {:keys [data out]} (round-trip xml)]
    (is (= ["1.0" "1.00" "1.0e0" "0.0000000000000000000001" "1000000000000000000"
            "1.000000000000000000e-245" "-1.000000000000000000e245"]
           (mapv #(get-in % [:valueQuantity :value]) (:component data))))
    (is (str/includes? out "value=\"1.00\"") "1.00 must not collapse to 1.0")
    (is (empty? (canon/diff xml out)))))

(deftest resource-type-is-only-synthetic-at-the-root
  ;; ExampleScenario.instance.resourceType is a real FHIR element.
  (let [{:keys [data]} (round-trip (fixture "examplescenario-example.xml"))]
    (is (= "ExampleScenario" (:resourceType data)))
    (is (some? (get-in data [:instance 0 :resourceType])))))

(deftest newlines-in-attribute-values-survive
  ;; XML normalizes a literal newline in an attribute to a space, so it has to
  ;; be written back as a character reference.
  (let [xml (fixture "operation-newlines-in-attrs.xml")]
    (is (str/includes? xml "&#xA;"))
    (is (empty? (diff-of xml)))))

(deftest typed-decode-yields-schema-valid-data
  (doseq [f ["patient-example.xml" "observation-decimal.xml" "bundle-transaction.xml"]]
    (testing f
      (let [{:keys [data schema]} (round-trip (fixture f))]
        (is (m/validate schema (fx/decode-typed schema data)))))))
