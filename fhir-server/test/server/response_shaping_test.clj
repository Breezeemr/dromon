(ns server.response-shaping-test
  "End-to-end tests for the response-shaping middleware -- `_summary`,
   `_elements`, `_pretty`, the `Prefer` return preference, and the
   ETag/Last-Modified headers -- driven through the FULL router stack.

   These middleware transform MAP response bodies, so they only work when they
   run inside `::router/format-response`: muuntaja encodes eagerly to an
   InputStream, and anything outside it sees bytes rather than a resource map.
   Unit tests that wrap a handler in isolation cannot catch that, which is how
   the ordering defect survived; every assertion here therefore goes through
   `server.router/handler`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [buddy.sign.jwt :as jwt]
            [fhir-store.mock.core :as mock]
            [hato.client :as hc]
            [jsonista.core :as json]
            [malli.core :as m]
            [server.auth :as auth]
            [server.core :as sc]
            [server.router :as router])
  (:import [java.io ByteArrayInputStream InputStream]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private tenant "default")

(def ^:private test-schemas
  "One Patient type with the interactions these tests drive. The :map is open
   and carries no entries, so any well-formed resource passes request and
   response coercion -- the shaping, not the profile, is under test."
  [(sc/capability-schema->server-schema
     (m/schema [:map {:resourceType  "Patient"
                      :interactions  ["read" "create" "update" "search-type"]
                      :search-params []}]))])

(def ^:private resolved-opts
  {:jwks-url              "http://jwks.test/.well-known/jwks.json"
   :keto-url              "http://keto.test"
   :enforce-smart-scopes? false
   :cors-origins          nil
   :trace-tap             nil
   :terminology           nil
   :bulk-job-store        nil})

(def ^:private keypair
  (let [gen (java.security.KeyPairGenerator/getInstance "RSA")]
    (.initialize gen 2048)
    (.generateKeyPair gen)))

(defn- bearer-token []
  (str "Bearer " (jwt/sign {:sub "user-1"} (.getPrivate keypair)
                           {:alg :rs256 :header {:kid "kid-1"}})))

(defmacro ^:private with-auth
  "Run `body` with a JWKS that validates [[bearer-token]] and a Keto that allows
   every check."
  [& body]
  `(with-redefs [auth/fetch-jwks (fn [_#] {"kid-1" (.getPublic keypair)})
                 hc/get (fn [_# _#] {:status 200 :body {:allowed true}})]
     ~@body))

(defn- app []
  (router/handler test-schemas (router/default-middleware
                                 (mock/create-mock-store {}) resolved-opts)))

;; ---------------------------------------------------------------------------
;; Request / response helpers
;; ---------------------------------------------------------------------------

(defn- auth-headers []
  {"accept" "application/fhir+json"
   "content-type" "application/fhir+json"
   "authorization" (bearer-token)})

(defn- GET [uri & {:keys [headers]}]
  (let [[path qs] (str/split uri #"\?" 2)]
    (cond-> {:request-method :get
             :uri path
             :headers (merge (auth-headers) headers)}
      qs (assoc :query-string qs))))

(defn- POST [uri resource & {:keys [headers]}]
  {:request-method :post
   :uri uri
   :headers (merge (auth-headers) headers)
   :body (ByteArrayInputStream. (json/write-value-as-bytes resource))})

(defn- body-string
  "The response body as the string a client would receive. A map body here would
   mean muuntaja never encoded it, which the Jetty adapter cannot serialize --
   so the assertion that it is bytes is itself part of the contract."
  [resp]
  (let [b (:body resp)]
    (cond
      (instance? InputStream b) (slurp b)
      (string? b) b
      (nil? b) ""
      :else (throw (ex-info "response body was never encoded"
                            {:body-class (class b) :body b})))))

(defn- json-body [resp]
  (json/read-value (body-string resp)))

(def ^:private patient
  {:resourceType "Patient"
   :gender "female"
   :birthDate "1990-01-01"
   :active true
   :text {:status "generated" :div "<div xmlns=\"http://www.w3.org/1999/xhtml\">Smith</div>"}
   :name [{:family "Smith"}]})

(defn- create-patient!
  "Create [[patient]] through `app` and return its server-assigned id."
  [app]
  (get (json-body (app (POST (str "/" tenant "/fhir/Patient") patient))) "id"))

(defn- subsetted? [resource]
  (boolean (some #(= "SUBSETTED" (get % "code")) (get-in resource ["meta" "tag"]))))

;; ---------------------------------------------------------------------------
;; The ordering that makes all of the above possible
;; ---------------------------------------------------------------------------

(deftest the-shaping-group-sits-inside-the-encoder
  (let [names (mapv :name (router/default-middleware nil resolved-opts))
        at    (fn [nme] (.indexOf ^java.util.List names nme))]
    (testing "every shaping middleware runs inside ::format-response and
              ::fhir-exceptions -- outside either one it would see an encoded
              body, or shape an exception's OperationOutcome"
      (doseq [nme [::router/pretty-print ::router/prefer ::router/elements
                   ::router/summary ::router/fhir-response-headers]]
        (is (< (at ::router/format-response) (at nme))
            (str nme " must come after ::format-response"))
        (is (< (at ::router/fhir-exceptions) (at nme))
            (str nme " must come after ::fhir-exceptions"))))

    (testing "within the group, outer runs last on the response: headers are
              derived first, then subsetting, then Prefer, then pretty-print"
      (is (= [::router/pretty-print ::router/prefer ::router/elements
              ::router/summary ::router/fhir-response-headers]
             (subvec names (at ::router/pretty-print)
                     (inc (at ::router/fhir-response-headers))))))))

;; ---------------------------------------------------------------------------
;; _summary
;; ---------------------------------------------------------------------------

(deftest summary-shapes-the-read-response
  (with-auth
    (let [app (app)
          id  (create-patient! app)]
      (testing "_summary=true keeps the mandatory elements and drops the rest"
        (let [resp (app (GET (str "/" tenant "/fhir/Patient/" id "?_summary=true")))
              body (json-body resp)]
          (is (= 200 (:status resp)))
          (is (= "Patient" (get body "resourceType")))
          (is (nil? (get body "name")) "name is not a summary element")
          (is (nil? (get body "gender")))
          (is (some? (get body "text")))
          (is (subsetted? body) "a subsetted response is tagged SUBSETTED")))

      (testing "_summary=data drops the narrative and keeps the data"
        (let [body (json-body (app (GET (str "/" tenant "/fhir/Patient/" id "?_summary=data"))))]
          (is (nil? (get body "text")))
          (is (= "female" (get body "gender")))
          (is (subsetted? body))))

      (testing "_summary=false leaves the resource whole and untagged"
        (let [body (json-body (app (GET (str "/" tenant "/fhir/Patient/" id "?_summary=false"))))]
          (is (= "female" (get body "gender")))
          (is (some? (get body "text")))
          (is (not (subsetted? body))))))))

(deftest summary-count-reduces-a-search-bundle-to-its-total
  (with-auth
    (let [app (app)]
      (create-patient! app)
      (let [resp (app (GET (str "/" tenant "/fhir/Patient?_summary=count")))
            body (json-body resp)]
        (is (= 200 (:status resp)))
        (is (= "Bundle" (get body "resourceType")))
        (is (= 1 (get body "total")))
        (is (nil? (get body "entry")) "_summary=count returns no entries")))))

(deftest summary-applies-to-every-entry-of-a-search-bundle
  (with-auth
    (let [app (app)]
      (create-patient! app)
      (let [body (json-body (app (GET (str "/" tenant "/fhir/Patient?_summary=true"))))
            resource (get-in body ["entry" 0 "resource"])]
        (is (= "Bundle" (get body "resourceType")))
        (is (nil? (get resource "gender")))
        (is (subsetted? resource))))))

;; ---------------------------------------------------------------------------
;; _elements
;; ---------------------------------------------------------------------------

(deftest elements-filters-the-response-to-the-named-fields
  (with-auth
    (let [app (app)
          id  (create-patient! app)
          body (json-body (app (GET (str "/" tenant "/fhir/Patient/" id "?_elements=gender,birthDate"))))]
      (is (= "female" (get body "gender")))
      (is (= "1990-01-01" (get body "birthDate")))
      (is (nil? (get body "active")) "a field outside _elements is dropped")
      (is (nil? (get body "name")))
      (is (= "Patient" (get body "resourceType")) "mandatory elements are kept")
      (is (= id (get body "id")))
      (is (subsetted? body)))))

;; ---------------------------------------------------------------------------
;; _pretty
;; ---------------------------------------------------------------------------

(deftest pretty-print-indents-the-encoded-body
  (with-auth
    (let [app (app)
          id  (create-patient! app)]
      (testing "_pretty=true produces indented JSON"
        (let [resp (app (GET (str "/" tenant "/fhir/Patient/" id "?_pretty=true")))
              text (body-string resp)]
          (is (= 200 (:status resp)))
          (is (str/includes? text "\n") "pretty JSON spans several lines")
          (is (re-find #"\n\s+\"resourceType\"" text) "fields are indented")
          (is (= "Patient" (get (json/read-value text) "resourceType")))))

      (testing "without _pretty the body is compact"
        (let [text (body-string (app (GET (str "/" tenant "/fhir/Patient/" id))))]
          (is (not (str/includes? text "\n")))))

      (testing "_pretty composes with _summary: the shaped body is what gets
                indented, so pretty-print must run after the subsetters"
        (let [text (body-string (app (GET (str "/" tenant "/fhir/Patient/" id
                                               "?_pretty=true&_summary=true"))))
              body (json/read-value text)]
          (is (str/includes? text "\n"))
          (is (nil? (get body "gender")))
          (is (subsetted? body)))))))

;; ---------------------------------------------------------------------------
;; Prefer
;; ---------------------------------------------------------------------------

(deftest prefer-return-minimal-strips-the-created-body
  (with-auth
    (let [app  (app)
          resp (app (POST (str "/" tenant "/fhir/Patient") patient
                          :headers {"prefer" "return=minimal"}))]
      (is (= 201 (:status resp)))
      (is (str/blank? (body-string resp)) "return=minimal sends no body")
      (is (= "return=minimal" (get-in resp [:headers "Prefer"])))
      (is (some? (get-in resp [:headers "Location"])) "Location survives")
      (is (some? (get-in resp [:headers "ETag"]))
          "ETag is computed before Prefer strips the body"))))

(deftest prefer-return-operation-outcome-replaces-the-created-body
  (with-auth
    (let [app  (app)
          resp (app (POST (str "/" tenant "/fhir/Patient") patient
                          :headers {"prefer" "return=OperationOutcome"}))
          body (json-body resp)]
      (is (= 201 (:status resp)))
      (is (= "OperationOutcome" (get body "resourceType")))
      (is (= "information" (get-in body ["issue" 0 "severity"])))
      (is (= "return=OperationOutcome" (get-in resp [:headers "Prefer"]))))))

(deftest prefer-return-representation-keeps-the-created-resource
  (with-auth
    (let [app  (app)
          resp (app (POST (str "/" tenant "/fhir/Patient") patient
                          :headers {"prefer" "return=representation"}))]
      (is (= 201 (:status resp)))
      (is (= "Patient" (get (json-body resp) "resourceType"))))))

;; ---------------------------------------------------------------------------
;; ETag / Last-Modified
;; ---------------------------------------------------------------------------

(deftest read-carries-etag-and-last-modified
  (with-auth
    (let [app  (app)
          id   (create-patient! app)
          resp (app (GET (str "/" tenant "/fhir/Patient/" id)))]
      (is (= 200 (:status resp)))
      (is (= "W/\"1\"" (get-in resp [:headers "ETag"]))
          "the weak ETag quotes meta.versionId")
      (is (re-find #"GMT$" (get-in resp [:headers "Last-Modified"]))
          "Last-Modified is an RFC 1123 HTTP date"))))

(deftest etag-tracks-the-version-across-an-update
  (with-auth
    (let [app  (app)
          id   (create-patient! app)
          _    (app {:request-method :put
                     :uri (str "/" tenant "/fhir/Patient/" id)
                     :headers (auth-headers)
                     :body (ByteArrayInputStream.
                             (json/write-value-as-bytes
                               (assoc patient :id id :gender "male")))})
          resp (app (GET (str "/" tenant "/fhir/Patient/" id)))]
      (is (= "W/\"2\"" (get-in resp [:headers "ETag"]))))))

(deftest a-search-bundle-carries-no-resource-etag
  (testing "a Bundle is not a versioned resource, so it gets no ETag"
    (with-auth
      (let [app (app)]
        (create-patient! app)
        (let [resp (app (GET (str "/" tenant "/fhir/Patient")))]
          (is (= 200 (:status resp)))
          (is (nil? (get-in resp [:headers "ETag"]))))))))

;; ---------------------------------------------------------------------------
;; Error responses must not be shaped
;; ---------------------------------------------------------------------------

(deftest shaping-parameters-leave-error-outcomes-intact
  (testing "an OperationOutcome is the diagnosis of a failed request, not the
            resource that was asked for: _summary and _elements must not strip
            its issues, however the query was parameterized"
    (with-auth
      (let [app (app)]
        (doseq [[label uri] {"_summary=true"  (str "/" tenant "/fhir/Patient/missing?_summary=true")
                             "_summary=text"  (str "/" tenant "/fhir/Patient/missing?_summary=text")
                             "_elements=id"   (str "/" tenant "/fhir/Patient/missing?_elements=id")}]
          (testing label
            (let [resp (app (GET uri))
                  body (json-body resp)]
              (is (= 404 (:status resp)))
              (is (= "OperationOutcome" (get body "resourceType")))
              (is (= "not-found" (get-in body ["issue" 0 "code"]))
                  "the issue array survives shaping")
              (is (not (subsetted? body))
                  "an error outcome is never tagged SUBSETTED"))))))))

(deftest a-keto-rejection-outcome-is-not-shaped
  (testing "keto's rejection is returned as a map body from inside the shaping
            middleware, so it is the case a status guard has to cover"
    (let [app  (app)
          ;; No Authorization header: wrap-jwt-auth attaches no :identity and
          ;; wrap-keto-authorization answers 401 for the missing subject
          ;; (present-subject-but-denied stays 403; both are map bodies).
          resp (app {:request-method :get
                     :uri (str "/" tenant "/fhir/Patient/123")
                     :query-string "_summary=true"
                     :headers {"accept" "application/fhir+json"}})
          body (json-body resp)]
      (is (= 401 (:status resp)))
      (is (= "OperationOutcome" (get body "resourceType")))
      (is (= "login" (get-in body ["issue" 0 "code"]))))))
