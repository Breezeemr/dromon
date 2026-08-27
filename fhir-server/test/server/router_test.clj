(ns server.router-test
  "Tests for the composable router API.

   Two things are under test. First, equivalence: `server.core/fhir-app` is now
   a thin composition of `server.router`, so a hand-composed router built from
   the same pieces must answer representative requests identically. Second,
   recomposition: a host must be able to splice middleware in by NAME and have
   it take effect at that position in the chain."
  (:require [clojure.test :refer [deftest is testing]]
            [buddy.sign.jwt :as jwt]
            [fhir-store.mock.core :as mock]
            [hato.client :as hc]
            [jsonista.core :as json]
            [malli.core :as m]
            [reitit.ring :as ring]
            [server.auth :as auth]
            [server.core :as sc]
            [server.router :as router])
  (:import [java.io ByteArrayInputStream InputStream]))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private tenant "default")

(def ^:private test-schemas
  "A single Patient type with read/create/search-type. Hand-built rather than
   pulled from a generated capability package: the us-core malli packages are
   not on fhir-server's classpath. The :map is open and carries no entries, so
   any well-formed resource body passes request and response coercion."
  [(sc/capability-schema->server-schema
     (m/schema [:map {:resourceType  "Patient"
                      :interactions  ["read" "create" "search-type"]
                      :search-params []}]))])

(def ^:private jwks-url "http://jwks.test/.well-known/jwks.json")
(def ^:private keto-url "http://keto.test")
(def ^:private allowed-origin "https://app.example")

(def ^:private app-opts
  "The kwargs both apps are built with. Every environment-backed option is
   passed explicitly so the tests do not depend on the ambient environment."
  {:jwks-url              jwks-url
   :keto-url              keto-url
   :cors-allowed-origins  #{allowed-origin}
   :enforce-smart-scopes? false})

(def ^:private resolved-opts
  "A resolved options map built by hand rather than through
   [[server.router/resolve-options]], so middleware-vector assertions are
   independent of DROMON_DEV_TRACE_TAP and friends."
  {:jwks-url              jwks-url
   :keto-url              keto-url
   :enforce-smart-scopes? false
   :cors-origins          #{allowed-origin}
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
   every check. Both are consulted lazily per request, so apps may be built
   outside this macro."
  [& body]
  `(with-redefs [auth/fetch-jwks (fn [_#] {"kid-1" (.getPublic keypair)})
                 hc/get (fn [_# _#] {:status 200 :body {:allowed true}})]
     ~@body))

;; ---------------------------------------------------------------------------
;; Request / response helpers
;; ---------------------------------------------------------------------------

(defn- json-stream [data]
  (ByteArrayInputStream. (json/write-value-as-bytes data)))

(defn- normalize
  "Response reduced to the parts that are deterministic across two identically
   composed apps: X-Request-Id is a fresh UUID per request, so it is dropped,
   and body streams are slurped to their encoded string."
  [resp]
  (-> resp
      (update :headers dissoc "X-Request-Id")
      (update :body (fn [b] (if (instance? InputStream b) (slurp b) b)))))

(defn- json-body [resp]
  (let [b (:body resp)]
    (json/read-value (if (instance? InputStream b) (slurp b) b))))

(defn- GET [uri & {:keys [headers]}]
  {:request-method :get
   :uri uri
   :headers (merge {"accept" "application/fhir+json"} headers)})

;; ---------------------------------------------------------------------------
;; The two apps under comparison
;; ---------------------------------------------------------------------------

(defn- composed-app
  "The manual composition a host would write, with the exact pieces
   `server.core/fhir-app` now composes."
  [store]
  (ring/ring-handler
    (router/router test-schemas
                   (router/default-middleware store (router/resolve-options app-opts)))
    router/default-handler))

;; ---------------------------------------------------------------------------
;; (a) Equivalence: fhir-app vs. the hand-composed router
;; ---------------------------------------------------------------------------

(def ^:private deterministic-requests
  "Requests whose full response is stable, so the two apps can be compared
   byte for byte."
  {:metadata         (GET (str "/" tenant "/fhir/metadata"))
   :preflight-allowed {:request-method :options
                       :uri (str "/" tenant "/fhir/Patient")
                       :headers {"origin" allowed-origin}}
   :preflight-denied  {:request-method :options
                       :uri (str "/" tenant "/fhir/Patient")
                       :headers {"origin" "https://evil.example"}}
   :unauthenticated-read (GET (str "/" tenant "/fhir/Patient/123"))
   :unauthenticated-export (GET (str "/" tenant "/fhir/$export"))
   :unmatched-route (GET (str "/" tenant "/fhir/UnknownType"))})

(deftest fhir-app-matches-the-hand-composed-router
  (testing "every representative request gets the same response from
            server.core/fhir-app and from a router composed out of
            server.router's exposed pieces"
    (let [store (mock/create-mock-store {})
          app-a (sc/fhir-app store test-schemas
                             :jwks-url jwks-url
                             :keto-url keto-url
                             :cors-allowed-origins #{allowed-origin}
                             :enforce-smart-scopes? false)
          app-b (composed-app store)]
      (doseq [[label req] deterministic-requests]
        (is (= (normalize (app-a req)) (normalize (app-b req)))
            (str "responses diverge for " label))))))

(deftest composed-router-answers-representative-requests
  (let [store (mock/create-mock-store {})
        app   (composed-app store)]
    (testing "metadata is public and returns a CapabilityStatement"
      (let [resp (app (:metadata deterministic-requests))]
        (is (= 200 (:status resp)))
        (is (= "CapabilityStatement" (get (json-body resp) "resourceType")))))

    (testing "an allowed CORS preflight short-circuits before auth"
      (let [resp (app (:preflight-allowed deterministic-requests))]
        (is (= 204 (:status resp)))
        (is (= allowed-origin (get-in resp [:headers "Access-Control-Allow-Origin"])))))

    (testing "a disallowed CORS origin is rejected with 403"
      (let [resp (app (:preflight-denied deterministic-requests))]
        (is (= 403 (:status resp)))
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"])))))

    (testing "an unauthenticated read is the Keto 401 (missing subject answers
              401 with a login issue; present-subject-denied stays 403):
              wrap-jwt-auth only attaches :identity, it never rejects"
      (let [resp (app (:unauthenticated-read deterministic-requests))
            body (json-body resp)]
        (is (= 401 (:status resp)))
        (is (= "OperationOutcome" (get body "resourceType")))
        (is (= "login" (get-in body ["issue" 0 "code"])))))

    (testing "an unauthenticated $export is a 401: that route is :public? and
              fronted with wrap-require-auth"
      (let [resp (app (:unauthenticated-export deterministic-requests))
            body (json-body resp)]
        (is (= 401 (:status resp)))
        (is (= "OperationOutcome" (get body "resourceType")))
        (is (= "login" (get-in body ["issue" 0 "code"])))))

    (testing "the default handler runs outside the middleware chain, so its
              404 body is already an encoded string"
      (let [resp (app (:unmatched-route deterministic-requests))]
        (is (= 404 (:status resp)))
        (is (string? (:body resp)))
        (is (= "application/fhir+json" (get-in resp [:headers "Content-Type"])))
        (is (= "OperationOutcome" (get (json/read-value (:body resp)) "resourceType")))))))

(defn- crud-summary
  "Drive create -> read -> search against `app` and reduce the responses to the
   parts that do not vary with the generated resource id or timestamps."
  [app]
  (let [headers {"accept" "application/fhir+json"
                 "content-type" "application/fhir+json"
                 "authorization" (bearer-token)}
        create  (app {:request-method :post
                      :uri (str "/" tenant "/fhir/Patient")
                      :headers headers
                      :body (json-stream {:resourceType "Patient"
                                          :name [{:family "Smith"}]})})
        created (json-body create)
        id      (get created "id")
        read    (app (GET (str "/" tenant "/fhir/Patient/" id) :headers headers))
        read-body (json-body read)
        ;; The hand-built schema carries no search parameters, so its registry
        ;; is empty and search-type rejects any filter; search unparameterized.
        search  (app (GET (str "/" tenant "/fhir/Patient") :headers headers))
        search-body (json-body search)]
    {:create {:status        (:status create)
              :versioned-location? (some? (re-find #"/_history/"
                                                   (or (get-in create [:headers "Location"]) "")))
              :resource-type (get created "resourceType")
              :family        (get-in created ["name" 0 "family"])}
     :read   {:status        (:status read)
              :resource-type (get read-body "resourceType")
              :family        (get-in read-body ["name" 0 "family"])}
     :search {:status        (:status search)
              :resource-type (get search-body "resourceType")
              :type          (get search-body "type")
              :total         (get search-body "total")}}))

(def ^:private expected-crud-summary
  {:create {:status 201 :versioned-location? true
            :resource-type "Patient" :family "Smith"}
   :read   {:status 200 :resource-type "Patient" :family "Smith"}
   :search {:status 200 :resource-type "Bundle" :type "searchset" :total 1}})

(deftest authenticated-crud-behaves-identically-through-both-apps
  (testing "create, read and search against the mock store go through the full
            auth + coercion + encoding chain in both compositions"
    (with-auth
      (let [app-a (sc/fhir-app (mock/create-mock-store {}) test-schemas
                               :jwks-url jwks-url
                               :keto-url keto-url
                               :cors-allowed-origins #{allowed-origin}
                               :enforce-smart-scopes? false)
            app-b (composed-app (mock/create-mock-store {}))]
        (is (= expected-crud-summary (crud-summary app-a)))
        (is (= expected-crud-summary (crud-summary app-b)))))))

;; ---------------------------------------------------------------------------
;; (b) Recomposition
;; ---------------------------------------------------------------------------

(def ^:private default-middleware-names
  "The default stack, in outermost-first order. Locked down here because the
   ordering invariants documented on server.router/default-middleware are what
   make the vector safe to splice into."
  [::router/telemere-trace
   ::router/otel-context
   ::router/head
   ::router/request-id
   ::router/cors
   ::router/parameters
   ::router/format-override
   ::router/not-acceptable
   ::router/unsupported-media-type
   ::router/format-negotiate
   ::router/format-response
   ::router/fhir-exceptions
   ::router/pretty-print
   ::router/prefer
   ::router/elements
   ::router/summary
   ::router/fhir-response-headers
   ::router/format-request
   ::router/coerce-request
   ::router/coerce-response
   ::router/coerce-exceptions
   ::router/fhir-store
   ::router/terminology
   ::router/bulk-job-store
   ::router/keto-url
   ::router/jwt-auth
   ::router/keto-authorization])

(deftest default-middleware-is-named-and-ordered
  (testing "every entry carries a :name in the :server.router namespace"
    (let [mw (router/default-middleware nil resolved-opts)]
      (is (= default-middleware-names (mapv :name mw)))
      (is (every? #(= "server.router" (namespace (:name %))) mw))
      (is (every? #(or (:wrap %) (:compile %)) mw)
          "reitit needs a :wrap or a :compile on every entry")))

  (testing "::trace-tap is prepended when opts carry a trace-tap fn"
    (let [mw (router/default-middleware nil (assoc resolved-opts :trace-tap identity))]
      (is (= (into [::router/trace-tap] default-middleware-names) (mapv :name mw)))))

  (testing "::smart-scope and ::patient-compartment appear only when SMART
            scope enforcement is on, between ::jwt-auth and ::keto-authorization"
    (let [off (mapv :name (router/default-middleware nil resolved-opts))
          on  (mapv :name (router/default-middleware
                            nil (assoc resolved-opts :enforce-smart-scopes? true)))]
      (is (not-any? #{::router/smart-scope ::router/patient-compartment} off))
      (is (= [::router/jwt-auth ::router/smart-scope ::router/patient-compartment
              ::router/keto-authorization]
             (subvec on (- (count on) 4)))))))

(def ^:private marker-middleware
  "Short-circuits on X-Marker so its position in the chain is observable."
  {:name ::marker
   :wrap (fn [handler]
           (fn [req]
             (if (get-in req [:headers "x-marker"])
               {:status 418 :body {:resourceType "Basic" :marked true}}
               (handler req))))})

(deftest host-can-insert-middleware-before-auth
  (let [store (mock/create-mock-store {})
        mw    (-> (router/default-middleware store resolved-opts)
                  (router/insert-before ::router/jwt-auth marker-middleware))
        app   (ring/ring-handler (router/router test-schemas mw) router/default-handler)
        names (mapv :name mw)]
    (testing "the entry lands immediately before ::jwt-auth"
      (is (= [::router/keto-url ::marker ::router/jwt-auth ::router/keto-authorization]
             (subvec names (- (count names) 4)))))

    (testing "it pre-empts the Keto 403, proving it runs before the auth block"
      (let [resp (app (GET (str "/" tenant "/fhir/Patient")
                           :headers {"x-marker" "1"}))]
        (is (= 418 (:status resp)))
        (is (true? (get (json-body resp) "marked")))))

    (testing "without the marker header the unchanged chain still rejects the
              anonymous request (401, missing subject)"
      (let [resp (app (GET (str "/" tenant "/fhir/Patient")))]
        (is (= 401 (:status resp)))
        (is (= "OperationOutcome" (get (json-body resp) "resourceType")))))))

(deftest host-can-replace-the-cors-entry
  (let [store (mock/create-mock-store {})
        stub  {:name ::router/cors
               :wrap (fn [handler]
                       (fn [req]
                         (if (= :options (:request-method req))
                           {:status 204 :headers {"X-Cors" "custom"} :body nil}
                           (handler req))))}
        mw    (-> (router/default-middleware store resolved-opts)
                  (router/replace-middleware ::router/cors stub))
        app   (ring/ring-handler (router/router test-schemas mw) router/default-handler)]
    (testing "the replacement keeps the position the dromon entry held"
      (is (= default-middleware-names (mapv :name mw))))

    (testing "preflight is answered by the host's policy, not dromon's"
      (let [resp (app {:request-method :options
                       :uri (str "/" tenant "/fhir/Patient")
                       :headers {"origin" allowed-origin}})]
        (is (= 204 (:status resp)))
        (is (= "custom" (get-in resp [:headers "X-Cors"])))
        (is (nil? (get-in resp [:headers "Access-Control-Allow-Origin"]))
            "dromon's CORS entry is gone, so it cannot reflect the origin")))))

;; ---------------------------------------------------------------------------
;; (c) Recomposition helpers
;; ---------------------------------------------------------------------------

(def ^:private abc [{:name :a} {:name :b} {:name :c}])

(deftest insert-before-splices-ahead-of-the-named-entry
  (is (= [{:name :a} {:name :x} {:name :b} {:name :c}]
         (router/insert-before abc :b {:name :x})))
  (testing "the first entry is addressable (index 0 is not treated as absent)"
    (is (= [{:name :x} {:name :a} {:name :b} {:name :c}]
           (router/insert-before abc :a {:name :x}))))
  (testing "several entries splice in the order given"
    (is (= [{:name :a} {:name :x} {:name :y} {:name :b} {:name :c}]
           (router/insert-before abc :b {:name :x} {:name :y})))))

(deftest insert-after-splices-behind-the-named-entry
  (is (= [{:name :a} {:name :b} {:name :x} {:name :c}]
         (router/insert-after abc :b {:name :x})))
  (testing "appending after the last entry is allowed"
    (is (= [{:name :a} {:name :b} {:name :c} {:name :x}]
           (router/insert-after abc :c {:name :x}))))
  (testing "several entries splice in the order given"
    (is (= [{:name :a} {:name :b} {:name :x} {:name :y} {:name :c}]
           (router/insert-after abc :b {:name :x} {:name :y})))))

(deftest replace-middleware-swaps-in-place
  (is (= [{:name :a} {:name :x} {:name :c}]
         (router/replace-middleware abc :b {:name :x})))
  (testing "the result is still a vector, so helpers compose"
    (is (vector? (router/replace-middleware abc :b {:name :x})))))

(deftest helpers-throw-on-an-unknown-name
  (doseq [[label f] {"insert-before"      #(router/insert-before abc :nope {:name :x})
                     "insert-after"       #(router/insert-after abc :nope {:name :x})
                     "replace-middleware" #(router/replace-middleware abc :nope {:name :x})}]
    (testing (str label " reports the missing name and the known ones")
      (let [ex (is (thrown? clojure.lang.ExceptionInfo (f)))
            data (ex-data ex)]
        (is (= :nope (:name data)))
        (is (= [:a :b :c] (:known data)))))))

;; ---------------------------------------------------------------------------
;; (d) Back-compat aliases
;; ---------------------------------------------------------------------------

(deftest server-core-aliases-point-at-the-router-vars
  (testing "external consumers (test-server tests, the jib2 contract spike)
            still reach these through server.core"
    (is (identical? sc/java-time-encode-mapper router/java-time-encode-mapper))
    (is (identical? sc/java-time-decode-mapper router/java-time-decode-mapper))
    (is (identical? sc/muuntaja-instance router/muuntaja-instance))
    (is (identical? sc/wrap-fhir-store router/wrap-fhir-store))
    (is (identical? sc/wrap-terminology router/wrap-terminology))
    (is (identical? sc/wrap-bulk-job-store router/wrap-bulk-job-store))
    (is (identical? sc/wrap-keto-url router/wrap-keto-url))))

;; ---------------------------------------------------------------------------
;; resolve-options
;; ---------------------------------------------------------------------------

(deftest resolve-options-prefers-explicit-arguments-over-the-environment
  (let [opts (router/resolve-options
               {:jwks-url jwks-url
                :keto-url keto-url
                :enforce-smart-scopes? false
                :cors-allowed-origins "https://a.example, https://b.example"
                :terminology :term
                :bulk-job-store :jobs})]
    (is (= jwks-url (:jwks-url opts)))
    (is (= keto-url (:keto-url opts)))
    (is (false? (:enforce-smart-scopes? opts)))
    (is (= #{"https://a.example" "https://b.example"} (:cors-origins opts))
        "a comma-separated string is split and trimmed into a set")
    (is (= :term (:terminology opts)))
    (is (= :jobs (:bulk-job-store opts)))))

(deftest parse-cors-origins-normalizes-every-accepted-shape
  (is (nil? (router/parse-cors-origins nil)))
  (is (nil? (router/parse-cors-origins "  ")))
  (is (= #{"a"} (router/parse-cors-origins "a")))
  (is (= #{"a" "b"} (router/parse-cors-origins "a, b")))
  (is (= #{"a" "b"} (router/parse-cors-origins ["a" "b"])))
  (is (= #{"a" "b"} (router/parse-cors-origins #{"a" "b"}))))

;; ---------------------------------------------------------------------------
;; router-options
;; ---------------------------------------------------------------------------

(deftest router-options-carry-the-route-data-hosts-depend-on
  (let [mw   (router/default-middleware nil resolved-opts)
        opts (router/router-options test-schemas mw)]
    (is (nil? (:conflicts opts))
        "route conflicts resolve by insertion order; server.routing relies on it")
    (is (identical? router/muuntaja-instance (get-in opts [:data :muuntaja])))
    (is (some? (get-in opts [:data :coercion])))
    (is (= ["Patient"] (keys (get-in opts [:data :fhir/all-registries]))))
    (is (= mw (get-in opts [:data :middleware])))))
