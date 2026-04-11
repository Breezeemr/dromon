(ns com.breezeehr.capability-statement
  "Reads a FHIR CapabilityStatement from an implementation guide and generates
   a namespace per REST resource. Each namespace contains a malli :multi schema
   that dispatches on meta.profile, with one branch per supportedProfile and a
   :default branch for the base resource type."
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [com.breezeehr.fhir-defintions-to-malli :as fdm]
            [fipp.edn :refer [pprint] :rename {pprint fipp}])
  (:import (java.nio.file Files OpenOption Paths)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; CapabilityStatement reading
;; ---------------------------------------------------------------------------

(defn read-capability-statement
  "Read a CapabilityStatement JSON file. `source` can be a classpath resource
   path (string), a java.io.File, or anything coercible by io/reader."
  [source]
  (let [reader (cond
                 (string? source) (some-> (io/resource source) io/reader)
                 (instance? java.io.File source) (when (.exists ^java.io.File source) (io/reader source))
                 :else (io/reader source))]
    (when reader
      (charred/read-json reader :key-fn keyword))))

(defn- strip-version-suffix
  "Strip the |version suffix from a canonical URL.
   \"http://...StructureDefinition/us-core-patient|8.0.0\" => \"http://...StructureDefinition/us-core-patient\""
  [^String canonical]
  (let [idx (.indexOf canonical "|")]
    (if (pos? idx)
      (.substring canonical 0 idx)
      canonical)))

;; ---------------------------------------------------------------------------
;; REST resource extraction
;; ---------------------------------------------------------------------------

(defn- extract-interactions
  "Extract interaction codes from a CapabilityStatement resource entry."
  [resource-entry]
  (into [] (map :code) (:interaction resource-entry)))

(defn- extract-search-params
  "Extract search parameters from a CapabilityStatement resource entry."
  [resource-entry]
  (into []
        (map (fn [sp] (select-keys sp [:name :type :definition])))
        (:searchParam resource-entry)))

(defn- find-base-search-params
  "Find base FHIR SearchParameter definitions for a resource type by scanning
   a SearchParameter directory on the filesystem. `search-param-dir` should be
   a java.io.File pointing to the directory containing {ResourceType}_*.json files.
   Returns a vector of {:name :type :definition} maps, or nil."
  [^java.io.File search-param-dir resource-type]
  (when (and search-param-dir (.isDirectory search-param-dir))
    (let [prefix (str resource-type "_")]
      (into []
            (comp
              (filter #(and (.isFile ^java.io.File %)
                            (.startsWith (.getName ^java.io.File %) prefix)
                            (.endsWith (.getName ^java.io.File %) ".json")))
              (map (fn [^java.io.File f]
                     (let [sp (charred/read-json (io/reader f) :key-fn keyword)]
                       {:name       (:name sp)
                        :type       (:type sp)
                        :definition (:url sp)})))
              (filter :name))
            (.listFiles search-param-dir)))))

(defn rest-resources
  "Extract REST resource entries from a CapabilityStatement.
   Returns a seq of maps with :type, :supported-profiles (bare URLs), :interactions, :search-params.
   When a resource declares search-type interaction but the CapabilityStatement
   lists no searchParam entries, base FHIR R4B SearchParameter definitions are
   injected from `search-param-dir` (a java.io.File) if provided."
  [capability-statement & {:keys [search-param-dir]}]
  (for [rest-entry (:rest capability-statement)
        resource   (:resource rest-entry)]
    (let [interactions  (extract-interactions resource)
          search-params (extract-search-params resource)
          search-params (if (and (empty? search-params)
                                 search-param-dir
                                 (some #{"search-type"} interactions))
                          (or (find-base-search-params search-param-dir (:type resource))
                              search-params)
                          search-params)]
      {:type               (:type resource)
       :supported-profiles (into []
                                 (map strip-version-suffix)
                                 (:supportedProfile resource))
       :interactions       interactions
       :search-params      search-params})))

;; ---------------------------------------------------------------------------
;; Schema form generation
;; ---------------------------------------------------------------------------

(defn- profile-dispatch-form
  "Generate the dispatch function form for a :multi schema.
   Dispatches on the first entry in meta.profile that matches a known profile URL,
   falling back to :default."
  [profile-urls]
  (let [url-set (set profile-urls)]
    `(fn [~'m]
       (let [~'profiles (get-in ~'m [:meta :profile])]
         (or (some ~url-set ~'profiles)
             :default)))))

(defn- resolve-profile-kw
  "Resolve a profile URL to its schema keyword by looking up the type name
   in the schema-atom. Returns nil if the profile was not generated."
  [url schema-atom _profile-version]
  (let [clean-url (strip-version-suffix url)
        type-name (fdm/munge-ns (str/replace (last (str/split clean-url #"/")) "." "-"))]
    (when schema-atom
      (first (filter #(= (fdm/kw->type-name %) type-name) (keys @schema-atom))))))

(defn- resource-multi-form
  "Build the :multi schema form for a single REST resource entry.
   Each supportedProfile becomes a branch keyed by its canonical URL.
   The :default branch references the base FHIR resource type.

   `schema-atom` is consulted first to resolve profile URLs to their actual
   generated keywords (handles cross-IG version differences)."
  [{:keys [type supported-profiles interactions search-params]}
   schema-atom profile-version base-fhir-version]
  (let [profile-entries (for [url supported-profiles
                              :let [kw (resolve-profile-kw url schema-atom profile-version)]
                              :when kw]
                          [url (fdm/kw->sch-sym kw)])
        profile-urls    (mapv first profile-entries)
        base-url        (str "http://hl7.org/fhir/StructureDefinition/" type)
        base-kw         (resolve-profile-kw base-url schema-atom base-fhir-version)
        meta-map        {:dispatch      (profile-dispatch-form profile-urls)
                         :resourceType  type
                         :interactions  interactions
                         :search-params search-params}]
    (when-not base-kw
      (println "WARN: base resource schema not found for" type))
    (into [:multi meta-map]
          (concat
           (for [[url sch-sym] profile-entries]
             [url sch-sym])
           (when base-kw
             [[:default (fdm/kw->sch-sym base-kw)]])))))

;; ---------------------------------------------------------------------------
;; Namespace emission
;; ---------------------------------------------------------------------------

(defn- capability-ns-sym
  "Build a namespace symbol for a resource type under an IG prefix.
   e.g. (capability-ns-sym \"us-core\" \"8.0.1\" \"Patient\")
        => us-core.capability.v8-0-1.Patient"
  [ig-name ig-version resource-type]
  (symbol (str (fdm/munge-ns ig-name)
               ".capability."
               "v" (str/replace ig-version "." "-")
               "." resource-type)))

(defn- collect-profile-ns-syms
  "Collect all namespace symbols for the profiles and base resource type."
  [{:keys [type supported-profiles]} schema-atom profile-version base-fhir-version]
  (let [profile-ns-syms (for [url supported-profiles
                              :let [kw (resolve-profile-kw url schema-atom profile-version)]
                              :when kw]
                          (fdm/kw->ns-sym kw))
        base-url        (str "http://hl7.org/fhir/StructureDefinition/" type)
        base-kw         (resolve-profile-kw base-url schema-atom base-fhir-version)]
    (sort (cond-> (set profile-ns-syms)
            base-kw (conj (fdm/kw->ns-sym base-kw))))))

(defn- resource-type->file-path
  "Convert IG name, version, and resource type to a java.nio.file.Path under base-dir."
  [ig-name ig-version resource-type base-dir]
  (let [ns-sym  (capability-ns-sym ig-name ig-version resource-type)
        segments (-> (if (vector? base-dir) base-dir [base-dir])
                     (into (map #(str/replace ^String % "-" "_"))
                           (str/split (str ns-sym) #"\.")))]
    (Paths/get (first segments)
               (into-array String (conj (vec (rest (butlast segments)))
                                        (str (last segments) ".cljc"))))))

(defn- write-resource-schema!
  "Write a single resource :multi schema file."
  [ig-name ig-version resource-entry schema-atom profile-version base-fhir-version base-dir]
  (let [resource-type (:type resource-entry)
        ns-sym        (capability-ns-sym ig-name ig-version resource-type)
        ns-syms       (collect-profile-ns-syms resource-entry schema-atom profile-version base-fhir-version)
        multi-form    (resource-multi-form resource-entry schema-atom profile-version base-fhir-version)
        path          (resource-type->file-path ig-name ig-version resource-type base-dir)
        ;; Build registry merge form from all branch namespaces
        registry-syms (mapv #(symbol (str % "/registry")) ns-syms)
        merged-registry-form `(~'malli.registry/composite-registry
                                (~'malli.registry/registry (~'merge ~@registry-syms))
                                (~'malli.registry/registry ~'fhir-registry))]
    (Files/createDirectories (.getParent path) (make-array FileAttribute 0))
    (with-open [w (Files/newBufferedWriter path (make-array OpenOption 0))]
      (fipp (list 'ns ns-sym
                  (list* :require
                         '[malli.core :as m]
                         '[malli.registry]
                         '[com.breezeehr.fhir-primitives :refer [fhir-registry]]
                         (sort ns-syms)))
            {:writer w})
      (.newLine w)
      (fipp (list 'def 'sch multi-form) {:writer w})
      (.newLine w)
      (fipp (list 'def 'full-sch
                  (list 'malli.core/schema 'sch {:registry merged-registry-form}))
            {:writer w}))
    path))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn generate-from-capability-statement!
  "Read a CapabilityStatement and generate one namespace per REST resource.

   Parameters:
     source            — path to CapabilityStatement JSON (classpath string, File, etc.)
     ig-name           — short name for the IG (e.g. \"us-core\")
     ig-version        — IG version (e.g. \"8.0.1\")
     profile-version   — version used when generating profile schemas, i.e. the
                          StructureDefinition .version (e.g. \"8.0.1\")
     base-fhir-version — the underlying FHIR version (e.g. \"4.3.0\")
     out-dir           — output directory (string or vector of path segments)

   Returns {:ok n :fail n :failures [...]}."
  [source ig-name ig-version profile-version base-fhir-version out-dir
   & {:keys [schema-atom search-param-dir]}]
  (let [cs        (read-capability-statement source)
        resources (rest-resources cs :search-param-dir search-param-dir)
        results   (atom {:ok 0 :fail 0 :failures []})]
    (doseq [resource-entry resources]
      (try
        (write-resource-schema! ig-name ig-version resource-entry schema-atom profile-version base-fhir-version out-dir)
        (swap! results update :ok inc)
        (catch Exception e
          (swap! results update :fail inc)
          (swap! results update :failures conj
                 {:type (:type resource-entry) :error (.getMessage e)}))))
    (println "CapabilityStatement generation:" (select-keys @results [:ok :fail]))
    @results))

(comment
  ;; Generate US Core STU8 capability namespaces
  (generate-from-capability-statement!
   (io/file "scratch/us-core/STU8.0.1/package/CapabilityStatement-us-core-server.json")
   "us-core" "8.0.1" "8.0.1" "4.3.0"
   ["target" "staging" "src"]))
