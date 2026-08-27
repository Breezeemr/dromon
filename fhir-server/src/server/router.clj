(ns server.router
  "Composable assembly of the dromon FHIR ring handler.

   Exposes the middleware stack as data (a vector of named reitit
   middleware maps), the router options, and the default handler, so a
   host can re-compose the router -- insert, replace, or extend
   middleware -- without forking [[server.core/fhir-app]].

   The pieces:
   - [[resolve-options]]     -- kwargs + environment -> resolved options map
   - [[default-middleware]]  -- resolved options -> named middleware vector
   - [[router-options]]      -- the reitit router option map
   - [[router]]              -- reitit router over `server.routing` routes
   - [[default-handler]]     -- the out-of-chain fallback handler
   - [[handler]]             -- `router` + `default-handler` in one call
   - [[insert-before]] / [[insert-after]] / [[replace-middleware]]

   `server.core/fhir-app` is a thin composition of exactly these.

   A host (for example a BFF that authenticates via its own session)
   recomposes without forking. Note the alias-qualified `::router/...`
   keywords: entries are named in the `:server.router` namespace, so from
   host code they must be written `::router/jwt-auth`, not `::jwt-auth`
   (which would auto-resolve into the host's own namespace and fail the
   name lookup).

       (require '[server.router :as router] '[reitit.ring :as ring])

       (let [opts (router/resolve-options {:keto-url keto-url})
             mw   (-> (router/default-middleware store opts)
                      ;; run before ::router/jwt-auth so a session identity
                      ;; is in place before scope/keto authorization
                      (router/insert-before ::router/jwt-auth
                        {:name ::session-identity
                         :wrap (fn [h] (fn [req] (h (attach-session-identity req))))})
                      ;; swap dromon's CORS policy for the host's
                      (router/replace-middleware ::router/cors
                        {:name ::router/cors
                         :wrap (fn [h] (my-cors h))}))]
         (ring/ring-handler (router/router schemas mw) router/default-handler))

   See [[default-middleware]] for the ordering invariants that constrain
   where new entries may go."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [muuntaja.core :as m-core]
            [muuntaja.format.json :as muuntaja-json]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring.middleware.head :refer [wrap-head]]
            [server.auth :as auth]
            [server.compartment :as compartment]
            [server.fhir-coercion :as fhir-coercion]
            [server.keto :as keto]
            [server.middleware :as middleware]
            [server.routing :as routing]
            [server.scope :as scope])
  (:import [com.fasterxml.jackson.datatype.jsr310 JavaTimeModule]
           [com.fasterxml.jackson.databind SerializationFeature]))

;; ---------------------------------------------------------------------------
;; Encoding
;; ---------------------------------------------------------------------------

(def java-time-encode-mapper
  "Jackson ObjectMapper that serializes java.time objects to ISO strings."
  (doto (json/object-mapper {:modules [(JavaTimeModule.)]})
    (.disable SerializationFeature/WRITE_DATES_AS_TIMESTAMPS)))

(def java-time-decode-mapper
  "Jackson ObjectMapper that deserializes with keyword keys."
  (json/object-mapper {:decode-key-fn keyword}))

(def muuntaja-instance
  "Muuntaja instance used by the FHIR router: `application/json` also matches
   `application/fhir+json`, java.time values encode as ISO strings, decimals
   decode as BigDecimal, and `application/json-patch+json` is a known format."
  (m-core/create
    (-> m-core/default-options
        (assoc-in [:formats "application/json" :matches] #"^application/(fhir\+)?json$")
        (assoc-in [:formats "application/json" :encoder-opts]
                  {:mapper java-time-encode-mapper})
        (assoc-in [:formats "application/json" :decoder-opts]
                  {:mapper java-time-decode-mapper
                   :bigdecimals true})
        (assoc-in [:formats "application/json-patch+json"]
                  {:decoder [muuntaja-json/decoder
                             {:mapper java-time-decode-mapper
                              :bigdecimals true}]
                   :encoder [muuntaja-json/encoder
                             {:mapper java-time-encode-mapper}]}))))

;; ---------------------------------------------------------------------------
;; Injection middleware
;; ---------------------------------------------------------------------------

(defn wrap-fhir-store [handler store]
  (fn [req]
    (handler (assoc req :fhir/store store))))

(defn wrap-terminology [handler terminology]
  (fn [req]
    (handler (assoc req :fhir/terminology terminology))))

(defn wrap-bulk-job-store
  "Inject the Bulk Data Access ($export) job registry into each request,
   mirroring wrap-fhir-store. The bulk-export handlers read it from
   :fhir/bulk-job-store."
  [handler bulk-job-store]
  (fn [req]
    (handler (assoc req :fhir/bulk-job-store bulk-job-store))))

(defn wrap-keto-url
  "Inject the configured Keto read API URL so the :public? bulk-data handlers,
   where wrap-keto-authorization is bypassed, can perform the same 'system'
   authorization check the middleware would (server.keto/system-read-allowed?)."
  [handler keto-url]
  (fn [req]
    (handler (assoc req :fhir/keto-url keto-url))))

;; ---------------------------------------------------------------------------
;; Options
;; ---------------------------------------------------------------------------

(defn parse-cors-origins
  "Parses CORS allowed origins from a comma-separated string or collection into a set.
   Returns nil if input is nil or blank."
  [origins]
  (cond
    (set? origins) origins
    (coll? origins) (set origins)
    (string? origins) (if (str/blank? origins)
                        nil
                        (set (map str/trim (str/split origins #","))))
    :else nil))

(defn resolve-options
  "Resolve [[server.core/fhir-app]]'s options map into the fully resolved map
   consumed by [[default-middleware]].

   Accepts `:jwks-url`, `:keto-url`, `:terminology`, `:cors-allowed-origins`,
   `:enforce-smart-scopes?`, `:bulk-job-store` and `:login-url`; unknown keys
   are ignored.

   This is the ONLY place the environment is consulted, so tests and hosts can
   bypass it entirely by hand-building the resolved map (for example passing a
   `:trace-tap` function directly). The fallbacks, unchanged from `fhir-app`:

   - :jwks-url              -- argument, else `JWKS_URL`, else
                               `http://localhost:4444/.well-known/jwks.json`
                               unless `JWT_DEV_SECRET` is set (dev HS256 mode).
   - :keto-url              -- argument, else `KETO_URL`, else
                               `http://localhost:4466`.
   - :enforce-smart-scopes? -- argument when non-nil, else `ENFORCE_SMART_SCOPES=1`.
   - :cors-origins          -- [[parse-cors-origins]] of the argument, else of
                               `CORS_ALLOWED_ORIGINS`.
   - :trace-tap             -- `server.dev.trace-tap/wrap-trace-tap`, resolved
                               only when `DROMON_DEV_TRACE_TAP=1`, so the OTel
                               SDK is not required on the default classpath."
  [{:keys [jwks-url keto-url terminology cors-allowed-origins
           enforce-smart-scopes? bulk-job-store login-url]}]
  {:jwks-url              (or jwks-url
                              (System/getenv "JWKS_URL")
                              (when-not (System/getenv "JWT_DEV_SECRET")
                                "http://localhost:4444/.well-known/jwks.json"))
   :keto-url              (or keto-url (System/getenv "KETO_URL") "http://localhost:4466")
   :login-url             login-url
   :enforce-smart-scopes? (if (some? enforce-smart-scopes?)
                            enforce-smart-scopes?
                            (= "1" (System/getenv "ENFORCE_SMART_SCOPES")))
   :cors-origins          (parse-cors-origins
                            (or cors-allowed-origins
                                (System/getenv "CORS_ALLOWED_ORIGINS")))
   :trace-tap             (when (= "1" (System/getenv "DROMON_DEV_TRACE_TAP"))
                            (some-> (requiring-resolve 'server.dev.trace-tap/wrap-trace-tap)
                                    deref))
   :terminology           terminology
   :bulk-job-store        bulk-job-store})

;; ---------------------------------------------------------------------------
;; Middleware stack
;; ---------------------------------------------------------------------------

(defn default-middleware
  "Dromon's default FHIR middleware stack, as data.

   `store` is the `IFHIRStore` instance; `opts` is a resolved options map
   (see [[resolve-options]]). Returns a vector of reitit middleware maps, each
   carrying a `:name` in the `:server.router` keyword namespace, so hosts can
   splice relative to names rather than indices (see [[insert-before]],
   [[insert-after]], [[replace-middleware]]).

   Two entry groups are conditional: `::trace-tap` appears only when `opts`
   carries a `:trace-tap` function, and `::smart-scope` / `::patient-compartment`
   only when `:enforce-smart-scopes?` is truthy.

   ORDERING INVARIANTS. Entries listed EARLIER are OUTER: they see the request
   first and the response last. Recomposition must respect the following, which
   is why hosts could not safely fork this vector:

   1. `::cors` must sit outside `::jwt-auth` / `::keto-authorization` (a
      preflight OPTIONS carries no token and must short-circuit before auth) and
      outside `::format-response` (its 204/403 preflight responses have nil
      bodies and bypass encoding).
   2. `::format-override`, `::not-acceptable` and `::unsupported-media-type`
      must sit outside `::format-negotiate` / `::format-response`:
      `::format-override` rewrites the Accept header negotiation reads, and all
      three short-circuit with PRE-SERIALIZED JSON OperationOutcome bodies
      precisely because muuntaja never sees their responses.
   3. `::parameters` must precede `::format-override`, `::summary`,
      `::elements` and `::pretty-print`, which read `:query-params`.
      (`::prefer` reads only the Prefer request header, so only invariant 10
      constrains it.)
   4. `::format-negotiate` must be outside `::format-request` and
      `::format-response`: it populates the `:muuntaja/request` and
      `:muuntaja/response` keys both consume.
   5. `::fhir-exceptions` must sit INSIDE `::format-response` and OUTSIDE
      `::format-request`, coercion, injection and auth. It converts exceptions
      from request decoding, coercion and handlers into OperationOutcome MAP
      responses, which `::format-response` (outer of it) then encodes. Moving it
      outside `::format-response` would hand Jetty raw map bodies, i.e. 500s.
   6. Coercion (`::coerce-request`, `::coerce-response`, `::coerce-exceptions`)
      must sit inside `::format-request`; it needs the decoded `:body-params`.
      Coercion errors are in practice caught by `::fhir-exceptions` (422/500
      OperationOutcomes); `::coerce-exceptions` is retained for compatibility.
   7. The injection middleware (`::fhir-store`, `::terminology`,
      `::bulk-job-store`, `::keto-url`) must sit outside `::patient-compartment`,
      which reads and REPLACES `:fhir/store` with a compartment-filtering store,
      and outside the handlers that consume the injected values.
   8. `::jwt-auth` must sit outside `::smart-scope`, `::patient-compartment` and
      `::keto-authorization`, all of which read the `:identity` it attaches.
      `::smart-scope` must sit outside `::patient-compartment` (compartment
      assumes scope already authorized the type + interaction).
      `::keto-authorization` is innermost; it and `::smart-scope` /
      `::patient-compartment` honor the `:public?` route data via
      `:reitit.core/match`.
   9. `::jwt-auth` never rejects on its own -- it only attaches `:identity`.
      Rejection is `::keto-authorization`'s 403 (or `server.auth/wrap-require-auth`'s
      401 on the bulk-data routes). A host inserting its own identity source
      (a BFF session, say) must insert it before `::keto-authorization`;
      conventionally before `::jwt-auth`.
   10. The response-shaping group -- `::pretty-print`, `::prefer`, `::elements`,
       `::summary`, `::fhir-response-headers` -- must sit INSIDE
       `::format-response`, because every one of them rewrites a response body
       that is still a resource MAP. `::format-response` encodes eagerly to an
       InputStream, so anything outside it sees bytes: the transformations
       silently no-op, and `::prefer`'s `return=OperationOutcome`, which swaps
       in a fresh map unconditionally, hands Jetty a body it cannot serialize.

       Within the group, remember that OUTER runs LAST on the response, so the
       vector reads in reverse of the shaping order. Innermost first:
       `::fhir-response-headers` derives ETag/Last-Modified from `meta` before
       anything can drop it (notably `::prefer`'s `return=minimal`, which nils
       the body but must still carry the ETag); `::summary` and `::elements`
       subset the resource; `::prefer` then applies the return preference; and
       `::pretty-print` is outermost so it re-serializes the FINAL body. Because
       it emits an InputStream and its own Content-Type, `::format-response`
       (outside it) skips encoding and passes those bytes through untouched.

       The group also sits inside `::fhir-exceptions`, which cuts both ways: an
       OperationOutcome synthesized from a thrown exception is never shaped, and
       a shaping failure of its own (`_pretty` on a body Jackson cannot write,
       say) still becomes an OperationOutcome rather than a raw 500. Errors
       returned as plain response maps -- handler 404/410/412s, `server.keto`'s
       403 -- do reach the group, which is why `server.middleware/wrap-summary`
       and `wrap-elements` skip non-2xx and OperationOutcome bodies themselves
       rather than relying on position."
  [store {:keys [trace-tap cors-origins terminology bulk-job-store keto-url
                 jwks-url enforce-smart-scopes? login-url]}]
  (cond-> []
    trace-tap
    (conj {:name ::trace-tap :wrap trace-tap})

    :always
    (into [{:name ::telemere-trace :wrap middleware/wrap-telemere-trace}
           {:name ::otel-context :wrap middleware/wrap-otel-context}
           {:name ::head :wrap wrap-head}
           {:name ::request-id :wrap middleware/wrap-request-id}
           {:name ::cors :wrap (fn [handler] (middleware/wrap-cors handler cors-origins))}
           (assoc parameters/parameters-middleware :name ::parameters)
           {:name ::format-override :wrap middleware/wrap-format-override}
           {:name ::not-acceptable :wrap middleware/wrap-not-acceptable}
           {:name ::unsupported-media-type :wrap middleware/wrap-unsupported-media-type}
           (assoc muuntaja/format-negotiate-middleware :name ::format-negotiate)
           (assoc muuntaja/format-response-middleware :name ::format-response)
           {:name ::fhir-exceptions :wrap middleware/wrap-fhir-exceptions}
           {:name ::pretty-print
            :wrap (fn [handler] (middleware/wrap-pretty-print handler java-time-encode-mapper))}
           {:name ::prefer :wrap middleware/wrap-prefer}
           {:name ::elements :wrap middleware/wrap-elements}
           {:name ::summary :wrap middleware/wrap-summary}
           {:name ::fhir-response-headers :wrap middleware/wrap-fhir-response-headers}
           (assoc muuntaja/format-request-middleware :name ::format-request)
           (assoc rrc/coerce-request-middleware :name ::coerce-request)
           (assoc rrc/coerce-response-middleware :name ::coerce-response)
           (assoc rrc/coerce-exceptions-middleware :name ::coerce-exceptions)
           {:name ::fhir-store :wrap (fn [handler] (wrap-fhir-store handler store))}
           {:name ::terminology :wrap (fn [handler] (wrap-terminology handler terminology))}
           {:name ::bulk-job-store
            :wrap (fn [handler] (wrap-bulk-job-store handler bulk-job-store))}
           {:name ::keto-url :wrap (fn [handler] (wrap-keto-url handler keto-url))}
           {:name ::jwt-auth
            :wrap (fn [handler] (auth/wrap-jwt-auth handler {:jwks-url jwks-url}))}])

    enforce-smart-scopes?
    (conj {:name ::smart-scope :wrap (fn [handler] (scope/wrap-smart-scope handler {}))}
          {:name ::patient-compartment
           :wrap (fn [handler] (compartment/wrap-patient-compartment handler {}))})

    :always
    (conj {:name ::keto-authorization
           :wrap (fn [handler] (keto/wrap-keto-authorization handler {:keto-url keto-url :login-url login-url}))})))

;; ---------------------------------------------------------------------------
;; Recomposition helpers
;; ---------------------------------------------------------------------------

(defn- index-of
  "Index of the entry in `middleware` whose `:name` is `nme`. Throws when
   absent, listing the known names, since a silent no-op would leave a host's
   middleware unwired."
  [middleware nme]
  (or (some (fn [[i m]] (when (= nme (:name m)) i))
            (map-indexed vector middleware))
      (throw (ex-info "No middleware with that :name"
                      {:name nme :known (mapv :name middleware)}))))

(defn insert-before
  "Return `middleware` with `entries` spliced in immediately before the entry
   named `nme` (i.e. the new entries become OUTER of it). Each entry must be a
   reitit middleware map carrying its own `:name`. Throws when `nme` is absent."
  [middleware nme & entries]
  (let [i (index-of middleware nme)
        v (vec middleware)]
    (into (into (subvec v 0 i) entries) (subvec v i))))

(defn insert-after
  "Return `middleware` with `entries` spliced in immediately after the entry
   named `nme` (i.e. the new entries become INNER of it). Each entry must be a
   reitit middleware map carrying its own `:name`. Throws when `nme` is absent."
  [middleware nme & entries]
  (let [i (inc (index-of middleware nme))
        v (vec middleware)]
    (into (into (subvec v 0 i) entries) (subvec v i))))

(defn replace-middleware
  "Return `middleware` with the entry named `nme` replaced by `entry`, in place.
   `entry` must be a reitit middleware map carrying its own `:name` (it need not
   be `nme`). Throws when `nme` is absent."
  [middleware nme entry]
  (assoc (vec middleware) (index-of middleware nme) entry))

;; ---------------------------------------------------------------------------
;; Router
;; ---------------------------------------------------------------------------

(defn router-options
  "Reitit router options for the FHIR routes built from `schemas`, with
   `middleware` (see [[default-middleware]]) as the route-data stack.

   `:conflicts` is nil on purpose: route conflicts resolve by insertion order,
   which `server.routing` depends on -- it emits operation routes ahead of the
   `/:id` wildcard so `$export` and friends are not swallowed by it.

   `:fhir/all-registries` is read by the compartment search and system search
   handlers, which need every resource type's search registry, not just the one
   the matched route carries.

   `:muuntaja` is the instance the `::format-negotiate` / `::format-request` /
   `::format-response` middleware compile against."
  [schemas middleware]
  {:conflicts nil
   :data {:coercion fhir-coercion/coercion
          :muuntaja muuntaja-instance
          :fhir/all-registries (routing/collect-registries schemas)
          :middleware middleware}})

(defn router
  "Reitit router over the FHIR routes for `schemas` with the `middleware` stack.
   `schemas` is the vector produced by `server.core/resolve-schemas`."
  [schemas middleware]
  (ring/router (routing/build-fhir-routes schemas)
               (router-options schemas middleware)))

(def default-handler
  "Fallback handler for requests no route matched: a trailing-slash redirect,
   then a 404 OperationOutcome.

   INVARIANT: this handler runs OUTSIDE the router's middleware chain, so
   muuntaja never encodes its body. A raw map body makes the Jetty adapter throw
   (there is no StreamableResponseBody impl for PersistentArrayMap), turning
   every unmatched route -- e.g. a resource type with no schema -- into a 500.
   The OperationOutcome is therefore pre-encoded to a JSON string with jsonista.
   A host supplying its own default handler must do the same."
  (some-fn
    (ring/redirect-trailing-slash-handler {:method :strip})
    (ring/create-default-handler
      {:not-found
       (constantly
         {:status 404
          :headers {"Content-Type" "application/fhir+json"}
          :body (json/write-value-as-string
                  {:resourceType "OperationOutcome"
                   :issue [{:severity "error"
                            :code "not-found"
                            :diagnostics "Resource or endpoint not found"}]})})})))

(defn handler
  "Ring handler over [[router]] with [[default-handler]] as the fallback: the
   one-call composition equivalent of `server.core/fhir-app` once `middleware`
   has been built and possibly recomposed. Hosts needing a different fallback
   call `reitit.ring/ring-handler` themselves with [[router]] and their own
   default handler (see [[default-handler]] for the constraint on its body)."
  [schemas middleware]
  (ring/ring-handler (router schemas middleware) default-handler))
