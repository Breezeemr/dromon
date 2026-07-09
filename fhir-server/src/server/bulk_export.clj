(ns server.bulk-export
  "FHIR Bulk Data Access ($export) MVP: system-level export.

   Implements the async kickoff / status / cancel / file download handshake
   from the Bulk Data Access IG (STU2). This is the Phase B increment of
   docs/proposals/bulk-data-export-and-backend-services.md:

     - kickoff  GET  /:tenant-id/fhir/$export
     - status   GET  /:tenant-id/fhir/$export-status/:job-id
     - cancel   DELETE .../$export-status/:job-id
     - file     GET  /:tenant-id/fhir/$export-file/:job-id/:file-id

   Response-encoding note (critical): the status manifest is application/json
   and the NDJSON files are application/fhir+ndjson, neither of which is what
   muuntaja would negotiate. A Clojure map body carrying an explicit
   Content-Type bypasses muuntaja and then fails to stream (HTTP 500), so
   every response here is PRE-SERIALIZED to a string and returned with an
   explicit Content-Type, deliberately bypassing muuntaja (mirroring the
   default-404 handler in server.core). See the smart-configuration comment in
   server.handlers for the map-body-vs-Content-Type gotcha."
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [fhir-store.protocol :as db]
            [server.bulk-job-store :as bjs]
            [server.handlers :as handlers]
            [taoensso.telemere :as t])
  (:import [com.fasterxml.jackson.datatype.jsr310 JavaTimeModule]
           [com.fasterxml.jackson.databind SerializationFeature]))

(def ^:private json-mapper
  "Jackson mapper that renders java.time values (e.g. meta.lastUpdated
   Instants) as ISO strings rather than epoch arrays, matching the server's
   canonical JSON encoding."
  (doto (json/object-mapper {:modules [(JavaTimeModule.)]})
    (.disable SerializationFeature/WRITE_DATES_AS_TIMESTAMPS)))

(defn- json-str [x]
  (json/write-value-as-string x json-mapper))

;; ---------------------------------------------------------------------------
;; Response helpers (all bodies pre-serialized to strings, explicit CT)
;; ---------------------------------------------------------------------------

(defn- str-response
  ([status content-type body-str]
   (str-response status content-type {} body-str))
  ([status content-type headers body-str]
   {:status status
    :headers (assoc headers "Content-Type" content-type)
    :body body-str}))

(defn- operation-outcome [severity code diagnostics]
  {:resourceType "OperationOutcome"
   :issue [{:severity severity :code code :diagnostics diagnostics}]})

(defn- oo-response [status code diagnostics]
  (str-response status "application/fhir+json"
                (json-str (operation-outcome "error" code diagnostics))))

;; ---------------------------------------------------------------------------
;; URL helpers
;; ---------------------------------------------------------------------------

(defn- forwarded-scheme [req]
  (or (get-in req [:headers "x-forwarded-proto"])
      (some-> (:scheme req) name)
      "https"))

(defn- forwarded-host [req]
  (or (get-in req [:headers "x-forwarded-host"])
      (get-in req [:headers "host"])
      "localhost"))

(defn- absolute-url
  "Build an absolute URL for `path` from the request's scheme/host, honoring
   X-Forwarded-* so poll/download URLs are reachable behind a proxy."
  [req path]
  (str (forwarded-scheme req) "://" (forwarded-host req) path))

(defn- status-url [req tenant-id job-id]
  (absolute-url req (str "/" tenant-id "/fhir/$export-status/" job-id)))

(defn- file-url [req tenant-id job-id file-id]
  (absolute-url req (str "/" tenant-id "/fhir/$export-file/" job-id "/" file-id)))

(defn- request-url [req]
  (absolute-url req (str (:uri req)
                         (when-let [q (not-empty (:query-string req))]
                           (str "?" q)))))

;; ---------------------------------------------------------------------------
;; _outputFormat validation
;; ---------------------------------------------------------------------------

(def ^:private valid-output-formats
  "NDJSON output-format spellings accepted by the Bulk Data IG."
  #{"application/fhir+ndjson" "application/ndjson" "ndjson"})

(defn valid-output-format?
  "True when `fmt` is nil (default) or one of the NDJSON spellings."
  [fmt]
  (or (nil? fmt) (contains? valid-output-formats fmt)))

;; ---------------------------------------------------------------------------
;; Enumeration
;; ---------------------------------------------------------------------------

(def ^:private page-size 1000)

(defn- scan-type
  "Page every resource of `resource-type` via IFHIRStore search, stopping at
   the first short page. MVP enumeration: O(pages), fine for the small
   datasets this MVP targets."
  [store tenant-id resource-type registry]
  (loop [skip 0
         acc  (transient [])]
    (let [results (db/search store tenant-id (keyword resource-type)
                             {:_count page-size :_skip skip}
                             registry)
          acc (reduce conj! acc results)]
      (if (< (count results) page-size)
        (persistent! acc)
        (recur (+ skip page-size) acc)))))

(defn- serialize-type
  "Enumerate one resource type and return an output descriptor with the
   NDJSON payload, or nil when the type has no resources."
  [store tenant-id encode resource-type registry]
  (let [resources (scan-type store tenant-id resource-type registry)]
    (when (seq resources)
      {:type    resource-type
       :file-id (str (random-uuid))
       :count   (count resources)
       :ndjson  (str (->> resources
                          (map (fn [r] (json-str (encode r))))
                          (str/join "\n"))
                     "\n")})))

(defn- run-export!
  "Background worker: enumerate `types`, serialize each to NDJSON, then flip
   the job to :complete (unless it was cancelled). Any failure flips it to
   :error with an OperationOutcome-ish error array."
  [job-store store tenant-id job-id all-registries encoders types]
  (t/trace!
   {:id :bulk/export.run
    :data {:tenant tenant-id :job-id job-id :type-count (count types)}}
   (try
     (let [encode  (partial handlers/encode-resource-by-type encoders)
           outputs (into []
                         (keep (fn [rt]
                                 (when-let [registry (get all-registries rt)]
                                   (serialize-type store tenant-id encode rt registry))))
                         types)
           files   (into {} (map (juxt :file-id :ndjson)) outputs)
           output  (mapv #(select-keys % [:type :file-id :count]) outputs)]
       (bjs/update-job! job-store tenant-id job-id
                        (fn [job]
                          (cond
                            (nil? job) nil
                            (= :cancelled (:status job)) job
                            :else (assoc job
                                         :status :complete
                                         :output output
                                         :files files)))))
     (catch Throwable e
       (bjs/update-job! job-store tenant-id job-id
                        (fn [job]
                          (when job
                            (assoc job
                                   :status :error
                                   :error [{:type "OperationOutcome"
                                            :url (str (operation-outcome
                                                       "error" "exception"
                                                       (or (.getMessage e) "export failed")))}]))))
       (t/error! {:id :bulk/export.failed
                  :data {:tenant tenant-id :job-id job-id}}
                 e)))))

;; ---------------------------------------------------------------------------
;; Manifest builder
;; ---------------------------------------------------------------------------

(defn build-manifest
  "Build the completed-export status manifest for `job`. Output/file URLs are
   made absolute from the polling request so they are reachable by the client
   that received them."
  [req job]
  (let [tenant-id (:tenant job)
        job-id    (:id job)]
    {:transactionTime     (:transaction-time job)
     :request             (:request-url job)
     :requiresAccessToken false
     :output              (mapv (fn [o]
                                  {:type  (:type o)
                                   :count (:count o)
                                   :url   (file-url req tenant-id job-id (:file-id o))})
                                (:output job))
     :error               (or (:error job) [])}))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn kickoff
  "GET /:tenant-id/fhir/$export — system-level export kickoff.

   Fronted by server.auth/wrap-require-auth in routing so a tokenless request
   returns 401 (not the Keto 403). Validates _outputFormat, mints an
   :in-progress job, spawns a virtual thread to enumerate and serialize, and
   returns 202 with an absolute Content-Location status URL and no body."
  [req]
  (let [tenant-id      (-> req :path-params :tenant-id)
        store          (:fhir/store req)
        job-store      (:fhir/bulk-job-store req)
        all-registries (:fhir/all-registries req)
        encoders       (:fhir/resource-encoders req)
        params         (merge (or (:form-params req) {}) (or (:query-params req) {}))
        output-format  (or (get params "_outputFormat") (get params :_outputFormat))
        type-param     (or (get params "_type") (get params :_type))]
    (if-not (valid-output-format? output-format)
      (oo-response 400 "invalid"
                   (str "Unsupported _outputFormat: '" output-format
                        "'. Supported: application/fhir+ndjson."))
      (let [job-id (str (random-uuid))
            types  (if (not-empty type-param)
                     (vec (str/split type-param #","))
                     (vec (keys all-registries)))
            job    {:id               job-id
                    :tenant           tenant-id
                    :kind             :system
                    :params           params
                    :status           :in-progress
                    :transaction-time (str (java.time.Instant/now))
                    :request-url      (request-url req)
                    :output           []
                    :error            []
                    :files            {}}]
        (bjs/put-job! job-store tenant-id job)
        (Thread/startVirtualThread
         ^Runnable (fn []
                     (run-export! job-store store tenant-id job-id
                                  all-registries encoders types)))
        (str-response 202 "application/json"
                      {"Content-Location" (status-url req tenant-id job-id)}
                      "")))))

(defn status
  "GET /:tenant-id/fhir/$export-status/:job-id — poll job status.

   :in-progress -> 202 with X-Progress + Retry-After.
   :complete    -> 200 application/json manifest.
   :error       -> 500 OperationOutcome.
   :cancelled / unknown -> 404."
  [req]
  (let [tenant-id (-> req :path-params :tenant-id)
        job-id    (-> req :path-params :job-id)
        job-store (:fhir/bulk-job-store req)
        job       (bjs/get-job job-store tenant-id job-id)]
    (case (:status job)
      :in-progress
      (str-response 202 "application/json"
                    {"X-Progress"  "in-progress, building files"
                     "Retry-After" "1"}
                    "")

      :complete
      (str-response 200 "application/json" (json-str (build-manifest req job)))

      :error
      (oo-response 500 "exception" "Export failed")

      ;; nil (unknown) and :cancelled both surface as 404: a cancelled job's
      ;; status endpoint no longer exists (Bulk Data IG).
      (oo-response 404 "not-found"
                   (str "Export job " job-id " not found")))))

(defn cancel
  "DELETE /:tenant-id/fhir/$export-status/:job-id — cancel a job (202)."
  [req]
  (let [tenant-id (-> req :path-params :tenant-id)
        job-id    (-> req :path-params :job-id)
        job-store (:fhir/bulk-job-store req)
        job       (bjs/get-job job-store tenant-id job-id)]
    (if job
      (do
        (bjs/update-job! job-store tenant-id job-id
                         (fn [j] (when j (assoc j :status :cancelled))))
        (str-response 202 "application/json"
                      (json-str (operation-outcome
                                 "information" "informational"
                                 (str "Export job " job-id " cancelled")))))
      (oo-response 404 "not-found"
                   (str "Export job " job-id " not found")))))

(defn file
  "GET /:tenant-id/fhir/$export-file/:job-id/:file-id — download one NDJSON
   output file. Public route (requiresAccessToken false in the MVP manifest)."
  [req]
  (let [tenant-id (-> req :path-params :tenant-id)
        job-id    (-> req :path-params :job-id)
        file-id   (-> req :path-params :file-id)
        job-store (:fhir/bulk-job-store req)
        job       (bjs/get-job job-store tenant-id job-id)
        ndjson    (get-in job [:files file-id])]
    (if (and ndjson (= :complete (:status job)))
      (str-response 200 "application/fhir+ndjson" ndjson)
      (oo-response 404 "not-found"
                   (str "Export file " file-id " not found for job " job-id)))))
