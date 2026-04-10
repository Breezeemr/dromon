(ns server.middleware-test
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [server.middleware :as middleware])
  (:import [com.fasterxml.jackson.databind ObjectMapper]))

(deftest wrap-fhir-exceptions-test
  (testing "pass-through on success"
    (let [handler (fn [req] {:status 200 :body "OK"})
          wrapped (middleware/wrap-fhir-exceptions handler)]
      (is (= {:status 200 :body "OK"} (wrapped {})))))

  (testing "handles standard RuntimeException"
    (let [handler (fn [req] (throw (RuntimeException. "Something broke!")))
          wrapped (middleware/wrap-fhir-exceptions handler)
          response (wrapped {})
          body (:body response)]
      (is (= 500 (:status response)))
      (is (= "OperationOutcome" (:resourceType body)))
      (is (= "fatal" (-> body :issue first :severity)))
      (is (= "exception" (-> body :issue first :code)))
      (is (= "Something broke!" (-> body :issue first :diagnostics)))))

  (testing "handles general ExceptionInfo"
    (let [handler (fn [req] (throw (ex-info "Custom logic error" {:type :custom/error})))
          wrapped (middleware/wrap-fhir-exceptions handler)
          response (wrapped {})
          body (:body response)]
      (is (= 400 (:status response)))
      (is (= "error" (-> body :issue first :severity)))
      (is (= "processing" (-> body :issue first :code)))
      (is (= "Custom logic error" (-> body :issue first :diagnostics)))))

  (testing "handles Reitit request coercion as 422 Unprocessable Entity"
    (let [handler (fn [req] (throw (ex-info "coercion" {:type :reitit.coercion/request-coercion
                                                        :errors {:id ["should be string"]}})))
          wrapped (middleware/wrap-fhir-exceptions handler)
          response (wrapped {})
          body (:body response)
          issue (first (:issue body))]
      (is (= 422 (:status response)))
      (is (= "error" (:severity issue)))
      (is (= "processing" (:code issue)))
      (is (clojure.string/includes? (:diagnostics issue) ":id [\"should be string\"]"))))

  (testing "handles custom :fhir/status exception"
    (let [handler (fn [req] (throw (ex-info "Business rule violated" {:fhir/status 422
                                                                       :fhir/code "business-rule"})))
          wrapped (middleware/wrap-fhir-exceptions handler)
          response (wrapped {})
          body (:body response)
          issue (first (:issue body))]
      (is (= 422 (:status response)))
      (is (= "error" (:severity issue)))
      (is (= "business-rule" (:code issue)))
      (is (= "Business rule violated" (:diagnostics issue)))))

  (testing "handles custom :fhir/status with default code"
    (let [handler (fn [req] (throw (ex-info "Something wrong" {:fhir/status 409})))
          wrapped (middleware/wrap-fhir-exceptions handler)
          response (wrapped {})
          body (:body response)
          issue (first (:issue body))]
      (is (= 409 (:status response)))
      (is (= "processing" (:code issue))))))

(deftest wrap-request-id-test
  (let [ok-handler (fn [_] {:status 200 :body "OK"})
        wrapped (middleware/wrap-request-id ok-handler)]

    (testing "generates X-Request-Id when client does not send one"
      (let [resp (wrapped {:headers {}})]
        (is (string? (get-in resp [:headers "X-Request-Id"])))
        (is (= 36 (count (get-in resp [:headers "X-Request-Id"]))))))

    (testing "echoes client-supplied X-Request-Id"
      (let [resp (wrapped {:headers {"x-request-id" "client-123"}})]
        (is (= "client-123" (get-in resp [:headers "X-Request-Id"])))))

    (testing "each request without header gets a unique ID"
      (let [r1 (wrapped {:headers {}})
            r2 (wrapped {:headers {}})]
        (is (not= (get-in r1 [:headers "X-Request-Id"])
                  (get-in r2 [:headers "X-Request-Id"])))))))

(deftest wrap-head-via-ring-test
  (testing "wrap-head strips body from GET handler on HEAD request"
    (let [handler (fn [_] {:status 200 :headers {"Content-Type" "application/json"} :body "data"})
          wrapped ((requiring-resolve 'ring.middleware.head/wrap-head) handler)
          resp (wrapped {:request-method :head :uri "/test"})]
      (is (= 200 (:status resp)))
      (is (nil? (:body resp))))))

(deftest wrap-cors-test
  (let [handler (fn [_] {:status 200 :headers {} :body "OK"})
        wrapped (middleware/wrap-cors handler)]

    (testing "adds CORS headers when Origin is present"
      (let [resp (wrapped {:request-method :get :headers {"origin" "http://example.com"}})]
        (is (= "http://example.com" (get-in resp [:headers "Access-Control-Allow-Origin"])))))

    (testing "no CORS headers without Origin"
      (let [resp (wrapped {:request-method :get :headers {}})]
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"])))))

    (testing "handles preflight OPTIONS request"
      (let [resp (wrapped {:request-method :options :headers {"origin" "http://example.com"}})]
        (is (= 204 (:status resp)))
        (is (= "http://example.com" (get-in resp [:headers "Access-Control-Allow-Origin"])))
        (is (string? (get-in resp [:headers "Access-Control-Allow-Methods"])))))))

(def ^:private test-mapper (json/object-mapper {}))

(deftest wrap-pretty-print-test
  (let [body-map {:resourceType "Patient" :id "123"}
        handler (fn [_] {:status 200 :body body-map})
        wrapped (middleware/wrap-pretty-print handler test-mapper)]

    (testing "pretty-prints JSON when _pretty=true"
      (let [resp (wrapped {:query-params {"_pretty" "true"}})
            body-bytes (.readAllBytes ^java.io.InputStream (:body resp))
            body-str (String. body-bytes "UTF-8")]
        (is (instance? java.io.InputStream (:body (wrapped {:query-params {"_pretty" "true"}}))))
        (is (clojure.string/includes? body-str "\n"))
        (is (= "application/fhir+json;charset=utf-8" (get-in resp [:headers "Content-Type"])))))

    (testing "does not pretty-print when _pretty is absent"
      (let [resp (wrapped {:query-params {}})]
        (is (map? (:body resp)))
        (is (= body-map (:body resp)))))

    (testing "does not pretty-print when _pretty is not 'true'"
      (let [resp (wrapped {:query-params {"_pretty" "false"}})]
        (is (map? (:body resp)))))

    (testing "passes through non-map bodies unchanged"
      (let [stream-handler (fn [_] {:status 200 :body "already a string"})
            stream-wrapped (middleware/wrap-pretty-print stream-handler test-mapper)
            resp (stream-wrapped {:query-params {"_pretty" "true"}})]
        (is (= "already a string" (:body resp)))))))

;; --- wrap-format-override tests ---

(deftest wrap-format-override-test
  (let [handler (fn [req] {:status 200 :body (get-in req [:headers "accept"])})
        wrapped (middleware/wrap-format-override handler)]

    (testing "overrides Accept with _format=json"
      (let [resp (wrapped {:query-params {"_format" "json"} :headers {"accept" "text/html"}})]
        (is (= 200 (:status resp)))
        (is (= "application/fhir+json" (:body resp)))))

    (testing "overrides Accept with _format=application/fhir+json"
      (let [resp (wrapped {:query-params {"_format" "application/fhir+json"} :headers {}})]
        (is (= "application/fhir+json" (:body resp)))))

    (testing "returns 406 for _format=xml"
      (let [resp (wrapped {:query-params {"_format" "xml"} :headers {}})]
        (is (= 406 (:status resp)))))

    (testing "passes through when _format is absent"
      (let [resp (wrapped {:query-params {} :headers {"accept" "application/json"}})]
        (is (= 200 (:status resp)))
        (is (= "application/json" (:body resp)))))))

;; --- wrap-not-acceptable tests ---

(defn- parse-stream-body [resp]
  (let [body (:body resp)]
    (if (instance? java.io.InputStream body)
      (json/read-value body (json/object-mapper {:decode-key-fn keyword}))
      body)))

(deftest wrap-not-acceptable-test
  (let [handler (fn [_] {:status 200 :body "OK"})
        wrapped (middleware/wrap-not-acceptable handler)]

    (testing "allows application/json"
      (is (= 200 (:status (wrapped {:headers {"accept" "application/json"}})))))

    (testing "allows application/fhir+json"
      (is (= 200 (:status (wrapped {:headers {"accept" "application/fhir+json"}})))))

    (testing "allows */*"
      (is (= 200 (:status (wrapped {:headers {"accept" "*/*"}})))))

    (testing "allows missing Accept header"
      (is (= 200 (:status (wrapped {:headers {}})))))

    (testing "rejects application/xml with 406"
      (let [resp (wrapped {:headers {"accept" "application/xml"}})]
        (is (= 406 (:status resp)))
        (is (= "OperationOutcome" (:resourceType (parse-stream-body resp))))))

    (testing "rejects application/fhir+xml with 406"
      (let [resp (wrapped {:headers {"accept" "application/fhir+xml"}})]
        (is (= 406 (:status resp)))))))

;; --- wrap-unsupported-media-type tests ---

(deftest wrap-unsupported-media-type-test
  (let [handler (fn [_] {:status 200 :body "OK"})
        wrapped (middleware/wrap-unsupported-media-type handler)]

    (testing "allows application/json on POST"
      (is (= 200 (:status (wrapped {:request-method :post :headers {"content-type" "application/json"}})))))

    (testing "allows application/fhir+json on PUT"
      (is (= 200 (:status (wrapped {:request-method :put :headers {"content-type" "application/fhir+json"}})))))

    (testing "allows application/json-patch+json on PATCH"
      (is (= 200 (:status (wrapped {:request-method :patch :headers {"content-type" "application/json-patch+json"}})))))

    (testing "allows GET with any content-type"
      (is (= 200 (:status (wrapped {:request-method :get :headers {"content-type" "application/xml"}})))))

    (testing "allows application/x-www-form-urlencoded on POST (for _search)"
      (is (= 200 (:status (wrapped {:request-method :post :headers {"content-type" "application/x-www-form-urlencoded"}})))))

    (testing "rejects application/xml on POST with 415"
      (let [resp (wrapped {:request-method :post :headers {"content-type" "application/xml"}})]
        (is (= 415 (:status resp)))
        (is (= "OperationOutcome" (:resourceType (parse-stream-body resp))))))

    (testing "rejects text/plain on PUT with 415"
      (is (= 415 (:status (wrapped {:request-method :put :headers {"content-type" "text/plain"}})))))))

;; --- wrap-fhir-response-headers tests ---

(deftest wrap-fhir-response-headers-test
  (testing "adds ETag and Last-Modified for resource with meta"
    (let [body {:resourceType "Patient"
                :id "pt-1"
                :meta {:versionId "3"
                       :lastUpdated "2026-01-01T00:00:00Z"}}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-fhir-response-headers handler)
          resp (wrapped {})]
      (is (= "W/\"3\"" (get-in resp [:headers "ETag"])))
      (is (some? (get-in resp [:headers "Last-Modified"])))
      (is (clojure.string/includes? (get-in resp [:headers "Last-Modified"]) "2026"))))

  (testing "does not add headers when meta is absent"
    (let [body {:resourceType "Patient" :id "pt-1"}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-fhir-response-headers handler)
          resp (wrapped {})]
      (is (nil? (get-in resp [:headers "ETag"])))
      (is (nil? (get-in resp [:headers "Last-Modified"])))))

  (testing "does not add headers for non-resource body (no resourceType)"
    (let [body {:type "searchset" :total 5}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-fhir-response-headers handler)
          resp (wrapped {})]
      (is (nil? (get-in resp [:headers "ETag"])))
      (is (nil? (get-in resp [:headers "Last-Modified"])))))

  (testing "does not add headers for non-map body"
    (let [handler (fn [_] {:status 200 :body "plain text"})
          wrapped (middleware/wrap-fhir-response-headers handler)
          resp (wrapped {})]
      (is (= "plain text" (:body resp))))))

;; --- wrap-prefer tests ---

(deftest wrap-prefer-test
  (let [body {:resourceType "Patient" :id "pt-1" :meta {:versionId "1"}}
        handler (fn [_] {:status 201 :headers {"Location" "/fhir/Patient/pt-1"} :body body})
        wrapped (middleware/wrap-prefer handler)]

    (testing "return=minimal strips body"
      (let [resp (wrapped {:request-method :post :headers {"prefer" "return=minimal"}})]
        (is (= 201 (:status resp)))
        (is (nil? (:body resp)))
        (is (= "/fhir/Patient/pt-1" (get-in resp [:headers "Location"])))))

    (testing "return=representation keeps body (default)"
      (let [resp (wrapped {:request-method :post :headers {"prefer" "return=representation"}})]
        (is (= 201 (:status resp)))
        (is (= body (:body resp)))))

    (testing "return=OperationOutcome returns OperationOutcome"
      (let [resp (wrapped {:request-method :post :headers {"prefer" "return=OperationOutcome"}})]
        (is (= 201 (:status resp)))
        (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

    (testing "no Prefer header keeps default behavior"
      (let [resp (wrapped {:request-method :post :headers {}})]
        (is (= body (:body resp)))))

    (testing "does not apply to GET requests"
      (let [get-handler (fn [_] {:status 200 :body body})
            get-wrapped (middleware/wrap-prefer get-handler)
            resp (get-wrapped {:request-method :get :headers {"prefer" "return=minimal"}})]
        (is (= body (:body resp)))))))

;; --- wrap-summary tests ---

(deftest wrap-summary-test
  (testing "_summary=data removes text"
    (let [body {:resourceType "Patient" :id "1" :meta {:versionId "1"} :text {:div "<div>hi</div>"} :gender "male"}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-summary handler)
          resp (wrapped {:query-params {"_summary" "data"}})]
      (is (nil? (get-in resp [:body :text])))
      (is (= "male" (get-in resp [:body :gender])))
      (is (some #(= "SUBSETTED" (:code %)) (get-in resp [:body :meta :tag])))))

  (testing "_summary=text keeps only text/id/meta/resourceType"
    (let [body {:resourceType "Patient" :id "1" :meta {:versionId "1"} :text {:div "<div>hi</div>"} :gender "male"}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-summary handler)
          resp (wrapped {:query-params {"_summary" "text"}})]
      (is (some? (get-in resp [:body :text])))
      (is (nil? (get-in resp [:body :gender])))))

  (testing "_summary=count returns Bundle with total only"
    (let [body {:resourceType "Bundle" :type "searchset" :total 5 :entry [{:resource {:resourceType "Patient"}}]}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-summary handler)
          resp (wrapped {:query-params {"_summary" "count"}})]
      (is (= 5 (get-in resp [:body :total])))
      (is (nil? (get-in resp [:body :entry])))))

  (testing "_summary=false passes through"
    (let [body {:resourceType "Patient" :id "1" :gender "male"}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-summary handler)
          resp (wrapped {:query-params {"_summary" "false"}})]
      (is (= body (:body resp))))))

(deftest wrap-elements-test
  (testing "_elements keeps specified fields plus mandatory"
    (let [body {:resourceType "Patient" :id "1" :meta {:versionId "1"}
                :gender "male" :birthDate "1990-01-01" :active true}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-elements handler)
          resp (wrapped {:query-params {"_elements" "gender,birthDate"}})]
      (is (= "male" (get-in resp [:body :gender])))
      (is (= "1990-01-01" (get-in resp [:body :birthDate])))
      (is (nil? (get-in resp [:body :active])))
      (is (= "Patient" (get-in resp [:body :resourceType])))
      (is (= "1" (get-in resp [:body :id])))
      (is (some #(= "SUBSETTED" (:code %)) (get-in resp [:body :meta :tag])))))

  (testing "no _elements passes through"
    (let [body {:resourceType "Patient" :id "1" :gender "male" :active true}
          handler (fn [_] {:status 200 :body body})
          wrapped (middleware/wrap-elements handler)
          resp (wrapped {:query-params {}})]
      (is (= body (:body resp))))))
