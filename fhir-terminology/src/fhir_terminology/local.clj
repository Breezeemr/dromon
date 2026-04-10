(ns fhir-terminology.local
  (:require [fhir-terminology.protocol :as proto]
            [clojure.java.io :as io]
            [jsonista.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(def ^:private json-mapper (json/object-mapper {:decode-key-fn keyword}))

(defn- resolve-context-valueset-url
  "Resolves a context parameter to a ValueSet URL by looking up the StructureDefinition
   and finding the element binding.
   Context format: {StructureDefinition-URL}#{element-path}
   e.g. http://hl7.org/fhir/us/core/StructureDefinition/us-core-diagnosticreport-note#DiagnosticReport.category"
  [structure-definitions context]
  (let [[sd-url element-path] (str/split context #"#" 2)]
    (when (and sd-url element-path)
      (when-let [sd (get structure-definitions sd-url)]
        (let [elements (or (get-in sd [:snapshot :element])
                           (get-in sd [:differential :element])
                           [])]
          (->> elements
               (some (fn [elem]
                       (when (or (= (:path elem) element-path)
                                 (= (:id elem) element-path))
                         (get-in elem [:binding :valueSet]))))))))))

(defn- load-json-resources
  "Load all JSON files matching resourceType from classpath directories."
  [resource-type dirs]
  (reduce
    (fn [acc dir]
      (let [dir-file (io/file dir)]
        (if (.exists dir-file)
          (reduce
            (fn [acc2 f]
              (if (.endsWith (.getName f) ".json")
                (try
                  (let [res (json/read-value (slurp f) json-mapper)]
                    (if (= resource-type (:resourceType res))
                      (if-let [url (:url res)]
                        (assoc acc2 url res)
                        acc2)
                      acc2))
                  (catch Exception _ acc2))
                acc2))
            acc
            (file-seq dir-file))
          acc)))
    {}
    dirs))

(defn- expand-compose
  "Expand a ValueSet's compose.include entries into a flat list of codes."
  [valueset codesystems]
  (let [includes (get-in valueset [:compose :include] [])]
    (mapcat
      (fn [include]
        (let [system (:system include)
              concepts (:concept include)]
          (if (seq concepts)
            ;; Explicit concept list
            (map (fn [c] (assoc c :system system)) concepts)
            ;; System-only: try to get codes from loaded CodeSystem
            (when-let [cs (get codesystems system)]
              (map (fn [c] {:system system
                            :code (:code c)
                            :display (:display c)})
                   (:concept cs))))))
      includes)))

(defn- apply-filter
  "Filter expansion codes by the _filter text parameter."
  [codes filter-text]
  (if (str/blank? filter-text)
    codes
    (let [lower-filter (str/lower-case filter-text)]
      (filter (fn [c]
                (or (and (:display c) (str/includes? (str/lower-case (:display c)) lower-filter))
                    (and (:code c) (str/includes? (str/lower-case (:code c)) lower-filter))))
              codes))))

(defrecord LocalTerminology [valuesets codesystems structure-definitions]
  proto/ITerminologyService

  (expand-valueset [_ params]
    (let [context (or (:context params) (get params "context"))
          url (if context
                (or (resolve-context-valueset-url structure-definitions context)
                    (throw (ex-info (str "Could not resolve context: " context)
                                    {:fhir/status 400
                                     :fhir/code "invalid"})))
                (or (:url params) (get params "url")))
          vs (get valuesets url)]
      (if vs
        (let [codes (expand-compose vs codesystems)
              filter-text (or (:filter params) (get params "filter"))
              filtered (apply-filter codes filter-text)
              offset (or (some-> (or (:offset params) (get params "offset")) parse-long) 0)
              count-param (or (some-> (or (:count params) (get params "count")) parse-long) 1000)
              paged (->> filtered (drop offset) (take count-param) vec)]
          (assoc vs
                 :expansion {:identifier (str (java.util.UUID/randomUUID))
                             :timestamp (str (java.time.Instant/now))
                             :total (count filtered)
                             :offset offset
                             :contains paged}))
        (throw (ex-info (str "ValueSet not found: " url)
                        {:fhir/status 404
                         :fhir/code "not-found"})))))

  (lookup-code [_ params]
    (let [system (or (:system params) (get params "system"))
          code (or (:code params) (get params "code"))
          cs (get codesystems system)]
      (if cs
        (if-let [concept (some #(when (= (:code %) code) %) (:concept cs))]
          {:resourceType "Parameters"
           :parameter [{:name "name" :valueString (:display concept)}
                       {:name "display" :valueString (or (:display concept) code)}]}
          (throw (ex-info (str "Code not found: " system "|" code)
                          {:fhir/status 404
                           :fhir/code "not-found"})))
        (throw (ex-info (str "CodeSystem not found: " system)
                        {:fhir/status 404
                         :fhir/code "not-found"})))))

  (validate-code [_ params]
    (let [url (or (:url params) (get params "url"))
          system (or (:system params) (get params "system"))
          code (or (:code params) (get params "code"))
          vs (get valuesets url)]
      (if vs
        (let [codes (expand-compose vs codesystems)
              match (some #(and (= (:system %) system) (= (:code %) code) %) codes)]
          {:resourceType "Parameters"
           :parameter [{:name "result" :valueBoolean (boolean match)}]})
        {:resourceType "Parameters"
         :parameter [{:name "result" :valueBoolean false}
                     {:name "message" :valueString (str "ValueSet not found: " url)}]}))))

(defn create-local-terminology
  "Creates a local terminology service from pre-loaded maps."
  ([valuesets codesystems]
   (create-local-terminology valuesets codesystems {}))
  ([valuesets codesystems structure-definitions]
   (->LocalTerminology valuesets codesystems structure-definitions)))

(defn load-from-directories
  "Loads ValueSets, CodeSystems, and StructureDefinitions from filesystem directories."
  [dirs]
  (let [valuesets (load-json-resources "ValueSet" dirs)
        codesystems (load-json-resources "CodeSystem" dirs)
        structure-definitions (load-json-resources "StructureDefinition" dirs)]
    (log/info "Local terminology loaded:" (count valuesets) "ValueSets,"
              (count codesystems) "CodeSystems,"
              (count structure-definitions) "StructureDefinitions")
    (create-local-terminology valuesets codesystems structure-definitions)))

(defmethod ig/init-key :fhir-terminology/local [_ {:keys [resource-dirs]}]
  (let [dirs (or resource-dirs [])]
    (log/info "Starting local terminology backend from dirs:" dirs)
    (load-from-directories dirs)))
