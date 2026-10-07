(ns fhir-store.lifecycle-store
  "PROTOTYPE: the write lifecycle as a store WRAPPER instead of a sequence
   every store reimplements.

   Today each store (mock, xtdb2, datomic, http) runs `prepare`, `tx-ops` and
   `after-commit` itself, in the order `fhir-store.lifecycle` documents. Here
   the wrapper owns that order, and a store's whole obligation shrinks to ONE
   opts key:

     :lifecycle/tx-ops   (fn [{:keys [resource-type id db entity]}] ops)

   handed to every create, update and Bundle write. The store calls it, through
   `contributed-ops`, once per written resource INSIDE its transaction, with
   its own read handle and entity id, and appends what it returns in its own
   dialect. That keeps the one thing a wrapper cannot do for itself -- compute
   ops against the exact db value the transaction is built from -- with the
   store, and moves everything else out.

   A store declares that it honours the key by implementing `ITxOpsStore`.
   Wrapping a store that does not, with a lifecycle that writes, is refused at
   construction: a store that ignored the key would drop the ops silently.

   WHAT THE WRAPPER DOES

   - Mints the id of a create that has none, so `prepare` sees a final id, and
     rewrites a transaction or batch POST entry into a PUT under that id. The
     response entry is answered `201 Created` again afterwards. Because stores
     resolve `urn:uuid:` fullUrls only for POST entries (fhir-store-datomic's
     `build-urn-uuid-mapping` does exactly that), the wrapper resolves every
     reference to a rewritten POST's fullUrl itself, before `prepare`.
   - Checks preconditions itself before `prepare`, because the lifecycle
     contract promises `prepare` only writes whose `:if-match` passed and whose
     create id is free. It does so with a READ of the current version: one
     extra read per guarded write or caller-id create. The store still makes
     its own atomic check, so a write that loses a race in between is refused
     by the store after `prepare` ran; the contract already forbids `prepare`
     effects that outlive a refused write.
   - Fires `after-commit` once per call that returned: once for a single
     write, once for a transaction with every write, and once per landed entry
     of a batch, judged by the entry's response status.
   - Leaves deletes, reads and `present` alone. `present` stays at the response
     layer (fhir-server's router), because a wrapper would also present the
     server's own reads -- a PATCH base, an upsert's existence check.

   CAPABILITIES. The server decides 400-versus-answer with `satisfies?` on
   `ITemporalReadStore`, so the wrapper must implement exactly what its base
   does. There is one wrapper type per capability set it can meet: plain,
   temporal, and temporal plus valid-time. `ITextSearchStore` and
   `ITxMetadataStore` already have capability queries and are always
   delegated. Valid-time writes pass straight through and never reach the
   lifecycle, which is also true of every store today.

   Map keys on the base (the mock's `:state` and `:operations`, say) are
   copied onto the wrapper, because hosts and the server read them off
   whatever store they are handed."
  (:require [clojure.string :as str]
            [clojure.walk]
            [fhir-store.lifecycle :as lc]
            [fhir-store.protocol :as fp]))

;; ---------------------------------------------------------------------------
;; The store side of the contract
;; ---------------------------------------------------------------------------

(defprotocol ITxOpsStore
  (tx-ops-dialect [this]
    "The dialect of the ops this store appends from `:lifecycle/tx-ops`:
     :datomic (tx-data), :xtdb2 (tx-op vectors), :fhir-bundle (Bundle
     entries), or :recorded (computed, never applied -- the mock)."))

(defn contributed-ops
  "Store side: the ops the lifecycle contributes for one written resource, or
   nil. Call it inside the transaction, once per created or updated resource,
   with `{:resource-type :id :db :entity}`. A refusal it throws aborts the
   write, as a thrown `tx-ops` always has."
  [opts ctx]
  (when-let [f (:lifecycle/tx-ops opts)]
    (f ctx)))

;; ---------------------------------------------------------------------------
;; Writes
;; ---------------------------------------------------------------------------

(defn- writes-lifecycle? [store]
  (satisfies? lc/IWriteLifecycle (:lifecycle store)))

(defn- write-map
  [store tenant-id method resource-type id resource]
  {:tenant-id     (str tenant-id)
   :resource-type (name resource-type)
   :id            id
   :method        method
   :resource      resource
   :db            nil
   :entity        nil
   :store         store})

(defn- prepare
  "The write map with :resource the prepared body and :submitted the body the
   caller sent."
  [store write]
  (assoc write
         :resource  (lc/prepare-write (:lifecycle store) write)
         :submitted (:resource write)))

(defn- key-of [resource-type id] [(name resource-type) (str id)])

(defn- precheck!
  "Refuse before `prepare` what the store would refuse: a stale or
   unsatisfiable `:if-match`, or a create under an id that is taken. Mirrors
   the stores' own 412 and 409 answers; the store re-checks atomically."
  [store tenant-id method resource-type id if-match minted?]
  (let [expected (fp/normalize-if-match if-match)]
    (when (or expected (and (= :create method) (not minted?)))
      (let [current (fp/read-resource (:base store) tenant-id (keyword (name resource-type)) id)
            actual  (get-in current [:meta :versionId])]
        (cond
          (and (= :create method) (not minted?) current)
          (throw (ex-info "Resource already exists"
                          {:fhir/status 409 :fhir/code "duplicate"}))

          (and expected (nil? current))
          (throw (ex-info "Version conflict"
                          {:fhir/status 412 :fhir/code "conflict"
                           :expected (fp/if-match-label expected) :actual nil}))

          (and expected (not= fp/if-match-any expected) (not= expected actual))
          (throw (ex-info "Version conflict"
                          {:fhir/status 412 :fhir/code "conflict"
                           :expected expected :actual actual})))))))

(defn- ops-fn
  "The `:lifecycle/tx-ops` fn for a set of prepared writes, recording what it
   returned per resource in `recorded` so `after-commit` sees the ops each
   write contributed. A store may call it more than once (the mock retries a
   swap!); the last answer is the one recorded."
  [store prepared recorded]
  (fn [{:keys [resource-type id db entity]}]
    (when-let [write (get prepared (key-of resource-type id))]
      (let [ops (lc/write-tx-ops (:lifecycle store)
                                 (assoc write :db db :entity entity))]
        (swap! recorded assoc (key-of resource-type id) ops)
        ops))))

(defn- fire!
  [store tenant-id writes recorded result]
  (when (seq writes)
    (lc/fire-after-commit! (:lifecycle store)
                           {:tenant-id (str tenant-id)
                            :writes    (mapv #(assoc % :tx-ops (get recorded (key-of (:resource-type %) (:id %))))
                                             writes)
                            :result    result
                            :store     store})))

(defn- single-write
  "create or update through the base, with the lifecycle around it."
  [store verb tenant-id resource-type id resource opts]
  (let [method   (if (= verb :create) :create :update)
        minted?  (nil? id)
        id       (or id (str (random-uuid)))
        _        (precheck! store tenant-id method resource-type id (:if-match opts) minted?)
        write    (prepare store (write-map store tenant-id method resource-type id resource))
        recorded (atom {})
        opts     (assoc opts :lifecycle/tx-ops
                        (ops-fn store {(key-of resource-type id) write} recorded))
        base     (:base store)
        result   (if (= verb :create)
                   (fp/create-resource base tenant-id resource-type id (:resource write) opts)
                   (fp/update-resource base tenant-id resource-type id (:resource write) opts))]
    (fire! store tenant-id [write] @recorded result)
    result))

;; ---------------------------------------------------------------------------
;; Bundles
;; ---------------------------------------------------------------------------

(defn- entry-method [entry]
  (some-> (get-in entry [:request :method]) str/upper-case))

(defn- mint-post-ids
  "Each POST entry with a minted id under ::id, and the urn:uuid fullUrl ->
   `Type/id` mapping for those that carry one."
  [entries]
  (let [minted (mapv (fn [e]
                       (if (= "POST" (entry-method e))
                         (assoc e ::id (str (random-uuid)))
                         e))
                     entries)]
    [minted
     (into {} (keep (fn [{:keys [fullUrl] ::keys [id] :as e}]
                      (when (and id fullUrl (str/starts-with? fullUrl "urn:uuid:"))
                        [fullUrl (str (first (str/split (get-in e [:request :url]) #"/")) "/" id)])))
           minted)]))

(defn- resolve-urns
  "`resource` with every string equal to a mapped urn:uuid fullUrl replaced by
   the reference it now names."
  [resource mapping]
  (if (empty? mapping)
    resource
    (clojure.walk/postwalk #(if (string? %) (get mapping % %) %) resource)))

(defn- plan-entry
  "One Bundle entry as it goes to the base, and its prepared write when it
   writes a resource. A POST becomes a PUT under its minted id; a conditional
   create cannot, and is refused."
  [store tenant-id urns entry]
  (let [method  (entry-method entry)
        [rt id] (str/split (or (get-in entry [:request :url]) "") #"/" 3)
        entry   (cond-> entry (:resource entry) (update :resource resolve-urns urns))]
    (case method
      ("POST" "PUT")
      (do
        (when (get-in entry [:request :ifNoneExist])
          (throw (ex-info "A conditional create in a Bundle is not supported by the lifecycle store"
                          {:fhir/status 501 :fhir/code "not-supported"})))
        (let [id    (if (= "POST" method) (::id entry) id)
              _     (when (= "PUT" method)
                      (precheck! store tenant-id :update rt id (get-in entry [:request :ifMatch]) false))
              write (prepare store (write-map store tenant-id
                                              (if (= "POST" method) :create :update)
                                              rt id (:resource entry)))]
          {:entry   (-> entry
                        (assoc-in [:request :method] "PUT")
                        (assoc-in [:request :url] (str rt "/" id))
                        (assoc :resource (assoc (:resource write) :id id))
                        (dissoc ::id))
           :write   write
           :posted? (= "POST" method)}))
      {:entry entry})))

(defn- prepared-by-key [planned]
  (into {} (keep (fn [{:keys [write]}]
                   (when write [(key-of (:resource-type write) (:id write)) write])))
        planned))

(defn- answer-posts-as-created
  "Response entries for rewritten POSTs say `201 Created` again, matched by
   the written resource's id because a store may answer in processing order
   rather than input order."
  [response planned]
  (let [posted (into {} (keep #(when (:posted? %) [(get-in % [:write :id]) (:write %)])) planned)]
    (update response :entry
            (fn [entries]
              (mapv (fn [e]
                      (let [res (:resource e)
                            w   (when res (get posted (:id res)))]
                        (if w
                          (assoc e :response
                                 (cond-> (assoc (:response e) :status "201 Created")
                                   (get-in res [:meta :versionId])
                                   (assoc :location (str (:resource-type w) "/" (:id res) "/_history/"
                                                         (get-in res [:meta :versionId])))))
                          e)))
                    entries)))))

(defn- landed? [entry]
  (some-> (get-in entry [:response :status]) (str/starts-with? "2")))

(defn- bundle-write
  [store verb tenant-id entries opts]
  (let [[entries urns] (mint-post-ids entries)
        planned  (mapv #(plan-entry store tenant-id urns %) entries)
        prepared (prepared-by-key planned)
        recorded (atom {})
        opts     (assoc opts :lifecycle/tx-ops (ops-fn store prepared recorded))
        base     (:base store)
        sent     (mapv :entry planned)
        response (answer-posts-as-created
                  (if (= verb :transaction)
                    (fp/transact-transaction base tenant-id sent opts)
                    (fp/transact-bundle base tenant-id sent opts))
                  planned)]
    (if (= verb :transaction)
      (fire! store tenant-id (into [] (keep :write) planned) @recorded response)
      ;; A batch-response answers entry for entry, in request order.
      (doseq [[e {:keys [write]}] (map vector (:entry response) planned)
              :when (and write (landed? e))]
        (fire! store tenant-id [write] @recorded response)))
    response))

;; ---------------------------------------------------------------------------
;; Delegation
;; ---------------------------------------------------------------------------

(defn- forward
  "Rule 2 of the opts convention: the opts arity iff opts is non-nil."
  [f-plain f-opts opts]
  (if opts (f-opts) (f-plain)))

(def ^:private fhir-store-impl
  {:create-resource
   (fn
     ([this t rt id r] (fp/create-resource this t rt id r nil))
     ([this t rt id r opts]
      (if (writes-lifecycle? this)
        (single-write this :create t rt id r opts)
        (forward #(fp/create-resource (:base this) t rt id r)
                 #(fp/create-resource (:base this) t rt id r opts) opts))))
   :update-resource
   (fn
     ([this t rt id r] (fp/update-resource this t rt id r nil))
     ([this t rt id r opts]
      (if (writes-lifecycle? this)
        (single-write this :update t rt id r opts)
        (forward #(fp/update-resource (:base this) t rt id r)
                 #(fp/update-resource (:base this) t rt id r opts) opts))))
   :delete-resource
   (fn
     ([this t rt id] (fp/delete-resource (:base this) t rt id))
     ([this t rt id opts]
      (forward #(fp/delete-resource (:base this) t rt id)
               #(fp/delete-resource (:base this) t rt id opts) opts)))
   :transact-transaction
   (fn
     ([this t entries] (fp/transact-transaction this t entries nil))
     ([this t entries opts]
      (if (writes-lifecycle? this)
        (bundle-write this :transaction t entries opts)
        (forward #(fp/transact-transaction (:base this) t entries)
                 #(fp/transact-transaction (:base this) t entries opts) opts))))
   :transact-bundle
   (fn
     ([this t entries] (fp/transact-bundle this t entries nil))
     ([this t entries opts]
      (if (writes-lifecycle? this)
        (bundle-write this :batch t entries opts)
        (forward #(fp/transact-bundle (:base this) t entries)
                 #(fp/transact-bundle (:base this) t entries opts) opts))))
   :read-resource     (fn [this t rt id] (fp/read-resource (:base this) t rt id))
   :vread-resource    (fn [this t rt id vid] (fp/vread-resource (:base this) t rt id vid))
   :search            (fn [this t rt p reg] (fp/search (:base this) t rt p reg))
   :count-resources   (fn [this t rt p reg] (fp/count-resources (:base this) t rt p reg))
   :history           (fn [this t rt id] (fp/history (:base this) t rt id))
   :history-type      (fn [this t rt p] (fp/history-type (:base this) t rt p))
   :resource-deleted? (fn [this t rt id] (fp/resource-deleted? (:base this) t rt id))
   :create-tenant     (fn ([this t] (fp/create-tenant (:base this) t))
                        ([this t opts] (forward #(fp/create-tenant (:base this) t)
                                                #(fp/create-tenant (:base this) t opts) opts)))
   :delete-tenant     (fn ([this t] (fp/delete-tenant (:base this) t))
                        ([this t opts] (forward #(fp/delete-tenant (:base this) t)
                                                #(fp/delete-tenant (:base this) t opts) opts)))
   :warmup-tenant     (fn ([this t] (fp/warmup-tenant (:base this) t))
                        ([this t opts] (forward #(fp/warmup-tenant (:base this) t)
                                                #(fp/warmup-tenant (:base this) t opts) opts)))
   :current-basis     (fn [this t] (fp/current-basis (:base this) t))
   :scan-type-as-of   (fn [this t rt b] (fp/scan-type-as-of (:base this) t rt b))
   :count-as-of       (fn [this t rt b] (fp/count-as-of (:base this) t rt b))})

(def ^:private text-search-impl
  {:text-searchable? (fn [this t rt]
                       (let [base (:base this)]
                         (boolean (and (satisfies? fp/ITextSearchStore base)
                                       (fp/text-searchable? base t rt)))))})

(def ^:private tx-metadata-impl
  {:tx-metadata-supported? (fn [this] (fp/supports-tx-metadata? (:base this)))
   :tx-metadata-of         (fn [this t rt id vid]
                             (let [base (:base this)]
                               (when (satisfies? fp/ITxMetadataStore base)
                                 (fp/tx-metadata-of base t rt id vid))))})

(def ^:private temporal-impl
  {:temporal-axes     (fn [this] (fp/temporal-axes (:base this)))
   :read-as-of        (fn [this t rt id b] (fp/read-as-of (:base this) t rt id b))
   :search-as-of      (fn [this t rt p reg b] (fp/search-as-of (:base this) t rt p reg b))
   :count-as-of-basis (fn [this t rt p reg b] (fp/count-as-of-basis (:base this) t rt p reg b))
   :resource-timeline (fn [this t rt id o] (fp/resource-timeline (:base this) t rt id o))})

(def ^:private valid-time-impl
  {:put-valid-time   (fn [this t rt id r vt] (fp/put-valid-time (:base this) t rt id r vt))
   :close-valid-time (fn
                       ([this t rt id from] (fp/close-valid-time (:base this) t rt id from))
                       ([this t rt id from to] (fp/close-valid-time (:base this) t rt id from to)))})

(defrecord LifecycleStore [base lifecycle])
(defrecord TemporalLifecycleStore [base lifecycle])
(defrecord BitemporalLifecycleStore [base lifecycle])

(doseq [t [LifecycleStore TemporalLifecycleStore BitemporalLifecycleStore]]
  (extend t
    fp/IFHIRStore        fhir-store-impl
    fp/ITextSearchStore  text-search-impl
    fp/ITxMetadataStore  tx-metadata-impl))
(extend TemporalLifecycleStore   fp/ITemporalReadStore temporal-impl)
(extend BitemporalLifecycleStore fp/ITemporalReadStore temporal-impl
                                 fp/IValidTimeStore    valid-time-impl)

(defn wrap
  "`base` with `lifecycle` (already resolved, e.g. by
   `fhir-store.lifecycle/resolve-lifecycle`) run around its writes. nil
   lifecycle -> `base` itself.

   Throws ex-info `{:fhir/status 500 :lifecycle/problem <kw>}` when the
   lifecycle writes and `base` does not implement `ITxOpsStore`, or when
   `base` has valid-time writes without temporal reads, a combination no
   wrapper type here meets."
  [base lifecycle]
  (if (nil? lifecycle)
    base
    (let [temporal?   (satisfies? fp/ITemporalReadStore base)
          valid-time? (satisfies? fp/IValidTimeStore base)
          refuse!     (fn [problem message]
                        (throw (ex-info message {:fhir/status 500 :fhir/code "exception"
                                                 :lifecycle/problem problem})))]
      (when (and (satisfies? lc/IWriteLifecycle lifecycle)
                 (not (satisfies? ITxOpsStore base)))
        (refuse! :store-ignores-tx-ops
                 "This store does not honour :lifecycle/tx-ops, so a write lifecycle's ops would be dropped"))
      (when (and valid-time? (not temporal?))
        (refuse! :unsupported-capabilities
                 "No lifecycle store wrapper exists for valid-time writes without temporal reads"))
      (merge ((cond valid-time? map->BitemporalLifecycleStore
                    temporal?   map->TemporalLifecycleStore
                    :else       map->LifecycleStore)
              {:base base :lifecycle lifecycle})
             (when (map? base) (dissoc (into {} base) :base :lifecycle))
             {:resource/lifecycle lifecycle}))))
