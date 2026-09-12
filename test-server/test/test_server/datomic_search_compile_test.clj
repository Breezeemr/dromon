(ns test-server.datomic-search-compile-test
  "Every declared search parameter compiles to Datalog on the Datomic store.

   `person-search-params-test` pins that a parameter survives registry
   resolution, which only proves it resolved to SOME column. The store can
   still fail to translate that column: `phone` and `email` once resolved to
   `:sub-col \"where(system='phone')\"` and `telecom` to a bare ref column,
   and all three passed the registry test while degrading, at query time, to
   an in-memory filter over every resource of the type. This test asks the
   store's query builder the question the registry test cannot: given the
   registry the server really builds and the catalog the store really builds
   from the same schema, does each parameter compile to a clause?

   Scope is the person-search cluster. Widen `guarded-types` as pages start
   on other types; a parameter the store is known not to compile goes in
   `known-uncompilable` with a reason, so the gap is a pinned fact and its
   fix is a test failure asking for the entry to be removed."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [server.core :as core]
            [fhir-store-datomic.search :as search]
            [fhir-store-datomic.write :as write]))

(def ^:private guarded-types
  ["Patient" "Person" "Practitioner" "RelatedPerson" "PractitionerRole"])

(def ^:private sample-value
  "One well-formed value per parameter type. Types absent here (composite,
   special) are not compiled by the store and are not checked."
  {"token" "x"
   "string" "x"
   "date" "2020-01-01"
   "reference" "Patient/x"
   "quantity" "1"
   "number" "1"
   "uri" "http://example.org/x"})

(def ^:private sample-by-name
  "Parameters whose column holds a typed literal the generic sample cannot
   equal: a boolean column bound to \"x\" is reported unsupported on purpose
   (typed-token-search-test), which is not the degradation this test hunts."
  {"active" "true"
   "deceased" "true"})

(def ^:private known-uncompilable
  "{type-name #{param-name}}: parameters the store does not compile today.
   Each is a defect or a gap a page must not lean on. An entry that starts
   compiling fails the test, so the allowlist cannot go stale.

   - Patient/deceased: the R4B expression is
     `Patient.deceased.exists() and Patient.deceased != false`, which the
     registry resolves to `:sub-col \"exists() and Patient.deceased != false\"`,
     an unparsed FHIRPath fragment no store translates. Same class as the
     `phone` defect this test was written for; not yet fixed."
  {"Patient" #{"deceased"}})

(defn- schema-for [type-name]
  (first (core/resolve-schemas
          [(symbol (str "breeze.capability.v1-0-0." type-name) "capability")])))

(deftest every-declared-parameter-compiles-to-datalog
  (doseq [type-name guarded-types]
    (let [schema (schema-for type-name)
          registry (:fhir/search-registry (m/properties schema))
          catalog (get (write/build-resource-catalogs [schema]) type-name)
          rt (keyword type-name)]
      (testing (str type-name " has a registry and a catalog to compile against")
        (is (seq registry))
        (is (seq catalog)))
      (doseq [[pname {:keys [type]}] (sort-by key registry)
              :let [value (or (get sample-by-name pname) (get sample-value type))]
              :when value]
        (let [{:keys [unsupported-params]}
              (search/build-search-query rt {pname value} registry catalog)
              expected-gap? (contains? (get known-uncompilable type-name #{}) pname)]
          (testing (str type-name "?" pname " (" type ")")
            (if expected-gap?
              (is (seq unsupported-params)
                  (str pname " now compiles; remove it from known-uncompilable"))
              (is (empty? unsupported-params)
                  (str pname " degrades to the in-memory filter; columns: "
                       (pr-str (:columns (get registry pname))))))))))))
