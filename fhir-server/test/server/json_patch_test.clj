(ns server.json-patch-test
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [malli.core :as malli]
            [reitit.ring :as ring]
            [server.core :as sc]
            [server.json-patch :as json-patch]
            [server.router :as router])
  (:import [java.io ByteArrayInputStream InputStream]))

;; ---------------------------------------------------------------------------
;; The `test` operation (RFC 6902 section 4.6)
;;
;; The stored side is what a store reads back: fhir-store-datomic returns a
;; FHIR decimal as a BigDecimal. The expected side is what the PATCH body
;; decoded to, which for a JSON number is an Integer, Long or Double. Clojure
;; `=` keeps those apart; `test` must not.
;; ---------------------------------------------------------------------------

(def ^:private observation
  {:resourceType "Observation"
   :id "o1"
   :status "final"
   :valueQuantity {:value 72.5M :unit "kg" :system "http://unitsofmeasure.org" :code "kg"}
   :component [{:code {:text "systolic"} :valueQuantity {:value 120M :unit "mm[Hg]"}}
               {:code {:text "diastolic"} :valueQuantity {:value 80.0M :unit "mm[Hg]"}}]})

(defn- passes?
  "Whether a single `test` of `value` at `path` passes against `doc`. Any
   failure other than the test's own is rethrown."
  [doc path value]
  (try
    (json-patch/apply-patch doc [{:op "test" :path path :value value}])
    true
    (catch clojure.lang.ExceptionInfo e
      (if (= "Test operation failed" (ex-message e))
        false
        (throw e)))))

(deftest a-number-equals-a-stored-decimal-of-the-same-value
  (testing "a fractional value"
    (doseq [value [72.5 72.5M 72.50M 72.500M]]
      (is (passes? observation "/valueQuantity/value" value)
          (str (pr-str value) " (" (.getName (class value)) ") against 72.5M"))))
  (testing "a whole value, stored as 120M and as 80.0M"
    (doseq [[path whole] [["/component/0/valueQuantity/value" 120]
                          ["/component/1/valueQuantity/value" 80]]
            value [whole (int whole) (double whole) (bigdec whole)
                   (.setScale (bigdec whole) 2) (biginteger whole) (bigint whole)]]
      (is (passes? observation path value)
          (str (pr-str value) " (" (.getName (class value)) ") at " path)))))

(deftest a-number-of-a-different-value-fails
  (doseq [value [72.4 72.51M 72 725M -72.5]]
    (is (not (passes? observation "/valueQuantity/value" value))
        (pr-str value))))

(deftest a-double-is-read-through-its-shortest-decimal-form
  ;; The double nearest 0.1 is 0.1000000000000000055511151231257827...; read
  ;; as that exact expansion it would never equal a stored 0.1M.
  (let [doc {:valueDecimal 0.1M}]
    (is (passes? doc "/valueDecimal" 0.1))
    (is (not (passes? doc "/valueDecimal" 0.2)))))

(deftest a-nan-or-infinite-double-fails-without-throwing
  (doseq [value [##NaN ##Inf ##-Inf]]
    (is (not (passes? observation "/valueQuantity/value" value))
        (pr-str value))))

(deftest numbers-compare-by-value-inside-objects-and-arrays
  (is (passes? observation "/valueQuantity"
               {:value 72.5 :unit "kg" :system "http://unitsofmeasure.org" :code "kg"}))
  (is (passes? observation "/component"
               [{:code {:text "systolic"} :valueQuantity {:value 120 :unit "mm[Hg]"}}
                {:code {:text "diastolic"} :valueQuantity {:value 80 :unit "mm[Hg]"}}]))
  (is (passes? {:valueSampledData {:data [1M 2.50M 3.0M]}} "/valueSampledData/data"
               [1 2.5 3]))
  (is (not (passes? observation "/valueQuantity"
                    {:value 72.6 :unit "kg" :system "http://unitsofmeasure.org" :code "kg"}))
      "one differing number inside an object fails the whole test"))

(deftest a-string-never-equals-a-number
  ;; RFC 6902 section 4.6 requires both values to have the same JSON type, and
  ;; `test` cannot tell a decimal position from a string one.
  (doseq [value ["72.5" "72.50" "72"]]
    (is (not (passes? observation "/valueQuantity/value" value))
        (pr-str value)))
  (is (not (passes? {:valueString "72.5"} "/valueString" 72.5))
      "the same rule from the other side: a stored string is not a number"))

(deftest non-numbers-compare-as-before
  (testing "strings, booleans and null"
    (is (passes? observation "/status" "final"))
    (is (not (passes? observation "/status" "Final")))
    (is (passes? {:active true} "/active" true))
    (is (not (passes? {:active true} "/active" 1)))
    (is (not (passes? {:active false} "/active" nil))))
  (testing "objects need the same members"
    (is (passes? observation "/component/0/code" {:text "systolic"}))
    (is (not (passes? observation "/component/0/code" {:text "systolic" :id "x"})))
    (is (not (passes? observation "/component/0/code" {})))
    (is (not (passes? {:code {:text nil}} "/code" {}))
        "a member present as null is not a missing member"))
  (testing "arrays need the same elements in the same order"
    (is (not (passes? {:given ["A" "B"]} "/given" ["B" "A"])))
    (is (not (passes? {:given ["A" "B"]} "/given" ["A"])))
    (is (not (passes? {:given ["A"]} "/given" ["A" "B"])))
    (is (not (passes? {:given ["A"]} "/given" "A")))))

(deftest a-passing-test-leaves-the-document-unchanged
  (is (= observation
         (json-patch/apply-patch observation
                                 [{:op "test" :path "/valueQuantity/value" :value 72.5}]))))

(deftest a-failing-test-stops-the-patch
  (let [e (try
            (json-patch/apply-patch observation
                                    [{:op "test" :path "/valueQuantity/value" :value 99}
                                     {:op "replace" :path "/status" :value "amended"}])
            nil
            (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e))
    (is (= "Test operation failed" (ex-message e)))
    (is (= {:op "test" :path "/valueQuantity/value" :expected 99 :actual 72.5M}
           (ex-data e)))))

;; ---------------------------------------------------------------------------
;; Over HTTP
;;
;; A PATCH sent through the router, so the expected value is whatever the
;; `application/json-patch+json` decoder makes of a JSON number, and the
;; stored one a BigDecimal, as fhir-store-datomic reads a decimal back. This
;; is the guard jib3's Observation editor needs: a test of the stored value
;; before a replace. Keto is a pass-through; authorization is not under test.
;; ---------------------------------------------------------------------------

(def ^:private observation-schemas
  [(sc/capability-schema->server-schema
     (malli/schema [:map {:resourceType "Observation"
                          :interactions ["read" "patch"]
                          :search-params []}]))])

(defn- app [store]
  (ring/ring-handler
    (router/router observation-schemas
                   (-> (router/default-middleware
                         store (router/resolve-options {:enforce-smart-scopes? false}))
                       (router/replace-middleware ::router/keto-authorization
                                                  {:name ::router/keto-authorization
                                                   :wrap identity})))
    router/default-handler))

(defn- patch!
  "PATCH Observation/o1, seeded as [[observation]], with `ops-json` as the
   body. Returns [response stored-observation-after]."
  [ops-json]
  (let [store (mock/create-mock-store {})
        _ (db/create-resource store "default" :Observation "o1" observation)
        resp ((app store) {:request-method :patch
                           :uri "/default/fhir/Observation/o1"
                           :headers {"content-type" "application/json-patch+json"
                                     "accept" "application/fhir+json"}
                           :body (ByteArrayInputStream. (.getBytes ^String ops-json "UTF-8"))})]
    [resp (db/read-resource store "default" :Observation "o1")]))

(defn- guarded-replace [path value-json]
  (str "[{\"op\":\"test\",\"path\":\"" path "\",\"value\":" value-json "},"
       "{\"op\":\"replace\",\"path\":\"/status\",\"value\":\"amended\"}]"))

(defn- body-of [resp]
  (let [b (:body resp)]
    (if (instance? InputStream b) (slurp b) b)))

(deftest a-json-number-in-a-patch-body-equals-a-stored-decimal
  (doseq [[path value-json] [["/valueQuantity/value" "72.5"]
                             ["/valueQuantity/value" "72.50"]
                             ["/valueQuantity/value" "7.25e1"]
                             ["/component/0/valueQuantity/value" "120"]
                             ["/component/1/valueQuantity/value" "80"]
                             ["/component/1/valueQuantity/value" "80.00"]
                             ["/valueQuantity" (str "{\"value\":72.5,\"unit\":\"kg\","
                                                    "\"system\":\"http://unitsofmeasure.org\","
                                                    "\"code\":\"kg\"}")]]]
    (testing (str path " " value-json)
      (let [[resp stored] (patch! (guarded-replace path value-json))]
        (is (= 200 (:status resp)) (body-of resp))
        (is (= "amended" (:status stored)))))))

(deftest a-json-string-in-a-patch-body-does-not-equal-a-stored-decimal
  (let [[resp stored] (patch! (guarded-replace "/valueQuantity/value" "\"72.5\""))]
    (is (= 400 (:status resp)))
    (is (re-find #"Test operation failed" (body-of resp)))
    (is (= "final" (:status stored)))))

(deftest a-different-json-number-fails-the-patch
  (let [[resp stored] (patch! (guarded-replace "/valueQuantity/value" "72.6"))]
    (is (= 400 (:status resp)))
    (is (= "final" (:status stored)))))
