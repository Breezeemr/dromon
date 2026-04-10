(ns server.auth-test
  (:require [clojure.test :refer [deftest is testing]]
            [server.auth :as auth]
            [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as bkeys]
            [hato.client :as hc]))

;; Mock keys for testing
(def mock-rs256-keypair
  (let [kpg (java.security.KeyPairGenerator/getInstance "RSA")]
    (.initialize kpg 2048)
    (.generateKeyPair kpg)))

(def mock-public-key (.getPublic mock-rs256-keypair))
(def mock-private-key (.getPrivate mock-rs256-keypair))

(def mock-jwks
  {:keys [{:kty "RSA"
           :kid "mock-key-1"
           :alg "RS256"
           :use "sig"
           :n "..." ;; We don't need real n/e if we mock the fetch
           :e "..."}]})

(deftest fetch-jwks-test
  (testing "fetches and parses JWKS successfully"
    ;; Mock hato client getting the JWKS payload
    (with-redefs [hc/get (fn [_ _] 
                           {:body mock-jwks})
                  bkeys/jwk->public-key (fn [jwk] 
                                        (if (= "mock-key-1" (:kid jwk))
                                          mock-public-key
                                          (throw (Exception. "Unexpected kid"))))]
      (let [keys (auth/fetch-jwks "http://mock-hydra/.well-known/jwks.json")]
        (is (contains? keys "mock-key-1"))
        (is (= mock-public-key (get keys "mock-key-1"))))))

  (testing "handles fetch errors gracefully"
    (with-redefs [hc/get (fn [_ _] (throw (Exception. "Network error")))]
      (is (= {} (auth/fetch-jwks "http://mock-hydra/.well-known/jwks.json"))))))

(deftest wrap-jwt-auth-test
  (testing "validates JWT using fetched JWKS"
    (with-redefs [auth/fetch-jwks (fn [_] {"mock-key-1" mock-public-key})]
      (let [handler (fn [req] req)
            wrapped-handler (auth/wrap-jwt-auth handler {:jwks-url "http://mock-hydra/.well-known/jwks.json"})
            ;; Create a valid token signed with our mock private key
            claims {:sub "user123" :iss "http://mock-hydra"}
            token (jwt/sign claims mock-private-key {:alg :rs256
                                                     :header {:kid "mock-key-1"}})
            request {:headers {"authorization" (str "Bearer " token)}}
            response (wrapped-handler request)]
        
        ;; The middleware should have attached the identity payload to the request
        (is (= "user123" (-> response :identity :sub))))))
  
  (testing "rejects invalid JWT signature"
    (with-redefs [auth/fetch-jwks (fn [_] {"mock-key-1" mock-public-key})]
      (let [handler (fn [_] {:status 200})
            wrapped-handler (-> handler
                                auth/wrap-require-auth
                                (auth/wrap-jwt-auth {:jwks-url "http://mock-hydra"}))
            ;; Sign with a different key
            other-keypair (let [kpg (java.security.KeyPairGenerator/getInstance "RSA")]
                            (.initialize kpg 2048)
                            (.generateKeyPair kpg))
            token (jwt/sign {:sub "user123"} (.getPrivate other-keypair)
                            {:alg :rs256 :header {:kid "mock-key-1"}})
            request {:headers {"authorization" (str "Bearer " token)}}
            response (wrapped-handler request)]
        (is (= 401 (:status response)))))))

(deftest wrap-require-auth-test
  (testing "allows request with identity"
    (let [handler (fn [req] {:status 200 :body "OK"})
          wrapped (auth/wrap-require-auth handler)]
      (is (= {:status 200 :body "OK"} (wrapped {:identity {:sub "test-user"}})))))
  
  (testing "rejects request without identity"
    (let [handler (fn [req] {:status 200})
          wrapped (auth/wrap-require-auth handler)
          response (wrapped {})]
      (is (= 401 (:status response)))
      (is (= "OperationOutcome" (:resourceType (:body response)))))))
