(ns server.bulk-export-test
  "Focused unit tests for the Bulk Data Access ($export) MVP: _outputFormat
   validation, the manifest builder, the 401-on-missing-token path, and the
   in-memory kickoff -> status -> file cycle against a fake store."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jsonista.core :as json]
            [fhir-store.protocol :as db]
            [server.bulk-export :as be]
            [server.bulk-job-store :as bjs]
            [server.routing :as routing]))

(def ^:private json->clj
  (partial json/read-value))

;; ---------------------------------------------------------------------------
;; _outputFormat validation
;; ---------------------------------------------------------------------------

(deftest output-format-validation
  (testing "nil (default) and the NDJSON spellings are accepted"
    (is (be/valid-output-format? nil))
    (is (be/valid-output-format? "application/fhir+ndjson"))
    (is (be/valid-output-format? "application/ndjson"))
    (is (be/valid-output-format? "ndjson")))
  (testing "anything else is rejected"
    (is (not (be/valid-output-format? "application/fhir+json")))
    (is (not (be/valid-output-format? "csv")))
    (is (not (be/valid-output-format? "")))))

;; ---------------------------------------------------------------------------
;; Manifest builder
;; ---------------------------------------------------------------------------

(deftest manifest-has-the-five-required-keys-with-absolute-urls
  (let [job {:id "job-1"
             :tenant "default"
             :status :complete
             :transaction-time "2026-07-09T00:00:00Z"
             :request-url "https://fhir.local:3001/default/fhir/$export"
             :output [{:type "Patient" :file-id "file-a" :count 2}]
             :error []}
        req {:headers {"host" "fhir.local:3001"} :scheme :https}
        manifest (be/build-manifest req job)]
    (testing "all five keys the bulk validator checks are present"
      (doseq [k [:transactionTime :request :requiresAccessToken :output :error]]
        (is (contains? manifest k) (str "missing manifest key " k))))
    (is (= "2026-07-09T00:00:00Z" (:transactionTime manifest)))
    (is (false? (:requiresAccessToken manifest)))
    (testing "each output entry has type and an absolute url"
      (let [entry (first (:output manifest))]
        (is (= "Patient" (:type entry)))
        (is (= 2 (:count entry)))
        (is (= "https://fhir.local:3001/default/fhir/$export-file/job-1/file-a"
               (:url entry)))))))

;; ---------------------------------------------------------------------------
;; 401 on a tokenless kickoff (routing composition)
;; ---------------------------------------------------------------------------

(defn- system-route-handlers
  "Build the system routes and index the $export handlers by path/method."
  [all-registries encoders]
  (let [routes  (routing/build-system-routes [] all-registries nil encoders)
        by-path (into {} (map (juxt first second)) routes)]
    {:kickoff (get-in by-path ["/:tenant-id/fhir/$export" :get])
     :status  (get-in by-path ["/:tenant-id/fhir/$export-status/:job-id" :get])
     :cancel  (get-in by-path ["/:tenant-id/fhir/$export-status/:job-id" :delete])
     :file    (get-in by-path ["/:tenant-id/fhir/$export-file/:job-id/:file-id" :get])
     :status-route (get by-path "/:tenant-id/fhir/$export-status/:job-id")
     :export-route (get by-path "/:tenant-id/fhir/$export")
     :file-route (get by-path "/:tenant-id/fhir/$export-file/:job-id/:file-id")}))

(deftest kickoff-without-token-returns-401
  (let [{:keys [kickoff export-route]} (system-route-handlers {"Patient" :reg} {})
        resp (kickoff {:request-method :get
                       :path-params {:tenant-id "default"}
                       :headers {}})]
    (is (= 401 (:status resp)) "tokenless kickoff must be 401, not the Keto 403")
    (testing "the kickoff route is public so the Keto 403 does not pre-empt the 401"
      (is (true? (:public? export-route))))))

(deftest route-data-carries-keto-relation-and-public-flags
  (let [{:keys [status-route file-route]} (system-route-handlers {} {})]
    (testing "status/cancel gate on the system read tuple"
      (is (= "read" (:keto/relation status-route)))
      (is (not (:public? status-route))))
    (testing "file download is public in the MVP"
      (is (true? (:public? file-route))))))

;; ---------------------------------------------------------------------------
;; kickoff -> status -> file cycle against a fake store
;; ---------------------------------------------------------------------------

(def ^:private patients
  [{:resourceType "Patient" :id "123" :name [{:family "Smith"}]}
   {:resourceType "Patient" :id "bulk-export-2" :name [{:family "Doe"}]}])

(defn- fake-store
  "Minimal IFHIRStore returning the two Patients on the first page and empty
   for everything else, so scan-type terminates immediately."
  []
  (reify db/IFHIRStore
    (search [_ _tenant resource-type params _registry]
      (if (and (= :Patient resource-type)
               (zero? (long (:_skip params 0))))
        patients
        []))))

(defn- await-status
  "Poll the job status until it stops being :in-progress, or throw after ~5s."
  [job-store tenant-id job-id]
  (loop [tries 0]
    (let [st (:status (bjs/get-job job-store tenant-id job-id))]
      (cond
        (not= :in-progress st) st
        (> tries 500) (throw (ex-info "export did not complete" {:status st}))
        :else (do (Thread/sleep 10) (recur (inc tries)))))))

(deftest kickoff-status-file-happy-path
  (let [job-store (bjs/create-store)
        {:keys [kickoff status file]} (system-route-handlers {"Patient" :reg} {})
        base-req {:path-params {:tenant-id "default"}
                  :headers {"host" "fhir.local:3001"}
                  :scheme :https
                  :identity {:sub "tester"}
                  :fhir/store (fake-store)
                  :fhir/bulk-job-store job-store}
        kick-resp (kickoff (assoc base-req :request-method :get :uri "/default/fhir/$export"))]

    (testing "kickoff returns 202 with an absolute Content-Location"
      (is (= 202 (:status kick-resp)))
      (let [loc (get-in kick-resp [:headers "Content-Location"])]
        (is (str/starts-with? loc "https://fhir.local:3001/default/fhir/$export-status/"))))

    (let [job-id (last (str/split (get-in kick-resp [:headers "Content-Location"]) #"/"))]
      (testing "the background worker completes the job"
        (is (= :complete (await-status job-store "default" job-id))))

      (let [status-resp (status (assoc base-req :request-method :get
                                       :path-params {:tenant-id "default" :job-id job-id}))]
        (testing "status returns 200 application/json manifest"
          (is (= 200 (:status status-resp)))
          (is (= "application/json" (get-in status-resp [:headers "Content-Type"])))
          (let [manifest (json->clj (:body status-resp))]
            (is (= false (get manifest "requiresAccessToken")))
            (is (seq (get manifest "output")) "output must be non-empty")
            (let [pt (first (filter #(= "Patient" (get % "type")) (get manifest "output")))]
              (is (some? pt))
              (is (= 2 (get pt "count")))
              (testing "the Patient output file has >= 2 distinct ids"
                (let [file-id (last (str/split (get pt "url") #"/"))
                      file-resp (file (assoc base-req :request-method :get
                                             :path-params {:tenant-id "default"
                                                           :job-id job-id
                                                           :file-id file-id}))]
                  (is (= 200 (:status file-resp)))
                  (is (= "application/fhir+ndjson"
                         (get-in file-resp [:headers "Content-Type"])))
                  (let [lines (remove str/blank? (str/split-lines (:body file-resp)))
                        ids (set (map #(get (json->clj %) "id") lines))]
                    (is (= 2 (count lines)))
                    (is (= #{"123" "bulk-export-2"} ids))))))))))))

;; ---------------------------------------------------------------------------
;; status/cancel edge cases
;; ---------------------------------------------------------------------------

(deftest status-in-progress-returns-202-with-progress-headers
  (let [job-store (bjs/create-store)]
    (bjs/put-job! job-store "default" {:id "j" :tenant "default" :status :in-progress})
    (let [resp (be/status {:path-params {:tenant-id "default" :job-id "j"}
                           :headers {"host" "h"}
                           :fhir/bulk-job-store job-store})]
      (is (= 202 (:status resp)))
      (is (= "1" (get-in resp [:headers "Retry-After"])))
      (is (some? (get-in resp [:headers "X-Progress"]))))))

(deftest status-unknown-job-is-404
  (let [job-store (bjs/create-store)
        resp (be/status {:path-params {:tenant-id "default" :job-id "nope"}
                         :headers {"host" "h"}
                         :fhir/bulk-job-store job-store})]
    (is (= 404 (:status resp)))))

(deftest cancel-flips-to-cancelled-and-status-then-404s
  (let [job-store (bjs/create-store)]
    (bjs/put-job! job-store "default" {:id "j" :tenant "default" :status :in-progress})
    (let [cancel-resp (be/cancel {:path-params {:tenant-id "default" :job-id "j"}
                                  :headers {"host" "h"}
                                  :fhir/bulk-job-store job-store})]
      (is (= 202 (:status cancel-resp)))
      (is (= :cancelled (:status (bjs/get-job job-store "default" "j"))))
      (testing "a cancelled job's status endpoint 404s"
        (let [status-resp (be/status {:path-params {:tenant-id "default" :job-id "j"}
                                      :headers {"host" "h"}
                                      :fhir/bulk-job-store job-store})]
          (is (= 404 (:status status-resp))))))))
