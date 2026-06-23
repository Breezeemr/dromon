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
