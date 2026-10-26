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

(deftest wrap-jwt-auth-preserves-injected-identity
  (testing "a request whose identity was established upstream passes through
            untouched -- this is the BFF seam: flotilla authenticates the
            opaque session cookie and injects :identity, and there is no
            Authorization header for buddy to derive one from, so running the
            backend would replace it with nil"
    (let [seen (atom nil)
          handler (fn [req] (reset! seen (:identity req)) {:status 200 :body "OK"})
          wrapped (auth/wrap-jwt-auth handler {:secret "test-secret"})
          response (wrapped {:headers {} :identity {:sub "kratos-id-1"}})]
      (is (= 200 (:status response)))
      (is (= {:sub "kratos-id-1"} @seen))))

  (testing "a request without an injected identity still runs the JWT backend"
    (let [seen (atom ::unset)
          handler (fn [req] (reset! seen (:identity req)) {:status 200 :body "OK"})
          wrapped (auth/wrap-jwt-auth handler {:secret "test-secret"})
          token (jwt/sign {:sub "token-user"} "test-secret" {:alg :hs256})]
      (wrapped {:headers {"authorization" (str "Token " token)}})
      (is (= {:sub "token-user"} @seen)))))

;; ---------------------------------------------------------------------------
;; Issuer and audience checks
;; ---------------------------------------------------------------------------

(def ^:private hydra-issuer "https://oauth.example/")

(defn- hydra-token
  "A token correctly signed by the mock JWKS key, carrying `claims`."
  [claims]
  (jwt/sign (merge {:sub "user123"} claims) mock-private-key
            {:alg :rs256 :header {:kid "mock-key-1"}}))

(defn- authenticate
  "Run `claims` through wrap-jwt-auth with `opts` (merged over a JWKS URL) and
   return the :identity the inner handler saw, or ::rejected when it saw none."
  [opts claims]
  (with-redefs [auth/fetch-jwks (fn [_] {"mock-key-1" mock-public-key})]
    (let [seen    (atom ::unset)
          wrapped (auth/wrap-jwt-auth (fn [req] (reset! seen (:identity req)) {:status 200})
                                      (merge {:jwks-url "http://mock-hydra"} opts))]
      (wrapped {:headers {"authorization" (str "Bearer " (hydra-token claims))}})
      (or @seen ::rejected))))

(deftest claims-rejection-test
  (testing "no options checks nothing"
    (is (nil? (auth/claims-rejection {} {})))
    (is (nil? (auth/claims-rejection {:iss "anyone"} {:audiences []}))
        "an empty audience list is the same as none"))
  (testing "issuer must match verbatim, trailing slash included"
    (is (nil? (auth/claims-rejection {:iss hydra-issuer} {:issuer hydra-issuer})))
    (is (= :iss (auth/claims-rejection {:iss "https://oauth.example"} {:issuer hydra-issuer})))
    (is (= :iss (auth/claims-rejection {} {:issuer hydra-issuer}))))
  (testing "aud may be a string or an array and must intersect the accepted list"
    (is (nil? (auth/claims-rejection {:aud "flotilla"} {:audiences ["flotilla"]})))
    (is (nil? (auth/claims-rejection {:aud ["other" "flotilla"]} {:audiences ["mcp" "flotilla"]})))
    (is (= :aud (auth/claims-rejection {:aud ["other"]} {:audiences ["flotilla"]})))
    (is (= :aud (auth/claims-rejection {:aud []} {:audiences ["flotilla"]})))
    (is (= :aud (auth/claims-rejection {} {:audiences ["flotilla"]})))))

(deftest wrap-jwt-auth-issuer-check
  (testing "a token from the configured issuer is accepted"
    (is (= "user123" (:sub (authenticate {:issuer hydra-issuer} {:iss hydra-issuer})))))

  (testing "a correctly signed token from another issuer gets no identity"
    (is (= ::rejected (authenticate {:issuer hydra-issuer} {:iss "https://other.example/"}))))

  (testing "a token with no iss claim gets no identity"
    (is (= ::rejected (authenticate {:issuer hydra-issuer} {}))))

  (testing "a rejected token is refused downstream with the 401 OperationOutcome"
    (with-redefs [auth/fetch-jwks (fn [_] {"mock-key-1" mock-public-key})]
      (let [wrapped  (-> (fn [_] {:status 200})
                         auth/wrap-require-auth
                         (auth/wrap-jwt-auth {:jwks-url "http://mock-hydra" :issuer hydra-issuer}))
            response (wrapped {:headers {"authorization"
                                         (str "Bearer " (hydra-token {:iss "https://other.example/"}))}})]
        (is (= 401 (:status response)))
        (is (= "OperationOutcome" (get-in response [:body :resourceType]))))))

  (testing "without :issuer any issuer is accepted, as before"
    (is (= "user123" (:sub (authenticate {} {:iss "https://other.example/"}))))))

(deftest wrap-jwt-auth-audience-check
  (let [opts {:issuer hydra-issuer :audiences ["https://flotilla.example/fhir"]}]
    (testing "a token whose aud includes an accepted audience is accepted"
      (is (= "user123" (:sub (authenticate opts {:iss hydra-issuer
                                                 :aud ["https://flotilla.example/fhir"]})))))

    (testing "a token for another audience gets no identity"
      (is (= ::rejected (authenticate opts {:iss hydra-issuer :aud ["https://rcm.example"]}))))

    (testing "a token with an empty or missing aud gets no identity"
      (is (= ::rejected (authenticate opts {:iss hydra-issuer :aud []})))
      (is (= ::rejected (authenticate opts {:iss hydra-issuer}))))

    (testing "the issuer is still checked when the audience matches"
      (is (= ::rejected (authenticate opts {:iss "https://other.example/"
                                            :aud ["https://flotilla.example/fhir"]})))))

  (testing "with no :audiences, a missing or foreign aud is accepted (issuer-only mode)"
    (is (= "user123" (:sub (authenticate {:issuer hydra-issuer} {:iss hydra-issuer}))))
    (is (= "user123" (:sub (authenticate {:issuer hydra-issuer} {:iss hydra-issuer :aud []}))))
    (is (= "user123" (:sub (authenticate {:issuer hydra-issuer :audiences []}
                                         {:iss hydra-issuer :aud ["anything"]}))))))

(deftest wrap-jwt-auth-claim-checks-leave-other-paths-alone
  (testing "an injected identity passes through untouched under both checks (BFF seam)"
    (with-redefs [auth/fetch-jwks (fn [_] (throw (Exception. "the backend must not run")))]
      (let [seen    (atom nil)
            wrapped (auth/wrap-jwt-auth (fn [req] (reset! seen (:identity req)) {:status 200})
                                        {:jwks-url  "http://mock-hydra"
                                         :issuer    hydra-issuer
                                         :audiences ["https://flotilla.example/fhir"]})]
        (is (= 200 (:status (wrapped {:headers {} :identity {:sub "kratos-id-1"}}))))
        (is (= {:sub "kratos-id-1"} @seen)))))

  (testing "the HS256 dev path ignores :issuer and :audiences"
    (let [seen    (atom nil)
          wrapped (auth/wrap-jwt-auth (fn [req] (reset! seen (:identity req)) {:status 200})
                                      {:secret    "test-secret"
                                       :issuer    hydra-issuer
                                       :audiences ["https://flotilla.example/fhir"]})
          token   (jwt/sign {:sub "token-user"} "test-secret" {:alg :hs256})]
      (wrapped {:headers {"authorization" (str "Token " token)}})
      (is (= {:sub "token-user"} @seen)))))

;; ---------------------------------------------------------------------------
;; Access tokens only
;; ---------------------------------------------------------------------------

(def ^:private hydra-access-token-claims
  "The claim shape of a Hydra `jwt`-strategy access token."
  {:iss hydra-issuer :aud [] :client_id "jib3" :scp ["openid" "user/*.read"]
   :jti "at-1" :ext {}})

(def ^:private hydra-id-token-claims
  "The claim shape of a Hydra ID token for the same sign-in: the client is named
   in `aud`, there is no `client_id`, and the login and consent app's claims
   (here a self-asserted `fhirUser`) sit at the top level."
  {:iss hydra-issuer :aud ["jib3"] :at_hash "h" :auth_time 1 :rat 1 :sid "s"
   :amr ["pwd"] :fhirUser "Practitioner/forged"})

(deftest claims-rejection-access-tokens-only
  (let [opts {:issuer hydra-issuer :access-tokens-only? true}]
    (testing "an access token is accepted"
      (is (nil? (auth/claims-rejection hydra-access-token-claims opts))))
    (testing "an ID token, a blank client_id and a non-string one are refused"
      (is (= :client_id (auth/claims-rejection hydra-id-token-claims opts)))
      (is (= :client_id (auth/claims-rejection (assoc hydra-access-token-claims :client_id "") opts)))
      (is (= :client_id (auth/claims-rejection (assoc hydra-access-token-claims :client_id ["jib3"]) opts))))
    (testing "the issuer is checked first"
      (is (= :iss (auth/claims-rejection (assoc hydra-access-token-claims :iss "https://other.example/")
                                         opts)))))
  (testing "off unless asked for"
    (is (nil? (auth/claims-rejection hydra-id-token-claims {:issuer hydra-issuer})))))

(deftest wrap-jwt-auth-access-tokens-only
  (let [opts {:issuer hydra-issuer :access-tokens-only? true}]
    (testing "a Hydra access token authenticates"
      (is (= "user123" (:sub (authenticate opts hydra-access-token-claims)))))
    (testing "a Hydra ID token, correctly signed by the same key, gets no identity"
      (is (= ::rejected (authenticate opts hydra-id-token-claims))))
    (testing "and is refused downstream with the 401 OperationOutcome"
      (with-redefs [auth/fetch-jwks (fn [_] {"mock-key-1" mock-public-key})]
        (let [wrapped  (-> (fn [_] {:status 200})
                           auth/wrap-require-auth
                           (auth/wrap-jwt-auth (assoc opts :jwks-url "http://mock-hydra")))
              response (wrapped {:headers {"authorization"
                                           (str "Bearer " (hydra-token hydra-id-token-claims))}})]
          (is (= 401 (:status response)))))))

  (testing "the HS256 dev path is unaffected"
    (let [seen    (atom nil)
          wrapped (auth/wrap-jwt-auth (fn [req] (reset! seen (:identity req)) {:status 200})
                                      {:secret "test-secret" :access-tokens-only? true})
          token   (jwt/sign {:sub "token-user"} "test-secret" {:alg :hs256})]
      (wrapped {:headers {"authorization" (str "Token " token)}})
      (is (= {:sub "token-user"} @seen)))))

(deftest delegation-test
  (let [clients #{"svc"}]
    (testing "a listed client's token for another subject is delegated"
      (is (= {:subject "person-1" :client "svc"}
             (auth/delegation {:sub "person-1" :client_id "svc"} clients))))
    (testing "the client's own client_credentials token is not"
      (is (nil? (auth/delegation {:sub "svc" :client_id "svc"} clients))))
    (testing "a client that is not listed is not, whatever its sub"
      (is (nil? (auth/delegation {:sub "person-1" :client_id "sign-in-app"} clients))))
    (testing "no listed clients means no delegation"
      (is (nil? (auth/delegation {:sub "person-1" :client_id "svc"} nil))))
    (testing "a token with no client_id (an ID token) or a blank sub is not"
      (is (nil? (auth/delegation {:sub "person-1"} clients)))
      (is (nil? (auth/delegation {:sub " " :client_id "svc"} clients))))))

(defn- delegation-seen
  "Run `claims` through wrap-jwt-auth (JWKS path, `opts` merged) and return the
   `:fhir/delegation` the inner handler saw. `extra` is merged into the request."
  ([opts claims] (delegation-seen opts claims {}))
  ([opts claims extra]
   (with-redefs [auth/fetch-jwks (fn [_] {"mock-key-1" mock-public-key})]
     (let [seen    (atom ::unset)
           wrapped (auth/wrap-jwt-auth (fn [req] (reset! seen (:fhir/delegation req)) {:status 200})
                                       (merge {:jwks-url "http://mock-hydra"} opts))]
       (wrapped (merge {:headers {"authorization" (str "Bearer " (hydra-token claims))}} extra))
       @seen))))

(deftest wrap-jwt-auth-attaches-delegation
  (testing "a delegated token carries both principals"
    (is (= {:subject "person-1" :client "svc"}
           (delegation-seen {:delegating-clients #{"svc"}} {:sub "person-1" :client_id "svc"}))))
  (testing "without :delegating-clients nothing is delegated"
    (is (nil? (delegation-seen {} {:sub "person-1" :client_id "svc"}))))
  (testing "a token refused by a claim check carries no delegation"
    (is (nil? (delegation-seen {:delegating-clients #{"svc"} :issuer "https://other"}
                               {:sub "person-1" :client_id "svc" :iss "https://hydra"}))))
  (testing "a caller cannot supply its own :fhir/delegation"
    (is (nil? (delegation-seen {:delegating-clients #{"svc"}} {:sub "svc" :client_id "svc"}
                               {:fhir/delegation {:subject "someone" :client "svc"}})))))
