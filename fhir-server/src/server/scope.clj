(ns server.scope
  "SMART on FHIR scope parsing and enforcement.

   Parses the OAuth2 `scope` claim carried in a validated JWT identity and
   gates FHIR resource access on it. Supports both SMART v1 scopes
   (e.g. `patient/Observation.read`, `user/*.write`) and SMART v2 scopes
   (e.g. `patient/Observation.rs`, `system/*.cruds`), including v2 granular
   (query-qualified) scopes such as
   `patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|laboratory`.

   Permission letters follow the SMART v2 convention: c=create, r=read,
   u=update, d=delete, s=search. v1 access levels expand to letter sets:
   `read` -> #{r s}, `write` -> #{c u d}, `*` -> all. v2 letters must appear
   in canonical order (`cruds`); `rc` is malformed.

   Two layers enforce scopes:

   - [[wrap-smart-scope]] gates the request's resource type and interaction:
     some granted scope, qualified or not, must grant the letter.
   - [[wrap-scope-filtering-store]] installs a [[ScopeFilteringStore]] that
     narrows every row a handler reads or writes to what the granted query
     qualifiers admit (see [[grant-filter]]), judges `_include`d resources
     under their own type, and gates the entries of a system-level
     transaction or batch.

   A scope that does not parse is dropped and logged, never treated as a
   wider grant: a qualified scope with a malformed or unsupported query is
   dropped whole, not read as the unqualified scope.

   Patient-compartment row-level restriction (limiting a `patient/` scope to
   the launch patient) is `server.compartment`'s. The compartment prefix
   (patient/user/system) is treated here as granting access to the named
   resource type."
  (:require [clojure.string :as str]
            [fhir-store.protocol :as db]
            [taoensso.telemere :as t]
            [fhir-store.trace :as ftrace])
  (:import [java.net URLDecoder]
           [java.nio.charset StandardCharsets]))

(def ^:private all-permissions #{\c \r \u \d \s})

(def us-core-granular-scopes
  "The US Core granular (query-qualified) `patient/` scopes, as SMART
   discovery advertises them by default."
  ["patient/Condition.rs?category=http://terminology.hl7.org/CodeSystem/condition-category|encounter-diagnosis"
   "patient/Condition.rs?category=http://terminology.hl7.org/CodeSystem/condition-category|problem-list-item"
   "patient/Condition.rs?category=http://hl7.org/fhir/us/core/CodeSystem/condition-category|health-concern"
   "patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|laboratory"
   "patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|social-history"
   "patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|vital-signs"
   "patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|survey"
   "patient/Observation.rs?category=http://hl7.org/fhir/us/core/CodeSystem/us-core-category|sdoh"])

(def granular-parameters
  "The search parameters a granular scope may qualify, per resource type. A
   scope naming any other parameter is dropped as unsupported. Each entry
   needs [[parameter-elements]] to say which element it indexes, and a test."
  {"Condition"   #{"category"}
   "Observation" #{"category"}})

(def ^:private parameter-elements
  "The resource element each supported granular parameter indexes, read by
   [[resource-matches?]]. `category` is `Resource.category`, a
   CodeableConcept[] on both Condition and Observation."
  {"category" :category})

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
  #"^(patient|user|system)/([A-Za-z]+|\*)\.(read|write|\*|c?r?u?d?s?)(?:\?(.*))?$")

(def ^:private resource-scope-prefix-re
  #"^(patient|user|system)/")

;; ---------------------------------------------------------------------------
;; Parsing
;; ---------------------------------------------------------------------------

(defn- percent-decode
  "`s` with each `%XX` escape decoded once as UTF-8, or nil when an escape is
   malformed. A `+` stays a `+`: in a scope it is not a space."
  [s]
  (try (URLDecoder/decode (str/replace s "+" "%2B") StandardCharsets/UTF_8)
       (catch IllegalArgumentException _ nil)))

(defn- parse-token-value
  "One token value of a granular query: `system|code`, `code` (any system),
   `system|` (any code in the system) or `|code` (a coding without a system).
   Returns {:system s-or-nil :code c-or-nil}, with `:no-system? true` for
   `|code`, or nil when malformed."
  [v]
  (if-let [i (str/index-of v "|")]
    (let [system (subs v 0 i)
          code   (subs v (inc i))]
      (cond
        (str/includes? code "|")                     nil
        (and (str/blank? system) (str/blank? code))  nil
        (str/blank? system)                          {:system nil :code code :no-system? true}
        (str/blank? code)                            {:system system :code nil}
        :else                                        {:system system :code code}))
    (when-not (str/blank? v)
      {:system nil :code v})))

(defn- parse-query
  "Parse the query of a granular scope on `resource` into
   {:query {param [token ...]}}, or {:error :malformed|:unsupported}. Params
   AND; the comma-separated values of one param OR."
  [resource query]
  (let [segments (str/split query #"&" -1)
        parsed   (map (fn [segment]
                        (let [i (str/index-of segment "=")]
                          (when (and i (pos? i) (< (inc i) (count segment)))
                            (let [param  (percent-decode (subs segment 0 i))
                                  values (percent-decode (subs segment (inc i)))
                                  tokens (when values
                                           (mapv parse-token-value (str/split values #"," -1)))]
                              (when (and param tokens (every? some? tokens))
                                [param tokens])))))
                      segments)]
    (cond
      (or (str/blank? query) (some nil? parsed))
      {:error :malformed}

      (not (apply distinct? (map first parsed)))
      {:error :malformed}

      (not-every? (get granular-parameters resource #{}) (map first parsed))
      {:error :unsupported}

      :else
      {:query (into {} parsed)})))

(defn- log-dropped! [scope reason]
  (t/log! {:level    :warn
           :id       (if (= :unsupported reason)
                       :authz/smart-scope.unsupported-query
                       :authz/smart-scope.malformed)
           :limit    {"1 per 10 min" [1 600000]}
           :limit-by scope
           :data     {:scope scope}}))

(defn- parse-scope*
  "{:scope parsed-map}, {:dropped reason} for a resource scope that does not
   parse, or nil for a scope that is not a resource scope at all."
  [scope]
  (if-let [[_ compartment resource access query] (re-matches resource-scope-re scope)]
    (let [v1?         (contains? access->permissions access)
          permissions (or (access->permissions access) (set access))
          parsed-q    (when (some? query) (parse-query resource query))]
      (cond
        (empty? permissions)                      {:dropped :malformed}
        (and (some? query) (or v1? (= "*" resource))) {:dropped :malformed}
        (:error parsed-q)                         {:dropped (:error parsed-q)}
        :else
        {:scope {:compartment compartment
                 :resource    resource
                 :permissions permissions
                 :version     (if v1? :v1 :v2)
                 :query       (:query parsed-q)}}))
    (when (re-find resource-scope-prefix-re scope)
      {:dropped :malformed})))

(defn parse-scope
  "Parse a single scope string into a resource-scope map, or nil for
   non-resource scopes (openid, profile, fhirUser, launch, launch/patient,
   offline_access, ...) and for malformed or unsupported resource scopes,
   which are logged as `:authz/smart-scope.malformed` /
   `:authz/smart-scope.unsupported-query`.

   A resource scope returns:
   {:compartment \"patient\" :resource \"Observation\" :permissions #{\\r \\s}
    :version :v1|:v2
    :query nil | {\"category\" [{:system s-or-nil :code c-or-nil} ...]}}

   A query is accepted only on a v2 scope naming a concrete type, with
   parameters from [[granular-parameters]], each once."
  [scope]
  (let [{parsed :scope dropped :dropped} (parse-scope* scope)]
    (when dropped
      (log-dropped! scope dropped))
    parsed))

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

;; ---------------------------------------------------------------------------
;; Grants
;; ---------------------------------------------------------------------------

(defn- grants-type? [{:keys [resource]} fhir-type]
  (or (= resource "*") (= resource fhir-type)))

(defn permitted?
  "True when `parsed-scopes` grant `interaction` on `fhir-type`, qualified or
   not. A wildcard resource scope (`*`) matches any type. This is the type
   gate; which rows a qualified grant admits is [[grant-filter]]'s."
  [parsed-scopes fhir-type interaction]
  (when-let [needed (interaction->permission interaction)]
    (boolean
      (some (fn [s]
              (and (grants-type? s fhir-type)
                   (contains? (:permissions s) needed)))
            parsed-scopes))))

(defn grant-filter
  "What `parsed-scopes` admit of `fhir-type` for `interaction`:

   - `:none` -- no scope grants the interaction's letter on the type;
   - `:all`  -- some granting scope (concrete type or `*`) carries no query,
                and an unqualified grant wins the union;
   - `{:any-of [query-map ...]}` -- only qualified scopes grant it: a
                resource is admitted when it matches ANY of the query maps
                (see [[resource-matches?]])."
  [parsed-scopes fhir-type interaction]
  (let [needed   (interaction->permission interaction)
        granting (filter (fn [s]
                           (and needed
                                (grants-type? s fhir-type)
                                (contains? (:permissions s) needed)))
                         parsed-scopes)]
    (cond
      (empty? granting)                 :none
      (some (comp nil? :query) granting) :all
      :else                             {:any-of (into [] (distinct) (map :query granting))})))

(defn unrestricted?
  "True when no query qualifier can narrow anything `parsed-scopes` grant:
   unqualified wildcard scopes grant read and search on every type, and every
   letter a qualified scope grants is also granted by such a wildcard. Such a
   token (a staff session's `user/*.*`, a backend `system/*.read`) needs no
   filtering store."
  [parsed-scopes]
  (let [wild      (into #{} (comp (filter #(and (= "*" (:resource %)) (nil? (:query %))))
                                  (mapcat :permissions))
                        parsed-scopes)
        qualified (into #{} (comp (filter :query) (mapcat :permissions)) parsed-scopes)]
    (boolean (and (contains? wild \r)
                  (contains? wild \s)
                  (every? wild qualified)))))

;; ---------------------------------------------------------------------------
;; Matching
;; ---------------------------------------------------------------------------

(defn- as-seq [x]
  (cond (nil? x) [] (sequential? x) x :else [x]))

(defn- codings
  "The Codings an element value holds: a CodeableConcept's `coding`, or a
   Coding itself, arrays flattened."
  [value]
  (mapcat (fn [v]
            (cond
              (not (map? v))         []
              (contains? v :coding)  (filter map? (as-seq (:coding v)))
              :else                  [v]))
          (as-seq value)))

(defn- token-matches-coding? [{:keys [system code no-system?]} coding]
  (let [cs (:system coding) cc (:code coding)]
    (cond
      no-system?          (and (nil? cs) (= code cc))
      (and system code)   (and (= system cs) (= code cc))
      system              (= system cs)
      :else               (= code cc))))

(defn resource-matches?
  "True when `resource` satisfies `query-map` (a granular scope's query): for
   every parameter, some Coding of the element it indexes matches one of the
   parameter's token values. `system|code` matches exactly, `code` in any
   system, `system|` any code in the system, `|code` a coding without a
   system. A parameter with no known element never matches."
  [query-map resource]
  (boolean
    (every? (fn [[param tokens]]
              (when-let [element (parameter-elements param)]
                (let [cs (codings (get resource element))]
                  (some (fn [token] (some #(token-matches-coding? token %) cs)) tokens))))
            query-map)))

(defn admits?
  "True when `resource` satisfies the grant filter `f` (`:all` or
   `{:any-of ...}`, see [[grant-filter]])."
  [f resource]
  (or (= :all f)
      (boolean (some #(resource-matches? % resource) (:any-of f)))))

(defn- token->search-value [{:keys [system code no-system?]}]
  (cond
    no-system?        (str "|" code)
    (and system code) (str system "|" code)
    system            (str system "|")
    :else             code))

;; ---------------------------------------------------------------------------
;; Request classification
;; ---------------------------------------------------------------------------

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

(defn route-interaction
  "Resolve the `:smart/interaction` route-data override for a request.

   Operation routes share one route-data map across HTTP methods, so the
   override is either a single interaction keyword or a map keyed by request
   method (e.g. {:get :read :post :update}). Returns:
   - nil when the route sets no override, or the method map has no entry for
     this request's method (method-derived scoring applies);
   - {:interaction kw} for a recognised interaction;
   - {:invalid value} for anything else, which callers must refuse."
  [request]
  (let [route-data (get-in request [:reitit.core/match :data])]
    (when (contains? route-data :smart/interaction)
      (let [override (:smart/interaction route-data)
            value    (if (map? override)
                       (get override (:request-method request) ::absent)
                       override)]
        (cond
          (= ::absent value)                  nil
          (interaction->permission value)     {:interaction value}
          :else                               {:invalid value})))))

(defn effective-interaction
  "The SMART interaction scored for a request: the route's `:smart/interaction`
   override when present, otherwise the method-derived interaction. Returns
   {:interaction kw :source :route|:method} or {:invalid value :source :route}."
  [request]
  (if-let [override (route-interaction request)]
    (assoc override :source :route)
    {:interaction (request->interaction request) :source :method}))

(defn- forbidden [diagnostics]
  {:status 403
   :body   {:resourceType "OperationOutcome"
            :issue [{:severity "error"
                     :code "forbidden"
                     :diagnostics diagnostics}]}})

(defn- refuse! [diagnostics]
  (throw (ex-info diagnostics {:fhir/status 403 :fhir/code "forbidden"})))

(defn- read-refusal [resource-type id]
  (str "Insufficient scope: no granted scope permits read on " resource-type "/" id "."))

;; ---------------------------------------------------------------------------
;; Scope-filtering store decorator
;; ---------------------------------------------------------------------------

(defn- row-filters
  "The grant filters every row of `fhir-type` a search or count returns must
   satisfy: read (`r`), and search (`s`) as well when the type is one the
   request searches directly (`search-types`, a set or `:all`). Returns
   `:none` when either is not granted, else a vector of the qualified filters
   (empty when nothing narrows the type). A type reached only as an
   `_include` or `_revinclude` is judged on read alone."
  [{:keys [scopes search-types]} fhir-type]
  (let [r (grant-filter scopes fhir-type :read)
        s (when (or (= :all search-types) (contains? search-types fhir-type))
            (grant-filter scopes fhir-type :search))]
    (if (or (= :none r) (= :none s))
      :none
      (into [] (comp (filter map?) (distinct)) [r s]))))

(defn- pushdown-pairs
  "The [param value] search parameters that express the qualified filter `f`,
   each value an OR (comma-joined) set. One query map pushes down each of its
   params (they AND). Several push down only when they all name the same
   single param, as one OR set; anything else cannot be said as a FHIR search
   and is refused (fail closed)."
  [f]
  (let [maps (:any-of f)
        join (fn [tokens] (str/join "," (map token->search-value (distinct tokens))))]
    (cond
      (= 1 (count maps))
      (mapv (fn [[param tokens]] [param (join tokens)]) (first maps))

      (and (every? #(= 1 (count %)) maps)
           (apply = (map ffirst maps)))
      (let [param (ffirst (first maps))]
        [[param (join (mapcat #(get % param) maps))]])

      :else
      (refuse! "Insufficient scope: unsupported scope combination."))))

(defn- add-param
  "`params` with `value` ANDed onto `param`: a repeated parameter (a vector),
   whichever key spelling the caller used. With no client value the store
   receives one plain string.

   A repeated parameter is FHIR's AND, kept rather than replaced by a
   token-wise intersection: the client's value and the grant may be met by
   different codings of one CodeableConcept[] (an Observation categorized both
   `survey` and `sdoh`, searched for `sdoh` under a `survey` grant), which an
   intersection of the two token sets would wrongly exclude. The mock and
   Datomic stores AND repeated values. A store that does not can only return
   too few or too many rows: too many are removed again by the check
   [[ScopeFilteringStore]] runs over every search result, but a count is only
   as accurate as the store's AND."
  [params param value]
  (let [kw       (keyword param)
        existing (concat (as-seq (get params param)) (as-seq (get params kw)))]
    (-> params
        (dissoc kw)
        (assoc param (if (seq existing) (conj (vec existing) value) value)))))

(defn- pushdown
  "`params` narrowed by `filters`, or nil when a filter names a parameter the
   type's `registry` does not index (nothing can be shown to match)."
  [params registry filters]
  (reduce (fn [p [param value]]
            (if (contains? registry param)
              (add-param p param value)
              (reduced nil)))
          params
          (mapcat pushdown-pairs filters)))

(defn- matches-filters? [filters resource]
  (every? #(admits? % resource) filters))

(defn- write-refusal
  "nil when writing `resource` (post-image; nil for a delete) over `existing`
   (the stored version, or nil) is within the scopes' `interaction` grant on
   `resource-type`, else the refusal diagnostics. A type the scopes do not
   grant at all is left to the type gate: an operation may write records of
   types its route does not name."
  [scopes interaction resource-type resource existing]
  (let [f (grant-filter scopes resource-type interaction)]
    (when (map? f)
      (when-not (and (or (nil? resource) (admits? f resource))
                     (or (nil? existing) (admits? f existing)))
        (str "Insufficient scope: no granted scope permits " (name interaction)
             " of this " resource-type ".")))))

(defn- entry-interaction [method id url]
  (case method
    ("GET" "HEAD") (if (and id (not (str/includes? url "?"))) :read :search)
    "POST"         :create
    ("PUT" "PATCH") :update
    "DELETE"       :delete
    nil))

(defn- entry-refusal
  "nil when a Bundle `entry` may reach the base store, else the refusal
   diagnostics. With `gate-types?` (a Bundle POSTed to the base, whose entries
   no type gate has seen) an entry must also be of a type and interaction the
   scopes grant. A qualified grant is judged on the entry's content against
   the state before the Bundle runs; shapes the base would resolve out of
   sight -- a conditional entry, a search, an unresolved PATCH -- are refused
   under a qualified grant."
  [{:keys [base scopes gate-types?] :as store} tenant-id entry]
  (let [request (:request entry)
        method  (some-> (:method request) str/upper-case)
        url     (str (:url request))
        [type-and-id] (str/split url #"\?" 2)
        [type id] (str/split type-and-id #"/")
        interaction (entry-interaction method id url)]
    (cond
      ;; A leading slash, an absolute URL or an unknown method leaves the
      ;; entry with no type or interaction to judge. The base parses the URL
      ;; the same way and cannot resolve it either, but a store may fall back
      ;; to the body's resourceType, so a token whose grants can narrow
      ;; anything is refused rather than trusted to that.
      (or (str/blank? type) (nil? interaction))
      (when-not (unrestricted? scopes)
        (str "Insufficient scope: a Bundle entry whose request names no resource type "
             "or no supported method cannot be judged under these scopes."))

      (and gate-types? (not (permitted? scopes type interaction)))
      (str "Insufficient scope: no granted scope permits " (name interaction) " on " type ".")

      :else
      (let [f (grant-filter scopes type interaction)
            rt (keyword type)]
        (when (map? f)
          (cond
            (or (= :search interaction)
                (str/includes? url "?") (:ifNoneExist request) (get request "ifNoneExist"))
            "Insufficient scope: a conditional or search Bundle entry cannot be judged under a qualified scope."

            (= "PATCH" method)
            "Insufficient scope: an unresolved PATCH entry cannot be judged under a qualified scope."

            (= :read interaction)
            (try (db/read-resource store tenant-id rt id) nil
                 (catch clojure.lang.ExceptionInfo e (ex-message e)))

            (and (:resource entry) (not= type (:resourceType (:resource entry))))
            (str "Bundle entry resource type does not match its url: " url)

            :else
            (write-refusal scopes interaction type (:resource entry)
                           (when (and id (#{:update :delete} interaction))
                             (db/read-resource base tenant-id rt id)))))))))

(defn- refused-entry [diagnostics]
  {:response {:status "403 Forbidden"
              :outcome {:resourceType "OperationOutcome"
                        :issue [{:severity "error" :code "forbidden" :diagnostics diagnostics}]}}})

(defrecord ScopeFilteringStore [base scopes search-types all-registries gate-types?]
  db/IFHIRStore
  (search [this tenant-id resource-type params search-registry]
    (let [ft       (name resource-type)
          registry (or search-registry (get all-registries ft))
          filters  (row-filters this ft)]
      (cond
        (= :none filters)  []
        (empty? filters)   (db/search base tenant-id resource-type params registry)
        :else
        (if-let [p (pushdown (dissoc params "_summary" :_summary) registry filters)]
          ;; The push-down is the filter; the check over the returned rows
          ;; keeps a store that mishandles it from widening the grant. That
          ;; check needs the whole row, so `_summary` is not passed down: a
          ;; store may project the page it answers (the Datomic store pulls
          ;; only id, meta and text), and the response middleware applies
          ;; `_summary` afterwards anyway.
          (filterv #(matches-filters? filters %)
                   (db/search base tenant-id resource-type p registry))
          []))))

  (count-resources [this tenant-id resource-type params search-registry]
    (let [ft       (name resource-type)
          registry (or search-registry (get all-registries ft))
          filters  (row-filters this ft)]
      (cond
        (= :none filters)  0
        (empty? filters)   (db/count-resources base tenant-id resource-type params registry)
        :else
        (if-let [p (pushdown params registry filters)]
          (db/count-resources base tenant-id resource-type p registry)
          0))))

  (read-resource [_ tenant-id resource-type id]
    (let [ft (name resource-type)
          f  (grant-filter scopes ft :read)]
      (when (= :none f) (refuse! (read-refusal ft id)))
      (let [resource (db/read-resource base tenant-id resource-type id)]
        (when (and resource (not (admits? f resource)))
          (refuse! (read-refusal ft id)))
        resource)))

  (vread-resource [_ tenant-id resource-type id vid]
    (let [ft (name resource-type)
          f  (grant-filter scopes ft :read)]
      (when (= :none f) (refuse! (read-refusal ft id)))
      (let [version (db/vread-resource base tenant-id resource-type id vid)]
        (when (and version (not (admits? f version)))
          (refuse! (read-refusal ft id)))
        version)))

  (resource-deleted? [_ tenant-id resource-type id]
    (db/resource-deleted? base tenant-id resource-type id))

  (create-resource [this tenant-id resource-type id resource]
    (db/create-resource this tenant-id resource-type id resource nil))

  (create-resource [_ tenant-id resource-type id resource opts]
    (when-let [r (write-refusal scopes :create (name resource-type) resource nil)]
      (refuse! r))
    (if opts
      (db/create-resource base tenant-id resource-type id resource opts)
      (db/create-resource base tenant-id resource-type id resource)))

  (update-resource [this tenant-id resource-type id resource]
    (db/update-resource this tenant-id resource-type id resource nil))

  (update-resource [_ tenant-id resource-type id resource opts]
    (when (map? (grant-filter scopes (name resource-type) :update))
      (when-let [r (write-refusal scopes :update (name resource-type) resource
                                  (db/read-resource base tenant-id resource-type id))]
        (refuse! r)))
    (if opts
      (db/update-resource base tenant-id resource-type id resource opts)
      (db/update-resource base tenant-id resource-type id resource)))

  (delete-resource [this tenant-id resource-type id]
    (db/delete-resource this tenant-id resource-type id nil))

  (delete-resource [_ tenant-id resource-type id opts]
    (when (map? (grant-filter scopes (name resource-type) :delete))
      (when-let [existing (db/read-resource base tenant-id resource-type id)]
        (when-let [r (write-refusal scopes :delete (name resource-type) nil existing)]
          (refuse! r))))
    (if opts
      (db/delete-resource base tenant-id resource-type id opts)
      (db/delete-resource base tenant-id resource-type id)))

  (history [_ tenant-id resource-type id]
    (let [f (grant-filter scopes (name resource-type) :read)]
      (if (= :none f)
        []
        (filterv #(admits? f %) (db/history base tenant-id resource-type id)))))

  (history-type [_ tenant-id resource-type params]
    (let [f (grant-filter scopes (name resource-type) :read)]
      (if (= :none f)
        []
        (filterv #(admits? f %) (db/history-type base tenant-id resource-type params)))))

  (transact-transaction [this tenant-id entries]
    (db/transact-transaction this tenant-id entries nil))

  ;; Atomic: every entry is judged before any reaches the base.
  (transact-transaction [this tenant-id entries opts]
    (doseq [entry entries]
      (when-let [r (entry-refusal this tenant-id entry)]
        (refuse! r)))
    (if opts
      (db/transact-transaction base tenant-id entries opts)
      (db/transact-transaction base tenant-id entries)))

  (transact-bundle [this tenant-id entries]
    (db/transact-bundle this tenant-id entries nil))

  ;; Batch: a refused entry is answered in place and the rest go to the base,
  ;; whose responses are woven back into input order.
  (transact-bundle [this tenant-id entries opts]
    (let [checked  (mapv (fn [entry]
                           (if-let [r (entry-refusal this tenant-id entry)]
                             {:refusal r}
                             {:entry entry}))
                         entries)
          admitted (into [] (keep :entry) checked)
          res      (cond
                     (empty? admitted) {:resourceType "Bundle" :type "batch-response" :entry []}
                     opts (db/transact-bundle base tenant-id admitted opts)
                     :else (db/transact-bundle base tenant-id admitted))
          from-base (volatile! (seq (:entry res)))]
      (assoc res :entry (mapv (fn [{:keys [refusal]}]
                                (if refusal
                                  (refused-entry refusal)
                                  (let [[head & tail] @from-base]
                                    (vreset! from-base tail)
                                    head)))
                              checked))))

  (create-tenant [_ tenant-id] (db/create-tenant base tenant-id))
  (create-tenant [_ tenant-id opts] (db/create-tenant base tenant-id opts))
  (delete-tenant [_ tenant-id] (db/delete-tenant base tenant-id))
  (delete-tenant [_ tenant-id opts] (db/delete-tenant base tenant-id opts))
  (warmup-tenant [_ tenant-id] (db/warmup-tenant base tenant-id))
  (warmup-tenant [_ tenant-id opts] (db/warmup-tenant base tenant-id opts))

  ;; Bulk-export snapshot reads pass through: server.bulk-export applies the
  ;; granular filter while streaming, as it does compartment confinement.
  (current-basis [_ tenant-id] (db/current-basis base tenant-id))
  (scan-type-as-of [_ tenant-id resource-type basis]
    (db/scan-type-as-of base tenant-id resource-type basis))
  (count-as-of [_ tenant-id resource-type basis]
    (db/count-as-of base tenant-id resource-type basis))

  db/ITxMetadataStore
  (tx-metadata-supported? [_]
    (db/supports-tx-metadata? base))
  (tx-metadata-of [this tenant-id resource-type id vid]
    (when (and (db/supports-tx-metadata? base)
               (db/vread-resource this tenant-id resource-type id vid))
      (db/tx-metadata-of base tenant-id resource-type id vid))))

(defn filtering-store
  "Wraps `base` so every row read or written through it is narrowed to what
   `scopes` (parsed) admit. `search-types` is the set of type names the
   request searches directly (or `:all`), whose rows must also satisfy the
   search grant. `gate-types?` makes Bundle entries pass the type gate too,
   for a Bundle POSTed to the base."
  [base {:keys [scopes search-types all-registries gate-types?]}]
  (->ScopeFilteringStore base (vec scopes) (or search-types #{}) all-registries
                         (boolean gate-types?)))

;; ---------------------------------------------------------------------------
;; Middleware
;; ---------------------------------------------------------------------------

(defn wrap-smart-scope
  "Middleware that enforces SMART on FHIR scopes from the JWT identity.

   Requires `:identity` to be populated by server.auth/wrap-jwt-auth and so
   must run after it. Bypasses enforcement for routes marked `:public? true`.
   System endpoints that do not resolve to a FHIR resource type pass this gate;
   [[wrap-scope-filtering-store]] narrows what they reach type by type.

   This is the type gate: some granted scope, qualified or not, must grant the
   interaction on the request's type. Which rows a qualified grant admits is
   enforced by the store [[wrap-scope-filtering-store]] installs.

   A route may pin the scored interaction with `:smart/interaction` (see
   `route-interaction`). An unrecognised override is refused with 403."
  [handler _opts]
  (fn [request]
    (let [route-data (get-in request [:reitit.core/match :data])
          public?    (:public? route-data)]
      (if public?
        (handler request)
        (let [scopes      (request-scopes request)
              fhir-type   (request->fhir-type request)
              effective   (effective-interaction request)
              {:keys [interaction invalid source]} effective]
          (cond
            (contains? effective :invalid)
            (do (t/log! {:level :error
                         :id    :authz/smart-scope.invalid-route-interaction
                         :data  {:uri (:uri request)
                                 :request-method (:request-method request)
                                 :value invalid}})
                (forbidden "Route declares an unrecognised :smart/interaction; access refused."))

            (nil? fhir-type)
            (handler request)

            (empty? scopes)
            (forbidden "No SMART scopes present in token; insufficient scope.")

            :else
            (let [allowed? (ftrace/trace!
                             {:id :authz/smart-scope.check
                              :data {:fhir-type fhir-type
                                     :interaction interaction
                                     :interaction-source source
                                     :scopes (mapv #(cond-> (str (:compartment %) "/" (:resource %)
                                                                 "." (apply str (sort (:permissions %))))
                                                      (:query %) (str "?" (str/join "&" (keys (:query %)))))
                                                   scopes)}}
                             (permitted? scopes fhir-type interaction))]
              (if allowed?
                (handler request)
                (forbidden (format "Insufficient scope: no granted scope permits %s on %s."
                                   (name interaction) fhir-type))))))))))

(def ^:private fhir-base-path-re
  #"^/[^/]+/fhir/?$")

(defn- base-bundle-post?
  "True for a request that POSTs a Bundle to the FHIR base (a transaction or
   batch), whose entries no type gate has seen."
  [request fhir-type]
  (and (nil? fhir-type)
       (= :post (:request-method request))
       (boolean (re-matches fhir-base-path-re (or (:uri request) "")))))

(defn- search-types
  "The type names a request searches directly, whose rows must also satisfy
   the search grant: its own type, and the target of a compartment search
   (`Patient/123/Observation`; `*` is every type). `:all` for a system
   endpoint that names no type."
  [request fhir-type]
  (let [target (get-in request [:path-params :target-type])]
    (cond
      (nil? fhir-type) :all
      (= "*" target)   :all
      target           #{fhir-type target}
      :else            #{fhir-type})))

(defn wrap-scope-filtering-store
  "Middleware that installs a [[ScopeFilteringStore]] on `:fhir/store` for
   every non-public request whose scopes can narrow anything (see
   [[unrestricted?]]), and for every Bundle POSTed to the base, whose entries
   it gates by type.

   Runs INSIDE `server.compartment/wrap-patient-compartment`, so it wraps the
   compartment-filtering store rather than the reverse: a read of an
   in-compartment resource the qualified scopes do not admit is answered 403,
   as SMART asks, instead of looking absent. Both narrowings apply (AND).

   The request's own type, and a compartment search's target type, must also
   satisfy the search grant on a search; a type reached by
   `_include`/`_revinclude` is judged on read alone, under its own type. A
   system endpoint (`/_search`, `/_history`) is narrowed type by type."
  [handler _opts]
  (fn [request]
    (let [route-data (get-in request [:reitit.core/match :data])]
      (if (:public? route-data)
        (handler request)
        (let [scopes    (request-scopes request)
              fhir-type (request->fhir-type request)
              bundle?   (base-bundle-post? request fhir-type)]
          (if (and (unrestricted? scopes) (not bundle?))
            (handler request)
            (handler (assoc request :fhir/store
                            (filtering-store (:fhir/store request)
                                             {:scopes         scopes
                                              :search-types   (search-types request fhir-type)
                                              :all-registries (:fhir/all-registries route-data)
                                              :gate-types?    bundle?})))))))))
