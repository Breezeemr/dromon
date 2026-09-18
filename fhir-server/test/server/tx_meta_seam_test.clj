(ns server.tx-meta-seam-test
  "dromon's half of write attribution: the CHANNEL, not the attribution.

   What a stamp should say is the host's business -- a practitioner, a user
   id, an impersonation target -- so these tests use a deliberately
   meaningless map. What is proven here is the contract dromon owes a host:

   - no injected map means the arities called before this seam existed are
     still the ones called, so a standalone dromon and every partial store
     are unchanged;
   - the map reaches the store UNCHANGED at every write call site, the
     upsert-create inside the update handler and the batch fan-out included;
   - a store that cannot persist refuses the write instead of writing it
     bare, at boot and per request."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.compartment :as compartment]
            [server.handlers :as handlers]
            [server.middleware :as middleware]
            [server.tx-meta :as tx-meta]
            [fhir-store.tx-meta-contract :as tx-contract]))

(def ^:private tenant "default")

(defn- run
  "Invoke a handler through the same exception middleware the router wraps it
   in, so a refusal arrives as the OperationOutcome a client would see rather
   than as an escaping exception."
  [handler req]
  ((middleware/wrap-fhir-exceptions handler) req))

(def ^:private stamp
  "An opaque host map. dromon must not read inside it, so its keys are
   deliberately not the ones a real host would use. Flat scalars, because
   `db/tx-meta` bounds the SHAPE even though it never reads the content."
  {:who "alice" :on-behalf-of "bob" :via :test})

(def ^:private search-registry
  {"identifier" {:type "token" :columns [{:col "identifier" :array? true
                                          :sub-col "value"}]}})

(defn- store [] (mock/create-mock-store {}))

(defn- req
  [st & {:keys [id body params form-params headers meta?]
         :or   {params {} headers {}}}]
  (cond-> {:fhir/store           st
           :fhir/resource-type   "Patient"
           :fhir/search-registry search-registry
           :path-params          {:tenant-id tenant}
           :query-params         params
           :headers              headers}
    meta?       (assoc :fhir/tx-meta stamp)
    id          (assoc-in [:path-params :id] id)
    body        (assoc-in [:parameters :body] body)
    form-params (assoc :form-params form-params)))

(defn- patient
  ([] {:resourceType "Patient" :name [{:family "Test"}]})
  ([ident] (assoc (patient) :identifier [{:value ident}])))

(defn- recorded
  "The metadata the mock recorded for the current version of a Patient."
  [st id]
  (mock/tx-meta-of st tenant :Patient id))

(defn- create! [st & {:keys [meta? body] :or {body (patient)}}]
  (handlers/create-resource (req st :body body :meta? meta?)))

(defn- post-entries [n]
  (mapv (fn [_] {:request {:method "POST" :url "Patient"} :resource (patient)})
        (range n)))

(defn- bundle! [st type entries & {:keys [meta?]}]
  (run (handlers/transaction {})
       (cond-> {:fhir/store  st
                :path-params {:tenant-id tenant}
                :body-params {:resourceType "Bundle"
                              :type type
                              :entry entries}}
         meta? (assoc :fhir/tx-meta stamp))))

;; ---------------------------------------------------------------------------
;; Nothing injected: the seam is invisible
;; ---------------------------------------------------------------------------

(defn- no-opts-store
  "A store implementing ONLY the arities that existed before this seam.

   This is the shape of every out-of-repo store and of dromon's own partial
   test fakes. Reaching an opts arity on it throws AbstractMethodError, so
   the test fails loudly if a handler ever calls the new arity for a request
   that carries no attribution."
  [calls]
  (reify db/IFHIRStore
    (create-resource [_ _ _ id resource]
      (swap! calls conj :create)
      (assoc resource :id id :meta {:versionId "1"}))
    (read-resource [_ _ _ _] nil)
    (update-resource [_ _ _ id resource]
      (swap! calls conj :update)
      (assoc resource :id id :meta {:versionId "2"}))
    (delete-resource [_ _ _ _] (swap! calls conj :delete) true)
    (transact-transaction [_ _ _]
      (swap! calls conj :transaction)
      {:resourceType "Bundle" :type "transaction-response" :entry []})
    (transact-bundle [_ _ _]
      (swap! calls conj :batch)
      {:resourceType "Bundle" :type "batch-response" :entry []})))

(deftest without-an-injected-map-only-the-original-arities-are-called
  (testing "a standalone dromon, and any store that never grew the new
            arities, is untouched until a host switches attribution on"
    (let [calls (atom [])
          st    (no-opts-store calls)]
      (is (= 201 (:status (handlers/create-resource (req st :body (patient))))))
      (is (= 204 (:status (handlers/delete-resource (req st :id "p1")))))
      (is (= 200 (:status (bundle! st "transaction" []))))
      (is (= 200 (:status (bundle! st "batch" []))))
      (is (= [:create :delete :transaction :batch] @calls)))))

(deftest without-an-injected-map-nothing-is-recorded
  (let [st   (store)
        id   (get-in (create! st) [:body :id])]
    (is (nil? (recorded st id)))))

(deftest write-opts-is-nil-when-nothing-is-injected
  (is (nil? (tx-meta/write-opts (req (store))))))

;; ---------------------------------------------------------------------------
;; The map reaches the store, at every call site
;; ---------------------------------------------------------------------------

(deftest the-map-reaches-the-store-unchanged-on-create
  (let [st (store)
        id (get-in (create! st :meta? true) [:body :id])]
    (is (= stamp (recorded st id)))))

(deftest the-map-reaches-the-store-on-conditional-create
  (testing "If-None-Exist zero-match branch shares do-create with the plain POST"
    (let [st   (store)
          resp (handlers/create-resource
                (assoc (req st :body (patient "abc") :meta? true)
                       :headers {"if-none-exist" "identifier=abc"}))]
      (is (= 201 (:status resp)))
      (is (= stamp (recorded st (get-in resp [:body :id])))))))

(deftest the-map-reaches-the-store-on-update-and-on-the-upsert-create
  (let [st (store)]
    (testing "PUT to a nonexistent id CREATES -- the create reached from the
              update handler, the one easiest to miss"
      (let [resp (handlers/update-resource
                  (req st :id "u1" :body (patient) :meta? true))]
        (is (= 201 (:status resp)))
        (is (= stamp (recorded st "u1")))))
    (testing "and the plain update branch, over a version that is NOT stamped,
              so the assertion cannot pass on the create's stamp"
      (handlers/update-resource (req st :id "u1" :body (assoc (patient) :active true)))
      (is (nil? (recorded st "u1")) "the unattributed write reads back unattributed")
      (let [resp (handlers/update-resource
                  (req st :id "u1" :body (assoc (patient) :gender "other") :meta? true))]
        (is (= 200 (:status resp)))
        (is (= stamp (recorded st "u1")))))))

(deftest the-map-joins-if-match-rather-than-replacing-it
  (let [st (store)
        _  (handlers/update-resource (req st :id "g1" :body (patient)))
        resp (run handlers/update-resource
                  (assoc (req st :id "g1" :body (assoc (patient) :active true) :meta? true)
                         :headers {"if-match" "W/\"1\""}))]
    (is (= 200 (:status resp)) "the precondition still holds")
    (is (= stamp (recorded st "g1")))
    (testing "and a stale precondition still refuses the write"
      (let [bad (run handlers/update-resource
                     (assoc (req st :id "g1" :body (patient) :meta? true)
                            :headers {"if-match" "W/\"1\""}))]
        (is (= 412 (:status bad)))))))

(deftest the-map-reaches-the-store-on-patch-both-branches
  (let [st (store)
        _  (handlers/update-resource (req st :id "p1" :body (patient)))
        r1 (handlers/patch-resource
            (req st :id "p1" :body [{:op "add" :path "/active" :value true}] :meta? true))
        _  (is (= 200 (:status r1)))
        _  (is (= stamp (recorded st "p1")))
        ;; A resource whose current version carries NO stamp, so the guarded
        ;; branch proves itself rather than reading the plain branch's stamp.
        _  (handlers/update-resource (req st :id "p2" :body (patient)))
        r2 (handlers/patch-resource
            (assoc (req st :id "p2" :body [{:op "add" :path "/active" :value true}]
                        :meta? true)
                   :headers {"if-match" "W/\"1\""}))]
    (is (= 200 (:status r2)) "guarded patch")
    (is (= stamp (recorded st "p2")))))

(deftest the-map-reaches-the-store-on-delete-both-branches
  (let [st (store)]
    (handlers/update-resource (req st :id "d1" :body (patient)))
    (handlers/delete-resource (req st :id "d1" :meta? true))
    (is (= stamp (recorded st "d1")))
    (handlers/update-resource (req st :id "d2" :body (patient)))
    (handlers/delete-resource
     (assoc (req st :id "d2" :meta? true) :headers {"if-match" "W/\"1\""}))
    (is (= stamp (recorded st "d2")))))

(deftest the-map-reaches-the-store-on-the-conditional-interactions
  (let [st (store)]
    (testing "conditional update, zero-match branch: creates"
      (let [resp (handlers/conditional-update
                  (req st :body (patient "cu") :params {"identifier" "cu"} :meta? true))]
        (is (= 201 (:status resp)))
        (is (= stamp (recorded st (get-in resp [:body :id]))))))
    (testing "conditional update, one-match branch: updates"
      ;; An unattributed write first, so each branch below is asserted against
      ;; a version that was NOT stamped by the branch before it.
      (handlers/conditional-update
       (req st :body (assoc (patient "cu") :gender "other") :params {"identifier" "cu"}))
      (let [resp (handlers/conditional-update
                  (req st :body (assoc (patient "cu") :active true)
                       :params {"identifier" "cu"} :meta? true))]
        (is (= 200 (:status resp)))
        (is (= stamp (recorded st (get-in resp [:body :id]))))))
    (testing "conditional patch"
      (handlers/conditional-update
       (req st :body (patient "cu") :params {"identifier" "cu"}))
      (let [resp (handlers/conditional-patch
                  (req st :body [{:op "add" :path "/gender" :value "other"}]
                       :params {"identifier" "cu"} :meta? true))]
        (is (= 200 (:status resp)))
        (is (= stamp (recorded st (get-in resp [:body :id]))))))
    (testing "conditional delete"
      ;; The patch that finds the id is itself unattributed, so the delete is
      ;; the only write that could have written the stamp read back below.
      (let [before (get-in (handlers/conditional-patch
                            (req st :body [] :params {"identifier" "cu"}))
                           [:body :id])
            resp   (handlers/conditional-delete
                    (req st :params {"identifier" "cu"} :meta? true))]
        (is (= 204 (:status resp)))
        (is (= stamp (recorded st before)))))))

;; ---------------------------------------------------------------------------
;; Bundles: per bundle, and the batch fan-out is where it goes missing
;; ---------------------------------------------------------------------------

(deftest a-transaction-bundle-stamps-every-write-it-opens
  (let [st   (store)
        resp (bundle! st "transaction" (post-entries 3) :meta? true)
        ids  (mapv #(get-in % [:resource :id]) (get-in resp [:body :entry]))]
    (is (= 200 (:status resp)))
    (is (= 3 (count ids)))
    (is (every? #(= stamp (recorded st %)) ids))))

(deftest a-batch-of-n-entries-produces-n-stamped-transactions
  (testing "PINNED ASYMMETRY: batch fans out into the store's OWN single-
            resource verbs, so a store that threads the metadata on the
            transaction path and not here loses attribution on exactly half
            its writes -- and nothing else in the suite would notice."
    (let [st   (store)
          n    4
          resp (bundle! st "batch" (post-entries n) :meta? true)
          ids  (mapv #(get-in % [:resource :id]) (get-in resp [:body :entry]))]
      (is (= 200 (:status resp)))
      (is (= n (count ids)))
      (is (= n (count (filter #(= stamp (recorded st %)) ids)))))))

(deftest a-bundle-with-no-map-stamps-nothing
  (let [st   (store)
        resp (bundle! st "batch" (post-entries 2))
        ids  (mapv #(get-in % [:resource :id]) (get-in resp [:body :entry]))]
    (is (every? #(nil? (recorded st %)) ids))))

(deftest every-version-keeps-the-metadata-it-was-written-with
  (testing "a later unattributed write must not rewrite an earlier stamp"
    (let [st (store)]
      (handlers/update-resource (req st :id "v1" :body (patient) :meta? true))
      (handlers/update-resource (req st :id "v1" :body (assoc (patient) :active true)))
      (is (= {"1" stamp} (mock/tx-meta-history st tenant :Patient "v1"))))))

;; ---------------------------------------------------------------------------
;; Detectability
;; ---------------------------------------------------------------------------

(defn- unsupported-store []
  (mock/create-mock-store {:tx-metadata-supported? false}))

(deftest a-store-that-cannot-persist-refuses-the-write
  (testing "FAIL CLOSED, inverting the narrative seam: an audit record cannot
            be reconstructed afterwards, so the write is refused rather than
            written bare"
    (let [st   (unsupported-store)
          resp (run handlers/create-resource (req st :body (patient) :meta? true))]
      (is (= 500 (:status resp)))
      (is (= "OperationOutcome" (get-in resp [:body :resourceType])))
      (is (empty? (get-in @(:state st) [tenant :Patient])) "nothing was written"))))

(deftest a-store-that-cannot-persist-still-takes-unattributed-writes
  (let [st (unsupported-store)]
    (is (= 201 (:status (create! st))))))

(deftest a-store-that-does-not-satisfy-the-protocol-at-all-refuses-too
  (let [calls (atom [])
        st    (no-opts-store calls)
        resp  (run handlers/create-resource (req st :body (patient) :meta? true))]
    (is (= 500 (:status resp)))
    (is (empty? @calls) "refused before the store was touched")))

(deftest an-empty-injected-map-is-a-host-bug-and-throws
  (testing "an attribution that collapsed to {} names nobody. A host with
            nothing to say must inject no key at all and get an ordinary
            unattributed write, rather than persist an empty attribution or
            refuse the write against a store that cannot take one"
    (let [st   (store)
          resp (run handlers/create-resource
                    (assoc (req st :body (patient)) :fhir/tx-meta {}))]
      (is (= 500 (:status resp)))
      (is (empty? (get-in @(:state st) [tenant :Patient]))))
    (testing "and it reads as the host bug it is, whether or not the store
              could have persisted a real map"
      (let [resp (run handlers/create-resource
                      (assoc (req (unsupported-store) :body (patient)) :fhir/tx-meta {}))]
        (is (re-find #"malformed"
                     (str (get-in resp [:body :issue 0 :diagnostics]))))))))

(deftest a-request-that-writes-nothing-is-not-refused-by-the-capability-check
  (testing "the check exists to stop an attributed WRITE landing unattributed.
            A request that performs no write could not have lost an audit
            record, so it must answer for itself rather than 500 -- which is
            why every handler binds the check lazily"
    (let [st (unsupported-store)]
      (testing "conditional delete matching nothing"
        (is (= 204 (:status (run handlers/conditional-delete
                                 (req st :params {"identifier" "nope"} :meta? true))))))
      (testing "conditional patch matching nothing"
        (is (= 404 (:status (run handlers/conditional-patch
                                 (req st :body [{:op "add" :path "/active" :value true}]
                                      :params {"identifier" "nope"} :meta? true))))))
      (testing "PATCH of a resource that does not exist"
        (is (= 404 (:status (run handlers/patch-resource
                                 (req st :id "absent"
                                      :body [{:op "add" :path "/active" :value true}]
                                      :meta? true))))))
      (testing "a body that is not a Bundle at all"
        (is (= 400 (:status (run (handlers/transaction {})
                                 {:fhir/store st
                                  :path-params {:tenant-id tenant}
                                  :fhir/tx-meta stamp
                                  :body-params {:resourceType "Patient"}}))))))))

(deftest a-non-map-injected-value-is-a-host-bug-and-throws
  (testing "never coerced, never dropped: a stamp that decayed into no stamp
            is the failure the channel exists to prevent"
    (let [st   (store)
          resp (run handlers/create-resource
                    (assoc (req st :body (patient)) :fhir/tx-meta "alice"))]
      (is (= 500 (:status resp)))
      (is (empty? (get-in @(:state st) [tenant :Patient]))))))

(deftest assert-supported-throws-at-boot-for-a-store-that-cannot
  (is (nil? (tx-meta/assert-supported! (store) [tenant "other"])))
  (is (thrown? clojure.lang.ExceptionInfo
               (tx-meta/assert-supported! (unsupported-store) [tenant]))))

(deftest a-per-tenant-answer-is-honoured
  (let [st (mock/create-mock-store {:tx-metadata-supported? #(= % "yes")})]
    (is (db/tx-metadata-store? st "yes"))
    (is (not (db/tx-metadata-store? st "no")))))

;; ---------------------------------------------------------------------------
;; The in-repo delegating wrapper
;; ---------------------------------------------------------------------------

(deftest the-compartment-wrapper-delegates-the-question-and-forwards-the-map
  (testing "CompartmentFilteringStore REPLACES :fhir/store per request, so a
            wrapper that stayed silent about the capability would refuse every
            attributed write under a patient token"
    (let [base    (store)
          wrapped (compartment/filtering-store base {:patient-id "pat-1"
                                                     :all-registries {}})]
      (is (db/tx-metadata-store? wrapped tenant))
      (db/create-resource wrapped tenant :Patient "pat-1"
                          {:resourceType "Patient" :id "pat-1"}
                          {:fhir/tx-meta stamp})
      (is (= stamp (recorded base "pat-1"))))))

(deftest the-compartment-wrapper-reports-a-base-that-cannot
  (let [wrapped (compartment/filtering-store (unsupported-store)
                                             {:patient-id "pat-1" :all-registries {}})]
    (is (not (db/tx-metadata-store? wrapped tenant)))))

(deftest the-compartment-wrapper-satisfies-the-contract-on-every-write-verb
  (testing "the one delegating wrapper dromon ships, run through the same
            conformance suite the parent repo's two wrappers must answer.
            Rule 3 -- an opts arity that calls the delegate's no-opts arity --
            is invisible from outside unless every verb is driven"
    (let [base    (store)
          wrapped (compartment/filtering-store base {:patient-id "pat-1"
                                                     :all-registries {}})]
      (tx-contract/check-tx-metadata-contract
       {:store     wrapped
        :tenant-id tenant
        :id        "pat-1"
        :recorded  (fn [_ tid rt rid] (mock/tx-meta-of base tid rt rid))}))))
