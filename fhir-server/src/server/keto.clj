(ns server.keto
  (:require [hato.client :as hc]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [taoensso.telemere :as t]
            [fhir-store.trace :as ftrace]))

;; Default Keto read API URL. In production this should come from configuration.
(def ^:private default-keto-url "http://localhost:4466")

(defn- check-permission
  "Checks Ory Keto for a specific permission. Returns true if allowed, false otherwise.
   Uses :throw-exceptions false to handle 403 (denied) responses without exceptions."
  [keto-url namespace object relation subject-id]
  (try
    (let [response (hc/get (str keto-url "/relation-tuples/check")
                           {:query-params {"namespace" namespace
                                           "object" object
                                           "relation" relation
                                           "subject_id" subject-id}
                            :as :json
                            :coerce :always
                            :throw-exceptions false})
          status (:status response)
          allowed? (get-in response [:body :allowed] false)]
      (log/debug "Keto check:" object relation subject-id "-> status:" status "allowed?" allowed?)
      (boolean allowed?))
    (catch Exception e
      (log/error e "Keto authorization check failed for" object relation subject-id)
      false)))

(def default-legacy-realm-blind-fallback?
  "Whether a realm-scoped check also accepts the pre-realm-scoping object.

   True for the duration of the realm-scoping migration: tuples written before
   it carry no realm, and a Keto check for \"<realm>/Patient\" is not answered
   by a \"Patient\" tuple (verified against Keto v0.12.0), so without this
   every existing grant would stop authorizing the moment the reader changed.

   Turning it off is what proves the backfill complete, which is why it is
   configuration (`KETO_LEGACY_REALM_BLIND_FALLBACK=0`) and not a code
   constant: the step that drops the legacy shape has to be reversible without
   a deploy. See docs/keto-realm-scoping.md."
  true)

(defn- scoped-object
  "`object` prefixed with `realm`, or unchanged when there is no realm.
   A realm can never contain a slash -- it arrives as one URL path segment --
   so the prefix stays unambiguous however many segments `object` has."
  [realm object]
  (if (str/blank? (str realm))
    object
    (str realm "/" object)))

(defn- base-objects
  "The realm-blind objects a request could be authorized by, least specific
   first. Type-level before instance-level because a type-level grant is the
   common one, so checking it first usually settles the request in one call."
  [fhir-type resource-id]
  (cond
    (and fhir-type resource-id) [fhir-type (str fhir-type "/" resource-id)]
    fhir-type                   [fhir-type]
    :else                       ["system"]))

(defn request-object
  "The object a request is ABOUT: the most specific one, realm-scoped.

   Distinct from [[request-objects]], which is the list of objects that may
   authorize the request. This is what the log line and the 403 diagnostics
   name, because reporting the object that merely happened to be checked first
   would tell an operator a denied instance request was refused on the type."
  [realm fhir-type resource-id]
  (scoped-object realm (peek (base-objects fhir-type resource-id))))

(defn request-objects
  "The Keto objects that may authorize one request, in the order to check them.

   Realm-scoped objects come first so that once the backfill has run the first
   check answers and the legacy calls are never made; while legacy tuples are
   still the common case this costs up to two extra checks per request, which
   is the accepted price of not locking everyone out on deploy.

   A request carrying no realm -- grant administration under /auth/grants, the
   Hydra token hook -- keeps the bare object. That surface is global rather
   than unscoped by oversight, and prefixing it with an empty realm segment
   would ask Keto about an object nothing ever wrote."
  [realm fhir-type resource-id legacy-realm-blind?]
  (let [base (base-objects fhir-type resource-id)]
    (if (str/blank? (str realm))
      base
      (cond-> (mapv #(scoped-object realm %) base)
        legacy-realm-blind? (into base)))))

(defn- authorized?
  "Whether `subject-id` holds `relation` on any of `objects`.

   `some` short-circuits, so a subject authorized by the realm-scoped object
   costs exactly one Keto call and the legacy fallback is never reached."
  [keto-url namespace objects relation subject-id]
  (boolean (some #(check-permission keto-url namespace % relation subject-id)
                 objects)))

(defn system-read-allowed?
  "Whether `subject-id` holds a system read tuple in the 'fhir' namespace for
   `realm`.

   Public entry point mirroring the middleware's check against the system
   object, for :public? routes (bulk-data $export / $export-file) where
   wrap-keto-authorization is bypassed and the handler must perform the same
   authorization check itself.

   `realm` is required for these routes rather than optional: they are mounted
   under /:tenant-id and a full-tenant export is the widest read the server
   offers, so a realm-blind gate here would let a subject exported from one
   realm drain every other. A nil realm still answers -- against the bare
   object -- because the fallback below has to keep pre-migration grants
   working, but it is not a shape any bulk route produces.

   `keto-url` falls back to the default when nil."
  ([keto-url realm subject-id]
   (system-read-allowed? keto-url realm subject-id default-legacy-realm-blind-fallback?))
  ([keto-url realm subject-id legacy-realm-blind?]
   (boolean
    (and subject-id
         (authorized? (or keto-url default-keto-url) "fhir"
                      (request-objects realm nil nil legacy-realm-blind?)
                      "read" subject-id)))))

(defn unauthenticated-response
  "The answer to a request that carries no subject at all.

   401, not 403: nothing was denied, there was nobody to deny. The distinction
   is what lets a browser client tell 'log in' from 'you may not do this', and
   401 is the only one it can act on.

   `login-url`, when the deployment configured one, tells the client where
   authentication starts, so turning a login flow on or off stays a server
   config change. It is the same field mast's 401 carries
   (com.breezeehr.cookie-authentication.authentication/unauthorized-response);
   the body stays an OperationOutcome so FHIR clients still get the shape every
   other dromon error uses."
  [login-url]
  {:status 401
   :body (cond-> {:resourceType "OperationOutcome"
                  :issue [{:severity "error"
                           :code "login"
                           :diagnostics "Missing subject in identity; cannot authorize."}]}
           (not (str/blank? login-url)) (assoc :login-url login-url))})

(defn wrap-keto-authorization
  "Middleware that checks Ory Keto to see if the identity is authorized to perform the action.
   Requires `identity` to be populated by buddy-auth (or injected upstream by a
   BFF that authenticated a session cookie -- see `server.auth/wrap-jwt-auth`).
   Bypasses authorization if the route specifies `:public? true` in its match-data.

   `:login-url` is advertised on the 401 a subject-less request receives; see
   `unauthenticated-response`.

   `:legacy-realm-blind-fallback?` keeps the pre-realm-scoping objects as a
   fallback; see `default-legacy-realm-blind-fallback?`."
  [handler {:keys [keto-url login-url legacy-realm-blind-fallback?]
            :or {keto-url default-keto-url
                 legacy-realm-blind-fallback? default-legacy-realm-blind-fallback?}}]
  (fn [request]
    (let [route-data (get-in request [:reitit.core/match :data])
          public? (:public? route-data)]
      (if public?
        (handler request)
        (let [identity (:identity request)
              subject-id (:sub identity)
              uri (or (:uri request) "")
              parts (str/split uri #"/")
              ;; URI: /default/fhir/Patient/123 -> ["" "default" "fhir" "Patient" "123"]
              ;; Prefer explicit fhir type if provided by Reitit match, else fallback to URL parsing
              fhir-type (or (:fhir/resource-type request)
                            (when (and (> (count parts) 3)
                                       ;; System endpoints whose 4th segment is
                                       ;; not a resource type gate on the
                                       ;; "system" object, not a bogus one
                                       ;; parsed from the URL. The bulk-data
                                       ;; $export endpoints join metadata/
                                       ;; _history/_search here.
                                       (not (#{"metadata" "_history" "_search"
                                               "$export" "$export-status" "$export-file"}
                                             (nth parts 3))))
                              (nth parts 3)))
              request-method (:request-method request)
              ;; Routes may pin the required relation via :keto/relation route
              ;; data (e.g. operations that write no clinical data and should
              ;; be available to read-only grants). Otherwise it is derived
              ;; from the HTTP method.
              relation (or (:keto/relation route-data)
                           (case request-method
                             :get "read"
                             :post "write"
                             :put "write"
                             :delete "delete"
                             :patch "write"
                             "read"))
              resource-id (get-in request [:path-params :id])
              ;; The realm the request addresses, read off the route's
              ;; :tenant-id path parameter -- the same accessor
              ;; flotilla/.../fhir.clj `request->realm` uses. Read from the
              ;; match rather than by splitting :uri because a BFF that mounts
              ;; dromon under a prefix strips that prefix before the chain
              ;; runs, leaving the realm at no fixed offset in the URI.
              realm (get-in request [:path-params :tenant-id])
              objects (request-objects realm fhir-type resource-id
                                       legacy-realm-blind-fallback?)
              object (request-object realm fhir-type resource-id)]

          (log/info "Keto authz -> subject:" subject-id "relation:" relation "object:" object "uri:" uri)
          (if (not subject-id)
            (unauthenticated-response login-url)
            (let [allowed? (ftrace/trace!
                            {:id :authz/keto.check
                             :data {:subject-id subject-id
                                    :namespace "fhir"
                                    :relation relation
                                    :object object
                                    :objects objects
                                    :realm realm
                                    :fhir-type fhir-type}}
                            (authorized? keto-url "fhir" objects relation subject-id))]
              (if allowed?
                (handler request)
                {:status 403
                 :body {:resourceType "OperationOutcome"
                        :issue [{:severity "error"
                                 :code "forbidden"
                                 :diagnostics (format "Subject %s is not allowed to %s %s" subject-id relation object)}]}}))))))))
