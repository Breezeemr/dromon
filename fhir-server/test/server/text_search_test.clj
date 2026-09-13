(ns server.text-search-test
  "The `_text` capability gate.

   `_text` never resolves in a registry (its SearchParameter has no
   expression), so without a gate every store would either report it as
   unknown or, worse, take it through `search` and ignore it. The handler
   therefore grants it per request, from the store: only a store that
   `satisfies?` ITextSearchStore AND answers `text-searchable?` true for the
   tenant and type may see it. Everything else must get the same 400 an
   unknown parameter gets, lenient handling included, and the decorators a
   patient token goes through must not pass the grant along."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [server.compartment :as compartment]
            [server.handlers :as handlers]))

(def ^:private tenant "default")

(def ^:private registry
  {"name" {:type "string" :columns [{:col "name" :array? true :sub-col "family"}]}})

;; A store fronting a full-text index for the types in `indexed-types`,
;; standing in for flotilla's IndexedStore. Reads and writes go to the mock
;; store underneath; every search is recorded so a test can see exactly which
;; parameters reached it.
(defrecord TextIndexedStore [base indexed-types calls]
  db/IFHIRStore
  (create-resource [_ tenant-id resource-type id resource]
    (db/create-resource base tenant-id resource-type id resource))
  (read-resource [_ tenant-id resource-type id]
    (db/read-resource base tenant-id resource-type id))
  (search [_ tenant-id resource-type params search-registry]
    (swap! calls conj [:search params])
    (db/search base tenant-id resource-type params search-registry))
  (count-resources [_ tenant-id resource-type params search-registry]
    (swap! calls conj [:count params])
    (db/count-resources base tenant-id resource-type params search-registry))
  db/ITextSearchStore
  (text-searchable? [_ _tenant-id resource-type]
    (contains? indexed-types resource-type)))

(defn- indexed-store
  "A TextIndexedStore whose index covers `indexed-types`, seeded with two
   Patients so a granted search has rows to return."
  [indexed-types]
  (let [base (mock/create-mock-store {})
        store (->TextIndexedStore base indexed-types (atom []))]
    (db/create-resource store tenant :Patient "p1"
                        {:resourceType "Patient" :id "p1" :name [{:family "Smith"}]})
    (db/create-resource store tenant :Patient "p2"
                        {:resourceType "Patient" :id "p2" :name [{:family "Jones"}]})
    store))

(defn- req [store params & {:keys [headers resource-type]
                            :or {headers {} resource-type "Patient"}}]
  {:fhir/store store
   :fhir/resource-type resource-type
   :fhir/search-registry registry
   :path-params {:tenant-id tenant}
   :query-params params
   :headers headers})

(defn- issues [resp]
  (get-in resp [:body :issue]))

(defn- issue-codes [resp]
  (set (map :code (issues resp))))

(defn- names-param?
  "True when every issue's details and diagnostics name `pname`."
  [issue-list pname]
  (and (seq issue-list)
       (every? (fn [{:keys [details diagnostics]}]
                 (and (str/includes? (:text details) (str "\"" pname "\""))
                      (str/includes? diagnostics (str "\"" pname "\""))))
               issue-list)))

(defn- searches
  "The parameter maps of every `search` call the indexed store saw."
  [store]
  (into [] (keep (fn [[verb params]] (when (= :search verb) params))) @(:calls store)))

;; ---------------------------------------------------------------------------
;; No capability at all
;; ---------------------------------------------------------------------------

(deftest store-without-an-index-refuses-text
  (let [store (mock/create-mock-store {})
        resp (handlers/search-type (req store {"_text" "smith"}))]
    (is (= 400 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))
    (is (= #{"not-supported"} (issue-codes resp)))
    (is (names-param? (issues resp) "_text")
        "the OperationOutcome names the parameter the client has to remove")
    (is (= "handling=strict" (get-in resp [:headers "Prefer"])))))

(deftest store-without-an-index-is-otherwise-unaffected
  (testing "a search that carries no _text neither consults nor needs the capability"
    (let [store (mock/create-mock-store {})]
      (db/create-resource store tenant :Patient "p1"
                          {:resourceType "Patient" :id "p1" :name [{:family "Smith"}]})
      (let [resp (handlers/search-type (req store {"name" "Smith"}))]
        (is (= 200 (:status resp)))
        (is (= 1 (count (get-in resp [:body :entry]))))))))

;; ---------------------------------------------------------------------------
;; The capability, granted
;; ---------------------------------------------------------------------------

(deftest indexed-store-takes-text-through-search
  (let [store (indexed-store #{:Patient})
        resp (handlers/search-type (req store {"_text" "smith" "_count" "10"}))]
    (is (= 200 (:status resp)))
    (is (= "searchset" (get-in resp [:body :type])))
    (is (= 1 (count (searches store))) "exactly one search ran")
    (let [params (first (searches store))]
      (is (= "smith" (get params "_text"))
          "_text reached the store's search untouched")
      (is (= 10 (:_count params))))
    (is (nil? (some #(= "outcome" (get-in % [:search :mode])) (get-in resp [:body :entry])))
        "a granted parameter is not an ignored one: no outcome entry")))

(deftest indexed-store-keeps-text-in-the-self-link
  (let [store (indexed-store #{:Patient})
        resp (handlers/search-type (req store {"_text" "smith"}))
        self (some #(when (= "self" (:relation %)) (:url %)) (get-in resp [:body :link]))]
    (is (= 200 (:status resp)))
    (is (str/includes? self "_text=smith")
        "the self link describes the search that actually ran")))

(deftest indexed-store-still-refuses-what-the-registry-lacks
  (testing "the grant is for _text alone; an unknown parameter beside it is still a 400"
    (let [store (indexed-store #{:Patient})
          resp (handlers/search-type (req store {"_text" "smith" "subject" "Patient/1"}))]
      (is (= 400 (:status resp)))
      (is (names-param? (issues resp) "subject"))
      (is (not (some #(str/includes? (:diagnostics %) "\"_text\"") (issues resp)))
          "_text is not among the refused parameters")
      (is (empty? (searches store)) "nothing reached the store"))))

(deftest indexed-store-refuses-a-modified-text
  (testing "an index answers bare _text; a modifier is a name it does not match"
    (let [store (indexed-store #{:Patient})
          resp (handlers/search-type (req store {"_text:exact" "smith"}))]
      (is (= 400 (:status resp)))
      (is (names-param? (issues resp) "_text:exact"))
      (is (empty? (searches store))))))

;; ---------------------------------------------------------------------------
;; The capability, declined for this tenant and type
;; ---------------------------------------------------------------------------

(deftest indexed-store-refuses-text-on-a-type-it-does-not-index
  (testing "satisfies? says the store CAN front an index; text-searchable? says whether it does here"
    (let [store (indexed-store #{:Organization})
          resp (handlers/search-type (req store {"_text" "smith"}))]
      (is (= 400 (:status resp)))
      (is (= #{"not-supported"} (issue-codes resp)))
      (is (names-param? (issues resp) "_text"))
      (is (empty? (searches store))
          "the store is asked whether it can, never asked to try"))))

;; ---------------------------------------------------------------------------
;; handling=lenient
;; ---------------------------------------------------------------------------

(deftest lenient-handling-drops-an-ungranted-text-with-the-outcome-entry
  (doseq [[label store] [["a store with no index" (mock/create-mock-store {})]
                         ["an indexed store on an unindexed type" (indexed-store #{:Organization})]]]
    (testing label
      (let [resp (handlers/search-type (req store {"_text" "smith"}
                                            :headers {"prefer" "handling=lenient"}))
            entries (get-in resp [:body :entry])
            outcome (some #(when (= "outcome" (get-in % [:search :mode])) (:resource %)) entries)]
        (is (= 200 (:status resp)))
        (is (some? outcome) "the searchset carries the warning, per R4B 3.1.1.4")
        (is (= #{"warning"} (set (map :severity (:issue outcome)))))
        (is (names-param? (:issue outcome) "_text"))
        (when (instance? TextIndexedStore store)
          (is (= 1 (count (searches store))))
          (is (not (contains? (first (searches store)) "_text"))
              "the dropped parameter never reaches the store"))))))

(deftest lenient-handling-changes-nothing-for-a-granted-text
  (let [store (indexed-store #{:Patient})
        resp (handlers/search-type (req store {"_text" "smith"}
                                        :headers {"prefer" "handling=lenient"}))]
    (is (= 200 (:status resp)))
    (is (= "smith" (get (first (searches store)) "_text")))
    (is (nil? (some #(= "outcome" (get-in % [:search :mode])) (get-in resp [:body :entry]))))))

;; ---------------------------------------------------------------------------
;; Decorators and other interactions never pass the grant along
;; ---------------------------------------------------------------------------

(deftest compartment-filtering-store-does-not-carry-the-grant
  (testing "a patient token wraps the store in CompartmentFilteringStore, which
            implements only IFHIRStore: the 400 stands even over an indexed base"
    (let [base (indexed-store #{:Patient})
          store (compartment/filtering-store base {:patient-id "p1"
                                                   :all-registries {"Patient" registry}})]
      (is (not (satisfies? db/ITextSearchStore store)))
      (let [resp (handlers/search-type (req store {"_text" "smith"}))]
        (is (= 400 (:status resp)))
        (is (names-param? (issues resp) "_text"))
        (is (empty? (searches base)))))))

(deftest conditional-create-does-not-grant-text
  (testing "conditional interactions select a resource to mutate and take no capability grant"
    (let [store (indexed-store #{:Patient})
          resp (handlers/create-resource
                (assoc (req store {})
                       :headers {"if-none-exist" "_text=smith"}
                       :parameters {:body {:resourceType "Patient" :name [{:family "Smith"}]}}))]
      (is (= 400 (:status resp)))
      (is (names-param? (issues resp) "_text"))
      (is (empty? (searches store))))))

;; ---------------------------------------------------------------------------
;; Paging a search whose page can come back short
;; ---------------------------------------------------------------------------

;; A store that answers `_text` from an index and then reads each hit back
;; from the record store, as flotilla's IndexedStore does: `missing` is a hit
;; whose resource is gone, so a FULL index page comes back one row short while
;; the match set goes on. `count-resources` answers the index's exact total,
;; which is the number the Bundle has to carry.
(defrecord DroppingIndexStore [ids missing]
  db/IFHIRStore
  (search [_ _tenant-id _resource-type params _registry]
    (into []
          (comp (drop (:_skip params))
                (take (:_count params))
                (remove #{missing})
                (map (fn [id] {:resourceType "Patient" :id id})))
          ids))
  (count-resources [_ _tenant-id _resource-type _params _registry]
    (count ids))
  db/ITextSearchStore
  (text-searchable? [_ _tenant-id _resource-type] true))

(defn- link-url [resp relation]
  (some #(when (= relation (:relation %)) (:url %)) (get-in resp [:body :link])))

(defn- query-params
  "The query string of a link as the parameters middleware would hand it to a
   handler: a repeated name becomes a vector, which is exactly what makes a
   duplicated `_count` a 400 on the way back in."
  [url]
  (reduce (fn [params pair]
            (let [[k v] (str/split pair #"=" 2)]
              (update params k (fn [seen] (cond (nil? seen) v
                                                (vector? seen) (conj seen v)
                                                :else [seen v])))))
          {}
          (str/split (second (str/split url #"\?" 2)) #"&")))

(deftest a-page-short-because-a-hit-was-dropped-is-not-the-last-page
  (testing "the total is the match set's and a next link still follows"
    (let [store (->DroppingIndexStore ["a" "b" "c" "d"] "a")
          resp (handlers/search-type (req store {"_text" "smith" "_count" "2"}))]
      (is (= 200 (:status resp)))
      (is (= ["b"] (mapv (comp :id :resource) (get-in resp [:body :entry])))
          "the hit whose resource is gone is not in the page")
      (is (= 4 (get-in resp [:body :total]))
          "short pages are the last page for a store that returns what it has;
           a full-text store's page can be short with three matches to go")
      (is (some? (link-url resp "next"))
          "and the rest of the match set stays reachable"))))

(deftest a-next-link-states-the-paging-once
  (let [store (->DroppingIndexStore ["a" "b" "c" "d"] "a")
        resp (handlers/search-type (req store {"_text" "smith" "_count" "2"}))
        next-url (link-url resp "next")]
    (is (= 1 (count (re-seq #"_count=" next-url)))
        "the request's own _count must not be echoed beside the parsed one")
    (is (= 1 (count (re-seq #"_skip=" next-url))))
    (testing "so the link a client follows is answered, not refused"
      (let [replay (handlers/search-type (req store (query-params next-url)))]
        (is (= 200 (:status replay)))
        (is (= ["c" "d"] (mapv (comp :id :resource) (get-in replay [:body :entry])))
            "page two: the rows the first page could not reach")))))
