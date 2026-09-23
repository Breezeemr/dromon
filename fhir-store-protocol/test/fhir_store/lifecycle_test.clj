(ns fhir-store.lifecycle-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [taoensso.telemere :as t]
            [fhir-store.lifecycle :as lc]))

(def composition
  {:resourceType "Composition"
   :id "c1"
   :title "Visit note for Alice Testerson"})

(defrecord WriteLifecycle [calls]
  lc/IWriteLifecycle
  (prepare [_ write]
    (swap! calls conj [:prepare write])
    (assoc (:resource write) :prepared true))
  (tx-ops [_ write]
    (swap! calls conj [:tx-ops write])
    (list [:db/add "tmp" :x/y (:id write)]))
  (after-commit [_ commit]
    (swap! calls conj [:after-commit commit])
    nil))

(defrecord ReadOnlyLifecycle []
  lc/IReadLifecycle
  (present [_ _ resource]
    (assoc resource :presented true)))

(defrecord ThrowingLifecycle []
  lc/IWriteLifecycle
  (prepare [_ _]
    (throw (ex-info "refused" {:fhir/status 422})))
  (tx-ops [_ _]
    (throw (ex-info "no ops" {:fhir/status 409})))
  (after-commit [_ _]
    (throw (ex-info "blob store down" {})))
  lc/IReadLifecycle
  (present [_ _ _]
    (throw (ex-info "render failed" {}))))

(defrecord SchemaLifecycle []
  lc/IReadLifecycle
  (present [_ _ resource] resource)
  lc/IStoreSchema
  (schema-tx [_]
    [{:db/ident :x/y :db/valueType :db.type/string :db/cardinality :db.cardinality/one}]))

(def lifecycle-value (->ReadOnlyLifecycle))

(defn make-lifecycle
  "Constructor fn resolved by symbol: records the store-opts it was given."
  [store-opts]
  (assoc (->ReadOnlyLifecycle) :store-opts store-opts))

(defn make-non-lifecycle [_store-opts] {:not "a lifecycle"})

(def not-a-lifecycle {:just "a map"})

(defn- problem [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:lifecycle/problem (ex-data e)))))

(deftest resolve-lifecycle-accepts-every-documented-spelling
  (testing "nil means no lifecycle"
    (is (nil? (lc/resolve-lifecycle nil {:some :opts}))))

  (testing "a qualified symbol resolves to the value it names"
    (is (identical? lifecycle-value
                    (lc/resolve-lifecycle `lifecycle-value {}))))

  (testing "a qualified symbol naming a constructor fn is called with the store opts"
    (let [opts {:resource/lifecycle `make-lifecycle :k 1}
          built (lc/resolve-lifecycle `make-lifecycle opts)]
      (is (instance? ReadOnlyLifecycle built))
      (is (= opts (:store-opts built)))))

  (testing "a lifecycle value is returned as is"
    (let [v (->WriteLifecycle (atom []))]
      (is (identical? v (lc/resolve-lifecycle v {})))))

  (testing "a constructor fn passed directly is called with the store opts"
    (let [built (lc/resolve-lifecycle make-lifecycle {:k 2})]
      (is (= {:k 2} (:store-opts built))))))

(deftest resolve-lifecycle-refuses-misconfiguration
  (testing "an unresolvable symbol throws"
    (is (= :unresolvable
           (problem #(lc/resolve-lifecycle 'fhir-store.lifecycle-test/no-such-var {}))))
    (is (= :unresolvable
           (problem #(lc/resolve-lifecycle 'no.such.namespace/lifecycle {})))))

  (testing "an unqualified symbol throws"
    (is (= :unqualified-symbol
           (problem #(lc/resolve-lifecycle 'lifecycle-value {})))))

  (testing "a constructor returning a non-lifecycle throws"
    (is (= :constructor-returned-non-lifecycle
           (problem #(lc/resolve-lifecycle `make-non-lifecycle {}))))
    (is (= :constructor-returned-non-lifecycle
           (problem #(lc/resolve-lifecycle make-non-lifecycle {})))))

  (testing "a value that is neither a lifecycle nor a fn throws"
    (is (= :not-a-lifecycle
           (problem #(lc/resolve-lifecycle `not-a-lifecycle {}))))
    (is (= :not-a-lifecycle
           (problem #(lc/resolve-lifecycle {:a 1} {})))))

  (testing "refusals carry a status and the symbol, never store opts"
    (let [data (try (lc/resolve-lifecycle `make-non-lifecycle {:secret "x"})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= 500 (:fhir/status data)))
      (is (= `make-non-lifecycle (:resource/lifecycle data)))
      (is (not (str/includes? (pr-str data) "secret"))))))

(def write
  {:tenant-id "t1" :resource-type "Composition" :id "c1" :method :create
   :resource composition})

(deftest write-helpers-pass-through-without-a-write-lifecycle
  (testing "nil lifecycle"
    (is (identical? composition (lc/prepare-write nil write)))
    (is (nil? (lc/write-tx-ops nil write)))
    (is (nil? (lc/fire-after-commit! nil {:tenant-id "t1" :writes [write]}))))

  (testing "a lifecycle implementing only IReadLifecycle"
    (let [ro (->ReadOnlyLifecycle)]
      (is (identical? composition (lc/prepare-write ro write)))
      (is (nil? (lc/write-tx-ops ro write)))
      (is (nil? (lc/fire-after-commit! ro {:tenant-id "t1" :writes [write]}))))))

(deftest write-helpers-call-the-write-lifecycle
  (let [calls (atom [])
        wl (->WriteLifecycle calls)
        prepared (lc/prepare-write wl write)
        ops (lc/write-tx-ops wl (assoc write :resource prepared :submitted composition))]
    (is (= (assoc composition :prepared true) prepared))
    (is (vector? ops))
    (is (= [[:db/add "tmp" :x/y "c1"]] ops))
    (is (nil? (lc/fire-after-commit! wl {:tenant-id "t1" :writes [write]})))
    (is (= [:prepare :tx-ops :after-commit] (mapv first @calls)))))

(deftest tx-ops-shape
  (let [returning (fn [v]
                    (reify lc/IWriteLifecycle
                      (prepare [_ w] (:resource w))
                      (tx-ops [_ _] v)
                      (after-commit [_ _] nil)))]
    (testing "an empty collection is no ops"
      (is (nil? (lc/write-tx-ops (returning []) write)))
      (is (nil? (lc/write-tx-ops (returning ()) write))))
    (testing "a lazy seq is realized into a vector"
      (is (= [[:a] [:b]] (lc/write-tx-ops (returning (map vector [:a :b])) write))))
    (testing "a map or set is refused rather than spliced as entries"
      (is (= :tx-ops-not-sequential
             (problem #(lc/write-tx-ops (returning {:db/id 1}) write))))
      (is (= :tx-ops-not-sequential
             (problem #(lc/write-tx-ops (returning #{[:a]}) write)))))))

(deftest prepare-returning-nil-keeps-the-submitted-body
  (let [nil-lc (reify lc/IWriteLifecycle
                 (prepare [_ _] nil)
                 (tx-ops [_ _] nil)
                 (after-commit [_ _] nil))]
    (is (identical? composition (lc/prepare-write nil-lc write)))))

(deftest prepare-and-tx-ops-exceptions-propagate
  (let [thrower (->ThrowingLifecycle)]
    (testing "prepare refuses the write with its own status"
      (let [data (try (lc/prepare-write thrower write) nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= 422 (:fhir/status data)))))
    (testing "tx-ops refuses the write with its own status"
      (let [data (try (lc/write-tx-ops thrower write) nil
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= 409 (:fhir/status data)))))))

(deftest after-commit-is-contained-and-logged-without-content
  (let [thrower (->ThrowingLifecycle)
        commit {:tenant-id "t1"
                :writes [write (assoc write :resource-type "Patient" :id "p1")]
                :result {:tx-id 1}}
        result (atom ::unset)
        signal (t/with-signal
                 (reset! result (lc/fire-after-commit! thrower commit)))]
    (is (nil? @result) "never rethrown, returns nil")
    (is (= :fhir-store/lifecycle-after-commit-failed (:id signal)))
    (is (= :error (:level signal)))
    (is (= {:tenant-id "t1" :resource-types #{"Composition" "Patient"}}
           (:data signal)))
    (is (not (str/includes? (pr-str (dissoc signal :error)) "Testerson")))))

(deftest present-helper
  (let [read {:tenant-id "t1" :resource-type "Composition"}]
    (testing "nil lifecycle and a write-only lifecycle leave the resource unchanged"
      (is (identical? composition (lc/present-resource nil read composition)))
      (is (identical? composition
                      (lc/present-resource (->WriteLifecycle (atom [])) read composition))))

    (testing "a read lifecycle presents the resource"
      (is (= (assoc composition :presented true)
             (lc/present-resource (->ReadOnlyLifecycle) read composition))))

    (testing "a nil from present keeps the resource"
      (is (identical? composition
                      (lc/present-resource (reify lc/IReadLifecycle
                                             (present [_ _ _] nil))
                                           read composition))))

    (testing "an exception is contained, logged without content, resource unchanged"
      (let [result (atom ::unset)
            signal (t/with-signal
                     (reset! result (lc/present-resource (->ThrowingLifecycle)
                                                         read composition)))]
        (is (identical? composition @result))
        (is (= :fhir-store/lifecycle-present-failed (:id signal)))
        (is (= {:tenant-id "t1" :resource-type "Composition"} (:data signal)))
        (is (not (str/includes? (pr-str (dissoc signal :error)) "Testerson")))))))

(deftest lifecycle-schema-tx
  (is (nil? (lc/lifecycle-schema-tx nil)))
  (is (nil? (lc/lifecycle-schema-tx (->ReadOnlyLifecycle))))
  (is (= [:x/y] (map :db/ident (lc/lifecycle-schema-tx (->SchemaLifecycle)))))
  (is (seq? (lc/lifecycle-schema-tx (->SchemaLifecycle))))
  (is (nil? (lc/lifecycle-schema-tx (reify lc/IStoreSchema (schema-tx [_] []))))))
