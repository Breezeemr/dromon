(ns server.handlers
  (:require [fhir-store.protocol :as db]
            [fhir-terminology.protocol :as terminology]
            [malli.core :as m]
            [malli.transform :as mt]
            [clojure.string :as str]
            [com.breezeehr.fhir-json-transform :as fjt]
            [server.json-patch :as json-patch]
            [taoensso.telemere :as t]))

(defn- gone-response [resource-type id]
  {:status 410
   :body {:resourceType "OperationOutcome"
          :issue [{:severity "error"
                   :code "deleted"
                   :diagnostics (str resource-type "/" id " has been deleted")}]}})

(defn- not-found-response [resource-type id]
  {:status 404
   :body {:resourceType "OperationOutcome"
          :issue [{:severity "error"
                   :code "not-found"
                   :diagnostics (str resource-type "/" id " not found")}]}})

(defn- invalid-param-response
  "Return a 400 OperationOutcome for an invalid query parameter value."
  [param-name value reason]
  {:status 400
   :body {:resourceType "OperationOutcome"
          :issue [{:severity "error"
                   :code "invalid"
                   :diagnostics (str "Invalid value for " param-name ": '"
                                     value "' — " reason)}]}})

(defn- parse-non-negative-int
  "Parse a string as a non-negative integer. Returns the integer on success,
   or an error map {:error response} on failure."
  [param-name s]
  (let [n (when (string? s) (parse-long s))]
    (cond
      (nil? n)
      {:error (invalid-param-response param-name s "must be a non-negative integer")}

      (neg? n)
      {:error (invalid-param-response param-name s "must not be negative")}

      :else n)))

(defn- parse-if-match
  "Parse W/\"[vid]\" from If-Match header. Returns version string or nil."
  [request]
  (when-let [if-match (get-in request [:headers "if-match"])]
    (second (re-find #"W/\"(.+)\"" if-match))))

(defn- parse-if-none-match
  "Parse W/\"[vid]\" from If-None-Match header."
  [request]
  (when-let [header (get-in request [:headers "if-none-match"])]
    (second (re-find #"W/\"(.+)\"" header))))

(defn- parse-if-modified-since
  "Parse If-Modified-Since header as Instant."
  [request]
  (when-let [header (get-in request [:headers "if-modified-since"])]
    (try
      (.toInstant
        (java.time.ZonedDateTime/parse header
          java.time.format.DateTimeFormatter/RFC_1123_DATE_TIME))
      (catch Exception _ nil))))

(defn- not-modified? [resource request]
  (let [vid (get-in resource [:meta :versionId])
        last-updated (get-in resource [:meta :lastUpdated])
        if-none-match (parse-if-none-match request)
        if-modified-since (parse-if-modified-since request)]
    (or (and if-none-match vid (= if-none-match vid))
        (and if-modified-since last-updated
             (let [updated-instant (if (string? last-updated)
                                     (java.time.Instant/parse last-updated)
                                     last-updated)]
               (not (.isAfter updated-instant if-modified-since)))))))

(defn- precondition-failed-response [resource-type id expected actual]
  {:status 412
   :body {:resourceType "OperationOutcome"
          :issue [{:severity "error"
                   :code "conflict"
                   :diagnostics (str "Version mismatch for " resource-type "/" id
                                     ": expected " expected ", current is " actual)}]}})

(defn read-resource
  "Handler for GET /[type]/:id RESTful interaction."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        id (-> req :path-params :id)
        res (db/read-resource store tenant-id (keyword resource-type) id)]
    (if res
      (if (not-modified? res req)
        {:status 304 :body nil}
        {:status 200 :body res})
      (if (db/resource-deleted? store tenant-id (keyword resource-type) id)
        (gone-response resource-type id)
        (not-found-response resource-type id)))))

(defn vread-resource
  "Handler for GET /[type]/:id/_history/:vid RESTful interaction."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        id (-> req :path-params :id)
        vid (-> req :path-params :vid)
        res (db/vread-resource store tenant-id (keyword resource-type) id vid)]
    (cond
      (nil? res)
      (not-found-response resource-type (str id "/_history/" vid))

      (not-modified? res req)
      {:status 304 :body nil}

      :else
      {:status 200 :body res})))

(defn update-resource
  "Handler for PUT /[type]/:id RESTful interaction."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        id (-> req :path-params :id)
        resource-body (get-in req [:parameters :body])
        body-id (:id resource-body)]
    (if (and body-id (not= body-id id))
      {:status 400
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "invalid"
                       :diagnostics (str "Resource id in body (" body-id ") does not match URL id (" id ")")}]}}
      (let [existing (db/read-resource store tenant-id (keyword resource-type) id)]
        (if-let [expected-version (parse-if-match req)]
          (let [current-version (get-in existing [:meta :versionId])]
            (if (not= expected-version current-version)
              (precondition-failed-response resource-type id expected-version current-version)
              (let [res (db/update-resource store tenant-id (keyword resource-type) id resource-body)]
                {:status 200 :body res})))
          (if existing
            ;; Resource exists: update
            (let [res (db/update-resource store tenant-id (keyword resource-type) id resource-body)]
              {:status 200 :body res})
            ;; Resource doesn't exist: create with client-supplied ID (upsert)
            (let [res (db/create-resource store tenant-id (keyword resource-type) id resource-body)
                  base-url (str "/" tenant-id "/fhir/" resource-type "/" id)
                  vid (get-in res [:meta :versionId])]
              {:status 201
               :headers {"Location" (str base-url "/_history/" vid)}
               :body res})))))))

(defn patch-resource
  "Handler for PATCH /[type]/:id RESTful interaction.
   Accepts a JSON Patch document (application/json-patch+json) per RFC 6902."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        id (-> req :path-params :id)
        patch-ops (get-in req [:parameters :body])
        existing (db/read-resource store tenant-id (keyword resource-type) id)]
    (if existing
      (if-let [expected-version (parse-if-match req)]
        (let [current-version (get-in existing [:meta :versionId])]
          (if (not= expected-version current-version)
            (precondition-failed-response resource-type id expected-version current-version)
            (let [patched (json-patch/apply-patch existing patch-ops)
                  result (db/update-resource store tenant-id (keyword resource-type) id patched)]
              {:status 200 :body result})))
        (let [patched (json-patch/apply-patch existing patch-ops)
              result (db/update-resource store tenant-id (keyword resource-type) id patched)]
          {:status 200 :body result}))
      {:status 404
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "not-found"
                       :diagnostics (str resource-type "/" id " not found")}]}})))

(defn delete-resource
  "Handler for DELETE /[type]/:id RESTful interaction."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        id (-> req :path-params :id)]
    (if-let [expected-version (parse-if-match req)]
      (let [existing (db/read-resource store tenant-id (keyword resource-type) id)
            current-version (get-in existing [:meta :versionId])]
        (if (not= expected-version current-version)
          (precondition-failed-response resource-type id expected-version current-version)
          (do (db/delete-resource store tenant-id (keyword resource-type) id)
              {:status 204 :body nil})))
      (do (db/delete-resource store tenant-id (keyword resource-type) id)
          {:status 204 :body nil}))))

(defn- history-entry
  "Build a Bundle entry for a history result."
  [tenant-id resource-type res]
  {:fullUrl (str "/" tenant-id "/fhir/" resource-type "/" (:id res))
   :resource res
   :request {:method (let [vid (get-in res [:meta :versionId])]
                       (if (= vid "1") "POST" "PUT"))
             :url (str resource-type "/" (:id res))}
   :response {:status "200"}})

(defn history-instance
  "Handler for GET /[type]/:id/_history RESTful interaction."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        id (-> req :path-params :id)
        params (or (:query-params req) {})
        count-param (or (get params "_count") (get params :_count) "50")
        skip-param (or (get params "_skip") (get params :_skip) "0")
        limit (parse-non-negative-int "_count" (str count-param))
        skip (parse-non-negative-int "_skip" (str skip-param))]
    (if-let [err (or (:error limit) (:error skip))]
      err
      (let [since (or (get params "_since") (get params :_since))
        raw-results (db/history store tenant-id (keyword resource-type) id)
        all-results (if since
                      (filterv (fn [r]
                                 (when-let [lu (get-in r [:meta :lastUpdated])]
                                   (pos? (compare (str lu) since))))
                               raw-results)
                      raw-results)
        total (count all-results)
        results (->> all-results (drop skip) (take limit) vec)
        entries (mapv (partial history-entry tenant-id resource-type) results)
        base-url (str "/" tenant-id "/fhir/" resource-type "/" id "/_history")
        self-link {:relation "self" :url (str base-url "?_count=" limit "&_skip=" skip)}
        next-link (when (< (+ skip limit) total)
                    {:relation "next" :url (str base-url "?_count=" limit "&_skip=" (+ skip limit))})
        prev-link (when (> skip 0)
                    {:relation "previous" :url (str base-url "?_count=" limit "&_skip=" (max 0 (- skip limit)))})
        links (filterv some? [self-link next-link prev-link])]
    {:status 200
     :body {:resourceType "Bundle"
            :type "history"
            :total total
            :link links
            :entry entries}}))))

(defn history-type
  "Handler for GET /[type]/_history RESTful interaction."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        params (or (:query-params req) {})
        results (db/history-type store tenant-id (keyword resource-type) params)
        entries (mapv (partial history-entry tenant-id resource-type) results)]
    {:status 200
     :body {:resourceType "Bundle"
            :type "history"
            :total (count results)
            :entry entries}}))

(defn- parse-query-string
  "Parse a URL query string into a map of string key-value pairs."
  [qs]
  (when (and qs (not (str/blank? qs)))
    (into {}
      (map (fn [pair]
             (let [[k v] (str/split pair #"=" 2)]
               [k (or v "")])))
      (str/split qs #"&"))))

(defn- do-create
  "Perform the actual resource creation, returning a 201 response."
  [store tenant-id resource-type resource-body]
  (let [id (str (java.util.UUID/randomUUID))
        res (db/create-resource store tenant-id (keyword resource-type) id resource-body)
        base-url (str "/" tenant-id "/fhir/" resource-type "/" id)
        vid (get-in res [:meta :versionId])]
    {:status 201
     :headers {"Location" (str base-url "/_history/" vid)}
     :body res}))

(defn create-resource
  "Handler for POST /[type] RESTful interaction.
   Supports conditional create via the If-None-Exist header (FHIR R4 §3.1.0.8.1)."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        resource-body (get-in req [:parameters :body])
        if-none-exist (get-in req [:headers "if-none-exist"])
        search-registry (:fhir/search-registry req)]
    (if if-none-exist
      ;; Conditional create: search first
      (let [search-params (parse-query-string if-none-exist)
            results (db/search store tenant-id (keyword resource-type)
                               (assoc search-params :_count 2 :_skip 0)
                               search-registry)
            match-count (count results)]
        (cond
          (zero? match-count)
          (do-create store tenant-id resource-type resource-body)

          (= 1 match-count)
          {:status 200 :body (first results)}

          :else
          {:status 412
           :body {:resourceType "OperationOutcome"
                  :issue [{:severity "error"
                           :code "duplicate"
                           :diagnostics "Conditional create found multiple matches"}]}}))
      ;; No If-None-Exist: create normally
      (do-create store tenant-id resource-type resource-body))))

(defn- ensure-coll
  "Coerce a value to a collection. If already sequential, return as-is; otherwise wrap in a vector."
  [x]
  (if (sequential? x) x [x]))

(defn- extract-reference
  "Extracts a FHIR reference string from a value that may be a Reference map,
   a plain string, or nested within an array."
  [val]
  (cond
    (map? val)        (:reference val)
    (string? val)     val
    (sequential? val) (some extract-reference val)
    :else             nil))

(defn- resolve-includes
  "For _include=SourceType:searchParam, follow reference fields in the primary results
   to include the referenced resources. Returns Bundle entries with search.mode=include.
   Uses the search registry to map search parameter names to actual FHIR field names.
   Batches all referenced resource IDs by type and resolves them in bulk via db/search
   with comma-separated _id values to avoid N+1 queries."
  [store tenant-id results include-params all-registries]
  (when (and (seq include-params) (seq results))
    (let [params (ensure-coll include-params)
          ;; First pass: collect all referenced resource IDs grouped by type
          ids-by-type
          (reduce
            (fn [acc include-param]
              (let [[source-type search-param] (str/split include-param #":" 2)
                    source-registry (get all-registries source-type)
                    param-descriptor (when source-registry (get source-registry search-param))
                    field-kws (if-let [columns (seq (:columns param-descriptor))]
                                (mapv (comp keyword :col) columns)
                                [(keyword search-param)])]
                (if (seq field-kws)
                  (reduce
                    (fn [acc2 res]
                      (reduce
                        (fn [acc3 field-kw]
                          (if-let [ref-val (get res field-kw)]
                            (let [ref-str (extract-reference ref-val)]
                              (if (and (string? ref-str) (str/includes? ref-str "/"))
                                (let [[rt id] (str/split ref-str #"/" 2)]
                                  (update acc3 rt (fnil conj #{}) id))
                                acc3))
                            acc3))
                        acc2
                        field-kws))
                    acc
                    results)
                  acc)))
            {}
            params)
          ;; Second pass: batch-fetch all resources per type with a single search call
          included-resources
          (into []
                (mapcat
                  (fn [[resource-type ids]]
                    (when (seq ids)
                      (let [registry (get all-registries resource-type)]
                        (db/search store tenant-id (keyword resource-type)
                                   {"_id" (str/join "," ids)}
                                   registry)))))
                ids-by-type)]
      (when (seq included-resources)
        (->> included-resources
             (distinct)
             (mapv (fn [res]
                     {:fullUrl (str "/" tenant-id "/fhir/" (:resourceType res) "/" (:id res))
                      :resource res
                      :search {:mode "include"}})))))))

(defn- resolve-revincludes
  "For _revinclude=TargetType:searchParam, find resources of TargetType whose searchParam
   references any of the primary results. Returns Bundle entries with search.mode=include."
  [store tenant-id results revinclude-params all-registries]
  (when (and (seq revinclude-params) (seq results))
    (let [params (ensure-coll revinclude-params)
          refs (keep (fn [res]
                       (when-let [rt (:resourceType res)]
                         (when-let [id (:id res)]
                           (str rt "/" id))))
                     results)]
      (when (seq refs)
        (let [target-param (str/join "," refs)]
          (->> params
               (mapcat
                 (fn [revinclude-param]
                   (let [[target-type search-param] (str/split revinclude-param #":" 2)
                         target-registry (get all-registries target-type)]
                     (when target-registry
                       (db/search store tenant-id (keyword target-type)
                                  {search-param target-param
                                   :_count (str (* 10 (count refs)))
                                   :_skip "0"}
                                  target-registry)))))
               (mapv (fn [res]
                       {:fullUrl (str "/" tenant-id "/fhir/" (:resourceType res) "/" (:id res))
                        :resource res
                        :search {:mode "include"}}))))))))

(defn search-type
  "Handler for GET /[type] RESTful interaction."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        params (merge (or (:form-params req) {}) (or (:query-params req) {}))

        ;; Extract _include and _revinclude before passing to search
        include-param (or (get params "_include") (get params :_include))
        revinclude (or (get params "_revinclude") (get params :_revinclude))

        ;; Ensure we have _count and _skip, defaulting to 50 and 0
        count-param (or (get params :_count) (get params "_count") "50")
        skip-param (or (get params :_skip) (get params "_skip") "0")

        limit (parse-non-negative-int "_count" (str count-param))
        skip (parse-non-negative-int "_skip" (str skip-param))]
    (if-let [err (or (:error limit) (:error skip))]
      err
      (let [search-registry (:fhir/search-registry req)
            base-url (str "/" tenant-id "/fhir/" resource-type)]
        (if (zero? limit)
          ;; _count=0: return total-only Bundle with no entries and no next link
          (let [total (db/count-resources store tenant-id (keyword resource-type)
                                         (assoc params :_count 0 :_skip 0) search-registry)
                build-link (fn [new-skip]
                             (let [query-string (->> (assoc params :_count limit :_skip new-skip)
                                                     (map (fn [[k v]] (str (name k) "=" v)))
                                                     (clojure.string/join "&"))]
                               (str base-url "?" query-string)))
                self-link {:relation "self" :url (build-link skip)}]
            {:status 200
             :body {:resourceType "Bundle"
                    :type "searchset"
                    :total total
                    :link [self-link]}})
          ;; Normal search with pagination
          (let [search-params (assoc params :_count limit :_skip skip)
                results (db/search store tenant-id (keyword resource-type) search-params search-registry)

                build-link (fn [new-skip]
                             (let [query-string (->> (assoc params :_count limit :_skip new-skip)
                                                     (map (fn [[k v]] (str (name k) "=" v)))
                                                     (clojure.string/join "&"))]
                               (str base-url "?" query-string)))

                self-link {:relation "self" :url (build-link skip)}

                next-link (when (= (count results) limit)
                            {:relation "next" :url (build-link (+ skip limit))})

                prev-link (when (> skip 0)
                            {:relation "previous" :url (build-link (max 0 (- skip limit)))})

                links (filterv some? [self-link next-link prev-link])

                entries (mapv (fn [res]
                                {:fullUrl (str base-url "/" (:id res))
                                 :resource res
                                 :search {:mode "match"}})
                              results)

                all-registries (:fhir/all-registries req)
                inc-entries (resolve-includes store tenant-id results include-param all-registries)
                revinc-entries (resolve-revincludes store tenant-id results revinclude all-registries)

                all-entries (cond-> entries
                              (seq inc-entries) (into inc-entries)
                              (seq revinc-entries) (into revinc-entries))]
            {:status 200
             :body {:resourceType "Bundle"
                    :type "searchset"
                    :total (count results)
                    :link links
                    :entry all-entries}}))))))

(defn conditional-update
  "Handler for PUT /[type]?[search params] — conditional update."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        resource-body (get-in req [:parameters :body])
        search-registry (:fhir/search-registry req)
        params (merge (or (:query-params req) {}) (or (:form-params req) {}))
        results (db/search store tenant-id (keyword resource-type)
                           (assoc params :_count 2 :_skip 0) search-registry)
        match-count (count results)]
    (cond
      (zero? match-count)
      ;; No matches: create
      (let [id (or (:id resource-body) (str (java.util.UUID/randomUUID)))
            res (db/create-resource store tenant-id (keyword resource-type) id resource-body)
            base-url (str "/" tenant-id "/fhir/" resource-type "/" id)
            vid (get-in res [:meta :versionId])]
        {:status 201
         :headers {"Location" (str base-url "/_history/" vid)}
         :body res})

      (= 1 match-count)
      ;; One match: update it
      (let [existing (first results)
            id (:id existing)
            body-id (:id resource-body)]
        (if (and body-id (not= body-id id))
          {:status 400
           :body {:resourceType "OperationOutcome"
                  :issue [{:severity "error" :code "invalid"
                           :diagnostics (str "Resource id in body (" body-id ") does not match resolved id (" id ")")}]}}
          (let [res (db/update-resource store tenant-id (keyword resource-type) id resource-body)]
            {:status 200 :body res})))

      :else
      {:status 412
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error" :code "duplicate"
                       :diagnostics "Conditional update matched multiple resources"}]}})))

(defn conditional-delete
  "Handler for DELETE /[type]?[search params] — conditional delete."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        search-registry (:fhir/search-registry req)
        params (merge (or (:query-params req) {}) (or (:form-params req) {}))
        results (db/search store tenant-id (keyword resource-type)
                           (assoc params :_count 2 :_skip 0) search-registry)
        match-count (count results)]
    (cond
      (zero? match-count)
      {:status 204 :body nil}

      (= 1 match-count)
      (do (db/delete-resource store tenant-id (keyword resource-type) (:id (first results)))
          {:status 204 :body nil})

      :else
      {:status 412
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error" :code "duplicate"
                       :diagnostics "Conditional delete matched multiple resources"}]}})))

(defn conditional-patch
  "Handler for PATCH /[type]?[search params] — conditional patch."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        patch-ops (get-in req [:parameters :body])
        search-registry (:fhir/search-registry req)
        params (merge (or (:query-params req) {}) (or (:form-params req) {}))
        results (db/search store tenant-id (keyword resource-type)
                           (assoc params :_count 2 :_skip 0) search-registry)
        match-count (count results)]
    (cond
      (zero? match-count)
      {:status 404
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error" :code "not-found"
                       :diagnostics "Conditional patch found no matching resources"}]}}

      (= 1 match-count)
      (let [existing (first results)
            id (:id existing)
            patched (json-patch/apply-patch existing patch-ops)
            result (db/update-resource store tenant-id (keyword resource-type) id patched)]
        {:status 200 :body result})

      :else
      {:status 412
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error" :code "duplicate"
                       :diagnostics "Conditional patch matched multiple resources"}]}})))

;; ---------------------------------------------------------------------------
;; Compartment search (FHIR R4 §3.3.1)
;; ---------------------------------------------------------------------------

(def ^:private compartment-definitions
  "Maps compartment-type -> target-resource-type -> search parameter name.
   The search parameter links the target resource back to the compartment owner."
  {"Patient"
   {"AllergyIntolerance"  "patient"
    "CarePlan"            "patient"
    "CareTeam"            "patient"
    "Condition"           "patient"
    "Coverage"            "patient"
    "Device"              "patient"
    "DiagnosticReport"    "patient"
    "DocumentReference"   "patient"
    "Encounter"           "patient"
    "Goal"                "patient"
    "Immunization"        "patient"
    "MedicationDispense"  "patient"
    "MedicationRequest"   "patient"
    "Observation"         "patient"
    "Procedure"           "patient"
    "QuestionnaireResponse" "patient"
    "RelatedPerson"       "patient"
    "ServiceRequest"      "patient"
    "Specimen"            "patient"}

   "Practitioner"
   {"Account"             "subject"
    "AllergyIntolerance"  "recorder"
    "CarePlan"            "performer"
    "CareTeam"            "participant"
    "Condition"           "asserter"
    "DiagnosticReport"    "performer"
    "DocumentReference"   "author"
    "Encounter"           "practitioner"
    "Immunization"        "performer"
    "MedicationRequest"   "requester"
    "Observation"         "performer"
    "Procedure"           "performer"
    "ServiceRequest"      "requester"}

   "Encounter"
   {"Condition"           "encounter"
    "DiagnosticReport"    "encounter"
    "DocumentReference"   "encounter"
    "MedicationRequest"   "encounter"
    "Observation"         "encounter"
    "Procedure"           "encounter"
    "ServiceRequest"      "encounter"}

   "RelatedPerson"
   {"AllergyIntolerance"  "asserter"
    "CarePlan"            "performer"
    "CareTeam"            "participant"
    "Encounter"           "participant"
    "Observation"         "performer"
    "Procedure"           "performer"}

   "Device"
   {"DeviceMetric"        "source"
    "DeviceRequest"       "device"
    "Observation"         "device"}})

(def valid-compartment-types
  "Set of resource types that define FHIR compartments."
  (set (keys compartment-definitions)))

(defn compartment-search
  "Handler for GET /:tenant-id/fhir/:compartment-type/:compartment-id/:target-type
   Searches for resources of target-type that belong to the given compartment."
  [req]
  (let [store            (:fhir/store req)
        tenant-id        (-> req :path-params :tenant-id)
        compartment-type (-> req :path-params :compartment-type)
        compartment-id   (-> req :path-params :compartment-id)
        target-type      (-> req :path-params :target-type)
        all-registries   (:fhir/all-registries req)
        compartment-map  (get compartment-definitions compartment-type)]
    (cond
      ;; Unknown compartment type
      (nil? compartment-map)
      {:status 400
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "invalid"
                       :diagnostics (str "Unknown compartment type: " compartment-type)}]}}

      ;; Wildcard: search all resource types in the compartment
      (= target-type "*")
      (let [params  (merge (or (:query-params req) {}) (or (:form-params req) {}))
            entries (vec
                      (mapcat
                        (fn [[rt search-param]]
                          (when-let [registry (get all-registries rt)]
                            (let [ref-value (str compartment-type "/" compartment-id)
                                  search-params (assoc params
                                                       search-param ref-value
                                                       :_count 50
                                                       :_skip 0)
                                  results (db/search store tenant-id (keyword rt)
                                                     search-params registry)]
                              (mapv (fn [res]
                                      {:fullUrl  (str "/" tenant-id "/fhir/" rt "/" (:id res))
                                       :resource res
                                       :search   {:mode "match"}})
                                    results))))
                        compartment-map))]
        {:status 200
         :body {:resourceType "Bundle"
                :type "searchset"
                :total (count entries)
                :entry entries}})

      ;; Specific target resource type
      :else
      (let [search-param (get compartment-map target-type)]
        (if (nil? search-param)
          {:status 400
           :body {:resourceType "OperationOutcome"
                  :issue [{:severity "error"
                           :code "invalid"
                           :diagnostics (str target-type " is not a member of the "
                                             compartment-type " compartment")}]}}
          (let [registry  (get all-registries target-type)
                params    (merge (or (:query-params req) {}) (or (:form-params req) {}))
                ref-value (str compartment-type "/" compartment-id)
                count-param (or (get params :_count) (get params "_count") "50")
                skip-param  (or (get params :_skip) (get params "_skip") "0")
                limit (if (string? count-param) (parse-long count-param) count-param)
                skip  (if (string? skip-param)  (parse-long skip-param)  skip-param)
                search-params (assoc params
                                     search-param ref-value
                                     :_count limit
                                     :_skip skip)
                results (if registry
                          (db/search store tenant-id (keyword target-type)
                                     search-params registry)
                          [])
                base-url (str "/" tenant-id "/fhir/" compartment-type "/" compartment-id "/" target-type)
                entries (mapv (fn [res]
                                {:fullUrl  (str "/" tenant-id "/fhir/" target-type "/" (:id res))
                                 :resource res
                                 :search   {:mode "match"}})
                              results)
                self-link {:relation "self" :url base-url}
                next-link (when (= (count results) limit)
                            {:relation "next"
                             :url (str base-url "?_count=" limit "&_skip=" (+ skip limit))})
                links (filterv some? [self-link next-link])]
            {:status 200
             :body {:resourceType "Bundle"
                    :type "searchset"
                    :total (count results)
                    :link links
                    :entry entries}}))))))

;; ---------------------------------------------------------------------------
;; $validate operation (FHIR R4 §3.1.0.11)
;; ---------------------------------------------------------------------------

(def ^:private validator-cache
  "Identity-keyed cache of compiled validators per cap-schema. cap-schema
   values come from route data (fixed at router build time), so identity
   equality is both correct and cheap."
  (java.util.concurrent.ConcurrentHashMap.))

(def ^:private explainer-cache
  "Identity-keyed cache of compiled explainers per cap-schema."
  (java.util.concurrent.ConcurrentHashMap.))

(defn- cached-validator [schema]
  (or (.get validator-cache schema)
      (let [v (m/validator schema)]
        (.putIfAbsent validator-cache schema v)
        (.get validator-cache schema))))

(defn- cached-explainer [schema]
  (or (.get explainer-cache schema)
      (let [e (m/explainer schema)]
        (.putIfAbsent explainer-cache schema e)
        (.get explainer-cache schema))))

(defn- malli-error->issue
  "Convert a single Malli error map into a FHIR OperationOutcome issue."
  [error]
  (let [path-str (when (seq (:in error))
                   (str/join "." (map #(if (integer? %) (str "[" % "]") (name %)) (:in error))))
        message (or (:message error)
                    (str "Validation failed"
                         (when (:schema error)
                           (str " against schema: " (pr-str (:schema error))))))]
    (cond-> {:severity "error"
             :code "structure"
             :diagnostics message}
      path-str (assoc :expression [path-str]))))

(defn validate-resource
  "Handler for POST /[type]/$validate operation.
   Validates a resource body against its Malli cap-schema and returns
   an OperationOutcome with validation results."
  [req]
  (let [cap-schema (:fhir/cap-schema req)
        ;; Accept either coerced body or raw body-params
        resource-body (or (get-in req [:parameters :body])
                          (:body-params req))
        resource-type (:fhir/resource-type req)]
    (cond
      (nil? resource-body)
      {:status 400
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "invalid"
                       :diagnostics "No resource body provided for validation"}]}}

      (nil? cap-schema)
      {:status 501
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "not-supported"
                       :diagnostics (str "No validation schema available for " resource-type)}]}}

      :else
      (if ((cached-validator cap-schema) resource-body)
        ;; Valid — fast path, no explainer walk
        {:status 200
         :body {:resourceType "OperationOutcome"
                :issue [{:severity "information"
                         :code "informational"
                         :diagnostics "Resource is valid"}]}}
        ;; Invalid — build the explanation only on the failure branch
        (let [explanation ((cached-explainer cap-schema) resource-body)
              issues (mapv malli-error->issue (:errors explanation))]
          {:status 200
           :body {:resourceType "OperationOutcome"
                  :issue (if (seq issues)
                           issues
                           [{:severity "error"
                             :code "invalid"
                             :diagnostics "Resource failed validation"}])}})))))

;; Non-resource handlers

(defn valueset-expand
  "Handler for ValueSet $expand operation."
  [req]
  (let [terminology (:fhir/terminology req)
        store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        id (-> req :path-params :id)
        params (or (:query-params req) {})]
    (if terminology
      (try
        {:status 200
         :body (terminology/expand-valueset terminology
                 (cond-> params id (assoc :id id)))}
        (catch Exception e
          {:status (or (:fhir/status (ex-data e)) 500)
           :body {:resourceType "OperationOutcome"
                  :issue [{:severity "error" :code "exception"
                           :diagnostics (ex-message e)}]}}))
      ;; Fallback to store operations
      (let [op-fn (:valueset-expand (:operations store))]
        (if op-fn
          {:status 200 :body (op-fn store tenant-id params id)}
          {:status 501
           :body {:resourceType "OperationOutcome"
                  :issue [{:severity "error" :code "not-supported"
                           :diagnostics "ValueSet $expand not supported"}]}})))))

(defn valueset-lookup
  "Handler for ValueSet $lookup operation."
  [req]
  (let [terminology (:fhir/terminology req)
        store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        params (or (:query-params req) {})]
    (if terminology
      (try
        {:status 200
         :body (terminology/lookup-code terminology params)}
        (catch Exception e
          {:status (or (:fhir/status (ex-data e)) 500)
           :body {:resourceType "OperationOutcome"
                  :issue [{:severity "error" :code "exception"
                           :diagnostics (ex-message e)}]}}))
      ;; Fallback to store operations
      (let [op-fn (:valueset-lookup (:operations store))]
        (if op-fn
          {:status 200 :body (op-fn store tenant-id params)}
          {:status 501
           :body {:resourceType "OperationOutcome"
                  :issue [{:severity "error" :code "not-supported"
                           :diagnostics "ValueSet $lookup not supported"}]}})))))

(defn capability-statement [schemas]
  (fn [_req]
    (let [resources (mapv (fn [schema]
                            (let [props (m/properties schema)
                                  fhir-type (:resourceType props)
                                  cap-schema (:fhir/cap-schema props)
                                  interactions (:fhir/interactions props {})
                                  search-params (get-in interactions [:search-type :search-parameters] [])
                                  profiles (when cap-schema
                                             (->> (m/children cap-schema)
                                                  (map first)
                                                  (remove #{:default :malli.core/default})
                                                  (filterv string?)))]
                              (let [operations (:fhir/operations props {})
                                    has-update? (contains? interactions :update)
                                    has-create? (contains? interactions :create)
                                    has-delete? (contains? interactions :delete)]
                                (cond-> {:type fhir-type
                                         :interaction (let [valid-codes #{"read" "vread" "update" "patch" "delete"
                                                                           "history-instance" "history-type" "create" "search-type"}]
                                                        (into [] (comp (map name)
                                                                       (filter valid-codes)
                                                                       (map (fn [c] {:code c})))
                                                              (keys interactions)))}
                                  has-update? (assoc :updateCreate true
                                                     :conditionalUpdate true)
                                  has-create? (assoc :conditionalCreate true)
                                  has-delete? (assoc :conditionalDelete "single")
                                  (seq profiles)
                                  (assoc :supportedProfile profiles)
                                  (seq search-params)
                                  (assoc :searchParam (mapv (fn [sp]
                                                              {:name (:name sp)
                                                               :definition (or (:definition sp) (:url sp))
                                                               :type (:type sp)})
                                                            search-params))
                                  (seq operations)
                                  (assoc :operation (mapv (fn [[op-name _]]
                                                           {:name op-name
                                                            :definition (str "http://hl7.org/fhir/OperationDefinition/ValueSet-" (subs op-name 1))})
                                                         operations))))))
                          schemas)]
      {:status 200
       :body {:resourceType "CapabilityStatement"
              :instantiates ["http://hl7.org/fhir/us/core/CapabilityStatement/us-core-server"]
              :text {:status "generated"
                     :div "<div xmlns=\"http://www.w3.org/1999/xhtml\">Dromon Capability Statement</div>"}
              :status "active"
              :date (str (java.time.LocalDate/now))
              :publisher "Dromon"
              :kind "instance"
              :software {:name "Dromon FHIR Server"}
              :implementation {:description "Dromon FHIR Server"
                               :url "https://fhir.local:8443/default/fhir"}
              :fhirVersion "4.0.1"
              :format ["application/fhir+json"]
              :rest [{:mode "server"
                      :security {:service [{:coding [{:system "http://terminology.hl7.org/CodeSystem/restful-security-service"
                                                      :code "SMART-on-FHIR"}]}]}
                      :resource resources}]}})))

(defn smart-configuration
  "Returns SMART configuration. When called as a 0-arg handler, uses env vars
   or localhost defaults. When called with an oauth-base-url, uses that."
  ([] (smart-configuration nil))
  ([oauth-base-url]
   (let [base (or oauth-base-url
                  (System/getenv "OAUTH_BASE_URL")
                  "http://localhost:4444")]
     (fn [_req]
       {:status 200
        :headers {"Content-Type" "application/json"}
        :body {:authorization_endpoint (str base "/oauth2/auth")
               :token_endpoint         (str base "/oauth2/token")
               :token_endpoint_auth_methods_supported ["client_secret_basic" "private_key_jwt"]
               :grant_types_supported  ["authorization_code" "client_credentials"]
               :scopes_supported       ["openid" "profile" "launch" "launch/patient"
                                        "patient/*.read" "patient/*.write"
                                        "user/*.read" "user/*.write"]
               :response_types_supported ["code"]
               :capabilities           ["launch-standalone" "client-public" "client-confidential-symmetric"
                                        "sso-openid-connect" "context-passthrough-banner"
                                        "permission-offline" "permission-patient" "permission-user"]}}))))

(defn system-history [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        all-registries (:fhir/all-registries req)
        params (or (:query-params req) {})
        types (keys all-registries)
        all-entries (vec
                      (mapcat
                        (fn [resource-type]
                          (let [results (db/history-type store tenant-id (keyword resource-type) params)]
                            (mapv (fn [res]
                                    {:fullUrl (str "/" tenant-id "/fhir/" (or (:resourceType res) resource-type) "/" (:id res))
                                     :resource res
                                     :request {:method "PUT"
                                               :url (str (or (:resourceType res) resource-type) "/" (:id res))}
                                     :response {:status "200"}})
                                  results)))
                        types))]
    {:status 200
     :body {:resourceType "Bundle"
            :type "history"
            :total (count all-entries)
            :entry all-entries}}))

(defn system-search [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        params (merge (or (:form-params req) {}) (or (:query-params req) {}))
        type-param (or (get params "_type") (get params :_type))
        all-registries (:fhir/all-registries req)
        ;; If _type specified, search only those types; otherwise search all
        types (if type-param
                (str/split type-param #",")
                (keys all-registries))
        all-entries (mapcat
                      (fn [resource-type]
                        (let [registry (get all-registries resource-type)
                              results (when registry
                                        (db/search store tenant-id (keyword resource-type)
                                                   (assoc params :_count 50 :_skip 0)
                                                   registry))]
                          (mapv (fn [res]
                                  {:fullUrl (str "/" tenant-id "/fhir/" resource-type "/" (:id res))
                                   :resource res
                                   :search {:mode "match"}})
                                (or results []))))
                      types)]
    {:status 200
     :body {:resourceType "Bundle"
            :type "searchset"
            :total (count all-entries)
            :entry (vec all-entries)}}))

(defn build-resource-decoders
  "Builds {resource-type-string -> decoder-fn} so a resource map can be
   coerced via the FHIR JSON transformer (extension promotion, java.time
   parsing, primitive extension renaming) using the resource's specific
   cap-schema. Used by both the transaction route (entries hold
   heterogeneous resource types and bypass reitit body coercion) and by
   the contained-resource decoder applied at the middleware level (the
   parent cap-schema treats `:contained` as a generic Resource map and
   does not dispatch the right per-type schema)."
  [schemas]
  (let [xf (fjt/fhir-json-transformer)]
    (into {}
          (keep (fn [schema]
                  (let [props (m/properties schema)
                        rt (:resourceType props)]
                    (when rt
                      [rt (m/decoder schema xf)]))))
          schemas)))

(defn build-resource-encoders
  "Builds {resource-type-string -> encoder-fn} for the response side: takes a
   stored resource map and runs the FHIR JSON transformer's encode direction
   to demote promoted extension fields back into the canonical `:extension`
   array. Mirrors `build-resource-decoders` and is used to recursively
   demote `:contained` resources on read."
  [schemas]
  (let [xf (fjt/fhir-json-transformer)]
    (into {}
          (keep (fn [schema]
                  (let [props (m/properties schema)
                        rt (:resourceType props)]
                    (when rt
                      [rt (m/encoder schema xf)]))))
          schemas)))


(defn coerce-resource-by-type
  "Decode a resource map (and its :contained children, recursively) using
   the matching cap-schema decoder from `decoders`. Falls back to the raw
   map when no decoder is registered for the resource type. Each contained
   child is dispatched independently by its own :resourceType."
  [decoders resource]
  (when resource
    (let [rt (:resourceType resource)
          decode (get decoders rt)
          decoded (if decode (try (decode resource) (catch Exception _ resource)) resource)]
      (cond-> decoded
        (seq (:contained decoded))
        (update :contained #(mapv (partial coerce-resource-by-type decoders) %))))))

(defn- decode-contained-only
  "Recursively decode just the :contained children of a resource. Used as
   a post-coercion middleware step on individual create/update routes
   where reitit has already decoded the parent via its cap-schema but the
   parent's `:contained [:sequential [:map …]]` schema does not dispatch
   per-type."
  [decoders resource]
  (if (and (map? resource) (seq (:contained resource)))
    (update resource :contained
            #(mapv (partial coerce-resource-by-type decoders) %))
    resource))

(defn encode-resource-by-type
  "Apply per-resourceType FHIR JSON encoding (demote-extensions, format
   java.time → ISO strings) to a single resource and any of its
   `:contained` children. Falls back to the raw map when no encoder is
   registered for the resource type."
  [encoders resource]
  (when resource
    (let [rt (:resourceType resource)
          encode (get encoders rt)
          encoded (if encode (try (encode resource) (catch Exception _ resource)) resource)]
      (cond-> encoded
        (seq (:contained encoded))
        (update :contained #(mapv (partial encode-resource-by-type encoders) %))))))

(defn- encode-contained-only
  "Recursively encode just the :contained children of a resource for
   responses. The parent itself is encoded by reitit's response coercion
   (or by the storage decoder for routes with no cap-schema response),
   but its `:contained` children are opaque to the parent's cap-schema
   and need per-type encoding here."
  [encoders resource]
  (if (and (map? resource) (seq (:contained resource)))
    (update resource :contained
            #(mapv (partial encode-resource-by-type encoders) %))
    resource))

(defn wrap-encode-contained-response
  "Middleware that recursively encodes (demotes) `:contained` resources in
   the response body. Wraps read/search/history handlers so the wire
   format always matches canonical FHIR JSON, regardless of whether the
   stored shape was promoted."
  [handler encoders]
  (fn [req]
    (let [resp (handler req)
          body (:body resp)]
      (cond
        ;; Search/history bundles: walk entries and encode their resources
        (and (map? body) (= "Bundle" (:resourceType body)) (seq (:entry body)))
        (update-in resp [:body :entry]
                   #(mapv (fn [entry]
                            (if (:resource entry)
                              (update entry :resource (partial encode-contained-only encoders))
                              entry))
                          %))

        (and (map? body) (:resourceType body))
        (update resp :body (partial encode-contained-only encoders))

        :else resp))))

(defn wrap-decode-contained
  "Middleware that recursively decodes `:contained` resources in the
   request body using per-resourceType cap-schema decoders. Runs after
   reitit's coerce-request-middleware so the parent body is already in
   its promoted/decoded form, but contained children (which the parent
   cap-schema treats as opaque Resource maps) still need per-type
   dispatching."
  [handler decoders]
  (fn [req]
    (let [resource-type (or (:fhir/resource-type req)
                            (get-in req [:parameters :body :resourceType]))
          req' (t/trace!
                {:id :fhir/decode
                 :data {:resource-type resource-type}}
                (cond-> req
                  (and (get-in req [:parameters :body])
                       (seq (get-in req [:parameters :body :contained])))
                  (update-in [:parameters :body] (partial decode-contained-only decoders))))]
      (handler req'))))

(defn transaction [decoders]
  (fn [req]
    (let [store (:fhir/store req)
          tenant-id (-> req :path-params :tenant-id)
          body (:body-params req)
          resource-type (:resourceType body)
          bundle-type (:type body)
          raw-entries (:entry body)
          entries (mapv (fn [entry]
                          (if (:resource entry)
                            (update entry :resource #(coerce-resource-by-type decoders %))
                            entry))
                        raw-entries)]
    (if (and (= resource-type "Bundle") (#{"transaction" "batch"} bundle-type))
      (if (= bundle-type "transaction")
        ;; Transaction: atomic — all succeed or all fail
        (try
          (let [res (t/trace!
                     {:id :bundle/transaction
                      :data {:tenant-id tenant-id
                             :entry-count (count entries)}}
                     (db/transact-bundle store tenant-id entries))]
            {:status 200 :body res})
          (catch Exception e
            {:status 400
             :body {:resourceType "OperationOutcome"
                    :issue [{:severity "error"
                             :code "transient"
                             :diagnostics (str "Transaction failed: " (ex-message e))}]}}))
        ;; Batch: process each entry independently — each succeeds or fails on its own
        (let [results (mapv
                        (fn [entry]
                          (try
                            (let [req-map (:request entry)
                                  method (some-> (:method req-map) str/upper-case)
                                  url (:url req-map)
                                  parts (when url (str/split url #"/"))
                                  resource-type (first parts)
                                  id (second parts)
                                  resource (:resource entry)]
                              (t/trace!
                               {:id :bundle/entry
                                :data {:method method
                                       :resource-type resource-type
                                       :id id}}
                              (case method
                                "POST"
                                (let [new-id (str (java.util.UUID/randomUUID))
                                      res (db/create-resource store tenant-id (keyword resource-type) new-id resource)
                                      vid (get-in res [:meta :versionId])
                                      last-mod (str (get-in res [:meta :lastUpdated]))]
                                  {:resource res
                                   :response (cond-> {:status "201 Created"}
                                               vid (assoc :etag (str "W/\"" vid "\"")
                                                          :location (str "/" tenant-id "/fhir/" resource-type "/" (:id res) "/_history/" vid))
                                               last-mod (assoc :lastModified last-mod))})

                                "PUT"
                                (let [res (db/update-resource store tenant-id (keyword resource-type) id resource)
                                      vid (get-in res [:meta :versionId])
                                      last-mod (str (get-in res [:meta :lastUpdated]))]
                                  {:resource res
                                   :response (cond-> {:status "200 OK"}
                                               vid (assoc :etag (str "W/\"" vid "\""))
                                               last-mod (assoc :lastModified last-mod))})

                                "DELETE"
                                (do (db/delete-resource store tenant-id (keyword resource-type) id)
                                    {:response {:status "204 No Content"}})

                                "GET"
                                (let [res (db/read-resource store tenant-id (keyword resource-type) id)]
                                  (if res
                                    (let [vid (get-in res [:meta :versionId])
                                          last-mod (str (get-in res [:meta :lastUpdated]))]
                                      {:resource res
                                       :response (cond-> {:status "200 OK"}
                                                   vid (assoc :etag (str "W/\"" vid "\""))
                                                   last-mod (assoc :lastModified last-mod))})
                                    {:response {:status "404 Not Found"
                                                :outcome {:resourceType "OperationOutcome"
                                                          :issue [{:severity "error"
                                                                   :code "not-found"
                                                                   :diagnostics (str resource-type "/" id " not found")}]}}}))

                                ;; Unknown method
                                {:response {:status "400 Bad Request"
                                            :outcome {:resourceType "OperationOutcome"
                                                      :issue [{:severity "error"
                                                               :code "invalid"
                                                               :diagnostics (str "Unsupported method: " method)}]}}})))
                            (catch Exception e
                              {:response {:status "400 Bad Request"
                                          :outcome {:resourceType "OperationOutcome"
                                                    :issue [{:severity "error"
                                                             :code "exception"
                                                             :diagnostics (str "Entry failed: " (ex-message e))}]}}})))
                        entries)]
          {:status 200
           :body {:resourceType "Bundle"
                  :type "batch-response"
                  :entry results}}))
      {:status 400
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "invalid"
                       :diagnostics "Expected a Bundle of type transaction or batch"}]}}))))
