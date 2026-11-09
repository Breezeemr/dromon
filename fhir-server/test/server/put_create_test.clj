(ns server.put-create-test
  "A PUT that creates the resource at the client's id says so in its write's
   opts (`:update-create?`), and honours `If-None-Match: *` (create only if
   absent), which also reaches the write's opts."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.lifecycle :as lc]
            [fhir-store.mock.core :as mock]
            [server.handlers :as handlers]
            [server.middleware :as middleware]))

(def ^:private tenant "default")

(defn- recording-lifecycle
  "Records each prepared write as [method opts]."
  [writes]
  (reify lc/IWriteLifecycle
    (prepare [_ write]
      (swap! writes conj [(:method write) (:opts write)])
      (:resource write))
    (tx-ops [_ _] [])
    (after-commit [_ _])))

(defn- put [store id & {:keys [if-match if-none-match body]}]
  ((middleware/wrap-fhir-exceptions handlers/update-resource)
   {:fhir/store         store
    :fhir/resource-type "Substance"
    :path-params        {:tenant-id tenant :id id}
    :query-params       {}
    :headers            (cond-> {}
                          if-match      (assoc "if-match" if-match)
                          if-none-match (assoc "if-none-match" if-none-match))
    :parameters         {:body (or body {:resourceType "Substance" :id id})}}))

(defn- conditional-put [store id & {:keys [if-none-match]}]
  ((middleware/wrap-fhir-exceptions handlers/conditional-update)
   {:fhir/store           store
    :fhir/resource-type   "Substance"
    :fhir/search-registry {}
    :path-params          {:tenant-id tenant}
    :query-params         {"_id" id}
    :headers              (cond-> {} if-none-match (assoc "if-none-match" if-none-match))
    :parameters           {:body {:resourceType "Substance" :id id}}}))

(defn- fixture []
  (let [writes (atom [])]
    [writes (mock/create-mock-store {:resource/lifecycle (recording-lifecycle writes)})]))

(deftest a-put-create-is-marked
  (let [[writes store] (fixture)]
    (testing "without If-None-Match"
      (is (= 201 (:status (put store "s-1"))))
      (is (= [[:create {:update-create? true}]] @writes)))
    (testing "an update of the same id carries no mark"
      (reset! writes [])
      (is (= 200 (:status (put store "s-1"))))
      (is (= [[:update nil]] @writes)))))

(deftest if-none-match-any-creates-only
  (let [[writes store] (fixture)]
    (testing "an absent resource is created, and the write says the client asked for that"
      (is (= 201 (:status (put store "s-1" :if-none-match "*"))))
      (is (= [[:create {:update-create? true :if-none-match :any}]] @writes)))
    (testing "an existing resource is refused 412, and nothing is written"
      (reset! writes [])
      (let [resp (put store "s-1" :if-none-match "*"
                      :body {:resourceType "Substance" :id "s-1" :status "inactive"})]
        (is (= 412 (:status resp)))
        (is (= [] @writes))))
    (testing "any other value is refused 400, never ignored"
      (let [resp (put store "s-2" :if-none-match "W/\"1\"")]
        (is (= 400 (:status resp)))
        (is (= [] @writes))))
    (testing "If-Match beside it is refused 400"
      (let [resp (put store "s-1" :if-match "W/\"1\"" :if-none-match "*")]
        (is (= 400 (:status resp)))
        (is (= [] @writes))))))

(deftest a-conditional-put-create-at-the-body-id-is-marked
  (let [[writes store] (fixture)]
    (is (= 201 (:status (conditional-put store "s-1"))))
    (is (= [[:create {:update-create? true}]] @writes))
    (testing "If-None-Match: * on a match is refused 412"
      (reset! writes [])
      (is (= 412 (:status (conditional-put store "s-1" :if-none-match "*"))))
      (is (= [] @writes)))
    (testing "and with no match it reaches the write"
      (reset! writes [])
      (is (= 201 (:status (conditional-put store "s-2" :if-none-match "*"))))
      (is (= [[:create {:update-create? true :if-none-match :any}]] @writes)))))
