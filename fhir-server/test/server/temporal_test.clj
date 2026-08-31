(ns server.temporal-test
  "The per-axis capability gate.

   Three tiers, because the interesting failure is the middle one: a store with
   system time but no valid time must ACCEPT `_asOf` and REFUSE `_validAt`.
   Answering `_validAt` on the remaining axis would return the valid-now
   answer to a historical question, which is indistinguishable from success at
   the call site."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.handlers :as handlers]
            [server.temporal :as tmp]))

(def ^:private tenant "default")

(def ^:private registry
  {"status" {:type "token" :columns [{:col "status" :array? false}]}})

;; A store with one time axis, standing in for Datomic.
(defrecord SystemTimeOnlyStore [calls]
  db/IFHIRStore
  (search [_ _ _ _ _] (swap! calls conj :search) [])
  (count-resources [_ _ _ _ _] 0)
  (current-basis [_ _] {:tx-id 7 :system-time (java.time.Instant/parse "2026-09-30T06:00:00Z")})
  db/ITemporalReadStore
  (temporal-axes [_] #{:system-time})
  (read-as-of [_ _ _ _ basis] (swap! calls conj [:read-as-of basis]) {:id "x" :resourceType "Coverage"})
  (search-as-of [_ _ _ _ _ basis] (swap! calls conj [:search-as-of basis]) [])
  (count-as-of-basis [_ _ _ _ _ basis] (swap! calls conj [:count-as-of basis]) 0)
  (resource-timeline [_ _ _ _ _]
    [{:resource {:id "x" :resourceType "Coverage"}
      :system-from (java.time.Instant/parse "2026-01-01T00:00:00Z")}]))

;; A store with both axes, standing in for XTDB v2.
(defrecord BitemporalStore [calls]
  db/IFHIRStore
  (search [_ _ _ _ _] (swap! calls conj :search) [])
  (count-resources [_ _ _ _ _] 0)
  (current-basis [_ _] {:tx-id 9 :system-time (java.time.Instant/parse "2026-10-01T06:00:00Z")})
  db/ITemporalReadStore
  (temporal-axes [_] #{:system-time :valid-time})
  (read-as-of [_ _ _ _ basis] (swap! calls conj [:read-as-of basis]) {:id "x" :resourceType "Coverage"})
  (search-as-of [_ _ _ _ _ basis] (swap! calls conj [:search-as-of basis]) [])
  (count-as-of-basis [_ _ _ _ _ basis] (swap! calls conj [:count-as-of basis]) 0)
  (resource-timeline [_ _ _ _ _]
    [{:resource {:id "x" :resourceType "Coverage"}
      :valid-from (java.time.Instant/parse "2026-01-01T00:00:00Z")
      :valid-to   (java.time.Instant/parse "2026-04-30T00:00:00Z")
      :system-from (java.time.Instant/parse "2026-01-01T00:00:00Z")}]))

(defn- req [store params & {:keys [id]}]
  (cond-> {:fhir/store store
           :fhir/resource-type "Coverage"
           :fhir/search-registry registry
           :path-params {:tenant-id tenant}
           :query-params params
           :headers {}}
    id (assoc-in [:path-params :id] id)))

(defn- issue-codes [resp]
  (set (map :code (get-in resp [:body :issue]))))

(defn- diagnostics [resp]
  (apply str (map :diagnostics (get-in resp [:body :issue]))))

;; ---------------------------------------------------------------------------
;; Tier 1: no temporal support at all
;; ---------------------------------------------------------------------------

(deftest store-without-temporal-support-refuses-both-selectors
  (let [store (mock/create-mock-store {})]
    (doseq [p ["_asOf" "_validAt"]]
      (testing p
        (let [resp (handlers/search-type (req store {p "2026-09-30T00:00:00Z"}))]
          (is (= 400 (:status resp)))
          (is (= #{"not-supported"} (issue-codes resp)))
          (is (clojure.string/includes? (diagnostics resp) p)))))))

(deftest lenient-handling-does-not-excuse-an-unsupported-axis
  (testing "handling=lenient drops unknown params, but never a temporal selector"
    ;; Dropping it would run the search against current state and return 200,
    ;; which reads as a successful answer to the question that was asked.
    (let [store (mock/create-mock-store {})
          resp (handlers/search-type
                (assoc (req store {"_asOf" "2026-09-30T00:00:00Z"})
                       :headers {"prefer" "handling=lenient"}))]
      (is (= 400 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Tier 2: system time only — the case that matters
;; ---------------------------------------------------------------------------

(deftest system-time-store-accepts-as-of
  (let [calls (atom [])
        store (->SystemTimeOnlyStore calls)
        resp (handlers/search-type (req store {"_asOf" "2026-09-30T00:00:00Z"}))]
    (is (= 200 (:status resp)))
    (is (some #(and (vector? %) (= :search-as-of (first %))) @calls)
        "routed to the temporal read, not the plain search")
    (is (not-any? #{:search} @calls))))

(deftest system-time-store-refuses-valid-at
  (let [calls (atom [])
        store (->SystemTimeOnlyStore calls)
        resp (handlers/search-type (req store {"_validAt" "2026-05-14"}))]
    (is (= 400 (:status resp)))
    (is (= #{"not-supported"} (issue-codes resp)))
    (is (clojure.string/includes? (diagnostics resp) "_validAt"))
    (is (clojure.string/includes? (diagnostics resp) "valid-time")
        "names the missing axis, not just the parameter")
    (is (empty? @calls) "no query ran")))

(deftest mixed-selectors-fail-on-the-missing-axis
  (let [calls (atom [])
        store (->SystemTimeOnlyStore calls)
        resp (handlers/search-type (req store {"_asOf" "2026-09-30T00:00:00Z"
                                               "_validAt" "2026-05-14"}))]
    (is (= 400 (:status resp)) "one unavailable axis refuses the whole request")
    (is (empty? @calls))))

;; ---------------------------------------------------------------------------
;; Tier 3: both axes
;; ---------------------------------------------------------------------------

(deftest bitemporal-store-honours-both-axes
  (let [calls (atom [])
        store (->BitemporalStore calls)
        resp (handlers/search-type (req store {"_asOf" "2026-10-01T06:00:00Z"
                                               "_validAt" "2026-09-30"}))]
    (is (= 200 (:status resp)))
    (let [[_ basis] (first (filter vector? @calls))]
      (is (= (java.time.Instant/parse "2026-10-01T06:00:00Z") (:system-time basis)))
      (is (= (java.time.Instant/parse "2026-09-30T00:00:00Z") (:valid-time basis))
          "a date-only _validAt means start of that day, UTC"))))

(deftest response-states-the-basis-it-used
  (testing "an omitted _asOf resolves to the latest indexed transaction, and says so"
    ;; Without this a report cannot be reproduced: 'as best known' names a
    ;; different instant on every request.
    (let [store (->BitemporalStore (atom []))
          resp (handlers/search-type (req store {"_validAt" "2026-09-30"}))
          tags (get-in resp [:body :meta :tag])]
      (is (= 200 (:status resp)))
      (is (= #{"system-time" "valid-time"} (set (map :code tags))))
      (is (= "2026-10-01T06:00:00Z"
             (:display (first (filter #(= "system-time" (:code %)) tags))))
          "the concrete resolved instant, not the absent request value"))))

(deftest ordinary-search-is-untouched
  (testing "no selectors means no basis, no tags, and the plain search path"
    (let [calls (atom [])
          store (->BitemporalStore calls)
          resp (handlers/search-type (req store {"status" "active"}))]
      (is (= 200 (:status resp)))
      (is (= [:search] @calls))
      (is (nil? (get-in resp [:body :meta]))))))

;; ---------------------------------------------------------------------------
;; Parsing and the instance-level operations
;; ---------------------------------------------------------------------------

(deftest unparseable-selector-is-a-400
  (let [store (->BitemporalStore (atom []))
        resp (handlers/search-type (req store {"_asOf" "last Tuesday"}))]
    (is (= 400 (:status resp)))
    (is (= #{"invalid"} (issue-codes resp)))))

(deftest as-of-requires-a-selector
  (testing "$as-of with no selector is refused rather than answered as current state"
    (let [store (->BitemporalStore (atom []))
          resp (handlers/resource-as-of (req store {} :id "x"))]
      (is (= 400 (:status resp)))
      (is (clojure.string/includes? (diagnostics resp) "_asOf")))))

(deftest as-of-returns-the-resource-and-its-basis
  (let [store (->BitemporalStore (atom []))
        resp (handlers/resource-as-of (req store {"_validAt" "2026-05-14"} :id "x"))]
    (is (= 200 (:status resp)))
    (is (= #{"system-time" "valid-time"}
           (set (map :code (get-in resp [:body :meta :tag])))))))

(deftest as-of-is-instance-level
  (let [store (->BitemporalStore (atom []))
        resp (handlers/resource-as-of (req store {"_validAt" "2026-05-14"}))]
    (is (= 400 (:status resp)))
    (is (clojure.string/includes? (diagnostics resp) "instance-level"))))

(deftest timeline-omits-valid-time-on-a-single-axis-store
  (testing "absent valid-time bounds, never null ones"
    ;; A null valid-to would read as end-of-time; the axis simply does not exist.
    (let [resp (handlers/resource-timeline (req (->SystemTimeOnlyStore (atom [])) {} :id "x"))
          urls (set (map :url (:extension (first (get-in resp [:body :entry])))))]
      (is (= 200 (:status resp)))
      (is (contains? urls (str tmp/extension-base "system-from")))
      (is (not-any? #(clojure.string/includes? % "valid-") urls)))))

(deftest timeline-reports-both-axes-on-a-bitemporal-store
  (let [resp (handlers/resource-timeline (req (->BitemporalStore (atom [])) {} :id "x"))
        urls (set (map :url (:extension (first (get-in resp [:body :entry])))))]
    (is (= 200 (:status resp)))
    (is (= "history" (get-in resp [:body :type])))
    (is (contains? urls (str tmp/extension-base "valid-from")))
    (is (contains? urls (str tmp/extension-base "valid-to")))))

(deftest timeline-needs-a-temporal-store
  (let [resp (handlers/resource-timeline (req (mock/create-mock-store {}) {} :id "x"))]
    (is (= 400 (:status resp)))
    (is (= #{"not-supported"} (issue-codes resp)))))
