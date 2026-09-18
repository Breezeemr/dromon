(ns fhir-store.mock.core
  (:require [clojure.string :as str]
            [fhir-store.protocol :as protocol]
            [taoensso.telemere :as t]
            [fhir-store.trace :as ftrace]))

(defn- method-order
  "Returns sort key for FHIR transaction entry processing order per §3.1.0.11.2:
   DELETE (0) → POST (1) → PUT/PATCH (2) → GET/HEAD (3)."
  [method]
  (case (str/upper-case (or method "GET"))
    "DELETE" 0
    "POST" 1
    "PUT" 2
    "PATCH" 2
    "GET" 3
    "HEAD" 3
    4))

(defn- new-id []
  (str (java.util.UUID/randomUUID)))

(defn- match-column
  "Check if a resource field matches v-str using a column descriptor from the search registry."
  [res col-desc v-str]
  (let [field-val (get res (keyword (:col col-desc)))
        sub-col (:sub-col col-desc)]
    (cond
      (nil? field-val) false

      ;; Array field with sub-column (e.g., name[*].family)
      (and (:array? col-desc) sub-col (sequential? field-val))
      (some (fn [item] (= (get item (keyword sub-col)) v-str)) field-val)

      ;; Array field without sub-column (e.g., target[*])
      (and (:array? col-desc) (sequential? field-val))
      (some (fn [item]
              (cond
                (string? item) (= item v-str)
                (map? item) (or (= (:reference item) v-str)
                                (= (:value item) v-str)
                                (= (:code item) v-str)
                                (some (fn [c] (= (:code c) v-str)) (:coding item)))
                :else (= (str item) v-str)))
            field-val)

      ;; Non-array with sub-column
      (and sub-col (map? field-val))
      (= (get field-val (keyword sub-col)) v-str)

      ;; Direct field
      (string? field-val) (= field-val v-str)
      (map? field-val) (or (= (:reference field-val) v-str)
                           (= (:value field-val) v-str))
      :else (= (str field-val) v-str))))

(defn- match-param
  "Match a search parameter against a resource, using the search registry when available."
  [res search-registry k v]
  (let [k-name (name k)
        v-str (str v)]
    (if (= k-name "_id")
      ;; A comma-separated list is an OR over ids, per FHIR R4B 3.1.1.5. The
      ;; handlers' _include batching sends exactly that shape, so a mock that
      ;; only matched a single id could never exercise it.
      (contains? (set (str/split v-str #",")) (:id res))
      (if-let [param-desc (get search-registry k-name)]
        ;; Use registry - match if ANY column matches
        (some #(match-column res % v-str) (:columns param-desc))
        ;; Fallback to direct field match
        (let [field-val (get res (keyword k-name))]
          (cond
            (nil? field-val) false
            (string? field-val) (= field-val v-str)
            (and (sequential? field-val) (every? map? field-val))
            (some (fn [item]
                    (or (= (:value item) v-str)
                        (= (:reference item) v-str)
                        (= (:code item) v-str)
                        (some (fn [c] (= (:code c) v-str)) (:coding item))))
                  field-val)
            (map? field-val) (or (= (:reference field-val) v-str)
                                 (= (:value field-val) v-str))
            :else (= (str field-val) v-str)))))))

;; ---------------------------------------------------------------------------
;; Transaction metadata
;;
;; The mock is a real `ITxMetadataStore`, not a store that merely tolerates
;; the opts key: it records what it was handed, per version, beside the record
;; itself. That is what makes the channel testable end to end inside dromon --
;; a store that accepted the map and forgot it would pass every test the seam
;; could write, which is precisely the failure the protocol warns about.
;; ---------------------------------------------------------------------------

(defn- stamp
  "Record `m` as the metadata of version `vid` on a store record."
  [record vid m]
  (cond-> record
    m (-> (assoc :tx-meta m)
          (assoc-in [:tx-meta-history vid] m))))

(defn tx-meta-of
  "The transaction metadata recorded for the CURRENT version of a resource, or
   nil. Test accessor; the mock is the only store in this repo that keeps it."
  [store tenant-id resource-type id]
  (:tx-meta (get-in @(:state store) [tenant-id resource-type id])))

(defn tx-meta-history
  "Every version's transaction metadata for one resource, as {vid m}."
  [store tenant-id resource-type id]
  (or (:tx-meta-history (get-in @(:state store) [tenant-id resource-type id])) {}))

(defrecord MockStore [state options]
  protocol/IFHIRStore
  (create-resource [this tenant-id resource-type id resource]
    (protocol/create-resource this tenant-id resource-type id resource nil))

  (create-resource [_ tenant-id resource-type id resource opts]
    (let [id (or id (new-id))
          vid "1"
          tx-m (protocol/tx-meta opts)
          meta-info {:versionId vid
                     :lastUpdated (java.time.Instant/now)}
          resource-with-meta (-> resource
                                 (update :meta merge meta-info)
                                 (assoc :id id))
          record (stamp {:history {vid resource-with-meta}
                         :current vid
                         :resource resource-with-meta
                         :deleted? false}
                        vid tx-m)]
      (swap! state update-in [tenant-id resource-type id]
             (fn [existing]
               (if existing
                 (throw (ex-info "Resource already exists" {:id id :type resource-type}))
                 record)))
      resource-with-meta))

  (read-resource [_ tenant-id resource-type id]
    (let [s @state
          record (get-in s [tenant-id resource-type id])]
      (if (and record (not (:deleted? record)))
        (:resource record)
        nil)))

  (vread-resource [_ tenant-id resource-type id vid]
    (let [s @state
          record (get-in s [tenant-id resource-type id])]
      (if record
        (get-in record [:history vid])
        nil)))

  (update-resource [this tenant-id resource-type id resource]
    (protocol/update-resource this tenant-id resource-type id resource nil))

  (update-resource [_ tenant-id resource-type id resource opts]
    (let [expected (protocol/normalize-if-match (:if-match opts))
          tx-m (protocol/tx-meta opts)
          result (atom nil)
          swap-fn (fn [existing]
                    (let [active? (and existing (not (:deleted? existing)))
                          current-vid (when existing (:current existing))
                          ;; `If-Match: *` guards on existence alone: any live
                          ;; version satisfies it, so only the existence check
                          ;; below applies to it.
                          any? (= protocol/if-match-any expected)]
                      (when (and expected (not active?))
                        (throw (ex-info "Version conflict"
                                        {:fhir/status 412
                                         :fhir/code "conflict"
                                         :expected (protocol/if-match-label expected)
                                         :actual nil})))
                      (when (and expected (not any?) active? (not= expected current-vid))
                        (throw (ex-info "Version conflict"
                                        {:fhir/status 412
                                         :fhir/code "conflict"
                                         :expected expected
                                         :actual current-vid})))
                      (let [vid (if current-vid
                                  (str (inc (Long/parseLong current-vid)))
                                  "1")
                            meta-info {:versionId vid
                                       :lastUpdated (java.time.Instant/now)}
                            resource-with-meta (-> resource
                                                   (update :meta merge meta-info)
                                                   (assoc :id id))
                            record (stamp (assoc existing
                                                 :history (assoc (or (:history existing) {}) vid resource-with-meta)
                                                 :current vid
                                                 :resource resource-with-meta
                                                 :deleted? false)
                                          vid tx-m)]
                        (reset! result resource-with-meta)
                        record)))]
      (swap! state update-in [tenant-id resource-type id] swap-fn)
      @result))

  (delete-resource [this tenant-id resource-type id]
    (protocol/delete-resource this tenant-id resource-type id nil))

  (delete-resource [_ tenant-id resource-type id opts]
    (let [expected (protocol/normalize-if-match (:if-match opts))
          tx-m (protocol/tx-meta opts)
          result (atom false)
          swap-fn (fn [existing]
                    (let [active? (and existing (not (:deleted? existing)))
                          current-vid (when existing (:current existing))
                          any? (= protocol/if-match-any expected)]
                      (when (and expected (not active?))
                        (throw (ex-info "Version conflict"
                                        {:fhir/status 412
                                         :fhir/code "conflict"
                                         :expected (protocol/if-match-label expected)
                                         :actual nil})))
                      (when (and expected (not any?) active? (not= expected current-vid))
                        (throw (ex-info "Version conflict"
                                        {:fhir/status 412
                                         :fhir/code "conflict"
                                         :expected expected
                                         :actual current-vid})))
                      (if active?
                        (let [vid (str (inc (Long/parseLong current-vid)))]
                          (reset! result true)
                          (stamp (assoc existing
                                        :current vid
                                        :deleted? true
                                        :resource nil)
                                 vid tx-m))
                        existing)))]
      (swap! state update-in [tenant-id resource-type id] swap-fn)
      @result))

  (resource-deleted? [_ tenant-id resource-type id]
    (let [s @state
          record (get-in s [tenant-id (keyword resource-type) id])]
      (boolean (and record (:deleted? record)))))

  (search [_ tenant-id resource-type params search-registry]
    (let [s @state
          resources (vals (get-in s [tenant-id resource-type]))
          active-resources (map :resource (remove :deleted? resources))
          ;; Remove result params from filter criteria
          result-params #{"_count" "_skip" "_offset" "_sort" "_include" "_revinclude"
                          "_total" "_elements" "_contained" "_containedType"
                          "_summary" "_format" "_pretty" "_type"}
          filter-params (into {} (remove (fn [[k _]] (contains? result-params (name k)))) params)
          ;; Apply basic filter matching using search registry
          filtered (if (seq filter-params)
                     (filter (fn [res]
                               (every? (fn [[k v]]
                                         (match-param res search-registry k v))
                                       filter-params))
                             active-resources)
                     active-resources)
          ;; Parse _sort parameter for in-memory sorting
          sort-str (or (get params "_sort") (get params :_sort))
          sorted (if sort-str
                   (let [specs (mapv (fn [s]
                                       (if (str/starts-with? s "-")
                                         {:field (keyword (subs s 1)) :dir :desc}
                                         {:field (keyword s) :dir :asc}))
                                     (str/split sort-str #","))]
                     ;; Sort by specs in reverse order (last spec is least significant)
                     (reduce (fn [coll {:keys [field dir]}]
                               (sort-by (fn [r] (get r field))
                                        (if (= dir :desc) #(compare %2 %1) compare)
                                        coll))
                             filtered
                             (reverse specs)))
                   filtered)
          ;; Apply pagination
          raw-count (or (get params :_count) (get params "_count") "50")
          raw-skip (or (get params :_skip) (get params "_skip") "0")
          limit (if (string? raw-count) (parse-long raw-count) raw-count)
          offset (if (string? raw-skip) (parse-long raw-skip) raw-skip)]
      (->> sorted (drop offset) (take limit) vec)))

  (count-resources [this tenant-id resource-type params search-registry]
    ;; Reuse search with a high limit to count all matching resources
    (let [count-params (assoc params :_count "2147483647" :_skip "0")]
      (count (protocol/search this tenant-id resource-type count-params search-registry))))

  (history [_ tenant-id resource-type id]
    (let [s @state
          record (get-in s [tenant-id resource-type id])]
      (if record
        (vals (:history record))
        [])))

  (history-type [_ tenant-id resource-type _params]
    (let [s @state
          records (vals (get-in s [tenant-id resource-type]))]
      (mapcat (fn [record] (vals (:history record))) records)))

  (transact-transaction [this tenant-id entries]
    (protocol/transact-transaction this tenant-id entries nil))

  (transact-transaction [this tenant-id entries opts]
    ;; Atomic transaction: snapshot state for rollback on failure.
    ;; Entries are reordered per FHIR §3.1.0.11.2: DELETE -> POST -> PUT/PATCH -> GET/HEAD
    (ftrace/trace!
     {:id :store/transact-transaction
      :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
     (let [ordered (sort-by #(method-order (get-in % [:request :method])) entries)
           ;; Threaded into EVERY inner verb below. The fan-out is where a
           ;; bundle's attribution evaporates if it is threaded nowhere, and
           ;; the loss is invisible from outside.
           tx-opts (select-keys opts [protocol/tx-meta-key])
           snapshot @state]
       (try
         (let [results (mapv (fn [entry]
                               (let [req (:request entry)
                                     method (:method req)
                                     url (:url req)
                                     resource (:resource entry)
                                     [type id] (str/split url #"/")
                                     ;; State is keyed by whatever the caller
                                     ;; passed, and every other verb is called
                                     ;; with the keyword form. Splitting the
                                     ;; url yields a string, so without this
                                     ;; the whole Bundle wrote into a parallel
                                     ;; bucket no read would ever look in.
                                     rt (keyword type)
                                     ;; update/delete-resource normalize the
                                     ;; ETag spellings themselves; this only
                                     ;; decides whether the entry carries a
                                     ;; precondition at all. Dropping it would
                                     ;; turn a guarded write into an
                                     ;; unconditional one.
                                     entry-if-match (or (:ifMatch req) (get req "ifMatch"))]
                                 (case method
                                   "POST" (let [res (protocol/create-resource this tenant-id rt nil resource tx-opts)
                                                vid (get-in res [:meta :versionId])
                                                last-mod (str (get-in res [:meta :lastUpdated]))]
                                            {:resource res
                                             :response {:status "201 Created"
                                                        :location (str type "/" (:id res) "/_history/" vid)
                                                        :etag (str "W/\"" vid "\"")
                                                        :lastModified last-mod}})
                                   "PUT" (let [res (protocol/update-resource
                                                    this tenant-id rt id resource
                                                    (cond-> tx-opts
                                                      entry-if-match (assoc :if-match entry-if-match)))
                                               vid (get-in res [:meta :versionId])
                                               last-mod (str (get-in res [:meta :lastUpdated]))]
                                           {:resource res
                                            :response {:status "200 OK"
                                                       :etag (str "W/\"" vid "\"")
                                                       :lastModified last-mod}})
                                   "DELETE" (do (protocol/delete-resource
                                                 this tenant-id rt id
                                                 (cond-> tx-opts
                                                   entry-if-match (assoc :if-match entry-if-match)))
                                                {:response {:status "204 No Content"}})
                                   "GET" (let [res (protocol/read-resource this tenant-id rt id)]
                                           (if res
                                             (let [vid (get-in res [:meta :versionId])
                                                   last-mod (str (get-in res [:meta :lastUpdated]))]
                                               {:resource res
                                                :response {:status "200 OK"
                                                           :etag (when vid (str "W/\"" vid "\""))
                                                           :lastModified last-mod}})
                                             {:response {:status "404 Not Found"}}))
                                   (throw (ex-info (str "Bundle entry method not supported: " method)
                                                   {:fhir/status 405
                                                    :fhir/code "not-supported"
                                                    :method method
                                                    :url url})))))
                             ordered)]
           {:resourceType "Bundle"
            :type "transaction-response"
            :entry results})
         (catch Exception e
           (reset! state snapshot)
           (throw e))))))

  (transact-bundle [this tenant-id entries]
    (protocol/transact-bundle this tenant-id entries nil))

  (transact-bundle [this tenant-id entries opts]
    ;; Batch semantics: each entry is processed independently; per-entry
    ;; failures do NOT roll back other entries. Returns a batch-response
    ;; Bundle reporting per-entry status in input order.
    (ftrace/trace!
     {:id :store/transact-bundle
      :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
     (let [;; Each entry here IS its own transaction, so the same map is
           ;; stamped on every one of them -- N entries, N stamped writes.
           tx-opts (select-keys opts [protocol/tx-meta-key])
           results
           (mapv
            (fn [entry]
              (try
                (let [req (:request entry)
                      method (some-> (:method req) str/upper-case)
                      url (:url req)
                      [type id] (when url (str/split url #"/"))
                      ;; Keyword, for the same reason as transact-transaction:
                      ;; state is keyed by what the caller passes, and every
                      ;; other verb passes the keyword form.
                      rt (when type (keyword type))
                      resource (:resource entry)
                      ;; update/delete-resource normalize the ETag spellings
                      ;; themselves; this only decides whether the entry
                      ;; carries a precondition at all.
                      entry-if-match (or (:ifMatch req) (get req "ifMatch"))]
                  (case method
                    "POST"
                    (let [res (protocol/create-resource this tenant-id rt nil resource tx-opts)
                          vid (get-in res [:meta :versionId])
                          last-mod (str (get-in res [:meta :lastUpdated]))]
                      {:resource res
                       :response (cond-> {:status "201 Created"}
                                   vid (assoc :etag (str "W/\"" vid "\"")
                                              :location (str type "/" (:id res) "/_history/" vid))
                                   last-mod (assoc :lastModified last-mod))})

                    "PUT"
                    (let [res (protocol/update-resource
                               this tenant-id rt id resource
                               (cond-> tx-opts
                                 entry-if-match (assoc :if-match entry-if-match)))
                          vid (get-in res [:meta :versionId])
                          last-mod (str (get-in res [:meta :lastUpdated]))]
                      {:resource res
                       :response (cond-> {:status "200 OK"}
                                   vid (assoc :etag (str "W/\"" vid "\""))
                                   last-mod (assoc :lastModified last-mod))})

                    "DELETE"
                    (do (protocol/delete-resource
                         this tenant-id rt id
                         (cond-> tx-opts
                           entry-if-match (assoc :if-match entry-if-match)))
                        {:response {:status "204 No Content"}})

                    "GET"
                    (let [res (protocol/read-resource this tenant-id rt id)]
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
                                                       :diagnostics (str type "/" id " not found")}]}}}))

                    {:response {:status "400 Bad Request"
                                :outcome {:resourceType "OperationOutcome"
                                          :issue [{:severity "error"
                                                   :code "invalid"
                                                   :diagnostics (str "Unsupported method: " method)}]}}}))
                (catch Exception e
                  {:response {:status "400 Bad Request"
                              :outcome {:resourceType "OperationOutcome"
                                        :issue [{:severity "error"
                                                 :code "exception"
                                                 :diagnostics (str "Entry failed: " (ex-message e))}]}}})))
            entries)]
       {:resourceType "Bundle"
        :type "batch-response"
        :entry results})))

  (create-tenant [this tenant-id]
    (protocol/create-tenant this tenant-id nil))

  (create-tenant [_ tenant-id opts]
    (let [tid       (str tenant-id)
          if-exists (get opts :if-exists :error)]
      (swap! state
             (fn [s]
               (let [exists? (contains? s tid)]
                 (cond
                   (and exists? (= :error if-exists))
                   (throw (ex-info "Tenant already exists"
                                   {:fhir/status 409 :fhir/code "conflict"
                                    :tenant-id tid}))

                   (and exists? (= :replace if-exists))
                   (assoc s tid {})

                   exists?
                   s

                   :else
                   (assoc s tid {})))))
      nil))

  (delete-tenant [this tenant-id]
    (protocol/delete-tenant this tenant-id nil))

  (delete-tenant [_ tenant-id opts]
    (let [tid       (str tenant-id)
          if-absent (get opts :if-absent :error)]
      (swap! state
             (fn [s]
               (cond
                 (and (not (contains? s tid)) (= :error if-absent))
                 (throw (ex-info "Tenant not found"
                                 {:fhir/status 404 :fhir/code "not-found"
                                  :tenant-id tid}))
                 :else (dissoc s tid))))
      nil))

  (warmup-tenant [this tenant-id]
    (protocol/warmup-tenant this tenant-id nil))

  (warmup-tenant [_ tenant-id _opts]
    ;; Mock has no cold state worth warming. Ensure the tenant key
    ;; exists so subsequent searches do not 404, then return.
    (swap! state update (str tenant-id) (fnil identity {}))
    nil)

  (current-basis [this _tenant-id]
    ;; No-history limitation: the mock keeps only current state, so it cannot
    ;; reconstruct a past snapshot. The basis is a strictly monotonic token
    ;; (successive kickoffs get distinct :tx-id) but scan-type-as-of /
    ;; count-as-of below ignore it and read CURRENT state.
    {:tx-id (or (some-> (:basis-counter this) (swap! inc)) (System/nanoTime))
     :system-time (java.time.Instant/now)})

  (scan-type-as-of [_ tenant-id resource-type _basis]
    ;; No history: returns a lazy seq over the CURRENT live resources of
    ;; `resource-type` (the basis is ignored). @state is snapshotted eagerly so
    ;; the seq is stable against later writes; the transformation is lazy and
    ;; realized incrementally by the consumer.
    (->> (get-in @state [tenant-id resource-type])
         vals
         (remove :deleted?)
         (map :resource)))

  (count-as-of [_ tenant-id resource-type _basis]
    ;; No history: count of CURRENT live resources of `resource-type`.
    (->> (get-in @state [tenant-id resource-type])
         vals
         (remove :deleted?)
         count))

  protocol/ITxMetadataStore
  (tx-metadata-supported? [_ tenant-id]
    ;; True by default, because the mock really does record what it is handed.
    ;; `:tx-metadata-supported?` in options -- a boolean, or a predicate of
    ;; tenant-id -- lets a test stand in for a store that cannot, which is the
    ;; only way to exercise the refusal path.
    (let [v (get options :tx-metadata-supported? true)]
      (boolean (if (fn? v) (v tenant-id) v)))))

(defn- mock-valueset-expand [store tenant-id _params id]
  ;; Mock an expansion logic
  (let [vs (if id
             (protocol/read-resource store tenant-id :ValueSet id)
             {:resourceType "ValueSet"
              :id "mock-valueset"
              :status "active"})]
    (if vs
      (assoc vs
             :expansion {:total 1
                         :timestamp (str (java.time.Instant/now))
                         :contains [{:system "http://example.com"
                                     :code "mock"
                                     :display "Mock Expanded Code"}]})
      {:resourceType "OperationOutcome"
       :issue [{:severity "error"
                :code "not-found"
                :diagnostics (str "ValueSet " id " not found")}]})))

(defn- mock-valueset-lookup [_store _tenant-id _params]
  ;; Mock a lookup logic
  {:resourceType "Parameters"
   :parameter [{:name "name"
                :valueString "Mock Lookup Result"}
               {:name "display"
                :valueString "Mocked"}]})

(defn create-mock-store [options]
  (let [store (->MockStore (atom {}) options)]
    (assoc store
           :basis-counter (atom 0)
           :operations {:valueset-expand mock-valueset-expand
                        :valueset-lookup mock-valueset-lookup})))

(defn halt-mock-store [store]
  (reset! (:state store) {})
  store)
