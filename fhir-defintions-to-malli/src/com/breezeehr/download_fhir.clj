(ns com.breezeehr.download-fhir
  (:require [hato.client :as hc]
            [promesa.core :as p]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell])
  (:import (java.util.zip ZipInputStream ZipEntry)
           (java.nio.file Paths Files CopyOption Path StandardCopyOption)
           (java.nio.file.attribute FileAttribute)
           (java.io InputStream)))

(def version->alias
  {"4.3.0" "R4B"
   "4.0.1" "R4"
   "3.0.2" "STU3"
   "1.0.2" "DSTU2"
   "5.0.0" "R5"})
(def aliases (into #{}
                   (map val version->alias)))

(defn fhir-downloads-url [version]
  (let [v (or (get version->alias version) (aliases version))]
    (str "http://hl7.org/fhir/" v "/definitions.json.zip")))

(defn copy-zip-stream [zis base]
  (loop [zis ^ZipInputStream zis]
    (when-some [ze ^ZipEntry (.getNextEntry zis)]
      (let [p (.resolve base (.getName ze))]
        (prn :Start (.getName ze) (.getSize ze))
        (if (.isDirectory ze)
          (Files/createDirectories
           p
           (make-array FileAttribute 0))
          (do (Files/createDirectories
               (.getParent p)
               (make-array FileAttribute 0))
              (Files/copy zis p (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))))
        (prn :finish (.getName ze) (.getSize ze))
        (recur zis)))))

(defn- do-download [req base]
  (-> (p/chain req
               (fn [{is :body}]
                 (ZipInputStream. is))
               (fn [zis]
                 (try (copy-zip-stream zis base)
                      (finally (.close zis)))))
      (p/finally (fn [s e] (p/cancel! req)))
      deref))

(defn download [version & {:keys [force?] :or {force? false}}]
  (let [base (Paths/get "scratch" (make-array String 0))
        marker (io/file "scratch" "definitions.json" "profiles-types.json")]
    (Files/createDirectories base (make-array FileAttribute 0))
    (if (and (not force?) (.exists marker))
      (println "FHIR definitions for" version "already downloaded, skipping.")
      (let [req (hc/request {:method :get :url (fhir-downloads-url version) :as :stream
                             :async? true})]
        (do-download req base)))))

(def target-dir "scratch/us-core")

(defn download-and-extract-uscore!  [version & {:keys [force?] :or {force? false}}]
  (let [package-url (str "https://hl7.org/fhir/us/core/" version "/package.tgz")
        out-dir (io/file target-dir version)
        package-dir (io/file out-dir "package")
        temp-tgz (io/file out-dir "package.tgz")]
    (if (and (not force?) (.isDirectory package-dir))
      (println "US Core" version "already downloaded, skipping.")
      (do
        (when-not (.exists out-dir)
          (.mkdirs out-dir))

        (println "Downloading US Core version" version "to" (.getAbsolutePath temp-tgz) "...")
        (with-open [in (:body (hc/get package-url {:as :stream}))
                    out (io/output-stream temp-tgz)]
          (io/copy in out))

        (println "Extracting" (.getAbsolutePath temp-tgz) "...")
        (let [result (shell/sh "tar" "-xzf" (.getAbsolutePath temp-tgz) "-C" (.getAbsolutePath out-dir))]
          (if (zero? (:exit result))
            (do
              (println "Extraction successful.")
              (.delete temp-tgz))
            (println "Error extracting:" (:err result))))
        (println "Done.")))))

(def sdc-target-dir "scratch/sdc")

(defn download-and-extract-sdc! [version & {:keys [force?] :or {force? false}}]
  (let [package-url (str "https://hl7.org/fhir/uv/sdc/" version "/full-ig.zip")
        out-dir (io/file sdc-target-dir version)
        site-dir (io/file out-dir "site")
        temp-zip (io/file out-dir "full-ig.zip")]
    (if (and (not force?) (.isDirectory site-dir))
      (println "SDC" version "already downloaded, skipping.")
      (do
        (when-not (.exists out-dir)
          (.mkdirs out-dir))

        (println "Downloading SDC version" version "to" (.getAbsolutePath temp-zip) "...")
        (with-open [in (:body (hc/get package-url {:as :stream}))
                    out (io/output-stream temp-zip)]
          (io/copy in out))

        (println "Extracting" (.getAbsolutePath temp-zip) "...")
        (let [result (shell/sh "unzip" "-o" (.getAbsolutePath temp-zip)
                               "-d" (.getAbsolutePath out-dir))]
          (if (zero? (:exit result))
            (do
              (println "Extraction successful.")
              (.delete temp-zip))
            (println "Error extracting:" (:err result))))
        (println "Done.")))))

(def xver-target-dir "scratch/xver")

(defn download-and-extract-xver! [version & {:keys [force?] :or {force? false}}]
  (let [package-url (str "https://hl7.org/fhir/uv/xver-r5.r4/" version "/package.tgz")
        out-dir (io/file xver-target-dir version)
        package-dir (io/file out-dir "package")
        temp-tgz (io/file out-dir "package.tgz")]
    (if (and (not force?) (.isDirectory package-dir))
      (println "xver-r5.r4" version "already downloaded, skipping.")
      (do
        (when-not (.exists out-dir)
          (.mkdirs out-dir))

        (println "Downloading xver-r5.r4 version" version "to" (.getAbsolutePath temp-tgz) "...")
        (with-open [in (:body (hc/get package-url {:as :stream}))
                    out (io/output-stream temp-tgz)]
          (io/copy in out))

        (println "Extracting" (.getAbsolutePath temp-tgz) "...")
        (let [result (shell/sh "tar" "-xzf" (.getAbsolutePath temp-tgz) "-C" (.getAbsolutePath out-dir))]
          (if (zero? (:exit result))
            (do
              (println "Extraction successful.")
              (.delete temp-tgz))
            (println "Error extracting:" (:err result))))
        (println "Done.")))))

(def fhir-extensions-target-dir "scratch/fhir-extensions")

(defn download-and-extract-fhir-extensions! [version & {:keys [force?] :or {force? false}}]
  (let [package-url (str "https://packages.fhir.org/hl7.fhir.uv.extensions.r4/" version)
        out-dir (io/file fhir-extensions-target-dir version)
        package-dir (io/file out-dir "package")
        temp-tgz (io/file out-dir "package.tgz")]
    (if (and (not force?) (.isDirectory package-dir))
      (println "FHIR Extensions IG" version "already downloaded, skipping.")
      (do
        (when-not (.exists out-dir)
          (.mkdirs out-dir))

        (println "Downloading FHIR Extensions IG version" version "to" (.getAbsolutePath temp-tgz) "...")
        (with-open [in (:body (hc/get package-url {:as :stream}))
                    out (io/output-stream temp-tgz)]
          (io/copy in out))

        (println "Extracting" (.getAbsolutePath temp-tgz) "...")
        (let [result (shell/sh "tar" "-xzf" (.getAbsolutePath temp-tgz) "-C" (.getAbsolutePath out-dir))]
          (if (zero? (:exit result))
            (do
              (println "Extraction successful.")
              (.delete temp-tgz))
            (println "Error extracting:" (:err result))))
        (println "Done.")))))

(comment
  (download "4.3.0")

  (download-and-extract-uscore! "STU8.0.1")

  (download-and-extract-sdc! "STU4")

  (download-and-extract-xver! "0.1.0")

  (download-and-extract-fhir-extensions! "5.3.0-ballot-tc1"))