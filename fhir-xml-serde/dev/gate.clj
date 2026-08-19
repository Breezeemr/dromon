(ns gate
  "The acceptance gate: parse -> unparse -> canonical compare, every R4B example."
  (:require [com.breezeehr.fhir-xml :as fx]
            [malli.core :as m]
            [com.breezeehr.fhir-xml-canonical :as canon]
            [clojure.string :as str])
  (:import (java.io File)))

(def corpus
  "The R4B example corpus. Fetch it with:
     curl -sSLO https://hl7.org/fhir/R4B/examples.zip && unzip -q examples.zip -d examples
   then point FHIR_XML_EXAMPLES at the directory."
  (File. (or (System/getenv "FHIR_XML_EXAMPLES") "dev-resources/r4b-examples")))

(def resource-schema
  (memoize
   (fn [type-name]
     (try
       (some-> (requiring-resolve
                (symbol (str "org.hl7.fhir.StructureDefinition." type-name ".v4-3-0") "full-sch"))
               deref)
       (catch Throwable _ nil)))))

(def parser-for  (memoize (fn [t] (fx/parser (resource-schema t) resource-schema))))
(def unparser-for (memoize (fn [t] (fx/unparser (resource-schema t) resource-schema))))

(defn root-type [^String xml]
  (second (re-find #"<([A-Za-z][A-Za-z0-9]*)[\s>]" (str/replace xml #"<\?xml[^>]*\?>" ""))))

(defn check [^File f]
  (let [xml (slurp f)
        t (root-type xml)]
    (try
      (if-not (resource-schema t)
        {:file (.getName f) :type t :status :no-schema}
        (let [data ((parser-for t) xml)
              out ((unparser-for t) data)
              d (canon/diff xml out)]
          (if (empty? d)
            {:file (.getName f) :type t :status :pass
             ;; secondary signal, not the gate: does the typed shape validate?
             :typed-valid? (try (m/validate (resource-schema t)
                                            (fx/decode-typed (resource-schema t) data))
                                (catch Throwable _ false))}
            {:file (.getName f) :type t :status :diff :diff (vec (take 2 d)) :n (count d)})))
      (catch Throwable e
        {:file (.getName f) :type t :status :error
         :error (str (.getSimpleName (class e)) ": " (.getMessage e))}))))

(defn -main [& _]
  (let [files (sort-by #(.getName ^File %) (filter #(.isFile ^File %) (.listFiles corpus)))
        results (mapv check files)
        by (group-by :status results)]
    (println)
    (println "=== GATE:" (count (:pass by)) "/" (count results) "round-trip clean ===")
    (println "    typed-decode validates:" (count (filter :typed-valid? (:pass by))) "/" (count (:pass by)))
    (doseq [[k v] (sort-by (comp - count val) (dissoc by :pass))]
      (println)
      (println k "->" (count v))
      (doseq [r (take 6 v)]
        (println "  " (:file r) (or (:error r) (pr-str (:diff r))))))
    ;; failure shapes, most common first
    (println)
    (println "--- error signatures ---")
    (doseq [[sig n] (->> (concat (:error by) (:diff by))
                         (map #(or (some-> (:error %) (str/replace #"\d+" "N") (subs 0 (min 90 (count (:error %)))))
                                   (str "diff:" (:why (first (:diff %))))))
                         frequencies (sort-by (comp - val)) (take 12))]
      (println (format "%5d  %s" n sig)))
    (System/exit 0)))
