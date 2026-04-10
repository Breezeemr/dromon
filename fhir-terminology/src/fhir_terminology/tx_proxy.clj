(ns fhir-terminology.tx-proxy
  (:require [fhir-terminology.protocol :as proto]
            [hato.client :as hc]
            [jsonista.core :as json]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(def ^:private json-mapper (json/object-mapper {:decode-key-fn keyword}))

(defrecord TxProxyBackend [base-url http-client]
  proto/ITerminologyService

  (expand-valueset [_ params]
    ;; Build query params from the FHIR $expand parameters
    (let [query-params (cond-> {}
                         (:url params) (assoc "url" (:url params))
                         (:filter params) (assoc "filter" (:filter params))
                         (:count params) (assoc "count" (str (:count params)))
                         (:offset params) (assoc "offset" (str (:offset params)))
                         (:context params) (assoc "context" (:context params))
                         (:contextDirection params) (assoc "contextDirection" (:contextDirection params)))
          url (str base-url "/ValueSet/$expand")
          resp (hc/get url {:query-params query-params
                            :as :string
                            :http-client http-client
                            :throw-exceptions? false})]
      (if (<= 200 (:status resp) 299)
        (json/read-value (:body resp) json-mapper)
        (throw (ex-info (str "Terminology server error: " (:status resp))
                        {:fhir/status (:status resp)
                         :fhir/code "exception"
                         :body (:body resp)})))))

  (lookup-code [_ params]
    (let [query-params (cond-> {}
                         (:system params) (assoc "system" (:system params))
                         (:code params) (assoc "code" (:code params))
                         (:version params) (assoc "version" (:version params)))
          url (str base-url "/CodeSystem/$lookup")
          resp (hc/get url {:query-params query-params
                            :as :string
                            :http-client http-client
                            :throw-exceptions? false})]
      (if (<= 200 (:status resp) 299)
        (json/read-value (:body resp) json-mapper)
        (throw (ex-info (str "Terminology server error: " (:status resp))
                        {:fhir/status (:status resp)
                         :fhir/code "exception"})))))

  (validate-code [_ params]
    (let [query-params (cond-> {}
                         (:url params) (assoc "url" (:url params))
                         (:system params) (assoc "system" (:system params))
                         (:code params) (assoc "code" (:code params))
                         (:display params) (assoc "display" (:display params)))
          url (str base-url "/ValueSet/$validate-code")
          resp (hc/get url {:query-params query-params
                            :as :string
                            :http-client http-client
                            :throw-exceptions? false})]
      (if (<= 200 (:status resp) 299)
        (json/read-value (:body resp) json-mapper)
        (throw (ex-info (str "Terminology server error: " (:status resp))
                        {:fhir/status (:status resp)
                         :fhir/code "exception"}))))))

(defn create-tx-proxy
  "Creates a terminology backend that proxies to an external FHIR terminology server."
  [base-url]
  (->TxProxyBackend base-url (hc/build-http-client {:connect-timeout 10000
                                                     :redirect-policy :normal})))

(defmethod ig/init-key :fhir-terminology/tx-proxy [_ {:keys [base-url]}]
  (let [url (or base-url (System/getenv "TX_SERVER_URL") "https://tx.fhir.org/r4")]
    (log/info "Starting terminology tx-proxy backend:" url)
    (create-tx-proxy url)))
