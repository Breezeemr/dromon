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
   - a host that throws loses its narrative, not the write."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.handlers :as handlers]
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
