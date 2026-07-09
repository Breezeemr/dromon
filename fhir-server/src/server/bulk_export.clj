(ns server.bulk-export
  "FHIR Bulk Data Access ($export) MVP: system-, patient- and group-level export.

   Implements the async kickoff / status / cancel / file download handshake
   from the Bulk Data Access IG (STU2). This is the Phase B increment of
   docs/proposals/bulk-data-export-and-backend-services.md.

     - kickoff  GET  /:tenant-id/fhir/$export
     - status   GET  /:tenant-id/fhir/$export-status/:job-id
     - cancel   DELETE .../$export-status/:job-id
     - file     GET  /:tenant-id/fhir/$export-file/:job-id/:file-id

   Memory/IO model (lazy stream-at-download): kickoff produces NO resource
   bytes and touches NO disk. It pins a point-in-time store basis
   (db/current-basis), builds the tiny status manifest (which types, a per-type
   count-as-of, a transactionTime derived from the basis), pre-serializes it,
   and stores only that metadata in the job. The actual NDJSON is produced
   lazily at DOWNLOAD time: $export-file returns a Ring StreamableResponseBody
   whose write-body-to-stream scans the type AS OF the pinned basis
   (db/scan-type-as-of) and writes NDJSON lines straight to the socket,
   flushing per batch so Jetty applies TCP backpressure and peak memory stays
   bounded to one store page regardless of type size. No background worker, no
   temp files.

   Response-encoding note (critical): the status manifest is application/json
   and the NDJSON stream is application/fhir+ndjson, neither of which is what
   muuntaja would negotiate. A Clojure map body carrying an explicit
   Content-Type bypasses muuntaja and then fails to stream (HTTP 500), so the
   manifest is PRE-SERIALIZED to a string and the NDJSON body is a
   StreamableResponseBody (a reify, not a map/coll) returned with an explicit
   Content-Type; muuntaja leaves both untouched (it only encodes map/coll
   bodies). See the smart-configuration comment in server.handlers for the
   map-body-vs-Content-Type gotcha."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [ring.core.protocols :as ring-protocols]
            [fhir-store.protocol :as db]
            [server.bulk-job-store :as bjs]
            [server.compartment :as compartment]
            [server.handlers :as handlers]
            [server.keto :as keto]
            [taoensso.telemere :as t])
  (:import [com.fasterxml.jackson.datatype.jsr310 JavaTimeModule]
           [com.fasterxml.jackson.databind SerializationFeature]
           [java.io OutputStream OutputStreamWriter BufferedWriter Writer]
           [java.nio.charset StandardCharsets]))

(def ^:private json-mapper
  "Jackson mapper that renders java.time values (e.g. meta.lastUpdated
   Instants) as ISO strings rather than epoch arrays, matching the server's
   canonical JSON encoding."
  (doto (json/object-mapper {:modules [(JavaTimeModule.)]})
    (.disable SerializationFeature/WRITE_DATES_AS_TIMESTAMPS)))

(defn- json-str [x]
  (json/write-value-as-string x json-mapper))

;; ---------------------------------------------------------------------------
;; Response helpers (all non-stream bodies pre-serialized to strings, explicit CT)
;; ---------------------------------------------------------------------------

(defn- str-response
  ([status content-type body-str]
   (str-response status content-type {} body-str))
  ([status content-type headers body-str]
   {:status status
    :headers (assoc headers "Content-Type" content-type)
    :body body-str}))

(defn- operation-outcome [severity code diagnostics]
  {:resourceType "OperationOutcome"
   :issue [{:severity severity :code code :diagnostics diagnostics}]})

(defn- oo-response
  ([status code diagnostics] (oo-response status code diagnostics {}))
  ([status code diagnostics headers]
   (str-response status "application/fhir+json" headers
                 (json-str (operation-outcome "error" code diagnostics)))))

;; ---------------------------------------------------------------------------
;; Authorization
;;
;; The kickoff ($export / Patient/$export / Group/[id]/$export) and file
;; ($export-file) routes are `:public?` so a tokenless request yields 401 from
;; server.auth/wrap-require-auth rather than the Keto 403 that a missing subject
;; would otherwise produce. Because `:public?` also bypasses the Keto
;; middleware, an authenticated caller could otherwise start or read a
;; full-tenant export with any valid token; these handlers therefore replicate
;; the middleware's "system" object check inline: no token -> 401 (upstream),
;; token without the system read tuple -> 403, token with it -> proceed.
;; ---------------------------------------------------------------------------

(defn- system-authorized?
  "Whether the request's authenticated subject holds the 'system' read tuple.
   Overridable per-request via :fhir/system-authorized? (a
   (fn [subject-id] -> boolean), used by tests); otherwise performs a live
   server.keto check against the 'system' object using the injected
   :fhir/keto-url (server.core/wrap-keto-url)."
  [req]
  (let [subject-id (get-in req [:identity :sub])]
    (if-let [pred (:fhir/system-authorized? req)]
      (boolean (pred subject-id))
      (keto/system-read-allowed? (:fhir/keto-url req) subject-id))))

(defn- authorize-system
  "Gate a :public? bulk route on the 'system' Keto read tuple. Returns nil to
   proceed, or a 403 OperationOutcome response when the subject is not
   authorized. Tokenless requests never reach here (wrap-require-auth -> 401)."
  [req]
  (when-not (system-authorized? req)
    (oo-response 403 "forbidden"
                 (str "Subject " (get-in req [:identity :sub])
                      " is not authorized for bulk export against the system object."))))

;; ---------------------------------------------------------------------------
;; URL helpers
;; ---------------------------------------------------------------------------

(defn- forwarded-scheme [req]
  (or (get-in req [:headers "x-forwarded-proto"])
      (some-> (:scheme req) name)
      "https"))

(defn- forwarded-host [req]
  (or (get-in req [:headers "x-forwarded-host"])
      (get-in req [:headers "host"])
      "localhost"))

(defn- absolute-url
  "Build an absolute URL for `path` from the request's scheme/host, honoring
   X-Forwarded-* so poll/download URLs are reachable behind a proxy."
  [req path]
  (str (forwarded-scheme req) "://" (forwarded-host req) path))

(defn- status-url [req tenant-id job-id]
  (absolute-url req (str "/" tenant-id "/fhir/$export-status/" job-id)))

(defn- file-url [req tenant-id job-id file-id]
  (absolute-url req (str "/" tenant-id "/fhir/$export-file/" job-id "/" file-id)))

(defn- request-url [req]
  (absolute-url req (str (:uri req)
                         (when-let [q (not-empty (:query-string req))]
                           (str "?" q)))))

;; ---------------------------------------------------------------------------
;; _outputFormat validation
;; ---------------------------------------------------------------------------

(def ^:private valid-output-formats
  "NDJSON output-format spellings accepted by the Bulk Data IG."
  #{"application/fhir+ndjson" "application/ndjson" "ndjson"})

(defn valid-output-format?
  "True when `fmt` is nil (default) or one of the NDJSON spellings."
  [fmt]
  (or (nil? fmt) (contains? valid-output-formats fmt)))

;; ---------------------------------------------------------------------------
;; Parameter parsing (_type, _since, _typeFilter)
;; ---------------------------------------------------------------------------

(defn- get-param
  "Read a bulk parameter under either its string or keyword spelling
   (query-params arrive string-keyed; form-params may be keyword-keyed)."
  [params k]
  (or (get params k) (get params (keyword k))))

(defn- get-param-values
  "Read a possibly-repeated parameter as a vector of strings (a single value,
   a Ring-collected vector, or empty when absent)."
  [params k]
  (let [v (get-param params k)]
    (cond
      (nil? v)        []
      (sequential? v) (vec v)
      :else           [v])))

(defn- parse-query-params
  "Parse an inner `param=value&param2=value2` query string (as carried inside a
   _typeFilter spec) into a string-keyed search-parameter map."
  [query]
  (into {}
        (comp (remove str/blank?)
              (map (fn [pair]
                     (let [[k v] (str/split pair #"=" 2)]
                       [k (or v "")]))))
        (str/split (or query "") #"&")))

(defn- parse-type-filters
  "Parse `_typeFilter` values (each `ResourceType?param=value&...`) into
   {resource-type [search-param-map ...]}. Multiple filters for one type are
   unioned while streaming. Malformed specs (no `?`) are ignored."
  [params]
  (reduce
   (fn [acc raw]
     (let [spec (str/trim (str raw))
           idx  (str/index-of spec "?")]
       (if idx
         (update acc (subs spec 0 idx) (fnil conj [])
                 (parse-query-params (subs spec (inc idx))))
         acc)))
   {}
   (get-param-values params "_typeFilter")))

(defn- filters-for
  "Search-param maps to run for `resource-type`: the type's _typeFilter specs,
   or a single empty map (unfiltered) when none apply."
  [type-filters resource-type]
  (or (seq (get type-filters resource-type)) [{}]))

(defn- parse-since
  "Parse the `_since` instant, or nil when absent/unparseable (degrade
   gracefully: an unparseable _since disables the filter rather than failing)."
  [params]
  (when-let [s (get-param params "_since")]
    (try (java.time.Instant/parse s) (catch Exception _ nil))))

(defn- after-since?
  "True when `resource` was last updated at/after `since`. _since is applied as
   an in-memory post-scan filter on meta.lastUpdated so it works uniformly
   across backends regardless of whether they support _lastUpdated search. A
   resource with no lastUpdated is included (cannot prove it is older)."
  [^java.time.Instant since resource]
  (or (nil? since)
      (let [lu (get-in resource [:meta :lastUpdated])]
        (cond
          (nil? lu)                       true
          (instance? java.time.Instant lu) (not (.isBefore ^java.time.Instant lu since))
          :else (try (not (.isBefore (java.time.Instant/parse (str lu)) since))
                     (catch Exception _ true))))))

(defn- matches-type-filter?
  "Best-effort in-memory match of a single _typeFilter search-param map against
   `resource`: every non-underscore string param must equal the resource's
   top-level field (stringwise). An empty map matches everything. This is a
   pragmatic subset of FHIR search semantics (there is no general search
   evaluator over an already-read resource); underscore params (_id etc.) and
   parameters that do not map to a top-level element are ignored."
  [param-map resource]
  (every? (fn [[k v]]
            (if (and (string? k) (not (str/starts-with? k "_")))
              (= (str (get resource (keyword k))) v)
              true))
          param-map))

(defn- matches-any-type-filter?
  "True when `resource` matches at least one of `param-maps` (the type's
   _typeFilter specs unioned, or `[{}]` for unfiltered)."
  [param-maps resource]
  (boolean (some #(matches-type-filter? % resource) param-maps)))

;; ---------------------------------------------------------------------------
;; Snapshot enumeration (as-of the pinned basis, filtered while streaming)
;;
;; scan-type-as-of streams every live resource of a type as of the basis; the
;; export layer applies compartment confinement (patient/group), _typeFilter,
;; _since and id-dedup here. Everything is a reduce over the store's reducible
;; (never `seq`/`doseq`), so an IReduceInit backend (xtdb2) and a lazy-seq
;; backend (mock) both work and only one page is held at a time.
;; ---------------------------------------------------------------------------

(defn- export-filter-xf
  "A STATEFUL transducer for one export type: keeps only resources that pass
   compartment confinement (for :patient/:group), match at least one
   _typeFilter spec, satisfy _since, and have a not-yet-seen id (global dedup
   within this stream). Create a fresh one per stream/collect; it closes over a
   mutable seen-id set."
  [kind owner-ids resource-type registry param-maps since]
  (let [seen (java.util.HashSet.)
        in-compartment? (if (= kind :system)
                          (constantly true)
                          (fn [r] (compartment/resource-in-any-compartment?
                                   "Patient" owner-ids resource-type r registry)))]
    (filter (fn [r]
              (let [id (:id r)]
                (and (in-compartment? r)
                     (matches-any-type-filter? param-maps r)
                     (after-since? since r)
                     (not (.contains seen id))
                     (do (.add seen id) true)))))))

(defn- scan-type
  "Reducible of live resources of `resource-type` as of `basis`."
  [store tenant-id resource-type basis]
  (db/scan-type-as-of store tenant-id (keyword resource-type) basis))

(defn- collect-type
  "Fully realize (into a vector) the export's resources for `resource-type` as
   of `basis`, applying compartment confinement, _typeFilter, _since and
   id-dedup. Retained for the enumeration unit tests; the download path streams
   the same set via stream-output! and never materializes the whole type."
  [store tenant-id basis kind owner-ids resource-type registry param-maps since]
  (into []
        (export-filter-xf kind owner-ids resource-type registry param-maps since)
        (scan-type store tenant-id resource-type basis)))

(defn- confined-count
  "Exact number of resources `resource-type` contributes to this export as of
   `basis`: reduce the snapshot through the SAME export filter the stream uses
   (compartment confinement + _typeFilter + _since + id-dedup) and count. Reads
   resources but produces no bytes; used for patient/group manifest counts so
   the count equals the streamed line count and empty-in-compartment types are
   omitted from :output (the unfiltered count-as-of would overcount a
   compartment and list empty files that the Bulk Data validator rejects)."
  [store tenant-id basis kind owner-ids resource-type registry param-maps since]
  (transduce (export-filter-xf kind owner-ids resource-type registry param-maps since)
             (completing (fn [n _] (inc n)))
             0
             (scan-type store tenant-id resource-type basis)))

;; ---------------------------------------------------------------------------
;; NDJSON streaming (write-body-to-stream)
;; ---------------------------------------------------------------------------

(def ^:private flush-every
  "Flush the socket writer every this-many NDJSON lines so Jetty pushes bytes
   (and applies TCP backpressure) without paying a syscall per line."
  128)

(defn- writing-rf
  "Reducing function that writes each resource as one NDJSON line to `w`,
   flushing every `flush-every` lines and once at completion. The accumulator
   is the running line count."
  [^Writer w encode]
  (fn
    ([n] (.flush w) n)
    ([n resource]
     (.write w ^String (json-str (encode resource)))
     (.write w "\n")
     (let [n' (inc n)]
       (when (zero? (rem n' flush-every)) (.flush w))
       n'))))

(defn- writer-on
  "A UTF-8 BufferedWriter over the response OutputStream. Not closed here (that
   would close the socket); the caller flushes via the reducing fn's completion
   arity, and Jetty owns the stream lifecycle."
  ^Writer [^OutputStream out]
  (BufferedWriter. (OutputStreamWriter. out StandardCharsets/UTF_8)))

(defn- stream-output!
  "Stream `resource-type`'s NDJSON to `out` lazily: reduce over the as-of
   snapshot through the export filter transducer, writing and flushing per
   batch. Peak memory is one store page: scan-type-as-of pulls the next page
   only after the reducing fn returns, and a backpressured socket pauses that
   pull."
  [store tenant-id basis kind owner-ids resource-type registry param-maps since encode
   ^OutputStream out]
  (let [w (writer-on out)]
    (transduce (export-filter-xf kind owner-ids resource-type registry param-maps since)
               (writing-rf w encode)
               0
               (scan-type store tenant-id resource-type basis))))

(defn- stream-error!
  "Stream a single-line OperationOutcome NDJSON error to `out` (the manifest
   :error entries carry per-type skip/failure diagnostics computed at kickoff)."
  [diagnostics ^OutputStream out]
  (let [w (writer-on out)]
    (.write w ^String (json-str (operation-outcome "error" "processing" diagnostics)))
    (.write w "\n")
    (.flush w)))

;; ---------------------------------------------------------------------------
;; Subject-set resolution (patient/group owner ids)
;; ---------------------------------------------------------------------------

(defn- patient-ids-in-tenant
  "All Patient logical ids in the tenant as of `basis` (the Patient-level export
   subject set), as a set for O(1) membership while streaming member types."
  [store tenant-id basis]
  (into #{} (map :id) (scan-type store tenant-id "Patient" basis)))

(defn- group-patient-ids
  "Resolve the Patient logical ids referenced by `Group.member.entity`. Returns
   [] when the Group is missing or references no Patients."
  [store tenant-id group-id]
  (let [group (try (db/read-resource store tenant-id :Group group-id)
                   (catch Throwable _ nil))]
    (into []
          (comp (keep #(get-in % [:entity :reference]))
                (filter #(str/starts-with? % "Patient/"))
                (map #(subs % (count "Patient/")))
                (distinct))
          (:member group))))

;; ---------------------------------------------------------------------------
;; Type set + manifest
;; ---------------------------------------------------------------------------

(defn- requested-types
  "Resolve which resource types this export enumerates. `_type` narrows the
   set; otherwise :system exports every registered type and :patient/:group
   export every registered Patient-compartment member type (including Patient)."
  [kind params all-registries]
  (let [type-param (get-param params "_type")]
    (if (not-empty type-param)
      (vec (str/split type-param #","))
      (if (= kind :system)
        (vec (keys all-registries))
        (into [] (filter #(compartment/member? "Patient" %)) (keys all-registries))))))

(defn- build-job-files
  "Compute the export's output/error file descriptors at kickoff WITHOUT
   producing any resource bytes. For each requested type: an unknown type or a
   non-member type on a patient/group export becomes an :error descriptor
   (OperationOutcome, streamed on demand); every other type gets an :output
   descriptor ONLY when it contributes at least one resource (empty types are
   omitted, so the Bulk Data validator never downloads an empty file). The count
   is the cheap unfiltered count-as-of for :system (exact there, no
   confinement), and the exact confinement/_typeFilter/_since-filtered count for
   :patient/:group (equal to what the stream will emit). A type with zero
   resources tenant-wide is skipped before any confined scan. Returns
   {:outputs [{:type :file-id :count}...] :errors [{:type :file-id :diagnostics}...]}."
  [store tenant-id basis kind owner-ids all-registries types since type-filters]
  (reduce
   (fn [acc rt]
     (let [registry (get all-registries rt)]
       (cond
         (nil? registry)
         (update acc :errors conj
                 {:type "OperationOutcome" :file-id (str (random-uuid))
                  :diagnostics (str "Unknown or unsupported resource type: " rt)})

         (and (not= kind :system) (not (compartment/member? "Patient" rt)))
         (update acc :errors conj
                 {:type "OperationOutcome" :file-id (str (random-uuid))
                  :diagnostics (str rt " is not a Patient-compartment member; "
                                    "skipped for " (name kind) "-level export.")})

         :else
         (let [tenant-cnt (try (db/count-as-of store tenant-id (keyword rt) basis)
                               (catch Throwable _ 0))]
           (if (zero? (long (or tenant-cnt 0)))
             ;; Nothing of this type exists as of the basis (the compartment is a
             ;; subset, so it is empty too): omit it without a confined scan.
             acc
             (let [cnt (if (= kind :system)
                         tenant-cnt
                         (confined-count store tenant-id basis kind owner-ids rt
                                         registry (filters-for type-filters rt) since))]
               (if (pos? (long (or cnt 0)))
                 (update acc :outputs conj
                         {:type rt :file-id (str (random-uuid)) :count cnt})
                 acc)))))))
   {:outputs [] :errors []}
   types))

(defn- file-descriptors
  "Index the output/error entries by file-id into the per-file stream
   descriptors the $export-file handler resolves ({file-id {:kind :type
   :diagnostics}})."
  [{:keys [outputs errors]}]
  (into {}
        (concat
         (map (fn [o] [(:file-id o) {:kind :output :type (:type o)}]) outputs)
         (map (fn [e] [(:file-id e) {:kind :error
                                     :type (:type e)
                                     :diagnostics (:diagnostics e)}]) errors))))

(defn build-manifest
  "Build the completed-export status manifest map for `job`. Output and error
   file URLs are made absolute from `req` so they are reachable by the client
   that received them. requiresAccessToken is true: the file routes are gated on
   the system Keto tuple (see server.routing)."
  [req job]
  (let [tenant-id (:tenant job)
        job-id    (:id job)
        ->url     (fn [o] (file-url req tenant-id job-id (:file-id o)))]
    {:transactionTime     (:transaction-time job)
     :request             (:request-url job)
     :requiresAccessToken true
     :output              (mapv (fn [o] {:type  (:type o)
                                         :count (:count o)
                                         :url   (->url o)})
                                (:output job))
     :error               (mapv (fn [o] {:type (:type o) :url (->url o)})
                                (:error job))}))

;; ---------------------------------------------------------------------------
;; TTL eviction (lazy sweep on each bulk request)
;; ---------------------------------------------------------------------------

(defn- sweep-expired!
  "Evict terminal (complete/error/cancelled) jobs older than the configured
   ttl-ms. Jobs are now tiny metadata (no files), so eviction just drops the
   record. Runs lazily at the head of each bulk request."
  [job-store]
  (let [ttl (long (:ttl-ms (bjs/config job-store)))
        now (System/currentTimeMillis)]
    (doseq [job (bjs/all-jobs job-store)]
      (when (and (#{:complete :error :cancelled} (:status job))
                 (> (- now (long (or (:finished-at job) (:created-at job) now))) ttl))
        (bjs/remove-job! job-store (:tenant job) (:id job))))))

;; ---------------------------------------------------------------------------
;; Kickoff
;; ---------------------------------------------------------------------------

(defn- start-export!
  "Validate _outputFormat, enforce the concurrent-stream cap, pin the store
   basis, compute the manifest (types + per-type count-as-of, NO bytes, NO
   disk), pre-serialize it, store the job metadata, and return 202 with an
   absolute Content-Location status URL and no body.

   Fronted by server.auth/wrap-require-auth in routing so a tokenless request
   returns 401 (not the Keto 403). When the number of active download streams
   is at or above max-concurrent-streams, returns 429 with a Retry-After."
  [req kind group-id]
  (let [tenant-id      (-> req :path-params :tenant-id)
        store          (:fhir/store req)
        job-store      (:fhir/bulk-job-store req)
        all-registries (:fhir/all-registries req)
        params         (merge (or (:form-params req) {}) (or (:query-params req) {}))
        output-format  (get-param params "_outputFormat")
        cfg            (bjs/config job-store)
        max-streams    (long (:max-concurrent-streams cfg))]
    (sweep-expired! job-store)
    (cond
      (not (valid-output-format? output-format))
      (oo-response 400 "invalid"
                   (str "Unsupported _outputFormat: '" output-format
                        "'. Supported: application/fhir+ndjson."))

      (>= (bjs/active-stream-count job-store) max-streams)
      (oo-response 429 "throttled"
                   (str "Too many concurrent export streams (limit " max-streams
                        "). Retry after the indicated delay.")
                   {"Retry-After" "120"})

      :else
      (t/trace!
       {:id :bulk/export.kickoff
        :data {:tenant tenant-id :kind kind}}
       (let [job-id       (str (random-uuid))
             basis        (db/current-basis store tenant-id)
             txn-time     (str (:system-time basis))
             owner-ids    (case kind
                            :system  nil
                            :patient (patient-ids-in-tenant store tenant-id basis)
                            :group   (set (group-patient-ids store tenant-id group-id)))
             types        (requested-types kind params all-registries)
             since        (parse-since params)
             type-filters (parse-type-filters params)
             files        (build-job-files store tenant-id basis kind owner-ids
                                           all-registries types since type-filters)
             now          (System/currentTimeMillis)
             base-job    {:id               job-id
                          :tenant           tenant-id
                          :kind             kind
                          :group-id         group-id
                          :params           params
                          :basis            basis
                          :owner-ids        owner-ids
                          :status           :complete
                          :transaction-time txn-time
                          :request-url      (request-url req)
                          :created-at       now
                          :finished-at      now
                          :output           (:outputs files)
                          :error            (:errors files)
                          :files            (file-descriptors files)}
             job         (assoc base-job :manifest (json-str (build-manifest req base-job)))]
         (bjs/put-job! job-store tenant-id job)
         (str-response 202 "application/json"
                       {"Content-Location" (status-url req tenant-id job-id)}
                       ""))))))

(defn kickoff
  "GET /:tenant-id/fhir/$export — system-level export kickoff. Authorized
   against the 'system' Keto object (the :public? route bypasses the Keto
   middleware); an authenticated caller lacking the system read tuple -> 403."
  [req]
  (or (authorize-system req)
      (start-export! req :system nil)))

(defn patient-export
  "GET /:tenant-id/fhir/Patient/$export — patient-level export kickoff. The
   subject set is every Patient in the tenant; each requested type is confined
   to the union of those Patients' compartments while streaming. Authorized
   against the 'system' Keto object (see kickoff)."
  [req]
  (or (authorize-system req)
      (start-export! req :patient nil)))

(defn group-export
  "GET /:tenant-id/fhir/Group/:id/$export — group-level export kickoff. Reads
   the Group (404 when absent), resolves its member.entity Patient references,
   and confines each requested type to the union of those Patients'
   compartments while streaming. Authorized against the 'system' Keto object
   (see kickoff); the authorization check runs before the Group read so an
   unauthorized caller cannot probe Group existence."
  [req]
  (or (authorize-system req)
      (let [tenant-id (-> req :path-params :tenant-id)
            group-id  (-> req :path-params :id)
            store     (:fhir/store req)
            group     (try (db/read-resource store tenant-id :Group group-id)
                           (catch Throwable _ nil))]
        (if (nil? group)
          (oo-response 404 "not-found" (str "Group/" group-id " not found"))
          (start-export! req :group group-id)))))

;; ---------------------------------------------------------------------------
;; Status / cancel
;; ---------------------------------------------------------------------------

(defn status
  "GET /:tenant-id/fhir/$export-status/:job-id — poll job status.

   :complete    -> 200 application/json manifest (pre-serialized at kickoff).
   :in-progress -> 202 with X-Progress + Retry-After.
   :error       -> 500 OperationOutcome.
   :cancelled / unknown -> 404."
  [req]
  (let [tenant-id (-> req :path-params :tenant-id)
        job-id    (-> req :path-params :job-id)
        job-store (:fhir/bulk-job-store req)
        _         (sweep-expired! job-store)
        job       (bjs/get-job job-store tenant-id job-id)]
    (case (:status job)
      :complete
      (str-response 200 "application/json" (:manifest job))

      :in-progress
      (str-response 202 "application/json"
                    {"X-Progress"  "in-progress, building manifest"
                     "Retry-After" "1"}
                    "")

      :error
      (oo-response 500 "exception" "Export failed")

      ;; nil (unknown) and :cancelled both surface as 404: a cancelled job's
      ;; status endpoint no longer exists (Bulk Data IG).
      (oo-response 404 "not-found"
                   (str "Export job " job-id " not found")))))

(defn cancel
  "DELETE /:tenant-id/fhir/$export-status/:job-id — cancel a job (202). There is
   no on-disk content to reclaim; the job metadata is flipped to :cancelled and
   TTL-swept later."
  [req]
  (let [tenant-id (-> req :path-params :tenant-id)
        job-id    (-> req :path-params :job-id)
        job-store (:fhir/bulk-job-store req)
        _         (sweep-expired! job-store)
        job       (bjs/get-job job-store tenant-id job-id)]
    (if job
      (do
        (bjs/update-job! job-store tenant-id job-id
                         (fn [j] (when j (assoc j :status :cancelled
                                               :finished-at (System/currentTimeMillis)))))
        (str-response 202 "application/json"
                      (json-str (operation-outcome
                                 "information" "informational"
                                 (str "Export job " job-id " cancelled")))))
      (oo-response 404 "not-found"
                   (str "Export job " job-id " not found")))))

;; ---------------------------------------------------------------------------
;; File download (lazy NDJSON stream at download time)
;; ---------------------------------------------------------------------------

(defn- output-stream-body
  "A Ring StreamableResponseBody that lazily streams `descriptor`'s NDJSON as of
   the job's pinned basis, releasing a concurrency slot when done. An :output
   descriptor scans the type and applies compartment/_typeFilter/_since/dedup;
   an :error descriptor writes a single OperationOutcome line."
  [job-store job store all-registries encoders descriptor]
  (let [{:keys [kind basis owner-ids params]} job
        {:keys [type diagnostics]} descriptor
        encode       (partial handlers/encode-resource-by-type encoders)
        since        (parse-since params)
        type-filters (parse-type-filters params)
        registry     (get all-registries type)]
    (reify ring-protocols/StreamableResponseBody
      (write-body-to-stream [_ _response out]
        (try
          (t/trace!
           {:id :bulk/export.stream
            :data {:tenant (:tenant job) :job-id (:id job) :type type}}
           (if (= :error (:kind descriptor))
             (stream-error! diagnostics out)
             (stream-output! store (:tenant job) basis kind owner-ids type registry
                             (filters-for type-filters type) since encode out)))
          (finally
            (bjs/release-stream! job-store)))))))

(defn file
  "GET /:tenant-id/fhir/$export-file/:job-id/:file-id — download one NDJSON
   output (or error) file by STREAMING it from the store as of the job's pinned
   basis. The :public? route yields 401 for a tokenless request
   (wrap-require-auth) and this handler then gates on the system Keto tuple
   (manifest requiresAccessToken is true): a token without the system read tuple
   -> 403. Acquires one of max-concurrent-streams slots (429 when saturated) and
   returns a StreamableResponseBody with an explicit Content-Type so muuntaja
   leaves it untouched."
  [req]
  (or (authorize-system req)
      (let [tenant-id      (-> req :path-params :tenant-id)
            job-id         (-> req :path-params :job-id)
            file-id        (-> req :path-params :file-id)
            job-store      (:fhir/bulk-job-store req)
            store          (:fhir/store req)
            all-registries (:fhir/all-registries req)
            encoders       (:fhir/resource-encoders req)
            _              (sweep-expired! job-store)
            job            (bjs/get-job job-store tenant-id job-id)
            descriptor     (get-in job [:files file-id])
            max-streams    (long (:max-concurrent-streams (bjs/config job-store)))]
        (cond
          (or (nil? descriptor) (not= :complete (:status job)))
          (oo-response 404 "not-found"
                       (str "Export file " file-id " not found for job " job-id))

          (not (bjs/acquire-stream! job-store max-streams))
          (oo-response 429 "throttled"
                       (str "Too many concurrent export streams (limit " max-streams
                            "). Retry after the indicated delay.")
                       {"Retry-After" "120"})

          :else
          {:status  200
           :headers {"Content-Type" "application/fhir+ndjson"}
           :body    (output-stream-body job-store job store all-registries encoders descriptor)}))))
