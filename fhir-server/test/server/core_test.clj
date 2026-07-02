(ns server.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
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
