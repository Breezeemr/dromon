(ns fhir-store.tx-meta-test
  "The three functions a store author touches when wiring `:fhir/tx-meta`:
   what counts as a valid stamp, how a store that cannot persist one refuses
   it, and how a caller asks whether it will be persisted at all.

   These belong here rather than in fhir-server because every out-of-repo
   implementation sees only this module."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.protocol :as db]))

(def ^:private stamp {:who "alice" :on-behalf-of "bob"})

(deftest a-missing-or-nil-value-is-simply-no-attribution
  (is (nil? (db/tx-meta nil)))
  (is (nil? (db/tx-meta {})))
  (is (nil? (db/tx-meta {:if-match "1"})))
  (is (nil? (db/tx-meta {db/tx-meta-key nil}))))

(deftest a-well-formed-map-is-returned-unchanged
  (is (= stamp (db/tx-meta {db/tx-meta-key stamp})))
  (testing "every scalar the contract allows"
    (let [m {:s "x" :k :x :u (java.util.UUID/randomUUID) :n 1 :b true
             :i (java.util.Date.)}]
      (is (= m (db/tx-meta {db/tx-meta-key m}))))))

(deftest a-malformed-value-throws-rather-than-being-coerced-or-dropped
  (testing "a non-map is a host bug"
    (doseq [v ["alice" 42 [:a] #{:a}]]
      (is (thrown? clojure.lang.ExceptionInfo (db/tx-meta {db/tx-meta-key v}))
          (str "should reject " (pr-str v)))))
  (testing "an EMPTY map is a decayed attribution, not an absent one: a host
            with nothing to say must inject no key at all and get an ordinary
            unattributed write, rather than persist a block naming nobody"
    (is (thrown? clojure.lang.ExceptionInfo (db/tx-meta {db/tx-meta-key {}}))))
  (testing "the shape is bounded, because the backend keeps history forever"
    (is (thrown? clojure.lang.ExceptionInfo
                 (db/tx-meta {db/tx-meta-key (zipmap (map #(keyword (str "k" %))
                                                          (range (inc db/tx-meta-max-keys)))
                                                     (repeat "v"))})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (db/tx-meta {db/tx-meta-key {"who" "alice"}}))
        "keys must be keywords")
    (is (thrown? clojure.lang.ExceptionInfo
                 (db/tx-meta {db/tx-meta-key {:who {:nested "alice"}}}))
        "no nested structure")
    (is (thrown? clojure.lang.ExceptionInfo
                 (db/tx-meta {db/tx-meta-key {:who ["alice"]}}))
        "no collections")
    (is (thrown? clojure.lang.ExceptionInfo
                 (db/tx-meta {db/tx-meta-key
                              {:who (apply str (repeat (inc db/tx-meta-max-value-length) "x"))}}))
        "no free text")))

(deftest reject-tx-meta-is-silent-until-there-is-something-to-lose
  (is (nil? (db/reject-tx-meta! "s" nil)))
  (is (nil? (db/reject-tx-meta! "s" {:if-match "1"})))
  (is (nil? (db/reject-tx-meta! "s" {db/tx-meta-key nil})))
  (testing "and refuses the write rather than accepting the key and dropping it"
    (let [e (try (db/reject-tx-meta! "s" {db/tx-meta-key stamp})
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= 500 (:fhir/status (ex-data e))))
      (is (= "s" (:store (ex-data e)))))))

(defrecord NoCapabilityStore []
  db/IFHIRStore
  (create-resource [_ _ _ _ _] nil))

(defrecord AnsweringStore [answer]
  db/IFHIRStore
  (create-resource [_ _ _ _ _] nil)
  db/ITxMetadataStore
  (tx-metadata-supported? [_ tenant-id]
    (if (fn? answer) (answer tenant-id) answer)))

(deftest a-store-that-cannot-persist-and-one-that-says-so-are-the-same-answer
  (is (not (db/tx-metadata-store? (->NoCapabilityStore) "t")))
  (is (not (db/tx-metadata-store? (->AnsweringStore false) "t")))
  (is (db/tx-metadata-store? (->AnsweringStore true) "t"))
  (testing "and the answer is per tenant, not per store"
    (let [st (->AnsweringStore #(= "yes" %))]
      (is (db/tx-metadata-store? st "yes"))
      (is (not (db/tx-metadata-store? st "no"))))))

(deftest a-check-that-throws-is-not-an-answer-and-keeps-its-cause
  (testing "reporting a broken check as \"cannot persist\" would refuse a
            clinical write with a message blaming the tenant's schema, and
            the real cause would exist nowhere"
    (let [boom (RuntimeException. "connection reset")
          st   (->AnsweringStore (fn [_] (throw boom)))
          e    (try (db/tx-metadata-store? st "t")
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (instance? clojure.lang.ExceptionInfo e))
      (is (= "tx-metadata capability check failed" (ex-message e)))
      (is (identical? boom (ex-cause e)) "the original exception is kept")
      (is (= "t" (:tenant-id (ex-data e)))))))
