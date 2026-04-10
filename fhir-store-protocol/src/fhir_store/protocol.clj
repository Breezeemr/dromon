(ns fhir-store.protocol)

(defprotocol IFHIRStore
  (create-resource [this tenant-id resource-type id resource])
  (read-resource [this tenant-id resource-type id])
  (vread-resource [this tenant-id resource-type id vid])
  (update-resource [this tenant-id resource-type id resource])
  (delete-resource [this tenant-id resource-type id])
  (search [this tenant-id resource-type params search-registry])
  (history [this tenant-id resource-type id])
  (history-type [this tenant-id resource-type params]
    "Returns all versions of all resources of a given type.")
  (count-resources [this tenant-id resource-type params search-registry]
    "Returns the total count of resources matching the search params.")
  (transact-bundle [this tenant-id entries])
  (resource-deleted? [this tenant-id resource-type id]
    "Returns true if the resource was previously created and then deleted,
     false if it exists or was never created."))

(defn create-store
  "Creates an IFHIRStore implementation. `impl-fn` is a function that takes
   a config map and returns an IFHIRStore instance.

   The config map contains:
   - :resource/schemas  — vector of compiled malli schemas (one per supported
                          resource type, each carrying :resourceType, :fhir/cap-schema,
                          :fhir/interactions, :fhir/search-registry in properties)
   - Implementation-specific keys (e.g., XTDB node config)"
  [impl-fn config]
  (impl-fn config))
