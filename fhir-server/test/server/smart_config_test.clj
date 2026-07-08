(ns server.smart-config-test
  "Tests for dromon's advertised SMART on FHIR surface: the
   .well-known/smart-configuration document and the CapabilityStatement
   SMART-on-FHIR security declaration."
  (:require [clojure.test :refer [deftest is testing]]
            [server.handlers :as handlers]))

;; ---------------------------------------------------------------------------
;; smart-configuration
;; ---------------------------------------------------------------------------

(def ^:private smart-config-body
  (:body ((handlers/smart-configuration "http://oauth.example") {})))

(deftest smart-configuration-endpoints
  (testing "authorization and token endpoints are derived from the OAuth base URL"
    (is (= "http://oauth.example/oauth2/auth"  (:authorization_endpoint smart-config-body)))
    (is (= "http://oauth.example/oauth2/token" (:token_endpoint smart-config-body)))))

(deftest smart-configuration-advertises-all-scopes
  (testing "every expected SMART scope is advertised"
    (let [advertised (set (:scopes_supported smart-config-body))]
      (doseq [scope ["openid" "profile" "launch" "launch/patient"
                     "patient/*.read" "patient/*.write"
                     "user/*.read" "user/*.write"]]
        (is (contains? advertised scope) (str "missing advertised scope: " scope))))))

(deftest smart-configuration-capabilities-and-grants
  (testing "advertises SMART capabilities, grant types and auth methods"
    (let [caps (set (:capabilities smart-config-body))]
      (doseq [cap ["launch-standalone" "client-public" "client-confidential-symmetric"
                   "sso-openid-connect" "context-passthrough-banner"
                   "permission-offline" "permission-patient" "permission-user"]]
        (is (contains? caps cap) (str "missing capability: " cap))))
    (is (= ["authorization_code" "client_credentials"]
           (:grant_types_supported smart-config-body)))
    (is (= ["code"] (:response_types_supported smart-config-body)))
    (is (= ["client_secret_basic" "private_key_jwt"]
           (:token_endpoint_auth_methods_supported smart-config-body)))))

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
