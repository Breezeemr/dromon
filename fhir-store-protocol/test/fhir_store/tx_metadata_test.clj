(ns fhir-store.tx-metadata-test
  "The tx-metadata seam, and above all what it does NOT break.

  The protocol lives here; its implementations live in another repository,
  which advances to this commit only later. So the headline property is not
  that the new arities work -- it is that an implementation written before
  they existed, and never touched since, still loads, still satisfies
  `IFHIRStore`, and still serves every call it served yesterday. `legacy-store`
  below is that implementation, standing in for the reify store fakes and the
  partial implementations that are already normal in production."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [fhir-store.protocol :as db]))

(def ^:private stamp
  "A well-formed two-axis stamp: a service principal, and the code that ran."
  {:principal {:credential "token" :altId "rcm-eligibility-client"}
   :device    {:name "purser"
               :versions {"purser" "a1b2c3d" "rcm-x12" "9f8e7d6"}}})

;; ---------------------------------------------------------------------------
;; The implementation that knows nothing about tx-metadata
;; ---------------------------------------------------------------------------

(defn- legacy-store
  "An IFHIRStore written against the protocol as it stood BEFORE the seam:
   the five write verbs in their pre-seam arities only, recording each call
   into `calls`. Nothing here mentions tx-metadata, and nothing here was
   changed to accommodate it -- which is the point."
  [calls]
  (reify db/IFHIRStore
    (read-resource [_ tenant-id resource-type id]
      (swap! calls conj [:read tenant-id resource-type id])
      {:resourceType (name resource-type) :id id})

    (create-resource [_ tenant-id resource-type id resource]
      (swap! calls conj [:create tenant-id resource-type id])
      resource)

    (update-resource [this tenant-id resource-type id resource]
      (db/update-resource this tenant-id resource-type id resource nil))
    (update-resource [_ tenant-id resource-type id resource opts]
      (swap! calls conj [:update tenant-id resource-type id (:if-match opts)])
      resource)

    (delete-resource [this tenant-id resource-type id]
      (db/delete-resource this tenant-id resource-type id nil))
    (delete-resource [_ tenant-id resource-type id opts]
      (swap! calls conj [:delete tenant-id resource-type id (:if-match opts)])
      {})

    (transact-transaction [_ _tenant-id entries]
      (swap! calls conj [:transaction (count entries)])
      {:resourceType "Bundle" :type "transaction-response"})

    (transact-bundle [_ _tenant-id entries]
      (swap! calls conj [:batch (count entries)])
      {:resourceType "Bundle" :type "batch-response"})))

(deftest a-store-that-predates-the-seam-still-works
  (let [calls (atom [])
        store (legacy-store calls)]

    (testing "it is still an IFHIRStore, and says it keeps no stamps"
      (is (satisfies? db/IFHIRStore store))
      (is (not (satisfies? db/ITxMetadataStore store)))
      (is (false? (db/supports-tx-metadata? store))
          "the capability check answers for a store that never heard of it"))

    (testing "every pre-seam arity still serves"
      (is (= {:resourceType "Patient" :id "p1"}
             (db/read-resource store "t" :Patient "p1")))
      (is (= {:id "p1"} (db/create-resource store "t" :Patient "p1" {:id "p1"})))
      (is (= {:id "p1"} (db/update-resource store "t" :Patient "p1" {:id "p1"})))
      (is (= {:id "p1"} (db/update-resource store "t" :Patient "p1" {:id "p1"}
                                            {:if-match "3"})))
      (is (= {} (db/delete-resource store "t" :Patient "p1")))
      (is (= {} (db/delete-resource store "t" :Patient "p1" {:if-match "3"})))
      (is (= "transaction-response"
             (:type (db/transact-transaction store "t" [:a :b]))))
      (is (= "batch-response" (:type (db/transact-bundle store "t" [:a]))))
      (is (= [[:read "t" :Patient "p1"]
              [:create "t" :Patient "p1"]
              [:update "t" :Patient "p1" nil]
              [:update "t" :Patient "p1" "3"]
              [:delete "t" :Patient "p1" nil]
              [:delete "t" :Patient "p1" "3"]
              [:transaction 2]
              [:batch 1]]
             @calls)
          "including the plain arities that delegate to their own opts arity"))

    (testing "only INVOKING a new arity on it fails, which is why callers ask first"
      ;; This is the entire cost of the change, and it is inert until some
      ;; caller passes opts to a store that never adopted them. Nothing in
      ;; either repository does after this step. `supports-tx-metadata?` above
      ;; is how a caller finds out without provoking this.
      (is (thrown? AbstractMethodError
                   (db/create-resource store "t" :Patient "p1" {:id "p1"}
                                       {:tx-metadata stamp})))
      (is (thrown? AbstractMethodError
                   (db/transact-transaction store "t" [:a] {:tx-metadata stamp})))
      (is (thrown? AbstractMethodError
                   (db/transact-bundle store "t" [:a] {:tx-metadata stamp}))))

    (testing "update and delete already had opts, so even that cost is zero there"
      (is (= {:id "p1"} (db/update-resource store "t" :Patient "p1" {:id "p1"}
                                            {:tx-metadata stamp})))
      (is (= {} (db/delete-resource store "t" :Patient "p1"
                                    {:tx-metadata stamp}))))))

;; ---------------------------------------------------------------------------
;; The implementation that adopted it
;; ---------------------------------------------------------------------------

(defn- adopting-store
  "A store that keeps stamps, written the way the protocol says to: one body
   per verb on the opts arity, the plain arity delegating with nil, and
   ITxMetadataStore implemented because the stamp is actually kept."
  [stamps]
  (reify
    db/IFHIRStore
    (create-resource [this tenant-id resource-type id resource]
      (db/create-resource this tenant-id resource-type id resource nil))
    (create-resource [_ _tenant-id resource-type id resource opts]
      (swap! stamps assoc [resource-type id]
             (db/check-tx-metadata (:tx-metadata opts)))
      resource)

    (transact-transaction [this tenant-id entries]
      (db/transact-transaction this tenant-id entries nil))
    (transact-transaction [_ _tenant-id entries opts]
      (swap! stamps assoc :transaction (db/check-tx-metadata (:tx-metadata opts)))
      {:resourceType "Bundle" :type "transaction-response" :count (count entries)})

    (transact-bundle [this tenant-id entries]
      (db/transact-bundle this tenant-id entries nil))
    (transact-bundle [_ _tenant-id entries opts]
      (swap! stamps assoc :batch (db/check-tx-metadata (:tx-metadata opts)))
      {:resourceType "Bundle" :type "batch-response" :count (count entries)})

    db/ITxMetadataStore
    (tx-metadata-supported? [_] true)
    (tx-metadata-of [_ _tenant-id resource-type id _vid]
      (get @stamps [resource-type id]))))

(deftest a-store-that-adopted-the-seam-keeps-the-stamp
  (let [stamps (atom {})
        store  (adopting-store stamps)]

    (testing "the capability is discoverable before any call is made"
      (is (satisfies? db/ITxMetadataStore store))
      (is (true? (db/supports-tx-metadata? store))))

    (testing "the opts arity carries the stamp through to the version"
      (db/create-resource store "t" :Patient "p1" {:id "p1"} {:tx-metadata stamp})
      (is (= stamp (db/tx-metadata-of store "t" :Patient "p1" "1"))))

    (testing "the plain arity IS the opts arity with nil"
      (db/create-resource store "t" :Patient "p2" {:id "p2"})
      (is (nil? (db/tx-metadata-of store "t" :Patient "p2" "1"))))

    (testing "a version that was never written carries nothing"
      (is (nil? (db/tx-metadata-of store "t" :Patient "nope" "1"))))

    (testing "both Bundle verbs take a stamp on their new arity"
      (db/transact-transaction store "t" [:a :b] {:tx-metadata stamp})
      (db/transact-bundle store "t" [:a] {:tx-metadata stamp})
      (is (= stamp (:transaction @stamps)))
      (is (= stamp (:batch @stamps))))))

;; ---------------------------------------------------------------------------
;; The decorator between them
;; ---------------------------------------------------------------------------

(defn- forwarding-decorator
  "A decorator following the documented forwarding rule: forward the opts
   arity IFF opts is non-nil. It deliberately does NOT implement
   ITxMetadataStore, because it does not know what its base keeps -- which is
   exactly the honest answer here."
  [base]
  (reify db/IFHIRStore
    (create-resource [this tenant-id resource-type id resource]
      (db/create-resource this tenant-id resource-type id resource nil))
    (create-resource [_ tenant-id resource-type id resource opts]
      (if opts
        (db/create-resource base tenant-id resource-type id resource opts)
        (db/create-resource base tenant-id resource-type id resource)))

    (transact-transaction [this tenant-id entries]
      (db/transact-transaction this tenant-id entries nil))
    (transact-transaction [_ tenant-id entries opts]
      (if opts
        (db/transact-transaction base tenant-id entries opts)
        (db/transact-transaction base tenant-id entries)))))

(deftest the-forwarding-rule-is-what-protects-an-unadopted-base
  (let [calls     (atom [])
        base      (legacy-store calls)
        decorated (forwarding-decorator base)]

    (testing "a plain write through the decorator never reaches the new arity"
      (is (= {:id "p1"} (db/create-resource decorated "t" :Patient "p1" {:id "p1"})))
      (is (= "transaction-response"
             (:type (db/transact-transaction decorated "t" [:a]))))
      (is (= [[:create "t" :Patient "p1"] [:transaction 1]] @calls))
      (is (false? (db/supports-tx-metadata? decorated))
          "and the decorator does not claim a capability it cannot vouch for"))

    (testing "a stamped write through it is what fails, so the guard belongs to the caller"
      ;; Pinning where the responsibility sits. The decorator forwards
      ;; faithfully; it is the CALLER that must not pass a stamp to a store
      ;; whose `supports-tx-metadata?` is false.
      (is (thrown? AbstractMethodError
                   (db/create-resource decorated "t" :Patient "p1" {:id "p1"}
                                       {:tx-metadata stamp}))))

    (testing "over an adopting base the same decorator carries the stamp through"
      (let [stamps (atom {})
            over   (forwarding-decorator (adopting-store stamps))]
        (db/create-resource over "t" :Patient "p9" {:id "p9"} {:tx-metadata stamp})
        (is (= stamp (get @stamps [:Patient "p9"])))
        (db/create-resource over "t" :Patient "p8" {:id "p8"})
        (is (nil? (get @stamps [:Patient "p8"])))))))

;; ---------------------------------------------------------------------------
;; The shared validation rule
;; ---------------------------------------------------------------------------

(deftest check-tx-metadata-normalizes-nothing-to-nil
  (is (nil? (db/check-tx-metadata nil)))
  (is (nil? (db/check-tx-metadata {})))
  (is (nil? (db/check-tx-metadata {:principal nil :device nil}))
      "a host whose deployment supplies neither axis writes no stamp row"))

(deftest check-tx-metadata-passes-a-well-formed-map-through-untouched
  (is (identical? stamp (db/check-tx-metadata stamp))
      "the map itself comes back: nothing is renamed, dropped or reordered")
  (testing "either axis alone is well-formed"
    (is (= {:principal {:credential "session" :altId "jdoe"}}
           (db/check-tx-metadata {:principal {:credential "session" :altId "jdoe"}})))
    (is (= {:device {:name "flotilla"}}
           (db/check-tx-metadata {:device {:name "flotilla"}}))
        ":versions is optional -- a deployment that knows no sha stamps a smaller map"))
  (testing "the map is open: undocumented keys survive"
    (let [m {:device {:name "purser"} :breeze/correlation-id "abc"}]
      (is (= m (db/check-tx-metadata m))))))

(deftest check-tx-metadata-refuses-a-shape-no-producer-should-build
  (let [problem (fn [v]
                  (try (db/check-tx-metadata v) ::no-throw
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))]
    (testing "a 500, because the host assembles the stamp, not the client"
      (is (= 500 (:fhir/status (problem "purser"))))
      (is (= "exception" (:fhir/code (problem "purser")))))
    (is (= :not-a-map (:tx-metadata/problem (problem "purser"))))
    (is (= :principal-not-a-map
           (:tx-metadata/problem (problem {:principal "jdoe"}))))
    (is (= :device-not-a-map
           (:tx-metadata/problem (problem {:device "purser"}))))
    (is (= :device-name-missing
           (:tx-metadata/problem (problem {:device {:versions {"purser" "a1"}}})))
        "an unnamed device names nothing")
    (is (= :device-name-missing
           (:tx-metadata/problem (problem {:device {:name "  "}}))))
    (is (= :device-versions-malformed
           (:tx-metadata/problem
            (problem {:device {:name "purser" :versions {"purser" 3}}})))
        "a version is a string; a number here would be a sha that got parsed")
    (testing "the message names the rule and never echoes the value"
      ;; The stamp can carry usernames and practitioner ids, and an exception
      ;; message reaches a log sink. Same rule as fhir-store.trace.
      (let [msg (try (db/check-tx-metadata {:principal "jane.doe@example.com"})
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (not (str/includes? msg "jane.doe")))))))

;; ---------------------------------------------------------------------------
;; Both halves of supports-tx-metadata?
;; ---------------------------------------------------------------------------

(defn- honest-decorator
  "A decorator that HAS the capability protocol and answers false through it,
   because the base it wraps keeps nothing.

   This is the store the capability check exists for, and until it was written
   nothing exercised the second half: every other fake here agrees between
   `satisfies?` and `tx-metadata-supported?`, so reducing `supports-tx-metadata?`
   to `satisfies?` alone passed the whole suite. dromon's own
   CompartmentFilteringStore is the real instance of this shape."
  [base]
  (reify
    db/IFHIRStore
    (create-resource [this tenant-id resource-type id resource]
      (db/create-resource this tenant-id resource-type id resource nil))
    (create-resource [_ tenant-id resource-type id resource opts]
      (if opts
        (db/create-resource base tenant-id resource-type id resource opts)
        (db/create-resource base tenant-id resource-type id resource)))

    db/ITxMetadataStore
    (tx-metadata-supported? [_] (db/supports-tx-metadata? base))))

(deftest supports-tx-metadata-needs-both-halves
  (testing "a decorator over a keeping base reports true"
    (let [calls (atom [])]
      (is (true? (db/supports-tx-metadata? (honest-decorator (adopting-store calls)))))))

  (testing "the SAME decorator over a base that keeps nothing reports false"
    ;; satisfies? is true here -- the decorator implements ITxMetadataStore --
    ;; so only the second half distinguishes this from the case above. A caller
    ;; that checked satisfies? alone would stamp a write nothing records.
    (let [calls     (atom [])
          decorated (honest-decorator (legacy-store calls))]
      (is (true? (satisfies? db/ITxMetadataStore decorated))
          "the capability protocol is present")
      (is (false? (db/supports-tx-metadata? decorated))
          "and the answer through it is still no"))))
