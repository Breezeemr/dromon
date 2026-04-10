(ns com.breezeehr.fhir-primitives-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [com.breezeehr.fhir-primitives :as fp]
            [com.breezeehr.malli-decimal :as md]))

(deftest decimal-schema-test
  (let [test-sch (m/schema [:decimal {:min-characters       nil
                                      :max-characters       10
                                      :min                  0.001M
                                      :max                  999999.999M
                                      :max-decimal-places   3
                                      :disallow-trailing-zero true}]
                            fp/external-registry)]
    (testing "valid decimals"
      (is (m/validate test-sch 1.5000000000000000000000000000M))
      (is (nil? (me/humanize (m/explain test-sch 1.5M)))))

    (testing "negative value fails"
      (is (some? (m/explain test-sch -1.5000000000000000000000000000M))))

    (testing "trailing zero fails"
      (is (some? (me/humanize (m/explain test-sch 1.000M)))))

    (testing "too many decimal places"
      (is (some? (me/humanize (m/explain test-sch 11.0500001M))))
      (is (some? (me/humanize (m/explain test-sch 11.05000001M)))))))

(deftest lazy-ref-schema-test
  (let [sch (m/schema
              [:schema {:registry {'test/test2 [:lazy-ref 'test/test3]
                                   'test/test3 :string}}
               [:map
                [:sub [:lazy-ref 'test/test2]]]]
              fp/external-registry)]
    (testing "lazy-ref resolves through chain"
      (is (m/validate sch {:sub "hello"}))
      (is (some? (m/explain sch {:sub 123}))))))
