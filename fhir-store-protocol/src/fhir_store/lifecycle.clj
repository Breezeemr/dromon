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
     :opts           the opts the write verb was called with, nil for the
                     plain arity: `:if-match`, `:tx-metadata`, and any key a
                     host passes for its own lifecycle. A transaction or batch
                     Bundle's opts reach every entry's write map, with the
                     entry's own If-Match in place of the Bundle's.
     :db             the store's read handle for this write. Datomic: the db
                     value the tx data is built from (inside a transaction
                     Bundle, the one db every entry is built from). xtdb2:
                     the node or jdbc connection being written through.
                     mock: the tenant's immutable state value the write is
                     computed from, before the write; inside a transaction
                     Bundle, before the whole Bundle, as Datomic's. Where the
                     store implements fhir-store.protocol/IBasisReadStore,
                     `(read-in-basis store db resource-type id)` reads the
                     version the write replaces (nil for a create) without
                     depending on what the handle is.
     :entity         Datomic: the resource entity's eid (Long) or its tempid
                     inside this tx. xtdb2: nil.
     :store          the store itself.

   WHO IS WRITING. dromon never puts a host key into `:opts`, and its router
   builds opts only from the If-Match header and the host's `:tx-metadata`
   producer. So a host that needs to tell its own trusted writers apart (an
   operation, a background worker) passes a key of its own beside them, such
   as `:write/origin`, on the writes it makes, and a write through the FHIR
   surface arrives without one. A store forwards the map and never reads a
   host key.

   THE COMMIT MAP handed to `after-commit`:

     :tenant-id  string
     :writes     vector of write maps, each with :resource = the prepared body
                 and :tx-ops = the ops that write contributed
     :result     the store's commit result (Datomic tx report, xtdb2 tx-key)
     :store      the store itself

   THE READ MAP handed to `present`:

     :tenant-id      string
     :resource-type  string, the presented resource's own type. A bundle
                     that mixes types (an `_include` companion, a system-level
                     search or history) names each entry's type, not the
                     request's.
     :interaction    keyword naming the interaction whose response carries
                     the resource: :read :vread :history-instance
                     :history-type :system-history :search-type
                     :compartment-search :system-search :as-of :timeline
                     :bulk-export :create :conditional-create :update :patch
                     :transaction :batch. A host MUST treat an absent key as
                     :read: a dromon that predates the key names none, and
                     presented only single-resource and transaction/batch
                     responses.
     :store          the request's `:fhir/store`
     :request        the ring request

   `present` sees what goes on a response, not every read: the server's own
   reads (a PATCH base, an upsert's existence check, a conditional
   interaction's matches, bulk-export counts) are never presented, so a
   lifecycle cannot use `present` to observe reads.

   FAILURE, by phase. `prepare` and `tx-ops` exceptions PROPAGATE: they are
   the only way to refuse a write, and an ex-info carrying `:fhir/status` is
   answered with that status. `after-commit` and `present` exceptions are
   CONTAINED and logged, because the write has already landed and the read has
   already succeeded; a host bug there must cost a missing side effect, never
   a failed request. The log events are meant to be monitored:

     :fhir-store/lifecycle-after-commit-failed  {:tenant-id :resource-types :lifecycle}
     :fhir-store/lifecycle-present-failed       {:tenant-id :resource-type :lifecycle}

   `:lifecycle` is the failing lifecycle's class name, so a monitor can tell
   which member of a composed lifecycle failed.

   RESOURCE CONTENT NEVER REACHES A SIGNAL. The events above carry the tenant
   id, resource types and a class name only -- the same rule as
   `fhir-store.trace` -- and a lifecycle must not put resource content into an
   exception message either, since propagated exceptions are logged by the
   server.

   COMPOSITION. A store holds ONE lifecycle, and a host often has several
   concerns that each want one (Composition narrative, an eRx profile's write
   constraints, ...). `compose` joins them into one, and `:resource/lifecycle`
   accepts a vector of specs, each resolved as above and then composed:

     {:resource/lifecycle [my.host.narrative/lifecycle my.host.erx/lifecycle]}

   A member listed EARLIER is OUTER, the way an interceptor chain is ordered:
   it sees the client's body first and the response last.

     prepare       in order; each member receives the body the previous
                   member prepared. The first refusal stops the chain.
     tx-ops        in order, every member computed from the FINAL prepared
                   body and concatenated. The first refusal stops the chain.
     after-commit  in order, each member contained on its own, so one
                   member's failure costs only that member's side effect.
     present       in REVERSE order, each member contained on its own, so the
                   outermost member has the last word on a response -- a
                   member that redacts must sit before one that renders.
     schema-tx     concatenated, in order.

   Every member's write map in `after-commit` carries the WHOLE write's
   `:tx-ops`, all members' ops together, because the store records one ops
   vector per write. A member that needs its own ops back recognizes them by
   their shape.

   A host that needs to find its own member -- to hand it the store after
   construction, say -- looks it up through `members`, which sees through a
   composition, rather than by testing the store's lifecycle's type directly.

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
     (Datomic tx-data; XTDB tx-op vectors; the mock's host-row ops, see
     fhir-store.mock.core). Seq or nil. Computed before blobs are saved, like
     tx-metadata stamp ops.")
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

(declare compose)

(defn resolve-lifecycle
  "Turn a `:resource/lifecycle` store opt into a lifecycle, or nil.

   - nil                -> nil, no lifecycle.
   - qualified symbol   -> `requiring-resolve`d and dereferenced, then treated
                           as the value it names. Throws when it does not
                           resolve.
   - a lifecycle value  -> returned as is.
   - a function         -> called with `store-opts`; must return a lifecycle.
   - a vector           -> each element resolved by these same rules, then
                           `compose`d in order. Empty, or all nil, is nil.

   Anything else throws ex-info `{:fhir/status 500 :lifecycle/problem <kw>}`
   at construction, where a misconfiguration belongs, rather than on the first
   write."
  [spec store-opts]
  (cond
    (nil? spec)    nil
    (vector? spec) (compose (mapv #(resolve-lifecycle % store-opts) spec))
    :else
    (let [value (cond
                  (symbol? spec) (resolve-symbol spec)
                  (var? spec)    @spec
                  :else          spec)]
      (cond
        (lifecycle? value) value

        ;; A symbol or var may name a vector of specs, so a host can keep its
        ;; composition in one def.
        (vector? value) (resolve-lifecycle value store-opts)

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
                                                  (:writes commit))
                            :lifecycle      (.getName (class lc))}}
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
                            :resource-type (:resource-type read)
                            :lifecycle     (.getName (class lc))}}
                    t)
        resource))
    resource))

(defn lifecycle-schema-tx
  "The schema `lc` needs installed per tenant, as a seq, or nil when it needs
   none or implements no IStoreSchema."
  [lc]
  (when (satisfies? IStoreSchema lc)
    (seq (schema-tx lc))))

;; ---------------------------------------------------------------------------
;; Composition. See COMPOSITION in the namespace docstring for the ordering.
;;
;; Each phase goes through the single-lifecycle helpers above, member by
;; member, so a member that implements only some of the protocols, returns
;; nil, or throws in a contained phase behaves exactly as it would alone.
;; ---------------------------------------------------------------------------

(defrecord ComposedLifecycle [members]
  IWriteLifecycle
  (prepare [_ write]
    (reduce (fn [resource member]
              (prepare-write member (assoc write :resource resource)))
            (:resource write)
            members))
  (tx-ops [_ write]
    (not-empty (into [] (mapcat #(write-tx-ops % write)) members)))
  (after-commit [_ commit]
    (run! #(fire-after-commit! % commit) members))

  IReadLifecycle
  (present [_ read resource]
    (reduce (fn [resource member] (present-resource member read resource))
            resource
            (rseq members)))

  IStoreSchema
  (schema-tx [_]
    (into [] (mapcat lifecycle-schema-tx) members)))

(defn members
  "The lifecycles `lc` is made of, in order: a composition's members, a single
   lifecycle as a one-element vector, nil as an empty one.

   How a host finds its own member, e.g.
   `(some #(when (instance? MyLifecycle %) %) (members (:resource/lifecycle store)))`,
   so the lookup keeps working once the lifecycle is composed."
  [lc]
  (cond
    (nil? lc)                          []
    (instance? ComposedLifecycle lc)   (:members lc)
    :else                              [lc]))

(defn compose
  "One lifecycle made of `lifecycles`, in order -- see COMPOSITION in the
   namespace docstring. nil entries are dropped and nested compositions are
   flattened. Returns nil for none and the lifecycle itself for one, so
   composing never changes what a single lifecycle does.

   Throws ex-info `{:fhir/status 500 :lifecycle/problem :not-a-lifecycle}`
   when an entry is not a lifecycle."
  [lifecycles]
  (let [flat (into [] (comp (remove nil?) (mapcat members)) lifecycles)]
    (doseq [lc flat]
      (when-not (lifecycle? lc)
        (refuse! :not-a-lifecycle "compose was given something that is not a lifecycle" nil)))
    (case (count flat)
      0 nil
      1 (first flat)
      (->ComposedLifecycle flat))))
