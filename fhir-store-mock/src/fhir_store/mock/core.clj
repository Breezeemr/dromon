(ns fhir-store.mock.core
  (:require [clojure.string :as str]
            [fhir-store.protocol :as protocol]
            [taoensso.telemere :as t]))

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
      (= (:id res) v-str)
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

(defrecord MockStore [state options]
  protocol/IFHIRStore
  (create-resource [_ tenant-id resource-type id resource]
    (let [id (or id (new-id))
          vid "1"
          meta-info {:versionId vid
                     :lastUpdated (java.time.Instant/now)}
          resource-with-meta (-> resource
                                 (update :meta merge meta-info)
                                 (assoc :id id))
          record {:history {vid resource-with-meta}
                  :current vid
                  :resource resource-with-meta
                  :deleted? false}]
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

  (update-resource [_ tenant-id resource-type id resource]
    (let [s @state
          existing (get-in s [tenant-id resource-type id])
          vid (if existing
                (str (inc (Long/parseLong (:current existing))))
                "1")
          meta-info {:versionId vid
                     :lastUpdated (java.time.Instant/now)}
          resource-with-meta (-> resource
                                 (update :meta merge meta-info)
                                 (assoc :id id))
          record {:history (assoc (or (:history existing) {}) vid resource-with-meta)
                  :current vid
                  :resource resource-with-meta
                  :deleted? false}]
      (swap! state assoc-in [tenant-id resource-type id] record)
      resource-with-meta))

  (delete-resource [_ tenant-id resource-type id]
    (let [s @state
          existing (get-in s [tenant-id resource-type id])]
      (if (and existing (not (:deleted? existing)))
        (let [vid (str (inc (Long/parseLong (:current existing))))
              record (assoc existing
                            :current vid
                            :deleted? true
                            :resource nil)]
          (swap! state assoc-in [tenant-id resource-type id] record)
          true)
        false)))

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

  (transact-bundle [this tenant-id entries]
    ;; Atomic transaction: snapshot state for rollback on failure.
    ;; Entries are reordered per FHIR §3.1.0.11.2: DELETE → POST → PUT/PATCH → GET/HEAD
    (t/trace!
     {:id :store/transact-bundle
      :data {:tenant-id (str tenant-id) :entry-count (count entries)}}
     (let [ordered (sort-by #(method-order (get-in % [:request :method])) entries)
          snapshot @state]
      (try
        (let [results (mapv (fn [entry]
                              (let [req (:request entry)
                                    method (:method req)
                                    url (:url req)
                                    resource (:resource entry)
                                    [type id] (str/split url #"/")]
                                (case method
                                  "POST" (let [res (protocol/create-resource this tenant-id type nil resource)
                                               vid (get-in res [:meta :versionId])
                                               last-mod (str (get-in res [:meta :lastUpdated]))]
                                           {:resource res
                                            :response {:status "201 Created"
                                                       :location (str type "/" (:id res) "/_history/" vid)
                                                       :etag (str "W/\"" vid "\"")
                                                       :lastModified last-mod}})
                                  "PUT" (let [res (protocol/update-resource this tenant-id type id resource)
                                              vid (get-in res [:meta :versionId])
                                              last-mod (str (get-in res [:meta :lastUpdated]))]
                                          {:resource res
                                           :response {:status "200 OK"
                                                      :etag (str "W/\"" vid "\"")
                                                      :lastModified last-mod}})
                                  "DELETE" (do (protocol/delete-resource this tenant-id type id)
                                               {:response {:status "204 No Content"}})
                                  "GET" (let [res (protocol/read-resource this tenant-id type id)]
                                          (if res
                                            (let [vid (get-in res [:meta :versionId])
                                                  last-mod (str (get-in res [:meta :lastUpdated]))]
                                              {:resource res
                                               :response {:status "200 OK"
                                                          :etag (when vid (str "W/\"" vid "\""))
                                                          :lastModified last-mod}})
                                            {:response {:status "404 Not Found"}})))))
                            ordered)]
          {:resourceType "Bundle"
           :type "transaction-response"
           :entry results})
        (catch Exception e
          (reset! state snapshot)
          (throw e)))))))

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
    (assoc store :operations {:valueset-expand mock-valueset-expand
                              :valueset-lookup mock-valueset-lookup})))

(defn halt-mock-store [store]
  (reset! (:state store) {})
  store)
