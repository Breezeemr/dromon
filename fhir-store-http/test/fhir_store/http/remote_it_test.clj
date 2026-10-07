(ns fhir-store.http.remote-it-test
  "The HTTP store against a real FHIR server. Skipped unless
   FHIR_STORE_HTTP_IT_BASE names one, as a base-url template carrying
   `{tenant}`:

     FHIR_STORE_HTTP_IT_BASE='http://localhost:8080/{tenant}/fhir'
     FHIR_STORE_HTTP_IT_TENANT=default            ; default `default`
     FHIR_STORE_HTTP_IT_AUTHORIZATION='Bearer …'  ; optional, the whole header
                                                  ; (dromon's dev HS256 mode
                                                  ; wants `Token <jwt>`)
     FHIR_STORE_HTTP_IT_OFFSET_PARAM=_skip        ; optional, else link walking

   Every resource it writes has a fresh id, so it can run against a shared
   server, and it leaves those resources behind."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.http.core :as http]
            [fhir-store.protocol :as fp]))

(def ^:private base (System/getenv "FHIR_STORE_HTTP_IT_BASE"))
(def ^:private tenant (or (System/getenv "FHIR_STORE_HTTP_IT_TENANT") "default"))

(defn- store []
  (http/create-http-store
   {:base-url      base
    :authorization (System/getenv "FHIR_STORE_HTTP_IT_AUTHORIZATION")
    :offset-param  (System/getenv "FHIR_STORE_HTTP_IT_OFFSET_PARAM")}))

(defn- status-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:fhir/status (ex-data e)))))

(defn- fresh [] (str "it-" (random-uuid)))

(deftest remote-round-trip
  (when base
    (let [s   (store)
          id  (fresh)
          family (fresh)]
      (fp/warmup-tenant s tenant)
      (let [created (fp/create-resource s tenant :Patient id
                                        {:resourceType "Patient" :gender "female"
                                         :name [{:family family}]})]
        (is (= id (:id created)))
        (is (some? (get-in created [:meta :versionId]))))
      (is (= 409 (status-of #(fp/create-resource s tenant :Patient id {:resourceType "Patient"})))
          "a caller-assigned id that exists is refused")
      (let [vid     (get-in (fp/read-resource s tenant :Patient id) [:meta :versionId])
            updated (fp/update-resource s tenant :Patient id
                                        {:resourceType "Patient" :id id :gender "male"
                                         :name [{:family family}]}
                                        {:if-match vid})]
        (is (= "male" (:gender updated)))
        (is (= 412 (status-of #(fp/update-resource s tenant :Patient id
                                                   {:resourceType "Patient" :id id}
                                                   {:if-match vid}))))
        (is (= "female" (:gender (fp/vread-resource s tenant :Patient id vid)))))
      (is (= 2 (count (fp/history s tenant :Patient id))))
      (testing "search and count"
        (doseq [_ (range 4)]
          (fp/create-resource s tenant :Patient nil {:resourceType "Patient"
                                                     :name [{:family family}]}))
        (let [all  (fp/search s tenant :Patient {"family" family :_count 50} {})
              page (fp/search s tenant :Patient {"family" family :_count 2 :_skip 3} {})]
          (is (= 5 (count all)))
          (is (= (subvec (mapv :id all) 3 5) (mapv :id page)))
          (is (= 5 (fp/count-resources s tenant :Patient {"family" family} {})))))
      (fp/delete-resource s tenant :Patient id)
      (is (nil? (fp/read-resource s tenant :Patient id)))
      (is (true? (fp/resource-deleted? s tenant :Patient id)))
      (is (false? (fp/resource-deleted? s tenant :Patient (fresh)))))))

(deftest remote-bundles
  (when base
    (let [s  (store)
          id (fresh)]
      (fp/create-resource s tenant :Patient id {:resourceType "Patient"})
      (testing "a transaction with a failing guard writes nothing"
        (let [created (fresh)]
          (is (= 412 (status-of
                      #(fp/transact-transaction
                        s tenant
                        [{:request {:method "PUT" :url (str "Patient/" created)}
                          :resource {:resourceType "Patient" :id created}}
                         {:request {:method "PUT" :url (str "Patient/" id) :ifMatch "W/\"99\""}
                          :resource {:resourceType "Patient" :id id}}]))))
          (is (nil? (fp/read-resource s tenant :Patient created)))))
      (testing "a batch keeps going past a failed entry"
        (let [res (fp/transact-bundle
                   s tenant
                   [{:request {:method "PUT" :url (str "Patient/" id) :ifMatch "W/\"99\""}
                     :resource {:resourceType "Patient" :id id}}
                    {:request {:method "GET" :url (str "Patient/" id)}}])]
          (is (= ["412" "200 OK"] (mapv #(get-in % [:response :status]) (:entry res)))))))))
