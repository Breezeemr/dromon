(ns server.tx-metadata-threading-test
  "The router's `:tx-metadata` producer reaches every write a handler makes,
   as `:tx-metadata` in the verb's opts, when the store keeps a stamp; with no
   producer, or a store that keeps none, the handlers call the plain arity as
   before."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.handlers :as handlers]
            [server.middleware :as middleware]
            [server.router :as router]))

(def ^:private tenant "default")

(def ^:private stamp {:principal {:credential "anonymous"}})

(defn- recording-store
  "The mock store, recording the opts each write verb receives (`:plain` for
   the plain arity), and claiming to keep a stamp when `keeps?`."
  [seen keeps?]
  (let [base (mock/create-mock-store {})
        note (fn [verb opts] (swap! seen conj [verb opts]))]
    (reify
      db/IFHIRStore
      (create-resource [_ t rt id r] (note :create :plain) (db/create-resource base t rt id r))
      (create-resource [_ t rt id r o] (note :create o) (db/create-resource base t rt id r o))
      (read-resource [_ t rt id] (db/read-resource base t rt id))
      (update-resource [_ t rt id r] (note :update :plain) (db/update-resource base t rt id r))
      (update-resource [_ t rt id r o] (note :update o) (db/update-resource base t rt id r o))
      (delete-resource [_ t rt id] (note :delete :plain) (db/delete-resource base t rt id))
      (delete-resource [_ t rt id o] (note :delete o) (db/delete-resource base t rt id o))
      (search [_ t rt p r] (db/search base t rt p r))
      (transact-transaction [_ t es] (note :transaction :plain) (db/transact-transaction base t es))
      (transact-transaction [_ t es o] (note :transaction o) (db/transact-transaction base t es o))
      (transact-bundle [_ t es] (note :batch :plain) (db/transact-bundle base t es))
      (transact-bundle [_ t es o] (note :batch o) (db/transact-bundle base t es o))

      db/ITxMetadataStore
      (tx-metadata-supported? [_] keeps?)
      (tx-metadata-of [_ _ _ _ _] nil))))

(defn- req [st resource-type & {:keys [produce id body if-match]}]
  (cond-> {:fhir/store         st
           :fhir/resource-type resource-type
           :path-params        {:tenant-id tenant}
           :query-params       {}
           :headers            (cond-> {} if-match (assoc "if-match" if-match))}
    produce (assoc :fhir/tx-metadata produce)
    id      (assoc-in [:path-params :id] id)
    body    (assoc-in [:parameters :body] body)))

(defn- run [handler request]
  ((middleware/wrap-fhir-exceptions handler) request))

(defn- write-everything!
  [st produce]
  (let [id (-> (handlers/create-resource
                (req st "Substance" :produce produce :body {:resourceType "Substance"}))
               :body :id)]
    (handlers/update-resource
     (req st "Substance" :produce produce :id id :body {:resourceType "Substance" :id id}))
    (handlers/update-resource
     (req st "Substance" :produce produce :id id :if-match "W/\"2\""
          :body {:resourceType "Substance" :id id :status "active"}))
    (handlers/patch-resource
     (req st "Substance" :produce produce :id id
          :body [{:op "replace" :path "/status" :value "inactive"}]))
    (run (handlers/transaction {})
         (cond-> {:fhir/store  st
                  :path-params {:tenant-id tenant}
                  :body-params {:resourceType "Bundle" :type "transaction"
                                :entry [{:resource {:resourceType "Substance" :id "s-2"}
                                         :request {:method "PUT" :url "Substance/s-2"}}]}}
           produce (assoc :fhir/tx-metadata produce)))
    (run (handlers/transaction {})
         (cond-> {:fhir/store  st
                  :path-params {:tenant-id tenant}
                  :body-params {:resourceType "Bundle" :type "batch"
                                :entry [{:resource {:resourceType "Substance" :id "s-3"}
                                         :request {:method "PUT" :url "Substance/s-3"}}]}}
           produce (assoc :fhir/tx-metadata produce)))
    (handlers/delete-resource (req st "Substance" :produce produce :id id))))

(deftest a-producer-stamps-every-handler-write
  (let [seen (atom [])
        produced (atom 0)
        produce (fn [request]
                  (is (some? (:fhir/store request)) "called with the request")
                  (swap! produced inc)
                  stamp)]
    (write-everything! (recording-store seen true) produce)
    (is (= [:create :update :update :update :transaction :batch :delete]
           (mapv first @seen))
        "every verb a handler calls")
    (testing "each carries the stamp, beside the If-Match it already had"
      (is (every? #(= stamp (:tx-metadata (second %))) @seen))
      (is (some #(= {:if-match "2" :tx-metadata stamp} (second %)) @seen)))
    (is (= (count @seen) @produced) "the producer runs once per write")))

(deftest no-producer-keeps-the-plain-arity
  (let [seen (atom [])]
    (write-everything! (recording-store seen true) nil)
    (is (= #{:plain} (into #{} (comp (remove #(= {:if-match "2"} (second %))) (map second)) @seen)))))

(deftest a-store-that-keeps-no-stamp-is-not-handed-one
  (let [seen (atom [])
        produce (fn [_] (throw (ex-info "must not be called" {})))]
    (write-everything! (recording-store seen false) produce)
    (is (not-any? #(contains? (second %) :tx-metadata) (filter (comp map? second) @seen)))))

(deftest the-router-injects-the-producer
  (let [resolved (router/resolve-options {:tx-metadata ::producer})
        mw (router/default-middleware (mock/create-mock-store {}) resolved)
        entry (some #(when (= ::router/tx-metadata (:name %)) %) mw)
        seen (atom nil)]
    (is (= ::producer (:tx-metadata resolved)))
    (is (some? entry))
    (((:wrap entry) (fn [request] (reset! seen (:fhir/tx-metadata request)))) {})
    (is (= ::producer @seen))
    (is (nil? (some #(when (= ::router/tx-metadata (:name %)) %)
                    (router/default-middleware (mock/create-mock-store {})
                                               (router/resolve-options {}))))
        "absent without a producer")))
