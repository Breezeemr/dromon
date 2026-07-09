(ns server.bulk-job-store
  "In-memory registry for FHIR Bulk Data Access ($export) jobs.

   Jobs are ephemeral and per-node: the store is
   `{:jobs (atom {[tenant-id job-id] -> job})
     :active-streams (atom <long>)
     :config {...}}`. Keying on `[tenant-id job-id]` keeps the registry
   multitenant without a store-backed table (which would diverge across the
   xtdb2/datomic backends). This is the MVP choice documented in
   docs/proposals/bulk-data-export-and-backend-services.md.

   Lazy stream-at-download model: a job holds only tiny metadata (a pinned
   store basis + a pre-serialized manifest + per-file stream descriptors). No
   NDJSON is ever held in the job map and nothing is spooled to disk; the bytes
   are produced by streaming from the store at download time (see
   server.bulk-export). The only bounded resource is the number of concurrent
   download streams, tracked by `:active-streams` and capped by
   :max-concurrent-streams.

   Job record shape:
     {:id               job-id string
      :tenant           tenant-id string
      :kind             #{:system :patient :group}
      :group-id         optional string
      :params           request query params map
      :basis            pinned point-in-time store basis (db/current-basis)
      :owner-ids        set of subject Patient ids (patient/group), else nil
      :status           #{:in-progress :complete :error :cancelled}
      :transaction-time ISO-8601 instant string derived from the basis
      :request-url      absolute kickoff URL string
      :created-at       epoch millis at kickoff
      :finished-at      epoch millis when the job reached a terminal status
      :manifest         pre-serialized application/json status manifest string
      :output           [{:type rt :file-id id :count n} ...]
      :error            [{:type \"OperationOutcome\" :file-id id :diagnostics s} ...]
      :files            {file-id {:kind #{:output :error} :type :diagnostics}}}"
  (:require [integrant.core :as ig]))

(def default-config
  "Bounded-resource config for the bulk job store. :max-concurrent-streams caps
   simultaneous download streams (each holds at most one store page in memory);
   :ttl-ms (milliseconds) bounds how long completed job metadata lingers.
   Overridable via the Integrant component opts and the BULK_* env vars (see the
   :fhir/bulk-job-store init-key)."
  {:max-concurrent-streams 4
   :ttl-ms                 3600000})   ; 1h

(defn create-store
  "Create a fresh, empty job registry with `config` merged over the defaults."
  ([] (create-store {}))
  ([config]
   {:jobs           (atom {})
    :active-streams (atom 0)
    :config         (merge default-config config)}))

(defn config
  "The bounded-resource config map for `store`."
  [store]
  (:config store))

(defn- jobs-atom [store]
  (:jobs store))

(defn put-job!
  "Insert (or replace) `job` under `[tenant-id (:id job)]`. Returns the job."
  [store tenant-id job]
  (swap! (jobs-atom store) assoc [tenant-id (:id job)] job)
  job)

(defn get-job
  "Return the job for `[tenant-id job-id]`, or nil when unknown."
  [store tenant-id job-id]
  (get @(jobs-atom store) [tenant-id job-id]))

(defn update-job!
  "Apply `f` (plus `args`) to the job at `[tenant-id job-id]` and return the
   updated job. `f` receives the current job (which may be nil) and must
   return the new job (or nil to leave/remove it)."
  [store tenant-id job-id f & args]
  (let [k [tenant-id job-id]]
    (get (apply swap! (jobs-atom store) update k f args) k)))

(defn remove-job!
  "Remove the job at `[tenant-id job-id]`."
  [store tenant-id job-id]
  (swap! (jobs-atom store) dissoc [tenant-id job-id])
  nil)

(defn all-jobs
  "A snapshot vector of every job currently in the store."
  [store]
  (vec (vals @(jobs-atom store))))

;; ---------------------------------------------------------------------------
;; Concurrent-stream accounting (memory bound)
;; ---------------------------------------------------------------------------

(defn active-stream-count
  "Number of download streams currently in flight (concurrency-cap input)."
  [store]
  (long @(:active-streams store)))

(defn acquire-stream!
  "Atomically reserve a download-stream slot: increment the active-stream
   counter iff it is below `max`, returning true on success or false when the
   cap is already reached. Pair every true return with exactly one
   release-stream!."
  [store max]
  (let [a (:active-streams store)
        m (long max)]
    (loop []
      (let [n (long @a)]
        (cond
          (>= n m)                        false
          (compare-and-set! a n (inc n))  true
          :else                           (recur))))))

(defn release-stream!
  "Release a previously acquired download-stream slot (never below zero)."
  [store]
  (swap! (:active-streams store) (fn [n] (max 0 (dec (long n)))))
  nil)

(defn- env-long [name]
  (some-> (System/getenv name) Long/parseLong))

(defn- env-config
  "Config overrides read from the BULK_* env vars (only keys that are set)."
  []
  (cond-> {}
    (System/getenv "BULK_MAX_CONCURRENT_STREAMS")
    (assoc :max-concurrent-streams (env-long "BULK_MAX_CONCURRENT_STREAMS"))
    (System/getenv "BULK_JOB_TTL_MS")
    (assoc :ttl-ms (env-long "BULK_JOB_TTL_MS"))))

(defmethod ig/init-key :fhir/bulk-job-store [_ opts]
  ;; Integrant opts provide the base config; BULK_* env vars override per key.
  (create-store (merge (or opts {}) (env-config))))
