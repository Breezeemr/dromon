(ns server.handlers
  (:require [fhir-store.protocol :as db]
            [fhir-terminology.protocol :as terminology]
            [malli.core :as m]
            [malli.transform :as mt]
            [clojure.string :as str]
            [com.breezeehr.fhir-json-transform :as fjt]
            [server.compartment :as compartment]
            [server.json-patch :as json-patch]
            [server.search-registry :as sr]
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

;; ---------------------------------------------------------------------------
;; Unsupported search parameters (FHIR R4B §3.1.1.4, "Handling Errors")
;; ---------------------------------------------------------------------------
;;
;; A search parameter this resource type does not declare cannot be turned into
;; a query constraint. Silently discarding it turns a filtered search into an
;; unfiltered one, so the constraint is never dropped without saying so:
;; type-level search rejects the request unless the client opts into
;; `Prefer: handling=lenient`, in which case the Bundle carries an
;; OperationOutcome warning naming what was ignored. Conditional interactions
;; select a resource to mutate and have no lenient mode at all — they always
;; fail closed.

(defn- prefer-handling
  "The `handling=` directive of the request's Prefer header: :strict, :lenient,
   or nil when absent or unrecognized."
  [req]
  (when-let [header (get-in req [:headers "prefer"])]
    (when-let [m (re-find #"handling=(strict|lenient)" header)]
      (keyword (second m)))))

(defn- unsupported-params-issues
  [resource-type severity param-names]
  (mapv (fn [p]
          {:severity severity
           :code "not-supported"
           :details {:text (str "Unknown search parameter \"" p "\" for resource type "
                                resource-type)}
           :diagnostics (str "Search parameter \"" p "\" is not one of the parameters "
                             resource-type " declares in the server's CapabilityStatement, "
                             "so it cannot restrict the result set. Remove it, or resend "
                             "with the header 'Prefer: handling=lenient' to have it "
                             "ignored.")})
        param-names))

(defn- unsupported-params-response
  "400 OperationOutcome naming every search parameter `resource-type` cannot
   honour."
  [resource-type param-names]
  {:status 400
   :headers {"Prefer" "handling=strict"}
   :body {:resourceType "OperationOutcome"
          :issue (unsupported-params-issues resource-type "error" param-names)}})

(defn- unsupported-params-entry
  "A Bundle entry carrying the `handling=lenient` warning for the parameters
   that were ignored. FHIR R4B §3.1.1.4 requires the outcome to travel in the
   searchset itself, with search.mode = \"outcome\"."
  [base-url resource-type param-names]
  {:fullUrl (str base-url "/_search-outcome")
   :resource {:resourceType "OperationOutcome"
              :issue (unsupported-params-issues resource-type "warning" param-names)}
   :search {:mode "outcome"}})

(defn- conditional-criteria-error
  "The 400 response for a conditional interaction whose criteria this resource
   type cannot honour, or nil when the criteria are fully supported. Missing
   criteria are an error too: a conditional interaction with nothing to match
   on would select an arbitrary resource to update or delete."
  [resource-type registry params]
  (let [unsupported (sr/unsupported-filter-params registry params)]
    (cond
      (seq unsupported)
      (unsupported-params-response resource-type unsupported)

      (empty? (sr/filter-params params))
      {:status 400
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "invalid"
                       :diagnostics (str "Conditional interactions on " resource-type
                                         " require at least one search parameter")}]}})))

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
  "Handler for PUT /[type]/:id RESTful interaction.
   The store enforces optimistic concurrency atomically when :if-match is
   supplied in opts; we no longer do a pre-read comparison here (TOCTOU)."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        id (-> req :path-params :id)
        resource-body (get-in req [:parameters :body])
        body-id (:id resource-body)
        expected-version (parse-if-match req)]
    (if (and body-id (not= body-id id))
      {:status 400
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "invalid"
                       :diagnostics (str "Resource id in body (" body-id ") does not match URL id (" id ")")}]}}
      (if expected-version
        ;; With If-Match: delegate entirely to the store. A missing/deleted
        ;; resource, or a version mismatch, surfaces as 412 ex-info handled
        ;; by wrap-fhir-exceptions.
        (let [res (db/update-resource store tenant-id (keyword resource-type) id
                                      resource-body {:if-match expected-version})]
          {:status 200 :body res})
        ;; Without If-Match: preserve the create-with-client-id upsert path
        ;; for nonexistent resources. Existing resources take the normal
        ;; update path.
        (let [existing (db/read-resource store tenant-id (keyword resource-type) id)]
          (if existing
            (let [res (db/update-resource store tenant-id (keyword resource-type) id resource-body)]
              {:status 200 :body res})
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
        expected-version (parse-if-match req)
        existing (db/read-resource store tenant-id (keyword resource-type) id)]
    (cond
      ;; Missing resource with If-Match: 412 regardless of whether the read
      ;; above sees it; we're still inside the same request so it's fine to
      ;; short-circuit here for the semantics.
      (and expected-version (nil? existing))
      (precondition-failed-response resource-type id expected-version nil)

      (nil? existing)
      {:status 404
       :body {:resourceType "OperationOutcome"
              :issue [{:severity "error"
                       :code "not-found"
                       :diagnostics (str resource-type "/" id " not found")}]}}

      :else
      (let [patched (json-patch/apply-patch existing patch-ops)
            opts (when expected-version {:if-match expected-version})
            result (if opts
                     (db/update-resource store tenant-id (keyword resource-type) id patched opts)
                     (db/update-resource store tenant-id (keyword resource-type) id patched))]
        {:status 200 :body result}))))

(defn delete-resource
  "Handler for DELETE /[type]/:id RESTful interaction."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        id (-> req :path-params :id)
        expected-version (parse-if-match req)]
    (if expected-version
      (do (db/delete-resource store tenant-id (keyword resource-type) id
                              {:if-match expected-version})
          {:status 204 :body nil})
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

;; Per-(tenant, resource-type, normalized-search) named locks for conditional
;; create. Conditional create (POST + If-None-Exist) is otherwise TOCTOU-racy:
;; the search and the subsequent insert are not atomic, so two concurrent
;; requests with the same If-None-Exist can both observe zero matches and both
;; insert. Serializing on a stable key closes that window. Plain POSTs do not
;; touch this map.
(def ^:private conditional-create-locks (atom {}))

(defn- normalize-search-params
  "Stable serialization of search params so equivalent queries hash to the
   same lock key. Sorts entries by key name."
  [params]
  (->> params
       (map (fn [[k v]] [(name k) (str v)]))
       (sort-by first)
       vec))

(defn- conditional-create-lock
  "Return (and intern) the lock object for this conditional-create key."
  [tenant-id resource-type normalized-params]
  (let [k [tenant-id resource-type normalized-params]]
    (or (get @conditional-create-locks k)
        (-> (swap! conditional-create-locks
                   (fn [m]
                     (if (contains? m k)
                       m
                       (assoc m k (Object.)))))
            (get k)))))

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
      ;; Conditional create: serialize search+create on a per-tenant,
      ;; per-type, per-search-param-set lock to close the TOCTOU window
      ;; described in FHIR R4 §3.1.0.8.1.
      (let [search-params (parse-query-string if-none-exist)
            normalized (normalize-search-params search-params)
            lock (conditional-create-lock tenant-id resource-type normalized)]
        (or
         (conditional-criteria-error resource-type search-registry search-params)
         (locking lock
           (let [results (db/search store tenant-id (keyword resource-type)
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
                                :diagnostics "Conditional create found multiple matches"}]}})))))
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
  "Handler for GET /[type] RESTful interaction.

   Parameters the resource type does not declare are rejected with a 400
   OperationOutcome. `Prefer: handling=lenient` instead ignores them and
   returns the (correspondingly wider) result set with an OperationOutcome
   warning entry naming each one, per FHIR R4B §3.1.1.4."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        raw-params (merge (or (:form-params req) {}) (or (:query-params req) {}))
        search-registry (:fhir/search-registry req)
        unsupported (sr/unsupported-filter-params search-registry raw-params)
        lenient? (= :lenient (prefer-handling req))

        ;; Under handling=lenient the ignored parameters are dropped from
        ;; everything downstream — the store call and the Bundle's own links —
        ;; so the self link describes the search that actually ran.
        params (if (seq unsupported)
                 (into {} (remove (fn [[k _]] (some #{(name k)} unsupported))) raw-params)
                 raw-params)

        ;; Extract _include and _revinclude before passing to search
        include-param (or (get params "_include") (get params :_include))
        revinclude (or (get params "_revinclude") (get params :_revinclude))

        ;; Ensure we have _count and _skip, defaulting to 50 and 0
        count-param (or (get params :_count) (get params "_count") "50")
        skip-param (or (get params :_skip) (get params "_skip") "0")

        limit (parse-non-negative-int "_count" (str count-param))
        skip (parse-non-negative-int "_skip" (str skip-param))]
    (if (and (seq unsupported) (not lenient?))
      (unsupported-params-response resource-type unsupported)
      (if-let [err (or (:error limit) (:error skip))]
        err
        (let [base-url (str "/" tenant-id "/fhir/" resource-type)
              outcome-entry (when (seq unsupported)
                              (unsupported-params-entry base-url resource-type unsupported))]
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
               :body (cond-> {:resourceType "Bundle"
                              :type "searchset"
                              :total total
                              :link [self-link]}
                       outcome-entry (assoc :entry [outcome-entry]))})
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
                                (seq revinc-entries) (into revinc-entries)
                                outcome-entry (conj outcome-entry))]
              {:status 200
               :body {:resourceType "Bundle"
                      :type "searchset"
                      :total (count results)
                      :link links
                      :entry all-entries}})))))))

(defn conditional-update
  "Handler for PUT /[type]?[search params] — conditional update."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        resource-body (get-in req [:parameters :body])
        search-registry (:fhir/search-registry req)
        params (merge (or (:query-params req) {}) (or (:form-params req) {}))]
    (or
     (conditional-criteria-error resource-type search-registry params)
     (let [results (db/search store tenant-id (keyword resource-type)
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
                          :diagnostics "Conditional update matched multiple resources"}]}})))))

(defn conditional-delete
  "Handler for DELETE /[type]?[search params] — conditional delete."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        search-registry (:fhir/search-registry req)
        params (merge (or (:query-params req) {}) (or (:form-params req) {}))]
    (or
     (conditional-criteria-error resource-type search-registry params)
     (let [results (db/search store tenant-id (keyword resource-type)
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
                          :diagnostics "Conditional delete matched multiple resources"}]}})))))

(defn conditional-patch
  "Handler for PATCH /[type]?[search params] — conditional patch."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        resource-type (:fhir/resource-type req)
        patch-ops (get-in req [:parameters :body])
        search-registry (:fhir/search-registry req)
        params (merge (or (:query-params req) {}) (or (:form-params req) {}))]
    (or
     (conditional-criteria-error resource-type search-registry params)
     (let [results (db/search store tenant-id (keyword resource-type)
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
                          :diagnostics "Conditional patch matched multiple resources"}]}})))))

;; ---------------------------------------------------------------------------
;; Compartment search (FHIR R4 §3.3.1)
;; ---------------------------------------------------------------------------

(defn- compartment-confined-search
  "Runs a compartment-confined search for `rt`, applying the UNION of the R4B
   link parameters via server.compartment/confine. Returns the matching
   resources, or [] when the type is a member with no registered link parameter
   (fail closed)."
  [store tenant-id compartment-type compartment-id rt params registry]
  (let [outcome (compartment/confine compartment-type compartment-id rt params registry)]
    (cond
      (= :passthrough outcome) []
      (= :deny outcome) []
      :else (let [[_ p r] outcome]
              (db/search store tenant-id (keyword rt) p r)))))

(defn compartment-search
  "Handler for GET /:tenant-id/fhir/:compartment-type/:compartment-id/:target-type
   Searches for resources of target-type that belong to the given compartment.
   Membership is the UNION of the link parameters the R4B CompartmentDefinition
   lists for each type (e.g. Observation via subject OR performer)."
  [req]
  (let [store            (:fhir/store req)
        tenant-id        (-> req :path-params :tenant-id)
        compartment-type (-> req :path-params :compartment-type)
        compartment-id   (-> req :path-params :compartment-id)
        target-type      (-> req :path-params :target-type)
        all-registries   (:fhir/all-registries req)
        compartment-map  (get compartment/compartment-definitions compartment-type)]
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
            ;; The same params are applied to every member type, so only the
            ;; ones every type honours are accepted (see `system-search`).
            unsupported (sr/unsupported-filter-params nil params)]
        (if (seq unsupported)
          (unsupported-params-response (str compartment-type " compartment search") unsupported)
          (let [entries (vec
                          (mapcat
                            (fn [[rt _params]]
                              (when-let [registry (get all-registries rt)]
                                (let [results (compartment-confined-search
                                                store tenant-id compartment-type compartment-id rt
                                                (assoc params :_count 50 :_skip 0) registry)]
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
                    :entry entries}})))

      ;; Specific target resource type
      :else
      (if (nil? (compartment/compartment-link-params compartment-type target-type))
        {:status 400
         :body {:resourceType "OperationOutcome"
                :issue [{:severity "error"
                         :code "invalid"
                         :diagnostics (str target-type " is not a member of the "
                                           compartment-type " compartment")}]}}
        (let [registry  (get all-registries target-type)
              params    (merge (or (:query-params req) {}) (or (:form-params req) {}))
              unsupported (sr/unsupported-filter-params registry params)]
          (if (seq unsupported)
            (unsupported-params-response target-type unsupported)
            (let [count-param (or (get params :_count) (get params "_count") "50")
                skip-param  (or (get params :_skip) (get params "_skip") "0")
                limit (if (string? count-param) (parse-long count-param) count-param)
                skip  (if (string? skip-param)  (parse-long skip-param)  skip-param)
                results (if registry
                          (compartment-confined-search
                            store tenant-id compartment-type compartment-id target-type
                            (assoc params :_count limit :_skip skip) registry)
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
                    :entry entries}})))))))

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

(def ^:private bulk-export-resource-operations
  "Bulk Data IG per-resource $export operation declarations. Inferno's
   bulk_data operation_support check locates Patient- and Group-level export at
   rest.resource[type].operation, matching operation.name = \"export\" and the
   type's OperationDefinition canonical (system-level export is checked at the
   rest level instead). See BulkDataExportOperationTests#check_export_support."
  {"Patient" {:name "export"
              :definition "http://hl7.org/fhir/uv/bulkdata/OperationDefinition/patient-export"}
   "Group"   {:name "export"
              :definition "http://hl7.org/fhir/uv/bulkdata/OperationDefinition/group-export"}})

(defn- with-bulk-export-operations
  "Ensure the Patient and Group resource capabilities declare the Bulk Data
   $export operation. Patient is a registered resource type, so its existing
   entry is augmented in place; Group is not a registered type here, so a
   minimal resource entry is appended when absent."
  [resources]
  (let [present (into #{} (map :type) resources)
        augment (fn [entry]
                  (if-let [op (bulk-export-resource-operations (:type entry))]
                    (update entry :operation (fnil conj []) op)
                    entry))]
    (cond-> (mapv augment resources)
      (not (contains? present "Group"))
      (conj {:type "Group"
             :operation [(bulk-export-resource-operations "Group")]}))))

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
              ;; The bulk-data IG canonical advertises Bulk Data Access
              ;; ($export) support; Inferno's bulk_data suite asserts it is
              ;; present in `instantiates`.
              :instantiates ["http://hl7.org/fhir/us/core/CapabilityStatement/us-core-server"
                             "http://hl7.org/fhir/uv/bulkdata/CapabilityStatement/bulk-data"]
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
                      ;; System-level Bulk Data $export. Inferno's bulk_data
                      ;; operation_support check locates system export at the
                      ;; rest level (operation.name "export"); the patient- and
                      ;; group-level declarations live under their respective
                      ;; rest.resource entries (see with-bulk-export-operations).
                      :operation [{:name "export"
                                   :definition "http://hl7.org/fhir/uv/bulkdata/OperationDefinition/export"}]
                      :resource (with-bulk-export-operations resources)}]}})))

;; SMART Backend Services (bulk data) discovery advertised below only declares
;; what the token endpoint supports; the token issuance and `private_key_jwt`
;; RS384/ES384 client-assertion validation are entirely Ory Hydra's job. To
;; make a discovered backend-services flow actually work (Phase A of
;; docs/proposals/bulk-data-export-and-backend-services.md), the deployment
;; must, OUTSIDE dromon:
;;   1. Register an Ory Hydra client with
;;        grant_types:              ["client_credentials"]
;;        token_endpoint_auth_method: "private_key_jwt"
;;        jwks (or jwks_uri):        the PUBLIC half of the key the client
;;                                   (Inferno) signs its assertions with.
;;   2. Confirm Hydra accepts RS384/ES384 client assertions (must match the
;;      algs advertised here).
;;   3. Grant that client subject a Keto "system" read tuple so the resulting
;;      RS256 access token passes dromon's Keto authorization.
;; dromon's own token validation (Hydra RS256 access tokens) is unchanged.
(defn smart-configuration
  "Returns SMART configuration. When called as a 0-arg handler, uses env vars
   or localhost defaults. When called with an oauth-base-url, uses that."
  ([] (smart-configuration nil))
  ([oauth-base-url]
   (let [base (or oauth-base-url
                  (System/getenv "OAUTH_BASE_URL")
                  "http://localhost:4444")]
     (fn [_req]
       ;; Return a plain map body (no explicit Content-Type) so the muuntaja
       ;; response middleware serializes it, exactly like the /metadata
       ;; handler. Setting Content-Type here suppresses that encoding and Ring
       ;; then fails to stream the raw map (HTTP 500).
       {:status 200
        :body {:issuer                 base
               :jwks_uri               (str base "/.well-known/jwks.json")
               :authorization_endpoint (str base "/oauth2/auth")
               :token_endpoint         (str base "/oauth2/token")
               :token_endpoint_auth_methods_supported ["client_secret_basic" "private_key_jwt"]
               ;; SMART Backend Services (SMART App Launch v2.2.0) requires the
               ;; asymmetric client-assertion signing algorithms the server's
               ;; token endpoint (Ory Hydra) accepts for `private_key_jwt`.
               :token_endpoint_auth_signing_alg_values_supported ["RS384" "ES384"]
               :grant_types_supported  ["authorization_code" "client_credentials"]
               :code_challenge_methods_supported ["S256"]
               ;; `system/*.read` and `system/*.rs` advertise SMART Backend
               ;; Services (bulk data) scopes alongside the launch/patient/user
               ;; scopes.
               :scopes_supported       ["openid" "profile" "launch" "launch/patient"
                                        "patient/*.read" "patient/*.write"
                                        "user/*.read" "user/*.write"
                                        "system/*.read" "system/*.rs"]
               :response_types_supported ["code"]
               ;; `sso-openid-connect` in :capabilities requires `issuer` +
               ;; `jwks_uri` to be present (SMART App Launch STU2 discovery).
               ;; `client-confidential-asymmetric` advertises the
               ;; `private_key_jwt` backend-services authentication method.
               :capabilities           ["launch-standalone" "client-public" "client-confidential-symmetric"
                                        "client-confidential-asymmetric"
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

(defn system-search
  "Handler for GET|POST /_search — search across every resource type.

   A system-level search can only be expressed with parameters every type
   shares, so anything beyond the result parameters and the resource-level
   filters (`_id`, `_tag`, `_security`, `_profile`) is rejected rather than
   applied per type, where it would silently degrade to an unfiltered scan of
   the types that do not declare it."
  [req]
  (let [store (:fhir/store req)
        tenant-id (-> req :path-params :tenant-id)
        params (merge (or (:form-params req) {}) (or (:query-params req) {}))
        type-param (or (get params "_type") (get params :_type))
        all-registries (:fhir/all-registries req)
        unsupported (sr/unsupported-filter-params nil params)
        ;; If _type specified, search only those types; otherwise search all
        types (if type-param
                (str/split type-param #",")
                (keys all-registries))]
    (if (seq unsupported)
      (unsupported-params-response "system-level search" unsupported)
      (let [all-entries (mapcat
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
                :entry (vec all-entries)}}))))

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
          ;; Capture the decoded request via a volatile and return a
          ;; constant from the trace body so Telemere's :run-val never
          ;; holds a ring request (which carries :reitit.core/match and
          ;; :fhir/store, both of which can OOM pr-str fallback).
          decoded (volatile! nil)
          _ (t/trace!
              {:id :fhir/decode
               :data {:resource-type resource-type}}
              (do (vreset! decoded
                           (cond-> req
                             (and (get-in req [:parameters :body])
                                  (seq (get-in req [:parameters :body :contained])))
                             (update-in [:parameters :body] (partial decode-contained-only decoders))))
                  ::ok))]
      (handler @decoded))))

(defn- decode-bundle-entries
  "Walks Bundle entries and decodes each entry's :resource (if any) using
   the per-resourceType cap-schema decoder map. Emits a per-entry
   `:bundle/entry` telemere span scoped only to the decode work."
  [decoders raw-entries]
  (mapv
   (fn [idx entry]
     (let [req-map (:request entry)
           method (some-> (:method req-map) str/upper-case)
           url (:url req-map)
           parts (when url (str/split url #"/"))
           entry-rt (first parts)
           entry-id (second parts)]
       (t/trace!
        {:id :bundle/entry
         :data {:index idx
                :method method
                :resource-type entry-rt
                :id entry-id}}
        (if (:resource entry)
          (update entry :resource
                  #(coerce-resource-by-type decoders %))
          entry))))
   (range)
   raw-entries))

(defn transaction [decoders]
  (fn [req]
    (let [store (:fhir/store req)
          tenant-id (-> req :path-params :tenant-id)
          body (:body-params req)
          resource-type (:resourceType body)
          bundle-type (:type body)
          raw-entries (:entry body)]
      (if (and (= resource-type "Bundle") (#{"transaction" "batch"} bundle-type))
        (if (= bundle-type "transaction")
          ;; Transaction: atomic — all succeed or all fail
          (try
            (let [entries (t/trace!
                           {:id :bundle/transaction
                            :data {:tenant-id tenant-id
                                   :entry-count (count raw-entries)}}
                           (decode-bundle-entries decoders raw-entries))
                  res (db/transact-transaction store tenant-id entries)]
              {:status 200 :body res})
            (catch Exception e
              {:status 400
               :body {:resourceType "OperationOutcome"
                      :issue [{:severity "error"
                               :code "transient"
                               :diagnostics (str "Transaction failed: " (ex-message e))}]}}))
          ;; Batch: each entry independent. Decode entries (with per-entry
          ;; spans), then hand off to the store's batch impl which emits
          ;; :store/transact-bundle around its work.
          (let [entries (decode-bundle-entries decoders raw-entries)
                res (db/transact-bundle store tenant-id entries)]
            {:status 200 :body res}))
        {:status 400
         :body {:resourceType "OperationOutcome"
                :issue [{:severity "error"
                         :code "invalid"
                         :diagnostics "Expected a Bundle of type transaction or batch"}]}}))))
