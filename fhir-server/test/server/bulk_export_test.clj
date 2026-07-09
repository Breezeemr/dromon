(ns server.bulk-export-test
  "Focused unit tests for the Bulk Data Access ($export) MVP in its lazy
   stream-at-download form: _outputFormat validation, the manifest builder, the
   401/403 authz paths, and the kickoff -> status -> file cycle against a fake
   store. Kickoff pins a store basis and builds the manifest WITHOUT producing
   any resource bytes or touching disk; $export-file returns a Ring
   StreamableResponseBody that streams NDJSON from the store as of the pinned
   basis."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jsonista.core :as json]
            [malli.core :as m]
            [reitit.core :as reitit]
            [reitit.ring :as ring]
            [ring.core.protocols :as ring-protocols]
            [fhir-store.protocol :as db]
            [server.bulk-export :as be]
            [server.bulk-job-store :as bjs]
            [server.compartment :as compartment]
            [server.handlers :as handlers]
            [server.routing :as routing])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]))

(def ^:private authorized
  "Per-request override that grants the caller the 'system' read tuple so the
   bulk handlers' inline Keto check (server.bulk-export/authorize-system) passes
   without a live Keto server."
  {:fhir/system-authorized? (constantly true)})

(def ^:private unauthorized
  "Per-request override denying the 'system' read tuple (authenticated caller
   without the system grant)."
  {:fhir/system-authorized? (constantly false)})

(def ^:private json->clj
  (partial json/read-value))

(defn- stream-body->string
  "Drive a Ring StreamableResponseBody to a UTF-8 string, the way Jetty would."
  [body]
  (let [baos (ByteArrayOutputStream.)]
    (ring-protocols/write-body-to-stream body {} baos)
    (String. (.toByteArray baos) StandardCharsets/UTF_8)))

(def ^:private fixed-basis
  {:tx-id 1 :system-time (java.time.Instant/parse "2026-07-09T00:00:00Z")})

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
    (is (true? (:requiresAccessToken manifest)))
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
    (testing "file download is public + auth-fronted (401 tokenless), gating on
              the system tuple inline rather than via the Keto middleware"
      (is (true? (:public? file-route)))
      (is (fn? (:get file-route)))
      (is (nil? (:keto/relation file-route))))))

(deftest file-tokenless-returns-401
  (let [{:keys [file]} (system-route-handlers {} {})
        resp (file {:request-method :get
                    :path-params {:tenant-id "default" :job-id "j" :file-id "f"}
                    :headers {}})]
    (is (= 401 (:status resp))
        "tokenless file download must be 401 (Inferno wants 400/401), not 403")))

(deftest file-authenticated-without-system-tuple-returns-403
  (let [{:keys [file]} (system-route-handlers {} {})
        resp (file (merge {:request-method :get
                           :path-params {:tenant-id "default" :job-id "j" :file-id "f"}
                           :headers {}
                           :identity {:sub "tester"}
                           :fhir/bulk-job-store (bjs/create-store)}
                          unauthorized))]
    (is (= 403 (:status resp))
        "authenticated caller without the system tuple must be 403")))

(deftest kickoff-authz-gap-closed-403-without-system-tuple
  (testing "an authenticated token without the system read tuple cannot start a
            full-tenant export at any level (system/patient/group); the authz
            check short-circuits before any store access"
    (doseq [handler [be/kickoff be/patient-export be/group-export]]
      (let [resp (handler (merge {:request-method :get
                                  :path-params {:tenant-id "default" :id "g1"}
                                  :headers {"host" "h"}
                                  :scheme :https
                                  :identity {:sub "no-grant"}
                                  :fhir/bulk-job-store (bjs/create-store)}
                                 unauthorized))]
        (is (= 403 (:status resp)))))))

(deftest patient-and-group-kickoff-routes-are-public-and-auth-fronted
  (let [routes  (routing/build-system-routes [] {"Patient" :reg} nil {})
        by-path (into {} (map (juxt first second)) routes)
        patient-route (get by-path "/:tenant-id/fhir/Patient/$export")
        group-route   (get by-path "/:tenant-id/fhir/Group/:id/$export")]
    (testing "patient-level kickoff route exists, is public, and has a GET handler"
      (is (some? patient-route))
      (is (true? (:public? patient-route)))
      (is (fn? (:get patient-route))))
    (testing "group-level kickoff route exists, is public, and has a GET handler"
      (is (some? group-route))
      (is (true? (:public? group-route)))
      (is (fn? (:get group-route))))
    (testing "tokenless patient kickoff returns 401, not the Keto 403"
      (is (= 401 (:status ((:get patient-route)
                           {:request-method :get
                            :path-params {:tenant-id "default"}
                            :headers {}})))))))

(deftest kickoff-operations-route-ahead-of-resource-wildcards
  ;; The $export operation paths must beat the Patient /:id read wildcard and
  ;; the Group /:id read wildcard under {:conflicts nil} (system routes first).
  (let [schemas [(m/schema [:map {:resourceType "Patient"
                                  :fhir/interactions {:read {}}
                                  :fhir/handlers {:read 'clojure.core/identity}}
                            [:id :string]])
                 (m/schema [:map {:resourceType "Group"
                                  :fhir/interactions {:read {}}
                                  :fhir/handlers {:read 'clojure.core/identity}}
                            [:id :string]])]
        all-registries (routing/collect-registries schemas)
        routes (into (routing/build-system-routes schemas all-registries nil nil)
                     (routing/build-resource-routes schemas nil nil))
        tmpl   (fn [path]
                 (-> (ring/router routes {:conflicts nil})
                     (reitit/match-by-path path)
                     :template))]
    (is (= "/:tenant-id/fhir/Patient/$export" (tmpl "/t/fhir/Patient/$export")))
    (is (= "/:tenant-id/fhir/Group/:id/$export" (tmpl "/t/fhir/Group/g1/$export")))
    (testing "ordinary reads still resolve to the resource tree"
      (is (= "/:tenant-id/fhir/Patient/:id" (tmpl "/t/fhir/Patient/123"))))))

;; ---------------------------------------------------------------------------
;; Fake stores (implement only the bulk-export surface of IFHIRStore)
;; ---------------------------------------------------------------------------

(def ^:private patients
  [{:resourceType "Patient" :id "123" :name [{:family "Smith"}]}
   {:resourceType "Patient" :id "bulk-export-2" :name [{:family "Doe"}]}])

(defn- fake-store
  "Minimal IFHIRStore snapshotting the two Patients: count-as-of and
   scan-type-as-of answer for :Patient, everything else is empty."
  []
  (reify db/IFHIRStore
    (current-basis [_ _tenant] fixed-basis)
    (count-as-of [_ _tenant resource-type _basis]
      (if (= :Patient resource-type) (count patients) 0))
    (scan-type-as-of [_ _tenant resource-type _basis]
      (if (= :Patient resource-type) patients []))
    (read-resource [_ _tenant _rt _id] nil)
    (search [_ _tenant _rt _params _registry] [])))

(defn- reduce-only-reducible
  "An IReduceInit that ONLY supports reduce — no seq/first/count — so a consumer
   that tries to fully realize it (via seq/doall/map/doseq) throws. Proves the
   export STREAMS via reduce rather than materializing the whole type."
  [coll]
  (reify clojure.lang.IReduceInit
    (reduce [_ f init] (reduce f init coll))))

(defn- reduce-only-store
  "Like fake-store, but scan-type-as-of hands back a reduce-only reducible."
  []
  (reify db/IFHIRStore
    (current-basis [_ _tenant] fixed-basis)
    (count-as-of [_ _tenant resource-type _basis]
      (if (= :Patient resource-type) (count patients) 0))
    (scan-type-as-of [_ _tenant resource-type _basis]
      (reduce-only-reducible (if (= :Patient resource-type) patients [])))
    (read-resource [_ _tenant _rt _id] nil)
    (search [_ _tenant _rt _params _registry] [])))

(defn- fake-search-store
  "IFHIRStore over static `data` ({type-string [resources]}). search honors the
   owner `_id` filter, the synthetic `_compartment` union param (matched against
   :subject references), and generic equality on any non-underscore string
   search param (so Group member reads work). read-resource returns the first
   row of a type whose :id matches. The as-of snapshot methods (current-basis /
   count-as-of / scan-type-as-of) return the CURRENT static data unfiltered —
   the export layer applies compartment/_typeFilter/_since/dedup while
   consuming."
  [data]
  (reify db/IFHIRStore
    (search [_ _tenant rt params _registry]
      (let [rows     (get data (name rt) [])
            id       (get params "_id")
            comp-ref (get params compartment/compartment-search-param)]
        (into []
              (filter (fn [r]
                        (and (or (nil? id) (= (:id r) id))
                             (or (nil? comp-ref)
                                 (let [s (:subject r)]
                                   (some #(= (:reference %) comp-ref)
                                         (if (sequential? s) s [s]))))
                             (every? (fn [[k v]]
                                       (if (and (string? k) (not (str/starts-with? k "_")))
                                         (= (str (get r (keyword k))) v)
                                         true))
                                     params))))
              rows)))
    (read-resource [_ _tenant rt id]
      (first (filter #(= (:id %) id) (get data (name rt) []))))
    (current-basis [_ _tenant] fixed-basis)
    (count-as-of [_ _tenant rt _basis]
      (count (get data (name rt) [])))
    (scan-type-as-of [_ _tenant rt _basis]
      (get data (name rt) []))))

(def ^:private obs-registry
  {"subject" {:type "reference" :columns [{:col "subject"}]}})

;; ---------------------------------------------------------------------------
;; kickoff -> status -> file cycle against a fake store
;; ---------------------------------------------------------------------------

(defn- content-location-job-id [resp]
  (last (str/split (get-in resp [:headers "Content-Location"]) #"/")))

(deftest kickoff-status-file-happy-path
  (let [job-store (bjs/create-store)
        {:keys [kickoff status file]} (system-route-handlers {"Patient" :reg} {})
        base-req (merge {:path-params {:tenant-id "default"}
                         :headers {"host" "fhir.local:3001"}
                         :scheme :https
                         :identity {:sub "tester"}
                         :fhir/store (fake-store)
                         :fhir/bulk-job-store job-store}
                        authorized)
        kick-resp (kickoff (assoc base-req :request-method :get :uri "/default/fhir/$export"))]

    (testing "kickoff returns 202 with an absolute Content-Location"
      (is (= 202 (:status kick-resp)))
      (let [loc (get-in kick-resp [:headers "Content-Location"])]
        (is (str/starts-with? loc "https://fhir.local:3001/default/fhir/$export-status/"))))

    (let [job-id (content-location-job-id kick-resp)]
      (testing "kickoff pins a basis and completes synchronously (no worker)"
        (let [job (bjs/get-job job-store "default" job-id)]
          (is (= :complete (:status job)))
          (is (some? (:basis job)))
          (is (nil? (:temp-dir job)) "nothing is spooled to disk")))

      (let [status-resp (status (assoc base-req :request-method :get
                                       :path-params {:tenant-id "default" :job-id job-id}))]
        (testing "status returns 200 application/json manifest (pre-serialized string)"
          (is (= 200 (:status status-resp)))
          (is (= "application/json" (get-in status-resp [:headers "Content-Type"])))
          (is (string? (:body status-resp)))
          (let [manifest (json->clj (:body status-resp))]
            (is (= true (get manifest "requiresAccessToken")))
            (is (= "2026-07-09T00:00:00Z" (get manifest "transactionTime")))
            (is (seq (get manifest "output")) "output must be non-empty")
            (let [pt (first (filter #(= "Patient" (get % "type")) (get manifest "output")))]
              (is (some? pt))
              (is (= 2 (get pt "count")))
              (testing "the Patient output streams >= 2 distinct ids as a StreamableResponseBody"
                (let [file-id (last (str/split (get pt "url") #"/"))
                      file-resp (file (assoc base-req :request-method :get
                                             :path-params {:tenant-id "default"
                                                           :job-id job-id
                                                           :file-id file-id}))]
                  (is (= 200 (:status file-resp)))
                  (is (= "application/fhir+ndjson"
                         (get-in file-resp [:headers "Content-Type"])))
                  (testing "the body is a Ring stream, not a heap string or a File"
                    (is (satisfies? ring-protocols/StreamableResponseBody (:body file-resp)))
                    (is (not (string? (:body file-resp))))
                    (is (not (instance? java.io.File (:body file-resp)))))
                  (let [lines (remove str/blank? (str/split-lines (stream-body->string (:body file-resp))))
                        ids (set (map #(get (json->clj %) "id") lines))]
                    (is (= 2 (count lines)))
                    (is (= #{"123" "bulk-export-2"} ids)))
                  (testing "the concurrency slot is released after the stream drains"
                    (is (zero? (bjs/active-stream-count job-store)))))))))))))

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

;; ---------------------------------------------------------------------------
;; Parameter parsing (_type, _since, _typeFilter)
;; ---------------------------------------------------------------------------

(deftest type-filter-parsing
  (let [parse @#'be/parse-type-filters]
    (testing "a single ResourceType?query spec"
      (is (= {"Patient" [{"gender" "female"}]}
             (parse {"_typeFilter" "Patient?gender=female"}))))
    (testing "repeated _typeFilter values union per type"
      (is (= {"Patient" [{"gender" "female"} {"active" "true"}]}
             (parse {"_typeFilter" ["Patient?gender=female" "Patient?active=true"]}))))
    (testing "multi-param query strings split on &"
      (is (= {"Observation" [{"category" "vital-signs" "status" "final"}]}
             (parse {"_typeFilter" "Observation?category=vital-signs&status=final"}))))
    (testing "malformed specs (no ?) are ignored, nothing when absent"
      (is (= {} (parse {"_typeFilter" "Patient"})))
      (is (= {} (parse {}))))))

(deftest since-parsing-and-filtering
  (let [parse  @#'be/parse-since
        after? @#'be/after-since?
        t0     (java.time.Instant/parse "2026-01-01T00:00:00Z")]
    (is (= t0 (parse {"_since" "2026-01-01T00:00:00Z"})))
    (testing "an unparseable _since degrades to nil (no filter)"
      (is (nil? (parse {"_since" "not-an-instant"}))))
    (testing "resources at/after _since pass; older ones are dropped"
      (is (after? t0 {:meta {:lastUpdated (java.time.Instant/parse "2026-06-01T00:00:00Z")}}))
      (is (not (after? t0 {:meta {:lastUpdated (java.time.Instant/parse "2025-06-01T00:00:00Z")}}))))
    (testing "a missing _since or missing lastUpdated includes the resource"
      (is (after? nil {:meta {:lastUpdated (java.time.Instant/parse "2000-01-01T00:00:00Z")}}))
      (is (after? t0 {:id "no-meta"})))))

(deftest requested-types-resolution
  (let [resolve @#'be/requested-types
        regs    {"Patient" :r "Observation" :r "Location" :r}]
    (testing "_type narrows to the listed types for any kind"
      (is (= ["Patient" "Observation"]
             (resolve :system {"_type" "Patient,Observation"} regs))))
    (testing ":system defaults to every registered type"
      (is (= #{"Patient" "Observation" "Location"}
             (set (resolve :system {} regs)))))
    (testing ":patient/:group default to registered Patient-compartment members
              (Patient + Observation), excluding non-members like Location"
      (is (= #{"Patient" "Observation"} (set (resolve :patient {} regs))))
      (is (= #{"Patient" "Observation"} (set (resolve :group {} regs)))))))

;; ---------------------------------------------------------------------------
;; Snapshot enumeration + streaming filters (collect-type)
;; ---------------------------------------------------------------------------

(deftest group-member-patient-resolution
  (let [group {:resourceType "Group" :id "g1"
               :member [{:entity {:reference "Patient/p1"}}
                        {:entity {:reference "Patient/p2"}}
                        {:entity {:reference "Patient/p1"}}      ; duplicate ref
                        {:entity {:reference "Practitioner/x"}}  ; non-Patient
                        {:period {:start "2020"}}]}              ; no entity
        store   (fake-search-store {"Group" [group]})
        resolve @#'be/group-patient-ids]
    (testing "only distinct Patient references are resolved, in order"
      (is (= ["p1" "p2"] (resolve store "default" "g1"))))
    (testing "a missing Group yields no patients"
      (is (= [] (resolve store "default" "nope"))))))

(deftest collect-type-confines-group-and-patient-to-compartments
  (let [store   (fake-search-store
                 {"Patient"     [{:resourceType "Patient" :id "p1"}
                                 {:resourceType "Patient" :id "p2"}
                                 {:resourceType "Patient" :id "p3"}]
                  "Observation" [{:resourceType "Observation" :id "o1" :subject {:reference "Patient/p1"}}
                                 {:resourceType "Observation" :id "o2" :subject {:reference "Patient/p2"}}
                                 {:resourceType "Observation" :id "o3" :subject {:reference "Patient/p3"}}]})
        collect @#'be/collect-type
        basis   (db/current-basis store "default")
        owners  #{"p1" "p2"}]
    (testing "owner Patient rows are confined to the subject set by id"
      (is (= #{"p1" "p2"}
             (set (map :id (collect store "default" basis :group owners "Patient" :reg [{}] nil))))))
    (testing "member rows are confined to the subject patients' compartments"
      (is (= #{"o1" "o2"}
             (set (map :id (collect store "default" basis :group owners "Observation" obs-registry [{}] nil))))))
    (testing ":system ignores the subject set and scans the whole type"
      (is (= #{"o1" "o2" "o3"}
             (set (map :id (collect store "default" basis :system nil "Observation" obs-registry [{}] nil))))))))

(deftest collect-type-applies-type-filter-and-since
  (let [store   (fake-search-store
                 {"Patient" [{:resourceType "Patient" :id "f" :gender "female"
                              :meta {:lastUpdated (java.time.Instant/parse "2026-06-01T00:00:00Z")}}
                             {:resourceType "Patient" :id "m" :gender "male"
                              :meta {:lastUpdated (java.time.Instant/parse "2026-06-01T00:00:00Z")}}
                             {:resourceType "Patient" :id "old-f" :gender "female"
                              :meta {:lastUpdated (java.time.Instant/parse "2025-06-01T00:00:00Z")}}]})
        collect @#'be/collect-type
        basis   (db/current-basis store "default")
        t0      (java.time.Instant/parse "2026-01-01T00:00:00Z")]
    (testing "_typeFilter narrows the streamed set (in-memory equality)"
      (is (= #{"f" "old-f"}
             (set (map :id (collect store "default" basis :system nil "Patient" :reg
                                    [{"gender" "female"}] nil))))))
    (testing "_since post-filters the type-filtered set"
      (is (= #{"f"}
             (set (map :id (collect store "default" basis :system nil "Patient" :reg
                                    [{"gender" "female"}] t0))))))
    (testing "multiple type-filters union, deduped by id"
      (is (= #{"f" "m" "old-f"}
             (set (map :id (collect store "default" basis :system nil "Patient" :reg
                                    [{"gender" "female"} {"gender" "male"}] nil))))))))

;; ---------------------------------------------------------------------------
;; Patient- and group-level kickoff (end-to-end against a fake store)
;; ---------------------------------------------------------------------------

(defn- stream-output-ids
  "Kickoff `resp`'s job, download its Patient output file via `file`, and return
   the set of streamed resource ids."
  [file base-req job-store tenant-id job-id]
  (let [job     (bjs/get-job job-store tenant-id job-id)
        pt      (first (filter #(= "Patient" (:type %)) (:output job)))
        f-resp  (file (assoc base-req :request-method :get
                             :path-params {:tenant-id tenant-id
                                           :job-id job-id :file-id (:file-id pt)}))
        ndjson  (stream-body->string (:body f-resp))]
    (set (map #(get (json->clj %) "id")
              (remove str/blank? (str/split-lines ndjson))))))

(deftest group-export-404-when-group-missing
  (let [store (fake-search-store {})
        resp  (be/group-export (merge {:path-params {:tenant-id "default" :id "nope"}
                                       :headers {"host" "h"}
                                       :scheme :https
                                       :identity {:sub "t"}
                                       :fhir/store store
                                       :fhir/bulk-job-store (bjs/create-store)}
                                      authorized))]
    (is (= 404 (:status resp)))))

(deftest group-export-happy-path-confines-output-to-members
  (let [group {:resourceType "Group" :id "g1"
               :member [{:entity {:reference "Patient/p1"}}
                        {:entity {:reference "Patient/p2"}}]}
        store (fake-search-store
               {"Group"   [group]
                "Patient" [{:resourceType "Patient" :id "p1"}
                           {:resourceType "Patient" :id "p2"}
                           {:resourceType "Patient" :id "p3"}]})
        job-store (bjs/create-store)
        {:keys [file]} (system-route-handlers {"Patient" :reg} {})
        base  (merge {:path-params {:tenant-id "default" :id "g1"}
                      :uri "/default/fhir/Group/g1/$export"
                      :request-method :get
                      :headers {"host" "fhir.local:3001"}
                      :scheme :https
                      :identity {:sub "t"}
                      :fhir/store store
                      :fhir/bulk-job-store job-store
                      :fhir/all-registries {"Patient" :reg}
                      :fhir/resource-encoders {}}
                     authorized)
        resp  (be/group-export base)]
    (is (= 202 (:status resp)))
    (let [job-id (content-location-job-id resp)]
      (is (= :complete (:status (bjs/get-job job-store "default" job-id))))
      (is (= #{"p1" "p2"} (stream-output-ids file base job-store "default" job-id))
          "output is confined to the group's member patients"))))

(deftest patient-export-unions-all-tenant-patients
  (let [store (fake-search-store
               {"Patient" [{:resourceType "Patient" :id "p1"}
                           {:resourceType "Patient" :id "p2"}]})
        job-store (bjs/create-store)
        {:keys [file]} (system-route-handlers {"Patient" :reg} {})
        base  (merge {:path-params {:tenant-id "default"}
                      :uri "/default/fhir/Patient/$export"
                      :request-method :get
                      :headers {"host" "fhir.local:3001"}
                      :scheme :https
                      :identity {:sub "t"}
                      :fhir/store store
                      :fhir/bulk-job-store job-store
                      :fhir/all-registries {"Patient" :reg}
                      :fhir/resource-encoders {}}
                     authorized)
        resp  (be/patient-export base)]
    (is (= 202 (:status resp)))
    (let [job-id (content-location-job-id resp)]
      (is (= :complete (:status (bjs/get-job job-store "default" job-id))))
      (is (= #{"p1" "p2"} (stream-output-ids file base job-store "default" job-id))))))

(deftest export-error-array-reports-unknown-and-non-member-types
  (testing "requesting a non-member type on a patient-level export records an
            OperationOutcome error entry rather than leaking or failing"
    (let [store (fake-search-store {"Patient" [{:resourceType "Patient" :id "p1"}]})
          job-store (bjs/create-store)
          req   (merge {:path-params {:tenant-id "default"}
                        :uri "/default/fhir/Patient/$export"
                        :request-method :get
                        :headers {"host" "fhir.local:3001"}
                        :scheme :https
                        :identity {:sub "t"}
                        :query-params {"_type" "Patient,Location"}
                        :fhir/store store
                        :fhir/bulk-job-store job-store
                        :fhir/all-registries {"Patient" :reg "Location" :reg}
                        :fhir/resource-encoders {}}
                       authorized)
          resp  (be/patient-export req)]
      (is (= 202 (:status resp)))
      (let [job-id (content-location-job-id resp)]
        (is (= :complete (:status (bjs/get-job job-store "default" job-id))))
        (let [job      (bjs/get-job job-store "default" job-id)
              manifest (be/build-manifest req job)]
          (testing "requiresAccessToken is true in the tightened manifest"
            (is (true? (:requiresAccessToken manifest))))
          (testing "Location (non-member) surfaces as an OperationOutcome error entry"
            (is (= 1 (count (:error manifest))))
            (is (= "OperationOutcome" (:type (first (:error manifest)))))
            (is (str/includes? (:url (first (:error manifest))) "$export-file")))
          (testing "Patient still exports successfully alongside the error"
            (is (= ["Patient"] (mapv :type (:output manifest))))))))))

;; ---------------------------------------------------------------------------
;; Lazy stream-at-download + concurrency cap + TTL eviction
;; ---------------------------------------------------------------------------

(defn- bulk-req
  "A base kickoff request wired for a system-level export against `job-store`
   and `store`, pre-authorized for the system tuple."
  [job-store store]
  (merge {:path-params    {:tenant-id "default"}
          :headers        {"host" "fhir.local:3001"}
          :scheme         :https
          :uri            "/default/fhir/$export"
          :request-method :get
          :identity       {:sub "tester"}
          :fhir/store     store
          :fhir/bulk-job-store job-store}
         authorized))

(deftest kickoff-builds-manifest-without-producing-bytes-or-files
  (testing "kickoff pins the basis and stores ONLY tiny metadata: a
            pre-serialized manifest + per-file stream descriptors, no NDJSON and
            no temp files"
    (let [job-store (bjs/create-store)
          {:keys [kickoff]} (system-route-handlers {"Patient" :reg} {})
          kick-resp (kickoff (bulk-req job-store (fake-store)))
          job-id    (content-location-job-id kick-resp)
          job       (bjs/get-job job-store "default" job-id)]
      (is (= 202 (:status kick-resp)))
      (is (= :complete (:status job)))
      (is (= fixed-basis (:basis job)) "the point-in-time basis is pinned")
      (is (string? (:manifest job)) "the manifest is pre-serialized at kickoff")
      (is (nil? (:temp-dir job)) "no temp directory")
      (testing "the Patient output descriptor carries type/count, no bytes/path"
        (let [pt   (first (filter #(= "Patient" (:type %)) (:output job)))
              desc (get-in job [:files (:file-id pt)])]
          (is (= 2 (:count pt)))
          (is (= {:kind :output :type "Patient"} desc))
          (is (every? map? (vals (:files job))))
          (is (not-any? #(contains? % :path) (vals (:files job)))
              "no on-disk path is recorded on any file descriptor")))
      (testing "kickoff opened no download streams"
        (is (zero? (bjs/active-stream-count job-store)))))))

(deftest export-file-streams-via-reduce-not-materialized
  (testing "the $export-file body is a StreamableResponseBody that consumes the
            store snapshot via reduce (a reduce-only reducible that would throw
            if the code tried to fully realize it via seq)"
    (let [job-store (bjs/create-store)
          {:keys [kickoff file]} (system-route-handlers {"Patient" :reg} {})
          base      (bulk-req job-store (reduce-only-store))
          kick-resp (kickoff base)
          job-id    (content-location-job-id kick-resp)
          job       (bjs/get-job job-store "default" job-id)
          pt        (first (filter #(= "Patient" (:type %)) (:output job)))
          file-resp (file (assoc base :path-params {:tenant-id "default"
                                                    :job-id job-id
                                                    :file-id (:file-id pt)}))
          body      (:body file-resp)]
      (is (= 200 (:status file-resp)))
      (is (satisfies? ring-protocols/StreamableResponseBody body))
      (is (not (string? body)))
      (is (not (instance? java.io.File body)))
      (let [ids (set (map #(get (json->clj %) "id")
                          (remove str/blank? (str/split-lines (stream-body->string body)))))]
        (is (= #{"123" "bulk-export-2"} ids)))
      (is (zero? (bjs/active-stream-count job-store))
          "the concurrency slot is released after the stream drains"))))

(deftest export-file-streams-error-operationoutcome
  (testing "downloading an :error descriptor streams a single OperationOutcome
            NDJSON line (per-type skip/failure diagnostics computed at kickoff)"
    (let [store (fake-search-store {"Patient" [{:resourceType "Patient" :id "p1"}]})
          job-store (bjs/create-store)
          {:keys [file]} (system-route-handlers {"Patient" :reg "Location" :reg} {})
          base (merge {:path-params {:tenant-id "default"}
                       :uri "/default/fhir/Patient/$export"
                       :request-method :get
                       :headers {"host" "fhir.local:3001"}
                       :scheme :https
                       :identity {:sub "t"}
                       :query-params {"_type" "Patient,Location"}
                       :fhir/store store
                       :fhir/bulk-job-store job-store
                       :fhir/all-registries {"Patient" :reg "Location" :reg}
                       :fhir/resource-encoders {}}
                      authorized)
          job-id (content-location-job-id (be/patient-export base))
          err    (first (:error (bjs/get-job job-store "default" job-id)))
          f-resp (file (assoc base :path-params {:tenant-id "default"
                                                 :job-id job-id :file-id (:file-id err)}))
          lines  (remove str/blank? (str/split-lines (stream-body->string (:body f-resp))))]
      (is (= 200 (:status f-resp)))
      (is (= "application/fhir+ndjson" (get-in f-resp [:headers "Content-Type"])))
      (is (= 1 (count lines)))
      (let [oo (json->clj (first lines))]
        (is (= "OperationOutcome" (get oo "resourceType")))
        (is (str/includes? (get-in oo ["issue" 0 "diagnostics"]) "Location")))
      (is (zero? (bjs/active-stream-count job-store))))))

(deftest max-concurrent-streams-cap-returns-429
  (testing "kickoff at/above the concurrent-stream cap returns 429 with
            Retry-After and an OperationOutcome, and starts no job"
    (let [job-store (bjs/create-store {:max-concurrent-streams 0})
          {:keys [kickoff]} (system-route-handlers {"Patient" :reg} {})
          resp (kickoff (bulk-req job-store (fake-store)))]
      (is (= 429 (:status resp)))
      (is (= "120" (get-in resp [:headers "Retry-After"])))
      (is (= "application/fhir+json" (get-in resp [:headers "Content-Type"])))
      (is (empty? (bjs/all-jobs job-store))))))

(deftest file-download-429-when-streams-saturated
  (testing "with every stream slot already held, a download returns 429 (the
            memory bound is on concurrent streams, not on kickoff)"
    (let [job-store (bjs/create-store {:max-concurrent-streams 1})
          {:keys [kickoff file]} (system-route-handlers {"Patient" :reg} {})
          base      (bulk-req job-store (fake-store))
          job-id    (content-location-job-id (kickoff base))
          job       (bjs/get-job job-store "default" job-id)
          pt        (first (filter #(= "Patient" (:type %)) (:output job)))
          file-req  (assoc base :path-params {:tenant-id "default"
                                              :job-id job-id :file-id (:file-id pt)})]
      (let [tok (bjs/acquire-stream! job-store 1)]
        (is (some? tok) "manually saturate the single slot")
        (let [resp (file file-req)]
          (is (= 429 (:status resp)))
          (is (= "120" (get-in resp [:headers "Retry-After"]))))
        (bjs/release-stream! job-store tok))
      (testing "once the slot frees, the same download succeeds and re-releases"
        (let [resp (file file-req)]
          (is (= 200 (:status resp)))
          (stream-body->string (:body resp))
          (is (zero? (bjs/active-stream-count job-store))))))))

(deftest leaked-stream-slot-self-heals
  (testing "a stream slot leaked by a client that disconnected before the body
            was written (its release finally never ran) is reclaimed after
            :max-stream-ms, so leaked slots cannot permanently wedge the cap"
    (let [job-store (bjs/create-store {:max-concurrent-streams 1 :max-stream-ms 1000})
          streams   (:active-streams job-store)]
      ;; A leaked slot: a token whose start is older than :max-stream-ms.
      (reset! streams {"leaked" (- (System/currentTimeMillis) 5000)})
      ;; A concurrent fresh slot must NOT be reclaimed.
      (swap! streams assoc "fresh" (System/currentTimeMillis))
      (is (= 1 (bjs/active-stream-count job-store))
          "the leaked slot is pruned, the fresh slot is kept")
      (is (= #{"fresh"} (set (keys @streams)))
          "only the leaked slot was swept")
      ;; Even at the cap, acquire reclaims a leaked slot instead of wedging.
      (reset! streams {"leaked" (- (System/currentTimeMillis) 5000)})
      (let [tok (bjs/acquire-stream! job-store 1)]
        (is (some? tok) "acquire reclaims the leaked slot despite being at the cap")
        (is (= #{tok} (set (keys @streams))))))))

(deftest ttl-sweep-evicts-expired-job-metadata
  (testing "a lazy sweep on the next bulk request removes terminal jobs older
            than ttl-ms (metadata only, no files to reclaim)"
    (let [job-store (bjs/create-store {:ttl-ms 0})]
      (bjs/put-job! job-store "default"
                    {:id "old" :tenant "default" :status :complete
                     :finished-at (- (System/currentTimeMillis) 10000)})
      ;; Any bulk request triggers the sweep; an unknown-job status poll is fine.
      (be/status {:path-params {:tenant-id "default" :job-id "other"}
                  :headers {"host" "h"}
                  :fhir/bulk-job-store job-store})
      (is (nil? (bjs/get-job job-store "default" "old")) "expired job evicted"))))

;; ---------------------------------------------------------------------------
;; CapabilityStatement $export operation declarations
;;
;; Inferno's bulk_data operation_support check locates system export at the
;; rest level and patient-/group-level export at rest.resource[type].operation
;; (operation.name "export" + the type's OperationDefinition canonical). See
;; BulkDataExportOperationTests#check_export_support.
;; ---------------------------------------------------------------------------

(deftest capability-statement-declares-export-operations
  (let [schemas [(m/schema [:map {:resourceType "Patient"
                                  :fhir/interactions {:read {}}} [:id :string]])
                 (m/schema [:map {:resourceType "Observation"
                                  :fhir/interactions {:read {}}} [:id :string]])]
        body    (:body ((handlers/capability-statement schemas) {}))
        rest0   (first (:rest body))
        resources (:resource rest0)
        find-export (fn [rtype]
                      (->> resources
                           (filter #(= rtype (:type %)))
                           first
                           :operation
                           (filter #(= "export" (:name %)))
                           first))]
    (testing "system-level export stays at the rest level"
      (is (some #(and (= "export" (:name %))
                      (= "http://hl7.org/fhir/uv/bulkdata/OperationDefinition/export"
                         (:definition %)))
                (:operation rest0))))
    (testing "Patient resource declares export with the patient-export canonical"
      (is (= "http://hl7.org/fhir/uv/bulkdata/OperationDefinition/patient-export"
             (:definition (find-export "Patient")))))
    (testing "Group resource is appended (not a registered type here) with the
              group-export canonical"
      (is (some #(= "Group" (:type %)) resources))
      (is (= "http://hl7.org/fhir/uv/bulkdata/OperationDefinition/group-export"
             (:definition (find-export "Group")))))
    (testing "non-bulk resources get no injected export operation"
      (is (nil? (find-export "Observation"))))))
