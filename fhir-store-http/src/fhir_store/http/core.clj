(ns fhir-store.http.core
  "An `IFHIRStore` whose storage is another FHIR server, reached over its REST
   API: a GCP Healthcare FHIR store, a HAPI server, or another dromon.

   Each tenant maps to one remote FHIR base URL through `:base-url`, which is
   either a string template carrying `{tenant}` or a function of the tenant id.
   There is deliberately no form that sends every tenant to one URL by
   accident: a host that wants that writes a function that ignores its
   argument, so the loss of tenant isolation is visible at the call site.

   WHAT MAPS DIRECTLY. read, vread, update, delete, history, search, count and
   transaction Bundles are one or more REST calls each. `:if-match` becomes an
   `If-Match` header, and the remote's own atomic check decides the write; a
   remote 412 comes back as the protocol's 412 conflict.

   WHAT DOES NOT, and how this store answers instead:

   - Snapshot verbs (`current-basis`, `scan-type-as-of`, `count-as-of`) have no
     REST equivalent, so they throw 501. They sit on `IFHIRStore` itself rather
     than on a capability protocol, which is why a store has to implement them
     only to refuse.
   - Tenants are provisioned on the remote, not by this store.
     `create-tenant` succeeds only with `:if-exists :ignore` (it then probes
     the remote); `delete-tenant` always throws 501, because deleting a remote
     store's data is not something a proxy should do on request.
   - `:tx-metadata` is accepted and dropped. This store does not implement
     `ITxMetadataStore`, because nothing writes the stamp in the same
     transaction as the data.
   - No write returns a `:fhir-store/basis`: a REST server exposes no
     monotonically increasing transaction id. The protocol makes it optional.
   - The synthetic `_compartment` parameter cannot be pushed down to a REST
     search, so a search carrying it is refused with 501 rather than run
     without its filter.

   CREATE. Every create writes under an id this store knows before the write,
   because the lifecycle contract requires the id to be final before
   `prepare`. A create without an id mints a UUID; every create is a `PUT
   Type/<id>` carrying `If-None-Match: *`. A caller-supplied id is read first
   and refused with 409 when it exists. That pre-read is racy and the header is
   what closes the race, but only on a remote that honours `If-None-Match` on
   PUT (dromon's fhir-server does not), so a remote that answers 200 instead of
   201 to a create has updated an existing resource, and the write is reported
   as a 500 naming that.

   SEARCH. Every search goes out with `Prefer: handling=strict`, so a remote
   that does not know a parameter refuses it instead of silently widening the
   result. Result-shaping parameters the server applies itself (`_include`,
   `_revinclude`, `_elements`, `_summary`, `_total`, ...) are not forwarded,
   and only entries with search mode `match` are returned. `_skip` paging is
   sent as `:offset-param` when the remote has one (`_skip` for dromon,
   `_offset` for HAPI) and otherwise emulated by walking the remote's `next`
   links, which is what a GCP store needs and costs a page fetch per skipped
   page.

   Every link the remote hands back (a `next` page) must start with the
   tenant's own base URL, since the `Authorization` header goes with it.

   LIFECYCLE. `fhir-store.lifecycle` runs as on every other store, with three
   store-specific readings:

   - `tx-ops` are Bundle entries -- this store's dialect is FHIR itself. A
     write that gains ops is sent as a `transaction` Bundle with its own entry
     first and the ops after it, so the remote commits them atomically.
   - `prepare` runs BEFORE the remote checks `:if-match`, because that check
     is the remote's. The contract already requires `prepare` to have no
     effect that outlives a refused write, so the only visible difference is
     that `prepare` can run for a write the remote then refuses.
   - `:db` on the write map is nil and `:entity` is nil; a lifecycle that
     needs to read goes through `:store`.

   A batch Bundle is run entry by entry through the single-write verbs, so
   each entry gets its own lifecycle pass and its own remote transaction.

   TRANSPORT. `:http/request` replaces the HTTP client: a function of
   `{:method :url :headers :body}` (body a JSON string or nil) returning
   `{:status :headers :body}`. The default uses `java.net.http.HttpClient`.
   `:authorization` is nil, a header value, or a no-argument function called
   per request so a token source can refresh.

   VALUES. Bodies handed to the write verbs are in the server's decoded form
   (java.time values, promoted extensions). With `:resource/schemas` they are
   encoded through `fhir-json-transformer` per resource type, the same
   encoder the xtdb2 store uses on its read path; a type with no schema falls
   back to writing java.time values as ISO strings. Reads return the remote's
   JSON as keyword-keyed maps, decimals as BigDecimal."
  (:require [clojure.string :as str]
            [com.breezeehr.fhir-json-transform :as fjt]
            [fhir-store.lifecycle :as lc]
            [fhir-store.protocol :as fp]
            [fhir-store.trace :as ftrace]
            [jsonista.core :as json]
            [malli.core :as m])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.time Duration)
           (java.time.temporal TemporalAccessor)))

;; ---------------------------------------------------------------------------
;; JSON
;; ---------------------------------------------------------------------------

(def ^:private mapper
  (json/object-mapper {:decode-key-fn true :bigdecimals true}))

(defn- read-json [s]
  (when-not (str/blank? s)
    (json/read-value s mapper)))

(defn- write-json [v]
  (json/write-value-as-string v mapper))

;; ---------------------------------------------------------------------------
;; Errors
;; ---------------------------------------------------------------------------

(defn- refuse!
  ([status code message] (refuse! status code message nil))
  ([status code message extra]
   (throw (ex-info message (merge {:fhir/status status :fhir/code code} extra)))))

(defn- not-supported! [what]
  (refuse! 501 "not-supported" (str what " is not supported by the HTTP store")))

(defn- outcome-issues
  "The remote OperationOutcome's issue codes and expressions, never its
   diagnostics: a remote may quote the submitted value back, and ex-data is
   logged by the server's exception middleware."
  [body]
  (when (= "OperationOutcome" (:resourceType body))
    (mapv #(select-keys % [:severity :code :expression]) (:issue body))))

(defn- remote-error!
  "Turn a non-2xx remote answer into the ex-info the protocol speaks. The
   message names the interaction and the status, nothing from the body."
  [interaction {:keys [status body]} extra]
  (let [parsed (try (read-json body) (catch Exception _ nil))
        issues (outcome-issues parsed)
        code   (or (some :code issues)
                   (case (int status) 412 "conflict" 409 "conflict" 404 "not-found" "exception"))]
    (refuse! (if (<= 400 status 599) status 502)
             code
             (str "Remote FHIR server answered " status " to " interaction)
             (cond-> extra
               (seq issues) (assoc :fhir-store.http/remote-issues issues)))))

;; ---------------------------------------------------------------------------
;; Identifiers and URLs
;; ---------------------------------------------------------------------------

(def ^:private tenant-pattern #"[A-Za-z0-9._-]{1,128}")
(def ^:private type-pattern #"[A-Z][A-Za-z]{0,63}")
(def ^:private id-pattern #"[A-Za-z0-9\-.]{1,64}")

(defn- checked
  "`v` as a string when it matches `pattern`; a 400 otherwise. Every path
   segment goes through here, so no value can add a segment or a query."
  [what pattern v]
  (let [s (if (keyword? v) (name v) (str v))]
    (if (re-matches pattern s)
      s
      (refuse! 400 "invalid" (str "Not a valid " what " for the HTTP store")))))

(defn- tenant-base
  [{:keys [base-url]} tenant-id]
  (let [tenant (checked "tenant id" tenant-pattern tenant-id)
        url    (if (fn? base-url)
                 (base-url tenant)
                 (str/replace base-url "{tenant}" tenant))]
    (str/replace url #"/+$" "")))

(defn- encode-component [s]
  (-> (URLEncoder/encode (str s) StandardCharsets/UTF_8)
      (str/replace "+" "%20")))

(defn- query-string
  "`params` as a query string. A sequential value repeats its key, which is
   how FHIR spells AND across one parameter."
  [params]
  (->> params
       (mapcat (fn [[k v]]
                 (let [k (if (keyword? k) (subs (str k) 1) (str k))]
                   (map (fn [x] (str (encode-component k) "=" (encode-component x)))
                        (if (sequential? v) v [v])))))
       (str/join "&")))

(defn- with-query [url params]
  (if (seq params) (str url "?" (query-string params)) url))

(defn- same-base!
  "A remote-supplied link resolved against the tenant's base URL, refused when
   it leaves that base. Resolved first because a remote may answer with a
   relative link (dromon's fhir-server does)."
  [base link]
  (let [link (str (.resolve (URI/create base) ^String link))]
    (when-not (or (= link base) (str/starts-with? link (str base "/"))
                  (str/starts-with? link (str base "?")))
      (refuse! 502 "exception" "Remote FHIR server returned a link outside the tenant's base URL"))
    link))

;; ---------------------------------------------------------------------------
;; Transport
;; ---------------------------------------------------------------------------

(defn java-http-transport
  "The default `:http/request`: a blocking `java.net.http.HttpClient` call.
   Response header names are lower-cased."
  [{:keys [connect-timeout-ms request-timeout-ms]
    :or   {connect-timeout-ms 10000 request-timeout-ms 60000}}]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                   (.followRedirects HttpClient$Redirect/NEVER)
                   (.build))]
    (fn [{:keys [method url headers body]}]
      (let [publisher (if body
                        (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8)
                        (HttpRequest$BodyPublishers/noBody))
            builder   (-> (HttpRequest/newBuilder (URI/create url))
                          (.timeout (Duration/ofMillis request-timeout-ms))
                          (.method (str/upper-case (name method)) publisher))
            _         (doseq [[k v] headers] (.header builder k v))
            response  (.send client (.build builder) (HttpResponse$BodyHandlers/ofString))]
        {:status  (.statusCode response)
         :headers (into {}
                        (map (fn [[k vs]] [(str/lower-case k) (first vs)]))
                        (.map (.headers response)))
         :body    (.body response)}))))

(defn- authorization-header [{:keys [authorization]}]
  (cond
    (nil? authorization) nil
    (fn? authorization)  (authorization)
    :else                (str authorization)))

(defn- send!
  [store {:keys [headers body] :as request}]
  (let [auth    (authorization-header store)
        headers (cond-> (merge {"Accept" "application/fhir+json"} headers)
                  body (assoc "Content-Type" "application/fhir+json")
                  auth (assoc "Authorization" auth))]
    ((:http/request store) (assoc request :headers headers))))

(defn- success? [{:keys [status]}] (<= 200 status 299))

;; ---------------------------------------------------------------------------
;; Values
;; ---------------------------------------------------------------------------

(defn- build-encoders
  "resource-type string -> (fn [resource] wire-shaped resource), for every
   schema that names its :resourceType."
  [schemas]
  (let [xf (fjt/fhir-json-transformer)]
    (into {}
          (keep (fn [schema]
                  (when-let [rt (:resourceType (try (m/properties schema)
                                                    (catch Exception _ nil)))]
                    [rt (m/encoder schema xf)])))
          schemas)))

(defn- plain-encode
  "The fallback for a type with no schema: java.time values as ISO strings."
  [v]
  (cond
    (map? v)                     (update-vals v plain-encode)
    (sequential? v)              (mapv plain-encode v)
    (instance? TemporalAccessor v) (str v)
    :else                        v))

(defn- encode-resource [store resource]
  (when resource
    (if-let [enc (get (:encoders store) (:resourceType resource))]
      (plain-encode (enc resource))
      (plain-encode resource))))

(defn- etag-header
  "The If-Match header value for an `:if-match` opt, or nil for none."
  [if-match]
  (let [v (fp/normalize-if-match if-match)]
    (cond
      (nil? v)                  nil
      (= fp/if-match-any v)     "*"
      :else                     (str "W/\"" v "\""))))

(defn- conflict!
  [interaction if-match response]
  (remote-error! interaction response
                 {:fhir/code "conflict"
                  :expected  (fp/if-match-label (fp/normalize-if-match if-match))
                  :actual    nil}))

;; ---------------------------------------------------------------------------
;; Reads
;; ---------------------------------------------------------------------------

(defn- get-json
  "GET `url`: the parsed body on 2xx, nil on 404 or 410, a thrown remote error
   otherwise."
  [store interaction url]
  (let [response (send! store {:method :get :url url})]
    (cond
      (success? response)               (read-json (:body response))
      (#{404 410} (:status response))   nil
      :else                             (remote-error! interaction response nil))))

(defn- bundle-pages
  "Every entry across `first-url` and the `next` pages after it, stopping as
   soon as `enough?` holds for the entries gathered so far."
  [store interaction base first-url enough?]
  (loop [url first-url acc []]
    (let [bundle (get-json store interaction url)
          acc    (into acc (:entry bundle))
          next   (some #(when (= "next" (:relation %)) (:url %)) (:link bundle))]
      (if (and next (not (enough? acc)))
        (recur (same-base! base next) acc)
        acc))))

(defn- versions-of
  "History Bundle entries -> the versions that carry a resource. A delete is
   an entry without one, and the protocol's history lists only versions."
  [entries]
  (into [] (keep :resource) entries))

;; ---------------------------------------------------------------------------
;; Search
;; ---------------------------------------------------------------------------

(def ^:private server-applied-params
  "Parameters the fhir-server applies to a search itself, after the store
   returns. Forwarding them would ask the remote to shape what the server
   then shapes again, or to return entries the store would have to drop."
  #{"_include" "_revinclude" "_elements" "_summary" "_total" "_format"
    "_pretty" "_contained" "_containedType" "_count" "_skip" "_offset"})

(defn- param-name [k] (if (keyword? k) (subs (str k) 1) (str k)))

(defn- forwarded-params [params]
  (when (some #(= "_compartment" (param-name %)) (keys params))
    (not-supported! "The _compartment search push-down"))
  (into {} (remove (fn [[k _]] (server-applied-params (param-name k)))) params))

(defn- param-int [params k default]
  (let [v (or (get params (keyword k)) (get params k))]
    (cond
      (nil? v)     default
      (number? v)  (long v)
      :else        (parse-long (str v)))))

(def ^:private strict {"Prefer" "handling=strict"})

(defn- search-entries
  [store tenant-id resource-type params]
  (let [base  (tenant-base store tenant-id)
        rt    (checked "resource type" type-pattern resource-type)
        limit (param-int params "_count" 50)
        skip  (param-int params "_skip" 0)
        query (cond-> (assoc (forwarded-params params) "_count" (str limit))
                (and (:offset-param store) (pos? skip))
                (assoc (:offset-param store) (str skip)))
        ;; With an offset parameter the remote has already skipped; without
        ;; one, the skipped rows are fetched and dropped here.
        local-skip (if (:offset-param store) 0 skip)
        fetch  (fn [u]
                 (let [response (send! store {:method :get :url u :headers strict})]
                   (if (success? response)
                     (read-json (:body response))
                     (remote-error! "search" response nil))))
        match? #(contains? #{nil "match"} (get-in % [:search :mode]))]
    (loop [u (with-query (str base "/" rt) query) acc []]
      (let [bundle (fetch u)
            acc    (into acc (comp (filter match?) (keep :resource)) (:entry bundle))
            next   (some #(when (= "next" (:relation %)) (:url %)) (:link bundle))]
        (if (and next (< (count acc) (+ local-skip limit)))
          (recur (same-base! base next) acc)
          (vec (take limit (drop local-skip acc))))))))

(defn- count-entries
  [store tenant-id resource-type params]
  (let [base  (tenant-base store tenant-id)
        rt    (checked "resource type" type-pattern resource-type)
        query (assoc (forwarded-params params) "_summary" "count")
        response (send! store {:method :get
                               :url (with-query (str base "/" rt) query)
                               :headers strict})]
    (if (success? response)
      (let [total (:total (read-json (:body response)))]
        (if (number? total)
          (long total)
          (refuse! 502 "exception" "Remote FHIR server answered a count search without a total")))
      (remote-error! "count" response nil))))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(def ^:private return-representation {"Prefer" "return=representation"})

(defn- lifecycle-write
  "Run `prepare` then `tx-ops` for one create or update: the write map with
   :resource the prepared body and :tx-ops the Bundle entries it adds."
  [store tenant-id method resource-type id resource opts]
  (let [lifecycle (:resource/lifecycle store)
        write     {:tenant-id     (str tenant-id)
                   :resource-type (name resource-type)
                   :id            id
                   :method        method
                   :resource      resource
                   :opts          opts
                   :db            nil
                   :entity        nil
                   :store         store}
        prepared  (lc/prepare-write lifecycle write)
        ops-write (assoc write :resource prepared :submitted resource)]
    (assoc ops-write :tx-ops (lc/write-tx-ops lifecycle ops-write))))

(defn- fire-after-commit! [store tenant-id writes result]
  (when (seq writes)
    (lc/fire-after-commit! (:resource/lifecycle store)
                           {:tenant-id (str tenant-id)
                            :writes    writes
                            :result    result
                            :store     store})))

(defn- encode-entry
  "A Bundle entry with its resource encoded for the wire."
  [store entry]
  (cond-> entry
    (:resource entry) (update :resource #(encode-resource store %))))

(defn- put-entry
  [store {:keys [resource-type id resource]} headers]
  {:fullUrl  (str resource-type "/" id)
   :resource (encode-resource store (assoc resource :resourceType resource-type :id id))
   :request  (cond-> {:method "PUT" :url (str resource-type "/" id)}
               (get headers "If-Match")      (assoc :ifMatch (get headers "If-Match"))
               (get headers "If-None-Match") (assoc :ifNoneMatch (get headers "If-None-Match")))})

(defn- post-transaction!
  "POST a transaction Bundle of `entries`; the transaction-response Bundle."
  [store tenant-id entries interaction if-match]
  (let [base     (tenant-base store tenant-id)
        response (send! store {:method  :post
                               :url     base
                               :headers return-representation
                               :body    (write-json {:resourceType "Bundle"
                                                     :type         "transaction"
                                                     :entry        entries})})]
    (cond
      (success? response)            (read-json (:body response))
      (= 412 (:status response))     (conflict! interaction if-match response)
      :else                          (remote-error! interaction response nil))))

(defn- put-one!
  "Write one prepared resource with `headers`, as a plain PUT when the write
   carries no tx-ops and as a transaction Bundle when it does. Returns
   [status resource]."
  [store tenant-id {:keys [resource-type id tx-ops] :as write} headers interaction if-match]
  (if (seq tx-ops)
    (let [entries (into [(put-entry store write headers)]
                        (map #(encode-entry store %))
                        tx-ops)
          bundle  (post-transaction! store tenant-id entries interaction if-match)
          entry   (first (:entry bundle))
          status  (some-> entry :response :status (str/split #" ") first parse-long)]
      [status (or (:resource entry)
                  (get-json store "read" (str (tenant-base store tenant-id) "/" resource-type "/" id)))])
    (let [url      (str (tenant-base store tenant-id) "/" resource-type "/" id)
          body     (write-json (encode-resource store (assoc (:resource write)
                                                             :resourceType resource-type
                                                             :id id)))
          response (send! store {:method  :put
                                 :url     url
                                 :headers (merge return-representation headers)
                                 :body    body})]
      (cond
        (success? response)
        [(:status response)
         (or (read-json (:body response))
             (get-json store "read" url))]

        (= 412 (:status response)) (conflict! interaction if-match response)
        :else                      (remote-error! interaction response nil)))))

(defn- create!
  [store tenant-id resource-type id resource opts]
  (let [rt      (checked "resource type" type-pattern resource-type)
        minted? (nil? id)
        id      (if minted? (str (random-uuid)) (checked "resource id" id-pattern id))]
    (when (and (not minted?)
               (get-json store "read" (str (tenant-base store tenant-id) "/" rt "/" id)))
      (refuse! 409 "duplicate" "Resource already exists"))
    (let [write             (lifecycle-write store tenant-id :create rt id resource opts)
          [status created]  (put-one! store tenant-id write {"If-None-Match" "*"} "create" nil)]
      (when (and status (not= 201 status))
        (refuse! 500 "exception"
                 (str "Remote FHIR server answered " status " to a create: it ignored "
                      "If-None-Match and may have updated an existing resource")))
      (fire-after-commit! store tenant-id [(assoc write :resource created)] created)
      created)))

(defn- update!
  [store tenant-id resource-type id resource opts]
  (let [rt       (checked "resource type" type-pattern resource-type)
        id       (checked "resource id" id-pattern id)
        if-match (:if-match opts)
        header   (etag-header if-match)
        write    (lifecycle-write store tenant-id :update rt id resource opts)
        [_ updated] (put-one! store tenant-id write
                              (cond-> {} header (assoc "If-Match" header))
                              "update" if-match)]
    (fire-after-commit! store tenant-id [(assoc write :resource updated)] updated)
    updated))

(defn- delete!
  [store tenant-id resource-type id opts]
  (let [rt       (checked "resource type" type-pattern resource-type)
        id       (checked "resource id" id-pattern id)
        if-match (:if-match opts)
        header   (etag-header if-match)
        response (send! store {:method  :delete
                               :url     (str (tenant-base store tenant-id) "/" rt "/" id)
                               :headers (cond-> {} header (assoc "If-Match" header))})]
    (cond
      (or (success? response) (and (nil? header) (#{404 410} (:status response)))) {}
      (= 412 (:status response)) (conflict! "delete" if-match response)
      ;; A guarded delete of a resource that is not there fails its
      ;; precondition, as on every other store.
      (and header (#{404 410} (:status response))) (conflict! "delete" if-match response)
      :else (remote-error! "delete" response nil))))

;; ---------------------------------------------------------------------------
;; Bundles
;; ---------------------------------------------------------------------------

(defn- entry-target
  "[resource-type id] of a Bundle entry's request url, ids checked."
  [entry]
  (let [[rt id] (str/split (or (get-in entry [:request :url]) "") #"/" 3)]
    [(checked "resource type" type-pattern rt)
     (when id (checked "resource id" id-pattern id))]))

(defn- method-of [entry]
  (some-> (get-in entry [:request :method]) str/upper-case))

(defn- transaction-entry
  "One Bundle entry as it goes to the remote, plus its lifecycle write when it
   writes a resource. A POST becomes a guarded PUT under a minted id, for the
   same reason as `create!`; its fullUrl is kept so references to it resolve."
  [store tenant-id opts entry]
  (let [method (method-of entry)
        [rt id] (entry-target entry)]
    (case method
      ("POST" "PUT")
      (do
        (when (get-in entry [:request :ifNoneExist])
          (not-supported! "A conditional create (ifNoneExist) in a transaction"))
        (let [id     (or (when (= "PUT" method) id) (str (random-uuid)))
              if-match (when (= "PUT" method) (get-in entry [:request :ifMatch]))
              write  (lifecycle-write store tenant-id (if (= "POST" method) :create :update)
                                      rt id (:resource entry)
                                      (not-empty (cond-> (dissoc opts :if-match)
                                                   if-match (assoc :if-match if-match))))
              guard  (if (= "POST" method)
                       {"If-None-Match" "*"}
                       (some->> (get-in entry [:request :ifMatch]) etag-header (hash-map "If-Match")))]
          {:entry (cond-> (put-entry store write guard)
                    (:fullUrl entry) (assoc :fullUrl (:fullUrl entry)))
           :write write}))

      ("DELETE" "GET")
      {:entry (cond-> {:request (select-keys (:request entry) [:method :url :ifMatch])}
                (get-in entry [:request :ifMatch])
                (update-in [:request :ifMatch] etag-header))}

      (refuse! 405 "not-supported" (str "Bundle entry method not supported: " method)))))

(defn- transact-transaction!
  [store tenant-id entries opts]
  (let [planned  (mapv #(transaction-entry store tenant-id opts %) entries)
        writes   (into [] (keep :write) planned)
        ops      (into [] (comp (mapcat :tx-ops) (map #(encode-entry store %))) writes)
        bundle   (post-transaction! store tenant-id
                                    (into (mapv :entry planned) ops)
                                    "transaction" nil)
        own      (vec (take (count planned) (:entry bundle)))
        by-index (zipmap (keep-indexed (fn [i p] (when (:write p) i)) planned)
                         writes)
        landed   (into [] (keep (fn [[i w]] (assoc w :resource (get-in own [i :resource]))))
                       (sort-by key by-index))]
    (fire-after-commit! store tenant-id landed bundle)
    {:resourceType "Bundle"
     :type         "transaction-response"
     :entry        own}))

(defn- batch-entry-response
  [store tenant-id bundle-opts entry]
  (try
    (let [method   (method-of entry)
          [rt id]  (entry-target entry)
          if-match (get-in entry [:request :ifMatch])
          opts     (not-empty (cond-> (dissoc bundle-opts :if-match)
                                if-match (assoc :if-match if-match)))
          etagged  (fn [status res]
                     {:resource res
                      :response (cond-> {:status status}
                                  (get-in res [:meta :versionId])
                                  (assoc :etag (str "W/\"" (get-in res [:meta :versionId]) "\""))
                                  (= "201 Created" status)
                                  (assoc :location (str rt "/" (:id res) "/_history/"
                                                        (get-in res [:meta :versionId])))
                                  (get-in res [:meta :lastUpdated])
                                  (assoc :lastModified (str (get-in res [:meta :lastUpdated]))))})]
      (case method
        "POST"   (etagged "201 Created" (create! store tenant-id rt nil (:resource entry)
                                                 (dissoc bundle-opts :if-match)))
        "PUT"    (etagged "200 OK" (update! store tenant-id rt id (:resource entry) opts))
        "DELETE" (do (delete! store tenant-id rt id opts)
                     {:response {:status "204 No Content"}})
        "GET"    (if-let [res (get-json store "read"
                                        (str (tenant-base store tenant-id) "/" rt "/" id))]
                   (etagged "200 OK" res)
                   {:response {:status "404 Not Found"}})
        (refuse! 405 "not-supported" (str "Bundle entry method not supported: " method))))
    ;; Any failure stays this entry's own, transport failures included: batch
    ;; entries are independent. Every message this store builds names the
    ;; interaction and the status only, so it is safe as diagnostics.
    (catch Exception e
      (let [{:fhir/keys [status code]} (ex-data e)]
        {:response {:status  (str (or status 500))
                    :outcome {:resourceType "OperationOutcome"
                              :issue [{:severity    "error"
                                       :code        (or code "exception")
                                       :diagnostics (ex-message e)}]}}}))))

;; ---------------------------------------------------------------------------
;; The store
;; ---------------------------------------------------------------------------

(defn- fhir-refusal
  "The first exception in `e`'s cause chain carrying `:fhir/status`."
  [e]
  (loop [x e]
    (cond
      (nil? x)                     nil
      (:fhir/status (ex-data x))   x
      :else                        (recur (ex-cause x)))))

(defmacro ^:private traced
  "`ftrace/trace!`, rethrowing the store's own refusal rather than the
   wrapper Telemere puts around a span's error, so a caller holding the store
   directly reads `:fhir/status` off `ex-data` as it does for the mock."
  [opts & body]
  `(try
     (ftrace/trace! ~opts (do ~@body))
     (catch clojure.lang.ExceptionInfo e#
       (throw (or (fhir-refusal e#) e#)))))

(defn- traced-data [tenant-id resource-type]
  (cond-> {:tenant-id (str tenant-id)}
    resource-type (assoc :resource-type (name resource-type))))

(defrecord HttpStore []
  fp/IFHIRStore
  (create-resource [this tenant-id resource-type id resource]
    (fp/create-resource this tenant-id resource-type id resource nil))
  (create-resource [this tenant-id resource-type id resource opts]
    (traced {:id :store/create :data (traced-data tenant-id resource-type)}
                   (create! this tenant-id resource-type id resource opts)))

  (read-resource [this tenant-id resource-type id]
    (traced {:id :store/read :data (traced-data tenant-id resource-type)}
                   (get-json this "read"
                             (str (tenant-base this tenant-id) "/"
                                  (checked "resource type" type-pattern resource-type) "/"
                                  (checked "resource id" id-pattern id)))))

  (vread-resource [this tenant-id resource-type id vid]
    (traced {:id :store/vread :data (traced-data tenant-id resource-type)}
                   (get-json this "vread"
                             (str (tenant-base this tenant-id) "/"
                                  (checked "resource type" type-pattern resource-type) "/"
                                  (checked "resource id" id-pattern id) "/_history/"
                                  (checked "version id" id-pattern vid)))))

  (update-resource [this tenant-id resource-type id resource]
    (fp/update-resource this tenant-id resource-type id resource nil))
  (update-resource [this tenant-id resource-type id resource opts]
    (traced {:id :store/update :data (traced-data tenant-id resource-type)}
                   (update! this tenant-id resource-type id resource opts)))

  (delete-resource [this tenant-id resource-type id]
    (fp/delete-resource this tenant-id resource-type id nil))
  (delete-resource [this tenant-id resource-type id opts]
    (traced {:id :store/delete :data (traced-data tenant-id resource-type)}
                   (delete! this tenant-id resource-type id opts)))

  (search [this tenant-id resource-type params _search-registry]
    (traced {:id :store/search :data (traced-data tenant-id resource-type)}
                   (search-entries this tenant-id resource-type params)))

  (count-resources [this tenant-id resource-type params _search-registry]
    (traced {:id :store/count :data (traced-data tenant-id resource-type)}
                   (count-entries this tenant-id resource-type params)))

  (history [this tenant-id resource-type id]
    (traced {:id :store/history :data (traced-data tenant-id resource-type)}
                   (let [base (tenant-base this tenant-id)]
                     (versions-of
                      (bundle-pages this "history" base
                                    (str base "/" (checked "resource type" type-pattern resource-type)
                                         "/" (checked "resource id" id-pattern id) "/_history")
                                    (constantly false))))))

  (history-type [this tenant-id resource-type params]
    (traced {:id :store/history-type :data (traced-data tenant-id resource-type)}
                   (let [base  (tenant-base this tenant-id)
                         since (or (get params :_since) (get params "_since"))]
                     (versions-of
                      (bundle-pages this "history-type" base
                                    (with-query (str base "/" (checked "resource type" type-pattern resource-type)
                                                     "/_history")
                                      (when since {"_since" (str since)}))
                                    (constantly false))))))

  (transact-transaction [this tenant-id entries]
    (fp/transact-transaction this tenant-id entries nil))
  (transact-transaction [this tenant-id entries opts]
    (traced {:id :store/transact-transaction
                    :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
                   (transact-transaction! this tenant-id entries opts)))

  (transact-bundle [this tenant-id entries]
    (fp/transact-bundle this tenant-id entries nil))
  (transact-bundle [this tenant-id entries opts]
    (traced {:id :store/transact-bundle
                    :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
                   {:resourceType "Bundle"
                    :type         "batch-response"
                    :entry        (mapv #(batch-entry-response this tenant-id opts %) entries)}))

  (resource-deleted? [this tenant-id resource-type id]
    (let [base     (tenant-base this tenant-id)
          url      (str base "/" (checked "resource type" type-pattern resource-type)
                        "/" (checked "resource id" id-pattern id))
          response (send! this {:method :get :url url})]
      (case (int (:status response))
        410 true
        404 (boolean (seq (:entry (get-json this "history" (str url "/_history?_count=1")))))
        (if (success? response) false (remote-error! "read" response nil)))))

  (create-tenant [this tenant-id]
    (fp/create-tenant this tenant-id nil))
  (create-tenant [this tenant-id opts]
    (if (= :ignore (:if-exists opts))
      (fp/warmup-tenant this tenant-id)
      (not-supported! "Creating a tenant (tenants are provisioned on the remote)")))

  (delete-tenant [this tenant-id]
    (fp/delete-tenant this tenant-id nil))
  (delete-tenant [_ _ _]
    (not-supported! "Deleting a tenant"))

  (warmup-tenant [this tenant-id]
    (fp/warmup-tenant this tenant-id nil))
  (warmup-tenant [this tenant-id _opts]
    (let [response (send! this {:method :get :url (str (tenant-base this tenant-id) "/metadata")})]
      (when-not (success? response)
        (remote-error! "capabilities" response nil))
      nil))

  (current-basis [_ _]
    (not-supported! "A point-in-time basis"))
  (scan-type-as-of [_ _ _ _]
    (not-supported! "A point-in-time scan"))
  (count-as-of [_ _ _ _]
    (not-supported! "A point-in-time count")))

(defn create-http-store
  "An `IFHIRStore` over a remote FHIR REST API. Options:

   - :base-url             string template with `{tenant}`, or (fn [tenant-id] url).
                           Required.
   - :authorization        nil, an Authorization header value, or a
                           no-argument fn returning one per request.
   - :offset-param         the remote's offset search parameter (`_skip`,
                           `_offset`), or nil to page by walking `next` links.
   - :resource/schemas     compiled malli schemas, used to encode bodies.
   - :resource/lifecycle   see `fhir-store.lifecycle`.
   - :http/request         transport fn replacing the default client.
   - :connect-timeout-ms / :request-timeout-ms  for the default client."
  [{:keys [base-url] :as options}]
  (when-not (or (fn? base-url)
                (and (string? base-url) (str/includes? base-url "{tenant}")))
    (throw (ex-info ":base-url must be a fn of the tenant id or a string containing {tenant}"
                    {:fhir/status 500 :fhir/code "exception"})))
  (-> (map->HttpStore (select-keys options [:base-url :authorization :offset-param]))
      (assoc :encoders           (build-encoders (:resource/schemas options))
             :http/request       (or (:http/request options) (java-http-transport options))
             :resource/lifecycle (lc/resolve-lifecycle (:resource/lifecycle options) options))))
