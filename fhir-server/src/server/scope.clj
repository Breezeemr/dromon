(ns server.scope
  "SMART on FHIR scope parsing and enforcement.

   Parses the OAuth2 `scope` claim carried in a validated JWT identity and
   gates FHIR resource access on it. Supports both SMART v1 scopes
   (e.g. `patient/Observation.read`, `user/*.write`) and SMART v2 granular
   scopes (e.g. `patient/Observation.rs`, `system/*.cruds`).

   Permission letters follow the SMART v2 convention: c=create, r=read,
   u=update, d=delete, s=search. v1 access levels expand to letter sets:
   `read` -> #{r s}, `write` -> #{c u d}, `*` -> all.

   This layer enforces the resource-type + interaction a scope grants. It does
   NOT implement patient-compartment row-level restriction (limiting a
   `patient/` scope to the in-context launch patient); that requires launch
   context and store support and is intentionally out of scope here. The
   compartment prefix (patient/user/system) is treated as granting access to
   the named resource type."
  (:require [clojure.string :as str]
            [taoensso.telemere :as t]))

(def ^:private all-permissions #{\c \r \u \d \s})

(def ^:private access->permissions
  {"read"  #{\r \s}
   "write" #{\c \u \d}
   "*"     all-permissions})

(def ^:private interaction->permission
  {:read   \r
   :vread  \r
   :search \s
   :create \c
   :update \u
   :delete \d})

(def ^:private resource-scope-re
  #"^(patient|user|system)/([A-Za-z]+|\*)\.(read|write|\*|[cruds]+)$")

(defn- expand-permissions
  "Expand a scope access part into a set of SMART v2 permission letters.
   `access` is one of the v1 levels (read/write/*) or a v2 letter string."
  [access]
  (or (access->permissions access)
      ;; v2 granular letters, e.g. \"rs\", \"cruds\", \"cud\"
      (let [letters (set access)]
        (when (every? all-permissions letters)
          letters))))

(defn parse-scope
  "Parse a single scope string into a resource-scope map, or nil for
   non-resource scopes (openid, profile, fhirUser, launch, launch/patient,
   offline_access, ...) and malformed scopes.

   A resource scope returns:
   {:compartment \"patient\" :resource \"Observation\" :permissions #{\\r \\s}}"
  [scope]
  (when-let [[_ compartment resource access] (re-matches resource-scope-re scope)]
    (when-let [permissions (expand-permissions access)]
      {:compartment compartment
       :resource    resource
       :permissions permissions})))

(defn- scope-claim->seq
  "Normalize the JWT `scope` claim into a sequence of scope strings.
   The claim is conventionally a space-delimited string but may already be a
   collection."
  [claim]
  (cond
    (string? claim) (remove str/blank? (str/split claim #"\s+"))
    (coll? claim)   (->> claim (map str) (remove str/blank?))
    :else           nil))

(defn parse-scopes
  "Parse the JWT `scope` claim (a space-delimited string or a collection) into
   a vector of resource-scope maps. Non-resource and malformed scopes are
   dropped."
  [claim]
  (into [] (keep parse-scope) (scope-claim->seq claim)))

(defn request-scopes
  "Parse the SMART scopes carried by a request's validated JWT `:identity`.
   Reads the OAuth2 `scope` claim (a space-delimited string) or the RFC 9068
   `scp` claim (a JSON array), whichever is present — Ory Hydra issues `scp`."
  [request]
  (let [identity (:identity request)]
    (parse-scopes (or (:scope identity) (:scp identity)))))

(defn permitted?
  "True when `parsed-scopes` grant `interaction` on `fhir-type`.
   A wildcard resource scope (`*`) matches any type."
  [parsed-scopes fhir-type interaction]
  (when-let [needed (interaction->permission interaction)]
    (boolean
      (some (fn [{:keys [resource permissions]}]
              (and (or (= resource "*") (= resource fhir-type))
                   (contains? permissions needed)))
            parsed-scopes))))

(defn request->fhir-type
  "Derive the FHIR resource type for a request. Prefers the explicit
   :fhir/resource-type set by routing, falling back to URL parsing.
   Mirrors server.keto."
  [request]
  (or (:fhir/resource-type request)
      (let [parts (str/split (or (:uri request) "") #"/")]
        (when (and (> (count parts) 3)
                   ;; Bulk-data $export endpoints are system-level and carry no
                   ;; resource-type URL segment; join metadata/_history/_search
                   ;; so scope enforcement does not treat "$export" as a type.
                   (not (#{"metadata" "_history" "_search"
                           "$export" "$export-status" "$export-file"}
                         (nth parts 3))))
          (nth parts 3)))))

(defn request->interaction
  "Derive the SMART interaction for a request from its method and shape.
   GET on an instance is a read, GET on a type (or any _search path) is a
   search, POST creates (POST to _search searches), PUT/PATCH update, DELETE
   deletes."
  [request]
  (let [method  (:request-method request)
        uri     (or (:uri request) "")
        id      (get-in request [:path-params :id])
        vid     (get-in request [:path-params :vid])
        search? (str/includes? uri "_search")]
    (case method
      :get    (cond search? :search
                    vid     :vread
                    id      :read
                    :else   :search)
      :post   (if search? :search :create)
      :put    :update
      :patch  :update
      :delete :delete
      :read)))

(defn- forbidden [diagnostics]
  {:status 403
   :body   {:resourceType "OperationOutcome"
            :issue [{:severity "error"
                     :code "forbidden"
                     :diagnostics diagnostics}]}})

(defn wrap-smart-scope
  "Middleware that enforces SMART on FHIR scopes from the JWT identity.

   Requires `:identity` to be populated by server.auth/wrap-jwt-auth and so
   must run after it. Bypasses enforcement for routes marked `:public? true`.
   System endpoints that do not resolve to a FHIR resource type are allowed
   (resource-type access is what scopes gate)."
  [handler _opts]
  (fn [request]
    (let [route-data (get-in request [:reitit.core/match :data])
          public?    (:public? route-data)]
      (if public?
        (handler request)
        (let [scopes      (request-scopes request)
              fhir-type   (request->fhir-type request)
              interaction (request->interaction request)]
          (cond
            (nil? fhir-type)
            (handler request)

            (empty? scopes)
            (forbidden "No SMART scopes present in token; insufficient scope.")

            :else
            (let [allowed? (t/trace!
                             {:id :authz/smart-scope.check
                              :data {:fhir-type fhir-type
                                     :interaction interaction
                                     :scopes (mapv #(str (:compartment %) "/" (:resource %)
                                                         "." (apply str (sort (:permissions %))))
                                                   scopes)}}
                             (permitted? scopes fhir-type interaction))]
              (if allowed?
                (handler request)
                (forbidden (format "Insufficient scope: no granted scope permits %s on %s."
                                   (name interaction) fhir-type))))))))))
