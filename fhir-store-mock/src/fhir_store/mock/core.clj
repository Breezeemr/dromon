(ns fhir-store.mock.core
  (:require [clojure.string :as str]
            [fhir-store.protocol :as protocol]
            [fhir-store.lifecycle :as lc]
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

;; ---------------------------------------------------------------------------
;; Keys, clock, ids and bases
;;
;; State is {tenant-string {type-keyword {id record}}}. Callers pass the type
;; as a keyword or a string and the tenant as whatever they hold, so every
;; verb normalises both; otherwise "Patient" and :Patient were two buckets and
;; a write through one was invisible to a read through the other.
;; ---------------------------------------------------------------------------

(defn- tenant-key [tenant-id]
  (if (keyword? tenant-id) (name tenant-id) (str tenant-id)))

(defn- type-key [resource-type] (keyword (name resource-type)))

(defn- clock-now [store]
  ((or (:clock store) #(java.time.Instant/now))))

(defn- mint-id [store]
  ((or (:id-fn store) new-id)))

(def ^:private ^:dynamic *transaction-basis*
  "Bound while transact-transaction runs, so every entry of one Bundle
   commits under the same basis, as one transaction does in a real store."
  nil)

(defn- next-basis
  [store]
  (or *transaction-basis*
      {:tx-id (if-let [counter (:basis-counter store)]
                (swap! counter inc)
                (System/nanoTime))
       :system-time (clock-now store)}))

(defn- with-basis [ret basis]
  (vary-meta ret assoc :fhir-store/basis basis))

;; ---------------------------------------------------------------------------
;; Search evaluation
;;
;; Parameters are evaluated through the search registry's descriptors
;; ({:type :target :columns [{:col :sub-col :array? :fixed ...}]}), the same
;; ones the other stores compile to queries. A parameter the registry does not
;; describe falls back to the resource field of the same name.
;;
;; A comma inside one value is an OR (FHIR R4B 3.1.1.5; `\,` is a literal
;; comma); a vector of values is the parameter repeated, an AND.
;; ---------------------------------------------------------------------------

(def ^:private result-params
  #{"_count" "_skip" "_offset" "_sort" "_include" "_revinclude"
    "_total" "_elements" "_contained" "_containedType"
    "_summary" "_format" "_pretty" "_type"})

(defn- split-or
  [s]
  (mapv #(str/replace % "\\," ",") (str/split s #"(?<!\\),")))

(defn- and-groups
  "A parameter value as AND groups, each a vector of OR alternatives."
  [v]
  (mapv (fn [x] (split-or (if (keyword? x) (name x) (str x))))
        (if (sequential? v) v [v])))

(defn- as-seq [x]
  (cond (nil? x) []
        (sequential? x) x
        :else [x]))

(defn- extension-value
  "The value[x] of an extension map."
  [ext]
  (some (fn [[k v]] (when (str/starts-with? (name k) "value") v)) ext))

(defn- column-values
  "Every element `res` holds at the column, arrays flattened."
  [res {:keys [col sub-col extension-url extension-promoted?]}]
  (if (and extension-url (not extension-promoted?))
    (->> (as-seq (:extension res))
         (filter #(= extension-url (:url %)))
         (keep extension-value))
    (let [tops (as-seq (get res (keyword col)))]
      (if sub-col
        (mapcat (fn [x] (when (map? x) (as-seq (get x (keyword sub-col))))) tops)
        tops))))

;; Token: `code`, `system|code`, `|code` (no system), `system|`.

(defn- parse-token [s]
  (if-let [i (str/index-of s "|")]
    {:system (subs s 0 i) :code (subs s (inc i)) :system? true}
    {:code s}))

(defn- system-code-matches?
  [{:keys [system code system?]} sys c]
  (cond
    (not system?)   (= code c)
    (= "" system)   (and (nil? sys) (= code c))
    (= "" code)     (= system sys)
    :else           (and (= system sys) (= code c))))

(defn- token-matches?
  "`element` is a primitive code, a Coding, a CodeableConcept, an Identifier,
   a ContactPoint or a CodeableReference."
  [element token]
  (cond
    (nil? element) false
    (keyword? element) (= (:code token) (name element))
    ;; A primitive code's system is implied by its element, so only the code
    ;; part of the token is compared.
    (not (map? element)) (and (not= "" (:code token)) (= (:code token) (str element)))
    (contains? element :coding) (boolean (some #(token-matches? % token) (:coding element)))
    (contains? element :concept) (token-matches? (:concept element) token)
    (contains? element :code) (system-code-matches? token (:system element) (:code element))
    (contains? element :value) (system-code-matches? token (:system element) (str (:value element)))
    :else false))

;; Reference: `Type/id`, a bare id, or an absolute URL ending in `/Type/id`.

(defn- reference-key
  "[type id] of a reference string, type nil for a bare id."
  [s]
  (let [s (str/replace s #"/_history/[^/]*$" "")
        parts (str/split s #"/")]
    (if (and (>= (count parts) 2) (not (str/starts-with? s "urn:")))
      [(nth parts (- (count parts) 2)) (peek parts)]
      [nil s])))

(defn- reference-string [element]
  (cond
    (string? element) element
    (map? element) (let [r (:reference element)]
                     (if (map? r) (:reference r) r))
    :else nil))

(defn- type-modifier [modifier]
  (when (and modifier (re-matches #"[A-Z][A-Za-z]*" modifier)) modifier))

(defn- reference-matches?
  [element value target modifier]
  (if-let [ref (reference-string element)]
    (or (= ref value)
        (let [[rtype rid] (reference-key ref)
              [vtype vid] (reference-key value)
              vtype (or vtype (type-modifier modifier))]
          (and (= rid vid)
               (if vtype
                 (or (nil? rtype) (= rtype vtype))
                 (or (nil? rtype) (empty? target) (contains? (set target) rtype))))))
    false))

;; String: case-insensitive starts-with; `:exact` and `:contains` modifiers.

(defn- string-parts [element]
  (cond
    (string? element) [element]
    (map? element) (->> [:family :given :text :prefix :suffix
                         :line :city :district :state :postalCode :country]
                        (mapcat #(as-seq (get element %)))
                        (filter string?))
    (nil? element) []
    :else [(str element)]))

(defn- string-matches? [element value modifier]
  (let [lc (str/lower-case value)]
    (boolean
     (some (fn [s]
             (case modifier
               "exact" (= s value)
               "contains" (str/includes? (str/lower-case s) lc)
               (str/starts-with? (str/lower-case s) lc)))
           (string-parts element)))))

;; Date and number: a comparator prefix, then a comparison at the precision
;; the value is written in. Dates compare as ISO strings truncated to the
;; search value's length, which holds while stored values share one zone
;; spelling (test fixtures do); a Period compares its start and end.

(defn- split-prefix [value]
  (let [[_ prefix v] (re-matches #"(eq|ne|gt|lt|ge|le|sa|eb|ap)?(.*)" value)]
    [(or prefix "eq") v]))

(defn- date-bounds [element]
  (cond
    (map? element) (when (or (:start element) (:end element))
                     [(some-> (:start element) str) (some-> (:end element) str)])
    (nil? element) nil
    :else (let [s (str element)] [s s])))

(defn- cmp-at [stored v]
  (compare (subs stored 0 (min (count stored) (count v))) v))

(defn- date-matches? [element value]
  (let [[prefix v] (split-prefix value)]
    (if-let [[lo hi] (date-bounds element)]
      (let [eq? (fn [s] (and s (or (str/starts-with? s v) (str/starts-with? v s))))]
        (case prefix
          ("eq" "ap") (boolean (or (eq? lo) (eq? hi)
                                   (and lo hi (neg? (cmp-at lo v)) (pos? (cmp-at hi v)))))
          "ne" (not (or (eq? lo) (eq? hi)))
          ("gt" "sa") (boolean (or (nil? hi) (pos? (cmp-at hi v))))
          "ge" (boolean (or (nil? hi) (not (neg? (cmp-at hi v)))))
          ("lt" "eb") (boolean (or (nil? lo) (neg? (cmp-at lo v))))
          "le" (boolean (or (nil? lo) (not (pos? (cmp-at lo v)))))))
      false)))

(defn- number-matches? [element value]
  (let [[prefix v] (split-prefix value)
        n (try (some-> (first (str/split v #"\|")) not-empty bigdec)
               (catch NumberFormatException _ nil))
        x (cond (number? element) element
                (map? element) (:value element)
                :else nil)]
    (if (and n (number? x))
      (let [c (compare (bigdec x) n)]
        (case prefix
          ("eq" "ap") (zero? c)
          "ne" (not (zero? c))
          ("gt" "sa") (pos? c)
          "ge" (not (neg? c))
          ("lt" "eb") (neg? c)
          "le" (not (pos? c))))
      false)))

(defn- fallback-matches?
  "A field the registry does not describe: exact equality on a primitive, and
   token or reference semantics on a map."
  [element value modifier]
  (cond
    (nil? element) false
    (string? element) (= element value)
    (keyword? element) (= (name element) value)
    (map? element) (or (reference-matches? element value nil modifier)
                       (token-matches? element (parse-token value))
                       (= (some-> (:value element) str) value))
    :else (= (str element) value)))

(defn- fixed-satisfied?
  "The registry's `:fixed` constraint (a `.where(system='phone')`)."
  [col-desc element]
  (let [fixed (:fixed col-desc)]
    (or (nil? fixed)
        (and (map? element)
             (every? (fn [[k v]] (= v (get element k))) fixed)))))

(defn- element-matches?
  [param-desc col-desc modifier element value]
  (and (fixed-satisfied? col-desc element)
       (case (:type param-desc)
         "token"     (token-matches? element (parse-token value))
         "reference" (reference-matches? element value (:target param-desc) modifier)
         "string"    (string-matches? element value modifier)
         "date"      (date-matches? element value)
         ("number" "quantity") (number-matches? element value)
         "uri"       (= (str element) value)
         (fallback-matches? element value modifier))))

(defn- present?
  "Whether `res` holds any value for the parameter (the `:missing` modifier)."
  [res param-desc base]
  (if param-desc
    (boolean (some (fn [col-desc]
                     (some #(fixed-satisfied? col-desc %) (column-values res col-desc)))
                   (concat (:columns param-desc) (:exists-not-false param-desc))))
    (some? (get res (keyword base)))))

(defn- alternative-matches?
  "One OR alternative of one parameter."
  [res param-desc base modifier value]
  (cond
    (= "_id" base) (= (:id res) value)

    (= "_lastUpdated" base) (date-matches? (get-in res [:meta :lastUpdated]) value)

    (#{"_tag" "_security"} base)
    (let [token (parse-token value)]
      (boolean (some #(token-matches? % token)
                     (as-seq (get-in res [:meta (keyword (subs base 1))])))))

    (= "_profile" base) (boolean (some #(= value %) (as-seq (get-in res [:meta :profile]))))

    (and param-desc (:exists-not-false param-desc))
    (= (= "true" value)
       (boolean (some (fn [col] (some #(not (false? %)) (column-values res col)))
                      (:exists-not-false param-desc))))

    param-desc
    (boolean
     (some (fn [col-desc]
             (some #(element-matches? param-desc col-desc modifier % value)
                   (column-values res col-desc)))
           (:columns param-desc)))

    :else
    (boolean (some #(fallback-matches? % value modifier)
                   (as-seq (get res (keyword base)))))))

(defn- match-param
  "Whether `res` satisfies parameter `k` = `v`: every AND group has a matching
   OR alternative."
  [res search-registry k v]
  (let [[base modifier] (str/split (name k) #":" 2)
        param-desc (get search-registry base)]
    (every? (fn [alternatives]
              (if (= "missing" modifier)
                (some #(= (= "true" %) (not (present? res param-desc base))) alternatives)
                (some #(alternative-matches? res param-desc base modifier %) alternatives)))
            (and-groups v))))

;; _sort: each field resolves through the registry's columns, the way the
;; other stores sort by a parameter's column, not by a resource field that
;; happens to share the parameter's name (Observation `date` is `effective[x]`).

(defn- sort-scalar [x]
  (cond
    (nil? x) nil
    (number? x) x
    (string? x) x
    (sequential? x) (some sort-scalar x)
    (map? x) (or (some-> (or (:start x) (:end x)) str)
                 (some-> (:coding x) first :code)
                 (:code x)
                 (:family x)
                 (:reference x)
                 (:value x)
                 (:text x))
    :else (str x)))

(defn- sort-key [res search-registry field]
  (case field
    "_id" (:id res)
    "_lastUpdated" (some-> (get-in res [:meta :lastUpdated]) str)
    (if-let [param-desc (get search-registry field)]
      (some (fn [col] (some sort-scalar (column-values res col)))
            (:columns param-desc))
      (sort-scalar (get res (keyword field))))))

(defn- compare-sort-keys [a b]
  (if (and (number? a) (number? b))
    (compare a b)
    (compare (str a) (str b))))

(defn- sort-specs [raw]
  (let [s (if (sequential? raw) (str/join "," raw) raw)]
    (when-not (str/blank? s)
      (mapv (fn [part]
              (let [part (str/trim part)]
                (if (str/starts-with? part "-")
                  {:field (subs part 1) :dir :desc}
                  {:field part :dir :asc})))
            (str/split s #",")))))

(defn- sort-results
  "Sorted by `specs`; a resource with no value for a field sorts after every
   resource that has one, in either direction."
  [resources search-registry specs]
  (let [keyed (map (fn [r] [(mapv #(sort-key r search-registry (:field %)) specs) r]) resources)
        cmp (fn [[ka _] [kb _]]
              (or (some (fn [[a b {:keys [dir]}]]
                          (let [c (cond (and (nil? a) (nil? b)) 0
                                        (nil? a) 1
                                        (nil? b) -1
                                        :else (cond-> (compare-sort-keys a b)
                                                (= dir :desc) -))]
                            (when-not (zero? c) c)))
                        (map vector ka kb specs))
                  0))]
    (mapv second (sort cmp keyed))))

(defn- param-int [params k default]
  (let [raw (or (get params (keyword k)) (get params k) default)]
    (if (string? raw) (parse-long raw) raw)))

;; ---------------------------------------------------------------------------
;; Write lifecycle (see fhir-store.lifecycle for the ordering contract).
;;
;; The mock has no transactions. `prepare` runs INSIDE the swap! fn, after the
;; precondition checks, so it may run more than once under contention; the
;; contract already requires it to be free of lasting side effects. `tx-ops`
;; is computed (so a lifecycle can still refuse a write from it) but never
;; applied: the ops are only handed back on the commit's write map. The
;; commit's :result is the state value the write produced.
;; ---------------------------------------------------------------------------

(def ^:private ^:dynamic *transaction-writes*
  "Bound to an atom while transact-transaction runs its entries through the
   single-write verbs. Each committed write is collected here instead of
   firing after-commit, and the Bundle fires once after every entry landed."
  nil)

(defn- lifecycle-write
  "Run prepare then tx-ops for one create or update; returns the write map
   with :resource the prepared body and :tx-ops the (unapplied) ops."
  [store tenant-id method resource-type id resource db opts]
  (let [lifecycle (:resource/lifecycle store)
        write     {:tenant-id     (tenant-key tenant-id)
                   :resource-type (name resource-type)
                   :id            id
                   :method        method
                   :resource      resource
                   :opts          opts
                   :db            db
                   :entity        nil
                   :store         store}
        prepared  (lc/prepare-write lifecycle write)
        ops-write (assoc write :resource prepared :submitted resource)]
    (assoc ops-write :tx-ops (lc/write-tx-ops lifecycle ops-write))))

(defn- committed!
  [store tenant-id write result]
  (if-let [pending *transaction-writes*]
    (swap! pending conj write)
    (lc/fire-after-commit! (:resource/lifecycle store)
                           {:tenant-id (tenant-key tenant-id)
                            :writes    [write]
                            :result    result
                            :store     store})))

(defn- entry-opts
  "A Bundle entry's opts: the Bundle's own, with the entry's If-Match in place
   of any the Bundle carried. nil when there is neither, so the plain arity is
   kept."
  [opts if-match]
  (not-empty (cond-> (dissoc opts :if-match)
               if-match (assoc :if-match if-match))))

(defn- create-with
  [store tenant-id rt resource opts]
  (if opts
    (protocol/create-resource store tenant-id rt nil resource opts)
    (protocol/create-resource store tenant-id rt nil resource)))

(defn- update-with
  [store tenant-id rt id resource opts]
  (if opts
    (protocol/update-resource store tenant-id rt id resource opts)
    (protocol/update-resource store tenant-id rt id resource)))

(defn- delete-with
  [store tenant-id rt id opts]
  (if opts
    (protocol/delete-resource store tenant-id rt id opts)
    (protocol/delete-resource store tenant-id rt id)))

(defn- next-version-id [record]
  (if-let [current (:current record)]
    (str (inc (Long/parseLong current)))
    "1"))

(defn- stamp-version
  "The stored form of a version: id and meta set, the write's basis attached."
  [resource id vid basis]
  (with-basis (-> resource
                  (update :meta merge {:versionId vid
                                       :lastUpdated (:system-time basis)})
                  (assoc :id id))
    basis))

(defn- put-version
  "`record` with `version` as its new current version."
  [record version basis]
  (let [vid (get-in version [:meta :versionId])]
    (assoc record
           :history (assoc (or (:history record) {}) vid version)
           :current vid
           :resource version
           :deleted? false
           ;; Creation order, the order of an unsorted search.
           :seq (or (:seq record) (:tx-id basis)))))

(defn- check-if-match!
  [expected record]
  (let [active? (and record (not (:deleted? record)))
        current-vid (:current record)
        ;; `If-Match: *` guards on existence alone: any live version satisfies
        ;; it, so only the existence check applies to it.
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
                       :actual current-vid})))))

(defn- versions-newest-first
  "Every version of a record, deletions included, newest first."
  [record]
  (sort-by #(- (Long/parseLong (get-in % [:meta :versionId])))
           (concat (vals (:history record)) (vals (:deletes record)))))

(defn- live-resources
  "The type's live resources in creation order."
  [s tid rt]
  (->> (vals (get-in s [tid rt]))
       (remove :deleted?)
       (sort-by :seq)
       (map :resource)))

(defrecord MockStore [state options]
  protocol/IFHIRStore
  (create-resource [this tenant-id resource-type id resource]
    (protocol/create-resource this tenant-id resource-type id resource nil))

  (create-resource [this tenant-id resource-type id resource opts]
    (let [tid (tenant-key tenant-id)
          rt (type-key resource-type)
          id (or id (mint-id this))
          write (atom nil)
          result (atom nil)
          new-state
          (swap! state
                 (fn [s]
                   (let [existing (get-in s [tid rt id])]
                     ;; A deleted id may be created again; its versions go on
                     ;; from the deletion's.
                     (when (and existing (not (:deleted? existing)))
                       (throw (ex-info "Resource already exists"
                                       {:fhir/status 409
                                        :fhir/code "conflict"
                                        :id id
                                        :resource-type (name rt)})))
                     (let [basis (next-basis this)
                           w (lifecycle-write this tid :create rt id resource s opts)
                           version (stamp-version (:resource w) id (next-version-id existing) basis)]
                       (reset! write w)
                       (reset! result version)
                       (assoc-in s [tid rt id] (put-version existing version basis))))))]
      (committed! this tid @write new-state)
      @result))

  (read-resource [_ tenant-id resource-type id]
    (let [record (get-in @state [(tenant-key tenant-id) (type-key resource-type) id])]
      (when (and record (not (:deleted? record)))
        (:resource record))))

  (vread-resource [_ tenant-id resource-type id vid]
    (get-in @state [(tenant-key tenant-id) (type-key resource-type) id :history (str vid)]))

  (update-resource [this tenant-id resource-type id resource]
    (protocol/update-resource this tenant-id resource-type id resource nil))

  (update-resource [this tenant-id resource-type id resource opts]
    (let [tid (tenant-key tenant-id)
          rt (type-key resource-type)
          expected (protocol/normalize-if-match (:if-match opts))
          result (atom nil)
          write (atom nil)
          new-state
          (swap! state
                 (fn [s]
                   (let [existing (get-in s [tid rt id])
                         _ (check-if-match! expected existing)
                         basis (next-basis this)
                         w (lifecycle-write this tid :update rt id resource s opts)
                         version (stamp-version (:resource w) id (next-version-id existing) basis)]
                     (reset! write w)
                     (reset! result version)
                     (assoc-in s [tid rt id] (put-version existing version basis)))))]
      (committed! this tid @write new-state)
      @result))

  (delete-resource [this tenant-id resource-type id]
    (protocol/delete-resource this tenant-id resource-type id nil))

  (delete-resource [this tenant-id resource-type id opts]
    ;; As the protocol's write-return convention: an empty map, carrying the
    ;; basis when a deletion was written. Deleting what is not live writes
    ;; nothing and returns a bare empty map.
    (let [tid (tenant-key tenant-id)
          rt (type-key resource-type)
          expected (protocol/normalize-if-match (:if-match opts))
          result (atom {})]
      (swap! state
             (fn [s]
               (reset! result {})
               (let [existing (get-in s [tid rt id])]
                 (check-if-match! expected existing)
                 (if (and existing (not (:deleted? existing)))
                   (let [basis (next-basis this)
                         vid (next-version-id existing)
                         ;; A deletion's history entry: no body, only the
                         ;; identity and the version it ended the resource at.
                         stub (with-meta {:resourceType (name rt)
                                          :id id
                                          :meta {:versionId vid
                                                 :lastUpdated (:system-time basis)}}
                                {:fhir-store/basis basis
                                 :fhir-store/deleted? true})]
                     (reset! result (with-basis {} basis))
                     (assoc-in s [tid rt id]
                               (assoc existing
                                      :deletes (assoc (or (:deletes existing) {}) vid stub)
                                      :current vid
                                      :deleted? true
                                      :resource nil)))
                   s))))
      @result))

  (resource-deleted? [_ tenant-id resource-type id]
    (let [record (get-in @state [(tenant-key tenant-id) (type-key resource-type) id])]
      (boolean (and record (:deleted? record)))))

  (search [_ tenant-id resource-type params search-registry]
    (let [candidates (live-resources @state (tenant-key tenant-id) (type-key resource-type))
          filter-params (into {} (remove (fn [[k _]] (contains? result-params (name k)))) params)
          filtered (filter (fn [res]
                             (every? (fn [[k v]] (match-param res search-registry k v))
                                     filter-params))
                           candidates)
          specs (sort-specs (or (get params "_sort") (get params :_sort)))
          sorted (if (seq specs) (sort-results filtered search-registry specs) filtered)
          limit (param-int params "_count" "50")
          offset (or (param-int params "_skip" nil) (param-int params "_offset" "0"))]
      (->> sorted (drop offset) (take limit) vec)))

  (count-resources [this tenant-id resource-type params search-registry]
    ;; Reuse search with a high limit to count all matching resources
    (let [count-params (-> params
                           (dissoc "_count" "_skip" "_offset" :_offset)
                           (assoc :_count "2147483647" :_skip "0"))]
      (count (protocol/search this tenant-id resource-type count-params search-registry))))

  (history [_ tenant-id resource-type id]
    (if-let [record (get-in @state [(tenant-key tenant-id) (type-key resource-type) id])]
      (vec (versions-newest-first record))
      []))

  (history-type [_ tenant-id resource-type _params]
    (->> (vals (get-in @state [(tenant-key tenant-id) (type-key resource-type)]))
         (mapcat versions-newest-first)
         (sort-by (fn [v] [(- (get-in (meta v) [:fhir-store/basis :tx-id] 0))
                           (:id v)
                           (- (Long/parseLong (get-in v [:meta :versionId])))]))
         vec))

  (transact-transaction [this tenant-id entries]
    (protocol/transact-transaction this tenant-id entries nil))

  (transact-transaction [this tenant-id entries opts]
    ;; Atomic transaction: snapshot state for rollback on failure.
    ;; Entries are reordered per FHIR §3.1.0.11.2: DELETE -> POST -> PUT/PATCH -> GET/HEAD
    (ftrace/trace!
     {:id :store/transact-transaction
      :data {:tenant-id (tenant-key tenant-id) :entry-count (count entries)}}
     (let [ordered (sort-by #(method-order (get-in % [:request :method])) entries)
           snapshot @state
           writes (atom [])
           basis (next-basis this)]
       (try
         (let [results (binding [*transaction-writes* writes
                                 *transaction-basis* basis]
                         (mapv (fn [entry]
                                 (let [req (:request entry)
                                       method (:method req)
                                       url (:url req)
                                       resource (:resource entry)
                                       [type id] (str/split url #"/")
                                       rt (type-key type)
                                       ;; update/delete-resource normalize the
                                       ;; ETag spellings themselves; this only
                                       ;; decides whether the entry carries a
                                       ;; precondition at all. Dropping it would
                                       ;; turn a guarded write into an
                                       ;; unconditional one.
                                       entry-if-match (or (:ifMatch req) (get req "ifMatch"))]
                                   (case method
                                     "POST" (let [res (create-with this tenant-id rt resource (entry-opts opts nil))
                                                  vid (get-in res [:meta :versionId])
                                                  last-mod (str (get-in res [:meta :lastUpdated]))]
                                              {:resource res
                                               :response {:status "201 Created"
                                                          :location (str type "/" (:id res) "/_history/" vid)
                                                          :etag (str "W/\"" vid "\"")
                                                          :lastModified last-mod}})
                                     "PUT" (let [res (update-with this tenant-id rt id resource
                                                                  (entry-opts opts entry-if-match))
                                                 vid (get-in res [:meta :versionId])
                                                 last-mod (str (get-in res [:meta :lastUpdated]))]
                                             {:resource res
                                              :response {:status "200 OK"
                                                         :etag (str "W/\"" vid "\"")
                                                         :lastModified last-mod}})
                                     "DELETE" (do (delete-with this tenant-id rt id
                                                               (entry-opts opts entry-if-match))
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
                               ordered))]
           ;; Once for the whole Bundle, and only once every entry landed.
           (when (seq @writes)
             (lc/fire-after-commit! (:resource/lifecycle this)
                                    {:tenant-id (tenant-key tenant-id)
                                     :writes    @writes
                                     :result    @state
                                     :store     this}))
           (with-basis {:resourceType "Bundle"
                        :type "transaction-response"
                        :entry results}
             basis))
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
      :data {:tenant-id (tenant-key tenant-id) :entry-count (count entries)}}
     (let [results
           (mapv
            (fn [entry]
              (try
                (let [req (:request entry)
                      method (some-> (:method req) str/upper-case)
                      url (:url req)
                      [type id] (when url (str/split url #"/"))
                      rt (when type (type-key type))
                      resource (:resource entry)
                      ;; update/delete-resource normalize the ETag spellings
                      ;; themselves; this only decides whether the entry
                      ;; carries a precondition at all.
                      entry-if-match (or (:ifMatch req) (get req "ifMatch"))]
                  (case method
                    "POST"
                    (let [res (create-with this tenant-id rt resource (entry-opts opts nil))
                          vid (get-in res [:meta :versionId])
                          last-mod (str (get-in res [:meta :lastUpdated]))]
                      {:resource res
                       :response (cond-> {:status "201 Created"}
                                   vid (assoc :etag (str "W/\"" vid "\"")
                                              :location (str type "/" (:id res) "/_history/" vid))
                                   last-mod (assoc :lastModified last-mod))})

                    "PUT"
                    (let [res (update-with this tenant-id rt id resource
                                           (entry-opts opts entry-if-match))
                          vid (get-in res [:meta :versionId])
                          last-mod (str (get-in res [:meta :lastUpdated]))]
                      {:resource res
                       :response (cond-> {:status "200 OK"}
                                   vid (assoc :etag (str "W/\"" vid "\""))
                                   last-mod (assoc :lastModified last-mod))})

                    "DELETE"
                    (do (delete-with this tenant-id rt id (entry-opts opts entry-if-match))
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
    (let [tid       (tenant-key tenant-id)
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
    (let [tid       (tenant-key tenant-id)
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
    (swap! state update (tenant-key tenant-id) (fnil identity {}))
    nil)

  (current-basis [this _tenant-id]
    ;; No-history limitation: the mock keeps only current state, so it cannot
    ;; reconstruct a past snapshot. The basis is a strictly monotonic token
    ;; (successive kickoffs get distinct :tx-id) but scan-type-as-of /
    ;; count-as-of below ignore it and read CURRENT state.
    (binding [*transaction-basis* nil]
      (next-basis this)))

  (scan-type-as-of [_ tenant-id resource-type _basis]
    ;; No history: returns a lazy seq over the CURRENT live resources of
    ;; `resource-type` (the basis is ignored). @state is snapshotted eagerly so
    ;; the seq is stable against later writes; the transformation is lazy and
    ;; realized incrementally by the consumer.
    (live-resources @state (tenant-key tenant-id) (type-key resource-type)))

  (count-as-of [_ tenant-id resource-type _basis]
    ;; No history: count of CURRENT live resources of `resource-type`.
    (->> (get-in @state [(tenant-key tenant-id) (type-key resource-type)])
         vals
         (remove :deleted?)
         count)))

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

(defn create-mock-store
  "Options may carry:

   - :resource/lifecycle (qualified symbol, value, or constructor fn of
     `options`), resolved once here; see fhir-store.lifecycle and the notes
     above MockStore for what a store without transactions does with it.
   - :clock, a (fn [] java.time.Instant): every write's meta.lastUpdated and
     basis :system-time, and current-basis's. Defaults to Instant/now.
   - :id-fn, a (fn [] string): the id of a create that names none. Defaults to
     a random UUID."
  [options]
  (let [store (->MockStore (atom {}) options)]
    (assoc store
           :resource/lifecycle (lc/resolve-lifecycle (:resource/lifecycle options) options)
           :clock (or (:clock options) #(java.time.Instant/now))
           :id-fn (or (:id-fn options) new-id)
           :basis-counter (atom 0)
           :operations {:valueset-expand mock-valueset-expand
                        :valueset-lookup mock-valueset-lookup})))

(defn halt-mock-store [store]
  (reset! (:state store) {})
  store)
