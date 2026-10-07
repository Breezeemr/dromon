(ns fhir-store.http.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [fhir-store.http.core :as http]
            [fhir-store.http.fake-remote :as fake]
            [fhir-store.lifecycle :as lc]
            [fhir-store.protocol :as fp]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.experimental.time :as met]
            [malli.registry :as mr]))

(def ^:private tenant "t1")
(def ^:private base "http://remote.test/{tenant}/fhir")

(defn- store-over
  ([remote] (store-over remote {}))
  ([remote opts]
   (http/create-http-store (merge {:base-url base :http/request (:transport remote)} opts))))

(defn- thrown-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (assoc (ex-data e) ::message (ex-message e)))))

(defn- requests [remote] @(:requests remote))

(defn- reset-requests! [remote] (reset! (:requests remote) []))

(deftest read-write-round-trip
  (let [remote (fake/fake-remote)
        store  (store-over remote)
        created (fp/create-resource store tenant :Patient "p1" {:resourceType "Patient" :gender "female"})]
    (is (= "p1" (:id created)))
    (is (= "1" (get-in created [:meta :versionId])))
    (is (= "female" (:gender (fp/read-resource store tenant :Patient "p1"))))
    (testing "a matching If-Match writes a new version"
      (let [updated (fp/update-resource store tenant :Patient "p1"
                                        {:resourceType "Patient" :gender "male"} {:if-match "W/\"1\""})]
        (is (= "2" (get-in updated [:meta :versionId])))
        (is (= "W/\"1\"" (get-in (last (requests remote)) [:headers "If-Match"])))))
    (testing "a stale If-Match is the protocol's 412 conflict"
      (let [data (thrown-data #(fp/update-resource store tenant :Patient "p1"
                                                   {:resourceType "Patient"} {:if-match "1"}))]
        (is (= 412 (:fhir/status data)))
        (is (= "conflict" (:fhir/code data)))
        (is (= "1" (:expected data)))))
    (is (= "female" (:gender (fp/vread-resource store tenant :Patient "p1" "1"))))
    (is (= 2 (count (fp/history store tenant :Patient "p1"))))
    (is (= {} (fp/delete-resource store tenant :Patient "p1")))
    (is (nil? (fp/read-resource store tenant :Patient "p1")))
    (is (true? (fp/resource-deleted? store tenant :Patient "p1")))
    (is (false? (fp/resource-deleted? store tenant :Patient "never")))))

(deftest create-without-an-id-mints-one-and-guards-the-put
  (let [remote  (fake/fake-remote)
        store   (store-over remote)
        created (fp/create-resource store tenant :Patient nil {:resourceType "Patient"})
        put     (last (requests remote))]
    (is (parse-uuid (:id created)))
    (is (= :put (:method put)))
    (is (str/ends-with? (:url put) (str "/t1/fhir/Patient/" (:id created))))
    (is (= "*" (get-in put [:headers "If-None-Match"])))
    (is (= "return=representation" (get-in put [:headers "Prefer"])))))

(deftest create-under-an-existing-id-is-refused
  (let [remote (fake/fake-remote)
        store  (store-over remote)]
    (fp/create-resource store tenant :Patient "p1" {:resourceType "Patient"})
    (is (= 409 (:fhir/status (thrown-data #(fp/create-resource store tenant :Patient "p1"
                                                               {:resourceType "Patient"})))))))

(deftest a-remote-that-ignores-if-none-match-and-updates-is-reported
  ;; The race the pre-read cannot close: the resource appears between the
  ;; read and the PUT, on a remote that ignores If-None-Match (dromon).
  (let [remote (fake/fake-remote {:ignore-if-none-match? true})
        racing (fn [req]
                 (if (and (= :get (:method req)) (str/ends-with? (:url req) "/Patient/p1"))
                   {:status 404 :headers {} :body nil}
                   ((:transport remote) req)))
        store  (http/create-http-store {:base-url base :http/request racing})]
    (fp/create-resource (store-over remote) tenant :Patient "p1" {:resourceType "Patient"})
    (let [data (thrown-data #(fp/create-resource store tenant :Patient "p1" {:resourceType "Patient"}))]
      (is (= 500 (:fhir/status data)))
      (is (str/includes? (::message data) "ignored If-None-Match")))))

(defn- seed-patients! [store n]
  (doseq [i (range n)]
    (fp/create-resource store tenant :Patient (str "p" i)
                        {:resourceType "Patient" :gender (if (even? i) "female" "male")})))

(deftest search-pages-by-walking-next-links
  (let [remote (fake/fake-remote {:page-size 2})
        store  (store-over remote)]
    (seed-patients! store 7)
    (reset-requests! remote)
    (let [found (fp/search store tenant :Patient {:_count 2 :_skip 3} {})]
      (is (= ["p3" "p4"] (mapv :id found)))
      (is (every? #(= "handling=strict" (get-in % [:headers "Prefer"])) (requests remote)))
      (is (every? #(str/includes? (:url %) "remote.test/t1/fhir") (requests remote))))
    (testing "filters are forwarded, server-applied parameters are not"
      (reset-requests! remote)
      (let [found (fp/search store tenant :Patient {"gender" "female" "_include" "Patient:link"
                                                    :_count 10 :_skip 0} {})]
        (is (= ["p0" "p2" "p4" "p6"] (mapv :id found)))
        (is (not (str/includes? (:url (first (requests remote))) "_include")))))
    (testing "an offset parameter skips on the remote instead"
      (reset-requests! remote)
      (let [found (fp/search (store-over remote {:offset-param "_skip"})
                             tenant :Patient {:_count 2 :_skip 3} {})]
        (is (= ["p3" "p4"] (mapv :id found)))
        (is (= 1 (count (requests remote))))
        (is (str/includes? (:url (first (requests remote))) "_skip=3"))))
    (is (= 4 (fp/count-resources store tenant :Patient {"gender" "female"} {})))))

(deftest search-never-runs-without-a-filter-it-was-given
  (let [remote (fake/fake-remote)
        store  (store-over remote)]
    (testing "an unknown parameter is the remote's refusal, not a wider result"
      (is (= 400 (:fhir/status (thrown-data #(fp/search store tenant :Patient {"bogus" "x"} {}))))))
    (testing "_compartment is refused before any request"
      (reset-requests! remote)
      (is (= 501 (:fhir/status (thrown-data #(fp/search store tenant :Patient
                                                        {"_compartment" "Patient/1"} {})))))
      (is (empty? (requests remote))))))

(deftest a-next-link-off-the-tenant-base-is-refused
  (let [seen      (atom [])
        transport (fn [req]
                    (swap! seen conj (:url req))
                    {:status 200 :headers {}
                     :body (json/write-value-as-string
                            {:resourceType "Bundle" :type "searchset"
                             :entry [{:resource {:resourceType "Patient" :id "a"}}]
                             :link [{:relation "next" :url "http://elsewhere.test/t1/fhir/Patient?p=2"}]})})
        store     (http/create-http-store {:base-url base :http/request transport
                                           :authorization "Bearer secret"})]
    (is (= 502 (:fhir/status (thrown-data #(fp/search store tenant :Patient {:_count 5} {})))))
    (is (= 1 (count @seen)) "the Authorization header never went to the other host")))

(deftest a-relative-next-link-resolves-against-the-base
  (let [seen      (atom [])
        transport (fn [req]
                    (swap! seen conj (:url req))
                    {:status 200 :headers {}
                     :body (json/write-value-as-string
                            {:resourceType "Bundle" :type "searchset"
                             :entry [{:resource {:resourceType "Patient" :id (str (count @seen))}}]
                             :link (if (= 1 (count @seen))
                                     [{:relation "next" :url "/t1/fhir/Patient?_skip=1"}]
                                     [])})})
        store     (http/create-http-store {:base-url base :http/request transport})]
    (is (= ["1" "2"] (mapv :id (fp/search store tenant :Patient {:_count 5} {}))))
    (is (= "http://remote.test/t1/fhir/Patient?_skip=1" (second @seen)))
    (testing "a relative link climbing out of the base is still refused"
      (reset! seen [])
      (let [escaping (fn [req]
                       (swap! seen conj (:url req))
                       {:status 200 :headers {}
                        :body (json/write-value-as-string
                               {:resourceType "Bundle" :type "searchset" :entry []
                                :link [{:relation "next" :url "/other/fhir/Patient"}]})})]
        (is (= 502 (:fhir/status (thrown-data #(fp/search (http/create-http-store
                                                           {:base-url base :http/request escaping})
                                                          tenant :Patient {:_count 5} {})))))))))

(deftest transaction-bundles-are-atomic-on-the-remote
  (let [remote (fake/fake-remote)
        store  (store-over remote)]
    (fp/create-resource store tenant :Patient "p1" {:resourceType "Patient"})
    (testing "a failing guard leaves nothing written"
      (let [data (thrown-data
                  #(fp/transact-transaction
                    store tenant
                    [{:request {:method "POST" :url "Patient"} :resource {:resourceType "Patient"}}
                     {:request {:method "PUT" :url "Patient/p1" :ifMatch "W/\"9\""}
                      :resource {:resourceType "Patient"}}]))]
        (is (= 412 (:fhir/status data)))
        (is (= 1 (count (fp/search (:store remote) tenant :Patient {} {}))))))
    (testing "a landing Bundle answers transaction-response in entry order"
      (let [res (fp/transact-transaction
                 store tenant
                 [{:fullUrl "urn:uuid:x" :request {:method "POST" :url "Patient"}
                   :resource {:resourceType "Patient"}}
                  {:request {:method "PUT" :url "Patient/p1" :ifMatch "1"}
                   :resource {:resourceType "Patient" :gender "male"}}])]
        (is (= "transaction-response" (:type res)))
        (is (= ["201 Created" "200 OK"] (mapv #(get-in % [:response :status]) (:entry res))))
        (is (= 2 (count (fp/search (:store remote) tenant :Patient {} {}))))))))

(deftest batch-entries-fail-independently
  (let [remote (fake/fake-remote)
        store  (store-over remote)
        res    (fp/transact-bundle
                store tenant
                [{:request {:method "PUT" :url "Patient/a"} :resource {:resourceType "Patient"}}
                 {:request {:method "PUT" :url "Patient/a" :ifMatch "W/\"7\""}
                  :resource {:resourceType "Patient"}}
                 {:request {:method "GET" :url "Patient/a"}}])]
    (is (= "batch-response" (:type res)))
    (is (= ["200 OK" "412" "200 OK"] (mapv #(get-in % [:response :status]) (:entry res))))))

(deftest remote-diagnostics-never-reach-the-exception
  (let [remote (fake/fake-remote)
        store  (store-over remote)
        data   (thrown-data #(fp/search store tenant :Patient {"bogus" "x"} {}))]
    (is (not (str/includes? (pr-str data) "SECRET-DIAGNOSTICS")))
    (is (= [{:severity "error" :code "not-supported"}] (:fhir-store.http/remote-issues data)))))

(defn- recording-lifecycle [calls]
  (reify lc/IWriteLifecycle
    (prepare [_ write]
      (swap! calls conj [:prepare (:resource-type write) (:method write) (:id write)])
      (when (= "refuse" (get-in write [:resource :language]))
        (throw (ex-info "refused" {:fhir/status 422 :fhir/code "business-rule"})))
      (assoc (:resource write) :language "prepared"))
    (tx-ops [_ write]
      (when (= "Patient" (:resource-type write))
        [{:request  {:method "PUT" :url (str "Basic/for-" (:id write))}
          :resource {:resourceType "Basic" :id (str "for-" (:id write))
                     :code {:text (:language (:resource write))}}}]))
    (after-commit [_ commit]
      (swap! calls conj [:after-commit (mapv (juxt :resource-type :id) (:writes commit))]))))

(deftest lifecycle-runs-around-remote-writes
  (let [remote (fake/fake-remote)
        calls  (atom [])
        store  (store-over remote {:resource/lifecycle (recording-lifecycle calls)})]
    (testing "tx-ops ride one transaction Bundle with the write"
      (let [created (fp/create-resource store tenant :Patient "p1" {:resourceType "Patient"})]
        (is (= "prepared" (:language created)))
        (is (= "prepared" (get-in (fp/read-resource (:store remote) tenant :Basic "for-p1")
                                  [:code :text])))
        (is (= [[:prepare "Patient" :create "p1"] [:after-commit [["Patient" "p1"]]]] @calls))
        (is (= [:post] (->> (requests remote) (map :method) (remove #{:get}))))))
    (testing "a refusal from prepare sends no write"
      (reset! calls [])
      (reset-requests! remote)
      (is (= 422 (:fhir/status (thrown-data #(fp/update-resource store tenant :Patient "p1"
                                                                 {:resourceType "Patient"
                                                                  :language "refuse"})))))
      (is (empty? (remove #(= :get (:method %)) (requests remote))))
      (is (not-any? #(= :after-commit (first %)) @calls)))
    (testing "a write the remote refuses fires no after-commit"
      (reset! calls [])
      (is (= 412 (:fhir/status (thrown-data #(fp/update-resource store tenant :Patient "p1"
                                                                 {:resourceType "Patient"}
                                                                 {:if-match "9"})))))
      (is (not-any? #(= :after-commit (first %)) @calls)))
    (testing "a transaction fires after-commit once, with every write"
      (reset! calls [])
      (fp/transact-transaction store tenant
                               [{:request {:method "PUT" :url "Patient/p2"} :resource {:resourceType "Patient"}}
                                {:request {:method "PUT" :url "Patient/p3"} :resource {:resourceType "Patient"}}])
      (is (= [[:after-commit [["Patient" "p2"] ["Patient" "p3"]]]]
             (filterv #(= :after-commit (first %)) @calls)))
      (is (some? (fp/read-resource (:store remote) tenant :Basic "for-p3"))))))

(deftest bodies-are-encoded-for-the-wire
  (let [remote   (fake/fake-remote)
        registry (mr/composite-registry (m/default-schemas) (met/schemas))
        schema   (m/schema [:map {:resourceType "Patient"}
                            [:resourceType :string]
                            [:birthDate {:optional true} :time/local-date]]
                           {:registry registry})
        body     #(-> (requests remote) last :body (json/read-value json/keyword-keys-object-mapper))]
    (fp/update-resource (store-over remote {:resource/schemas [schema]}) tenant :Patient "p1"
                        {:resourceType "Patient" :birthDate (java.time.LocalDate/of 1990 2 3)})
    (is (= "1990-02-03" (:birthDate (body))))
    (testing "a type with no schema still writes java.time as ISO strings"
      (fp/update-resource (store-over remote) tenant :Observation "o1"
                          {:resourceType "Observation"
                           :effectiveInstant (java.time.Instant/parse "2026-10-07T12:00:00Z")})
      (is (= "2026-10-07T12:00:00Z" (:effectiveInstant (body)))))))

(deftest authorization-is-asked-for-per-request
  (let [remote (fake/fake-remote)
        n      (atom 0)
        store  (store-over remote {:authorization #(str "Bearer t" (swap! n inc))})]
    (fp/read-resource store tenant :Patient "x")
    (fp/read-resource store tenant :Patient "x")
    (is (= ["Bearer t1" "Bearer t2"] (mapv #(get-in % [:headers "Authorization"]) (requests remote))))))

(deftest identifiers-cannot-reshape-the-url
  (let [remote (fake/fake-remote)
        store  (store-over remote)]
    (doseq [f [#(fp/read-resource store "../other" :Patient "p1")
               #(fp/read-resource store tenant :Patient "p1?_format=xml")
               #(fp/read-resource store tenant (keyword "Patient/x") "p1")
               #(fp/vread-resource store tenant :Patient "p1" "1/../2")]]
      (is (= 400 (:fhir/status (thrown-data f)))))
    (is (empty? (requests remote)))))

(deftest what-a-rest-server-cannot-answer-is-a-501
  (let [remote (fake/fake-remote)
        store  (store-over remote)]
    (doseq [f [#(fp/current-basis store tenant)
               #(fp/scan-type-as-of store tenant :Patient {})
               #(fp/count-as-of store tenant :Patient {})
               #(fp/delete-tenant store tenant {:if-absent :ignore})
               #(fp/create-tenant store tenant)]]
      (is (= 501 (:fhir/status (thrown-data f)))))
    (is (not (fp/supports-tx-metadata? store)))
    (testing "create-tenant :ignore probes the remote"
      (reset-requests! remote)
      (is (nil? (fp/create-tenant store tenant {:if-exists :ignore})))
      (is (str/ends-with? (:url (first (requests remote))) "/t1/fhir/metadata")))))

(deftest base-url-must-name-the-tenant
  (is (thrown? clojure.lang.ExceptionInfo
               (http/create-http-store {:base-url "http://remote.test/fhir"})))
  (is (some? (http/create-http-store {:base-url (fn [_] "http://one.test/fhir")}))))
