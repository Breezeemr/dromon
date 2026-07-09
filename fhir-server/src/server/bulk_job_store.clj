(ns server.bulk-job-store
  "In-memory registry for FHIR Bulk Data Access ($export) jobs.

   Jobs are ephemeral and per-node: an atom of `{[tenant-id job-id] -> job}`.
   Keying on `[tenant-id job-id]` keeps the registry multitenant without a
   store-backed table (which would diverge across the xtdb2/datomic backends).
   This is the MVP choice documented in
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
      :output           [{:type rt :file-id id :count n} ...]
      :error            [ ... OperationOutcome-ish maps ... ]
      :files            {file-id ndjson-string}}"
  (:require [integrant.core :as ig]))

(defn create-store
  "Create a fresh, empty job registry atom."
  []
  (atom {}))

(defn put-job!
  "Insert (or replace) `job` under `[tenant-id (:id job)]`. Returns the job."
  [store tenant-id job]
  (swap! store assoc [tenant-id (:id job)] job)
  job)

(defn get-job
  "Return the job for `[tenant-id job-id]`, or nil when unknown."
  [store tenant-id job-id]
  (get @store [tenant-id job-id]))

(defn update-job!
  "Apply `f` (plus `args`) to the job at `[tenant-id job-id]` and return the
   updated job. `f` receives the current job (which may be nil) and must
   return the new job (or nil to leave/remove it)."
  [store tenant-id job-id f & args]
  (let [k [tenant-id job-id]]
    (get (apply swap! store update k f args) k)))

(defn remove-job!
  "Remove the job at `[tenant-id job-id]`."
  [store tenant-id job-id]
  (swap! store dissoc [tenant-id job-id])
  nil)

(defmethod ig/init-key :fhir/bulk-job-store [_ _opts]
  (create-store))
