(ns server.search-registry-test
  "Tests for the search parameter classification the search handlers use to
   decide which query parameters they can actually honour."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [server.search-registry :as sr]))

(def ^:private registry
  "Stand-in for the enriched registry `build-resource-registry` produces for a
   resource type that declares `patient` and `status`."
  {"patient" {:type "reference" :columns [{:col "patient"}]}
   "status"  {:type "token" :columns [{:col "status"}]}})

(deftest result-param-classification
  (testing "search result parameters are recognised"
    (is (sr/result-param? "_count"))
    (is (sr/result-param? "_sort"))
    (is (sr/result-param? "_include"))
    (is (sr/result-param? "_revinclude")))
  (testing "a modified result parameter classifies with its base name"
    (is (sr/result-param? "_include:iterate")))
  (testing "filter parameters are not result parameters"
    (is (not (sr/result-param? "patient")))
    (is (not (sr/result-param? "_id")))
    (is (not (sr/result-param? "_lastUpdated")))))

(deftest filter-params-drops-only-result-params
  (is (= {"patient" "Patient/1" "_id" "x"}
         (sr/filter-params {"patient" "Patient/1"
                            "_id" "x"
                            "_count" "50"
                            "_sort" "-_lastUpdated"
                            "_include" "Consent:patient"})))
  (testing "keyword keys are preserved and classified by name"
    (is (= {:patient "Patient/1"}
           (sr/filter-params {:patient "Patient/1" :_count 50 :_skip 0})))))

(deftest unsupported-filter-params-detection
  (testing "declared parameters are supported"
    (is (= [] (sr/unsupported-filter-params registry {"patient" "Patient/1"
                                                      "status" "active"}))))
  (testing "result parameters are never reported"
    (is (= [] (sr/unsupported-filter-params registry {"_count" "50"
                                                      "_sort" "status"
                                                      "_elements" "id"}))))
  (testing "resource-level parameters are supported whatever the registry says"
    (is (= [] (sr/unsupported-filter-params registry {"_id" "abc"
                                                      "_tag" "sys|code"
                                                      "_security" "sys|code"
                                                      "_profile" "http://example/p"}))))
  (testing "an undeclared parameter is reported"
    (is (= ["subject"] (sr/unsupported-filter-params registry {"subject" "Patient/1"}))))
  (testing "modifiers and chains are reported: the store matches registry
            entries by exact name, so neither reaches the query builder"
    (is (= ["patient:identifier"]
           (sr/unsupported-filter-params registry {"patient:identifier" "sys|1"})))
    (is (= ["patient.name"]
           (sr/unsupported-filter-params registry {"patient.name" "Smith"})))
    (is (= ["_has:Observation:patient:code"]
           (sr/unsupported-filter-params registry {"_has:Observation:patient:code" "1234-5"}))))
  (testing "_lastUpdated is only supported where the registry declares it"
    (is (= ["_lastUpdated"] (sr/unsupported-filter-params registry {"_lastUpdated" "gt2020-01-01"})))
    (is (= [] (sr/unsupported-filter-params (assoc registry "_lastUpdated" {:type "date"})
                                            {"_lastUpdated" "gt2020-01-01"}))))
  (testing "results are sorted and de-duplicated"
    (is (= ["aaa" "zzz"] (sr/unsupported-filter-params registry {"zzz" "1" "aaa" "2"}))))
  (testing "a nil registry supports only the resource-level parameters"
    (is (= [] (sr/unsupported-filter-params nil {"_id" "abc" "_count" "10"})))
    (is (= ["patient"] (sr/unsupported-filter-params nil {"patient" "Patient/1"})))))

(deftest text-param-classification
  (is (sr/text-param? "_text"))
  (testing "a modified _text is not the parameter an index answers"
    (is (not (sr/text-param? "_text:exact")))
    (is (not (sr/text-param? "_text:contains"))))
  (testing "_text is neither a result parameter nor a resource-level one"
    (is (not (sr/result-param? "_text")))
    (is (contains? (sr/filter-params {"_text" "smith" "_count" "5"}) "_text"))))

(deftest unsupported-filter-params-grants-text-only-when-told-to
  (testing "by default _text is reported like any name the registry lacks"
    (is (= ["_text"] (sr/unsupported-filter-params registry {"_text" "smith"})))
    (is (= ["_text"] (sr/unsupported-filter-params registry {"_text" "smith"} {})))
    (is (= ["_text"] (sr/unsupported-filter-params registry {"_text" "smith"}
                                                   {:text-search? false}))))
  (testing "the 2-arity and the opts-less 3-arity agree byte for byte"
    (let [params {"_text" "smith" "subject" "Patient/1" "status" "active" "_count" "3"}]
      (is (= (sr/unsupported-filter-params registry params)
             (sr/unsupported-filter-params registry params {})
             (sr/unsupported-filter-params registry params {:text-search? false})
             ["_text" "subject"]))))
  (testing "with the store's grant _text drops out and nothing else changes"
    (is (= [] (sr/unsupported-filter-params registry {"_text" "smith"}
                                            {:text-search? true})))
    (is (= ["subject"]
           (sr/unsupported-filter-params registry {"_text" "smith" "subject" "Patient/1"}
                                         {:text-search? true}))))
  (testing "the grant covers bare _text only; a modified form stays unsupported"
    (is (= ["_text:exact"]
           (sr/unsupported-filter-params registry {"_text:exact" "smith"}
                                         {:text-search? true}))))
  (testing "a keyword key classifies by name like every other parameter"
    (is (= [] (sr/unsupported-filter-params registry {:_text "smith"} {:text-search? true})))
    (is (= ["_text"] (sr/unsupported-filter-params registry {:_text "smith"}))))
  (testing "the grant does not need a registry"
    (is (= [] (sr/unsupported-filter-params nil {"_text" "smith"} {:text-search? true})))))

(deftest where-resolve-dotted-path-delegates-to-nested-resolution
  (let [resolve-expression #'sr/resolve-expression
        field-map {"participant" {:fhir-type "BackboneElement" :array? true
                                  :children {"actor" {:fhir-type "Reference" :array? false}}}
                   "subject" {:fhir-type "Reference" :array? false}}]
    (testing "a dotted .where(resolve() is X) path yields a :sub-col descriptor
              the store can translate to Datalog, not a dotted column"
      (is (= [{:col "participant" :fhir-type "BackboneElement" :array? true
               :sub-col "actor" :sub-fhir-type "Reference" :sub-array? false}]
             (resolve-expression "Appointment.participant.actor.where(resolve() is Patient)"
                                 field-map "reference"))))
    (testing "an un-dotted path keeps the plain reference descriptor"
      (is (= [{:col "subject" :fhir-type "Reference" :array? false}]
             (resolve-expression "Observation.subject.where(resolve() is Patient)"
                                 field-map "reference"))))))

(deftest where-system-resolves-to-a-fixed-system-constraint
  ;; `phone` and `email` are `telecom.where(system='phone')` and
  ;; `telecom.where(system='email')`. Before this branch existed the
  ;; expression fell into the nested-path case and emitted
  ;; `:sub-col "where(system='phone')"`, a column no store could translate,
  ;; so both parameters silently degraded to an in-memory match that
  ;; ignored the system.
  (let [resolve-expression #'sr/resolve-expression
        field-map {"telecom" {:fhir-type "ContactPoint" :array? true}
                   "contact" {:fhir-type "BackboneElement" :array? true
                              :children {"telecom" {:fhir-type "ContactPoint" :array? true}}}}]
    (testing "the telecom column carries the system as a fixed constraint"
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true
               :fixed {:system "phone"}}]
             (resolve-expression "Person.telecom.where(system='phone')" field-map "token"))))
    (testing "whitespace around the equals sign is tolerated"
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true
               :fixed {:system "email"}}]
             (resolve-expression "Patient.telecom.where(system = 'email')" field-map "token"))))
    (testing "a dotted path resolves through the nested machinery and keeps the constraint"
      (is (= [{:col "contact" :fhir-type "BackboneElement" :array? true
               :sub-col "telecom" :sub-fhir-type "ContactPoint" :sub-array? true
               :fixed {:system "phone"}}]
             (resolve-expression "Patient.contact.telecom.where(system='phone')" field-map "token"))))
    (testing "the un-narrowed telecom parameter is unchanged"
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true}]
             (resolve-expression "Person.telecom" field-map "token"))))
    (testing "no descriptor leaks an unparsed FHIRPath fragment as a column name"
      (doseq [col (resolve-expression "Person.telecom.where(system='phone')" field-map "token")]
        (is (not (re-find #"\(" (str (:col col) (:sub-col col)))))))))

(deftest shared-expressions-keep-only-this-types-alternatives
  ;; Shared SearchParameters list one path per base type. Stripping each
  ;; alternative's type prefix used to turn `DocumentReference.masterIdentifier`
  ;; into a `masterIdentifier` column of Observation; XTDB 2.2 refuses to plan a
  ;; query naming a column nothing wrote, so Observation?identifier= answered
  ;; empty even with a matching row.
  (let [resolve-param #'sr/resolve-search-param-expression
        obs-fields {"identifier" {:fhir-type "Identifier" :array? true}
                    "encounter"  {:fhir-type "Reference" :array? false}
                    "subject"    {:fhir-type "Reference" :array? false}}
        doc-fields {"masterIdentifier" {:fhir-type "Identifier" :array? false}
                    "identifier"       {:fhir-type "Identifier" :array? true}
                    "subject"          {:fhir-type "Reference" :array? false}
                    "context"          {:fhir-type "BackboneElement" :array? false
                                        :children {"encounter" {:fhir-type "Reference" :array? true}}}}
        clinical-identifier "AllergyIntolerance.identifier | Observation.identifier | DocumentReference.masterIdentifier | DocumentReference.identifier"
        clinical-encounter "Observation.encounter | DocumentReference.context.encounter | Procedure.encounter"
        clinical-patient "Observation.subject.where(resolve() is Patient) | DocumentReference.subject.where(resolve() is Patient) | Patient.link.other"]
    (testing "another type's alternative never becomes a column of this type"
      (is (= [{:col "identifier" :fhir-type "Identifier" :array? true}]
             (resolve-param clinical-identifier obs-fields "token" "Observation")))
      (is (= [{:col "encounter" :fhir-type "Reference" :array? false}]
             (resolve-param clinical-encounter obs-fields "reference" "Observation"))))
    (testing "every alternative rooted at this type is kept"
      (is (= [{:col "masterIdentifier" :fhir-type "Identifier" :array? false}
              {:col "identifier" :fhir-type "Identifier" :array? true}]
             (resolve-param clinical-identifier doc-fields "token" "DocumentReference")))
      (is (= [{:col "context" :fhir-type "BackboneElement" :array? false
               :sub-col "encounter" :sub-fhir-type "Reference" :sub-array? true}]
             (resolve-param clinical-encounter doc-fields "reference" "DocumentReference")))
      (is (= [{:col "subject" :fhir-type "Reference" :array? false}]
             (resolve-param clinical-patient doc-fields "reference" "DocumentReference"))))
    (testing "a parenthesised cast is rooted at the type inside the parenthesis"
      (is (= [{:col "medicationCodeableConcept" :fhir-type "CodeableConcept" :array? false}]
             (resolve-param "Medication.code | (MedicationRequest.medication as CodeableConcept)"
                            {"medicationCodeableConcept" {:fhir-type "CodeableConcept" :array? false}}
                            "token" "MedicationRequest"))))
    (testing "Resource-rooted expressions apply to every type"
      (is (= [{:col "_id" :fhir-type "id" :array? false}]
             (resolve-param "Resource.id" obs-fields "token" "Observation"))))
    (testing "without a resource type every alternative is resolved, as before"
      (is (= ["identifier" "masterIdentifier"]
             (mapv :col (resolve-param "Observation.identifier | DocumentReference.masterIdentifier"
                                       obs-fields "token")))))))

(deftest build-resource-registry-scopes-shared-parameters-to-the-schema-type
  (let [cap-schema (m/schema
                    [:multi {:dispatch (constantly :base) :resourceType "Observation"}
                     [:base [:map {:resourceType "Observation"}
                             [:identifier {:optional true}
                              [:sequential [:map [:value {:optional true} :string]]]]]]])
        sp-json {:expression "Observation.identifier | DocumentReference.masterIdentifier"}]
    (with-redefs [sr/load-search-param-json (constantly sp-json)]
      (is (= ["identifier"]
             (mapv :col (get-in (sr/build-resource-registry
                                 [{:name "identifier" :type "token"
                                   :definition "http://hl7.org/fhir/SearchParameter/clinical-identifier"}]
                                 cap-schema)
                                ["identifier" :columns])))
          "the resource type comes from the capability schema's properties"))))

;; ---------------------------------------------------------------------------
;; Presence and composite parameters
;; ---------------------------------------------------------------------------

(def ^:private datatype-registry
  "Stand-ins for the generated FHIR datatype schemas, keyed the way the
   generated packages key them, so :ref classification and deref run as they
   do against the real capability schemas."
  {:org.hl7.fhir.StructureDefinition.Coding/v4-3-0
   [:map [:system {:optional true} :string] [:code {:optional true} :string]]
   :org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0
   [:map [:coding {:optional true}
          [:sequential [:ref :org.hl7.fhir.StructureDefinition.Coding/v4-3-0]]]]
   :org.hl7.fhir.StructureDefinition.Quantity/v4-3-0
   [:map [:value {:optional true} :double] [:code {:optional true} :string]]
   :org.hl7.fhir.StructureDefinition.Reference/v4-3-0
   [:map [:reference {:optional true} :string]]
   :org.hl7.fhir.StructureDefinition.UsageContext/v4-3-0
   [:map
    [:code [:ref :org.hl7.fhir.StructureDefinition.Coding/v4-3-0]]
    [:valueCodeableConcept {:optional true} [:ref :org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0]]
    [:valueQuantity {:optional true} [:ref :org.hl7.fhir.StructureDefinition.Quantity/v4-3-0]]]})

(defn- cap-schema
  "A one-variant capability :multi for `resource-type` with `entries`."
  [resource-type & entries]
  (m/schema [:multi {:dispatch (constantly :base) :resourceType resource-type}
             [:base (into [:map {:resourceType resource-type}] entries)]]
            {:registry (merge (m/default-schemas) datatype-registry)}))

(defn- ref-to [type-name]
  [:ref (keyword (str "org.hl7.fhir.StructureDefinition." type-name) "v4-3-0")])

(def ^:private search-parameters
  "R4B SearchParameter JSON, trimmed to the keys the registry reads, by
   definition url."
  (let [sp "http://hl7.org/fhir/SearchParameter/"
        code-value-quantity [{:definition (str sp "clinical-code") :expression "code"}
                             {:definition (str sp "Observation-value-quantity")
                              :expression "value.as(Quantity)"}]]
    {(str sp "Patient-deceased")
     {:type "token" :expression "Patient.deceased.exists() and Patient.deceased != false"}
     (str sp "clinical-code")
     {:type "token" :expression "Condition.code | Observation.code"}
     (str sp "Observation-value-quantity")
     {:type "quantity" :expression "(Observation.value as Quantity) | (Observation.value as SampledData)"}
     (str sp "Observation-value-concept")
     {:type "token" :expression "(Observation.value as CodeableConcept)"}
     (str sp "Observation-code-value-quantity")
     {:type "composite" :expression "Observation" :component code-value-quantity}
     (str sp "Observation-component-code-value-quantity")
     {:type "composite" :expression "Observation.component" :component code-value-quantity}
     (str sp "Observation-combo-code-value-quantity")
     {:type "composite" :expression "Observation | Observation.component"
      :component code-value-quantity}
     (str sp "Observation-code-value-concept")
     {:type "composite" :expression "Observation"
      :component [{:definition (str sp "clinical-code") :expression "code"}
                  {:definition (str sp "Observation-value-concept")
                   :expression "value.as(CodeableConcept)"}]}
     ;; R4B pairs each component's definition with the OTHER component's
     ;; expression: the reference definition reads `code`, the token one `target`.
     (str sp "DocumentReference-relationship")
     {:type "composite" :expression "DocumentReference.relatesTo"
      :component [{:definition (str sp "DocumentReference-relatesto") :expression "code"}
                  {:definition (str sp "DocumentReference-relation") :expression "target"}]}
     (str sp "DocumentReference-relatesto")
     {:type "reference" :expression "DocumentReference.relatesTo.target"}
     (str sp "DocumentReference-relation")
     {:type "token" :expression "DocumentReference.relatesTo.code"}
     (str sp "conformance-context-type-value")
     {:type "composite" :expression "CodeSystem.useContext | ValueSet.useContext"
      :component [{:definition (str sp "conformance-context-type") :expression "code"}
                  {:definition (str sp "conformance-context")
                   :expression "value.as(CodeableConcept)"}]}
     (str sp "conformance-context-type")
     {:type "token" :expression "CodeSystem.useContext.code | ValueSet.useContext.code"}
     (str sp "conformance-context")
     {:type "token" :expression "(ValueSet.useContext.value as CodeableConcept)"}}))

(defn- registry-for
  "build-resource-registry over `param-names` -> definition urls, against the
   stub SearchParameters."
  [schema params]
  (with-redefs [sr/load-search-param-json search-parameters]
    (sr/build-resource-registry
     (mapv (fn [[pname type-name sp-id]]
             {:name pname :type type-name
              :definition (str "http://hl7.org/fhir/SearchParameter/" sp-id)})
           params)
     schema)))

(def ^:private observation-cap
  (let [cc (ref-to "CodeableConcept")
        qty (ref-to "Quantity")]
    (cap-schema "Observation"
                [:code cc]
                [:valueQuantity {:optional true} qty]
                [:valueCodeableConcept {:optional true} cc]
                [:component {:optional true}
                 [:sequential [:map
                               [:code cc]
                               [:valueQuantity {:optional true} qty]
                               [:valueCodeableConcept {:optional true} cc]]]])))

(def ^:private code-component
  {:type "token" :columns [{:col "code" :fhir-type "CodeableConcept" :array? false}]})

(def ^:private quantity-component
  {:type "quantity" :columns [{:col "valueQuantity" :fhir-type "Quantity" :array? false}]})

(deftest presence-expression-resolves-to-the-choice-columns
  ;; Patient.deceased once resolved to {:col "deceased" :sub-col
  ;; "exists() and Patient.deceased != false"}: a column nothing stores, so
  ;; the store compared against nothing and every search answered empty.
  (let [registry (registry-for (cap-schema "Patient"
                                           [:deceasedBoolean {:optional true} :boolean]
                                           [:deceasedDateTime {:optional true}
                                            [:string {:fhir/primitive "dateTime"}]])
                               [["deceased" "token" "Patient-deceased"]])
        entry (get registry "deceased")]
    (testing "the token is computed from every deceased[x] column"
      (is (= "token" (:type entry)))
      (is (= #{{:col "deceasedBoolean" :fhir-type "boolean" :array? false}
               {:col "deceasedDateTime" :fhir-type "dateTime" :array? false}}
             (set (:exists-not-false entry)))))
    (testing "no plain column is offered: equality at a column is not the semantics"
      (is (= [] (:columns entry))))
    (testing "the parameter is still declared"
      (is (= [] (sr/unsupported-filter-params registry {"deceased" "true"}))))))

(deftest composite-resolves-each-component-within-each-scope
  (let [registry (registry-for observation-cap
                               [["code-value-quantity" "composite" "Observation-code-value-quantity"]
                                ["component-code-value-quantity" "composite"
                                 "Observation-component-code-value-quantity"]
                                ["combo-code-value-quantity" "composite"
                                 "Observation-combo-code-value-quantity"]
                                ["code-value-concept" "composite" "Observation-code-value-concept"]])
        component-element {:col "component" :fhir-type "BackboneElement" :array? true}]
    (testing "an `Observation` base scopes the components to the resource itself"
      (is (= {:type "composite" :target nil :columns []
              :composite [{:element nil :components [code-component quantity-component]}]}
             (get registry "code-value-quantity"))))
    (testing "an `Observation.component` base scopes them to one component element"
      (is (= [{:element component-element :components [code-component quantity-component]}]
             (:composite (get registry "component-code-value-quantity")))))
    (testing "a combo base yields both scopes, resource first"
      (is (= [{:element nil :components [code-component quantity-component]}
              {:element component-element :components [code-component quantity-component]}]
             (:composite (get registry "combo-code-value-quantity")))))
    (testing "a relative `value.as(X)` component keeps its own first segment"
      (is (= [code-component
              {:type "token"
               :columns [{:col "valueCodeableConcept" :fhir-type "CodeableConcept" :array? false}]}]
             (:components (first (:composite (get registry "code-value-concept")))))))
    (testing "a component reads only the column its expression names, not every
              alternative of the definition's own expression (valueSampledData)"
      (is (= [{:col "valueQuantity" :fhir-type "Quantity" :array? false}]
             (-> registry (get "code-value-quantity") :composite first :components second :columns))))
    (testing "no composite offers the resource or element itself as a plain column"
      (doseq [[pname entry] registry]
        (is (= [] (:columns entry)) pname)))))

(deftest composite-on-a-datatype-element-reads-the-datatype-fields
  ;; useContext is a UsageContext reference, not an inline BackboneElement,
  ;; so its fields come from the referenced schema.
  (let [registry (registry-for (cap-schema "ValueSet"
                                           [:useContext {:optional true}
                                            [:sequential (ref-to "UsageContext")]])
                               [["context-type-value" "composite" "conformance-context-type-value"]])]
    (is (= [{:element {:col "useContext" :fhir-type "UsageContext" :array? true}
             :components [{:type "token" :columns [{:col "code" :fhir-type "Coding" :array? false}]}
                          {:type "token" :columns [{:col "valueCodeableConcept"
                                                    :fhir-type "CodeableConcept"
                                                    :array? false}]}]}]
           (:composite (get registry "context-type-value")))
        "only this type's alternative of the shared expression is a scope")))

(deftest composite-component-type-follows-its-expression-when-the-definition-cannot-read-it
  ;; R4B's DocumentReference `relationship` pairs each component's definition
  ;; with the other component's expression. The expressions say what is
  ;; compared: `code` is a token, `target` a reference -- `replaces$Document
  ;; Reference/1`, as R5 defines it.
  (let [registry (registry-for (cap-schema "DocumentReference"
                                           [:relatesTo {:optional true}
                                            [:sequential [:map
                                                          [:code [:string {:fhir/primitive "code"}]]
                                                          [:target (ref-to "Reference")]]]])
                               [["relationship" "composite" "DocumentReference-relationship"]])]
    (is (= [{:element {:col "relatesTo" :fhir-type "BackboneElement" :array? true}
             :components [{:type "token" :columns [{:col "code" :fhir-type "code" :array? false}]}
                          {:type "reference" :columns [{:col "target" :fhir-type "Reference"
                                                        :array? false}]}]}]
           (:composite (get registry "relationship"))))))

(deftest composite-that-resolves-in-no-scope-is-reported
  (let [registry (registry-for (cap-schema "Observation" [:status :string])
                               [["component-code-value-quantity" "composite"
                                 "Observation-component-code-value-quantity"]])]
    (testing "a base element the schema lacks yields no scope, so no entry"
      (is (nil? (get registry "component-code-value-quantity"))))
    (testing "and the parameter is reported rather than answered empty"
      (is (= ["component-code-value-quantity"]
             (sr/unsupported-filter-params registry {"component-code-value-quantity" "8480-6$gt100"}))))))
