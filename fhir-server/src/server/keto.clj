(ns server.keto
  (:require [hato.client :as hc]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [taoensso.telemere :as t]))

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

(defn- authorized?
  "Check if subject-id is authorized to perform relation on the given resource.
   For instance-level access (e.g. Patient/123), checks type-level permission
   first (e.g. Patient), then falls back to instance-level. This allows
   type-level grants to cover all instances of that resource type."
  [keto-url namespace fhir-type resource-id relation subject-id]
  (let [has-instance? (and fhir-type resource-id)]
    (if has-instance?
      ;; Instance-level request: check type-level first (more common grant),
      ;; then instance-level as fallback
      (or (check-permission keto-url namespace fhir-type relation subject-id)
          (check-permission keto-url namespace (str fhir-type "/" resource-id) relation subject-id))
      ;; Type-level or system request: check directly
      (let [object (cond
                     fhir-type fhir-type
                     :else "system")]
        (check-permission keto-url namespace object relation subject-id)))))

(defn system-read-allowed?
  "Whether `subject-id` holds a 'system' read tuple in the 'fhir' namespace.
   Public entry point mirroring the middleware's check against the 'system'
   object, for :public? routes (bulk-data $export / $export-file) where
   wrap-keto-authorization is bypassed and the handler must perform the same
   authorization check itself. `keto-url` falls back to the default when nil."
  [keto-url subject-id]
  (boolean
   (and subject-id
        (check-permission (or keto-url default-keto-url) "fhir" "system" "read" subject-id))))

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
   `unauthenticated-response`."
  [handler {:keys [keto-url login-url] :or {keto-url default-keto-url}}]
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
              object (cond
                       (and fhir-type resource-id) (str fhir-type "/" resource-id)
                       fhir-type fhir-type
                       :else "system")]

          (log/info "Keto authz -> subject:" subject-id "relation:" relation "object:" object "uri:" uri)
          (if (not subject-id)
            (unauthenticated-response login-url)
            (let [allowed? (t/trace!
                            {:id :authz/keto.check
                             :data {:subject-id subject-id
                                    :namespace "fhir"
                                    :relation relation
                                    :object object
                                    :fhir-type fhir-type}}
                            (authorized? keto-url "fhir" fhir-type resource-id relation subject-id))]
              (if allowed?
                (handler request)
                {:status 403
                 :body {:resourceType "OperationOutcome"
                        :issue [{:severity "error"
                                 :code "forbidden"
                                 :diagnostics (format "Subject %s is not allowed to %s %s" subject-id relation object)}]}}))))))))
