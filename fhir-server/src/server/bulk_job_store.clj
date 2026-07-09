(ns server.bulk-job-store
  "In-memory registry for FHIR Bulk Data Access ($export) jobs.

   Jobs are ephemeral and per-node: the store is a small map
   `{:jobs (atom {[tenant-id job-id] -> job}) :config {...}}`. Keying on
   `[tenant-id job-id]` keeps the registry multitenant without a store-backed
   table (which would diverge across the xtdb2/datomic backends). This is the
   MVP choice documented in
   docs/proposals/bulk-data-export-and-backend-services.md.

   Job record shape:
     {:id               job-id string
      :tenant           tenant-id string
      :kind             #{:system :patient :group}
      :group-id         optional string
      :params           request query params map
      :status           #{:in-progress :complete :error :cancelled}
      :transaction-time ISO-8601 instant string
      :request-url      absolute kickoff URL string
      :created-at       epoch millis at kickoff
      :finished-at      epoch millis when the job reached a terminal status
      :temp-dir         absolute path of this job's temp directory tree
      :output           [{:type rt :file-id id :count n} ...]
      :error            [ ... OperationOutcome-ish maps ... ]
      :files            {file-id {:path :type :count :bytes}}}

   NDJSON content is never held in the job map: it is streamed to the temp
   files whose metadata lives under :files (see server.bulk-export)."
  (:require [integrant.core :as ig]))

(def default-config
  "Bounded-memory caps for the bulk job store. All byte values are in bytes;
   ttl-ms is milliseconds. Overridable via the Integrant component opts and the
   BULK_* env vars (see the :fhir/bulk-job-store init-key)."
  {:max-concurrent-jobs 4
   :max-job-bytes       (* 1024 1024 1024)       ; 1 GB per job
   :max-total-bytes     (* 5 1024 1024 1024)     ; 5 GB across all jobs on disk
   :ttl-ms              3600000                  ; 1h
   :temp-dir            (System/getProperty "java.io.tmpdir")})

(defn create-store
  "Create a fresh, empty job registry with `config` merged over the defaults."
  ([] (create-store {}))
  ([config]
   {:jobs   (atom {})
    :config (merge default-config config)}))

(defn config
  "The bounded-memory config map for `store`."
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

(defn in-progress-count
  "Number of jobs currently in the :in-progress status (concurrency cap input)."
  [store]
  (reduce (fn [n job] (if (= :in-progress (:status job)) (inc n) n))
          0
          (vals @(jobs-atom store))))

(defn total-on-disk-bytes
  "Sum of the recorded :bytes across every file of every job in the store. A
   job records its files only once it completes, so while a worker is still
   writing, this total covers the OTHER jobs' on-disk footprint."
  [store]
  (reduce (fn [total job]
            (reduce (fn [t f] (+ t (long (or (:bytes f) 0))))
                    total
                    (vals (:files job))))
          0
          (vals @(jobs-atom store))))

(defn- env-long [name]
  (some-> (System/getenv name) Long/parseLong))

(defn- env-config
  "Config overrides read from the BULK_* env vars (only keys that are set)."
  []
  (cond-> {}
    (System/getenv "BULK_MAX_CONCURRENT_JOBS")
    (assoc :max-concurrent-jobs (env-long "BULK_MAX_CONCURRENT_JOBS"))
    (System/getenv "BULK_MAX_JOB_BYTES")
    (assoc :max-job-bytes (env-long "BULK_MAX_JOB_BYTES"))
    (System/getenv "BULK_MAX_TOTAL_BYTES")
    (assoc :max-total-bytes (env-long "BULK_MAX_TOTAL_BYTES"))
    (System/getenv "BULK_JOB_TTL_MS")
    (assoc :ttl-ms (env-long "BULK_JOB_TTL_MS"))
    (System/getenv "BULK_TEMP_DIR")
    (assoc :temp-dir (System/getenv "BULK_TEMP_DIR"))))

(defmethod ig/init-key :fhir/bulk-job-store [_ opts]
  ;; Integrant opts provide the base config; BULK_* env vars override per key.
  (create-store (merge (or opts {}) (env-config))))
