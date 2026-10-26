(ns server.auth
  (:require [buddy.auth.backends.token :as token]
            [buddy.auth.middleware :as auth-mw]
            [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as bkeys]
            [hato.client :as hc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [taoensso.telemere :as t]
            [fhir-store.trace :as ftrace]))

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

(defn- audience-claim-set
  "The `aud` claim as a set. RFC 7519 allows a single string or an array;
   Hydra always emits an array, empty when no audience was granted."
  [aud]
  (cond
    (nil? aud)        #{}
    (string? aud)     #{aud}
    (sequential? aud) (set aud)
    :else             #{}))

(defn access-token-claims?
  "Whether `claims` are an OAuth access token's rather than an OpenID Connect
   ID token's, judged by the `client_id` claim.

   RFC 9068 (section 2.2) requires `client_id` in a JWT access token, and Hydra's
   `jwt` access-token strategy always writes it. An ID token never carries it:
   it names its client in `aud`, which a bearer check cannot tell apart from a
   resource audience when no audience is configured. Both are signed by the same
   JWKS key, so the signature says nothing about which one was presented."
  [claims]
  (let [client-id (:client_id claims)]
    (and (string? client-id) (not (str/blank? client-id)))))

(defn claims-rejection
  "Why verified `claims` were not issued for this server, or nil when they were.

   `issuer` (a string) must equal the `iss` claim exactly; `audiences` (a
   collection of strings) must intersect the `aud` claim. Either check is
   skipped when its option is nil, and `audiences` is also skipped when empty,
   so a host that configures neither sees no change.

   `access-tokens-only?` refuses any token that is not an access token
   ([[access-token-claims?]]). An ID token is a statement to its client about
   who signed in, and its claims are whatever the login and consent app chose to
   put there, so accepting one as a bearer credential lets those claims stand in
   for an authorization decision nobody made.

   Returns `:iss`, `:aud` or `:client_id`."
  [claims {:keys [issuer audiences access-tokens-only?]}]
  (cond
    (and issuer (not= issuer (:iss claims)))
    :iss

    (and (seq audiences)
         (not-any? (audience-claim-set (:aud claims)) audiences))
    :aud

    (and access-tokens-only? (not (access-token-claims? claims)))
    :client_id))

(defn delegation
  "The two principals of a delegated access token, or nil.

   A token is delegated when its `client_id` is one of `delegating-clients` and
   its `sub` is a different, non-blank subject: the client obtained a token for
   that person (an RFC 7523 JWT-bearer grant, whose `sub` is the assertion's and
   whose `client_id` is the authenticated client), so the client acts and the
   person is who it acts for (RFC 9068 section 2.2). Returns `{:subject
   :client}`. A client's own `client_credentials` token has `sub` = `client_id`
   and is not delegated; a token of any client not listed is not delegated
   either, whatever its `sub`, so a person's own sign-in token never reads as
   one."
  [claims delegating-clients]
  (let [{:keys [sub client_id]} claims
        text? #(and (string? %) (not (str/blank? %)))]
    (when (and (text? sub) (text? client_id) (not= sub client_id)
               (contains? (set delegating-clients) client_id))
      {:subject sub :client client_id})))

(defn wrap-jwt-auth
  "Middleware that validates JWTs from the Authorization header using buddy-auth.
   Adds the :identity key to the request map if a valid token is provided.
   Supports either a static :secret (e.g. for testing/dev HS256) or a :jwks-url
   (e.g. for Ory Hydra OIDC RS256).

   With a :jwks-url, the optional :issuer, :audiences and :access-tokens-only?
   restrict which correctly signed tokens are accepted (see
   [[claims-rejection]]). A JWKS
   proves only that the authorization server signed a token, not that it was
   issued for this server: without these options a token minted for any client
   of the same Hydra is accepted. A token that fails either check is treated
   exactly like one with a bad signature -- no :identity is attached, and the
   downstream authorization middleware answers 401. The HS256 :secret path
   ignores all three options.

   `:delegating-clients` (a collection of client ids, default none) names the
   clients whose tokens for another subject are delegated ([[delegation]]). Such
   a request also carries `:fhir/delegation` `{:subject :client}`, which
   `server.keto/wrap-keto-authorization` checks as the client.

   A request that already carries :identity is passed through untouched. That is
   the BFF seam: flotilla authenticates the opaque session cookie itself and
   injects {:identity {:sub subject}} before delegating here, and buddy's
   authentication-request would overwrite it with nil (no Authorization header
   accompanies a cookie-mode request)."
  [handler {:keys [secret jwks-url issuer audiences access-tokens-only? delegating-clients]
            :or {secret dev-secret}}]
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
                                      :options {:alg :hs256}}))
        claim-checks (when jwks-url {:issuer              issuer
                                     :audiences           audiences
                                     :access-tokens-only? access-tokens-only?})]
    (fn [request]
      (if (:identity request)
        (handler request)
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
              rejected (volatile! nil)
              _ (ftrace/trace!
                  {:id :auth/jwt.verify
                   :data {:kid kid
                          :has-token (some? token)}}
                  (let [req (auth-mw/authentication-request request backend)
                        reason (when-let [claims (and claim-checks (:identity req))]
                                 (claims-rejection claims claim-checks))]
                    (vreset! rejected reason)
                    (vreset! authed
                             (let [req (cond-> req reason (assoc :identity nil))
                                   d   (some-> (:identity req) (delegation delegating-clients))]
                               (cond-> (dissoc req :fhir/delegation)
                                 d (assoc :fhir/delegation d))))
                    ::ok))]
          (when-let [reason @rejected]
            (log/info "Rejected a correctly signed JWT not issued for this server; claim:"
                      (name reason)))
          (handler @authed))))))

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
