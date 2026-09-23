(ns fhir-store.lifecycle
  "The seam through which a host takes part in a store's writes and reads.

   A host (flotilla, for example) may need to do more than persist the body a
   client sent: fill a derived field, write rows of its own in the same
   transaction, move a blob once the write is durable, or present a stored
   resource differently on a response. It does that through ONE lifecycle
   object the store resolves from its constructor opts:

     {:resource/lifecycle my.host.lifecycle/lifecycle}   ; qualified symbol
     {:resource/lifecycle (->MyLifecycle ...)}           ; a value
     {:resource/lifecycle my.host.lifecycle/make}        ; (fn [store-opts] ...)

   The store calls `resolve-lifecycle` ONCE at construction and keeps the
   result on the store record under `:resource/lifecycle` (by assoc, not as a
   positional field). With no `:resource/lifecycle` the store behaves exactly
   as it did before this seam existed.

   dromon never names a resource type here. A lifecycle is handed EVERY
   create and update and filters its own types; a lifecycle that only cares
   about Composition returns every other resource unchanged.

   THE ORDERING CONTRACT, for a create or update (PATCH arrives as a guarded
   update and is included; deletes never reach a lifecycle):

     1. Preconditions pass (`:if-match` checked) and the id is final (minted,
        for a server-assigned create).
     2. `prepare` returns the resource to persist.
     3. `tx-ops` returns extra ops for the SAME transaction, computed from the
        PREPARED resource. They are appended to the tx beside the
        `:tx-metadata` stamp ops.
     4. Only now does the store build its own tx data and save any contained
        blobs, from the prepared resource.
     5. The transaction commits.
     6. `after-commit` fires ONCE for the transaction; a transaction Bundle
        fires once with all its writes, a batch once per entry that landed.

   A write refused at 1 or 2, or whose tx fails at 5 (a 409 or 412 lost
   race, a schema rejection), must leave nothing moved. That is why `prepare`
   and `tx-ops` may read but must not perform I/O whose effect outlives a
   failed transaction, and why anything that must not happen for a write that
   did not land belongs in `after-commit`.

   THE WRITE MAP handed to `prepare` and `tx-ops`:

     :tenant-id      string
     :resource-type  string
     :id             the final logical id
     :method         :create or :update
     :resource       for `prepare`: the submitted body.
                     for `tx-ops`: the PREPARED body.
     :submitted      for `tx-ops`: the original submitted body.
     :db             the store's read handle for this write. Datomic: the db
                     value the tx data is built from. xtdb2: the node or jdbc
                     connection being written through.
     :entity         Datomic: the resource entity's eid (Long) or its tempid
                     inside this tx. xtdb2: nil.
     :store          the store itself.

   THE COMMIT MAP handed to `after-commit`:

     :tenant-id  string
     :writes     vector of write maps, each with :resource = the prepared body
                 and :tx-ops = the ops that write contributed
     :result     the store's commit result (Datomic tx report, xtdb2 tx-key)
     :store      the store itself

   THE READ MAP handed to `present`:

     :tenant-id      string
     :resource-type  string
     :store          the request's `:fhir/store`
     :request        the ring request

   FAILURE, by phase. `prepare` and `tx-ops` exceptions PROPAGATE: they are
   the only way to refuse a write, and an ex-info carrying `:fhir/status` is
   answered with that status. `after-commit` and `present` exceptions are
   CONTAINED and logged, because the write has already landed and the read has
   already succeeded; a host bug there must cost a missing side effect, never
   a failed request. The log events are meant to be monitored:

     :fhir-store/lifecycle-after-commit-failed  {:tenant-id :resource-types}
     :fhir-store/lifecycle-present-failed       {:tenant-id :resource-type}

   RESOURCE CONTENT NEVER REACHES A SIGNAL. The events above carry the tenant
   id and resource types only -- the same rule as `fhir-store.trace` -- and a
   lifecycle must not put resource content into an exception message either,
   since propagated exceptions are logged by the server.

   Stores and the server call the helpers below rather than the protocol
   methods, so a call site is one line and a nil lifecycle, or one that
   implements only some of the protocols, needs no branch of its own."
  (:require [taoensso.telemere :as tel]))

(defprotocol IWriteLifecycle
  (prepare [this write]
    "Return the resource to persist. Runs after preconditions pass and the id
     is final, before any tx data or blob is built. Must do no I/O that
     outlives a failed tx (reads are fine). May throw ex-info with
     :fhir/status to refuse the write.")
  (tx-ops [this write]
    "Extra ops for the SAME transaction, in the calling store's own dialect
     (Datomic tx-data; XTDB tx-op vectors). Seq or nil. Computed before blobs
     are saved, like tx-metadata stamp ops.")
  (after-commit [this commit]
    "Fired once per committed transaction. Exceptions are logged (telemere)
     and never rethrown."))

(defprotocol IReadLifecycle
  (present [this read resource]
    "The resource to put on a response. Never changes storage. Exceptions ->
     resource unchanged, logged."))

(defprotocol IStoreSchema
  (schema-tx [this]
    "Store-dialect schema the lifecycle needs installed per tenant (Datomic
     attribute maps). Schemaless stores ignore it."))

(defn lifecycle?
  "True when `x` implements IWriteLifecycle or IReadLifecycle."
  [x]
  (boolean (or (satisfies? IWriteLifecycle x)
               (satisfies? IReadLifecycle x))))

(defn- refuse!
  [problem message spec]
  (throw (ex-info message
                  {:fhir/status        500
                   :fhir/code          "exception"
                   :lifecycle/problem  problem
                   :resource/lifecycle (when (symbol? spec) spec)})))

(defn- resolve-symbol
  [sym]
  (when-not (qualified-symbol? sym)
    (refuse! :unqualified-symbol
             ":resource/lifecycle symbol must be namespace-qualified" sym))
  (let [v (try
            (requiring-resolve sym)
            (catch Exception e
              (throw (ex-info (str ":resource/lifecycle " sym " does not resolve")
                              {:fhir/status        500
                               :fhir/code          "exception"
                               :lifecycle/problem  :unresolvable
                               :resource/lifecycle sym}
                              e))))]
    (when-not v
      (refuse! :unresolvable (str ":resource/lifecycle " sym " does not resolve") sym))
    @v))

(defn resolve-lifecycle
  "Turn a `:resource/lifecycle` store opt into a lifecycle, or nil.

   - nil                -> nil, no lifecycle.
   - qualified symbol   -> `requiring-resolve`d and dereferenced, then treated
                           as the value it names. Throws when it does not
                           resolve.
   - a lifecycle value  -> returned as is.
   - a function         -> called with `store-opts`; must return a lifecycle.

   Anything else throws ex-info `{:fhir/status 500 :lifecycle/problem <kw>}`
   at construction, where a misconfiguration belongs, rather than on the first
   write."
  [spec store-opts]
  (when (some? spec)
    (let [value (cond
                  (symbol? spec) (resolve-symbol spec)
                  (var? spec)    @spec
                  :else          spec)]
      (cond
        (lifecycle? value) value

        (fn? value)
        (let [built (value store-opts)]
          (if (lifecycle? built)
            built
            (refuse! :constructor-returned-non-lifecycle
                     ":resource/lifecycle constructor did not return a lifecycle"
                     spec)))

        :else
        (refuse! :not-a-lifecycle
                 ":resource/lifecycle is neither a lifecycle nor a constructor fn"
                 spec)))))

(defn prepare-write
  "The resource a store persists for `write`: the lifecycle's `prepare`, or
   `(:resource write)` unchanged when `lc` is nil, implements no
   IWriteLifecycle, or returns nil. Exceptions propagate -- they refuse the
   write."
  [lc write]
  (if (satisfies? IWriteLifecycle lc)
    (or (prepare lc write) (:resource write))
    (:resource write)))

(defn write-tx-ops
  "The extra same-transaction ops `lc` contributes for `write`, as a vector,
   or nil when there are none. `write`'s :resource must already be the
   prepared body. Exceptions propagate -- they refuse the write.

   Throws when `tx-ops` returns something other than nil or a sequential
   collection: a map or set here would be spliced into tx data as its
   entries."
  [lc write]
  (when (satisfies? IWriteLifecycle lc)
    (let [ops (tx-ops lc write)]
      (cond
        (nil? ops)        nil
        (sequential? ops) (not-empty (vec ops))
        :else             (refuse! :tx-ops-not-sequential
                                   "lifecycle tx-ops must return nil or a sequential collection"
                                   nil)))))

(defn fire-after-commit!
  "Hand `commit` to the lifecycle's `after-commit`. Returns nil.

   Contained: an exception is logged as
   `:fhir-store/lifecycle-after-commit-failed` with the tenant id and the
   written resource types, and never rethrown -- the transaction has already
   committed."
  [lc commit]
  (when (satisfies? IWriteLifecycle lc)
    (try
      (after-commit lc commit)
      (catch Throwable t
        (tel/error! {:id   :fhir-store/lifecycle-after-commit-failed
                     :data {:tenant-id      (:tenant-id commit)
                            :resource-types (into (sorted-set)
                                                  (keep :resource-type)
                                                  (:writes commit))}}
                    t))))
  nil)

(defn present-resource
  "The resource to put on a response for `read`: the lifecycle's `present`,
   or `resource` unchanged when `lc` is nil, implements no IReadLifecycle, or
   returns nil.

   Contained: an exception is logged as `:fhir-store/lifecycle-present-failed`
   with the tenant id and resource type, and `resource` is returned
   unchanged."
  [lc read resource]
  (if (satisfies? IReadLifecycle lc)
    (try
      (or (present lc read resource) resource)
      (catch Throwable t
        (tel/error! {:id   :fhir-store/lifecycle-present-failed
                     :data {:tenant-id     (:tenant-id read)
                            :resource-type (:resource-type read)}}
                    t)
        resource))
    resource))

(defn lifecycle-schema-tx
  "The schema `lc` needs installed per tenant, as a seq, or nil when it needs
   none or implements no IStoreSchema."
  [lc]
  (when (satisfies? IStoreSchema lc)
    (seq (schema-tx lc))))
