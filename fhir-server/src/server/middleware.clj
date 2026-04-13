(ns server.middleware
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as json]
            [taoensso.telemere :as t]
            [fhir-store.protocol :as fp])
  (:import [com.fasterxml.jackson.databind ObjectMapper SerializationFeature]
           [java.time Instant ZonedDateTime ZoneOffset]
           [java.time.format DateTimeFormatter]))

(def ^:private json-encoder (json/object-mapper {}))

(defn- build-operation-outcome [issues]
  {:resourceType "OperationOutcome"
   :issue issues})

(defn- error-response [status severity code diagnostics]
  {:status status
   :body (build-operation-outcome [{:severity severity
                                    :code code
                                    :diagnostics diagnostics}])})

(defn- json-error-response
  "Returns an error response with body pre-serialized to a JSON InputStream.
   Use this when short-circuiting before Muuntaja (which would otherwise serialize the body)."
  [status severity code diagnostics]
  (let [body (build-operation-outcome [{:severity severity :code code :diagnostics diagnostics}])
        bytes (json/write-value-as-bytes body json-encoder)]
    {:status status
     :headers {"Content-Type" "application/fhir+json;charset=utf-8"}
     :body (java.io.ByteArrayInputStream. bytes)}))

(defn- find-fhir-status-ex
  "Walks the ex-info cause chain looking for the first ex-data carrying a
   `:fhir/status`. Required because store-layer exceptions can be wrapped by
   instrumentation (e.g. telemere `trace!` signals) whose top-level ex-data
   does not include the FHIR status we want to surface."
  [e]
  (loop [x e]
    (cond
      (nil? x) nil
      (some-> x ex-data :fhir/status) x
      :else (recur (.getCause x)))))

(defn wrap-fhir-exceptions
  "Middleware that catches exceptions and formats them into FHIR OperationOutcomes."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)
              fhir-ex (find-fhir-status-ex e)
              fhir-data (some-> fhir-ex ex-data)
              msg (or (some-> fhir-ex ex-message) (ex-message e))]
          (log/warn e "FHIR Request Exception")
          (cond
            (= (:type data) :reitit.coercion/request-coercion)
            (error-response 422 "error" "processing" (str "Resource failed validation: " (pr-str (:errors data))))

            (= (:type data) :reitit.coercion/response-coercion)
            (error-response 500 "fatal" "exception" (str "Response validation failed: " (pr-str (:errors data))))

            fhir-data
            (error-response (:fhir/status fhir-data) "error" (:fhir/code fhir-data "processing") msg)

            :else
            (error-response 400 "error" "processing" msg))))
      (catch Exception e
        (log/error e "Unhandled FHIR Server Exception")
        (error-response 500 "fatal" "exception" (or (ex-message e) "Internal Server Error"))))))

(defn wrap-telemere-trace
  "Middleware that traces HTTP requests using Telemere, capturing method, URI, status, and duration."
  [handler]
  (fn [request]
    (let [{:keys [request-method uri]} request
          start-ns (System/nanoTime)]
      (t/trace!
       {:id :http/request
        :data {:method request-method
               :uri uri}}
       (let [response (handler request)
             status (:status response)
             duration-ms (/ (- (System/nanoTime) start-ns) 1e6)]
         (t/event! :http/response
                   {:data {:method request-method
                           :uri uri
                           :status status
                           :duration-ms duration-ms}})
         response)))))

(defn wrap-otel-context
  "Activates the current OpenTelemetry context on the request thread for the
   duration of the downstream handler call, so XTDB v2's native OTel spans
   (and any other OTel-instrumented library) attach as children of the
   current Telemere span. No-op when the OpenTelemetry SDK is not present."
  [handler]
  (fn [request]
    (fp/with-otel-context
      (handler request))))

(defn wrap-request-id
  "Middleware that adds X-Request-Id to responses. Echoes client's value or generates a UUID."
  [handler]
  (fn [request]
    (let [client-id (get-in request [:headers "x-request-id"])
          request-id (or client-id (str (java.util.UUID/randomUUID)))
          response (handler request)]
      (update response :headers assoc "X-Request-Id" request-id))))

(defn- origin-allowed?
  "Returns true if the origin is allowed by the given allowlist.
   Allows all origins when allowed-origins is nil, empty, or contains \"*\"."
  [allowed-origins origin]
  (or (nil? allowed-origins)
      (empty? allowed-origins)
      (contains? allowed-origins "*")
      (contains? allowed-origins origin)))

(defn wrap-cors
  "Middleware that adds CORS headers for browser-based FHIR clients.
   Handles preflight OPTIONS requests and adds Access-Control headers to all responses.
   `allowed-origins` is an optional set of origin strings. When nil, empty, or
   containing \"*\", all origins are allowed (dev mode). Otherwise, only origins
   in the set are reflected back."
  ([handler]
   (wrap-cors handler nil))
  ([handler allowed-origins]
   (fn [request]
     (let [origin (get-in request [:headers "origin"])]
       (if (and (= :options (:request-method request)) origin)
         ;; Preflight response
         (if (origin-allowed? allowed-origins origin)
           {:status 204
            :headers {"Access-Control-Allow-Origin" origin
                      "Access-Control-Allow-Methods" "GET, POST, PUT, PATCH, DELETE, OPTIONS, HEAD"
                      "Access-Control-Allow-Headers" "Content-Type, Authorization, Accept, X-Request-Id, If-Match, If-None-Match, If-Modified-Since, Prefer, If-None-Exist"
                      "Access-Control-Expose-Headers" "Location, ETag, Last-Modified, X-Request-Id, Content-Location"
                      "Access-Control-Max-Age" "86400"}
            :body nil}
           {:status 403
            :headers {}
            :body nil})
         ;; Normal response with CORS headers
         (let [response (handler request)]
           (if (and origin (origin-allowed? allowed-origins origin))
             (update response :headers merge
                     {"Access-Control-Allow-Origin" origin
                      "Access-Control-Expose-Headers" "Location, ETag, Last-Modified, X-Request-Id, Content-Location"})
             response)))))))

(defn wrap-pretty-print
  "When _pretty=true, serializes map bodies to pretty-printed JSON,
   bypassing Muuntaja's serialization (which passes through InputStreams).
   Should be placed between format-negotiate-middleware and format-response-middleware."
  [handler encode-mapper]
  (fn [request]
    (let [response (handler request)
          params (merge (:query-params request) (:params request))
          pretty? (= "true" (get params "_pretty"))]
      (if (and pretty? (map? (:body response)))
        (let [pretty-mapper (doto (.copy ^ObjectMapper encode-mapper)
                              (.enable SerializationFeature/INDENT_OUTPUT))
              json-bytes (json/write-value-as-bytes (:body response) pretty-mapper)]
          (-> response
              (assoc :body (java.io.ByteArrayInputStream. json-bytes))
              (assoc-in [:headers "Content-Type"] "application/fhir+json;charset=utf-8")))
        response))))

(defn- format-http-date [instant-or-string]
  (try
    (let [instant (if (string? instant-or-string)
                    (Instant/parse instant-or-string)
                    instant-or-string)]
      (.format DateTimeFormatter/RFC_1123_DATE_TIME
               (ZonedDateTime/ofInstant instant ZoneOffset/UTC)))
    (catch Exception _ nil)))

(defn wrap-format-override
  "Overrides the Accept header based on _format query parameter.
   Per FHIR R4 §3.1.0.1.11, _format takes precedence over Accept."
  [handler]
  (fn [request]
    (let [params (merge (:query-params request) (:params request))
          fmt (get params "_format")]
      (if fmt
        (let [accept (case fmt
                       ("json" "application/json" "application/fhir+json") "application/fhir+json"
                       ("xml" "application/xml" "application/fhir+xml" "text/xml")
                       nil ;; unsupported
                       nil)]
          (if accept
            (handler (assoc-in request [:headers "accept"] accept))
            (json-error-response 406 "error" "not-supported"
                                 (str "Unsupported format: " fmt ". Only JSON is supported."))))
        (handler request)))))

(defn wrap-not-acceptable
  "Returns 406 Not Acceptable when the Accept header specifies a format the server cannot produce.
   Allows requests with no Accept header, */* , or JSON-compatible types."
  [handler]
  (fn [request]
    (let [accept (get-in request [:headers "accept"])]
      (if (and accept
               (not (str/blank? accept))
               (not (re-find #"(?i)application/(fhir\+)?json|application/json-patch\+json|\*/\*" accept)))
        (json-error-response 406 "error" "not-supported"
                             (str "Not Acceptable: server only supports application/fhir+json. Received Accept: " accept))
        (handler request)))))

(defn wrap-unsupported-media-type
  "Returns 415 Unsupported Media Type when POST/PUT/PATCH requests have an unsupported Content-Type.
   Allows application/x-www-form-urlencoded for _search endpoints."
  [handler]
  (fn [request]
    (let [method (:request-method request)
          content-type (get-in request [:headers "content-type"])]
      (if (and (#{:post :put :patch} method)
               content-type
               (not (re-find #"(?i)application/(fhir\+)?json|application/json-patch\+json|application/x-www-form-urlencoded" content-type)))
        (json-error-response 415 "error" "invalid"
                             (str "Unsupported Media Type: " content-type ". Supported: application/fhir+json, application/json"))
        (handler request)))))

(defn wrap-prefer
  "Middleware that honors the Prefer header on create/update/patch responses.
   return=minimal strips the body, return=OperationOutcome wraps success."
  [handler]
  (fn [request]
    (let [response (handler request)
          prefer (get-in request [:headers "prefer"])
          return-pref (when prefer
                        (second (re-find #"return=(\S+)" prefer)))
          status (:status response)
          ;; Only apply Prefer to successful mutating responses (2xx)
          success? (and status (>= status 200) (< status 300))
          mutating? (#{:post :put :patch} (:request-method request))]
      (if (and success? mutating? return-pref)
        (case return-pref
          "minimal"
          (-> response
              (assoc :body nil)
              (update :headers assoc "Prefer" "return=minimal"))
          "OperationOutcome"
          (-> response
              (assoc :body {:resourceType "OperationOutcome"
                            :issue [{:severity "information"
                                     :code "informational"
                                     :diagnostics "Resource operation completed successfully"}]})
              (update :headers assoc "Prefer" "return=OperationOutcome"))
          ;; "representation" or unknown — return as-is (default behavior)
          response)
        response))))

(defn- add-subsetted-tag [resource]
  (update-in resource [:meta :tag]
    (fn [tags]
      (let [tags (or tags [])
            subsetted {:system "http://terminology.hl7.org/CodeSystem/v3-ObservationValue"
                       :code "SUBSETTED"}]
        (if (some #(= "SUBSETTED" (:code %)) tags)
          tags
          (conj tags subsetted))))))

(defn- apply-summary-true [resource]
  ;; Keep only: resourceType, id, meta, text (simplified mandatory elements)
  (-> (select-keys resource [:resourceType :id :meta :text])
      add-subsetted-tag))

(defn- apply-summary-text [resource]
  ;; Keep resourceType, id, meta, text only
  (-> (select-keys resource [:resourceType :id :meta :text])
      add-subsetted-tag))

(defn- apply-summary-data [resource]
  ;; Remove text narrative
  (-> (dissoc resource :text)
      add-subsetted-tag))

(defn- apply-summary [resource mode]
  (case mode
    "true" (apply-summary-true resource)
    "text" (apply-summary-text resource)
    "data" (apply-summary-data resource)
    resource))

(defn wrap-summary
  "Middleware that applies _summary parameter to responses."
  [handler]
  (fn [request]
    (let [params (merge (:query-params request) (:params request))
          summary (or (get params "_summary") (get params :_summary))
          response (handler request)]
      (if (and summary (not= summary "false"))
        (let [body (:body response)]
          (cond
            ;; _summary=count on search — return Bundle with total only
            (and (= summary "count")
                 (map? body)
                 (= "Bundle" (:resourceType body)))
            (assoc response :body {:resourceType "Bundle"
                                   :type (:type body)
                                   :total (or (:total body) 0)})

            ;; Single resource response
            (and (map? body) (:resourceType body) (not= "Bundle" (:resourceType body)))
            (assoc response :body (apply-summary body summary))

            ;; Bundle with entries — apply to each entry's resource
            (and (map? body) (= "Bundle" (:resourceType body)) (:entry body))
            (assoc response :body
                   (update body :entry
                           (fn [entries]
                             (mapv (fn [entry]
                                     (if (:resource entry)
                                       (update entry :resource apply-summary summary)
                                       entry))
                                   entries))))

            :else response))
        response))))

(defn- apply-elements [resource elements-set]
  (let [;; Always keep mandatory fields
        mandatory #{:resourceType :id :meta}
        keep-keys (into mandatory elements-set)]
    (-> (select-keys resource keep-keys)
        add-subsetted-tag)))

(defn wrap-elements
  "Middleware that applies _elements parameter to filter response fields."
  [handler]
  (fn [request]
    (let [params (merge (:query-params request) (:params request))
          elements-str (or (get params "_elements") (get params :_elements))
          response (handler request)]
      (if elements-str
        (let [elements-set (set (map keyword (str/split elements-str #",")))
              body (:body response)]
          (cond
            ;; Single resource
            (and (map? body) (:resourceType body) (not= "Bundle" (:resourceType body)))
            (assoc response :body (apply-elements body elements-set))

            ;; Bundle with entries
            (and (map? body) (= "Bundle" (:resourceType body)) (:entry body))
            (assoc response :body
                   (update body :entry
                           (fn [entries]
                             (mapv (fn [entry]
                                     (if (:resource entry)
                                       (update entry :resource apply-elements elements-set)
                                       entry))
                                   entries))))
            :else response))
        response))))

(defn wrap-fhir-response-headers
  "Adds ETag and Last-Modified headers based on resource meta."
  [handler]
  (fn [request]
    (let [response (handler request)
          body (:body response)]
      (if (and (map? body) (:resourceType body))
        (let [vid (get-in body [:meta :versionId])
              last-updated (get-in body [:meta :lastUpdated])
              formatted-date (when last-updated (format-http-date last-updated))
              headers (cond-> (or (:headers response) {})
                        vid (assoc "ETag" (str "W/\"" vid "\""))
                        formatted-date (assoc "Last-Modified" formatted-date))]
          (assoc response :headers headers))
        response))))
