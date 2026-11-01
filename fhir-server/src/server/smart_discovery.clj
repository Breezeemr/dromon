(ns server.smart-discovery
  "What dromon advertises about its authorization surface: the SMART App Launch
   2.2.0 discovery document (`.well-known/smart-configuration`) and the
   CapabilityStatement's software and SMART `oauth-uris` declarations.

   A host configures both through `server.router/resolve-options`:

   - `:smart-discovery` -- endpoints and feature flags; see
     [[check-smart-discovery]]. When the host passes none, the `SMART_*`
     environment variables are read (see [[env-smart-discovery]]).
   - `:capability-metadata` -- publisher and software strings; see
     [[check-capability-metadata]].

   Both are validated once, at startup. A misspelled key, a URL that is not
   absolute https, or an impossible flag combination throws there rather than
   publishing a document that promises what the server cannot do."
  (:require [clojure.string :as str]
            [server.scope :as scope])
  (:import [java.net URI URISyntaxException]))

;; ---------------------------------------------------------------------------
;; :smart-discovery
;; ---------------------------------------------------------------------------

(def default-smart-discovery
  "Every `:smart-discovery` key with the value a host gets when it omits it."
  {:token-endpoint          nil
   :introspection-endpoint  nil
   :revocation-endpoint     nil
   :management-endpoint     nil
   :launch-ehr?             false
   :token-response-context? false
   :context-style?          false
   :authorize-post?         false
   :extra-scopes            scope/us-core-granular-scopes})

(def ^:private url-keys
  [:token-endpoint :introspection-endpoint :revocation-endpoint :management-endpoint])

(def ^:private flag-keys
  [:launch-ehr? :token-response-context? :context-style? :authorize-post?])

(defn- local-host? [host]
  (contains? #{"localhost" "127.0.0.1"} host))

(defn check-url
  "`value` when it is an absolute https URL (http only for localhost or
   127.0.0.1), else throws naming `k`."
  [k value]
  (let [bad (fn [why]
              (throw (ex-info (str (pr-str k) " " why) {:key k :value value})))
        uri (try (when (string? value) (URI. value))
                 (catch URISyntaxException _ nil))]
    (when-not uri
      (bad "must be an absolute URL string"))
    (let [scheme (some-> (.getScheme uri) str/lower-case)
          host   (.getHost uri)]
      (when-not (and (.isAbsolute uri) host)
        (bad "must be an absolute URL"))
      (when-not (or (= "https" scheme)
                    (and (= "http" scheme) (local-host? host)))
        (bad "must use https (http is accepted only for localhost and 127.0.0.1)"))
      value)))

(defn check-smart-discovery
  "Validates a host's `:smart-discovery` map and returns it with every key of
   [[default-smart-discovery]] filled in. Throws ex-info on an unknown key, a
   URL that is not absolute https (see [[check-url]]), a flag that is not a
   boolean, an `:extra-scopes` that is not a sequence of scope strings, or
   `:launch-ehr? true` without `:token-response-context? true`: an EHR launch
   whose token response carries no `patient` cannot be completed by any app.

   `:revocation-endpoint` may also be `false`, which omits it from discovery."
  [discovery]
  (when-not (or (nil? discovery) (map? discovery))
    (throw (ex-info ":smart-discovery must be a map" {:value discovery})))
  (when-let [unknown (seq (remove (set (keys default-smart-discovery)) (keys discovery)))]
    (throw (ex-info (str ":smart-discovery has no key " (pr-str (first unknown))
                         "; accepted: " (pr-str (sort (keys default-smart-discovery))))
                    {:unknown (vec unknown)})))
  (let [d (merge default-smart-discovery discovery)]
    (doseq [k url-keys
            :let [v (get d k)]
            :when (and (some? v) (not (and (= k :revocation-endpoint) (false? v))))]
      (check-url k v))
    (doseq [k flag-keys
            :let [v (get d k)]]
      (when-not (boolean? v)
        (throw (ex-info (str ":smart-discovery " (pr-str k) " must be a boolean")
                        {:key k :value v}))))
    (let [extra (:extra-scopes d)]
      (when-not (and (sequential? extra)
                     (every? #(and (string? %) (not (str/blank? %)) (not (re-find #"\s" %)))
                             extra))
        (throw (ex-info ":smart-discovery :extra-scopes must be a sequence of scope strings"
                        {:value extra}))))
    (when (and (:launch-ehr? d) (not (:token-response-context? d)))
      (throw (ex-info (str ":smart-discovery :launch-ehr? true requires "
                           ":token-response-context? true")
                      {:launch-ehr? true :token-response-context? (:token-response-context? d)})))
    (update d :extra-scopes vec)))

(defn- env-flag
  "The boolean an environment flag names: unset or blank is false, `1`/`true`
   is true, `0`/`false` is false. Anything else throws, so a typo does not
   quietly switch a feature off."
  [env-name value]
  (cond
    (or (nil? value) (str/blank? value)) false
    (#{"1" "true"} (str/lower-case value)) true
    (#{"0" "false"} (str/lower-case value)) false
    :else (throw (ex-info (str env-name " must be 1/true or 0/false")
                          {:env env-name :value value}))))

(defn env-smart-discovery
  "The `:smart-discovery` map the environment describes, unvalidated:
   `SMART_TOKEN_ENDPOINT`, `SMART_INTROSPECTION_ENDPOINT`,
   `SMART_REVOCATION_ENDPOINT`, `SMART_MANAGEMENT_ENDPOINT` (URLs), and the
   flags `SMART_LAUNCH_EHR`, `SMART_TOKEN_RESPONSE_CONTEXT`,
   `SMART_CONTEXT_STYLE`, `SMART_AUTHORIZE_POST`. `getenv` is injectable for
   tests."
  ([] (env-smart-discovery #(System/getenv %)))
  ([getenv]
   (let [url  (fn [n] (not-empty (getenv n)))
         flag (fn [n] (env-flag n (getenv n)))]
     (cond-> {:launch-ehr?             (flag "SMART_LAUNCH_EHR")
              :token-response-context? (flag "SMART_TOKEN_RESPONSE_CONTEXT")
              :context-style?          (flag "SMART_CONTEXT_STYLE")
              :authorize-post?         (flag "SMART_AUTHORIZE_POST")}
       (url "SMART_TOKEN_ENDPOINT")
       (assoc :token-endpoint (url "SMART_TOKEN_ENDPOINT"))

       (url "SMART_INTROSPECTION_ENDPOINT")
       (assoc :introspection-endpoint (url "SMART_INTROSPECTION_ENDPOINT"))

       (url "SMART_REVOCATION_ENDPOINT")
       (assoc :revocation-endpoint (url "SMART_REVOCATION_ENDPOINT"))

       (url "SMART_MANAGEMENT_ENDPOINT")
       (assoc :management-endpoint (url "SMART_MANAGEMENT_ENDPOINT"))))))

(defn resolve-smart-discovery
  "The validated discovery configuration in force: the host's map when it
   passes one, else the environment's (see [[env-smart-discovery]])."
  [discovery]
  (check-smart-discovery (if (some? discovery) discovery (env-smart-discovery))))

;; ---------------------------------------------------------------------------
;; Discovery document
;; ---------------------------------------------------------------------------

(defn oauth-endpoint
  "`path` (starting with a slash) under the authorization server `base`, with
   exactly one slash between them. An issuer is published verbatim and may end
   in a slash (Hydra's does); concatenating would then yield `//oauth2/auth`."
  [base path]
  (str (str/replace base #"/+$" "") path))

(def base-scopes
  "The scopes every discovery document advertises, in order, ahead of the
   configured `:extra-scopes`. `online_access` and `launch/encounter` are left
   out on purpose: the authorization server issues refresh tokens only for
   `offline_access`, and there is no standalone encounter picker."
  ["openid" "fhirUser" "profile" "launch" "launch/patient" "offline_access"
   "patient/*.read" "patient/*.write" "user/*.read" "user/*.write" "system/*.read"
   "patient/*.rs" "patient/*.cruds" "user/*.rs" "user/*.cruds" "system/*.rs"])

(defn capabilities
  "The SMART capability strings `discovery` supports. `context-passthrough-banner`
   is never among them: dromon performs no passthrough launch."
  [{:keys [token-response-context? context-style? launch-ehr? authorize-post?]}]
  (cond-> ["launch-standalone" "client-public" "client-confidential-symmetric"
           "client-confidential-asymmetric" "sso-openid-connect"
           "permission-offline" "permission-patient" "permission-user"
           "permission-v1" "permission-v2"]
    token-response-context?
    (into ["context-standalone-patient" "context-banner"])

    (and token-response-context? context-style?)
    (conj "context-style")

    launch-ehr?
    (into ["launch-ehr" "context-ehr-patient" "context-ehr-encounter"])

    authorize-post?
    (conj "authorize-post")))

(defn endpoints
  "The authorization server URLs `discovery` publishes for `base`, keyed
   :authorize :token :introspect :revoke :manage; an omitted one is absent."
  [base {:keys [token-endpoint introspection-endpoint revocation-endpoint management-endpoint]}]
  (cond-> {:authorize (oauth-endpoint base "/oauth2/auth")
           :token     (or token-endpoint (oauth-endpoint base "/oauth2/token"))}
    introspection-endpoint
    (assoc :introspect introspection-endpoint)

    (not (false? revocation-endpoint))
    (assoc :revoke (or revocation-endpoint (oauth-endpoint base "/oauth2/revoke")))

    management-endpoint
    (assoc :manage management-endpoint)))

(defn smart-configuration-body
  "The `.well-known/smart-configuration` document for the authorization server
   `base` (published verbatim as `issuer`) under the validated `discovery`."
  [base discovery]
  (let [{:keys [authorize token introspect revoke manage]} (endpoints base discovery)]
    (cond-> {:issuer                 base
             :jwks_uri               (oauth-endpoint base "/.well-known/jwks.json")
             :authorization_endpoint authorize
             :token_endpoint         token
             :token_endpoint_auth_methods_supported ["client_secret_basic" "private_key_jwt"]
             ;; SMART Backend Services requires the asymmetric client-assertion
             ;; algorithms the token endpoint accepts for `private_key_jwt`.
             :token_endpoint_auth_signing_alg_values_supported ["RS384" "ES384"]
             :grant_types_supported  ["authorization_code" "client_credentials" "refresh_token"]
             :code_challenge_methods_supported ["S256"]
             :response_types_supported ["code"]
             :scopes_supported       (into base-scopes (:extra-scopes discovery))
             :capabilities           (capabilities discovery)}
      revoke
      (assoc :revocation_endpoint revoke)

      ;; RFC 8414: the methods a caller authenticates to introspection with.
      ;; Bearer access-token auth is also accepted, but it is not a registered
      ;; method name.
      introspect
      (assoc :introspection_endpoint introspect
             :introspection_endpoint_auth_methods_supported ["client_secret_basic"])

      manage
      (assoc :management_endpoint manage))))

(def oauth-uris-url
  "The SMART extension a CapabilityStatement's `rest.security` declares the
   authorization server's endpoints with."
  "http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris")

(defn oauth-uris-extension
  "The `oauth-uris` extension for the authorization server `base` under
   `discovery`, listing the same endpoints the discovery document does."
  [base discovery]
  (let [eps (endpoints base discovery)]
    {:url oauth-uris-url
     :extension (into []
                      (keep (fn [k] (when-let [v (get eps k)]
                                      {:url (name k) :valueUri v})))
                      [:authorize :token :introspect :revoke :manage])}))

(defn request-discovery
  "The validated discovery configuration a request is answered under: the
   injected `:fhir/smart-discovery`, else the environment's, for a handler
   mounted outside the router chain."
  [req]
  (or (:fhir/smart-discovery req) (resolve-smart-discovery nil)))

(defn request-oauth-base
  "The authorization server base a request is answered under: the injected
   `:fhir/oauth-base-url`, else `OAUTH_BASE_URL`, else `http://localhost:4444`."
  [req]
  (or (:fhir/oauth-base-url req)
      (System/getenv "OAUTH_BASE_URL")
      "http://localhost:4444"))

;; ---------------------------------------------------------------------------
;; :capability-metadata
;; ---------------------------------------------------------------------------

(def default-capability-metadata
  {:publisher                  "Dromon"
   :software-name              "Dromon FHIR Server"
   :software-version           nil
   :implementation-description "Dromon FHIR Server"})

(defn check-capability-metadata
  "Validates a host's `:capability-metadata` and returns it over
   [[default-capability-metadata]]. Throws on an unknown key or a value that is
   not a non-blank string (`:software-version` may be nil)."
  [metadata]
  (when-not (or (nil? metadata) (map? metadata))
    (throw (ex-info ":capability-metadata must be a map" {:value metadata})))
  (when-let [unknown (seq (remove (set (keys default-capability-metadata)) (keys metadata)))]
    (throw (ex-info (str ":capability-metadata has no key " (pr-str (first unknown))
                         "; accepted: " (pr-str (sort (keys default-capability-metadata))))
                    {:unknown (vec unknown)})))
  (doseq [[k v] metadata]
    (when-not (or (and (nil? v) (= k :software-version))
                  (and (string? v) (not (str/blank? v))))
      (throw (ex-info (str ":capability-metadata " (pr-str k) " must be a non-blank string")
                      {:key k :value v}))))
  (merge default-capability-metadata metadata))

(defn request-capability-metadata
  "The capability metadata a request is answered under: the injected
   `:fhir/capability-metadata`, else the defaults."
  [req]
  (or (:fhir/capability-metadata req) default-capability-metadata))
