(ns com.breezeehr.main
  (:require [com.breezeehr.download-fhir :as download-fhir]
            [com.breezeehr.fhir-schema-gen :as gen]
            [com.breezeehr.fhir-defintions-to-malli :as fdm]
            [com.breezeehr.capability-statement :as cs]
            [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [fipp.edn :refer [pprint] :rename {pprint fipp}])
  (:import (java.io File)
           (java.nio.file Paths Files OpenOption FileVisitResult
                          SimpleFileVisitor Path StandardCopyOption)
           (java.nio.file.attribute BasicFileAttributes FileAttribute)))

(defn- delete-directory-recursive!
  "Delete a directory and all its contents."
  [^Path dir-path]
  (when (Files/exists dir-path (make-array java.nio.file.LinkOption 0))
    (Files/walkFileTree dir-path
      (proxy [SimpleFileVisitor] []
        (visitFile [^Path file ^BasicFileAttributes _attrs]
          (Files/delete file)
          FileVisitResult/CONTINUE)
        (postVisitDirectory [^Path dir _exc]
          (Files/delete dir)
          FileVisitResult/CONTINUE)))))

(defn- version-sources [prefix]
  [{:type :bundle :path (str prefix "profiles-types.json")}
   {:type :bundle :path (str prefix "profiles-resources.json")}
   {:type :bundle :path (str prefix "extension-definitions.json")}
   {:type :bundle :path (str prefix "profiles-others.json")}])

(defn- write-deps-edn!
  "Write a deps.edn file for a generated package.
   `pkg-dir` is the package root (e.g. [\"..\" \"fhir\" \"malli\" \"r4b\"]).
   `local-deps` is a seq of maps {:name \"r4b\" :relative-path \"../r4b\"} for
   local package dependencies.
   `extra-paths` is an optional seq of additional paths (e.g. [\"resources\"])."
  [pkg-dir local-deps & {:keys [extra-paths] :or {extra-paths []}}]
  (let [dir-path (Paths/get (first pkg-dir) (into-array String (rest pkg-dir)))
        deps-path (.resolve dir-path "deps.edn")
        dep-map (into '{com.breezeehr/fhir-primitives {:local/root "../../../fhir-primitives"}}
                      (map (fn [{:keys [name relative-path]}]
                             [(symbol "com.breezeehr" (str "fhir-malli-" name))
                              {:local/root relative-path}]))
                      local-deps)
        paths (into ["src"] extra-paths)]
    (Files/createDirectories dir-path (make-array FileAttribute 0))
    (with-open [w (Files/newBufferedWriter deps-path (make-array OpenOption 0))]
      (fipp {:paths paths
             :deps  (merge '{org.clojure/clojure {:mvn/version "1.12.2"}}
                           dep-map)
             :aliases
             {:build
              {:replace-paths ["."]
               :replace-deps '{io.github.clojure/tools.build
                               {:git/tag "v0.9.4" :git/sha "76b78fe"}}
               :ns-default 'build}}}
            {:writer w}))
    deps-path))

(defn- definition-url->resource-path
  "Converts a FHIR definition URL to a classpath resource path.
   http://hl7.org/fhir/us/core/SearchParameter/us-core-condition-category
   -> org/hl7/fhir/us/core/SearchParameter/us_core_condition_category.json"
  [url]
  (let [parsed (java.net.URI. url)
        host (.getHost parsed)
        host-parts (.split host "\\.")
        host-reversed (str/join "/" (reverse (seq host-parts)))
        path-segments (->> (.split (.getPath parsed) "/")
                           (remove str/blank?))
        init-segments (butlast path-segments)
        last-segment (-> (last path-segments) (str/replace "-" "_"))]
    (str host-reversed "/" (str/join "/" init-segments) "/" last-segment ".json")))

(defn- write-search-param-resource!
  "Writes a SearchParameter resource map to the target package's resources
   directory at a URL-mapped path. Returns true on success."
  [target-pkg-dir sp-resource source-file]
  (let [url (:url sp-resource)
        rel-path (definition-url->resource-path url)
        target-dir (Paths/get (first target-pkg-dir)
                              (into-array String (rest target-pkg-dir)))
        target-path (.resolve target-dir (str "resources/" rel-path))]
    (Files/createDirectories (.getParent target-path) (make-array FileAttribute 0))
    (if source-file
      ;; Copy the original file (preserves formatting)
      (Files/copy (.toPath ^File source-file) target-path
                  (into-array java.nio.file.CopyOption
                              [StandardCopyOption/REPLACE_EXISTING]))
      ;; Write from parsed map (for resources extracted from bundles)
      (with-open [w (Files/newBufferedWriter target-path (make-array OpenOption 0))]
        (charred/write-json w sp-resource)))
    true))

(defn- copy-search-params-from-directory!
  "Copies standalone SearchParameter-*.json files from a package directory.
   Returns the number of files copied."
  [package-dir target-pkg-dir]
  (let [pkg-path (io/file package-dir)
        sp-files (->> (.listFiles pkg-path)
                      (filter #(and (.isFile %)
                                    (str/starts-with? (.getName %) "SearchParameter-")
                                    (str/ends-with? (.getName %) ".json"))))]
    (reduce
     (fn [cnt sp-file]
       (try
         (let [parsed (with-open [r (io/reader sp-file)]
                        (charred/read-json r :key-fn keyword))]
           (write-search-param-resource! target-pkg-dir parsed sp-file)
           (inc cnt))
         (catch Exception e
           (println "  Warning: failed to copy" (.getName sp-file) "-" (.getMessage e))
           cnt)))
     0
     sp-files)))

(defn- extract-search-params-from-bundle!
  "Extracts SearchParameter resources from a FHIR bundle JSON file and writes
   each one to the target package's resources directory.
   Returns the number of resources extracted."
  [bundle-path target-pkg-dir]
  (let [bundle-file (io/file bundle-path)]
    (if (.exists bundle-file)
      (let [bundle (with-open [r (io/reader bundle-file)]
                     (charred/read-json r :key-fn keyword))
            entries (:entry bundle)
            sp-entries (filter #(= "SearchParameter" (get-in % [:resource :resourceType])) entries)]
        (reduce
         (fn [cnt entry]
           (try
             (let [sp (:resource entry)]
               (write-search-param-resource! target-pkg-dir sp nil)
               (inc cnt))
             (catch Exception e
               (println "  Warning: failed to extract SearchParameter -" (.getMessage e))
               cnt)))
         0
         sp-entries))
      0)))

(defn- copy-search-parameters!
  "Copies SearchParameter resources from source(s) to target package resources.
   `sources` is a vector of source descriptors:
     {:type :directory :path \"...\"} - copy standalone SearchParameter-*.json files
     {:type :bundle    :path \"...\"} - extract SearchParameter entries from bundle
   Returns the total number of resources copied."
  [sources target-pkg-dir]
  (reduce
   (fn [total {:keys [type path]}]
     (let [cnt (case type
                 :directory (copy-search-params-from-directory! path target-pkg-dir)
                 :bundle    (extract-search-params-from-bundle! path target-pkg-dir)
                 0)]
       (+ total cnt)))
   0
   sources))

(defn- report-unresolved-profiles!
  "Print the profile canonicals no package in the run defines. Each one is a
   slice whose narrowing was dropped: it keeps its :url discriminator but falls
   back to the element's own type. Runs planned with :skip-missing can legitimately
   leave some of these, so this reports rather than throws."
  [unresolved]
  (if (empty? unresolved)
    (println "\nEvery profile canonical resolved to a generated schema.")
    (let [by-profile (group-by :profile unresolved)]
      (println "\nWARNING:" (count unresolved) "slice(s) reference"
               (count by-profile) "profile canonical(s) no package in this run defines."
               "Their narrowing is dropped; the slice keeps its :url and falls back to the element type.")
      (doseq [[profile occurrences] (sort-by key by-profile)]
        (println "  " (count occurrences) "x" profile)
        (println "       degraded to" (:degraded-to (first occurrences)))
        (println "       referenced by" (str/join ", " (sort (distinct (map :from occurrences)))))))))

(defn -main [& [version out-dir]]
  (let [lower-version (if version (.toLowerCase ^String version) "r4b")
        prefix (case lower-version
                 "r4b" "scratch/definitions.json/"
                 "")
        out-dir (or out-dir [".." "fhir" "malli" lower-version "src"])
        staging-dir ["target" "staging" "src"]]
    (println "Clearing staging directory...")
    (delete-directory-recursive! (Paths/get (first staging-dir) (into-array String (rest staging-dir))))
    (println "Downloading FHIR definitions for version:" lower-version)
    (download-fhir/download lower-version)
    (println "Download complete. Generating schemas...")
    (let [plan  (gen/plan (version-sources prefix) #{nil})
          index (gen/canonical-index [plan])
          sa    (atom {})
          unresolved (atom [])
          result (binding [fdm/*canonical-index*     index
                           fdm/*known-canonical-kws* (gen/index-kws index)
                           fdm/*unresolved-profiles* unresolved]
                   (gen/generate! sa staging-dir out-dir plan))]
      (report-unresolved-profiles! @unresolved)
      (println "Generation complete:" result)
      (assoc result :unresolved-profiles @unresolved))))

(defn generate-uscore!
  "Generate schemas for US Core STU8, including all intermediate dependencies.

   Processes in order:
     1. R4B base definitions
     2. FHIR Extensions IG (common extensions; xver and SDC both profile these)
     3. xver-r5.r4 cross-version extensions (R5 extensions backported to R4)
     4. SDC IG (intermediate dependency for Questionnaire/QuestionnaireResponse)
     5. US Core STU8 profiles
     6. US Core CapabilityStatement :multi schemas
     7. US Core SearchParameter resources
     8. Da Vinci CRD extensions

   FHIR Extensions is generated before xver because xver profiles 27 extensions
   that only FHIR Extensions defines (alternate-reference, the artifact-* family)
   while FHIR Extensions references nothing from xver -- its 680 definitions all
   derive from R4B's Extension. The old order made those 27 forward references,
   which is what the canonical index below exists to resolve correctly, but a
   forward reference from a resource file also emits a `:require` on a package
   the referencing package does not depend on. Ordering by the real dependency
   makes those references backward and the emitted deps.edn honest.

   Every plan is computed before anything is generated so that
   `fdm/*canonical-index*` can resolve a profile canonical to the package that
   really defines it -- and to the version that package publishes -- instead of
   stamping the referencing package's version onto it.

   Each package only contains its own schemas. deps.edn files are written
   with local dependencies in the correct order.

   Downloads any missing packages before generating.

   Options:
     :base-dir           — base output directory (default: [\"..\" \"fhir\" \"malli\"])
     :staging-dir        — staging directory (default: [\"target\" \"staging\" \"src\"])
     :force-download?    — re-download packages even if already present (default: false)
     :schema-atom        — schema atom to accumulate generated entries into
                           (default: a fresh atom). Pass one in to chain further
                           packages (e.g. downstream IGs deriving from these
                           profiles) onto the same generation state.

   The return map includes :urls — the set of all StructureDefinition URLs
   processed across the pipeline — for use as the roots of a chained package,
   and :canonical-index, the pre-scan the run resolved against."
  [& {:keys [base-dir staging-dir force-download? schema-atom]
      :or   {base-dir         [".." "fhir" "malli"]
             staging-dir      ["target" "staging" "src"]
             force-download?  false}}]
  (println "Clearing staging directory...")
  (delete-directory-recursive! (Paths/get (first staging-dir) (into-array String (rest staging-dir))))

  ;; --- Downloads, then every plan, before any generation ---
  (println "=== Downloading packages ===")
  (download-fhir/download "R4B" :force? force-download?)
  (download-fhir/download-and-extract-fhir-extensions! "5.3.0-ballot-tc1" :force? force-download?)
  (download-fhir/download-and-extract-xver! "0.1.0" :force? force-download?)
  (download-fhir/download-and-extract-sdc! "STU4" :force? force-download?)
  (download-fhir/download-and-extract-uscore! "STU8.0.1" :force? force-download?)
  (download-fhir/download-and-extract-davinci-crd! "2.0.1" :force? force-download?)

  (println "\n=== Planning all packages ===")
  (let [r4b-plan     (gen/plan (version-sources "scratch/definitions.json/") #{nil})
        r4b-urls     (into #{nil} (map :url) r4b-plan)
        fhirext-plan (gen/plan [{:type :directory :path "scratch/fhir-extensions/5.3.0-ballot-tc1/package"}]
                               r4b-urls :skip-missing true)
        fhirext-urls (into r4b-urls (map :url) fhirext-plan)
        xver-plan    (gen/plan [{:type :directory :path "scratch/xver/0.1.0/package"}]
                               fhirext-urls :skip-missing true)
        xver-urls    (into fhirext-urls (map :url) xver-plan)
        sdc-plan     (gen/plan [{:type :directory :path "scratch/sdc/STU4/site"}]
                               xver-urls :skip-missing true)
        sdc-urls     (into xver-urls (map :url) sdc-plan)
        uscore-plan  (gen/plan [{:type :directory :path "scratch/us-core/STU8.0.1/package"}] sdc-urls)
        uscore-urls  (into sdc-urls (map :url) uscore-plan)
        davinci-plan (filterv #(= "Extension" (:type %))
                              (gen/plan [{:type :directory :path "scratch/davinci-crd/2.0.1/package"}]
                                        uscore-urls :skip-missing true))
        ;; Pipeline order, so a canonical defined by two packages prefers the earlier.
        index        (gen/canonical-index [r4b-plan fhirext-plan xver-plan
                                           sdc-plan uscore-plan davinci-plan])
        unresolved   (atom [])]
    (println "  planned" (+ (count r4b-plan) (count fhirext-plan) (count xver-plan)
                            (count sdc-plan) (count uscore-plan) (count davinci-plan))
             "definitions;" (count index) "distinct canonicals indexed")

    (binding [fdm/*canonical-index*    index
              fdm/*known-canonical-kws* (gen/index-kws index)
              fdm/*unresolved-profiles* unresolved]
      (let [sa          (or schema-atom (atom {}))
            r4b-pkg     (conj base-dir "r4b")
            fhirext-pkg (conj base-dir "fhir-extensions")
            xver-pkg    (conj base-dir "xver")
            sdc-pkg     (conj base-dir "sdc")
            uscore-pkg  (conj base-dir "uscore8")
            davinci-pkg (conj base-dir "davinci")

            ;; --- 1. R4B base ---
            _           (println "\n=== Step 1: R4B base definitions ===")
            r4b-result  (gen/generate! sa staging-dir (conj r4b-pkg "src") r4b-plan)
            _           (write-deps-edn! r4b-pkg [] :extra-paths ["resources"])
            _           (println "  Copying R4B SearchParameters...")
            r4b-sp      (copy-search-parameters!
                         [{:type :bundle :path "scratch/definitions.json/search-parameters.json"}]
                         r4b-pkg)
            _           (println "  Copied" r4b-sp "R4B SearchParameter resources")

            ;; --- 2. FHIR Extensions IG (common extensions needed by xver and SDC) ---
            _              (println "\n=== Step 2: FHIR Extensions IG ===")
            fhirext-result (gen/generate! sa staging-dir (conj fhirext-pkg "src") fhirext-plan)
            _              (write-deps-edn! fhirext-pkg [{:name "r4b" :relative-path "../r4b"}]
                                            :extra-paths ["resources"])
            _              (println "  Copying FHIR Extensions SearchParameters...")
            fhirext-sp     (copy-search-parameters!
                            [{:type :directory :path "scratch/fhir-extensions/5.3.0-ballot-tc1/package"}]
                            fhirext-pkg)
            _              (println "  Copied" fhirext-sp "FHIR Extensions SearchParameter resources")

            ;; --- 3. xver-r5.r4 cross-version extensions ---
            _           (println "\n=== Step 3: xver-r5.r4 cross-version extensions ===")
            xver-result (gen/generate! sa staging-dir (conj xver-pkg "src") xver-plan)
            _           (write-deps-edn! xver-pkg [{:name "r4b" :relative-path "../r4b"}
                                                   {:name "fhir-extensions" :relative-path "../fhir-extensions"}])
            ;; xver has no SearchParameter resources

            ;; --- 4. SDC IG ---
            _           (println "\n=== Step 4: SDC IG (intermediate dependency) ===")
            sdc-result  (gen/generate! sa staging-dir (conj sdc-pkg "src") sdc-plan)
            _           (write-deps-edn! sdc-pkg [{:name "r4b" :relative-path "../r4b"}
                                                  {:name "fhir-extensions" :relative-path "../fhir-extensions"}
                                                  {:name "xver" :relative-path "../xver"}]
                                         :extra-paths ["resources"])
            _           (println "  Copying SDC SearchParameters...")
            sdc-sp      (copy-search-parameters!
                         [{:type :directory :path "scratch/sdc/STU4/site"}]
                         sdc-pkg)
            _           (println "  Copied" sdc-sp "SDC SearchParameter resources")

            ;; --- 5. US Core STU8 ---
            _           (println "\n=== Step 5: US Core STU8 profiles ===")
            uscore-result (gen/generate! sa staging-dir (conj uscore-pkg "src") uscore-plan)
            _           (write-deps-edn! uscore-pkg [{:name "r4b" :relative-path "../r4b"}
                                                     {:name "fhir-extensions" :relative-path "../fhir-extensions"}
                                                     {:name "xver" :relative-path "../xver"}
                                                     {:name "sdc" :relative-path "../sdc"}]
                                         :extra-paths ["resources"])

            ;; --- 6. CapabilityStatement ---
            _           (println "\n=== Step 6: US Core CapabilityStatement ===")
            r4b-sp-dir  (io/file (str (str/join "/" r4b-pkg) "/resources/org/hl7/fhir/SearchParameter"))
            cap-result  (cs/generate-from-capability-statement!
                          (io/file "scratch/us-core/STU8.0.1/package/CapabilityStatement-us-core-server.json")
                          "us-core" "8.0.1" "8.0.1" "4.3.0"
                          (conj uscore-pkg "src")
                          :schema-atom sa
                          :search-param-dir r4b-sp-dir)

            ;; --- 7. US Core SearchParameter resources ---
            _           (println "\n=== Step 7: Copy SearchParameter resources ===")
            uscore-sp   (copy-search-parameters!
                         [{:type :directory :path "scratch/us-core/STU8.0.1/package"}]
                         uscore-pkg)
            _           (println "  Copied" uscore-sp "US Core SearchParameter resources")

            ;; --- 8. Da Vinci CRD extensions ---
            ;; The decant carries the CRD ext-coverage-information extension self-describing
            ;; (fhir-datomic-decant ig-extensions). Only the Extension StructureDefinitions are
            ;; generated; the CRD profiles are out of scope for the decant read surface. The
            ;; extension refs resolve against the r4b/us-core chain already generated above.
            _           (println "\n=== Step 8: Da Vinci CRD extensions ===")
            davinci-result (gen/generate! sa staging-dir (conj davinci-pkg "src") davinci-plan)
            _           (write-deps-edn! davinci-pkg [{:name "r4b" :relative-path "../r4b"}
                                                      {:name "fhir-extensions" :relative-path "../fhir-extensions"}
                                                      {:name "xver" :relative-path "../xver"}
                                                      {:name "sdc" :relative-path "../sdc"}
                                                      {:name "uscore8" :relative-path "../uscore8"}])]
        (report-unresolved-profiles! @unresolved)
        {:r4b            r4b-result
         :xver           xver-result
         :fhir-extensions fhirext-result
         :sdc            sdc-result
         :uscore         uscore-result
         :davinci        davinci-result
         :capability     cap-result
         :search-params  {:r4b r4b-sp :fhir-extensions fhirext-sp :sdc sdc-sp :uscore uscore-sp}
         :unresolved-profiles @unresolved
         :canonical-index index
         :urls           (into uscore-urls (map :url) davinci-plan)}))))
