(ns test-server.person-search-params-test
  "Pins the Person search parameter surface.

   Person declares no `searchParam` block in
   `fhir-igs/breeze-ig/package/CapabilityStatement-breeze-server.json`; its 19
   R4B parameters arrive through the automatic base-R4B merge in
   `com.breezeehr.capability-statement/rest-resources` and are baked into the
   generated `breeze.capability.v1-0-0.Person/capability`. Nothing at runtime
   reads the authored JSON for parameters, so the declaration is not the risk.

   The twentieth, `_text`, is the exception to everything below: it is
   authored in the CapabilityStatement, its SearchParameter (Resource-text)
   has no expression, so the registry can never resolve it, and the search
   handler grants it from the store (`fhir-store.protocol/ITextSearchStore`)
   rather than from the registry. It is pinned as declared and pinned as
   absent from the registry, so a change on either side shows up.

   The risk is downstream: `server.search-registry/build-resource-registry`
   silently drops any parameter whose FHIRPath expression fails to resolve to
   columns. A dropped parameter is still advertised in /metadata but 400s at
   query time, because `server.handlers/search-type` rejects anything the
   registry does not carry. These tests fail loudly, with a diff, if that set
   ever shrinks."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [server.core :as core]
            [server.search-registry :as sr]))

(def ^:private person-schema
  (delay (first (core/resolve-schemas ['breeze.capability.v1-0-0.Person/capability]))))

(defn- person-properties [] (m/properties @person-schema))

(defn- person-registry [] (:fhir/search-registry (person-properties)))

(def ^:private declared-params
  "Every parameter `breeze.capability.v1-0-0.Person/capability` declares."
  #{"_text"
    "address" "address-city" "address-country" "address-postalcode"
    "address-state" "address-use" "birthdate" "email" "gender" "identifier"
    "link" "name" "organization" "patient" "phone" "phonetic" "practitioner"
    "relatedperson" "telecom"})

(def ^:private person-search-params
  "The parameters that survive registry resolution: everything declared except
   `_text`, which is answered by a full-text index rather than a column.
   Pinned as a literal: a silent drop in `build-resource-registry` is the
   failure this test exists to catch, and it can only be seen as a difference
   from a known-good set."
  (into #{} (remove sr/text-param?) declared-params))

(def ^:private person-search-minimum
  "The subset person-search grows into. Called out separately from the pinned
   set so a failure says which page breaks, not just that a set changed."
  #{"name" "birthdate" "gender" "identifier" "telecom" "address"})

(deftest person-declares-twenty-search-params
  (let [declared (into #{} (map :name) (:search-params (person-properties)))]
    (is (= 20 (count declared)))
    (is (= declared-params declared)
        "the generated capability's declared set changed; regenerate expectations
         only after confirming the base-R4B merge really did change")))

(deftest person-search-params-survive-registry-resolution
  (let [registry (person-registry)]
    (testing "the parameters person-search needs all resolve to columns"
      (is (empty? (remove registry person-search-minimum))
          (str "person-search cannot express: "
               (pr-str (vec (sort (remove registry person-search-minimum)))))))

    (testing "no declared parameter is silently dropped"
      (is (= person-search-params (set (keys registry)))
          (str "declared but dropped: "
               (pr-str (vec (sort (remove (set (keys registry)) declared-params))))))
      (is (= 19 (count registry)))
      (is (not (contains? registry "_text"))
          "_text is a store capability, not a registry entry; a registry that
           resolved it would let a store without an index run it unfiltered"))

    (testing "every entry carries the columns the query builder needs"
      (doseq [pname (sort person-search-params)]
        (is (seq (:columns (get registry pname)))
            (str pname " resolved to no columns"))))

    (testing "no column carries an unparsed FHIRPath fragment as its name"
      ;; `phone` once resolved to `:sub-col \"where(system='phone')\"`: a
      ;; column that exists, so the check above passed, and that no store can
      ;; translate, so the parameter silently degraded to an in-memory match.
      (doseq [pname (sort person-search-params)
              col (:columns (get registry pname))]
        (is (not (re-find #"\(" (str (:col col) (:sub-col col))))
            (str pname " resolved to an untranslatable column " (pr-str col)))))

    (testing "phone and email are telecom narrowed by a fixed system"
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true
               :fixed {:system "phone"}}]
             (:columns (get registry "phone"))))
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true
               :fixed {:system "email"}}]
             (:columns (get registry "email"))))
      (is (= [{:col "telecom" :fhir-type "ContactPoint" :array? true}]
             (:columns (get registry "telecom")))))))

(deftest person-rejects-parameters-r4b-does-not-define-for-person
  (let [registry (person-registry)]
    (testing "a declared parameter is accepted"
      (is (= [] (sr/unsupported-filter-params registry {"name" "smith"}))))

    (testing "family/given are HumanName parts, not Person parameters in R4B;
              the page must search on `name`"
      (is (= ["family"] (sr/unsupported-filter-params registry {"family" "smith"})))
      (is (= ["given"] (sr/unsupported-filter-params registry {"given" "ann"}))))

    (testing "_text is refused by the registry alone and granted only with the
              store's say-so"
      (is (= ["_text"] (sr/unsupported-filter-params registry {"_text" "smith"})))
      (is (= [] (sr/unsupported-filter-params registry {"_text" "smith"}
                                              {:text-search? true}))))))
