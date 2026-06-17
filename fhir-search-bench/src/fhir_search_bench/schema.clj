(ns fhir-search-bench.schema
  "Resolves the uscore8 capability schemas once and exposes the three things the
   bench needs from them:

   - `schemas`            the compiled server-ready schema vector, handed to both
                          store backends as `:resource/schemas`.
   - `supported-types`    the set of resource-type strings the schemas cover;
                          used to filter the Synthea dataset.
   - `registry-for`       per-resource-type search registry (the map the store's
                          `search` arity expects), extracted from each compiled
                          schema's `:fhir/search-registry` property.

   The capability spec vector is the canonical one from
   `test-server.schemas.uscore8/specs`, so this never drifts from the running
   server's schema set."
  (:require [malli.core :as m]
            [server.core :as server]
            [test-server.schemas.uscore8 :as uscore8]))

(def schemas
  "Compiled, server-ready capability schemas for every uscore8 resource type."
  (delay (server/resolve-schemas uscore8/specs)))

(def ^:private by-type
  (delay
    (into {}
          (map (fn [s]
                 (let [props (m/properties s)]
                   [(:resourceType props) props])))
          @schemas)))

(defn supported-types
  "Set of resource-type strings (e.g. #{\"Observation\" \"Patient\" ...})."
  []
  (set (keys @by-type)))

(defn registry-for
  "The search registry for `resource-type` (string or keyword), or nil if the
   type is unknown."
  [resource-type]
  (get-in @by-type [(name resource-type) :fhir/search-registry]))
