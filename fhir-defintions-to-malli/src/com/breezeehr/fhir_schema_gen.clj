(ns com.breezeehr.fhir-schema-gen
  "Loads FHIR StructureDefinitions from bundle files and standalone JSON files,
   then produces them in dependency order (baseDefinition DAG).

   The generate! function processes definitions incrementally, writes each
   schema file, requires it, and feeds the compiled schema back into the
   pipeline for downstream definitions."
  (:require [charred.api :as charred]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [com.breezeehr.fhir-defintions-to-malli :as fdm]
            [fipp.edn :refer [pprint] :rename {pprint fipp}])
  (:import (java.io File)
           (java.net URL URLClassLoader)
           (java.nio.file Files OpenOption Paths)
           (java.nio.file.attribute FileAttribute)))

;; ---------------------------------------------------------------------------
;; Definition loading
;; ---------------------------------------------------------------------------

(def ^:private generatable-kinds
  "StructureDefinition kinds we generate Malli schemas for. Primitive types are
   handled separately via fhir-primitives. Logical models are documentation /
   template constructs that don't correspond to wire-format resources, and R4B
   does not even define a `Base` root for them, so any logical model that bases
   on `Base` would be permanently unreachable in this pipeline."
  #{"complex-type" "resource"})

(defn- read-bundle
  "Read StructureDefinition entries from a FHIR bundle JSON file.
   `source` may be a filesystem path string (e.g. \"scratch/definitions.json/profiles-types.json\")
   or a java.io.File. Downloads land under `scratch/`, which is intentionally
   not on the classpath -- URLClassLoader caches an empty view of any path
   that doesn't exist at JVM startup, which would silently break a fresh run."
  [source]
  (let [reader (cond
                 (string? source)
                 (let [f (io/file source)]
                   (when (.exists f) (io/reader f)))
                 (instance? java.io.File source) (when (.exists ^java.io.File source) (io/reader source))
                 :else (io/reader source))]
    (when reader
      (let [json (charred/read-json reader :key-fn keyword)]
        (into []
              (comp (map :resource)
                    (filter #(= "StructureDefinition" (:resourceType %)))
                    (filter (comp generatable-kinds :kind)))
              (:entry json))))))

(defn- read-directory
  "Read standalone StructureDefinition JSON files from a directory."
  [dir-path]
  (let [dir (io/file dir-path)]
    (when (.isDirectory dir)
      (into []
            (keep (fn [^java.io.File f]
                    (when (and (.isFile f)
                               (str/starts-with? (.getName f) "StructureDefinition-")
                               (str/ends-with? (.getName f) ".json"))
                      (let [json (charred/read-json (io/reader f) :key-fn keyword)]
                        (when (and (= "StructureDefinition" (:resourceType json))
                                   (generatable-kinds (:kind json)))
                          json)))))
            (file-seq dir)))))

(defn load-definitions
  "Load StructureDefinitions from multiple sources.
   Each source is a map with :type and :path:
     {:type :bundle    :path \"scratch/definitions.json/profiles-types.json\"}
     {:type :directory :path \"scratch/us-core/STU8.0.1/package\"}

   Returns a flat sequence of StructureDefinition maps, deduplicated by :url."
  [sources]
  (let [all-defs (into []
                       (mapcat (fn [{:keys [type path]}]
                                 (case type
                                   :bundle (or (read-bundle path) [])
                                   :directory (or (read-directory path) []))))
                       sources)]
    (vals (reduce (fn [m sd] (assoc m (:url sd) sd)) {} all-defs))))

;; ---------------------------------------------------------------------------
;; Dependency ordering
;; ---------------------------------------------------------------------------

(defn- wave-sort-key
  "Sort key for definitions within a dependency wave.
   1. primitive-type < complex-type < resource < logical
   2. Within complex-type, extensions (baseDefinition ends with /Extension)
      come after non-extensions, since simple extensions may reference
      types from the same wave."
  [d]
  (let [kind-rank (case (:kind d)
                    "primitive-type" 0
                    "complex-type"   1
                    "resource"       2
                    "logical"        3
                    9)
        extension? (some-> (:baseDefinition d) (str/ends-with? "/Extension"))]
    [kind-rank (if extension? 1 0)]))

(defn- versioned-base?
  "True if a baseDefinition URL carries an explicit FHIR version qualifier
   (e.g. \"http://hl7.org/fhir/StructureDefinition/SubstanceSpecification|4.0.1\").
   Such bases are intentionally pinned to a specific FHIR version, so when they
   are unreachable in the loaded set it is expected (the type was renamed or
   deprecated in the version we are building against), not a missing dependency."
  [^String base]
  (and base (pos? (.indexOf base "|"))))

(defn dependency-order
  "Returns definitions sorted in dependency order (parents before children).
   Processes in waves: first all definitions whose baseDefinition is in `roots`,
   then their children, etc.  Within each wave, complex-types are placed before
   resources and extensions after non-extensions so that type dependencies are
   satisfied.

   Definitions whose baseDefinition carries an explicit version qualifier (e.g.
   `|4.0.1`) and does not resolve are dropped silently -- they are intentionally
   pinned cross-version mismatches, not missing dependencies.

   For other unreachable definitions, throws an ExceptionInfo listing them.
   Pass `:skip-missing true` to instead print a warning and return only the
   reachable definitions."
  [definitions roots & {:keys [skip-missing]}]
  (let [strip-version (fn [^String url]
                        (when url
                          (let [idx (.indexOf url "|")]
                            (if (pos? idx) (.substring url 0 idx) url))))
        by-base (group-by (comp strip-version :baseDefinition) definitions)
        result  (loop [parents roots
                       result []]
                  (let [wave (mapcat #(get by-base %) parents)]
                    (if (seq wave)
                      (let [wave-vec (vec (sort-by wave-sort-key wave))
                            next-parents (into #{} (map :url) wave-vec)]
                        (recur next-parents (into result wave-vec)))
                      result)))
        ordered-urls (into #{} (map :url) result)
        unreachable  (into []
                           (comp (remove #(contains? ordered-urls (:url %)))
                                 (map (fn [d]
                                        {:name (:name d)
                                         :url  (:url d)
                                         :baseDefinition (:baseDefinition d)})))
                           definitions)
        {pinned-mismatch true real-missing false}
        (group-by (comp boolean versioned-base? :baseDefinition) unreachable)]
    (when (seq real-missing)
      (let [msg (str (count real-missing) " StructureDefinition(s) have unreachable baseDefinition "
                     "(not in roots or any reachable definition):\n"
                     (str/join "\n"
                               (map (fn [{:keys [name url baseDefinition]}]
                                      (str "  " name " (" url ")\n"
                                           "    base: " baseDefinition))
                                    real-missing)))]
        (if skip-missing
          (println "WARNING:" msg)
          (throw (ex-info msg {:missing real-missing})))))
    (when (seq pinned-mismatch)
      (println (count pinned-mismatch)
               "StructureDefinition(s) skipped: baseDefinition pinned to a FHIR version not present in the loaded set"
               "(e.g."
               (-> pinned-mismatch first :name)
               "->"
               (-> pinned-mismatch first :baseDefinition)
               (when (> (count pinned-mismatch) 1) (str "and " (dec (count pinned-mismatch)) " more"))
               ")"))
    result))

(defn plan
  "Given a list of sources, load all StructureDefinitions and return them
   in dependency order.

   `roots` is the set of baseDefinition URLs that are considered already
   available (e.g. #{nil} for base FHIR types).

   Options:
     :skip-missing — when true, warn about unreachable definitions instead of
                     throwing. Default: false (throws)."
  [sources roots & {:as opts}]
  (dependency-order (load-definitions sources) roots opts))

;; ---------------------------------------------------------------------------
;; File writing
;; ---------------------------------------------------------------------------

(defn- kw->file-path
  "Convert a schema keyword to a java.nio.file.Path under `base-dir`."
  [k base-dir]
  (let [p (-> (if (vector? base-dir) base-dir [base-dir])
              (into (map #(str/replace ^String % "-" "_"))
                    (str/split (namespace k) #"\."))
              (conj (str (str/replace ^String (name k) "-" "_") ".cljc")))]
    (Paths/get (first p) (into-array String (rest p)))))

(defn- emit-ns-form [w k & {:keys [resource? extra-requires]}]
  (fipp (list 'ns (fdm/kw->ns-sym k)
              (into [:require
                     (if resource?
                       '[com.breezeehr.fhir-primitives :refer [fhir-registry-options fhir-registry]]
                       '[com.breezeehr.fhir-primitives :refer [fhir-registry-options]])
                     '[malli.core :as m]
                     '[malli.util :as mu]]
                    (concat
                     (when resource? ['[malli.registry]])
                     extra-requires)))
        {:writer w}))

(defn- compute-transitive-deps
  "Walk the schema-atom dependency graph starting from `root-deps` and return
   the full set of transitive dependency keywords."
  [schema-atom root-deps]
  (loop [queue (vec root-deps)
         seen  #{}]
    (if (empty? queue)
      seen
      (let [kw    (peek queue)
            queue (pop queue)]
        (if (seen kw)
          (recur queue seen)
          (let [entry (get @schema-atom kw)
                deps  (or (:references entry) #{})]
            (recur (into queue (remove seen) deps)
                   (conj seen kw))))))))

(defn- lr-key->def-name
  "Convert a local-registry key like \"#Bundle.link\" to a def name like \"Bundle-link\"."
  [lr-key]
  (str/replace lr-key #"[#.]" {"#" "" "." "-"}))

(defn- kw->alias-sym
  "Derive an alias symbol from a schema keyword using the last namespace segment."
  [kw]
  (symbol (last (str/split (namespace kw) #"\."))))

(defn- build-alias-map
  "Build a map of kw → alias-symbol, disambiguating collisions with numeric suffixes."
  [kws]
  (let [sorted-kws (sort kws)
        seg-fn (fn [kw] (last (str/split (namespace kw) #"\.")))
        groups (group-by seg-fn sorted-kws)]
    (into {}
          (mapcat (fn [[seg kws]]
                    (if (= 1 (count kws))
                      [[(first kws) (symbol seg)]]
                      (map-indexed (fn [i kw] [kw (symbol (str seg (inc i)))]) kws))))
          groups)))

(defn- emit-base-fns
  "Emit (defn base-X [] ...) forms for each base ref.
   In staging mode, uses requiring-resolve. In final mode, uses direct var refs."
  [w base-refs staging? dep-aliases]
  (doseq [[fn-name ref-kw] (sort-by key base-refs)]
    (let [fn-sym (symbol fn-name)]
      (if staging?
        (fipp (list 'defn fn-sym [] `(~'deref (~'requiring-resolve '~(fdm/kw->sch-sym ref-kw))))
              {:writer w})
        (if-let [alias-sym (get dep-aliases ref-kw)]
          (fipp (list 'defn fn-sym [] (symbol (str alias-sym) "sch"))
                {:writer w})
          ;; For non-resource types, use the namespace directly
          (fipp (list 'defn fn-sym [] (fdm/kw->sch-sym ref-kw))
                {:writer w}))))))

(defn- write-single-schema!
  "Write a single schema entry to a .cljc file. Returns the file path written.
   When `staging?` is true, writes only ns + sch (no registry/full-sch) so the
   file can be required without eagerly compiling the full transitive graph.
   `schema-atom` is needed for resources (to compute transitive deps)."
  [k entry base-dir staging? & {:keys [schema-atom]}]
  (let [{:keys [kind form references local-registry base-refs]} entry
        path (kw->file-path k base-dir)]
    (Files/createDirectories (.getParent path) (make-array FileAttribute 0))
    (with-open [w (Files/newBufferedWriter path (make-array OpenOption 0))]
      (if staging?
        ;; Staging: minimal file — ns + base fns + local-registry :own defs + sch def.
        ;; Local-registry defs (e.g. Questionnaire-item) must be staged so that the
        ;; runtime registry built by resolve-local-registry-schemas can locate them
        ;; via requiring-resolve when later sibling/child profiles are being processed.
        (do
          (emit-ns-form w k)
          (when (seq base-refs)
            (emit-base-fns w base-refs true nil))
          (when local-registry
            (doseq [[lr-name {:keys [type form]}] local-registry
                    :when (= type :own)]
              (let [def-sym (symbol (lr-key->def-name lr-name))]
                (fipp (list 'def def-sym form) {:writer w}))))
          (fipp (list 'def 'sch (fdm/make-into-schema-form form)) {:writer w}))
        ;; Final: full file with deps, base fns, local-registry defs, sch, and full-sch for resources
        (let [;; For resources, compute the full transitive dependency set
              transitive-deps (when (and (= kind "resource") schema-atom)
                                (compute-transitive-deps schema-atom references))
              ;; Build alias map: dep-kw → alias symbol using last namespace segment
              dep-aliases (when transitive-deps
                            (build-alias-map transitive-deps))
              ;; For non-resource types, build aliases just for the base-ref namespaces
              base-ref-aliases (when (and (seq base-refs) (not dep-aliases))
                                 (build-alias-map (vals base-refs)))]
          ;; Collect namespaces referenced by :ref local-registry entries so we can
          ;; add static :require forms (needed for ClojureScript compatibility).
          (let [ref-ns-syms (when local-registry
                              (into (sorted-set)
                                (keep (fn [[_ {:keys [type source-kw]}]]
                                        (when (= type :ref)
                                          (fdm/kw->ns-sym source-kw))))
                                local-registry))
                ;; Merge dep-aliases and base-ref-aliases for a unified alias map
                all-aliases (merge dep-aliases base-ref-aliases)
                dep-requires (when (seq all-aliases)
                               (mapv (fn [[dep-kw alias-sym]]
                                       [(fdm/kw->ns-sym dep-kw) :as alias-sym])
                                     (sort-by val all-aliases)))
                dep-ns-set (into #{} (map (fn [[dep-kw _]] (fdm/kw->ns-sym dep-kw))) all-aliases)
                ;; Only include bare ref-ns-sym requires for namespaces not already in dep-requires
                ref-requires (into []
                                   (comp (remove dep-ns-set)
                                         (map (fn [ns-sym] [ns-sym])))
                                   ref-ns-syms)
                extra-requires (into ref-requires dep-requires)]
            (emit-ns-form w k :resource? (= kind "resource") :extra-requires extra-requires))
          ;; Every file gets deps — all schema keywords referenced by the sch body
          (fipp (list 'def 'deps (into (sorted-set) references)) {:writer w})
          ;; Emit base-X functions with direct var references
          (when (seq base-refs)
            (emit-base-fns w base-refs false (or dep-aliases base-ref-aliases)))
          ;; Local-registry defs (BackboneElement inline types)
          (when local-registry
            (doseq [[lr-name {:keys [type form]}] local-registry
                    :when (= type :own)]
              (let [def-sym (symbol (lr-key->def-name lr-name))]
                (fipp (list 'def def-sym form) {:writer w}))))
          ;; sch def
          (fipp (list 'def 'sch (fdm/make-into-schema-form form)) {:writer w})
          ;; Resources get registry + full-sch built from transitive deps
          (when (= kind "resource")
            (let [local-entries (when local-registry
                                  (into {}
                                    (map (fn [[lr-name {:keys [type source-kw]}]]
                                           (let [def-name (lr-key->def-name lr-name)]
                                             (if (= type :ref)
                                               [lr-name (symbol (str (fdm/kw->ns-sym source-kw)) def-name)]
                                               [lr-name (symbol def-name)]))))
                                    local-registry))
                  ;; Build literal registry map: {kw dep-alias/sch, ...}
                  literal-registry (into (sorted-map)
                                     (map (fn [[dep-kw alias-sym]]
                                            [dep-kw (symbol (str alias-sym) "sch")]))
                                     dep-aliases)]
              (fipp (list 'def 'registry
                          (if (seq local-entries)
                            `(~'merge ~literal-registry ~local-entries)
                            literal-registry))
                    {:writer w})
              (fipp (list 'def 'full-sch
                          `(m/schema ~'sch
                                     {:registry
                                      (malli.registry/composite-registry
                                       (malli.registry/registry ~'registry)
                                       (malli.registry/registry ~'fhir-registry))}))
                    {:writer w}))))))
    path))

;; ---------------------------------------------------------------------------
;; Generation pipeline
;; ---------------------------------------------------------------------------

(defn- ensure-on-classpath!
  "Ensure a directory is on the classpath so that `require` can find files
   written there. Creates the directory if it doesn't exist, then adds it
   to the context classloader if not already present."
  [dir-segments]
  (let [path (Paths/get (first dir-segments) (into-array String (rest dir-segments)))
        abs-path (.toAbsolutePath path)
        _ (Files/createDirectories abs-path (make-array FileAttribute 0))
        dir-url (.toURL (.toUri abs-path))
        cl (clojure.lang.RT/baseLoader)]
    (if (instance? clojure.lang.DynamicClassLoader cl)
      (.addURL ^clojure.lang.DynamicClassLoader cl dir-url)
      ;; Wrap the current classloader so require can find staged files
      (let [dcl (clojure.lang.DynamicClassLoader. cl)]
        (.addURL dcl dir-url)
        (.setContextClassLoader (Thread/currentThread) dcl)))))

(defn generate!
  "Process definitions incrementally.

   For each definition in order:
   1. Process with structure-definition->schema (builds :form)
   2. Write staging .cljc (just ns + sch)
   3. Require the staging file (making sch available via requiring-resolve)

   After all definitions:
   4. Write final .cljc files (with registry + full-sch) to out-dir

   `schema-atom` should be empty (or contain pre-existing base schemas).
   `staging-dir` is where staging files are written (e.g. [\"target\" \"staging\" \"src\"]).
     It will be dynamically added to the classpath if not already present.
   `out-dir` is the final output path (e.g. [\"..\" \"fhir\" ... \"src\"]).

   Returns {:process {:ok n :fail n :failures [...]},
            :staging {:ok n :fail n},
            :output  {:ok n :fail n}}."
  [schema-atom staging-dir out-dir ordered-defs]
  (ensure-on-classpath! staging-dir)
  (let [process-fn (fdm/structure-definition->schema schema-atom)
        process-results (atom {:ok 0 :fail 0 :failures []})
        staging-results (atom {:ok 0 :fail 0})]

    ;; Phase 1+2: process each definition, write staging, require
    (println "Phase 1+2: processing" (count ordered-defs) "definitions incrementally...")
    (binding [fdm/*schema-atom* schema-atom]
      (doseq [sd ordered-defs]
        (let [url (:url sd)
              version (:version sd)]
          (try
            ;; Step 1: Process
            (process-fn sd)
            (let [kw (fdm/uri->kw2 url version)
                  entry (get @schema-atom kw)]
              ;; Step 2: Write staging
              (write-single-schema! kw entry staging-dir true)
              ;; Step 3: Require (makes sch var available for requiring-resolve)
              (try
                (require (fdm/kw->ns-sym kw) :reload)
                (swap! staging-results update :ok inc)
                (catch Exception _
                  (swap! staging-results update :fail inc))))
            (swap! process-results update :ok inc)
            (catch Exception e
              (swap! process-results update :fail inc)
              (swap! process-results update :failures conj
                     {:name (:name sd) :url url :error (.getMessage e)}))))))
    (println "Phase 1+2 done: process=" (select-keys @process-results [:ok :fail])
             "staging=" @staging-results)

    ;; Phase 3: write final output
    ;; Only write schemas for definitions in this batch, not upstream ones.
    (println "Phase 3: writing final output to" (str/join "/" (if (vector? out-dir) out-dir [out-dir])) "...")
    (let [this-batch-kws (into #{}
                               (keep (fn [sd]
                                       (try (fdm/uri->kw2 (:url sd) (:version sd))
                                            (catch Exception _ nil))))
                               ordered-defs)
          output-results (atom {:ok 0 :fail 0})]
      ;; Ensure staging files exist for all defs (needed for require)
      (doseq [[k _] @schema-atom]
        (when-not (find-ns (fdm/kw->ns-sym k))
          (try
            (write-single-schema! k (get @schema-atom k) staging-dir true)
            (require (fdm/kw->ns-sym k) :reload)
            (catch Exception _))))
      ;; Only write final output for this batch's definitions
      (doseq [[k entry] @schema-atom
              :when (contains? this-batch-kws k)]
        (try
          (write-single-schema! k entry out-dir false :schema-atom schema-atom)
          (swap! output-results update :ok inc)
          (catch Exception e
            (println "OUTPUT WRITE ERROR:" k (.getMessage e))
            (swap! output-results update :fail inc))))
      (println "Phase 3 done:" @output-results)

      {:process @process-results
       :staging @staging-results
       :output @output-results})))

(defn validate!
  "Load all generated .cljc files from `dir` and report pass/fail counts.
   Does two passes: first loads all, then retries failures (for dependency ordering)."
  [dir ns-prefix]
  (let [files (->> (.listFiles (io/file dir))
                   (filter #(str/ends-with? (.getName ^java.io.File %) ".cljc"))
                   (sort-by #(.getName ^java.io.File %)))
        pass1-fails (atom [])
        _ (doseq [f files]
            (let [fname (.getName ^java.io.File f)
                  ns-name (symbol (str ns-prefix "." (subs fname 0 (- (count fname) 5))))]
              (try (require ns-name :reload) (catch Exception _ (swap! pass1-fails conj fname)))))
        results (atom {:ok (- (count files) (count @pass1-fails)) :fail 0 :failures []})]
    (doseq [fname @pass1-fails]
      (let [ns-name (symbol (str ns-prefix "." (subs fname 0 (- (count fname) 5))))]
        (try (require ns-name :reload) (swap! results update :ok inc)
             (catch Exception e
               (swap! results update :fail inc)
               (swap! results update :failures conj
                      {:file fname :error (or (some-> e .getCause .getMessage) (.getMessage e))})))))
    @results))

(comment
  ;; === R4B ===
  (def r4b-sources
    [{:type :bundle :path "definitions.json/profiles-types.json"}
     {:type :bundle :path "definitions.json/profiles-resources.json"}
     {:type :bundle :path "definitions.json/extension-definitions.json"}
     {:type :bundle :path "definitions.json/profiles-others.json"}])

  (def r4b-plan (plan r4b-sources #{nil}))
  (count r4b-plan)

  (def sa (atom {}))
  (time (def r4b-results
    (generate! sa
               ["target" "staging" "src"]
               [".." "fhir" "malli" "r4b" "src"]
               r4b-plan)))
  r4b-results

  ;; Validate the final output
  (validate! "../fhir/malli/r4b/src/org/hl7/fhir/StructureDefinition/v4_3_0"
             "org.hl7.fhir.StructureDefinition.v4_3_0")

  ;; === US Core STU8 (extends R4B — uses same schema-atom) ===
  (def r4b-urls (into #{nil} (map :url) r4b-plan))
  (def uscore8-plan (plan [{:type :directory :path "scratch/us-core/STU8.0.1/package"}]
                          r4b-urls))

  (time (def uscore8-results
    (generate! sa
               ["target" "staging" "src"]
               [".." "fhir" "malli" "uscore8" "src"]
               uscore8-plan)))
  uscore8-results

  (validate! "../fhir/malli/uscore8/src/org/hl7/fhir/us/core/StructureDefinition/v8_0_1"
             "org.hl7.fhir.us.core.StructureDefinition.v8_0_1"))
