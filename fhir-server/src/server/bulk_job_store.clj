(ns server.bulk-job-store
  "Registry for FHIR Bulk Data Access ($export) jobs.

   The store is
   `{:jobs           <JobStore>
     :active-streams (atom {stream-token -> start-epoch-ms})
     :config         {...}}`.

   Job records live behind the [[JobStore]] protocol, keyed by
   `[tenant-id job-id]`. [[MemoryJobStore]] (the default) keeps them in one
   process, which is correct only while a single replica serves the tenant: a
   status poll, file download or DELETE that a load balancer sends to another
   replica answers 404. A host running several replicas supplies a durable
   JobStore shared by all of them (see [[create-store]]), storing each job as
   [[job->edn]] and reading it back with [[edn->job]]. Kickoff, status, cancel,
   file download and the TTL sweep reach jobs only through the protocol, so a
   durable store needs no handler change: cancel removes the job, after which
   every replica answers 404.

   Stream-slot accounting (`:active-streams`, capped by
   :max-concurrent-streams) stays per process and outside the protocol on
   purpose: the cap bounds the memory one replica spends on concurrent download
   streams, so each replica counts its own.

   Lazy stream-at-download model: a job holds only tiny metadata (a pinned
   store basis + a pre-serialized manifest + per-file stream descriptors). No
   NDJSON is ever held in the job record and nothing is spooled to disk; the
   bytes are produced by streaming from the store at download time (see
   server.bulk-export). Stream tokens are timestamped, so a slot leaked by a
   client that disconnects before Jetty writes the body self-heals after
   :max-stream-ms.

   Job record shape:
     {:id               job-id string
      :tenant           tenant-id string
      :kind             #{:system :patient :group}
      :group-id         optional string
      :params           request query params map
      :basis            pinned point-in-time store basis (db/current-basis)
      :owner-ids        set of subject Patient ids (patient/group), else nil
      :compartment-definitions the compartment definitions in force at kickoff,
                        so the files stream under the same membership
      :patient-ids      the Patient ids a `patient` parameter named, else nil
      :scan-mode        #{:type :compartment} how each type is enumerated
      :access           #{:system :subject} what the requester was authorized as
      :owner            {:sub :client-id} of the requester
      :status           #{:in-progress :complete :error}
      :transaction-time ISO-8601 instant string derived from the basis
      :request-url      absolute kickoff URL string
      :created-at       epoch millis at kickoff
      :finished-at      epoch millis when the job reached a terminal status
      :manifest         pre-serialized application/json status manifest string
      :output           [{:type rt :file-id id :count n} ...]
      :error            [{:type \"OperationOutcome\" :file-id id :diagnostics s} ...]
      :files            {file-id {:kind #{:output :error} :type :diagnostics}}}"
  (:require [clojure.edn :as edn]
            [integrant.core :as ig]
            [malli.core :as m]
            [malli.error :as me])
  (:import [java.time Instant]
           [java.time.format DateTimeParseException]))

;; ---------------------------------------------------------------------------
;; Job storage protocol
;; ---------------------------------------------------------------------------

(defprotocol JobStore
  "Where bulk export job records live. Every replica that serves a tenant's
   bulk routes must see the same JobStore for that tenant, or a request that
   lands on another replica answers 404.

   Implementations must:
   - key jobs by `[tenant id]`, never by id alone;
   - make `-update-job!` atomic per job: `f` receives the current job (nil when
     absent) and returns the new one, or nil to remove it. A compare-and-set
     store may call `f` more than once, so `f` must be pure (compute clocks and
     ids outside it);
   - make `-remove-job!` idempotent (two replicas may sweep the same job);
   - return plain job maps as [[edn->job]] would, never their serialized form."
  (-put-job! [s tenant job]
    "Insert or replace `job` under `[tenant (:id job)]`. Returns the job.")
  (-get-job [s tenant id]
    "The job under `[tenant id]`, or nil.")
  (-update-job! [s tenant id f]
    "Atomically replace the job under `[tenant id]` with `(f job)`, removing
     it when that is nil. Returns the new job or nil.")
  (-remove-job! [s tenant id]
    "Remove the job under `[tenant id]`; a missing job is not an error.
     Returns nil.")
  (-all-jobs [s]
    "A snapshot collection of every job, across tenants, for the TTL sweep."))

(defrecord MemoryJobStore [jobs]
  JobStore
  (-put-job! [_ tenant job]
    (swap! jobs assoc [tenant (:id job)] job)
    job)
  (-get-job [_ tenant id]
    (get @jobs [tenant id]))
  (-update-job! [_ tenant id f]
    (let [k [tenant id]]
      (get (swap! jobs (fn [m]
                         (if-some [job (f (get m k))]
                           (assoc m k job)
                           (dissoc m k))))
           k)))
  (-remove-job! [_ tenant id]
    (swap! jobs dissoc [tenant id])
    nil)
  (-all-jobs [_]
    (vec (vals @jobs))))

(defn memory-job-store
  "A fresh, empty in-process [[JobStore]]. Correct for one replica only."
  []
  (->MemoryJobStore (atom {})))

;; ---------------------------------------------------------------------------
;; Store construction
;; ---------------------------------------------------------------------------

(def default-config
  "Bounded-resource config for the bulk job store. :max-concurrent-streams caps
   simultaneous download streams on this replica (each holds at most one store
   page in memory); :max-stream-ms (milliseconds) bounds how long a leaked
   stream slot lingers before it self-heals (a client can disconnect after the
   200 but before Jetty writes the body, so the body's release finally never
   runs); :ttl-ms bounds how long completed job metadata lingers. Overridable
   via the Integrant component opts and the BULK_* env vars (see the
   :fhir/bulk-job-store init-key)."
  {:max-concurrent-streams 4
   :max-stream-ms          3600000     ; 1h -- self-heal a leaked stream slot
   :ttl-ms                 3600000})   ; 1h

(defn create-store
  "Create a bulk job store with `config` merged over the defaults. Jobs live in
   `jobs`, a [[JobStore]] (default: a fresh [[memory-job-store]]); a host
   running several replicas passes one durable store shared by all of them.
   Anything that is not a JobStore throws."
  ([] (create-store {}))
  ([config] (create-store config (memory-job-store)))
  ([config jobs]
   (when-not (satisfies? JobStore jobs)
     (throw (ex-info "Bulk job store :jobs must satisfy server.bulk-job-store/JobStore"
                     {:jobs-type (some-> jobs class .getName)})))
   {:jobs           jobs
    :active-streams (atom {})
    :config         (merge default-config config)}))

(defn config
  "The bounded-resource config map for `store`."
  [store]
  (:config store))

(defn- jobs-of [store]
  (:jobs store))

(defn put-job!
  "Insert (or replace) `job` under `[tenant-id (:id job)]`. Returns the job."
  [store tenant-id job]
  (-put-job! (jobs-of store) tenant-id job))

(defn get-job
  "Return the job for `[tenant-id job-id]`, or nil when unknown."
  [store tenant-id job-id]
  (-get-job (jobs-of store) tenant-id job-id))

(defn update-job!
  "Apply `f` (plus `args`) to the job at `[tenant-id job-id]` and return the
   updated job. `f` receives the current job (which may be nil) and returns the
   new job, or nil to remove it. `f` must be pure: a durable store may retry
   it."
  [store tenant-id job-id f & args]
  (-update-job! (jobs-of store) tenant-id job-id #(apply f % args)))

(defn remove-job!
  "Remove the job at `[tenant-id job-id]` (idempotent)."
  [store tenant-id job-id]
  (-remove-job! (jobs-of store) tenant-id job-id)
  nil)

(defn all-jobs
  "A snapshot vector of every job currently in the store."
  [store]
  (vec (-all-jobs (jobs-of store))))

;; ---------------------------------------------------------------------------
;; Serialization (for a durable JobStore)
;;
;; A job is plain data except its :basis, whose :system-time (and optional
;; :valid-time) is a java.time.Instant. Every store's current-basis (mock,
;; xtdb2, datomic) returns {:tx-id <long-or-nil> :system-time <Instant>}, so one
;; basis codec serves them all: each Instant is written as its ISO-8601 string
;; and parsed back with Instant/parse. Not #inst: that reads back as a
;; java.util.Date at millisecond precision, which would move an xtdb2
;; `FOR SYSTEM_TIME AS OF` to before the pinned transaction and silently drop
;; its writes from the export. A store whose basis carries anything else
;; must extend encode-basis and decode-basis, or encoding throws.
;; ---------------------------------------------------------------------------

(def ^:private basis-instant-keys [:system-time :valid-time])

(defn- encode-basis [basis]
  (when-not (map? basis)
    (throw (ex-info "Bulk job basis must be a map" {:basis-type (some-> basis class .getName)})))
  (let [unknown (remove #{:tx-id :system-time :valid-time} (keys basis))]
    (when (seq unknown)
      (throw (ex-info "Bulk job basis carries keys the codec does not know"
                      {:unknown-keys (vec unknown)}))))
  (when-not (or (nil? (:tx-id basis)) (int? (:tx-id basis)))
    (throw (ex-info "Bulk job basis :tx-id must be an integer or nil"
                    {:tx-id-type (some-> (:tx-id basis) class .getName)})))
  (when-not (instance? Instant (:system-time basis))
    (throw (ex-info "Bulk job basis :system-time must be a java.time.Instant"
                    {:system-time-type (some-> (:system-time basis) class .getName)})))
  (reduce (fn [b k]
            (let [v (get b k)]
              (cond
                (nil? v) b
                (instance? Instant v) (assoc b k (str v))
                :else (throw (ex-info (str "Bulk job basis " k " must be a java.time.Instant")
                                      {:key k :type (.getName (class v))})))))
          basis
          basis-instant-keys))

(defn- decode-basis [basis]
  (reduce (fn [b k]
            (if-some [v (get b k)]
              (assoc b k (try (Instant/parse v)
                              (catch DateTimeParseException e
                                (throw (ex-info (str "Bulk job basis " k " is not an ISO-8601 instant")
                                                {:key k} e)))))
              b))
          basis
          basis-instant-keys))

(defn- edn-leaf? [x]
  (or (nil? x) (string? x) (keyword? x) (boolean? x) (int? x) (double? x)))

(defn- check-plain-data!
  "Throw naming the path of the first value EDN cannot carry faithfully."
  [x path]
  (cond
    (edn-leaf? x) nil
    (map? x)      (doseq [[k v] x]
                    (check-plain-data! k (conj path :key))
                    (check-plain-data! v (conj path k)))
    (or (vector? x) (set? x) (seq? x))
    (doseq [[i v] (map-indexed vector x)]
      (check-plain-data! v (conj path i)))
    :else
    (throw (ex-info "Bulk job holds a value that cannot be serialized"
                    {:path path :type (.getName (class x))}))))

(def ^:private SerializedJob
  "The shape edn->job accepts: the keys every handler relies on, typed.
   Optional keys are typed when present."
  (m/schema
   [:map
    [:id :string]
    [:tenant :string]
    [:kind [:enum :system :patient :group]]
    [:status [:enum :in-progress :complete :error]]
    [:basis [:map
             [:tx-id {:optional true} [:maybe :int]]
             [:system-time :string]
             [:valid-time {:optional true} [:maybe :string]]]]
    [:created-at :int]
    [:finished-at {:optional true} [:maybe :int]]
    [:group-id {:optional true} [:maybe :string]]
    [:params {:optional true} [:maybe map?]]
    [:owner-ids {:optional true} [:maybe [:set :string]]]
    [:patient-ids {:optional true} [:maybe [:vector :string]]]
    [:compartment-definitions {:optional true}
     [:maybe [:map-of :string [:map-of :string [:maybe [:vector :string]]]]]]
    [:scan-mode {:optional true} [:maybe [:enum :type :compartment]]]
    [:access {:optional true} [:maybe [:enum :system :subject]]]
    [:owner {:optional true} [:maybe [:map
                                      [:sub [:maybe :string]]
                                      [:client-id [:maybe :string]]]]]
    [:transaction-time {:optional true} [:maybe :string]]
    [:request-url {:optional true} [:maybe :string]]
    [:manifest {:optional true} [:maybe :string]]
    [:output {:optional true} [:maybe [:vector [:map
                                                [:type :string]
                                                [:file-id :string]
                                                [:count :int]]]]]
    [:error {:optional true} [:maybe [:vector [:map
                                               [:type :string]
                                               [:file-id :string]
                                               [:diagnostics :string]]]]]
    [:files {:optional true}
     [:maybe [:map-of :string [:map
                               [:kind [:enum :output :error]]
                               [:type :string]
                               [:diagnostics {:optional true} :string]]]]]]))

(defn- check-serialized! [job]
  (when-let [explanation (m/explain SerializedJob job)]
    (throw (ex-info "Malformed bulk job record"
                    {:errors (me/humanize explanation)}))))

(defn job->edn
  "Serialize `job` to an EDN string a durable [[JobStore]] can keep, with the
   basis Instants as ISO-8601 strings. Throws ex-info when the job holds a
   value EDN cannot carry or misses a required key, so nothing is stored that
   [[edn->job]] would refuse."
  ^String [job]
  (let [encoded (update job :basis encode-basis)]
    (check-plain-data! encoded [])
    (check-serialized! encoded)
    (binding [*print-length*         nil
              *print-level*          nil
              *print-meta*           false
              *print-namespace-maps* false]
      (pr-str encoded))))

(defn edn->job
  "Read a job written by [[job->edn]]. Throws ex-info on unreadable EDN, a
   record missing a required key or holding a wrongly typed value, or a basis
   instant that does not parse; a partial job is never returned."
  [s]
  (when-not (string? s)
    (throw (ex-info "Serialized bulk job must be a string" {:type (some-> s class .getName)})))
  (let [job (try (edn/read-string s)
                 (catch Exception e
                   (throw (ex-info "Unreadable serialized bulk job" {} e))))]
    (when-not (map? job)
      (throw (ex-info "Serialized bulk job is not a map" {:type (some-> job class .getName)})))
    (check-serialized! job)
    (update job :basis decode-basis)))

;; ---------------------------------------------------------------------------
;; Concurrent-stream accounting (memory bound, per replica)
;; ---------------------------------------------------------------------------

(defn- prune-stale
  "Drop stream tokens whose start is older than `max-ms` (leaked slots)."
  [streams now max-ms]
  (into {} (remove (fn [[_ started]] (> (- now (long started)) max-ms))) streams))

(defn active-stream-count
  "Number of download streams currently in flight, excluding leaked (stale)
   slots (concurrency-cap input). Prunes stale slots as a side effect."
  [store]
  (let [a      (:active-streams store)
        max-ms (long (:max-stream-ms (config store)))]
    (loop []
      (let [cur  @a
            live (prune-stale cur (System/currentTimeMillis) max-ms)]
        (if (or (identical? cur live) (compare-and-set! a cur live))
          (count live)
          (recur))))))

(defn acquire-stream!
  "Atomically reserve a download-stream slot, first self-healing any leaked
   slots (a stream whose release never ran, e.g. a client disconnect before the
   body was written) older than :max-stream-ms. Returns an opaque token on
   success, or nil when `max` live slots are already held. Pass the token to
   release-stream! exactly once."
  [store max]
  (let [a      (:active-streams store)
        m      (long max)
        max-ms (long (:max-stream-ms (config store)))
        token  (str (random-uuid))]
    (loop []
      (let [cur  @a
            now  (System/currentTimeMillis)
            live (prune-stale cur now max-ms)]
        (if (>= (count live) m)
          (if (or (identical? cur live) (compare-and-set! a cur live)) nil (recur))
          (if (compare-and-set! a cur (assoc live token now)) token (recur)))))))

(defn release-stream!
  "Release a previously acquired download-stream slot by its `token`
   (idempotent; a nil token is a no-op)."
  [store token]
  (when token
    (swap! (:active-streams store) dissoc token))
  nil)

(defn- env-long [name]
  (some-> (System/getenv name) Long/parseLong))

(defn- env-config
  "Config overrides read from the BULK_* env vars (only keys that are set)."
  []
  (cond-> {}
    (System/getenv "BULK_MAX_CONCURRENT_STREAMS")
    (assoc :max-concurrent-streams (env-long "BULK_MAX_CONCURRENT_STREAMS"))
    (System/getenv "BULK_MAX_STREAM_MS")
    (assoc :max-stream-ms (env-long "BULK_MAX_STREAM_MS"))
    (System/getenv "BULK_JOB_TTL_MS")
    (assoc :ttl-ms (env-long "BULK_JOB_TTL_MS"))))

(defmethod ig/init-key :fhir/bulk-job-store [_ opts]
  ;; Integrant opts provide the base config; BULK_* env vars override per key.
  ;; An optional :jobs (e.g. an ig/ref to a durable JobStore) replaces the
  ;; in-memory default.
  (let [opts (or opts {})]
    (if (contains? opts :jobs)
      (create-store (merge (dissoc opts :jobs) (env-config)) (:jobs opts))
      (create-store (merge opts (env-config))))))
