(ns fhir-store.mock.lifecycle-store-test
  "The prototype lifecycle store wrapper, over the mock store as its base."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.lifecycle :as lc]
            [fhir-store.lifecycle-store :as lcs]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as fp]))

(def ^:private tenant "t1")

(defn- ops-recording-lifecycle [seen]
  (reify lc/IWriteLifecycle
    (prepare [_ write] (:resource write))
    (tx-ops [_ write]
      (swap! seen conj {:id (:id write) :db? (map? (:db write))})
      [[:op (:id write)]])
    (after-commit [_ _] nil)))

(def ^:private read-only
  (reify lc/IReadLifecycle
    (present [_ _ resource] resource)))

(defn- problem [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:lifecycle/problem (ex-data e)))))

(deftest tx-ops-are-computed-by-the-store-against-its-own-db
  (let [seen  (atom [])
        store (mock/create-mock-store {:resource/lifecycle (ops-recording-lifecycle seen)})]
    (fp/create-resource store tenant :Patient "p1" {:active true})
    (is (= [{:id "p1" :db? true}] @seen)
        "the mock hands its state value as :db, which a wrapper alone could not")))

(deftest no-lifecycle-means-no-wrapper
  (let [base (mock/create-mock-store {})]
    (is (identical? base (lcs/wrap base nil)))))

(deftest base-keys-stay-readable-on-the-wrapper
  (let [store (mock/create-mock-store {:resource/lifecycle read-only})]
    (is (instance? clojure.lang.Atom (:state store)))
    (is (fn? (get-in store [:operations :valueset-expand])))
    (is (identical? read-only (:resource/lifecycle store)))
    (is (= [read-only] (lc/members (:resource/lifecycle store))))))

(defn- bare-store
  "A base honouring nothing beyond IFHIRStore, with optional capabilities."
  [& {:keys [temporal? valid-time? tx-ops?]}]
  (cond
    (and temporal? valid-time?) (reify fp/IFHIRStore lcs/ITxOpsStore (tx-ops-dialect [_] :none)
                                  fp/ITemporalReadStore fp/IValidTimeStore)
    valid-time?                 (reify fp/IFHIRStore lcs/ITxOpsStore (tx-ops-dialect [_] :none)
                                  fp/IValidTimeStore)
    temporal?                   (reify fp/IFHIRStore lcs/ITxOpsStore (tx-ops-dialect [_] :none)
                                  fp/ITemporalReadStore)
    tx-ops?                     (reify fp/IFHIRStore lcs/ITxOpsStore (tx-ops-dialect [_] :none))
    :else                       (reify fp/IFHIRStore)))

(deftest the-wrapper-claims-exactly-the-capabilities-of-its-base
  (let [writes (ops-recording-lifecycle (atom []))]
    (let [w (lcs/wrap (bare-store :tx-ops? true) writes)]
      (is (not (satisfies? fp/ITemporalReadStore w)))
      (is (not (satisfies? fp/IValidTimeStore w))))
    (let [w (lcs/wrap (bare-store :temporal? true) writes)]
      (is (satisfies? fp/ITemporalReadStore w))
      (is (not (satisfies? fp/IValidTimeStore w))))
    (let [w (lcs/wrap (bare-store :temporal? true :valid-time? true) writes)]
      (is (satisfies? fp/ITemporalReadStore w))
      (is (satisfies? fp/IValidTimeStore w)))
    (is (= :unsupported-capabilities
           (problem #(lcs/wrap (bare-store :valid-time? true) writes))))
    (testing "text search and tx-metadata answer through their capability queries"
      (let [w (lcs/wrap (mock/create-mock-store {}) writes)]
        (is (false? (fp/text-searchable? w tenant :Patient)))
        (is (false? (fp/supports-tx-metadata? w)))))))

(deftest a-store-that-would-drop-ops-is-refused
  (is (= :store-ignores-tx-ops
         (problem #(lcs/wrap (bare-store) (ops-recording-lifecycle (atom []))))))
  (is (some? (lcs/wrap (bare-store) read-only))
      "a lifecycle that never writes needs nothing from the store"))

(deftest posted-entries-are-answered-as-creates
  (let [store (mock/create-mock-store {:resource/lifecycle (ops-recording-lifecycle (atom []))})]
    (testing "transaction"
      (let [res   (fp/transact-transaction
                   store tenant
                   [{:request {:method "POST" :url "Patient"} :resource {:active true}}
                    {:request {:method "PUT" :url "Patient/p2"} :resource {:active true}}])
            by-st (group-by #(get-in % [:response :status]) (:entry res))]
        (is (= 1 (count (get by-st "201 Created"))))
        (is (re-matches #"Patient/[0-9a-f-]{36}/_history/1"
                        (get-in (first (get by-st "201 Created")) [:response :location])))))
    (testing "batch"
      (let [res (fp/transact-bundle
                 store tenant
                 [{:request {:method "POST" :url "Patient"} :resource {:active true}}])]
        (is (= "201 Created" (get-in res [:entry 0 :response :status])))))))

(deftest a-guarded-write-is-refused-before-prepare
  (let [prepared (atom 0)
        lc       (reify lc/IWriteLifecycle
                   (prepare [_ w] (swap! prepared inc) (:resource w))
                   (tx-ops [_ _] nil)
                   (after-commit [_ _] nil))
        store    (mock/create-mock-store {:resource/lifecycle lc})]
    (fp/create-resource store tenant :Patient "p1" {:active true})
    (reset! prepared 0)
    (doseq [f [#(fp/update-resource store tenant :Patient "p1" {} {:if-match "9"})
               #(fp/update-resource store tenant :Patient "nope" {} {:if-match "1"})
               #(fp/create-resource store tenant :Patient "p1" {})
               #(fp/transact-transaction store tenant
                                         [{:request {:method "PUT" :url "Patient/p1" :ifMatch "W/\"9\""}
                                           :resource {}}])]]
      (is (thrown? clojure.lang.ExceptionInfo (f))))
    (is (zero? @prepared))))

(deftest references-to-a-rewritten-post-are-resolved-before-prepare
  (let [seen  (atom [])
        lc    (reify lc/IWriteLifecycle
                (prepare [_ w] (swap! seen conj (:resource w)) (:resource w))
                (tx-ops [_ _] nil)
                (after-commit [_ _] nil))
        store (mock/create-mock-store {:resource/lifecycle lc})
        res   (fp/transact-transaction
               store tenant
               [{:fullUrl "urn:uuid:aaaaaaaa-0000-0000-0000-000000000001"
                 :request {:method "POST" :url "Patient"}
                 :resource {:resourceType "Patient"}}
                {:request {:method "PUT" :url "Observation/o1"}
                 :resource {:resourceType "Observation"
                            :subject {:reference "urn:uuid:aaaaaaaa-0000-0000-0000-000000000001"}}}])
        patient-id (some #(when (= "Patient" (get-in % [:resource :resourceType]))
                            (get-in % [:resource :id]))
                         (:entry res))]
    (is (= (str "Patient/" patient-id)
           (get-in (fp/read-resource store tenant :Observation "o1") [:subject :reference])))
    (is (= (str "Patient/" patient-id)
           (get-in (second @seen) [:subject :reference]))
        "the lifecycle sees the resolved reference")))
