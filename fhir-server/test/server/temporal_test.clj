(ns server.temporal-test
  "The per-axis capability gate.

   Three tiers, because the interesting failure is the middle one: a store with
   system time but no valid time must ACCEPT `_asOf` and REFUSE `_validAt`.
   Answering `_validAt` on the remaining axis would return the valid-now
   answer to a historical question, which is indistinguishable from success at
   the call site."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.lifecycle :as lifecycle]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [integrant.core :as ig]
            [ring.adapter.jetty9 :as jetty]
            [server.core :as core]
            [server.handlers :as handlers]
            [server.router :as router]
            [server.temporal :as tmp]))

(def ^:private tenant "default")

(def ^:private registry
  {"status" {:type "token" :columns [{:col "status" :array? false}]}})

;; A store with one time axis, standing in for Datomic.
(defrecord SystemTimeOnlyStore [calls]
  db/IFHIRStore
  (search [_ _ _ _ _] (swap! calls conj :search) [])
  (count-resources [_ _ _ _ _] 0)
  (current-basis [_ _] {:tx-id 7 :system-time (java.time.Instant/parse "2026-09-30T06:00:00Z")})
  db/ITemporalReadStore
  (temporal-axes [_] #{:system-time})
  (read-as-of [_ _ _ _ basis] (swap! calls conj [:read-as-of basis]) {:id "x" :resourceType "Coverage"})
  (search-as-of [_ _ _ _ _ basis] (swap! calls conj [:search-as-of basis]) [])
  (count-as-of-basis [_ _ _ _ _ basis] (swap! calls conj [:count-as-of basis]) 0)
  (resource-timeline [_ _ _ _ _]
    [{:resource {:id "x" :resourceType "Coverage"}
      :system-from (java.time.Instant/parse "2026-01-01T00:00:00Z")}]))

;; A store with both axes, standing in for XTDB v2.
(defrecord BitemporalStore [calls]
  db/IFHIRStore
  (search [_ _ _ _ _] (swap! calls conj :search) [])
  (count-resources [_ _ _ _ _] 0)
  (current-basis [_ _] {:tx-id 9 :system-time (java.time.Instant/parse "2026-10-01T06:00:00Z")})
  db/ITemporalReadStore
  (temporal-axes [_] #{:system-time :valid-time})
  (read-as-of [_ _ _ _ basis] (swap! calls conj [:read-as-of basis]) {:id "x" :resourceType "Coverage"})
  (search-as-of [_ _ _ _ _ basis] (swap! calls conj [:search-as-of basis]) [])
  (count-as-of-basis [_ _ _ _ _ basis] (swap! calls conj [:count-as-of basis]) 0)
  (resource-timeline [_ _ _ _ _]
    [{:resource {:id "x" :resourceType "Coverage"}
      :valid-from (java.time.Instant/parse "2026-01-01T00:00:00Z")
      :valid-to   (java.time.Instant/parse "2026-04-30T00:00:00Z")
      :system-from (java.time.Instant/parse "2026-01-01T00:00:00Z")}]))

(defn- req [store params & {:keys [id]}]
  (cond-> {:fhir/store store
           :fhir/resource-type "Coverage"
           :fhir/search-registry registry
           :path-params {:tenant-id tenant}
           :query-params params
           :headers {}}
    id (assoc-in [:path-params :id] id)))

(defn- issue-codes [resp]
  (set (map :code (get-in resp [:body :issue]))))

(defn- diagnostics [resp]
  (apply str (map :diagnostics (get-in resp [:body :issue]))))

;; ---------------------------------------------------------------------------
;; Tier 1: no temporal support at all
;; ---------------------------------------------------------------------------

(deftest store-without-temporal-support-refuses-both-selectors
  (let [store (mock/create-mock-store {})]
    (doseq [p ["_asOf" "_validAt"]]
      (testing p
        (let [resp (handlers/search-type (req store {p "2026-09-30T00:00:00Z"}))]
          (is (= 400 (:status resp)))
          (is (= #{"not-supported"} (issue-codes resp)))
          (is (clojure.string/includes? (diagnostics resp) p)))))))

(deftest lenient-handling-does-not-excuse-an-unsupported-axis
  (testing "handling=lenient drops unknown params, but never a temporal selector"
    ;; Dropping it would run the search against current state and return 200,
    ;; which reads as a successful answer to the question that was asked.
    (let [store (mock/create-mock-store {})
          resp (handlers/search-type
                (assoc (req store {"_asOf" "2026-09-30T00:00:00Z"})
                       :headers {"prefer" "handling=lenient"}))]
      (is (= 400 (:status resp))))))

;; ---------------------------------------------------------------------------
;; Tier 2: system time only — the case that matters
;; ---------------------------------------------------------------------------

(deftest system-time-store-accepts-as-of
  (let [calls (atom [])
        store (->SystemTimeOnlyStore calls)
        resp (handlers/search-type (req store {"_asOf" "2026-09-30T00:00:00Z"}))]
    (is (= 200 (:status resp)))
    (is (some #(and (vector? %) (= :search-as-of (first %))) @calls)
        "routed to the temporal read, not the plain search")
    (is (not-any? #{:search} @calls))))

(deftest system-time-store-refuses-valid-at
  (let [calls (atom [])
        store (->SystemTimeOnlyStore calls)
        resp (handlers/search-type (req store {"_validAt" "2026-05-14"}))]
    (is (= 400 (:status resp)))
    (is (= #{"not-supported"} (issue-codes resp)))
    (is (clojure.string/includes? (diagnostics resp) "_validAt"))
    (is (clojure.string/includes? (diagnostics resp) "valid-time")
        "names the missing axis, not just the parameter")
    (is (empty? @calls) "no query ran")))

(deftest mixed-selectors-fail-on-the-missing-axis
  (let [calls (atom [])
        store (->SystemTimeOnlyStore calls)
        resp (handlers/search-type (req store {"_asOf" "2026-09-30T00:00:00Z"
                                               "_validAt" "2026-05-14"}))]
    (is (= 400 (:status resp)) "one unavailable axis refuses the whole request")
    (is (empty? @calls))))

;; ---------------------------------------------------------------------------
;; Tier 3: both axes
;; ---------------------------------------------------------------------------

(deftest bitemporal-store-honours-both-axes
  (let [calls (atom [])
        store (->BitemporalStore calls)
        resp (handlers/search-type (req store {"_asOf" "2026-10-01T06:00:00Z"
                                               "_validAt" "2026-09-30"}))]
    (is (= 200 (:status resp)))
    (let [[_ basis] (first (filter vector? @calls))]
      (is (= (java.time.Instant/parse "2026-10-01T06:00:00Z") (:system-time basis)))
      (is (= (java.time.Instant/parse "2026-09-30T00:00:00Z") (:valid-time basis))
          "a date-only _validAt means start of that day, UTC"))))

(deftest response-states-the-basis-it-used
  (testing "an omitted _asOf resolves to the latest indexed transaction, and says so"
    ;; Without this a report cannot be reproduced: 'as best known' names a
    ;; different instant on every request.
    (let [store (->BitemporalStore (atom []))
          resp (handlers/search-type (req store {"_validAt" "2026-09-30"}))
          tags (get-in resp [:body :meta :tag])]
      (is (= 200 (:status resp)))
      (is (= #{"system-time" "valid-time"} (set (map :code tags))))
      (is (= "2026-10-01T06:00:00Z"
             (:display (first (filter #(= "system-time" (:code %)) tags))))
          "the concrete resolved instant, not the absent request value"))))

(deftest ordinary-search-is-untouched
  (testing "no selectors means no basis, no tags, and the plain search path"
    (let [calls (atom [])
          store (->BitemporalStore calls)
          resp (handlers/search-type (req store {"status" "active"}))]
      (is (= 200 (:status resp)))
      (is (= [:search] @calls))
      (is (nil? (get-in resp [:body :meta]))))))

;; ---------------------------------------------------------------------------
;; Parsing and the instance-level operations
;; ---------------------------------------------------------------------------

(deftest unparseable-selector-is-a-400
  (let [store (->BitemporalStore (atom []))
        resp (handlers/search-type (req store {"_asOf" "last Tuesday"}))]
    (is (= 400 (:status resp)))
    (is (= #{"invalid"} (issue-codes resp)))))

(deftest as-of-requires-a-selector
  (testing "$as-of with no selector is refused rather than answered as current state"
    (let [store (->BitemporalStore (atom []))
          resp (handlers/resource-as-of (req store {} :id "x"))]
      (is (= 400 (:status resp)))
      (is (clojure.string/includes? (diagnostics resp) "_asOf")))))

(deftest as-of-returns-the-resource-and-its-basis
  (let [store (->BitemporalStore (atom []))
        resp (handlers/resource-as-of (req store {"_validAt" "2026-05-14"} :id "x"))]
    (is (= 200 (:status resp)))
    (is (= #{"system-time" "valid-time"}
           (set (map :code (get-in resp [:body :meta :tag])))))))

(deftest as-of-is-instance-level
  (let [store (->BitemporalStore (atom []))
        resp (handlers/resource-as-of (req store {"_validAt" "2026-05-14"}))]
    (is (= 400 (:status resp)))
    (is (clojure.string/includes? (diagnostics resp) "instance-level"))))

(deftest timeline-omits-valid-time-on-a-single-axis-store
  (testing "absent valid-time bounds, never null ones"
    ;; A null valid-to would read as end-of-time; the axis simply does not exist.
    (let [resp (handlers/resource-timeline (req (->SystemTimeOnlyStore (atom [])) {} :id "x"))
          urls (set (map :url (:extension (first (get-in resp [:body :entry])))))]
      (is (= 200 (:status resp)))
      (is (contains? urls (str tmp/extension-base "system-from")))
      (is (not-any? #(clojure.string/includes? % "valid-") urls)))))

(deftest timeline-reports-both-axes-on-a-bitemporal-store
  (let [resp (handlers/resource-timeline (req (->BitemporalStore (atom [])) {} :id "x"))
        urls (set (map :url (:extension (first (get-in resp [:body :entry])))))]
    (is (= 200 (:status resp)))
    (is (= "history" (get-in resp [:body :type])))
    (is (contains? urls (str tmp/extension-base "valid-from")))
    (is (contains? urls (str tmp/extension-base "valid-to")))))

(deftest timeline-needs-a-temporal-store
  (let [resp (handlers/resource-timeline (req (mock/create-mock-store {}) {} :id "x"))]
    (is (= 400 (:status resp)))
    (is (= #{"not-supported"} (issue-codes resp)))))

;; ---------------------------------------------------------------------------
;; Presentation through an injected IReadLifecycle (see server.narrative).
;; These live here rather than in server.narrative-seam-test because the mock
;; store implements no ITemporalReadStore.
;; ---------------------------------------------------------------------------

(def ^:private presented-text
  {:status "generated" :div "<div>presented</div>"})

(defn- presenting-lifecycle
  "Fills :text, drops :meta, and records each read map with the resource it
   was handed."
  [calls]
  (reify lifecycle/IReadLifecycle
    (present [_ read resource]
      (swap! calls conj [read resource])
      (-> resource
          (assoc :text presented-text)
          (dissoc :meta)))))

(deftest as-of-is-presented-before-the-basis-is-stamped
  (let [calls (atom [])
        resp  (handlers/resource-as-of
               (assoc (req (->BitemporalStore (atom [])) {"_validAt" "2026-05-14"} :id "x")
                      :fhir/lifecycle (presenting-lifecycle calls)))
        [[read seen]] @calls]
    (is (= 200 (:status resp)))
    (is (= presented-text (get-in resp [:body :text])))
    (is (= 1 (count @calls)))
    (is (= {:interaction :as-of :resource-type "Coverage"}
           (select-keys read [:interaction :resource-type])))
    (is (nil? (:meta seen)) "the lifecycle is handed the stored resource, untagged")
    (is (= #{"system-time" "valid-time"} (set (map :code (get-in resp [:body :meta :tag]))))
        "a presenter cannot remove the server's statement of its basis")))

(deftest timeline-entries-are-presented
  (let [calls (atom [])
        resp  (handlers/resource-timeline
               (assoc (req (->BitemporalStore (atom [])) {} :id "x")
                      :fhir/lifecycle (presenting-lifecycle calls)))
        [entry] (get-in resp [:body :entry])]
    (is (= 200 (:status resp)))
    (is (= presented-text (get-in entry [:resource :text])))
    (is (contains? (set (map :url (:extension entry))) (str tmp/extension-base "valid-from"))
        "the temporal-bound extensions sit on the entry, untouched")
    (is (= [[:timeline "Coverage"]]
           (mapv (fn [[read _]] [(:interaction read) (:resource-type read)]) @calls)))))

;; ---------------------------------------------------------------------------
;; Canonicals: dromon names no deployment, the host supplies its own
;; ---------------------------------------------------------------------------

(def ^:private host-canonicals
  {:basis-tag-system "https://host.example/fhir/CodeSystem/basis"
   :extension-base   "https://host.example/fhir/StructureDefinition/host-"})

(def ^:private default-tag-system "http://localhost/fhir/CodeSystem/temporal-basis")
(def ^:private default-extension-base "http://localhost/fhir/StructureDefinition/temporal-")

(defn- tag-systems [body]
  (set (map :system (get-in body [:meta :tag]))))

(defn- extension-urls [body]
  (set (map :url (:extension (first (:entry body))))))

(defn- every-temporal-answer
  "The four places a temporal response states a canonical, each answered by the
   real handler for `request-fn` (a function from a request to a request): the
   search Bundle on both of its branches (`_count=0` and the paged one),
   `$as-of`, and `$timeline`. Returns {:search-page .. :search-count .. :as-of ..
   :timeline ..} of response bodies."
  [request-fn]
  (let [store #(->BitemporalStore (atom []))
        sel {"_asOf" "2026-10-01T06:00:00Z" "_validAt" "2026-09-30"}]
    {:search-page  (:body (handlers/search-type (request-fn (req (store) sel))))
     :search-count (:body (handlers/search-type (request-fn (req (store) (assoc sel "_count" "0")))))
     :as-of        (:body (handlers/resource-as-of (request-fn (req (store) sel :id "x"))))
     :timeline     (:body (handlers/resource-timeline (request-fn (req (store) {} :id "x"))))}))

(deftest without-host-canonicals-responses-state-neutral-localhost-ones
  (let [{:keys [search-page search-count as-of timeline]} (every-temporal-answer identity)]
    (testing "every meta.tag names the default CodeSystem"
      (doseq [body [search-page search-count as-of]]
        (is (= #{default-tag-system} (tag-systems body)))
        (is (= #{"system-time" "valid-time"} (set (map :code (get-in body [:meta :tag])))))))
    (testing "the timeline extensions sit under the default base"
      (is (= #{(str default-extension-base "system-from")
               (str default-extension-base "valid-from")
               (str default-extension-base "valid-to")}
             (extension-urls timeline))))))

(deftest host-canonicals-reach-every-temporal-response
  (let [{:keys [search-page search-count as-of timeline]}
        (every-temporal-answer #(assoc % :fhir/temporal-canonicals host-canonicals))]
    (testing "search, on the paged branch and on the _count=0 branch, and $as-of"
      (doseq [[label body] {:search-page search-page :search-count search-count :as-of as-of}]
        (is (= #{(:basis-tag-system host-canonicals)} (tag-systems body)) (name label))
        (is (= #{"system-time" "valid-time"} (set (map :code (get-in body [:meta :tag]))))
            (name label))))
    (testing "$timeline"
      (is (= #{"https://host.example/fhir/StructureDefinition/host-system-from"
               "https://host.example/fhir/StructureDefinition/host-valid-from"
               "https://host.example/fhir/StructureDefinition/host-valid-to"}
             (extension-urls timeline))))))

(deftest a-host-may-supply-one-canonical-and-keep-the-other-default
  (testing "only the CodeSystem"
    (let [{:keys [search-page timeline]}
          (every-temporal-answer
           #(assoc % :fhir/temporal-canonicals
                   (select-keys host-canonicals [:basis-tag-system])))]
      (is (= #{(:basis-tag-system host-canonicals)} (tag-systems search-page)))
      (is (contains? (extension-urls timeline) (str default-extension-base "system-from")))))
  (testing "only the extension base"
    (let [{:keys [search-page timeline]}
          (every-temporal-answer
           #(assoc % :fhir/temporal-canonicals
                   (select-keys host-canonicals [:extension-base])))]
      (is (= #{default-tag-system} (tag-systems search-page)))
      (is (contains? (extension-urls timeline)
                     (str (:extension-base host-canonicals) "system-from"))))))

(deftest check-canonicals-refuses-what-would-silently-fall-back
  (is (nil? (tmp/check-canonicals nil)))
  (is (= host-canonicals (tmp/check-canonicals host-canonicals)))
  (testing "a misspelled key would otherwise serve the default with no error"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":basis-tag-sytem"
                          (tmp/check-canonicals {:basis-tag-sytem "https://host.example/x"}))))
  (testing "a value that is not a usable string"
    (doseq [bad [nil "" "  " :kw 5]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (tmp/check-canonicals {:basis-tag-system bad}))
          (pr-str bad))))
  (testing "not a map"
    (is (thrown? clojure.lang.ExceptionInfo (tmp/check-canonicals "https://host.example/x")))))

(deftest the-router-injects-the-canonicals
  (let [resolved (router/resolve-options {:temporal-canonicals host-canonicals})
        entry (some #(when (= ::router/temporal-canonicals (:name %)) %)
                    (router/default-middleware (mock/create-mock-store {}) resolved))
        seen (atom nil)]
    (is (= host-canonicals (:temporal-canonicals resolved)))
    (is (some? entry))
    (((:wrap entry) (fn [request] (reset! seen (:fhir/temporal-canonicals request)))) {})
    (is (= host-canonicals @seen))
    (is (nil? (some #(when (= ::router/temporal-canonicals (:name %)) %)
                    (router/default-middleware (mock/create-mock-store {})
                                               (router/resolve-options {}))))
        "absent without the option: the default stack is unchanged")
    (is (thrown? clojure.lang.ExceptionInfo
                 (router/resolve-options {:temporal-canonicals {:extension-bas "https://host.example/x"}}))
        "a malformed option fails at startup, not on the first response")))

(deftest the-jetty-component-forwards-the-canonicals-to-the-app
  (let [seen (atom nil)]
    (with-redefs [core/fhir-app (fn [store schemas & kvs]
                                  (reset! seen {:store store :schemas schemas :opts (apply hash-map kvs)})
                                  ::app)
                  jetty/run-jetty (fn [app _] {:app app})]
      (ig/init-key :server/jetty {:port 0
                                  :store ::store
                                  :schemas []
                                  :temporal-canonicals host-canonicals}))
    (is (= host-canonicals (get-in @seen [:opts :temporal-canonicals])))
    (is (= ::store (:store @seen)))))
