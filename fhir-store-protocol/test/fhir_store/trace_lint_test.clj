(ns fhir-store.trace-lint-test
  "Guards the redaction in `fhir-store.trace` against erosion.

  The redaction lives at the call site, so a namespace that goes back to
  Telemere's own `trace!` silently starts logging whatever the traced form
  returns. Nothing else catches that: the leak produces no error, and a bare
  `trace!` is exactly what the surrounding code used to look like."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private scanned-roots
  "Source trees whose spans wrap store verbs or request handling. Paths are
   relative to this module, and missing ones are skipped so the suite still
   runs from a partial checkout."
  ["src"
   "../fhir-server/src"
   "../fhir-store-xtdb2/src"
   "../fhir-store-mock/src"])

(def ^:private wrapper-source
  "fhir_store/trace.clj expands to Telemere's `trace!` by definition."
  "trace.clj")

(defn- clj-files [root]
  (let [dir (io/file root)]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(str/ends-with? (.getName ^java.io.File %) ".clj"))
           (remove #(= wrapper-source (.getName ^java.io.File %)))))))

(defn- telemere-alias
  "The alias a namespace binds Telemere to, or nil when it does not require it."
  [source]
  (second (re-find #"\[taoensso\.telemere\s+:as\s+([^\s\]]+)\]" source)))

(defn- bare-trace-calls
  "Lines calling Telemere's own `trace!`/`spy!` rather than the redacting one."
  [file]
  (let [source (slurp file)]
    (when-let [alias (telemere-alias source)]
      (let [pattern (re-pattern (str "\\(" (java.util.regex.Pattern/quote alias)
                                     "/(?:trace|spy)!"))]
        (->> (str/split-lines source)
             (keep-indexed (fn [i line]
                             (when (re-find pattern line)
                               (str file ":" (inc i)))))
             seq)))))

(deftest store-and-server-spans-use-the-redacting-trace
  (let [offenders (->> scanned-roots
                       (mapcat clj-files)
                       (mapcat bare-trace-calls)
                       sort)]
    (is (empty? offenders)
        (str "These spans record the traced form's return value, which for a "
             "store verb is a FHIR resource. Use fhir-store.trace/trace!:\n  "
             (str/join "\n  " offenders)))))

(deftest the-lint-actually-looks-at-something
  (testing "a silently empty scan would make the guard above vacuous"
    (let [files (mapcat clj-files scanned-roots)]
      (is (< 10 (count files)))
      (is (some telemere-alias (map slurp files))
          "no scanned file requires Telemere; the alias pattern has drifted"))))
