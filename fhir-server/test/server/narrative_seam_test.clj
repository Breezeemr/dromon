(ns server.narrative-seam-test
  "dromon's half of Resource.text derivation: the seam, not the prose.

   These tests deliberately use a TOY narrative function. What a Condition
   should read like is the host's business and is proven in the
   `fhir-narrative` module's own integration suite. What is proven here is the
   contract dromon owes a host:

   - no injected function means no narrative, and a standalone dromon is
     therefore unchanged by this seam existing;
   - the function is applied at every write path;
   - it sees the FINAL body, so a PATCH cannot author its own narrative;
   - a host that throws loses its narrative, not the write.

   The second half pins presentation: an `IReadLifecycle` injected as
   `:fhir/lifecycle` fills read, write and transaction/batch responses, never
   storage and never search results, and a throwing one costs the
   presentation, not the read."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.lifecycle :as lifecycle]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.handlers :as handlers]
            [server.middleware :as middleware]
            [server.narrative :as narrative]))

(def ^:private tenant "default")

(defn- store [] (mock/create-mock-store {}))

(defn- marker
  "A toy host function: stamps the resource type and its id into :text."
  [resource-type resource]
  (assoc resource :text {:status "generated"
                         :div    (str "<div>" resource-type "/" (:id resource) "</div>")}))

(defn- req [st resource-type & {:keys [narrative-fn id body]}]
  (cond-> {:fhir/store         st
           :fhir/resource-type resource-type
           :path-params        {:tenant-id tenant}
           :query-params       {}
           :headers            {}}
    narrative-fn (assoc :fhir/narrative narrative-fn)
    id           (assoc-in [:path-params :id] id)
    body         (assoc-in [:parameters :body] body)))

(defn- saved [st resource-type id]
  (or (db/read-resource st tenant (keyword resource-type) id)
      (db/read-resource st tenant resource-type id)))

(deftest without-an-injected-function-nothing-is-derived
  (testing "a standalone dromon leaves :text absent rather than empty"
    (let [st   (store)
          resp (handlers/create-resource (req st "Substance" :body {:resourceType "Substance"}))]
      (is (= 201 (:status resp)))
      (is (not (contains? (saved st "Substance" (get-in resp [:body :id])) :text))))))

(deftest without-an-injected-function-client-narrative-is-left-alone
  (let [st       (store)
        supplied {:status "additional" :div "<div>mine</div>"}
        resp     (handlers/create-resource
                  (req st "Substance" :body {:resourceType "Substance" :text supplied}))]
    (is (= supplied (:text (saved st "Substance" (get-in resp [:body :id])))))))

(deftest the-injected-function-is-applied-on-create
  (let [st   (store)
        resp (handlers/create-resource
              (req st "Substance" :narrative-fn marker :body {:resourceType "Substance"}))
        id   (get-in resp [:body :id])]
    (is (= 201 (:status resp)))
    (is (some? (get-in (saved st "Substance" id) [:text :div])))))

(deftest on-create-the-narrative-is-derived-before-the-id-is-assigned
  (testing "PINNED CONSTRAINT, not an aspiration: `do-create` derives the
            narrative from the posted body and only then mints the id, so a
            renderer CANNOT reference the resource's own id on a create -- it
            would render an empty one. Renderers that need it must be given it
            another way, or this ordering has to change deliberately. None of
            the 48 current renderers reference `:id`, which is why this has
            never bitten."
    (let [st   (store)
          resp (handlers/create-resource
                (req st "Substance" :narrative-fn marker :body {:resourceType "Substance"}))
          id   (get-in resp [:body :id])]
      (is (= "<div>Substance/</div>" (get-in (saved st "Substance" id) [:text :div]))
          "the id is absent at derivation time")
      (is (string? id) "but the response still carries a real assigned id"))))

(deftest the-injected-function-sees-the-patched-body-not-the-clients-ops
  (testing "a client op targeting /text cannot author the saved narrative"
    (let [st (store)
          _  (handlers/create-resource
              (req st "Substance" :narrative-fn marker
                   :body {:resourceType "Substance" :id "s-1"}))
          id (-> (handlers/create-resource
                  (req st "Substance" :narrative-fn marker
                       :body {:resourceType "Substance"}))
                 :body :id)
          _  (handlers/patch-resource
              (req st "Substance" :narrative-fn marker :id id
                   :body [{:op "replace" :path "/text/div" :value "<div>forged</div>"}]))]
      (is (= (str "<div>Substance/" id "</div>")
             (get-in (saved st "Substance" id) [:text :div]))))))

(deftest a-throwing-host-function-loses-the-narrative-not-the-write
  (let [st    (store)
        boom  (fn [_ _] (throw (ex-info "renderer bug" {})))
        resp  (handlers/create-resource
               (req st "Substance" :narrative-fn boom :body {:resourceType "Substance"}))]
    (is (= 201 (:status resp)))
    (is (not (contains? (saved st "Substance" (get-in resp [:body :id])) :text)))))

(deftest a-host-function-returning-nil-is-a-no-op
  (let [st   (store)
        resp (handlers/create-resource
              (req st "Substance" :narrative-fn (constantly nil)
                   :body {:resourceType "Substance"}))]
    (is (= 201 (:status resp)))
    (is (not (contains? (saved st "Substance" (get-in resp [:body :id])) :text)))))

(deftest bundle-entries-that-write-get-it-and-the-rest-do-not
  (let [entries [{:request {:method "POST" :url "Substance"}
                  :resource {:resourceType "Substance" :id "a"}}
                 {:request {:method "PUT" :url "Substance/b"}
                  :resource {:resourceType "Substance" :id "b"}}
                 {:request {:method "DELETE" :url "Substance/c"}}
                 {:request {:method "GET" :url "Substance/d"}}]
        out (narrative/ensure-bundle-narrative marker entries)]
    (is (= "<div>Substance/a</div>" (get-in out [0 :resource :text :div])))
    (is (= "<div>Substance/b</div>" (get-in out [1 :resource :text :div])))
    (is (= (nth entries 2) (nth out 2)))
    (is (= (nth entries 3) (nth out 3))))
  (testing "no injected function leaves every entry identical"
    (let [entries [{:request {:method "POST" :url "Substance"}
                    :resource {:resourceType "Substance" :id "a"}}]]
      (is (= entries (narrative/ensure-bundle-narrative nil entries))))))

(deftest the-type-comes-from-the-url-when-the-body-omits-it
  (let [entries [{:request {:method "PUT" :url "Substance/b"} :resource {:id "b"}}]
        out     (narrative/ensure-bundle-narrative marker entries)]
    (is (= "<div>Substance/b</div>" (get-in out [0 :resource :text :div])))))

;; ---------------------------------------------------------------------------
;; Presentation: the injected `fhir-store.lifecycle/IReadLifecycle`
;; ---------------------------------------------------------------------------

(def ^:private presented-text
  {:status "generated" :div "<div>presented</div>"})

(defn- recording-lifecycle
  "A toy read lifecycle that fills :text on the response and records every
   read map it was handed."
  [calls]
  (reify lifecycle/IReadLifecycle
    (present [_ read resource]
      (swap! calls conj read)
      (assoc resource :text presented-text))))

(def ^:private throwing-lifecycle
  (reify lifecycle/IReadLifecycle
    (present [_ _ _] (throw (ex-info "presenter bug" {})))))

(defn- lc-req [st resource-type lc & {:as more}]
  (cond-> (apply req st resource-type (mapcat identity more))
    lc (assoc :fhir/lifecycle lc)))

(defn- run [handler request]
  ((middleware/wrap-fhir-exceptions handler) request))

(defn- seed! [st id]
  (db/create-resource st tenant :Substance id {:resourceType "Substance" :id id}))

(deftest a-read-response-is-presented-and-storage-is-not
  (let [st    (store)
        _     (seed! st "s-1")
        calls (atom [])
        r     (lc-req st "Substance" (recording-lifecycle calls) :id "s-1")
        resp  (handlers/read-resource r)]
    (is (= 200 (:status resp)))
    (is (= presented-text (:text (:body resp))))
    (is (not (contains? (saved st "Substance" "s-1") :text))
        "a direct store read returns what was stored")
    (testing "the read map names the tenant, type, store and request"
      (let [[read] @calls]
        (is (= 1 (count @calls)))
        (is (= tenant (:tenant-id read)))
        (is (= "Substance" (:resource-type read)))
        (is (identical? st (:store read)))
        (is (identical? r (:request read)))))))

(deftest create-update-and-patch-responses-are-presented
  (let [st    (store)
        lc    (recording-lifecycle (atom []))
        post  (handlers/create-resource
               (lc-req st "Substance" lc :body {:resourceType "Substance"}))
        id    (get-in post [:body :id])
        put   (handlers/update-resource
               (lc-req st "Substance" lc :id id
                       :body {:resourceType "Substance" :id id :status "active"}))
        upsrt (handlers/update-resource
               (lc-req st "Substance" lc :id "s-new"
                       :body {:resourceType "Substance" :id "s-new"}))
        patch (handlers/patch-resource
               (lc-req st "Substance" lc :id id
                       :body [{:op "replace" :path "/status" :value "inactive"}]))]
    (is (= [201 200 201 200] (mapv :status [post put upsrt patch])))
    (is (every? #(= presented-text (get-in % [:body :text])) [post put upsrt patch]))
    (is (not (contains? (saved st "Substance" id) :text))
        "presentation never reaches storage")
    (is (not (contains? (saved st "Substance" "s-new") :text)))))

(deftest guarded-and-conditional-write-responses-are-presented
  (let [st    (store)
        _     (seed! st "s-1")
        lc    (recording-lifecycle (atom []))
        vid   (get-in (saved st "Substance" "s-1") [:meta :versionId])
        guarded (handlers/update-resource
                 (-> (lc-req st "Substance" lc :id "s-1"
                             :body {:resourceType "Substance" :id "s-1" :status "active"})
                     (assoc-in [:headers "if-match"] (str "W/\"" vid "\""))))
        conditional (fn [handler criteria body]
                      (handler (-> (lc-req st "Substance" lc :body body)
                                   (assoc :query-params criteria
                                          :fhir/search-registry {}))))
        cond-create (conditional handlers/conditional-update {"_id" "s-none"}
                                 {:resourceType "Substance" :id "s-cond"})
        cond-update (conditional handlers/conditional-update {"_id" "s-1"}
                                 {:resourceType "Substance" :status "inactive"})
        cond-patch  (conditional handlers/conditional-patch {"_id" "s-1"}
                                 [{:op "replace" :path "/status" :value "active"}])]
    (is (= [200 201 200 200] (mapv :status [guarded cond-create cond-update cond-patch])))
    (is (every? #(= presented-text (get-in % [:body :text]))
                [guarded cond-create cond-update cond-patch]))
    (is (= "active" (:status (saved st "Substance" "s-1"))) "the patch was stored")
    (is (not (contains? (saved st "Substance" "s-1") :text))
        "presentation never reaches storage")
    (is (not (contains? (saved st "Substance" "s-cond") :text)))))

(deftest a-conditional-create-match-is-presented
  (let [st   (store)
        _    (seed! st "s-1")
        resp (handlers/create-resource
              (-> (lc-req st "Substance" (recording-lifecycle (atom []))
                          :body {:resourceType "Substance"})
                  (assoc-in [:headers "if-none-exist"] "_id=s-1")
                  (assoc :fhir/search-registry {})))]
    (is (= 200 (:status resp)))
    (is (= "s-1" (get-in resp [:body :id])))
    (is (= presented-text (get-in resp [:body :text])))))

(deftest a-throwing-lifecycle-leaves-the-stored-resource-on-the-response
  (let [st   (store)
        _    (seed! st "s-1")
        resp (handlers/read-resource (lc-req st "Substance" throwing-lifecycle :id "s-1"))]
    (is (= 200 (:status resp)))
    (is (= (saved st "Substance" "s-1") (:body resp)))))

(deftest without-an-injected-lifecycle-responses-are-unchanged
  (let [st   (store)
        _    (seed! st "s-1")
        resp (handlers/read-resource (lc-req st "Substance" nil :id "s-1"))]
    (is (= (saved st "Substance" "s-1") (:body resp))))
  (testing "a write-only lifecycle presents nothing"
    (let [st   (store)
          _    (seed! st "s-1")
          lc   (reify lifecycle/IWriteLifecycle
                 (prepare [_ w] (:resource w))
                 (tx-ops [_ _] nil)
                 (after-commit [_ _] nil))
          resp (handlers/read-resource (lc-req st "Substance" lc :id "s-1"))]
      (is (= (saved st "Substance" "s-1") (:body resp))))))

(deftest search-results-are-not-presented
  (let [st    (store)
        _     (seed! st "s-1")
        calls (atom [])
        resp  (handlers/search-type
               (-> (lc-req st "Substance" (recording-lifecycle calls))
                   (assoc :fhir/search-registry {})))]
    (is (= 200 (:status resp)))
    (is (= 1 (count (get-in resp [:body :entry]))))
    (is (not-any? #(contains? (:resource %) :text) (get-in resp [:body :entry])))
    (is (empty? @calls))))

(defn- bundle-of [bundle-type]
  {:resourceType "Bundle"
   :type bundle-type
   :entry [{:request  {:method "POST" :url "Substance"}
            :resource {:resourceType "Substance"}}
           {:request  {:method "PUT" :url "Substance/s-2"}
            :resource {:resourceType "Substance" :id "s-2"}}]})

(deftest transaction-and-batch-response-entries-are-presented
  (doseq [bundle-type ["transaction" "batch"]]
    (testing bundle-type
      (let [st    (store)
            calls (atom [])
            resp  (run (handlers/transaction {})
                       {:fhir/store     st
                        :fhir/lifecycle (recording-lifecycle calls)
                        :path-params    {:tenant-id tenant}
                        :body-params    (bundle-of bundle-type)})
            entries (get-in resp [:body :entry])]
        (is (= 200 (:status resp)))
        (is (= (str bundle-type "-response") (get-in resp [:body :type])))
        (is (= 2 (count entries)))
        (is (every? #(= presented-text (get-in % [:resource :text])) entries))
        (is (= #{"Substance"} (set (map :resource-type @calls))))
        (is (not (contains? (saved st "Substance" "s-2") :text)))))))

(deftest present-bundle-response-leaves-other-bundle-types-alone
  (let [bundle {:resourceType "Bundle" :type "searchset"
                :entry [{:resource {:resourceType "Substance" :id "a"}}]}
        r      {:path-params {:tenant-id tenant}
                :fhir/lifecycle (recording-lifecycle (atom []))}]
    (is (identical? bundle (narrative/present-bundle-response r bundle)))))
