(ns fhir-store.http.fake-remote
  "A FHIR REST server as an `:http/request` function, backed by the mock store,
   so the HTTP store's mapping is tested without sockets.

   It behaves like a GCP Healthcare store where that matters to the mapping:
   pages are linked by an opaque `_page_token` and there is no offset
   parameter, deleted resources read as 410, `Prefer: handling=strict` refuses
   an unknown parameter (any name containing `bogus`), and `If-None-Match: *`
   on PUT is honoured. `:ignore-if-none-match? true` makes it behave like
   dromon's fhir-server instead, which ignores that header on PUT.

   Every request is recorded in `(:requests remote)`."
  (:require [clojure.string :as str]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as fp]
            [jsonista.core :as json])
  (:import (java.net URI URLDecoder)
           (java.nio.charset StandardCharsets)))

(def ^:private mapper (json/object-mapper {:decode-key-fn true :bigdecimals true}))

(defn- respond
  ([status] {:status status :headers {} :body nil})
  ([status body] {:status status :headers {} :body (json/write-value-as-string body mapper)}))

(defn- outcome [status code]
  (respond status {:resourceType "OperationOutcome"
                   :issue [{:severity "error" :code code :diagnostics "SECRET-DIAGNOSTICS"}]}))

(defn- parse-query [q]
  (when-not (str/blank? q)
    (reduce (fn [acc pair]
              (let [[k v] (map #(URLDecoder/decode % StandardCharsets/UTF_8)
                               (str/split pair #"=" 2))]
                (update acc k (fn [old] (cond (nil? old) v
                                              (vector? old) (conj old v)
                                              :else [old v])))))
            {}
            (str/split q #"&"))))

(defn- etag [h]
  (some-> h (str/replace #"^W/" "") (str/replace "\"" "")))

(defn- page
  "One searchset/history page of `resources` starting at `offset`, with a
   `next` link carrying the following offset as an opaque token."
  [base path params resources offset size type]
  (let [rows (take size (drop offset resources))
        more (< (+ offset size) (count resources))
        mode (if (= type "searchset") {:search {:mode "match"}} {})]
    {:resourceType "Bundle"
     :type type
     :total (count resources)
     :entry (mapv #(if (fp/deleted-version? %)
                     {:request {:method "DELETE" :url (str (:resourceType %) "/" (:id %))}}
                     (merge {:resource %} mode))
                  rows)
     :link (cond-> []
             more (conj {:relation "next"
                         :url (str base "/" path "?"
                                   (str/join "&" (map (fn [[k v]] (str k "=" v))
                                                      (assoc (dissoc params "_page_token")
                                                             "_page_token" (str "t" (+ offset size))))))}))}))

(defn- apply-entry
  "One transaction entry against the mock: [response-entry]."
  [{:keys [store ignore-if-none-match?]} tenant {:keys [request resource]}]
  (let [[rt id] (str/split (:url request) #"/")
        rtk (keyword rt)]
    (case (:method request)
      "PUT" (let [exists (fp/read-resource store tenant rtk id)]
              (when (and exists (= "*" (:ifNoneMatch request)) (not ignore-if-none-match?))
                (throw (ex-info "exists" {:fhir/status 412 :fhir/code "conflict"})))
              (if (and (nil? exists) (nil? (:ifMatch request)))
                {:resource (fp/create-resource store tenant rtk id resource)
                 :response {:status "201 Created"}}
                {:resource (fp/update-resource store tenant rtk id resource
                                               {:if-match (etag (:ifMatch request))})
                 :response {:status "200 OK"}}))
      "DELETE" (do (fp/delete-resource store tenant rtk id
                                       (when (:ifMatch request) {:if-match (etag (:ifMatch request))}))
                   {:response {:status "204 No Content"}})
      "GET" {:resource (fp/read-resource store tenant rtk id) :response {:status "200 OK"}})))

(defn- handle
  [{:keys [store ignore-if-none-match? page-size] :as remote} {:keys [method url headers body]}]
  (let [uri    (URI/create url)
        [_ tenant _fhir & segs] (str/split (.getPath uri) #"/")
        base   (str (.getScheme uri) "://" (.getAuthority uri) "/" tenant "/fhir")
        params (parse-query (.getRawQuery uri))
        body   (when body (json/read-value body mapper))
        [rt id third vid] segs
        rtk    (some-> rt keyword)]
    (case [method (count segs)]
      [:get 1]
      (cond
        (= "metadata" rt) (respond 200 {:resourceType "CapabilityStatement"})

        (and (= "handling=strict" (get headers "Prefer"))
             (some #(str/includes? % "bogus") (keys params)))
        (outcome 400 "not-supported")

        (= "count" (get params "_summary"))
        (respond 200 {:resourceType "Bundle" :type "searchset"
                      :total (count (fp/search store tenant rtk
                                               (assoc (dissoc params "_summary") "_count" "100000")
                                               {}))})

        :else
        (let [size   (or (some-> (get params "_count") parse-long) page-size)
              offset (or (some-> (get params "_page_token") (subs 1) parse-long) 0)
              filter (dissoc params "_count" "_page_token")
              found  (fp/search store tenant rtk (assoc filter "_count" "100000") {})]
          (respond 200 (page base rt params found offset size "searchset"))))

      [:get 2]
      (if (= "_history" id)
        (respond 200 (page base (str rt "/_history") params
                           (fp/history-type store tenant rtk params) 0 1000 "history"))
        (if-let [r (fp/read-resource store tenant rtk id)]
          (respond 200 r)
          (if (fp/resource-deleted? store tenant rtk id) (outcome 410 "deleted") (outcome 404 "not-found"))))

      [:get 3]
      (let [versions (fp/history store tenant rtk id)
            size     (or (some-> (get params "_count") parse-long) page-size)]
        (respond 200 (page base (str rt "/" id "/_history") params versions 0 size "history")))

      [:get 4]
      (if-let [r (fp/vread-resource store tenant rtk id vid)]
        (respond 200 r)
        (outcome 404 "not-found"))

      [:put 2]
      (let [exists (fp/read-resource store tenant rtk id)
            if-match (get headers "If-Match")]
        (cond
          (and exists (= "*" (get headers "If-None-Match")) (not ignore-if-none-match?))
          (outcome 412 "conflict")

          if-match
          (respond 200 (fp/update-resource store tenant rtk id body {:if-match (etag if-match)}))

          exists
          (respond 200 (fp/update-resource store tenant rtk id body))

          :else
          (respond 201 (fp/create-resource store tenant rtk id body))))

      [:delete 2]
      (let [if-match (get headers "If-Match")]
        (if (and if-match (nil? (fp/read-resource store tenant rtk id)))
          (outcome 412 "conflict")
          (do (fp/delete-resource store tenant rtk id
                                  (when if-match {:if-match (etag if-match)}))
              (respond 204))))

      [:post 0]
      (let [state    (:state store)
            snapshot @state]
        (try
          (respond 200 {:resourceType "Bundle"
                        :type "transaction-response"
                        :entry (mapv #(apply-entry remote tenant %) (:entry body))})
          (catch clojure.lang.ExceptionInfo e
            (reset! state snapshot)
            (throw e))))

      (outcome 400 "not-supported"))))

(defn fake-remote
  "{:store <mock store> :requests <atom> :transport <fn>}."
  ([] (fake-remote {}))
  ([opts]
   (let [remote (merge {:store (mock/create-mock-store {})
                        :requests (atom [])
                        :page-size 2}
                       opts)]
     (assoc remote
            :transport
            (fn [request]
              (swap! (:requests remote) conj request)
              (try
                (handle remote request)
                (catch clojure.lang.ExceptionInfo e
                  (outcome (or (:fhir/status (ex-data e)) 500)
                           (or (:fhir/code (ex-data e)) "exception")))))))))
