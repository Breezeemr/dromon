(ns server.keto
  (:require [hato.client :as hc]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

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

(defn wrap-keto-authorization
  "Middleware that checks Ory Keto to see if the identity is authorized to perform the action.
   Requires `identity` to be populated by buddy-auth.
   Bypasses authorization if the route specifies `:public? true` in its match-data."
  [handler {:keys [keto-url] :or {keto-url default-keto-url}}]
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
                                       (not (#{"metadata" "_history" "_search"} (nth parts 3))))
                              (nth parts 3)))
              request-method (:request-method request)
              relation (case request-method
                         :get "read"
                         :post "write"
                         :put "write"
                         :delete "delete"
                         :patch "write"
                         "read")
              resource-id (get-in request [:path-params :id])
              object (cond
                       (and fhir-type resource-id) (str fhir-type "/" resource-id)
                       fhir-type fhir-type
                       :else "system")]

          (log/info "Keto authz -> subject:" subject-id "relation:" relation "object:" object "uri:" uri)
          (if (not subject-id)
            {:status 403
             :body {:resourceType "OperationOutcome"
                    :issue [{:severity "error"
                             :code "forbidden"
                             :diagnostics "Missing subject in identity; cannot authorize."}]}}

            (if (authorized? keto-url "fhir" fhir-type resource-id relation subject-id)
              (handler request)
              {:status 403
               :body {:resourceType "OperationOutcome"
                      :issue [{:severity "error"
                               :code "forbidden"
                               :diagnostics (format "Subject %s is not allowed to %s %s" subject-id relation object)}]}})))))))
