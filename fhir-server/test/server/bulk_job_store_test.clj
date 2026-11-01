(ns server.bulk-job-store-test
  "The JobStore protocol, its in-memory default, and the job->edn / edn->job
   codec a durable store shared by several replicas relies on."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [integrant.core :as ig]
            [server.bulk-job-store :as bjs]
            [server.compartment :as compartment])
  (:import [java.time Instant]))

;; ---------------------------------------------------------------------------
;; MemoryJobStore through the public fns
;; ---------------------------------------------------------------------------

(deftest the-default-store-is-a-memory-job-store
  (is (instance? server.bulk_job_store.MemoryJobStore (:jobs (bjs/create-store))))
  (is (instance? server.bulk_job_store.MemoryJobStore
                 (:jobs (ig/init-key :fhir/bulk-job-store {:ttl-ms 5}))))
  (is (= 5 (:ttl-ms (bjs/config (ig/init-key :fhir/bulk-job-store {:ttl-ms 5}))))))

(deftest a-host-supplies-its-own-job-store
  (let [jobs (bjs/memory-job-store)]
    (is (identical? jobs (:jobs (bjs/create-store {} jobs))))
    (testing "through Integrant opts, kept out of the config"
      (let [store (ig/init-key :fhir/bulk-job-store {:jobs jobs :ttl-ms 7})]
        (is (identical? jobs (:jobs store)))
        (is (not (contains? (bjs/config store) :jobs)))
        (is (= 7 (:ttl-ms (bjs/config store))))))
    (testing "anything else is refused at construction"
      (is (thrown? clojure.lang.ExceptionInfo (bjs/create-store {} (atom {}))))
      (is (thrown? clojure.lang.ExceptionInfo (bjs/create-store {} nil)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (ig/init-key :fhir/bulk-job-store {:jobs {}}))))))

(deftest memory-store-semantics
  (let [store (bjs/create-store)]
    (bjs/put-job! store "t1" {:id "j" :tenant "t1" :status :complete})
    (bjs/put-job! store "t2" {:id "j" :tenant "t2" :status :complete})
    (testing "jobs are keyed by tenant and id"
      (is (= "t1" (:tenant (bjs/get-job store "t1" "j"))))
      (is (= "t2" (:tenant (bjs/get-job store "t2" "j"))))
      (is (nil? (bjs/get-job store "t3" "j"))))
    (testing "update-job! returns the new job"
      (is (= :error (:status (bjs/update-job! store "t1" "j" assoc :status :error))))
      (is (= :error (:status (bjs/get-job store "t1" "j")))))
    (testing "update-job! returning nil removes the job rather than storing nil"
      (is (nil? (bjs/update-job! store "t1" "j" (constantly nil))))
      (is (nil? (bjs/get-job store "t1" "j")))
      (is (= ["t2"] (map :tenant (bjs/all-jobs store))))
      (is (not-any? nil? (bjs/all-jobs store))))
    (testing "update-job! of a missing job that stays nil changes nothing"
      (is (nil? (bjs/update-job! store "t1" "missing" identity)))
      (is (= 1 (count (bjs/all-jobs store)))))
    (testing "remove-job! is idempotent"
      (is (nil? (bjs/remove-job! store "t2" "j")))
      (is (nil? (bjs/remove-job! store "t2" "j")))
      (is (empty? (bjs/all-jobs store))))))

;; ---------------------------------------------------------------------------
;; Codec
;; ---------------------------------------------------------------------------

(defn- kickoff-like-job
  "A job carrying every field start-export! writes, around `basis`."
  [basis]
  {:id "job-1"
   :tenant "default"
   :kind :group
   :group-id "g1"
   :params {"_type" "Patient,Observation"
            "_typeFilter" ["Observation?status=final" "Patient?active=true"]
            :_since "2026-01-01T00:00:00Z"}
   :basis basis
   :owner-ids #{"p1" "p2"}
   :compartment-definitions compartment/compartment-definitions
   :scan-mode :compartment
   :access :subject
   :patient-ids ["p1" "p2"]
   :owner {:sub "kratos-a" :client-id "portal"}
   :status :complete
   :transaction-time "2026-10-31T12:00:00.123456789Z"
   :request-url "https://h/default/fhir/Group/g1/$export"
   :created-at 1793000000000
   :finished-at 1793000000001
   :manifest "{\"transactionTime\":\"2026-10-31T12:00:00.123456789Z\"}"
   :output [{:type "Patient" :file-id "f1" :count 2}]
   :error [{:type "OperationOutcome" :file-id "f2" :diagnostics "Unknown type: Foo"}]
   :files {"f1" {:kind :output :type "Patient"}
           "f2" {:kind :error :type "OperationOutcome" :diagnostics "Unknown type: Foo"}}})

(def ^:private nano-basis
  {:tx-id 4711 :system-time (Instant/parse "2026-10-31T12:00:00.123456789Z")})

(deftest a-job-round-trips-through-edn
  (let [job (kickoff-like-job nano-basis)
        s   (bjs/job->edn job)]
    (is (string? s))
    (is (= job (bjs/edn->job s)))
    (testing "the basis instant keeps nanosecond precision and its type"
      (let [st (get-in (bjs/edn->job s) [:basis :system-time])]
        (is (instance? Instant st))
        (is (= 123456789 (.getNano ^Instant st)))))
    (testing "an optional valid-time and a nil tx-id round-trip too"
      (let [basis {:tx-id nil
                   :system-time (Instant/parse "2026-10-31T12:00:00Z")
                   :valid-time (Instant/parse "2026-10-30T00:00:00.5Z")}
            job   (kickoff-like-job basis)]
        (is (= job (bjs/edn->job (bjs/job->edn job))))))))

(deftest the-mock-stores-basis-round-trips
  (let [store (mock/create-mock-store {})
        _     (db/create-resource store "t" :Patient "p1" {:resourceType "Patient" :id "p1"})
        basis (db/current-basis store "t")
        job   (kickoff-like-job basis)]
    (is (= basis (:basis (bjs/edn->job (bjs/job->edn job)))))))

(deftest the-encoding-ignores-print-settings
  (binding [*print-length* 1
            *print-level*  1
            *print-meta*   true]
    (let [job (kickoff-like-job nano-basis)]
      (is (= job (bjs/edn->job (bjs/job->edn job)))))))

(deftest encoding-refuses-what-it-cannot-carry
  (let [job (kickoff-like-job nano-basis)]
    (doseq [[label bad] [["a function in the params" (assoc-in job [:params "x"] inc)]
                         ["a java object in the params" (assoc-in job [:params "x"] (Object.))]
                         ["a Date basis" (assoc-in job [:basis :system-time] (java.util.Date.))]
                         ["a string basis" (assoc-in job [:basis :system-time] "2026-10-31T12:00:00Z")]
                         ["an unknown basis key" (assoc-in job [:basis :db] :opaque)]
                         ["a non-integer tx-id" (assoc-in job [:basis :tx-id] "7")]
                         ["no basis" (dissoc job :basis)]
                         ["no tenant" (dissoc job :tenant)]
                         ["an unknown status" (assoc job :status :cancelled)]]]
      (testing label
        (is (thrown? clojure.lang.ExceptionInfo (bjs/job->edn bad)))))))

(deftest decoding-refuses-malformed-records
  (let [good (bjs/job->edn (kickoff-like-job nano-basis))]
    (doseq [[label bad] [["not a string" 42]
                         ["unreadable EDN" "{:id \"j\""]
                         ["an unknown tag" "#object[java.time.Instant 0 \"x\"]"]
                         ["not a map" "[1 2]"]
                         ["a missing id" (str/replace good ":id \"job-1\"" "")]
                         ["an unparseable instant"
                          (str/replace good "2026-10-31T12:00:00.123456789Z\"}" "yesterday\"}")]
                         ["a wrongly typed count" (str/replace good ":count 2" ":count \"2\"")]]]
      (testing label
        (is (not= good bad) "the fixture really altered the record")
        (is (thrown? clojure.lang.ExceptionInfo (bjs/edn->job bad)))))))
