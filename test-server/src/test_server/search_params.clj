(ns test-server.search-params
  "Loads SearchParameter JSON resources from the classpath.
   SearchParameter files are stored in the uscore8 package at URL-mapped paths
   under org/hl7/fhir/us/core/SearchParameter/."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]))

(def ^:private json-mapper
  (json/object-mapper {:decode-key-fn keyword}))

(def ^:private search-param-resource-dir
  "Classpath directory containing SearchParameter JSON files."
  "org/hl7/fhir/us/core/SearchParameter")

(defn- load-search-parameter-jsons
  "Loads all SearchParameter JSON files from the URL-mapped classpath directory
   and parses them into Clojure maps with keyword keys."
  []
  (when-let [dir (io/resource search-param-resource-dir)]
    (->> (io/file dir)
         file-seq
         (filter #(and (.isFile %) (str/ends-with? (.getName %) ".json")))
         (mapv (fn [f]
                 (let [m (json/read-value (slurp f) json-mapper)]
                   ;; Strip underscore-prefixed keys that XTDB doesn't support as columns
                   (into {} (remove (fn [[k _]] (-> k name (.startsWith "_")))) m))))
         (sort-by :id))))

(def search-parameters
  "All US Core SearchParameter resources, loaded from JSON definition files.
   Each entry is a FHIR SearchParameter resource map with keyword keys."
  (or (load-search-parameter-jsons) []))
