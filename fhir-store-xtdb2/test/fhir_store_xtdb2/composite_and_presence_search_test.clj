(ns fhir-store-xtdb2.composite-and-presence-search-test
  "Composite search parameters and `X.exists() and X != false` parameters.

   Neither kind is a value at a column, so the registry describes them with an
   empty :columns and its own key (:composite, :exists-not-false; see
   server.search-registry/build-resource-registry). Before the store compiled
   those keys, the US Core Observation composites (code-value-*,
   component-code-value-*, combo-code-value-*) and Patient `deceased` compiled
   to a comparison against a column nothing declares, which XTDB 2.2 refuses
   at planning: every search answered an empty page.

   The registry entries below are the ones the server builds from the US Core
   8.0.1 capability schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [taoensso.telemere :as t]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]
            [com.breezeehr.fhir-primitives :as fp]))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- failure-signals
  "Runs `f` and returns [its value, the store's query-failed signals]. A search
   that fails at planning answers [] like a search that matches nothing; the
   signal is what tells the two apart."
  [f]
  (let [{:keys [value signals error]} (t/with-signals (f))]
    (when error (throw error))
    [value (filterv #(#{::core-db/search-query-failed ::core-db/count-query-failed} (:id %))
                    signals)]))

(defn- ids-fn
  "(ids params) -> the set of matching ids, asserting the query planned."
  [store tenant resource-type registry]
  (fn [params]
    (let [[results failures] (failure-signals
                              #(db/search store tenant resource-type params registry))]
      (is (empty? failures) (str "search failed: " (pr-str (mapv :data failures))))
      (set (map :id results)))))

;; ---------------------------------------------------------------------------
;; Observation composites
;; ---------------------------------------------------------------------------

(def ^:private codeable-concept
  [:map
   [:coding {:optional true}
    [:sequential [:map [:system {:optional true} :string] [:code {:optional true} :string]]]]
   [:text {:optional true} :string]])

(def ^:private quantity
  [:map
   [:value {:optional true} :decimal]
   [:unit {:optional true} :string]
   [:system {:optional true} :string]
   [:code {:optional true} :string]])

(def ^:private date-time
  [:or {:fhir/primitive "dateTime"}
   :time/year :time/year-month :time/local-date :time/offset-date-time :time/instant])

(def ^:private observation-schema
  (m/schema
   [:map {:resourceType "Observation"}
    [:resourceType :string]
    [:status :string]
    [:code {:optional true} codeable-concept]
    [:valueQuantity {:optional true} quantity]
    [:valueCodeableConcept {:optional true} codeable-concept]
    [:valueString {:optional true} :string]
    [:valueDateTime {:optional true} date-time]
    [:valuePeriod {:optional true} [:map [:start {:optional true} date-time]
                                    [:end {:optional true} date-time]]]
    [:component {:optional true}
     [:sequential
      [:map
       [:code codeable-concept]
       [:valueQuantity {:optional true} quantity]
       [:valueCodeableConcept {:optional true} codeable-concept]]]]]
   fp/fhir-registry-options))

(def ^:private code-columns
  [{:col "code" :fhir-type "CodeableConcept" :array? false}])

(def ^:private component-element
  {:col "component" :fhir-type "BackboneElement" :array? true})

(defn- composite
  "A registry entry with one scope per element (nil = the resource itself),
   each pairing the code token with `value-component`."
  [value-component & elements]
  {:type "composite" :target nil :columns []
   :composite (mapv (fn [element]
                      {:element element
                       :components [{:type "token" :columns code-columns}
                                    value-component]})
                    elements)})

(def ^:private value-quantity
  {:type "quantity" :columns [{:col "valueQuantity" :fhir-type "Quantity" :array? false}]})

(def ^:private value-concept
  {:type "token" :columns [{:col "valueCodeableConcept" :fhir-type "CodeableConcept" :array? false}]})

(def ^:private observation-registry
  {"code-value-quantity"           (composite value-quantity nil)
   "code-value-concept"            (composite value-concept nil)
   "code-value-string"             (composite {:type "string"
                                               :columns [{:col "valueString" :fhir-type "string"
                                                          :array? false}]}
                                              nil)
   "code-value-date"               (composite {:type "date"
                                               :columns [{:col "valueDateTime" :fhir-type "dateTime"
                                                          :array? false}
                                                         {:col "valuePeriod" :fhir-type "Period"
                                                          :array? false}]}
                                              nil)
   "component-code-value-quantity" (composite value-quantity component-element)
   "component-code-value-concept"  (composite value-concept component-element)
   "combo-code-value-quantity"     (composite value-quantity nil component-element)
   "combo-code-value-concept"      (composite value-concept nil component-element)})

(defn- loinc [code] {:coding [{:system "http://loinc.org" :code code}]})

(defn- ucum [value unit]
  {:value value :unit unit :system "http://unitsofmeasure.org" :code unit})

(def ^:private observations
  {"weight-72" {:code (loinc "29463-7") :valueQuantity (ucum 72.5M "kg")}
   "weight-90" {:code (loinc "29463-7") :valueQuantity (ucum 90M "kg")}
   ;; Same value as weight-72's code would accept, under another code.
   "height-72" {:code (loinc "8302-2") :valueQuantity (ucum 72M "[in_i]")}
   ;; Systolic 120 and diastolic 80. Systolic is never below 100, and the
   ;; panel's own code carries no value: neither may be paired with a part
   ;; that only some OTHER element satisfies.
   "bp"        {:code (loinc "85354-9")
                :component [{:code (loinc "8480-6") :valueQuantity (ucum 120M "mm[Hg]")}
                            {:code (loinc "8462-4") :valueQuantity (ucum 80M "mm[Hg]")}]}
   "smoking"   {:code (loinc "72166-2")
                :valueCodeableConcept {:coding [{:system "http://snomed.info/sct"
                                                 :code "449868002"}]}}
   "survey"    {:code (loinc "survey-panel")
                :component [{:code (loinc "q1") :valueCodeableConcept {:coding [{:code "yes"}]}}
                            {:code (loinc "q2") :valueCodeableConcept {:coding [{:code "no"}]}}]}
   "note"      {:code (loinc "11506-3") :valueString "Negative for malignancy"}
   "price"     {:code (loinc "11506-3") :valueString "Cost $5, paid"}
   "dated"     {:code (loinc "21112-8") :valueDateTime "2024-03-01T10:00:00Z"}
   "period"    {:code (loinc "21112-8") :valuePeriod {:start "2024-01-05T00:00:00Z"}}})

(defn- seed-observations! [store tenant]
  (doseq [[id body] observations]
    (db/create-resource store tenant :Observation id
                        (merge {:resourceType "Observation" :status "final"} body))))

(defn- check-composites [ids]
  (testing "code-value-quantity pairs the Observation's own code and value"
    (is (= #{"weight-72" "weight-90"} (ids {"code-value-quantity" "http://loinc.org|29463-7$gt70"})))
    (is (= #{"weight-72"} (ids {"code-value-quantity" "29463-7$lt80"})))
    (is (= #{"height-72"} (ids {"code-value-quantity" "8302-2$ge72"})))
    (is (= #{} (ids {"code-value-quantity" "8302-2$gt80"}))
        "the value side does not match on its own")
    (is (= #{} (ids {"code-value-quantity" "http://snomed.info/sct|29463-7$gt70"}))
        "the code side does not match on its own"))
  (testing "a unit on the quantity part constrains it"
    (is (= #{"weight-72" "weight-90"}
           (ids {"code-value-quantity" "29463-7$gt70|http://unitsofmeasure.org|kg"})))
    (is (= #{} (ids {"code-value-quantity" "29463-7$gt70|http://unitsofmeasure.org|g"}))))
  (testing "a comma separates whole composite values"
    (is (= #{"weight-72" "height-72"}
           (ids {"code-value-quantity" "29463-7$lt80,8302-2$ge72"}))))
  (testing "code-value-* never reads a component"
    (is (= #{} (ids {"code-value-quantity" "8480-6$gt100"}))))

  (testing "component-code-value-quantity pairs code and value on the SAME component"
    (is (= #{"bp"} (ids {"component-code-value-quantity" "http://loinc.org|8480-6$gt100"})))
    (is (= #{"bp"} (ids {"component-code-value-quantity" "8462-4$lt100"})))
    (is (= #{} (ids {"component-code-value-quantity" "8480-6$lt100"}))
        "systolic is 120; the 80 below 100 belongs to the diastolic component")
    (is (= #{} (ids {"component-code-value-quantity" "29463-7$gt70"}))
        "the Observation's own code and value are not a component"))

  (testing "combo-code-value-quantity matches the Observation or any one component"
    (is (= #{"bp"} (ids {"combo-code-value-quantity" "8480-6$gt100"})))
    (is (= #{"weight-90"} (ids {"combo-code-value-quantity" "29463-7$gt80"})))
    (is (= #{"bp" "weight-90"} (ids {"combo-code-value-quantity" "8480-6$gt100,29463-7$gt80"})))
    (is (= #{} (ids {"combo-code-value-quantity" "8480-6$lt100"}))))

  (testing "code-value-concept"
    (is (= #{"smoking"}
           (ids {"code-value-concept" "http://loinc.org|72166-2$http://snomed.info/sct|449868002"})))
    (is (= #{"smoking"} (ids {"code-value-concept" "72166-2$http://snomed.info/sct|"}))
        "system| matches any code in the system")
    (is (= #{} (ids {"code-value-concept" "72166-2$266919005"}))))

  (testing "component-code-value-concept and its combo on the SAME component"
    (is (= #{"survey"} (ids {"component-code-value-concept" "q1$yes"})))
    (is (= #{} (ids {"component-code-value-concept" "q1$no"})))
    (is (= #{"survey" "smoking"} (ids {"combo-code-value-concept" "q2$no,72166-2$449868002"}))))

  (testing "code-value-string is a case-insensitive prefix on the value"
    (is (= #{"note"} (ids {"code-value-string" "11506-3$NEG"})))
    (is (= #{} (ids {"code-value-string" "11506-3$malignancy"}))))
  (testing "an escaped $ or , belongs to the part"
    (is (= #{"price"} (ids {"code-value-string" "11506-3$cost \\$5\\, paid"}))))

  (testing "code-value-date reads valueDateTime, and a Period by its start"
    (is (= #{"dated"} (ids {"code-value-date" "21112-8$2024-03"})))
    (is (= #{"dated" "period"} (ids {"code-value-date" "21112-8$ge2024-01-01"})))
    (is (= #{} (ids {"code-value-date" "21112-8$gt2024-06-01"}))))

  (testing "a value with the wrong number of parts matches nothing"
    (is (= #{} (ids {"code-value-quantity" "29463-7"})))
    (is (= #{} (ids {"code-value-quantity" "29463-7$gt70$extra"})))))

(deftest composite-parameters-match-on-the-same-resource-or-element
  (let [store (core-db/create-xtdb-store {:resource/schemas [observation-schema]})
        tenant "composite"]
    (try
      (seed-observations! store tenant)
      (check-composites (ids-fn store tenant :Observation observation-registry))
      (testing "the count agrees with the page"
        (is (= 2 (db/count-resources store tenant :Observation
                                     {"code-value-quantity" "29463-7$gt70"}
                                     observation-registry))))
      (testing "a composite ANDs with an ordinary parameter"
        (is (= #{"weight-90"}
               ((ids-fn store tenant :Observation
                        (assoc observation-registry
                               "code" {:type "token" :target nil :columns code-columns}))
                {"code-value-quantity" "29463-7$gt70" "_id" "weight-90"}))))
      (finally (close-store-nodes! store)))))

(defn- xtql-store
  "[xtql-store sql-writer]: an XTQL-mode store and a SQL-mode view of the same
   tenant nodes. Seeding goes through the SQL view: the XTQL write path stores
   top-level camelCase keys as lowercased columns (`valuequantity`), which no
   SQL-fallback search reads, and that is a write-path matter apart from the
   search pathway these tests are about."
  [schema]
  (let [store (core-db/create-xtdb-store {:resource/schemas [schema] :query-mode :xtql})]
    [store (assoc store :query-mode nil)]))

(defn- xtql-fallbacks
  "Runs `f`, returning [its value, the :xtql/fallback signals it emitted]."
  [f]
  (let [{:keys [value signals error]} (t/with-signals (f))]
    (when error (throw error))
    [value (filterv #(= :xtql/fallback (:id %)) signals)]))

(deftest composite-parameters-under-xtql-fall-back-to-sql
  (let [[store sql-writer] (xtql-store observation-schema)
        tenant "composite-xtql"]
    (try
      (seed-observations! sql-writer tenant)
      (let [[ids fallbacks] (xtql-fallbacks
                             #(set (map :id (db/search store tenant :Observation
                                                       {"component-code-value-quantity" "8480-6$gt100"}
                                                       observation-registry))))]
        (is (= #{"bp"} ids))
        (is (seq fallbacks) "the XTQL pathway handed the composite to SQL"))
      (let [ids (ids-fn store tenant :Observation observation-registry)]
        (is (= #{} (ids {"component-code-value-quantity" "8480-6$lt100"})))
        (is (= #{"weight-72" "weight-90"} (ids {"code-value-quantity" "29463-7$gt70"}))))
      (is (= 1 (db/count-resources store tenant :Observation
                                   {"component-code-value-quantity" "8480-6$gt100"}
                                   observation-registry)))
      (finally (close-store-nodes! store)))))

;; ---------------------------------------------------------------------------
;; Patient deceased: `Patient.deceased.exists() and Patient.deceased != false`
;; ---------------------------------------------------------------------------

(def ^:private patient-schema
  (m/schema
   [:map {:resourceType "Patient"}
    [:resourceType :string]
    [:deceasedBoolean {:optional true} :boolean]
    [:deceasedDateTime {:optional true} date-time]]
   fp/fhir-registry-options))

(def ^:private patient-registry
  {"deceased" {:type "token" :target nil :columns []
               :exists-not-false [{:col "deceasedBoolean" :fhir-type "boolean" :array? false}
                                  {:col "deceasedDateTime" :fhir-type "dateTime" :array? false}]}})

(defn- check-deceased [ids]
  (testing "true: deceasedBoolean true, or any deceasedDateTime"
    (is (= #{"flagged" "dated"} (ids {"deceased" "true"}))))
  (testing "false: deceasedBoolean false, or no deceased element at all"
    (is (= #{"alive" "unknown"} (ids {"deceased" "false"}))))
  (testing "a comma ORs the two"
    (is (= #{"flagged" "dated" "alive" "unknown"} (ids {"deceased" "true,false"}))))
  (testing "a boolean from a direct caller reads like the token"
    (is (= #{"flagged" "dated"} (ids {"deceased" true})))
    (is (= #{"alive" "unknown"} (ids {"deceased" false}))))
  (testing "any other value matches nothing"
    (is (= #{} (ids {"deceased" "yes"})))))

(defn- seed-patients! [store tenant]
  (doseq [[id body] {"unknown" {}
                     "alive"   {:deceasedBoolean false}
                     "flagged" {:deceasedBoolean true}
                     "dated"   {:deceasedDateTime "2020-05-01T00:00:00Z"}}]
    (db/create-resource store tenant :Patient id (merge {:resourceType "Patient"} body))))

(deftest deceased-is-true-for-a-true-flag-or-any-date
  (let [store (core-db/create-xtdb-store {:resource/schemas [patient-schema]})
        tenant "deceased"]
    (try
      (seed-patients! store tenant)
      (check-deceased (ids-fn store tenant :Patient patient-registry))
      (testing "the count agrees with the page"
        (is (= 2 (db/count-resources store tenant :Patient {"deceased" "true"} patient-registry)))
        (is (= 2 (db/count-resources store tenant :Patient {"deceased" "false"} patient-registry))))
      (finally (close-store-nodes! store)))))

(deftest deceased-plans-before-any-patient-has-a-death-date
  ;; deceasedDateTime is declared from the schema but never written here, so
  ;; it plans as an untyped null column.
  (let [store (core-db/create-xtdb-store {:resource/schemas [patient-schema]})
        tenant "deceased-no-dates"
        ids (ids-fn store tenant :Patient patient-registry)]
    (try
      (db/create-resource store tenant :Patient "flagged" {:resourceType "Patient" :deceasedBoolean true})
      (db/create-resource store tenant :Patient "unknown" {:resourceType "Patient"})
      (is (= #{"flagged"} (ids {"deceased" "true"})))
      (is (= #{"unknown"} (ids {"deceased" "false"})))
      (finally (close-store-nodes! store)))))

(deftest deceased-under-xtql-falls-back-to-sql
  ;; The boolean case matters most here: the XTQL builder's first branch read a
  ;; boolean value as an equality on a column named after the parameter.
  (let [[store sql-writer] (xtql-store patient-schema)
        tenant "deceased-xtql"]
    (try
      (seed-patients! sql-writer tenant)
      (let [[ids fallbacks] (xtql-fallbacks
                             #(set (map :id (db/search store tenant :Patient {"deceased" true}
                                                       patient-registry))))]
        (is (= #{"flagged" "dated"} ids))
        (is (seq fallbacks) "the XTQL pathway handed the parameter to SQL"))
      (check-deceased (ids-fn store tenant :Patient patient-registry))
      (finally (close-store-nodes! store)))))

;; ---------------------------------------------------------------------------
;; A reference component: DocumentReference `relationship` (code$target)
;; ---------------------------------------------------------------------------

(def ^:private document-reference-schema
  (m/schema
   [:map {:resourceType "DocumentReference"}
    [:resourceType :string]
    [:status :string]
    [:relatesTo {:optional true}
     [:sequential [:map [:code :string] [:target [:map [:reference :string]]]]]]]
   fp/fhir-registry-options))

(def ^:private document-reference-registry
  {"relationship"
   {:type "composite" :target nil :columns []
    :composite [{:element {:col "relatesTo" :fhir-type "BackboneElement" :array? true}
                 :components [{:type "token" :columns [{:col "code" :fhir-type "code" :array? false}]}
                              {:type "reference"
                               :columns [{:col "target" :fhir-type "Reference" :array? false}]}]}]}})

(deftest reference-component-matches-on-the-same-relatesTo-element
  (let [store (core-db/create-xtdb-store {:resource/schemas [document-reference-schema]})
        tenant "relationship"
        ids (ids-fn store tenant :DocumentReference document-reference-registry)]
    (try
      (db/create-resource store tenant :DocumentReference "d3"
                          {:resourceType "DocumentReference" :status "current"
                           :relatesTo [{:code "replaces" :target {:reference "DocumentReference/d1"}}
                                       {:code "appends" :target {:reference "DocumentReference/d2"}}]})
      (is (= #{"d3"} (ids {"relationship" "replaces$DocumentReference/d1"})))
      (is (= #{"d3"} (ids {"relationship" "appends$DocumentReference/d2"})))
      (is (= #{} (ids {"relationship" "replaces$DocumentReference/d2"}))
          "d2 is appended to, not replaced")
      (finally (close-store-nodes! store)))))
