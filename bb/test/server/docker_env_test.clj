(ns server.docker-env-test
  "The auth stack's pure Kratos config rendering. The failure these guard
   against is silent: with Kratos and the login app on different hosts and no
   shared cookie domain, the login succeeds but the session never reaches the
   login app, and every sign-in ends in login-consent's `login_loop` page."
  (:require [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [server.docker-env :as env]))

(deftest shared-cookie-domain-is-the-parent-both-hosts-share
  (testing "this stack under local*.breezeehr.com: different hosts, one parent"
    (is (= "breezeehr.com"
           (env/shared-cookie-domain "https://localkratos.breezeehr.com:4433"
                                     "https://localauth.breezeehr.com:3001"))))
  (testing "the longest shared suffix, not the shortest"
    (is (= "dev.example.org"
           (env/shared-cookie-domain "https://kratos.dev.example.org"
                                     "https://auth.dev.example.org"))))
  (testing "a bare TLD is never a cookie domain"
    (is (nil? (env/shared-cookie-domain "https://kratos.example.com"
                                        "https://login.other.com")))
    (is (nil? (env/shared-cookie-domain "https://a.com" "https://b.com")))))

(deftest the-default-stack-needs-no-cookie-domain
  (testing "localhost on both sides: one host, nothing to share, and browsers
            reject Domain=localhost"
    (is (nil? (env/shared-cookie-domain "https://localhost:4433" "https://localhost:3001"))))
  (testing "the same host on different ports is still one host"
    (is (nil? (env/shared-cookie-domain "https://auth.example.com:4433"
                                        "https://auth.example.com:3001"))))
  (testing "localhost against a named host, or an IP, is not shareable"
    (is (nil? (env/shared-cookie-domain "https://localhost:4433" "https://localauth.breezeehr.com:3001")))
    (is (nil? (env/shared-cookie-domain "https://127.0.0.1:4433" "https://127.0.0.2:3001")))
    (is (nil? (env/shared-cookie-domain "https://[::1]:4433" "https://[::1]:3001")))))

(deftest cookies-block-is-empty-without-a-domain
  (is (= "" (env/cookies-block nil "ory_kratos_session_local"))))

(def ^:private template (slurp "docker/kratos.yml"))

(def ^:private urls
  {:kratos-public "https://localkratos.breezeehr.com:4433"
   :kratos-admin  "https://localkratos.breezeehr.com:4434"})

(deftest rendering-under-separate-hosts-scopes-the-cookies-to-the-parent
  (let [out (yaml/parse-string
             (env/render-kratos-config template urls "https://localauth.breezeehr.com:3001"
                                       "ory_kratos_session_local"))]
    (is (= "breezeehr.com" (get-in out [:cookies :domain])))
    (is (= "breezeehr.com" (get-in out [:session :cookie :domain])))
    (testing "the session cookie keeps a name of its own, so it cannot shadow or
              overwrite a production ory_kratos_session in the same browser"
      (is (= "ory_kratos_session_local" (get-in out [:session :cookie :name]))))
    (testing "the rest of the template is untouched"
      (is (= "https://localkratos.breezeehr.com:4433/" (get-in out [:serve :public :base_url])))
      (is (= ["https://localauth.breezeehr.com:3001"]
             (get-in out [:selfservice :allowed_return_urls]))))))

(deftest rendering-the-default-stack-adds-no-cookie-config
  (let [out (env/render-kratos-config template
                                      {:kratos-public "https://localhost:4433"
                                       :kratos-admin  "https://localhost:4434"}
                                      "https://localhost:3001" "ignored")
        parsed (yaml/parse-string out)]
    (is (nil? (:cookies parsed)))
    (is (nil? (:session parsed)))
    (is (not (str/includes? out "ory_kratos_session")))))

(deftest every-placeholder-is-filled
  (is (nil? (re-find #"\{\{[A-Z_]+\}\}"
                     (env/render-kratos-config template urls "https://localauth.breezeehr.com:3001" "n")))))
