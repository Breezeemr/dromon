(ns test-server.mock-search-registry-test
  "The mock store's search evaluation against the registries fhir-server
   really builds for the Breeze capability. fhir-store-mock's own search tests
   run on copies of those registries (it cannot depend on fhir-server); this
   checks the copies still match, and runs furl's search shapes through the
   real ones."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.mock.search-test :as mock-search]
            [fhir-store.protocol :as db]
            [malli.core :as m]
            [server.core :as core]
            [test-server.schemas.breeze :as breeze]))

(def ^:private tenant "realm")

(def ^:private real-registries
  (delay
    (into {}
          (map (fn [schema]
                 (let [props (m/properties schema)]
                   [(keyword (:resourceType props)) (:fhir/search-registry props)])))
          (core/resolve-schemas breeze/specs))))

(deftest copied-registries-match-the-built-ones
  (doseq [[rt params] @#'mock-search/registries
          [param descriptor] params]
    (is (= descriptor (get-in @real-registries [rt param]))
        (str (name rt) " " param " drifted from server.search-registry"))))

(defn- ids [store rt params]
  (mapv :id (db/search store tenant rt params (get @real-registries rt))))

(defn- seeded [& resources]
  (let [store (mock/create-mock-store {})]
    (doseq [r resources]
      (db/create-resource store tenant (keyword (:resourceType r)) (:id r) r))
    store))

(deftest list-encounter-bare-id-and-code
  (let [allergies {:coding [{:system "http://breezeehr.com/list" :code "allergies"}]}
        store (seeded {:resourceType "List" :id "l1" :status "current" :mode "working"
                       :code allergies :encounter {:reference "Encounter/e1"}}
                      {:resourceType "List" :id "l2" :status "current" :mode "working"
                       :code {:coding [{:system "http://breezeehr.com/list" :code "medications"}]}
                       :encounter {:reference "Encounter/e1"}}
                      {:resourceType "List" :id "l3" :status "current" :mode "working"
                       :code allergies :encounter {:reference "Encounter/e2"}})]
    (is (= ["l1"] (ids store :List {"encounter" "e1" "code" "allergies"})))))

(deftest observation-latest-by-effective-date
  (let [obs (fn [id subject code at]
              {:resourceType "Observation" :id id :status "final"
               :subject {:reference (str "Patient/" subject)}
               :code {:coding [{:system "http://loinc.org" :code code}]}
               :effectiveDateTime at})
        store (seeded (obs "o-mid" "p1" "8302-2" "2026-03-01")
                      (obs "o-old" "p1" "8302-2" "2026-01-01")
                      (obs "o-new" "p1" "8302-2" "2026-05-01")
                      (obs "o-weight" "p1" "29463-7" "2026-06-01")
                      (obs "o-other" "p2" "8302-2" "2026-07-01"))]
    (testing "_sort=date orders by effective[x], oldest first"
      (is (= ["o-old"] (ids store :Observation {"subject" "p1" "code" "8302-2"
                                                "_sort" "date" "_count" "1"}))))
    (testing "_sort=-date finds the latest"
      (is (= ["o-new"] (ids store :Observation {"subject" "p1" "code" "8302-2"
                                                "_sort" "-date" "_count" "1"}))))))

(deftest patient-phone-matches-only-phone-contact-points
  (let [store (seeded {:resourceType "Patient" :id "p-phone"
                       :telecom [{:system "phone" :value "5551234567"}]}
                      {:resourceType "Patient" :id "p-fax"
                       :telecom [{:system "fax" :value "5551234567"}]})]
    (is (= ["p-phone"] (ids store :Patient {"phone" "5551234567"})))))
