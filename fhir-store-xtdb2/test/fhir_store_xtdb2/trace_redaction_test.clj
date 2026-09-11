(ns fhir-store-xtdb2.trace-redaction-test
  "Checks the real store's spans against the leak they used to carry.

  Every verb here is wrapped in a span whose default message is
  \"<form> => <return value>\", at Telemere's default `:info` level. The return
  value is the resource, so `read-resource` on a Coverage logged its
  `subscriberId` and on a Patient its names and birth date. These tests drive
  an actual XTDB node so the assertion covers what the store really returns,
  not a stand-in."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [taoensso.telemere :as t]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]))

(def ^:private phi
  ["ZZLEAKFAMILY" "ZZLEAKGIVEN" "1980-02-03" "ZZLEAKMEMBERID"])

(def ^:private patient
  {:name [{"family" "ZZLEAKFAMILY" "given" ["ZZLEAKGIVEN"]}]
   :birthDate "1980-02-03"
   :active true})

(def ^:private coverage
  {:status "active"
   :subscriberId "ZZLEAKMEMBERID"
   :beneficiary {"reference" "Patient/p1"}})

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- signals-text
  "Every signal a verb emits, rendered the way a handler would see it: the
   message plus every other field, including :run-val."
  [signals]
  (->> signals
       (map (fn [s] (str (some-> (:msg_ s) force) " " (pr-str (dissoc s :msg_)))))
       (str/join "\n")))

(deftest store-verbs-emit-no-resource-content
  (let [store (core-db/create-xtdb-store {})
        tenant "trace-redaction"]
    (try
      (let [{:keys [signals]}
            (t/with-signals
              (do (db/create-resource store tenant :Patient "p1" patient)
                  (db/create-resource store tenant :Coverage "c1" coverage)
                  (db/read-resource store tenant :Patient "p1")
                  (db/read-resource store tenant :Coverage "c1")
                  (db/update-resource store tenant :Patient "p1"
                                      (assoc patient :gender "female"))
                  (db/search store tenant :Patient {:active true} nil)
                  (db/history store tenant :Patient "p1")))
            text (signals-text signals)]

        (testing "the verbs really ran and really traced"
          (is (seq signals))
          (is (str/includes? text ":store/read")))

        (testing "no element value reaches any signal"
          (doseq [secret phi]
            (is (not (str/includes? text secret))
                (str secret " reached a store signal"))))

        (testing "the span still identifies the resource"
          ;; The store returns element maps without a :resourceType -- the
          ;; server layer adds that -- so the shape reads as #Map[n] and the
          ;; type and id come from the span's :data.
          (is (str/includes? text "#Map["))
          (is (str/includes? text ":resource-type \"Patient\""))
          (is (str/includes? text ":id \"p1\""))))

      (finally (close-store-nodes! store)))))

(deftest a-read-still-returns-the-whole-resource
  (let [store (core-db/create-xtdb-store {})
        tenant "trace-redaction-return"]
    (try
      (db/create-resource store tenant :Patient "p1" patient)
      (let [result (db/read-resource store tenant :Patient "p1")]
        (is (= "1980-02-03" (:birthDate result))
            "redaction applies to the signal, never to the store's answer")
        (is (= [{:family "ZZLEAKFAMILY" :given ["ZZLEAKGIVEN"]}] (:name result))))
      (finally (close-store-nodes! store)))))
