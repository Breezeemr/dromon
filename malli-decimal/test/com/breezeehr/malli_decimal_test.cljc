(ns com.breezeehr.malli-decimal-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [com.breezeehr.malli-decimal :as md]))

(def test-registry
  (merge (m/default-schemas)
         md/decimal-schemas))

(deftest decimal-schema-test
  (let [sch (m/schema [:decimal {:min-characters   nil
                                 :max-characters   10
                                 :min              0.001M
                                 :max              999999.999M
                                 :max-decimal-places  3
                                 :disallow-trailing-zero true}]
                      {:registry test-registry})]

    (testing "valid inputs"
      (is (m/validate sch 1.5M))
      (is (m/validate sch 11.05M))
      (is (m/validate sch 999.999M)))

    (testing "invalid inputs"
      (is (not (m/validate sch :not-a-decimal)))
      ;; out of bounds max decimal places:
      (is (not (m/validate sch 1.5000000000000000000000000000M)))
      (is (not (m/validate sch 11.05000001M)))
      ;; trailing zeros:
      (is (not (m/validate sch 1.50M)))
      ;; boundaries:
      (is (not (m/validate sch -1.5M)))
      ;; characters limit:
      (is (not (m/validate sch 99999999.99M))))

    (testing "humanized errors"
      (is (some? (me/humanize (m/explain sch -1.5M))))
      (is (some? (me/humanize (m/explain sch 11.0500001M)))))))
