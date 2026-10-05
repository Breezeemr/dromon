(ns test-server.terminology-routes-test
  "The built-in terminology operations route on the Breeze surface test-server
   serves: `$expand` and `$validate-code` on ValueSet, `$lookup` on CodeSystem."
  (:require [clojure.test :refer [deftest is testing]]
            [reitit.core :as r]
            [reitit.ring :as ring]
            [server.core :as core]
            [server.routing :as routing]
            [test-server.schemas.breeze :as breeze]))

(def ^:private router
  (delay (ring/router (routing/build-fhir-routes (core/resolve-schemas breeze/specs))
                      {:conflicts nil})))

(defn- template [path]
  (:template (r/match-by-path @router path)))

(deftest terminology-operations-route-on-their-fhir-types
  (testing "ValueSet"
    (is (= "/:tenant-id/fhir/ValueSet/$expand" (template "/t/fhir/ValueSet/$expand")))
    (is (= "/:tenant-id/fhir/ValueSet/$validate-code" (template "/t/fhir/ValueSet/$validate-code")))
    (is (= "/:tenant-id/fhir/ValueSet/:id/$validate-code" (template "/t/fhir/ValueSet/vs1/$validate-code"))))
  (testing "CodeSystem"
    (is (= "/:tenant-id/fhir/CodeSystem/$lookup" (template "/t/fhir/CodeSystem/$lookup"))))
  (testing "no ValueSet/$lookup route: R4B declares none"
    (is (not= "/:tenant-id/fhir/ValueSet/$lookup" (template "/t/fhir/ValueSet/$lookup")))))
