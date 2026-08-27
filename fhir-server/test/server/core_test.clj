(ns server.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [malli.core :as m]
            [server.core :as sc]))

(deftest unmatched-route-returns-encoded-404
  (testing "the default not-found handler runs outside the router middleware
            chain, so its body must already be an encoded string; a raw map
            body makes the Jetty adapter throw (no StreamableResponseBody
            impl) and turns every unknown resource type into a 500"
    (let [app (sc/fhir-app nil [] :jwks-url "http://unused" :keto-url "http://unused")
          resp (app {:request-method :get
                     :uri "/default/fhir/UnknownType"
                     :headers {"accept" "application/fhir+json"}})]
      (is (= 404 (:status resp)))
      (is (string? (:body resp))
          "body must be pre-encoded; the router middleware never sees it")
      (is (= "application/fhir+json" (get-in resp [:headers "Content-Type"])))
      (is (= "OperationOutcome"
             (get (json/read-value (:body resp)) "resourceType"))))))

(def test-capability
  "Hand-rolled stand-in for a generated capability data map: one branch keyed
   by a meta.profile URL, plus a closed :default branch."
  {:resourceType "Widget"
   :dispatch (fn [m] (if (some #{"http://example.com/p"} (get-in m [:meta :profile]))
                       "http://example.com/p"
                       :default))
   :interactions ["read"]
   :search-params []
   :branches [["http://example.com/p" [:map {:closed true}
                                       [:resourceType :string]
                                       [:name :string]]]
              [:default [:map {:closed true}
                         [:resourceType :string]
                         [:status :string]]]]})

(deftest lenient-default-responses-relaxes-only-the-default-branch
  (let [props (m/properties (sc/resolve-schema
                             {:schema 'server.core-test/test-capability
                              :lenient-default-responses? true}))
        response-schema (:fhir/response-schema props)
        cap-schema (:fhir/cap-schema props)]
    (is (some? response-schema)
        "the knob attaches a response-only schema")
    (testing "the :default branch tolerates undeclared and missing keys"
      (is (true? (m/validate response-schema {:resourceType "Widget" :extra 1}))))
    (testing "keys that are present still validate against their declared schema"
      (is (false? (m/validate response-schema {:resourceType "Widget" :status 5}))))
    (testing "a profiled resource keeps validating against its strict branch"
      (is (false? (m/validate response-schema
                              {:resourceType "Widget"
                               :meta {:profile ["http://example.com/p"]}
                               :name "n"
                               :bogus 1}))))
    (testing "request-side cap-schema is unchanged"
      (is (false? (m/validate cap-schema {:resourceType "Widget" :extra 1}))))))
