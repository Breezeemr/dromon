(ns fhir-store-xtdb2.query-xtql
  "Optional XTQL pathway for fhir-store-xtdb2. Under :query-mode :xtql the
   XTDBStore dispatches reads/searches here and writes through put-docs/
   delete-docs. Reads use runtime-constructed XTQL forms (from/where), which
   lets us parameterize the resource-type table name and the predicate values
   dynamically — xt/template would require compile-time literals.

   Writes are all document-shaped: [:put-docs :RT doc] and [:delete-docs :RT id].
   Optimistic-concurrency guards remain [:sql \"ASSERT ...\" ...] ops, which
   execute-tx accepts in the same atomic transaction."
  (:require [xtdb.api :as xt]
            [clojure.string :as str]
            [taoensso.telemere :as t]
            [fhir-store-xtdb2.core :as core]))

;; ---------------------------------------------------------------------------
;; Simple reads
;; ---------------------------------------------------------------------------

(defn- rt-kw [resource-type]
  (keyword (name resource-type)))

(defn- from-star [rt-k]
  ;; (from :RT [*])  — returns all projected columns, matching SELECT *
  (list 'from rt-k '[*]))

(defn- from-star-opts [rt-k opts]
  ;; (from :RT {:for-system-time ... :bind [*]})
  (list 'from rt-k (assoc opts :bind '[*])))

(defn read-xtql [node resource-type id read-decoders]
  (t/trace!
   {:id :xtql/read
    :data {:resource-type (name resource-type) :id id}}
   (let [q (list '-> (from-star (rt-kw resource-type))
                 (list 'where (list '= 'xt/id id)))
         rows (xt/q node q)]
     (core/xtdb->fhir (first rows) read-decoders))))

(defn- parse-vid-instant [vid]
  (cond
    (instance? java.time.Instant vid) vid
    (string? vid) (try (java.time.Instant/parse vid)
                       (catch Exception _ nil))
    :else nil))

(defn vread-xtql [node resource-type id vid read-decoders]
  (t/trace!
   {:id :xtql/vread
    :data {:resource-type (name resource-type) :id id :vid (str vid)}}
   (let [inst (parse-vid-instant vid)
         opts (if inst
                {:for-system-time (list 'at inst)}
                ;; fall back to all-time + manual filter if we can't parse
                {:for-system-time :all-time})
         q (list '-> (from-star-opts (rt-kw resource-type) opts)
                 (list 'where (list '= 'xt/id id)))
         rows (xt/q node q)]
     (core/xtdb->fhir (first rows) read-decoders))))

(defn deleted?-xtql [node resource-type id]
  (t/trace!
   {:id :xtql/deleted?
    :data {:resource-type (name resource-type) :id id}}
   (let [rt-k (rt-kw resource-type)
         current-q (list '-> (list 'from rt-k '[xt/id])
                         (list 'where (list '= 'xt/id id)))
         current (xt/q node current-q)]
     (if (seq current)
       false
       (let [history-q (list '-> (list 'from rt-k
                                       {:for-system-time :all-time
                                        :bind '[xt/id]})
                             (list 'where (list '= 'xt/id id)))
             history (xt/q node history-q)]
         (boolean (seq history)))))))

(defn history-xtql [node resource-type id read-decoders]
  (t/trace!
   {:id :xtql/history
    :data {:resource-type (name resource-type) :id id}}
   (let [q (list '-> (from-star-opts (rt-kw resource-type)
                                     {:for-system-time :all-time})
                 (list 'where (list '= 'xt/id id)))
         rows (xt/q node q)]
     (mapv #(core/xtdb->fhir % read-decoders) rows))))

(defn- parse-timestamp [s]
  (cond
    (instance? java.time.Instant s) s
    (string? s) (try (java.time.Instant/parse s)
                     (catch Exception _ nil))
    :else nil))

;; ---------------------------------------------------------------------------
;; Search predicate builder
;;
;; First-class (translated directly to an XTQL `where` form):
;;   - boolean flat column   :gender "male"    {:active true}
;;   - string flat column    :gender "male"
;;   - _id equality          {:_id "abc"}
;;   - comma-separated OR    "male,female"
;;
;; Fallback to SQL (returns :fallback/complex):
;;   - any search-param with :columns nested metadata (CodeableConcept, HumanName,
;;     Period, Reference-across-types, ...) — the SQL builder already knows how
;;     to express these via UNNEST/EXISTS; translating them is deferred.
;;   - order-by with sort-specs we cannot map 1:1
;; ---------------------------------------------------------------------------

(def ^:private fallback ::fallback)

(defn- coerce-value
  "Best-effort string->typed coercion for bare flat columns. Mirrors the SQL
   path's behavior of letting XTDB handle type promotion for well-known
   literals while keeping unknowns as strings."
  [s]
  (cond
    (not (string? s)) s
    (= s "true") true
    (= s "false") false
    :else (or (parse-long s) (parse-double s) s)))

(defn- flat-col-sym [pname]
  ;; XTDB v2 exposes underscore SQL column names as kebab-cased symbols in XTQL
  ;; result rows, but inside from/bind we use the storage symbol as-is.
  (symbol pname))

(defn- build-comma-or
  "Decomposes a comma-separated value and composes an XTQL (or ...) form
   across the sub-conditions. Returns :fallback/complex if any sub-condition
   can't be translated."
  [pname values search-param build-single]
  (let [subs (mapv #(build-single pname % search-param) values)]
    (if (some #{fallback} subs)
      fallback
      {:where (cons 'or (map :where subs))})))

(declare build-xtql-condition)

(defn- build-single-xtql
  [pname v search-param]
  (cond
    ;; Boolean flat column — SQL path recognizes boolean? param-value; mirror it.
    (boolean? v)
    {:where (list '= (flat-col-sym pname) v)}

    ;; _id always direct equality against xt/id
    (= (name pname) "_id")
    {:where (list '= 'xt/id (if (string? v) v (str v)))}

    ;; Any registry entry with :columns metadata indicates nested/complex shape.
    ;; We defer these to the SQL fallback.
    (and search-param (or (:columns search-param) (:target search-param)))
    fallback

    ;; Otherwise: flat column equality with best-effort coercion.
    :else
    (let [coerced (coerce-value v)]
      {:where (list '= (flat-col-sym (name pname)) coerced)})))

(defn build-xtql-condition
  "Returns {:where <xtql-form>} for a single search param, or :fallback/complex
   if the predicate isn't first-class under the XTQL pathway yet."
  [param-name param-value search-param]
  (let [param-str (cond
                    (keyword? param-value) (name param-value)
                    (boolean? param-value) (str param-value)
                    :else (str param-value))
        comma (when (string? param-value) (str/split param-str #","))]
    (if (and comma (> (count comma) 1))
      (build-comma-or param-name comma search-param build-single-xtql)
      (build-single-xtql param-name param-value search-param))))

(defn- xtql-order-by [sort-specs]
  (when (seq sort-specs)
    (mapv (fn [{:keys [field dir]}]
            {:val (flat-col-sym field) :dir dir})
          sort-specs)))

(defn- compose-search-query
  "Assembles a runtime XTQL pipeline form for a search: (-> (from :RT [*]) (where ...) ...)"
  [resource-type wheres sort-specs limit offset]
  (let [base (from-star (rt-kw resource-type))
        pipeline (cond-> [base]
                   (seq wheres) (conj (cons 'where wheres))
                   (seq sort-specs)
                   (conj (cons 'order-by (xtql-order-by sort-specs)))
                   (and limit (pos? limit)) (conj (list 'limit limit))
                   (and offset (pos? offset)) (conj (list 'offset offset)))]
    (apply list '-> pipeline)))

(defn search-xtql
  [node resource-type {:keys [filter-params sort-specs search-registry limit offset]}
   read-decoders sql-fallback]
  (t/trace!
   {:id :xtql/search
    :data {:resource-type (name resource-type)}}
   (let [order-by-fallback? (and (seq sort-specs)
                                 ;; sort on fields we don't model as flat columns
                                 ;; — defer to SQL, which uses sort-field->sql-col.
                                 (some #(contains? (or search-registry {}) (:field %)) sort-specs))
         conditions (when (seq filter-params)
                      (mapv (fn [[k v]]
                              (build-xtql-condition k v (get search-registry (name k))))
                            filter-params))]
     (cond
       order-by-fallback?
       (do (t/event! :xtql/fallback
                     {:level :info
                      :data {:resource-type (name resource-type)
                             :reason :order-by-unsupported}})
           (sql-fallback))

       (some #{fallback} conditions)
       (do (t/event! :xtql/fallback
                     {:level :info
                      :data {:resource-type (name resource-type)
                             :params (mapv first filter-params)
                             :reason :complex-predicate}})
           (sql-fallback))

       :else
       (let [wheres (mapv :where conditions)
             q (compose-search-query resource-type wheres sort-specs limit offset)
             rows (xt/q node q)]
         (mapv #(core/xtdb->fhir % read-decoders) rows))))))

(defn count-resources-xtql
  [node resource-type {:keys [filter-params search-registry]} sql-fallback]
  (t/trace!
   {:id :xtql/count
    :data {:resource-type (name resource-type)}}
   (let [conditions (when (seq filter-params)
                      (mapv (fn [[k v]]
                              (build-xtql-condition k v (get search-registry (name k))))
                            filter-params))]
     (if (some #{fallback} conditions)
       (do (t/event! :xtql/fallback
                     {:level :info
                      :data {:resource-type (name resource-type)
                             :params (mapv first filter-params)
                             :reason :complex-predicate-count}})
           (sql-fallback))
       ;; Bind `*` so `where` clauses referencing arbitrary flat columns
       ;; resolve. Count the result rows — XTDB 2.1.0 does not expose a
       ;; dedicated aggregate-count XTQL primitive callable at this level,
       ;; but the engine pushes the bind projection down regardless.
       (let [wheres (mapv :where conditions)
             base (list 'from (rt-kw resource-type) '[*])
             pipeline (cond-> [base]
                        (seq wheres) (conj (cons 'where wheres)))
             q (apply list '-> pipeline)
             rows (xt/q node q)]
         (count rows))))))

;; ---------------------------------------------------------------------------
;; Writes — put-docs / delete-docs pathway.
;;
;; Mutations use [:put-docs table doc] and [:delete-docs table id], which
;; bypass the SQL INSERT/DELETE planner. Optimistic-concurrency guards keep
;; the [:sql "ASSERT ..."] shape — execute-tx accepts mixed ops in one atomic
;; transaction, and this avoids translating the ASSERT into an XTQL predicate.
;; ---------------------------------------------------------------------------

(defn create-xtql [node resource-type id resource storage-encoders]
  (t/trace!
   {:id :xtql/create
    :data {:resource-type (name resource-type) :id id}}
   (let [version "1"
         rt-name (name resource-type)
         doc (core/encode-resource-doc resource-type id resource storage-encoders
                                       :version version)
         put-doc (core/doc->put-doc doc)
         assert-op [:sql (format "ASSERT NOT EXISTS (SELECT 1 FROM %s WHERE _id = ?)" rt-name)
                    [id]]
         put-op [:put-docs (rt-kw resource-type) put-doc]]
     (try
       (xt/execute-tx node [assert-op put-op])
       (catch Exception e
         (throw (ex-info (str "Resource already exists: " rt-name "/" id)
                         {:fhir/status 409 :fhir/code "conflict"
                          :resource-type rt-name :id id}
                         e))))
     (-> resource
         (assoc :id id)
         (assoc-in [:meta :versionId] version)))))

(defn update-xtql [node resource-type id resource opts storage-encoders]
  (t/trace!
   {:id :xtql/update
    :data {:resource-type (name resource-type) :id id}}
   (let [rt-name (name resource-type)
         if-match (:if-match opts)
         current (core/current-version node resource-type id)
         _ (when (and if-match (nil? current))
             (throw (ex-info "Version conflict: resource does not exist"
                             {:fhir/status 412 :fhir/code "conflict"
                              :expected if-match :actual nil})))
         _ (when (and if-match current (not= if-match current))
             (throw (ex-info "Version conflict"
                             {:fhir/status 412 :fhir/code "conflict"
                              :expected if-match :actual current})))
         expected-vid (or if-match current)
         new-version (core/next-version expected-vid)
         doc (core/encode-resource-doc resource-type id resource storage-encoders
                                       :version new-version)
         put-doc (core/doc->put-doc doc)
         assert-op (if expected-vid
                     [:sql (format "ASSERT EXISTS (SELECT 1 FROM %s WHERE _id = ? AND fhir_version = ?)"
                                   rt-name)
                      [id expected-vid]]
                     [:sql (format "ASSERT NOT EXISTS (SELECT 1 FROM %s WHERE _id = ?)"
                                   rt-name)
                      [id]])
         put-op [:put-docs (rt-kw resource-type) put-doc]]
     (try
       (xt/execute-tx node [assert-op put-op])
       (catch Exception e
         (if if-match
           (throw (ex-info (str "Version conflict: " (ex-message e))
                           {:fhir/status 412 :fhir/code "conflict"
                            :expected if-match}
                           e))
           (throw (ex-info (str "Conflict: " (ex-message e))
                           {:fhir/status 409 :fhir/code "conflict"}
                           e)))))
     (-> resource
         (assoc :id id)
         (assoc-in [:meta :versionId] new-version)))))

(defn delete-xtql [node resource-type id opts]
  (t/trace!
   {:id :xtql/delete
    :data {:resource-type (name resource-type) :id id}}
   (let [rt-name (name resource-type)
         if-match (:if-match opts)
         current (when if-match (core/current-version node resource-type id))
         _ (when (and if-match (nil? current))
             (throw (ex-info "Version conflict: resource does not exist"
                             {:fhir/status 412 :fhir/code "conflict"
                              :expected if-match :actual nil})))
         _ (when (and if-match (not= if-match current))
             (throw (ex-info "Version conflict"
                             {:fhir/status 412 :fhir/code "conflict"
                              :expected if-match :actual current})))
         assert-op (when if-match
                     [:sql (format "ASSERT EXISTS (SELECT 1 FROM %s WHERE _id = ? AND fhir_version = ?)"
                                   rt-name)
                      [id if-match]])
         delete-op [:delete-docs (rt-kw resource-type) id]
         tx-ops (if assert-op [assert-op delete-op] [delete-op])]
     (try
       (xt/execute-tx node tx-ops)
       (catch Exception e
         (if if-match
           (throw (ex-info (str "Version conflict: " (ex-message e))
                           {:fhir/status 412 :fhir/code "conflict"
                            :expected if-match}
                           e))
           (throw (ex-info (str "Conflict: " (ex-message e))
                           {:fhir/status 409 :fhir/code "conflict"}
                           e)))))
     nil)))

(defn history-type-xtql [node resource-type params read-decoders]
  (t/trace!
   {:id :xtql/history-type
    :data {:resource-type (name resource-type)}}
   (let [raw-count (or (get params :_count) (get params "_count") "50")
         limit (if (string? raw-count) (parse-long raw-count) raw-count)
         since (parse-timestamp (or (get params :_since) (get params "_since")))
         at    (parse-timestamp (or (get params :_at)    (get params "_at")))
         ;; XTQL `where`/`order-by` can only reference columns bound in the
         ;; `from` — binding xt/system-from (plus *) makes it available.
         base (list 'from (rt-kw resource-type)
                    {:for-system-time :all-time
                     :bind '[xt/system-from *]})
         pipeline (cond-> [base]
                    since (conj (list 'where (list '> 'xt/system-from since)))
                    at    (conj (list 'where (list '<= 'xt/system-from at)))
                    true  (conj (list 'order-by {:val 'xt/system-from :dir :desc}))
                    true  (conj (list 'limit limit)))
         q (apply list '-> pipeline)
         rows (xt/q node q)]
     (mapv #(core/xtdb->fhir % read-decoders) rows))))
