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

;; ---------------------------------------------------------------------------
;; A bounded close.
;;
;; `close-valid-time` takes a `valid-to`, so a retroactive termination can
;; shorten the run it is about instead of erasing everything after its start.
;; The tests below are the boundary matrix: nil, inside a rectangle, exactly on
;; the next rectangle's valid-from, in a gap, over nothing, and inverted.
;; ---------------------------------------------------------------------------

(def ^:private feb (inst "2026-02-01T00:00:00Z"))
(def ^:private mar (inst "2026-03-01T00:00:00Z"))
(def ^:private apr (inst "2026-04-01T00:00:00Z"))
(def ^:private may (inst "2026-05-01T00:00:00Z"))
(def ^:private jun (inst "2026-06-01T00:00:00Z"))
(def ^:private jul (inst "2026-07-01T00:00:00Z"))
(def ^:private long-after   (inst "2030-01-01T00:00:00Z"))
(def ^:private prior-mid    (inst "2025-06-01T00:00:00Z"))
(def ^:private prior-start  (inst "2025-01-01T00:00:00Z"))

(defn- current-rows
  "Timeline rows still believed. A nil :system-to is what 'currently' means."
  [store tid id]
  (filter #(nil? (:system-to %)) (db/resource-timeline store tid :Coverage id nil)))

(defn- current-rects
  "The currently believed rectangles as [valid-from valid-to payor], oldest
   first. A nil valid-to is end-of-time."
  [store tid id]
  (->> (current-rows store tid id)
       (map (fn [row]
              [(some-> (:valid-from row) .toInstant str)
               (some-> (:valid-to row) .toInstant str)
               (first (payors (:resource row)))]))
       (sort-by first)
       vec))

(defn- row-starting-at [store tid id t]
  (first (filter #(= (str t) (some-> (:valid-from %) .toInstant str))
                 (current-rows store tid id))))

(defn- payor-at [store tid id t]
  (first (payors (db/read-as-of store tid :Coverage id {:valid-time t}))))

(defn- covers? [row ^Instant t]
  (let [from (some-> (:valid-from row) .toInstant)
        to   (some-> (:valid-to row) .toInstant)]
    (and (or (nil? from) (not (.isAfter ^Instant from t)))
         (or (nil? to) (.isBefore t ^Instant to)))))

(defn- refusal-code
  "The invalid-portion code, wherever in the chain it sits. The store raises it
   itself before issuing the statement, so it is on the top-level ex-data; when
   the engine raises it instead, a store span rethrows that wrapped and the
   code sits on a cause. Walking the chain covers both."
  [t]
  (some #(:xtdb.error/code (ex-data %))
        (take-while some? (iterate ex-cause t))))

(defn- close-failure [f]
  (try (f) nil (catch Throwable t t)))

(deftest bounded-close-with-nil-is-the-unbounded-close
  (testing "the five-argument close IS the six-argument close with nil"
    ;; Backward compatibility, and the reason there is only one code path: the
    ;; shorter arity delegates rather than keeping a second statement alive.
    (let [store (core-db/create-xtdb-store {})
          tid "t-nil-bound"]
      (try
        (doseq [id ["cov-5" "cov-6"]]
          (db/put-valid-time store tid :Coverage id (coverage "aetna")
                             {:valid-from year-start}))
        (Thread/sleep 10)
        (db/close-valid-time store tid :Coverage "cov-5" term-date)
        (db/close-valid-time store tid :Coverage "cov-6" term-date nil)

        (is (= (current-rects store tid "cov-5") (current-rects store tid "cov-6"))
            "both arities land on the same timeline")
        (is (= [[(str year-start) (str term-date) "aetna"]]
               (current-rects store tid "cov-6"))
            "which is today's outcome: one rectangle ending at the termination")
        (doseq [t [mar dos long-after]]
          (is (= (payor-at store tid "cov-5" t) (payor-at store tid "cov-6" t))
              (str "the two arities agree at " t)))

        (testing "nil is end-of-time, not now"
          (is (nil? (payor-at store tid "cov-6" long-after))
              "a read years past today still finds nothing, so the close ran to
               the end of the axis rather than stopping at the present"))
        (finally (close-store-nodes! store))))))

(deftest a-bound-inside-a-rectangle-splits-it
  (testing "the portion is erased and what lies beyond it stands"
    (let [store (core-db/create-xtdb-store {})
          tid "t-split"]
      (try
        (db/put-valid-time store tid :Coverage "cov" (coverage "aetna")
                           {:valid-from year-start})
        (Thread/sleep 10)
        (db/close-valid-time store tid :Coverage "cov" mar jun)

        (is (= [[(str year-start) (str mar) "aetna"]
                [(str jun) nil "aetna"]]
               (current-rects store tid "cov"))
            "one rectangle became two, not none")
        (is (= "aetna" (payor-at store tid "cov" feb)) "before the portion")
        (is (nil? (payor-at store tid "cov" apr)) "inside it")
        (is (= "aetna" (payor-at store tid "cov" jul)) "after it")
        (finally (close-store-nodes! store))))))

(deftest a-bound-equal-to-the-next-start-leaves-it-untouched
  (testing "a termination shortens the run it is about, and a separately stated later period stands"
    ;; The half-open axis at its sharpest: the bound is the next rectangle's
    ;; own valid-from, and that rectangle must not be touched at all.
    (let [store (core-db/create-xtdb-store {})
          tid "t-boundary"]
      (try
        (db/put-valid-time store tid :Coverage "cov" (coverage "aetna")
                           {:valid-from year-start})
        (Thread/sleep 10)
        (db/put-valid-time store tid :Coverage "cov" (coverage "cigna")
                           {:valid-from jul})
        (let [cigna-before (:system-from (row-starting-at store tid "cov" jul))]
          (is (some? cigna-before) "the later period is there to be left alone")
          (Thread/sleep 10)
          (db/close-valid-time store tid :Coverage "cov" apr jul)

          (is (= [[(str year-start) (str apr) "aetna"]
                  [(str jul) nil "cigna"]]
                 (current-rects store tid "cov")))
          (is (= cigna-before (:system-from (row-starting-at store tid "cov" jul)))
              "the later period was not rewritten: its system time is unchanged")
          (is (= "aetna" (payor-at store tid "cov" mar)) "before the termination")
          (is (nil? (payor-at store tid "cov" may)) "inside the terminated run")
          (is (= "cigna" (payor-at store tid "cov" jul))
              "and the bound itself belongs to the later period"))
        (finally (close-store-nodes! store))))))

(deftest a-portion-is-a-cut-not-a-lookup
  (testing "neither bound need coincide with an existing boundary"
    (let [store (core-db/create-xtdb-store {})
          tid "t-cut"]
      (try
        (doseq [id ["cov-on" "cov-before"]]
          (db/put-valid-time store tid :Coverage id (coverage "aetna")
                             {:valid-from year-start}))
        (Thread/sleep 10)
        (db/close-valid-time store tid :Coverage "cov-on" year-start mar)
        (db/close-valid-time store tid :Coverage "cov-before" prior-mid mar)

        (is (= [[(str mar) nil "aetna"]] (current-rects store tid "cov-on"))
            "a portion starting exactly at the rectangle's start")
        (is (= [[(str mar) nil "aetna"]] (current-rects store tid "cov-before"))
            "and one starting before it reach the same place")
        (finally (close-store-nodes! store))))))

(deftest a-bound-in-a-gap-erases-only-the-intersection
  (testing "a portion needs no rectangle under it"
    (let [store (core-db/create-xtdb-store {})
          tid "t-gap"]
      (try
        (db/put-valid-time store tid :Coverage "cov" (coverage "aetna")
                           {:valid-from year-start :valid-to apr})
        (Thread/sleep 10)
        (db/put-valid-time store tid :Coverage "cov" (coverage "cigna")
                           {:valid-from jul})
        (Thread/sleep 10)
        ;; Feb to May spans the end of the first period, the gap, and nothing else.
        (db/close-valid-time store tid :Coverage "cov" feb may)

        (is (= [[(str year-start) (str feb) "aetna"]
                [(str jul) nil "cigna"]]
               (current-rects store tid "cov"))
            "exactly the intersection went, and the gap was not an error")
        (finally (close-store-nodes! store))))))

(deftest a-portion-touching-nothing-writes-nothing
  (testing "a close over a stretch no version occupies is a no-op, not an error"
    (let [store (core-db/create-xtdb-store {})
          tid "t-untouched"]
      (try
        (db/put-valid-time store tid :Coverage "cov" (coverage "aetna")
                           {:valid-from year-start})
        (let [rects-before (current-rects store tid "cov")
              rows-before  (count (db/resource-timeline store tid :Coverage "cov" nil))]
          (Thread/sleep 10)
          (db/close-valid-time store tid :Coverage "cov" prior-start prior-mid)

          (is (= rects-before (current-rects store tid "cov")))
          (is (= rows-before (count (db/resource-timeline store tid :Coverage "cov" nil)))
              "no phantom version: nothing was written at all"))
        (finally (close-store-nodes! store))))))

(deftest an-inverted-or-empty-portion-is-refused
  (testing "a valid-to at or before valid-from is refused with nothing written"
    ;; The rule this pins is that such a portion is neither a no-op nor a close
    ;; to end-of-time. Reading it as `TO NULL` would turn a zero-width close
    ;; into an unbounded one, silently.
    ;;
    ;; The fixture is BOUNDED and half the portions fall outside it on purpose.
    ;; The engine validates a portion only for the rows its DELETE selects, so
    ;; over one open-ended rectangle every portion overlaps and the refusal
    ;; fires whoever owns the rule -- a fixture that cannot tell the store's
    ;; rule from the engine's incidental one, and so proves neither. The
    ;; selecting-nothing pair is where the engine falls silent, and is what
    ;; makes this a test of the guarantee the protocol states rather than of
    ;; the rows that happened to overlap.
    (let [store (core-db/create-xtdb-store {})
          tid "t-refused"]
      (try
        (db/put-valid-time store tid :Coverage "cov" (coverage "aetna")
                           {:valid-from year-start :valid-to jun})
        (let [rects-before (current-rects store tid "cov")
              rows-before  (count (db/resource-timeline store tid :Coverage "cov" nil))]
          (doseq [[label from to] [["inverted, overlapping the rectangle" apr feb]
                                   ["empty, overlapping the rectangle" mar mar]
                                   ["inverted, selecting nothing" long-after jul]
                                   ["empty, selecting nothing" jul jul]]]
            (testing label
              (let [failure (close-failure
                             #(db/close-valid-time store tid :Coverage "cov" from to))]
                (is (some? failure) "the write must not be accepted")
                (is (= :xtdb.indexer/invalid-valid-times (refusal-code failure))
                    "and the refusal names the portion, whoever raised it")
                (is (= rects-before (current-rects store tid "cov"))
                    "the resource is untouched")
                (is (= rows-before
                       (count (db/resource-timeline store tid :Coverage "cov" nil)))
                    "and no version was written")))))
        (finally (close-store-nodes! store))))))

(deftest a-bounded-close-preserves-history
  (testing "the bounded form is a retraction on the system axis, not a rewrite"
    (let [store (core-db/create-xtdb-store {})
          tid "t-bounded-history"]
      (try
        (db/put-valid-time store tid :Coverage "cov" (coverage "aetna")
                           {:valid-from year-start})
        (let [before (:system-time (db/current-basis store tid))]
          (Thread/sleep 10)
          (db/close-valid-time store tid :Coverage "cov" mar jun)

          (is (= ["aetna"] (payors (db/read-as-of store tid :Coverage "cov"
                                                  {:valid-time apr :system-time before})))
              "what we believed about April before the news is still readable")
          (is (nil? (db/read-as-of store tid :Coverage "cov" {:valid-time apr}))
              "while what we now know about April is that nothing covered it")
          (is (= "aetna" (payor-at store tid "cov" jul))
              "and July, past the bound, was never in question")
          (is (some #(and (some? (:system-to %)) (covers? % apr))
                    (db/resource-timeline store tid :Coverage "cov" nil))
              "and the retracted portion is still in the timeline, closed on the
               system axis rather than deleted"))
        (finally (close-store-nodes! store))))))
