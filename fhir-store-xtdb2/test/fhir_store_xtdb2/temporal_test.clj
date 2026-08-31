(ns fhir-store-xtdb2.temporal-test
  "Bitemporal store surface.

   The assertions here are the worked examples from
   `rcm-design/design/02-bitemporality.md` §2.1 and §2.2, expressed through the
   store protocol rather than raw SQL. They are the specification: each one is a
   question an incumbent RCM system cannot answer, and the point of the store
   work is that it becomes one call."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db])
  (:import [java.time Instant]))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- inst [s] (Instant/parse s))

(def ^:private dos        (inst "2026-05-14T00:00:00Z"))
(def ^:private term-date  (inst "2026-04-30T00:00:00Z"))
(def ^:private year-start (inst "2026-01-01T00:00:00Z"))

(defn- coverage [payer]
  {:status "active" :beneficiary {:reference "Patient/p123"} :payor [{:display payer}]})

(defn- payors [res] (mapv :display (:payor res)))

(deftest retro-eligibility-termination
  (testing "§2.1 — a coverage terminated retroactively is still what we knew at submission"
    (let [store (core-db/create-xtdb-store {})
          tid "t-retro"]
      (try
        ;; The coverage is true from Jan 1 onward, as far as anyone knows today.
        (db/put-valid-time store tid :Coverage "cov-aetna"
                           (coverage "aetna") {:valid-from year-start})

        (is (= ["aetna"] (payors (db/read-as-of store tid :Coverage "cov-aetna"
                                                {:valid-time dos})))
            "before any news, the DOS is covered by Aetna")

        (let [known-at-submission (:system-time (db/current-basis store tid))]
          (Thread/sleep 10)
          ;; July: the payer says coverage actually ended April 30.
          (db/close-valid-time store tid :Coverage "cov-aetna" term-date)

          (testing "Q1 — what we believed on the DOS at submission time"
            (is (= ["aetna"] (payors (db/read-as-of store tid :Coverage "cov-aetna"
                                                    {:valid-time dos
                                                     :system-time known-at-submission})))
                "the May submission was correct given knowledge at the time"))

          (testing "Q2 — what we now know was true on the DOS"
            (is (nil? (db/read-as-of store tid :Coverage "cov-aetna" {:valid-time dos}))
                "the claim must be rebilled: no coverage was valid at the DOS"))

          (testing "the two questions disagree, which is the whole point"
            (is (not= (db/read-as-of store tid :Coverage "cov-aetna"
                                     {:valid-time dos :system-time known-at-submission})
                      (db/read-as-of store tid :Coverage "cov-aetna" {:valid-time dos}))))

          (testing "history is preserved, not overwritten"
            (is (some? (db/read-as-of store tid :Coverage "cov-aetna"
                                      {:valid-time (inst "2026-03-01T00:00:00Z")}))
                "the pre-termination window is still covered")))
        (finally (close-store-nodes! store))))))

(deftest retro-amendment-restates-a-past-portion
  (testing "§2.2 — a retroactive correction changes the past without losing the old belief"
    (let [store (core-db/create-xtdb-store {})
          tid "t-amend"]
      (try
        (db/put-valid-time store tid :Coverage "cov-1"
                           (assoc (coverage "aetna") :network "in") {:valid-from year-start})
        (let [before (:system-time (db/current-basis store tid))]
          (Thread/sleep 10)
          ;; Restate only Feb-Mar; the rest of the timeline is untouched.
          (db/put-valid-time store tid :Coverage "cov-1" (coverage "cigna")
                             {:valid-from (inst "2026-02-01T00:00:00Z")
                              :valid-to   (inst "2026-03-01T00:00:00Z")})

          (is (= ["cigna"] (payors (db/read-as-of store tid :Coverage "cov-1"
                                                  {:valid-time (inst "2026-02-15T00:00:00Z")})))
              "inside the restated portion")
          (is (= ["aetna"] (payors (db/read-as-of store tid :Coverage "cov-1"
                                                  {:valid-time (inst "2026-06-15T00:00:00Z")})))
              "outside it, the original stands")
          (is (= ["aetna"] (payors (db/read-as-of store tid :Coverage "cov-1"
                                                  {:valid-time (inst "2026-02-15T00:00:00Z")
                                                   :system-time before})))
              "and the superseded belief is still readable at its own system time")

          (testing "a portion write REPLACES rather than merges"
            (is (nil? (:network (db/read-as-of store tid :Coverage "cov-1"
                                               {:valid-time (inst "2026-02-15T00:00:00Z")})))
                "an element absent from the new resource must not survive from the old")
            (is (= "in" (:network (db/read-as-of store tid :Coverage "cov-1"
                                                 {:valid-time (inst "2026-06-15T00:00:00Z")})))
                "while the untouched portion keeps it")))
        (finally (close-store-nodes! store))))))

(deftest search-honours-both-axes-alongside-ordinary-params
  (testing "a temporal basis composes with a normal search parameter"
    ;; Guards the bind-order trap: the temporal params precede the WHERE params,
    ;; so a wrong concat swaps a timestamp for a search value and still runs.
    (let [store (core-db/create-xtdb-store {})
          tid "t-search"
          registry {"status" {:type "token" :columns [{:col "status" :fhir-type "code" :array? false}]}}]
      (try
        (db/put-valid-time store tid :Coverage "c-active"
                           {:status "active" :payor [{:display "aetna"}]} {:valid-from year-start})
        (db/put-valid-time store tid :Coverage "c-cancelled"
                           {:status "cancelled" :payor [{:display "bcbs"}]} {:valid-from year-start})

        (let [hits (db/search-as-of store tid :Coverage {"status" "active"} registry
                                    {:valid-time dos})]
          (is (= 1 (count hits)))
          (is (= "c-active" (:id (first hits))))
          (is (= 1 (db/count-as-of-basis store tid :Coverage {"status" "active"} registry
                                         {:valid-time dos}))))

        (testing "the same search before the coverages were true finds nothing"
          (is (empty? (db/search-as-of store tid :Coverage {"status" "active"} registry
                                       {:valid-time (inst "2025-06-01T00:00:00Z")}))))

        (testing "the temporal selectors never leak into the WHERE clause"
          ;; _asOf / _validAt ride in the params map from the server layer; if
          ;; they reached build-condition they would compile to a comparison
          ;; against a column no table has.
          (is (= 1 (count (db/search-as-of store tid :Coverage
                                           {"status" "active" "_asOf" "2026-08-01T00:00:00Z"}
                                           registry {:valid-time dos})))))
        (finally (close-store-nodes! store))))))

(deftest timeline-reports-every-version-on-both-axes
  (testing "resource-timeline is the queryable claim timeline no incumbent ships"
    (let [store (core-db/create-xtdb-store {})
          tid "t-timeline"]
      (try
        (db/put-valid-time store tid :Coverage "cov-x" (coverage "aetna") {:valid-from year-start})
        (Thread/sleep 10)
        (db/close-valid-time store tid :Coverage "cov-x" term-date)

        (let [tl (db/resource-timeline store tid :Coverage "cov-x" nil)]
          (is (seq tl))
          (is (every? #(contains? % :system-from) tl))
          (is (every? #(contains? % :valid-from) tl)
              "a both-axes store reports valid-time bounds on every row")
          (testing "the surviving rectangle ends at the termination date"
            (is (some #(= (str term-date) (some-> (:valid-to %) .toInstant str)) tl))))

        (testing "the store advertises both axes"
          (is (= #{:system-time :valid-time} (db/temporal-axes store))))
        (finally (close-store-nodes! store))))))
