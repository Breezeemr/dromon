(ns fhir-search-bench.dataset
  "Turns the raw Synthea transaction bundles into store-ready, per-bundle entry
   vectors, preserving Synthea's native intra-bundle reference style.

   Each Synthea entry references its siblings by `urn:uuid:` fullUrl and is
   inserted with a `POST`. We keep that shape rather than pre-rewriting
   references to `Type/id`: the IFHIRStore `transact-transaction` implementations
   build their own `urn:uuid` -> tempid mapping from the bundle's POST fullUrls
   and resolve intra-bundle references atomically. Cross-bundle references (a
   patient's link to an Organization defined in the hospital-information bundle)
   stay as `urn:uuid:` strings and are skipped by the stores — which is exactly
   what keeps the Datomic backend's entity-ref model from orphaning nested
   component tempids on unresolvable references.

   We:
   1. Load the shared hospital/practitioner-information bundles first, then the
      patient bundles (load order only matters for cross-bundle links).
   2. Drop entries whose resource type is not covered by the uscore8 schemas.
   3. Normalize each kept entry to `{:fullUrl <urn> :request {:method \"POST\"
      :url <ResourceType>} :resource <resource>}` (dropping Synthea's
      conditional-create `ifNoneExist`).
   4. Cap the total number of resources (default 10k), adding whole bundles at a
      time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [jsonista.core :as json]
            [fhir-search-bench.schema :as schema]))

(def ^:private default-fhir-dir "synthea-output/fhir")
(def ^:private mapper (json/object-mapper {:decode-key-fn true}))

(defn- log [& args] (apply println "[dataset]" args))

(defn- bundle-files
  "Synthea writes the shared hospital/practitioner info bundles plus one bundle
   per patient. Info bundles are returned first so their Organizations and
   Practitioners are committed before any patient that references them."
  [dir]
  (let [files (->> (.listFiles (io/file dir))
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".json")))
        info? #(let [n (.getName ^java.io.File %)]
                 (or (str/starts-with? n "hospitalInformation")
                     (str/starts-with? n "practitionerInformation")))]
    (concat (sort-by #(.getName ^java.io.File %) (filter info? files))
            (sort-by #(.getName ^java.io.File %) (remove info? files)))))

(defn- parse-bundle [^java.io.File f]
  (json/read-value f mapper))

(defn- strip-conditional-refs
  "Synthea emits conditional references such as
   `Organization?identifier=https://github.com/synthetichealth/synthea|<uuid>`
   for cross-bundle links. These are search-based references that the Datomic
   backend cannot resolve to an entity (it splits on `/` and fails with
   :db.error/not-an-entity), aborting the load; xtdb2 keeps them as opaque
   strings. They are not part of the search workload, so we drop the
   `:reference` value wherever it is a conditional (`Type?query`) reference,
   leaving identical data for both backends."
  [resource]
  (walk/postwalk
   (fn [x]
     (if (and (map? x)
              (let [r (:reference x)] (and (string? r) (str/includes? r "?"))))
       (dissoc x :reference)
       x))
   resource))

(defn build
  "Build the capped dataset, preserving Synthea bundle boundaries.

   Options:
   - :dir            directory of Synthea bundle json files (default synthea-output/fhir)
   - :max-resources  cap on total resources (default 10000)

   Returns {:bundles [{:name :entries [...]} ...]   ; load order
            :entries [...]                           ; flat, for stats
            :total <n> :by-type {type count} :patients <n>}."
  [{:keys [dir max-resources] :or {dir default-fhir-dir max-resources 10000}}]
  (let [supported (schema/supported-types)
        files     (bundle-files dir)
        _         (log "Found" (count files) "bundle file(s) in" dir)
        ;; Per bundle, keep the supported entries in Synthea's native POST shape.
        bundle->entries
        (fn [bundle]
          (into []
                (keep (fn [{:keys [resource fullUrl]}]
                        (let [rtype (:resourceType resource)]
                          (when (and rtype (contains? supported rtype))
                            {:fullUrl  fullUrl
                             :request  {:method "POST" :url rtype}
                             :resource (strip-conditional-refs resource)}))))
                (:entry bundle)))
        ;; Parse files lazily and accumulate whole bundles until the resource cap
        ;; is reached, so we never hold more than the capped dataset in memory
        ;; (important at multi-100k scale where the full corpus is many GiB).
        {:keys [bundles total patients]}
        (reduce (fn [{:keys [total] :as acc} ^java.io.File f]
                  (if (>= total max-resources)
                    (reduced acc)
                    (let [es      (bundle->entries (parse-bundle f))
                          has-pt? (some #(= "Patient" (get-in % [:resource :resourceType])) es)]
                      (-> acc
                          (update :bundles conj {:name (.getName f) :entries es})
                          (update :total + (count es))
                          (update :patients (if has-pt? inc identity))))))
                {:bundles [] :total 0 :patients 0}
                files)
        entries (vec (mapcat :entries bundles))
        by-type (->> entries
                     (map #(get-in % [:resource :resourceType]))
                     frequencies
                     (into (sorted-map)))]
    (log "Built" total "entries from" (count bundles) "bundle(s) /" patients "patient(s)")
    {:bundles  bundles
     :entries  entries
     :total    total
     :patients patients
     :by-type  by-type}))
