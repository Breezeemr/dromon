(ns server.auth
  (:require [buddy.auth.backends.token :as token]
            [buddy.auth.middleware :as auth-mw]
            [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as bkeys]
            [hato.client :as hc]
            [clojure.tools.logging :as log]
            [taoensso.telemere :as t]))

(def ^:private dev-secret
  (when-let [s (System/getenv "JWT_DEV_SECRET")]
    s))

(defn fetch-jwks
  "Fetches the JWKS payload from a URL and parses the keys into a map
   keyed by 'kid' (Key ID). Valid keys are converted to public keys."
  [jwks-url]
  (try
    (let [response (hc/get jwks-url {:as :json})
          keys (:keys (:body response))]
      (reduce 
       (fn [acc jwk-data]
         (if-let [kid (:kid jwk-data)]
           (assoc acc kid (bkeys/jwk->public-key jwk-data))
           acc))
       {}
       keys))
    (catch Exception e
      (log/error e "Failed to fetch or parse JWKS from" jwks-url)
      {})))

(defn build-jwks-secret-fn
  "Returns a function that buddy-auth can use as the :secret to dynamically
   resolve the public key based on the 'kid' in the incoming JWT header.
   Re-fetches JWKS on cache miss to support key rotation."
  [jwks-url]
  (let [keystore (atom nil)
        resolve-key (fn [kid]
                      ;; Lazy load the keystore on first request
                      (when (nil? @keystore)
                        (reset! keystore (fetch-jwks jwks-url)))
                      (if-let [pub-key (get @keystore kid)]
                        pub-key
                        ;; Cache miss: re-fetch JWKS in case keys were rotated
                        (do
                          (log/info "JWKS cache miss for kid:" kid "- re-fetching from" jwks-url)
                          (let [refreshed (fetch-jwks jwks-url)]
                            (reset! keystore refreshed)
                            (or (get refreshed kid)
                                (do (log/warn "No matching key found for kid:" kid "after JWKS refresh")
                                    nil))))))]
    (fn 
      ([req token-or-payload] 
       ;; buddy-auth wrap-authentication passes (request token-string)
       (let [kid (try
                   (if (string? token-or-payload)
                     (:kid (jwt/decode-header token-or-payload))
                     nil)
                   (catch Exception _ nil))]
         (if kid
           (resolve-key kid)
           (do
             (log/error "Failed to extract kid from JWT token")
             nil))))
      ([header-map]
       ;; buddy.sign.jws/unsign passes the parsed header map directly to the secret fn!
       (let [kid (:kid header-map)]
         (if kid
           (resolve-key kid)
           (do
             (log/error "Failed to extract kid from JWT header map")
             nil)))))))

(defn wrap-jwt-auth
  "Middleware that validates JWTs from the Authorization header using buddy-auth.
   Adds the :identity key to the request map if a valid token is provided.
   Supports either a static :secret (e.g. for testing/dev HS256) or a :jwks-url
   (e.g. for Ory Hydra OIDC RS256)."
  [handler {:keys [secret jwks-url] :or {secret dev-secret}}]
  (when (and (nil? jwks-url) (nil? secret))
    (throw (ex-info "JWT auth requires either :jwks-url or :secret (set JWT_DEV_SECRET env var for dev)" {})))
  (let [backend (if jwks-url
                  (token/jws-backend {:secret (build-jwks-secret-fn jwks-url)
                                      :options {:alg :rs256}
                                      :token-name "Bearer"
                                      :unauthorized-handler (fn [req _] 
                                                              {:status 401 
                                                               :body {:resourceType "OperationOutcome"
                                                                      :issue [{:severity "error"
                                                                               :code "login"
                                                                               :diagnostics "Invalid or missing token"}]}})})
                  (token/jws-backend {:secret secret
                                      :options {:alg :hs256}}))]
    (fn [request]
      (let [auth-header (get-in request [:headers "authorization"])
            token (when (and auth-header (.startsWith ^String auth-header "Bearer "))
                    (subs auth-header 7))
            kid (try
                  (when token (:kid (jwt/decode-header token)))
                  (catch Exception _ nil))
            ;; The trace body would otherwise return the authenticated
            ;; request, which Telemere captures as the signal's :run-val.
            ;; Ring requests carry :reitit.core/match and :fhir/store,
            ;; both of which can OOM the pr-str fallback serializer.
            ;; Capture the real result via a volatile and return ::ok.
            authed (volatile! nil)
            _ (t/trace!
                {:id :auth/jwt.verify
                 :data {:kid kid
                        :has-token (some? token)}}
                (do (vreset! authed (auth-mw/authentication-request request backend))
                    ::ok))]
        (handler @authed)))))

(defn- extract-identity [request]
  ;; buddy-auth will populate :identity in the request map if validation passes
  (:identity request))

(defn wrap-require-auth
  "Optional middleware to strictly enforce that a valid identity exists."
  [handler]
  (fn [request]
    (if-let [identity (extract-identity request)]
      (handler request)
      {:status 401
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "login"
                       :diagnostics "Unauthorized: Valid bearer token required"}]}})))
