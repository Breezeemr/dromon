(ns fhir-search-bench.synthea
  "Generates the synthetic FHIR dataset with Synthea, mirroring the Blaze
   FHIR-search performance methodology (https://samply.github.io/blaze/performance/fhir-search.html).

   Blaze scales by patient count (Synthea emits ~hundreds-to-thousands of
   resources per living patient). For a first, storage-light run we generate a
   small population and let `dataset.clj` cap the total at ~10k resources.

   The Synthea distribution jar (~80 MiB) is downloaded once into this project
   directory and git-ignored. Output transaction bundles land under
   `synthea-output/fhir/`."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ^:private jar-name "synthea-with-dependencies.jar")
(def ^:private jar-url
  "https://github.com/synthetichealth/synthea/releases/download/master-branch-latest/synthea-with-dependencies.jar")
(def ^:private output-dir "synthea-output")

(defn- log [& args] (apply println "[synthea]" args))

(defn- jar-present? [] (.exists (io/file jar-name)))

(defn- download-jar! []
  (if (jar-present?)
    (log jar-name "already present, skipping download.")
    (do
      (log "Downloading Synthea distribution (~80 MiB) from" jar-url)
      (let [{:keys [exit err]} (shell/sh "curl" "-fL" "--retry" "3"
                                         "-o" jar-name jar-url)]
        (when-not (zero? exit)
          (throw (ex-info "Failed to download Synthea jar" {:err err})))
        (log "Download complete:" (.getName (io/file jar-name)))))))

(defn generate
  "Generate the Synthea dataset.

   Options (all optional):
   - :population  number of living patients to generate (default 12). ~12 living
     patients yields well over 10k resources, which `dataset.clj` then caps.
   - :seed        deterministic seed (default 42)
   - :clean?      delete any previous output first (default true)

   Invoke with: clojure -X fhir-search-bench.synthea/generate :population 12"
  [{:keys [population seed clean?]
    :or   {population 12 seed 42 clean? true}}]
  (download-jar!)
  (when (and clean? (.exists (io/file output-dir)))
    (log "Removing previous output at" output-dir)
    (shell/sh "rm" "-rf" output-dir))
  (.mkdirs (io/file output-dir))
  (log "Generating" population "living patients (seed" (str seed ") ..."))
  (let [args ["java" "-jar" jar-name
              "-p" (str population)
              "-s" (str seed)
              "--exporter.baseDirectory" output-dir
              "--exporter.fhir.export" "true"
              "--exporter.fhir.transaction_bundle" "true"
              "--exporter.hospital.fhir.export" "true"
              "--exporter.practitioner.fhir.export" "true"
              "--exporter.csv.export" "false"
              "--exporter.text.export" "false"
              "--generate.only_alive_patients" "true"]
        {:keys [exit out err]} (apply shell/sh args)]
    (when (seq out)
      (println (str/join "\n" (take-last 8 (str/split-lines out)))))
    (when-not (zero? exit)
      (throw (ex-info "Synthea generation failed" {:exit exit :err err})))
    (let [fhir-dir (io/file output-dir "fhir")
          files    (when (.isDirectory fhir-dir)
                     (filter #(str/ends-with? (.getName ^java.io.File %) ".json")
                             (.listFiles fhir-dir)))]
      (log "Generated" (count files) "FHIR bundle file(s) under" (.getPath fhir-dir))
      {:output-dir (.getPath fhir-dir)
       :file-count (count files)})))
