(ns server.bulk-export
  "FHIR Bulk Data Access ($export) MVP: system-level export.

   Implements the async kickoff / status / cancel / file download handshake
   from the Bulk Data Access IG (STU2). This is the Phase B increment of
   docs/proposals/bulk-data-export-and-backend-services.md:

     - kickoff  GET  /:tenant-id/fhir/$export
     - status   GET  /:tenant-id/fhir/$export-status/:job-id
     - cancel   DELETE .../$export-status/:job-id
     - file     GET  /:tenant-id/fhir/$export-file/:job-id/:file-id

   Response-encoding note (critical): the status manifest is application/json
   and the NDJSON files are application/fhir+ndjson, neither of which is what
   muuntaja would negotiate. A Clojure map body carrying an explicit
   Content-Type bypasses muuntaja and then fails to stream (HTTP 500), so
   every response here is PRE-SERIALIZED to a string and returned with an
   explicit Content-Type, deliberately bypassing muuntaja (mirroring the
   default-404 handler in server.core). See the smart-configuration comment in
   server.handlers for the map-body-vs-Content-Type gotcha."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [fhir-store.protocol :as db]
            [server.bulk-job-store :as bjs]
            [server.compartment :as compartment]
            [server.handlers :as handlers]
            [server.keto :as keto]
            [taoensso.telemere :as t])
  (:import [com.fasterxml.jackson.datatype.jsr310 JavaTimeModule]
           [com.fasterxml.jackson.databind SerializationFeature]))

(def ^:private json-mapper
  "Jackson mapper that renders java.time values (e.g. meta.lastUpdated
   Instants) as ISO strings rather than epoch arrays, matching the server's
   canonical JSON encoding."
  (doto (json/object-mapper {:modules [(JavaTimeModule.)]})
    (.disable SerializationFeature/WRITE_DATES_AS_TIMESTAMPS)))

(defn- json-str [x]
  (json/write-value-as-string x json-mapper))

;; ---------------------------------------------------------------------------
;; Response helpers (all bodies pre-serialized to strings, explicit CT)
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

(defn- oo-response [status code diagnostics]
  (str-response status "application/fhir+json"
                (json-str (operation-outcome "error" code diagnostics))))

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
   unioned by the enumerator. Malformed specs (no `?`) are ignored."
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

;; ---------------------------------------------------------------------------
;; Enumeration
;; ---------------------------------------------------------------------------

(def ^:private page-size 1000)

(defn- scan
  "Page every resource of `resource-type` matching `params` via IFHIRStore
   search, stopping at the first short page. O(pages), fine for the small
   datasets this MVP targets."
  [store tenant-id resource-type params registry]
  (loop [skip 0
         acc  (transient [])]
    (let [results (db/search store tenant-id (keyword resource-type)
                             (assoc params :_count page-size :_skip skip)
                             registry)
          acc (reduce conj! acc results)]
      (if (< (count results) page-size)
        (persistent! acc)
        (recur (+ skip page-size) acc)))))

(defn- confined-scan
  "Scan `resource-type` confined to one patient's compartment via
   server.compartment/confine. A non-member type (`:passthrough`) contributes
   nothing to a patient's compartment, and a member type with no registered
   link parameter (`:deny`) fails closed to empty."
  [store tenant-id patient-id resource-type params registry]
  (let [outcome (compartment/confine "Patient" patient-id resource-type params registry)]
    (cond
      (= outcome :passthrough) []
      (= outcome :deny)        []
      :else (let [[_ p r] outcome] (scan store tenant-id resource-type p r)))))

(defn- gather-type
  "Collect all resources of `resource-type` for the export, deduped by id and
   filtered by `_since`. For :system, scans the whole type; for :patient/:group
   it unions each patient's compartment. Each of `param-maps` (the type's
   _typeFilter specs, or `[{}]`) is applied and its results unioned."
  [store tenant-id kind patient-ids resource-type registry param-maps since]
  (let [by-id (reduce
               (fn [acc params]
                 (let [rs (if (= kind :system)
                            (scan store tenant-id resource-type params registry)
                            (into [] (mapcat #(confined-scan store tenant-id % resource-type
                                                             params registry))
                                  patient-ids))]
                   (reduce (fn [m r] (assoc m (:id r) r)) acc rs)))
               {}
               param-maps)]
    (into [] (filter #(after-since? since %)) (vals by-id))))

;; ---------------------------------------------------------------------------
;; Output/error file descriptors
;; ---------------------------------------------------------------------------

(defn- output-file
  "An output descriptor with its NDJSON payload for a non-empty resource set."
  [resource-type encode resources]
  {:type    resource-type
   :file-id (str (random-uuid))
   :count   (count resources)
   :ndjson  (str (str/join "\n" (map (fn [r] (json-str (encode r))) resources)) "\n")})

(defn- error-file
  "An error descriptor: a single-line OperationOutcome NDJSON file that the
   manifest surfaces in its :error array (Bulk Data IG error output)."
  [diagnostics]
  {:type    "OperationOutcome"
   :file-id (str (random-uuid))
   :count   1
   :ndjson  (str (json-str (operation-outcome "error" "processing" diagnostics)) "\n")})

;; ---------------------------------------------------------------------------
;; Patient-set resolution
;; ---------------------------------------------------------------------------

(defn- patient-ids-in-tenant
  "All Patient logical ids in the tenant (Patient-level export subject set)."
  [store tenant-id all-registries]
  (mapv :id (scan store tenant-id "Patient" {} (get all-registries "Patient"))))

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
;; Background worker
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

(defn- run-export!
  "Background worker: resolve the subject patient set (for patient/group),
   enumerate each requested type, serialize NDJSON, and flip the job to
   :complete with :output plus an :error array of OperationOutcome files for
   any per-type failures or skipped types. A fatal error flips it to :error."
  [job-store store tenant-id job-id all-registries encoders]
  (t/trace!
   {:id :bulk/export.run
    :data {:tenant tenant-id :job-id job-id}}
   (try
     (let [job          (bjs/get-job job-store tenant-id job-id)
           {:keys [kind group-id params]} job
           encode       (partial handlers/encode-resource-by-type encoders)
           since        (parse-since params)
           type-filters (parse-type-filters params)
           types        (requested-types kind params all-registries)
           patient-ids  (case kind
                          :system  nil
                          :patient (patient-ids-in-tenant store tenant-id all-registries)
                          :group   (group-patient-ids store tenant-id group-id))
           {:keys [outputs errors]}
           (reduce
            (fn [acc rt]
              (let [registry (get all-registries rt)]
                (cond
                  (nil? registry)
                  (update acc :errors conj
                          (error-file (str "Unknown or unsupported resource type: " rt)))

                  (and (not= kind :system) (not (compartment/member? "Patient" rt)))
                  (update acc :errors conj
                          (error-file (str rt " is not a Patient-compartment member; "
                                           "skipped for " (name kind) "-level export.")))

                  :else
                  (try
                    (let [resources (gather-type store tenant-id kind patient-ids rt registry
                                                 (filters-for type-filters rt) since)]
                      (if (seq resources)
                        (update acc :outputs conj (output-file rt encode resources))
                        acc))
                    (catch Throwable e
                      (update acc :errors conj
                              (error-file (str "Failed to export " rt ": "
                                               (or (.getMessage e) (str e))))))))))
            {:outputs [] :errors []}
            types)
           files (into {} (map (juxt :file-id :ndjson)) (concat outputs errors))]
       (bjs/update-job! job-store tenant-id job-id
                        (fn [job]
                          (cond
                            (nil? job) nil
                            (= :cancelled (:status job)) job
                            :else (assoc job
                                         :status :complete
                                         :output (mapv #(select-keys % [:type :file-id :count]) outputs)
                                         :error  (mapv #(select-keys % [:type :file-id :count]) errors)
                                         :files  files)))))
     (catch Throwable e
       (bjs/update-job! job-store tenant-id job-id
                        (fn [job]
                          (when job
                            (assoc job
                                   :status :error
                                   :error [{:type "OperationOutcome"
                                            :diagnostics (or (.getMessage e) "export failed")}]))))
       (t/error! {:id :bulk/export.failed
                  :data {:tenant tenant-id :job-id job-id}}
                 e)))))

;; ---------------------------------------------------------------------------
;; Manifest builder
;; ---------------------------------------------------------------------------

(defn build-manifest
  "Build the completed-export status manifest for `job`. Output and error file
   URLs are made absolute from the polling request so they are reachable by the
   client that received them. requiresAccessToken is true: the file routes are
   gated on the system Keto tuple (see server.routing)."
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
;; Handlers
;; ---------------------------------------------------------------------------

(defn- start-export!
  "Validate _outputFormat, mint an :in-progress job of `kind` (optionally
   pinned to `group-id`), spawn a virtual thread to enumerate and serialize,
   and return 202 with an absolute Content-Location status URL and no body.

   Fronted by server.auth/wrap-require-auth in routing so a tokenless request
   returns 401 (not the Keto 403)."
  [req kind group-id]
  (let [tenant-id      (-> req :path-params :tenant-id)
        store          (:fhir/store req)
        job-store      (:fhir/bulk-job-store req)
        all-registries (:fhir/all-registries req)
        encoders       (:fhir/resource-encoders req)
        params         (merge (or (:form-params req) {}) (or (:query-params req) {}))
        output-format  (get-param params "_outputFormat")]
    (if-not (valid-output-format? output-format)
      (oo-response 400 "invalid"
                   (str "Unsupported _outputFormat: '" output-format
                        "'. Supported: application/fhir+ndjson."))
      (let [job-id (str (random-uuid))
            job    {:id               job-id
                    :tenant           tenant-id
                    :kind             kind
                    :group-id         group-id
                    :params           params
                    :status           :in-progress
                    :transaction-time (str (java.time.Instant/now))
                    :request-url      (request-url req)
                    :output           []
                    :error            []
                    :files            {}}]
        (bjs/put-job! job-store tenant-id job)
        (Thread/startVirtualThread
         ^Runnable (fn []
                     (run-export! job-store store tenant-id job-id
                                  all-registries encoders)))
        (str-response 202 "application/json"
                      {"Content-Location" (status-url req tenant-id job-id)}
                      "")))))

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
   to each Patient's compartment (server.compartment/confine). Authorized
   against the 'system' Keto object (see kickoff)."
  [req]
  (or (authorize-system req)
      (start-export! req :patient nil)))

(defn group-export
  "GET /:tenant-id/fhir/Group/:id/$export — group-level export kickoff. Reads
   the Group (404 when absent), resolves its member.entity Patient references,
   and confines each requested type to the union of those Patients'
   compartments. Authorized against the 'system' Keto object (see kickoff); the
   authorization check runs before the Group read so an unauthorized caller
   cannot probe Group existence."
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

(defn status
  "GET /:tenant-id/fhir/$export-status/:job-id — poll job status.

   :in-progress -> 202 with X-Progress + Retry-After.
   :complete    -> 200 application/json manifest.
   :error       -> 500 OperationOutcome.
   :cancelled / unknown -> 404."
  [req]
  (let [tenant-id (-> req :path-params :tenant-id)
        job-id    (-> req :path-params :job-id)
        job-store (:fhir/bulk-job-store req)
        job       (bjs/get-job job-store tenant-id job-id)]
    (case (:status job)
      :in-progress
      (str-response 202 "application/json"
                    {"X-Progress"  "in-progress, building files"
                     "Retry-After" "1"}
                    "")

      :complete
      (str-response 200 "application/json" (json-str (build-manifest req job)))

      :error
      (oo-response 500 "exception" "Export failed")

      ;; nil (unknown) and :cancelled both surface as 404: a cancelled job's
      ;; status endpoint no longer exists (Bulk Data IG).
      (oo-response 404 "not-found"
                   (str "Export job " job-id " not found")))))

(defn cancel
  "DELETE /:tenant-id/fhir/$export-status/:job-id — cancel a job (202)."
  [req]
  (let [tenant-id (-> req :path-params :tenant-id)
        job-id    (-> req :path-params :job-id)
        job-store (:fhir/bulk-job-store req)
        job       (bjs/get-job job-store tenant-id job-id)]
    (if job
      (do
        (bjs/update-job! job-store tenant-id job-id
                         (fn [j] (when j (assoc j :status :cancelled))))
        (str-response 202 "application/json"
                      (json-str (operation-outcome
                                 "information" "informational"
                                 (str "Export job " job-id " cancelled")))))
      (oo-response 404 "not-found"
                   (str "Export job " job-id " not found")))))

(defn file
  "GET /:tenant-id/fhir/$export-file/:job-id/:file-id — download one NDJSON
   output (or error) file. The :public? route yields 401 for a tokenless
   request (wrap-require-auth) and this handler then gates on the system Keto
   tuple (manifest requiresAccessToken is true): a token without the system
   read tuple -> 403. The body is pre-serialized NDJSON with an explicit
   Content-Type, bypassing muuntaja."
  [req]
  (or (authorize-system req)
      (let [tenant-id (-> req :path-params :tenant-id)
            job-id    (-> req :path-params :job-id)
            file-id   (-> req :path-params :file-id)
            job-store (:fhir/bulk-job-store req)
            job       (bjs/get-job job-store tenant-id job-id)
            ndjson    (get-in job [:files file-id])]
        (if (and ndjson (= :complete (:status job)))
          (str-response 200 "application/fhir+ndjson" ndjson)
          (oo-response 404 "not-found"
                       (str "Export file " file-id " not found for job " job-id))))))
