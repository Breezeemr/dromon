(ns fhir-store.lifecycle-compose-test
  (:require [clojure.test :refer [deftest testing is]]
            [taoensso.telemere :as t]
            [fhir-store.lifecycle :as lc]))

(def ^:private write
  {:tenant-id "t1" :resource-type "Patient" :id "p1" :method :create
   :resource {:resourceType "Patient"}})

(defrecord Tagger [tag calls]
  lc/IWriteLifecycle
  (prepare [_ w]
    (swap! calls conj [:prepare tag (:tags (:resource w))])
    (update (:resource w) :tags (fnil conj []) tag))
  (tx-ops [_ w]
    (swap! calls conj [:tx-ops tag (:tags (:resource w))])
    [[:op tag]])
  (after-commit [_ commit]
    (swap! calls conj [:after-commit tag (mapv :tx-ops (:writes commit))]))
  lc/IReadLifecycle
  (present [_ _ resource]
    (swap! calls conj [:present tag])
    (update resource :shown (fnil conj []) tag))
  lc/IStoreSchema
  (schema-tx [_] [{:db/ident (keyword "x" tag)}]))

(defrecord Failing []
  lc/IWriteLifecycle
  (prepare [_ _] (throw (ex-info "refused" {:fhir/status 422})))
  (tx-ops [_ _] nil)
  (after-commit [_ _] (throw (ex-info "side effect failed" {})))
  lc/IReadLifecycle
  (present [_ _ _] (throw (ex-info "render failed" {}))))

(defrecord ReadOnly []
  lc/IReadLifecycle
  (present [_ _ resource] (assoc resource :read-only true)))

(defn make-tagger [_opts] (->Tagger "built" (atom [])))
(def taggers [(->Tagger "from-def-1" (atom [])) (->Tagger "from-def-2" (atom []))])

(defn- of [calls kind] (filterv #(= kind (first %)) @calls))

(deftest compose-is-identity-at-zero-and-one
  (let [a (->Tagger "a" (atom []))]
    (is (nil? (lc/compose [])))
    (is (nil? (lc/compose [nil nil])))
    (is (identical? a (lc/compose [a])))
    (is (identical? a (lc/compose [nil a nil])))))

(deftest compose-flattens-and-members-sees-through
  (let [[a b c] (map #(->Tagger % (atom [])) ["a" "b" "c"])
        ab      (lc/compose [a b])
        abc     (lc/compose [ab c])]
    (is (= [a b c] (lc/members abc)) "nested compositions flatten")
    (is (= [a] (lc/members a)))
    (is (= [] (lc/members nil)))))

(deftest compose-refuses-a-non-lifecycle
  (is (= :not-a-lifecycle
         (try (lc/compose [(->ReadOnly) {:not "one"}]) nil
              (catch clojure.lang.ExceptionInfo e (:lifecycle/problem (ex-data e)))))))

(deftest write-phases-run-in-order-on-the-prepared-body
  (let [calls (atom [])
        lc    (lc/compose [(->Tagger "a" calls) (->Tagger "b" calls)])
        prepared (lc/prepare-write lc write)
        ops   (lc/write-tx-ops lc (assoc write :resource prepared))]
    (is (= ["a" "b"] (:tags prepared)) "each member prepares what the previous one returned")
    (is (= [[:prepare "a" nil] [:prepare "b" ["a"]]] (of calls :prepare)))
    (is (= [[:tx-ops "a" ["a" "b"]] [:tx-ops "b" ["a" "b"]]] (of calls :tx-ops))
        "every member computes tx-ops from the final prepared body")
    (is (= [[:op "a"] [:op "b"]] ops))
    (testing "after-commit fires in order, each seeing the whole write's ops"
      (lc/fire-after-commit! lc {:tenant-id "t1" :writes [(assoc write :tx-ops ops)]})
      (is (= [[:after-commit "a" [ops]] [:after-commit "b" [ops]]] (of calls :after-commit))))))

(deftest a-members-nil-prepare-keeps-the-body-so-far
  (let [calls (atom [])
        lc    (lc/compose [(->Tagger "a" calls)
                           (reify lc/IWriteLifecycle
                             (prepare [_ _] nil)
                             (tx-ops [_ _] nil)
                             (after-commit [_ _] nil))])]
    (is (= ["a"] (:tags (lc/prepare-write lc write))))
    (is (= [[:op "a"]] (lc/write-tx-ops lc (assoc write :resource {}))))))

(deftest the-first-refusal-stops-the-chain
  (let [calls (atom [])
        lc    (lc/compose [(->Tagger "a" calls) (->Failing) (->Tagger "c" calls)])]
    (is (= 422 (try (lc/prepare-write lc write) nil
                    (catch clojure.lang.ExceptionInfo e (:fhir/status (ex-data e))))))
    (is (= [[:prepare "a" nil]] (of calls :prepare)) "the member after the refusal never runs")))

(deftest after-commit-failures-are-contained-per-member
  (let [calls  (atom [])
        lc     (lc/compose [(->Failing) (->Tagger "b" calls)])
        signal (t/with-signal
                 (lc/fire-after-commit! lc {:tenant-id "t1" :writes [write]}))]
    (is (= 1 (count (of calls :after-commit))) "the member after a failing one still fires")
    (is (= :fhir-store/lifecycle-after-commit-failed (:id signal)))
    (is (= (.getName Failing) (get-in signal [:data :lifecycle]))
        "the signal names the member that failed, not the composition")))

(deftest present-runs-in-reverse-so-the-outer-member-has-the-last-word
  (let [calls (atom [])
        lc    (lc/compose [(->Tagger "outer" calls) (->Tagger "inner" calls)])
        read  {:tenant-id "t1" :resource-type "Patient"}]
    (is (= ["inner" "outer"] (:shown (lc/present-resource lc read {:resourceType "Patient"}))))
    (testing "a failing member costs only its own presentation"
      (let [lc (lc/compose [(->ReadOnly) (->Failing)])
            signal (t/with-signal
                     (is (= {:resourceType "Patient" :read-only true}
                            (lc/present-resource lc read {:resourceType "Patient"}))))]
        (is (= (.getName Failing) (get-in signal [:data :lifecycle])))))))

(deftest read-only-and-write-only-members-mix
  (let [calls (atom [])
        lc    (lc/compose [(->ReadOnly) (->Tagger "w" calls)])]
    (is (= ["w"] (:tags (lc/prepare-write lc write))) "a read-only member is skipped on writes")
    (is (true? (:read-only (lc/present-resource lc {} {}))))))

(deftest schema-tx-concatenates-in-order
  (let [lc (lc/compose [(->Tagger "a" (atom [])) (->ReadOnly) (->Tagger "b" (atom []))])]
    (is (= [:x/a :x/b] (map :db/ident (lc/lifecycle-schema-tx lc))))
    (is (nil? (lc/lifecycle-schema-tx (lc/compose [(->ReadOnly) (->ReadOnly)]))))))

(deftest resolve-lifecycle-composes-a-vector
  (let [a  (->Tagger "a" (atom []))
        lc (lc/resolve-lifecycle [a nil `make-tagger (->ReadOnly)] {:some :opts})]
    (is (= ["a" "built"] (keep :tag (lc/members lc))))
    (is (= 3 (count (lc/members lc))))
    (is (nil? (lc/resolve-lifecycle [] {})))
    (is (identical? a (lc/resolve-lifecycle [a] {}))))
  (testing "a symbol naming a vector of specs composes it"
    (is (= ["from-def-1" "from-def-2"]
           (mapv :tag (lc/members (lc/resolve-lifecycle `taggers {}))))))
  (testing "a bad element is refused at construction"
    (is (= :not-a-lifecycle
           (try (lc/resolve-lifecycle [(->ReadOnly) {:not "one"}] {}) nil
                (catch clojure.lang.ExceptionInfo e (:lifecycle/problem (ex-data e))))))))
