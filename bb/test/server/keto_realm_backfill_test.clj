(ns server.keto-realm-backfill-test
  "Tests for the backfill's pure planning logic. The Keto access is passed in,
   so the dangerous parts -- realm attribution across the two subject
   spellings, and telling an already-scoped object from a legacy one -- are
   exercised without a running Keto."
  (:require [clojure.test :refer [deftest is testing]]
            [server.keto-realm-backfill :as backfill]))

(def ^:private realms #{"dev" "clinic2"})

(defn- legacy [object subject]
  {:namespace "fhir" :object object :relation "read" :subject_id subject})

(deftest realm-scoped-is-decided-by-the-known-realm-set
  (testing "an object whose first segment is a known realm is already done"
    (is (backfill/realm-scoped? realms "dev/Patient"))
    (is (backfill/realm-scoped? realms "dev/Patient/123"))
    (is (backfill/realm-scoped? realms "clinic2/system")))

  (testing "a legacy object is not, however many segments it has -- which is
            why segment counting cannot be the test: Patient/123 and
            dev/Patient both have two"
    (is (not (backfill/realm-scoped? realms "Patient")))
    (is (not (backfill/realm-scoped? realms "Patient/123")))
    (is (not (backfill/realm-scoped? realms "system"))))

  (testing "a realm not in the known set is treated as legacy rather than
            assumed: guessing here would skip a tuple that needs backfilling"
    (is (not (backfill/realm-scoped? realms "someotherrealm/Patient")))))

(deftest realms-come-from-the-first-segment-of-role-and-practitioner-objects
  (is (= #{"dev"}
         (backfill/realms-from-relations
          [{:object "dev/provider"}] [{:object "dev/prac-1"}])))
  (is (= #{"clinic2" "dev"}
         (backfill/realms-from-relations
          [{:object "dev/provider"} {:object "clinic2/nurse"}]
          [{:object "dev/prac-1"}])))
  (testing "a malformed object naming no realm contributes nothing"
    (is (= #{} (backfill/realms-from-relations [{:object ""}] [])))))

(deftest a-subject-in-one-realm-gets-one-scoped-tuple
  (let [p (backfill/plan realms nil (constantly #{"dev"})
                         [(legacy "Patient" "alice")])]
    (is (= 1 (count (:to-write p))))
    (is (= [{:namespace "fhir" :object "dev/Patient"
             :relation "read" :subject_id "alice"}]
           (:tuples (first (:to-write p)))))))

(deftest a-subject-in-several-realms-gets-one-tuple-per-realm
  (testing "the realm-blind tuple reaches every realm today, so narrowing to
            one of them would silently remove access the backfill was not
            asked to remove"
    (let [p (backfill/plan realms nil (constantly #{"dev" "clinic2"})
                           [(legacy "Patient" "bob")])]
      (is (= ["clinic2/Patient" "dev/Patient"]
             (mapv :object (:tuples (first (:to-write p)))))))))

(deftest a-subject-with-no-realm-evidence-is-reported-not-guessed
  (testing "machine subjects -- test clients, dev tokens, export service
            accounts -- hold fhir tuples and no role tuple to name a realm by.
            This list is the answer to whether anything relies on cross-realm
            access, so it must never be filled in by assumption"
    (let [p (backfill/plan realms nil (constantly #{})
                           [(legacy "Patient" "inferno-client")])]
      (is (empty? (:to-write p)))
      (is (= 1 (count (:unattributable p)))))))

(deftest an-explicit-realm-overrides-attribution
  (testing "--realm is how an operator resolves the unattributable list"
    (let [p (backfill/plan realms "dev" (constantly #{})
                           [(legacy "Patient" "inferno-client")])]
      (is (empty? (:unattributable p)))
      (is (= ["dev/Patient"] (mapv :object (:tuples (first (:to-write p)))))))))

(deftest already-scoped-tuples-are-left-alone
  (let [p (backfill/plan realms nil (constantly #{"dev"})
                         [(legacy "dev/Patient" "alice")])]
    (is (= 1 (count (:already-scoped p))))
    (is (empty? (:to-write p)))))

(deftest a-second-run-writes-nothing
  (testing "Keto's write is not an upsert -- identical PUTs pile up rows -- so
            the plan has to exclude tuples that already exist, or re-running
            the backfill inflates the store until list pages start truncating
            real grants"
    (let [tuples [(legacy "Patient" "alice")
                  (legacy "dev/Patient" "alice")]
          p (backfill/plan realms nil (constantly #{"dev"}) tuples)]
      (is (empty? (:to-write p)))
      (is (= 1 (count (:already-backfilled p)))
          "the legacy tuple is recognized as done, and is what --prune removes"))))

(deftest a-subject-set-tuple-keeps-its-subject
  (testing "a listing returns tuples carrying subject_set instead of
            subject_id. The object is rewritten like any other; the subject is
            never touched, and with no kratos id there is nothing to attribute
            a realm by, so it needs an explicit --realm"
    (let [set-tuple {:namespace "fhir" :object "Patient" :relation "read"
                     :subject_set {:namespace "breezeehr-role"
                                   :object "dev/provider"
                                   :relation "has-role"}}]
      (is (= 1 (count (:unattributable (backfill/plan realms nil
                                                      (constantly #{})
                                                      [set-tuple])))))
      (let [written (:tuples (first (:to-write (backfill/plan realms "dev"
                                                             (constantly #{})
                                                             [set-tuple]))))]
        (is (= "dev/Patient" (:object (first written))))
        (is (= (:subject_set set-tuple) (:subject_set (first written)))
            "rewriting the subject would corrupt the userset")))))
