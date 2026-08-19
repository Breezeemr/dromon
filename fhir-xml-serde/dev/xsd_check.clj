(ns xsd-check
  "Independent conformance check: validate the XML we EMIT against HL7's own
  fhir-all.xsd. The round-trip gate compares our output to the input through a
  comparator we wrote; this stage asks a third party whether the output is
  conformant FHIR XML at all."
  (:require [gate]
            [clojure.string :as str])
  (:import (java.io File StringReader)
           (javax.xml.transform.stream StreamSource)
           (javax.xml.validation SchemaFactory Validator)
           (javax.xml XMLConstants)))

(def xsd-path (or (System/getenv "FHIR_XML_XSD") "dev-resources/fhir-all-xsd/fhir-all.xsd"))

(defn validator ^Validator []
  (let [f (SchemaFactory/newInstance XMLConstants/W3C_XML_SCHEMA_NS_URI)]
    (.newValidator (.newSchema f (File. ^String xsd-path)))))

(defn -main [& args]
  (let [limit (if (seq args) (parse-long (first args)) 200)
        files (->> (.listFiles gate/corpus)
                   (filter #(.isFile ^File %))
                   (sort-by #(.getName ^File %))
                   (take limit))
        v (validator)
        results
        (doall
         (for [^File f files]
           (let [xml (slurp f)
                 t (gate/root-type xml)]
             (if-not (gate/resource-schema t)
               {:file (.getName f) :status :no-schema}
               (try
                 (let [out ((gate/unparser-for t) ((gate/parser-for t) xml))]
                   (try
                     (.validate v (StreamSource. (StringReader. out)))
                     {:file (.getName f) :status :valid}
                     (catch Exception e
                       {:file (.getName f) :status :invalid :msg (.getMessage e)})))
                 (catch Throwable e
                   {:file (.getName f) :status :error :msg (.getMessage e)}))))))
        by (group-by :status results)]
    (println)
    (println "=== emitted XML validated against HL7 fhir-all.xsd ===")
    (println "valid:" (count (:valid by)) "/" (count results))
    (doseq [[k v] (dissoc by :valid)]
      (println)
      (println k "->" (count v))
      (doseq [r (take 5 v)] (println "  " (:file r) (some-> (:msg r) (subs 0 (min 160 (count (:msg r))))))))
    (System/exit 0)))
