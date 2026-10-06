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
;; decoded to, which for a JSON number is an Integer, Long or BigDecimal. A
;; Double is covered too, for a caller that applies a patch it built itself.
;; Clojure `=` keeps those apart; `test` must not.
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
;; Operations below an array element
;;
;; A path through an array element (/note/0/text) must rewrite that element
;; in place. An earlier engine put the edited element back with `add`
;; semantics, so the edited copy was inserted in front of the original and
;; the resource ended up with both.
;; ---------------------------------------------------------------------------

(def ^:private condition
  {:resourceType "Condition"
   :id "c1"
   :clinicalStatus {:coding [{:system "http://terminology.hl7.org/CodeSystem/condition-clinical"
                              :code "active"}]}
   :identifier [{:system "urn:a" :value "1"} {:system "urn:b" :value "2"}]
   :note [{:text "Seen at Lafayette General, 3 nights"}
          {:text "second" :authorString "Lee"}]})

(defn- patched [doc & ops]
  (json-patch/apply-patch doc (vec ops)))

(defn- refusal
  "The ex-message of the ex-info `ops` throw against `doc`, or nil."
  [doc ops]
  (try
    (json-patch/apply-patch doc ops)
    nil
    (catch clojure.lang.ExceptionInfo e
      (ex-message e))))

(deftest replace-below-an-array-element-edits-that-element
  (testing "the guarded note edit jib3's hospitalizations page sends"
    (is (= [{:text "Seen at Lafayette General, 3 nights; appendectomy"}
            {:text "second" :authorString "Lee"}]
           (:note (patched condition
                           {:op "test" :path "/note/0/text"
                            :value "Seen at Lafayette General, 3 nights"}
                           {:op "replace" :path "/note/0/text"
                            :value "Seen at Lafayette General, 3 nights; appendectomy"})))))
  (testing "the last element, and a path two arrays deep"
    (is (= [{:text "Seen at Lafayette General, 3 nights"} {:text "x" :authorString "Lee"}]
           (:note (patched condition {:op "replace" :path "/note/1/text" :value "x"}))))
    (is (= [{:system "http://terminology.hl7.org/CodeSystem/condition-clinical"
             :code "resolved"}]
           (get-in (patched condition {:op "replace" :path "/clinicalStatus/coding/0/code"
                                       :value "resolved"})
                   [:clinicalStatus :coding]))))
  (testing "a whole element"
    (is (= [{:system "urn:a" :value "1"} {:system "urn:c" :value "3"}]
           (:identifier (patched condition {:op "replace" :path "/identifier/1"
                                            :value {:system "urn:c" :value "3"}}))))))

(deftest remove-below-an-array-element-edits-that-element
  (is (= [{:text "Seen at Lafayette General, 3 nights"} {:text "second"}]
         (:note (patched condition {:op "remove" :path "/note/1/authorString"}))))
  (is (= [{:text "second" :authorString "Lee"}]
         (:note (patched condition {:op "remove" :path "/note/0"}))))
  (is (= [{:text "Seen at Lafayette General, 3 nights"}]
         (:note (patched condition {:op "remove" :path "/note/1"})))))

(deftest add-below-an-array-element-edits-that-element
  (testing "a member of an element"
    (is (= [{:text "Seen at Lafayette General, 3 nights" :authorString "Kim"}
            {:text "second" :authorString "Lee"}]
           (:note (patched condition {:op "add" :path "/note/0/authorString" :value "Kim"})))))
  (testing "an element inserted at an index, appended at the end and at -"
    (is (= ["new" "Seen at Lafayette General, 3 nights" "second"]
           (map :text (:note (patched condition {:op "add" :path "/note/0"
                                                 :value {:text "new"}})))))
    (is (= ["Seen at Lafayette General, 3 nights" "second" "new"]
           (map :text (:note (patched condition {:op "add" :path "/note/2"
                                                 :value {:text "new"}})))
           (map :text (:note (patched condition {:op "add" :path "/note/-"
                                                 :value {:text "new"}})))))))

(deftest move-and-copy-below-an-array-element
  (is (= [{:text "Seen at Lafayette General, 3 nights" :authorString "Lee"}
          {:text "second"}]
         (:note (patched condition {:op "move" :from "/note/1/authorString"
                                    :path "/note/0/authorString"}))))
  (is (= [{:text "Seen at Lafayette General, 3 nights" :authorString "Lee"}
          {:text "second" :authorString "Lee"}]
         (:note (patched condition {:op "copy" :from "/note/1/authorString"
                                    :path "/note/0/authorString"}))))
  (is (= ["second" "Seen at Lafayette General, 3 nights"]
         (map :text (:note (patched condition {:op "move" :from "/note/0" :path "/note/1"}))))))

(deftest a-target-that-does-not-exist-is-refused
  ;; RFC 6902 section 4: remove, replace, move and copy need an existing
  ;; target (or source), and add an existing parent with an index no greater
  ;; than the array's size.
  (doseq [op [{:op "replace" :path "/note/2/text" :value "x"}
              {:op "replace" :path "/note/0/authorString" :value "x"}
              {:op "replace" :path "/abatementDateTime" :value "2024"}
              {:op "remove" :path "/note/2"}
              {:op "remove" :path "/note/0/authorString"}
              {:op "remove" :path "/abatementDateTime"}
              {:op "add" :path "/note/3" :value {:text "x"}}
              {:op "add" :path "/note/2/text" :value "x"}
              {:op "add" :path "/stage/0/summary" :value {:text "x"}}
              {:op "move" :from "/note/2" :path "/note/0"}
              {:op "copy" :from "/note/0/authorString" :path "/note/1/authorString"}]]
    (is (= (str "Path not found: " (or (:from op) (:path op)))
           (refusal condition [op]))
        (pr-str op))))

(deftest a-test-of-a-target-that-does-not-exist-fails
  ;; A target that is gone is the resource moving under the client, which is
  ;; what a failed test reports.
  (is (= "Test operation failed"
         (refusal condition [{:op "test" :path "/note/2/text" :value "x"}])
         (refusal condition [{:op "test" :path "/abatementDateTime" :value nil}]))))

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

(deftest a-replaced-note-text-is-stored-as-one-note
  (let [store (mock/create-mock-store {})
        _ (db/create-resource store "default" :Observation "o1"
                              (assoc observation :note [{:text "Seen at Lafayette General, 3 nights"}]))
        resp ((app store) {:request-method :patch
                           :uri "/default/fhir/Observation/o1"
                           :headers {"content-type" "application/json-patch+json"
                                     "accept" "application/fhir+json"}
                           :body (ByteArrayInputStream.
                                   (.getBytes (str "[{\"op\":\"test\",\"path\":\"/note/0/text\","
                                                   "\"value\":\"Seen at Lafayette General, 3 nights\"},"
                                                   "{\"op\":\"replace\",\"path\":\"/note/0/text\","
                                                   "\"value\":\"Seen at Lafayette General, 3 nights; appendectomy\"}]")
                                              "UTF-8"))})]
    (is (= 200 (:status resp)) (body-of resp))
    (is (= [{:text "Seen at Lafayette General, 3 nights; appendectomy"}]
           (:note (db/read-resource store "default" :Observation "o1"))))))

(deftest a-replace-of-a-missing-element-is-refused-and-stores-nothing
  (let [[resp stored] (patch! "[{\"op\":\"replace\",\"path\":\"/component/2/code\",\"value\":{}}]")]
    (is (= 400 (:status resp)))
    (is (re-find #"Path not found: /component/2/code" (body-of resp)))
    (is (= (:component observation) (:component stored)))))

(deftest a-replaced-decimal-reaches-the-store-with-its-scale
  ;; A PATCH result is written without request coercion, so the store gets
  ;; exactly what the decoder made of the JSON number.
  (let [[resp stored] (patch! (str "[{\"op\":\"replace\",\"path\":\"/valueQuantity/value\","
                                   "\"value\":80.50}]"))
        value (get-in stored [:valueQuantity :value])]
    (is (= 200 (:status resp)) (body-of resp))
    (is (instance? BigDecimal value) (pr-str value))
    (is (= 80.50M value))
    (is (= 2 (.scale ^BigDecimal value)))))
