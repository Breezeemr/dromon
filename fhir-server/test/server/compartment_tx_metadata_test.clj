(ns server.compartment-tx-metadata-test
  "A patient-scoped request's CompartmentFilteringStore answers
   `supports-tx-metadata?` by delegating to its base, so the write handlers
   stamp its writes exactly when the base keeps stamps."
  (:require [clojure.test :refer [deftest is]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.compartment :as compartment]))

(defn- store-that-keeps [keeps?]
  (reify
    db/ITxMetadataStore
    (tx-metadata-supported? [_] keeps?)
    (tx-metadata-of [_ _ _ _ _] nil)))

(deftest the-compartment-store-delegates-the-question
  (let [registries {}
        over (fn [base] (compartment/filtering-store base {:patient-id "p1"
                                                           :all-registries registries}))]
    (is (true? (db/supports-tx-metadata? (over (store-that-keeps true)))))
    (is (false? (db/supports-tx-metadata? (over (store-that-keeps false)))))
    (is (false? (db/supports-tx-metadata? (over (mock/create-mock-store {})))))))
