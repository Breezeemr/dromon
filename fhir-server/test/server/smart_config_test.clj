(ns server.smart-config-test
  "Tests for dromon's advertised SMART on FHIR surface: the
   .well-known/smart-configuration document and the CapabilityStatement
   SMART-on-FHIR security declaration."
  (:require [clojure.test :refer [deftest is testing]]
            [server.handlers :as handlers]
            [server.scope :as scope]
            [server.smart-discovery :as sd]))

;; ---------------------------------------------------------------------------
;; smart-configuration
;; ---------------------------------------------------------------------------

(def ^:private smart-config-body
  (:body ((handlers/smart-configuration "http://oauth.example") {})))

(deftest smart-configuration-endpoints
  (testing "authorization and token endpoints are derived from the OAuth base URL"
    (is (= "http://oauth.example/oauth2/auth"  (:authorization_endpoint smart-config-body)))
    (is (= "http://oauth.example/oauth2/token" (:token_endpoint smart-config-body)))))

(deftest smart-configuration-keeps-the-issuer-verbatim
  (testing "an issuer with a trailing slash (Hydra's) is published as given,
            and its endpoints carry exactly one slash"
    (let [body (:body ((handlers/smart-configuration "https://oauth.example/") {}))]
      (is (= "https://oauth.example/" (:issuer body)))
      (is (= "https://oauth.example/oauth2/auth" (:authorization_endpoint body)))
      (is (= "https://oauth.example/oauth2/token" (:token_endpoint body)))
      (is (= "https://oauth.example/.well-known/jwks.json" (:jwks_uri body)))))
  (testing "an issuer without one is published as given too"
    (is (= "http://oauth.example" (:issuer smart-config-body)))
    (is (= "http://oauth.example/.well-known/jwks.json" (:jwks_uri smart-config-body)))))

(deftest smart-configuration-reads-the-injected-base-url
  (testing "the 0-arity handler takes the request's :fhir/oauth-base-url"
    (let [body (:body ((handlers/smart-configuration)
                       {:fhir/oauth-base-url "https://oauth.example/"}))]
      (is (= "https://oauth.example/" (:issuer body)))
      (is (= "https://oauth.example/oauth2/auth" (:authorization_endpoint body)))))
  (testing "an explicit base URL wins over the injected one"
    (is (= "http://explicit.example"
           (:issuer (:body ((handlers/smart-configuration "http://explicit.example")
                            {:fhir/oauth-base-url "https://oauth.example/"})))))))

(deftest smart-configuration-advertises-all-scopes
  (testing "every expected SMART scope is advertised"
    (let [advertised (set (:scopes_supported smart-config-body))]
      (doseq [scope ["openid" "profile" "launch" "launch/patient"
                     "patient/*.read" "patient/*.write"
                     "user/*.read" "user/*.write"]]
        (is (contains? advertised scope) (str "missing advertised scope: " scope))))))

(deftest smart-configuration-advertises-backend-services-scopes
  (testing "SMART Backend Services (bulk data) system scopes are advertised"
    (let [advertised (set (:scopes_supported smart-config-body))]
      (doseq [scope ["system/*.read" "system/*.rs"]]
        (is (contains? advertised scope)
            (str "missing backend-services scope: " scope))))))

(deftest smart-configuration-capabilities-and-grants
  (testing "advertises SMART capabilities, grant types and auth methods"
    (let [caps (set (:capabilities smart-config-body))]
      (doseq [cap ["launch-standalone" "client-public" "client-confidential-symmetric"
                   "sso-openid-connect"
                   "permission-offline" "permission-patient" "permission-user"
                   "permission-v1" "permission-v2"]]
        (is (contains? caps cap) (str "missing capability: " cap)))
      (is (not (contains? caps "context-passthrough-banner"))
          "no passthrough launch is performed, so it is not advertised"))
    (is (= ["authorization_code" "client_credentials" "refresh_token"]
           (:grant_types_supported smart-config-body)))
    (is (= ["code"] (:response_types_supported smart-config-body)))
    (is (= ["client_secret_basic" "private_key_jwt"]
           (:token_endpoint_auth_methods_supported smart-config-body)))))

(deftest smart-configuration-backend-services-discovery
  (testing "advertises SMART Backend Services (private_key_jwt) discovery"
    (let [caps (set (:capabilities smart-config-body))]
      (is (contains? caps "client-confidential-asymmetric")
          "missing client-confidential-asymmetric capability"))
    (testing "asymmetric client-assertion signing algorithms are advertised"
      (let [algs (set (:token_endpoint_auth_signing_alg_values_supported smart-config-body))]
        (is (vector? (:token_endpoint_auth_signing_alg_values_supported smart-config-body)))
        (doseq [alg ["RS384" "ES384"]]
          (is (contains? algs alg) (str "missing signing alg: " alg)))))
    (is (some #{"private_key_jwt"}
              (:token_endpoint_auth_methods_supported smart-config-body))
        "token_endpoint_auth_methods_supported must include private_key_jwt")
    (is (some #{"client_credentials"}
              (:grant_types_supported smart-config-body))
        "grant_types_supported must include client_credentials")))

(deftest smart-configuration-stu2-discovery-required-fields
  (testing "document satisfies SMART App Launch STU2 well-known discovery"
    ;; Mirror the assertions in smart_app_launch_test_kit's
    ;; well_known_capabilities_stu2 test so Inferno's discovery group passes.
    (is (string? (:authorization_endpoint smart-config-body)))
    (is (string? (:token_endpoint smart-config-body)))
    (is (vector? (:capabilities smart-config-body)))
    (is (every? string? (:capabilities smart-config-body)))
    (is (some #{"authorization_code"} (:grant_types_supported smart-config-body))
        "grant_types_supported must include authorization_code")
    (is (some #{"S256"} (:code_challenge_methods_supported smart-config-body))
        "code_challenge_methods_supported must include S256")
    (is (not-any? #{"plain"} (:code_challenge_methods_supported smart-config-body))
        "code_challenge_methods_supported must not include plain")
    (testing "sso-openid-connect capability requires issuer + jwks_uri"
      (when (some #{"sso-openid-connect"} (:capabilities smart-config-body))
        (is (string? (:issuer smart-config-body)))
        (is (string? (:jwks_uri smart-config-body)))))))

;; ---------------------------------------------------------------------------
;; CapabilityStatement SMART-on-FHIR security
;; ---------------------------------------------------------------------------

(deftest capability-statement-declares-smart-security
  (testing "the REST security service declares SMART-on-FHIR"
    (let [body     (:body ((handlers/capability-statement []) {}))
          security (-> body :rest first :security)
          codings  (->> (:service security) (mapcat :coding))]
      (is (= "CapabilityStatement" (:resourceType body)))
      (is (some (fn [{:keys [system code]}]
                  (and (= "http://terminology.hl7.org/CodeSystem/restful-security-service" system)
                       (= "SMART-on-FHIR" code)))
                codings)
          "expected a SMART-on-FHIR security service coding"))))

;; ---------------------------------------------------------------------------
;; SMART App Launch 2.2.0 discovery configuration
;; ---------------------------------------------------------------------------

(defn- body-under [discovery]
  (:body ((handlers/smart-configuration)
          {:fhir/oauth-base-url "https://oauth.example/"
           :fhir/smart-discovery (sd/check-smart-discovery discovery)})))

(deftest default-discovery-document
  (let [body (body-under {})]
    (testing "the revocation endpoint is derived from the issuer"
      (is (= "https://oauth.example/oauth2/revoke" (:revocation_endpoint body))))
    (testing "introspection and management are omitted until configured"
      (is (not (contains? body :introspection_endpoint)))
      (is (not (contains? body :introspection_endpoint_auth_methods_supported)))
      (is (not (contains? body :management_endpoint))))
    (testing "scopes: the fixed v1/v2 set in order, then the US Core granular scopes"
      (is (= (into sd/base-scopes scope/us-core-granular-scopes) (:scopes_supported body)))
      (is (= ["openid" "fhirUser" "profile" "launch" "launch/patient" "offline_access"]
             (take 6 (:scopes_supported body))))
      (is (not-any? #{"online_access" "launch/encounter"} (:scopes_supported body))))
    (testing "no launch-ehr or context capability is claimed by default"
      (is (= ["launch-standalone" "client-public" "client-confidential-symmetric"
              "client-confidential-asymmetric" "sso-openid-connect"
              "permission-offline" "permission-patient" "permission-user"
              "permission-v1" "permission-v2"]
             (:capabilities body))))
    (testing "PKCE S256 only"
      (is (= ["S256"] (:code_challenge_methods_supported body))))))

(deftest configured-endpoints-are-published
  (let [body (body-under {:token-endpoint         "https://auth.example/oauth2/token"
                          :introspection-endpoint "https://auth.example/oauth2/introspect"
                          :revocation-endpoint    "https://auth.example/oauth2/revoke"
                          :management-endpoint    "https://app.example/connected-apps"})]
    (is (= "https://auth.example/oauth2/token" (:token_endpoint body)))
    (is (= "https://oauth.example/oauth2/auth" (:authorization_endpoint body)))
    (is (= "https://auth.example/oauth2/introspect" (:introspection_endpoint body)))
    (is (= ["client_secret_basic"] (:introspection_endpoint_auth_methods_supported body)))
    (is (= "https://auth.example/oauth2/revoke" (:revocation_endpoint body)))
    (is (= "https://app.example/connected-apps" (:management_endpoint body))))
  (testing "a revocation endpoint set to false is omitted"
    (is (not (contains? (body-under {:revocation-endpoint false}) :revocation_endpoint)))))

(deftest capabilities-follow-the-flags
  (let [caps (fn [d] (set (:capabilities (body-under d))))]
    (testing "token response context adds the standalone patient and banner contexts"
      (is (every? (caps {:token-response-context? true})
                  ["context-standalone-patient" "context-banner"]))
      (is (not ((caps {:token-response-context? true}) "context-style"))))
    (testing "context-style needs both the token response context and a style"
      (is ((caps {:token-response-context? true :context-style? true}) "context-style"))
      (is (not ((caps {:context-style? true}) "context-style"))))
    (testing "EHR launch adds launch-ehr and its contexts"
      (is (every? (caps {:launch-ehr? true :token-response-context? true})
                  ["launch-ehr" "context-ehr-patient" "context-ehr-encounter"]))
      (is (not ((caps {}) "launch-ehr"))))
    (testing "authorize-post only when enabled"
      (is ((caps {:authorize-post? true}) "authorize-post"))
      (is (not ((caps {}) "authorize-post"))))))

(deftest extra-scopes-replace-the-granular-default
  (is (= (conj sd/base-scopes "patient/Observation.rs?category=x|y")
         (:scopes_supported (body-under {:extra-scopes ["patient/Observation.rs?category=x|y"]})))))

(deftest discovery-configuration-is-validated
  (let [rejects? (fn [d] (try (sd/check-smart-discovery d) false
                             (catch clojure.lang.ExceptionInfo _ true)))]
    (testing "an unknown key throws"
      (is (rejects? {:introspection-url "https://a.example/x"})))
    (testing "URLs must be absolute https, http only on localhost"
      (is (rejects? {:token-endpoint "http://auth.example/oauth2/token"}))
      (is (rejects? {:token-endpoint "/oauth2/token"}))
      (is (rejects? {:introspection-endpoint 42}))
      (is (not (rejects? {:token-endpoint "http://localhost:3001/oauth2/token"})))
      (is (not (rejects? {:token-endpoint "http://127.0.0.1:3001/oauth2/token"}))))
    (testing "flags must be booleans"
      (is (rejects? {:launch-ehr? "true"})))
    (testing "EHR launch without the token response context throws"
      (is (rejects? {:launch-ehr? true}))
      (is (not (rejects? {:launch-ehr? true :token-response-context? true}))))
    (testing "extra scopes must be scope strings"
      (is (rejects? {:extra-scopes "patient/*.rs"}))
      (is (rejects? {:extra-scopes ["patient/*.rs openid"]})))))

(deftest discovery-falls-back-to-the-environment
  (let [env {"SMART_INTROSPECTION_ENDPOINT" "https://auth.example/oauth2/introspect"
             "SMART_LAUNCH_EHR" "1"
             "SMART_TOKEN_RESPONSE_CONTEXT" "true"}
        d (sd/check-smart-discovery (sd/env-smart-discovery #(get env %)))]
    (is (= "https://auth.example/oauth2/introspect" (:introspection-endpoint d)))
    (is (true? (:launch-ehr? d)))
    (is (true? (:token-response-context? d)))
    (is (false? (:authorize-post? d))))
  (testing "a flag that is neither on nor off throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sd/env-smart-discovery #(get {"SMART_LAUNCH_EHR" "yes"} %))))))

;; ---------------------------------------------------------------------------
;; CapabilityStatement metadata and oauth-uris
;; ---------------------------------------------------------------------------

(deftest capability-statement-carries-host-metadata-and-oauth-uris
  (let [req  {:fhir/oauth-base-url "https://oauth.example/"
              :fhir/smart-discovery (sd/check-smart-discovery
                                     {:introspection-endpoint "https://auth.example/oauth2/introspect"})
              :fhir/capability-metadata (sd/check-capability-metadata
                                         {:publisher "BreezeEHR" :software-name "BreezeEHR"
                                          :software-version "abc123"
                                          :implementation-description "BreezeEHR FHIR API"})
              :scheme :http
              :headers {"host" "internal:8080"
                        "x-forwarded-proto" "https"
                        "x-forwarded-host" "fhir.example"}
              :reitit.core/match {:path-params {:tenant-id "dev"}}
              :path-params {:tenant-id "dev"}}
        body (:body ((handlers/capability-statement []) req))
        security (-> body :rest first :security)
        oauth-uris (some #(when (= sd/oauth-uris-url (:url %)) %) (:extension security))
        uri-of (fn [n] (some #(when (= n (:url %)) (:valueUri %)) (:extension oauth-uris)))]
    (is (= "BreezeEHR" (:publisher body)))
    (is (= {:name "BreezeEHR" :version "abc123"} (:software body)))
    (is (= {:description "BreezeEHR FHIR API" :url "https://fhir.example/dev/fhir"}
           (:implementation body)))
    (is (true? (:cors security)))
    (is (some? oauth-uris))
    (is (= "https://oauth.example/oauth2/auth" (uri-of "authorize")))
    (is (= "https://oauth.example/oauth2/token" (uri-of "token")))
    (is (= "https://auth.example/oauth2/introspect" (uri-of "introspect")))
    (is (= "https://oauth.example/oauth2/revoke" (uri-of "revoke")))
    (is (nil? (uri-of "manage")))))

(deftest capability-statement-defaults
  (let [body (:body ((handlers/capability-statement [])
                     {:scheme :https :headers {"host" "fhir.local:8443"}
                      :path-params {:tenant-id "default"}}))]
    (is (= "Dromon" (:publisher body)))
    (is (= {:name "Dromon FHIR Server"} (:software body)))
    (is (= "https://fhir.local:8443/default/fhir" (get-in body [:implementation :url])))))

(deftest capability-metadata-is-validated
  (is (thrown? clojure.lang.ExceptionInfo (sd/check-capability-metadata {:vendor "x"})))
  (is (thrown? clojure.lang.ExceptionInfo (sd/check-capability-metadata {:publisher ""})))
  (is (= "Dromon FHIR Server" (:software-name (sd/check-capability-metadata nil)))))
