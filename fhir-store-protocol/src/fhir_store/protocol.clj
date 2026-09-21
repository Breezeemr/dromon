(ns fhir-store.protocol
  (:require [clojure.string :as str]))

;; Reflective lookup for the OpenTelemetry Context class. We avoid a hard
;; compile-time dependency on the OTel SDK so this module stays free of
;; OpenTelemetry jars when tracing is disabled. When the SDK is on the
;; classpath (DROMON_OTEL=1 path), `Context.makeCurrent()` is invoked via
;; reflection, ensuring that XTDB v2's native spans nest under whatever
;; span is currently active in the request thread.
(def ^:private otel-context-class
  (delay
    (try
      (Class/forName "io.opentelemetry.context.Context")
      (catch Throwable _ nil))))

(defn otel-available?
  "True when the OpenTelemetry SDK is on the classpath."
  []
  (some? @otel-context-class))

(defn ^java.lang.AutoCloseable open-current-otel-scope!
  "Returns an AutoCloseable scope for the current OpenTelemetry context, or
   nil if the SDK is not loaded. Use inside a with-open or try/finally so the
   scope is always closed."
  []
  (when-let [klass @otel-context-class]
    (try
      (let [current (.invoke (.getMethod klass "current" (into-array Class []))
                             nil (into-array Object []))]
        (.invoke (.getMethod klass "makeCurrent" (into-array Class []))
                 current (into-array Object [])))
      (catch Throwable _ nil))))

(defmacro with-otel-context
  "Evaluates body with the current OpenTelemetry context made active for the
   thread, so downstream OTel-instrumented libraries (XTDB v2, etc.) see this
   span as their parent. No-op when the OpenTelemetry SDK is not on the
   classpath."
  [& body]
  `(let [^java.lang.AutoCloseable scope# (open-current-otel-scope!)]
     (try
       ~@body
       (finally
         (when scope# (.close scope#))))))

;; ---------------------------------------------------------------------------
;; Optimistic concurrency (:if-match)
;; ---------------------------------------------------------------------------

(def if-match-any
  "Sentinel `:if-match` value standing for HTTP `If-Match: *` (RFC 7232
   §3.1): the write is guarded on the resource merely existing, whatever
   version it currently holds, and fails 412 only when it does not. A
   keyword because it has to be distinguishable from every possible version
   id, which strings are not."
  :fhir.if-match/any)

(defn normalize-if-match
  "Normalize an `:if-match` opt to a bare version id string, `if-match-any`,
   or nil when the caller supplied no precondition.

   Accepts every spelling a version guard reaches a store in: the sentinel
   and `\"*\"` unchanged, an integer or bare id (`7`, `\"7\"`), and both
   ETag forms clients put on the wire and in a Bundle entry's
   `request.ifMatch` — weak (`W/\"7\"`) and strong (`\"7\"`).

   A value it does not recognize is returned as its own string rather than
   nil, so the caller's version comparison fails and the write is refused.
   Non-nil in, non-nil out: a supplied precondition must never decay into no
   precondition, which is the difference between a 412 and a lost update."
  [v]
  (cond
    (nil? v)             nil
    (= if-match-any v)   if-match-any
    (integer? v)         (str v)
    :else                (let [s (str v)]
                           (if (= "*" s)
                             if-match-any
                             (or (second (re-find #"^W/\"(.*)\"$" s))
                                 (second (re-find #"^\"(.*)\"$" s))
                                 s)))))

(defn if-match-label
  "How an `:if-match` value should read back to a client in an
   OperationOutcome: the sentinel as the `*` it came from, anything else as
   itself."
  [v]
  (if (= if-match-any v) "*" v))

;; ---------------------------------------------------------------------------
;; Transaction metadata (:tx-metadata)
;; ---------------------------------------------------------------------------

(defn check-tx-metadata
  "Validate a `:tx-metadata` opt and normalize \"carries nothing\" to nil.

   The analogue of `normalize-if-match`: one shared rule every adopting
   store runs, so the stores agree on what a caller may pass instead of each
   inventing its own tolerance.

   THE MAP IS OPEN and dromon never reads it. Two top-level keys are
   documented, both optional, both maps, and they are orthogonal axes --
   who authorized the write, and what code performed it:

     {:principal {:credential   \"session\" | \"token\" | \"anonymous\"
                  :altId        <the real human, or a service's client_id>
                  :userId       <acting practitioner uuid, bare string>
                  :actAsAltId   <impersonated username>          ; session only
                  :actAsUserId  <impersonated practitioner uuid> ; session only
                  :subject      <Kratos id>
                  :actAsSubject <Kratos id of the impersonated>}
      :device    {:name      \"purser\"                  ; required inside :device
                  :versions  {\"purser\" \"<git sha>\" \"rcm-x12\" \"<git sha>\"}
                  :reference \"Device/<id>\"}}           ; optional

   `:principal`'s keys are camelCase on purpose: they are the tails of the
   four `SecurityEvent.participant` attributes the host already stamps on a
   Datomic transaction entity, so an in-store stamp and the host's audit
   trail join with no translation.

   `:device` is DATA, not a FHIR Reference, because nothing mints Device
   resources yet; committing the channel to `Device/<id>` would oblige every
   producer to register one before it could stamp anything. `:versions` is a
   map rather than one sha because which dependency read the input changes
   how the input was interpreted. A `:reference` may be added later without
   changing this contract.

   Returns nil for nil, for an empty map, and for a map whose values are all
   nil -- a stamp that names nobody and nothing is no stamp, and a store
   should not write an empty row for it. Otherwise returns the map ITSELF,
   unchanged and including keys not documented here.

   Throws ex-info `{:fhir/status 500 :fhir/code \"exception\"
   :tx-metadata/problem <keyword>}` for a shape no producer should ever
   build. 500 and not 400: the stamp is assembled by the host from its own
   request context, so a malformed one is a programming error on this side,
   not something a client sent. The exception names the RULE and never
   echoes the value, because the map can carry usernames and practitioner
   ids -- the same reason a stamp must not go into a span's `:data`."
  [m]
  (letfn [(bad! [problem message]
            (throw (ex-info message
                            {:fhir/status 500
                             :fhir/code "exception"
                             :tx-metadata/problem problem})))]
    (cond
      (nil? m) nil

      (not (map? m))
      (bad! :not-a-map ":tx-metadata must be a map")

      (every? nil? (vals m)) nil

      :else
      (let [{:keys [principal device]} m]
        (when (and (some? principal) (not (map? principal)))
          (bad! :principal-not-a-map ":tx-metadata :principal must be a map"))
        (when (some? device)
          (when-not (map? device)
            (bad! :device-not-a-map ":tx-metadata :device must be a map"))
          (when-not (and (string? (:name device))
                         (not (str/blank? (:name device))))
            (bad! :device-name-missing
                  ":tx-metadata :device must carry a non-blank string :name"))
          (when-some [versions (:versions device)]
            (when-not (and (map? versions)
                           (every? string? (keys versions))
                           (every? string? (vals versions)))
              (bad! :device-versions-malformed
                    ":tx-metadata :device :versions must map strings to strings"))))
        m))))

(defprotocol IFHIRStore
  "Store contract for FHIR resource persistence.

   Write-return basis convention: write methods (create-resource,
   update-resource, delete-resource, transact-transaction) SHOULD attach
   the committed transaction's store basis as Clojure metadata on their
   return value:

     {:fhir-store/basis {:tx-id <long> :system-time <instant>}}

   tx-id is monotonically increasing per store node, so a change feed can
   stamp and order frames by it without minting its own counter. Deletes,
   having no resource to return, return an empty map carrying the
   metadata. Backends that cannot supply a basis omit the metadata;
   callers must treat it as optional.

   Opts convention. Every write verb has a plain arity and an arity taking
   a trailing `opts` map, and four rules hold across all of them:

   1. THE PLAIN ARITY IS THE OPTS ARITY WITH nil. An implementation should
      define it by delegating, the way CompartmentFilteringStore already
      does for the tenant verbs, so there is one body per verb.
   2. A DECORATOR FORWARDS THE OPTS ARITY IFF opts IS NON-nil --
      `(if opts (verb base ... opts) (verb base ...))`. A base that predates
      an arity has no such method, so forwarding unconditionally turns every
      plain write through the decorator into an AbstractMethodError. This
      was CompartmentFilteringStore's private habit; it is now the rule.
   3. `:tx-metadata` is THREADED, NEVER READ. dromon does not interpret
      `:principal` or `:device` and has no vocabulary for them: the host
      builds the map, the store persists it, the same division
      `server.narrative` draws between WHAT and WHEN. See
      `check-tx-metadata` for the shape and `ITxMetadataStore` for who
      promises to keep it.
   4. A STAMP MUST NOT REACH A SPAN'S `:data`. The map can carry usernames
      and practitioner ids; log key presence, never values. Same rule as
      `fhir-store.trace`.

   PASSING A STAMP TO AN UNADOPTED IMPLEMENTATION FAILS TWO DIFFERENT WAYS,
   and the difference is the whole reason `ITxMetadataStore` exists.

   `update-resource` and `delete-resource` already had an opts arity before
   this seam, so an implementation that predates it ACCEPTS `:tx-metadata`
   and silently ignores it -- no error, no stamp.

   `create-resource`, `transact-transaction` and `transact-bundle` gained
   their opts arity here, so an implementation that predates it does NOT have
   that arity to call: the invocation throws `AbstractMethodError` at runtime.
   It compiles, it loads, `satisfies?` is true, and it dies on the call. That
   is Clojure's behaviour for an added arity on an inline implementation, and
   it is measured rather than assumed.

   So a caller must not decide by trying. It finds out BEFORE the call, with
   `supports-tx-metadata?` -- see `ITxMetadataStore`."
  (create-resource
    [this tenant-id resource-type id resource]
    [this tenant-id resource-type id resource opts]
    "Create a resource under a caller-assigned logical id.

     `opts` may contain:
     - :tx-metadata — provenance recorded BESIDE the data, in the same
       transaction. See `check-tx-metadata` for the shape, and
       `ITxMetadataStore` for which stores promise to keep it. A store that
       does not implement `ITxMetadataStore` ignores this key.")
  (read-resource [this tenant-id resource-type id])
  (vread-resource [this tenant-id resource-type id vid])
  (update-resource
    [this tenant-id resource-type id resource]
    [this tenant-id resource-type id resource opts]
    "Update (or conditional upsert) a resource. `opts` may contain:
     - :if-match — enforces an atomic optimistic-concurrency check. On
       version mismatch, implementations throw ex-info with
       `{:fhir/status 412 :fhir/code \"conflict\" :expected :actual}`.
       A missing resource combined with :if-match is also a 412.

       Accepted values, all run through `normalize-if-match` by every
       implementation so the stores agree on what a caller may pass:
         - a bare version id, string or integer — `\"7\"`, `7`
         - either ETag spelling — weak `W/\"7\"`, strong `\"7\"`
         - `if-match-any` or `\"*\"` — HTTP `If-Match: *`: guard on the
           resource existing at all, whatever version it holds
         - nil, or the key absent — no precondition, unconditional write

       Anything else is a value no resource can hold, and fails the write
       with a 412 rather than being ignored. An :if-match a store cannot
       make sense of must never degrade into an unconditional write.

     - :tx-metadata — provenance recorded beside the data, in the same
       transaction. See `check-tx-metadata` and `ITxMetadataStore`.")
  (delete-resource
    [this tenant-id resource-type id]
    [this tenant-id resource-type id opts]
    "Delete a resource. `opts` may contain :if-match for optimistic
     concurrency; accepted values and semantics match update-resource.
     It may also contain :tx-metadata, as every write verb does — a delete
     is the write whose provenance is least recoverable from the data, since
     it leaves none.")
  (search [this tenant-id resource-type params search-registry])
  (history [this tenant-id resource-type id])
  (history-type [this tenant-id resource-type params]
    "Returns all versions of all resources of a given type.")
  (count-resources [this tenant-id resource-type params search-registry]
    "Returns the total count of resources matching the search params.")
  (transact-transaction
    [this tenant-id entries]
    [this tenant-id entries opts]
    "Atomic FHIR `transaction` Bundle semantics (HL7 FHIR §3.1.0.11.2):
     all entries succeed or all fail as a single database transaction.
     Any failure propagates as an exception that rolls back the whole
     transaction; there is no per-entry error handling.

     `opts` may contain:
     - :tx-metadata — ONE stamp for the WHOLE Bundle, because the whole
       Bundle is one transaction. There is no per-entry stamp here: a stamp
       names a transaction, and every entry shares this one.")
  (transact-bundle
    [this tenant-id entries]
    [this tenant-id entries opts]
    "FHIR `batch` Bundle semantics: each entry is processed
     independently. Per-entry failures do NOT affect other entries.
     Returns a Bundle of type `batch-response` whose :entry vector
     reports the status of each input entry in the original order.

     `opts` may contain:
     - :tx-metadata — the SAME stamp applied to each entry's own
       transaction. A batch is many transactions, so the map is written
       many times; entries that fail commit nothing and are stamped
       nowhere, which is the correct reading of a stamp: it exists only
       for a write that landed.")
  (resource-deleted? [this tenant-id resource-type id]
    "Returns true if the resource was previously created and then deleted,
     false if it exists or was never created.")
  (create-tenant
    [this tenant-id]
    [this tenant-id opts]
    "Eagerly create whatever backing state a tenant needs: the per-tenant
     XTDB node, the Datomic database and connection, the mock store's
     state entry, etc. Schema transacts should run here so the first
     resource call is pure I/O against an already-warm backend.

     `opts` may contain:
     - :if-exists — one of :error (default), :ignore, :replace.
       :error throws ex-info {:fhir/status 409 :fhir/code \"conflict\"}
       when the tenant already has any state. :ignore is a no-op if
       the tenant already exists. :replace is equivalent to calling
       delete-tenant immediately followed by create-tenant.

     Returns nil. Safe to call concurrently for the same tenant; the
     first caller wins and losers see an atomic no-op.")
  (delete-tenant
    [this tenant-id]
    [this tenant-id opts]
    "Remove all per-tenant state. After this call, reads and searches
     against the tenant behave as if the tenant was never created.
     Implementations must release any OS resources held open for the
     tenant (Datomic connection, XTDB node, file handles, JDBC pool).

     `opts` may contain:
     - :if-absent — one of :error (default), :ignore. :ignore is a
       no-op when the tenant has no state.
     - :close-storage? — boolean, default false. When true and the
       backend has persistent storage (datomic :dev/:peer, XTDB
       `xtdb2-disk`), also drop the underlying database/file storage,
       not just the in-process handle. In-memory backends ignore this.

     Returns nil.")
  (warmup-tenant
    [this tenant-id]
    [this tenant-id opts]
    "Prime caches, classloaders, JIT, and any lazy per-tenant init
     without actually mutating data. Intended to be idempotent and safe
     to call on a tenant that already has data — unlike create-tenant,
     which treats an existing tenant as a conflict by default.

     Implementations should issue a representative no-op query against
     the tenant that exercises the same code path a real request would
     take (e.g. a 0-result search against a known-small resource type).
     This forces the read-side classloader to load the decoder, the
     query engine to plan/cache the query shape, and the backend to
     resolve any lazy per-tenant resources.

     `opts` may contain:
     - :resource-types — a collection of resource type keywords to
       exercise. Default is #{:Patient} which is enough to prime the
       common hot paths.

     Returns nil. Never throws on a missing tenant; instead, creates
     the tenant as a side effect and then runs the warmup. This mirrors
     the current `get-or-create-node` / `ensure-tenant-conn!` laziness
     but makes it explicit and callable at application boot.")
  (current-basis [this tenant-id]
    "Capture the current point-in-time basis (snapshot token) for `tenant-id`
     as a map:

       {:tx-id <long-or-nil> :system-time <java.time.Instant>}

     :system-time is the commit time of the latest transaction visible to the
     tenant (never nil; backends with no committed transactions fall back to
     the wall-clock now). Pass the returned map straight to `scan-type-as-of`
     and `count-as-of` to read a consistent snapshot as of this moment. This is
     the read-side dual of the write-return basis convention: it pins a moment
     WITHOUT producing any resource bytes, so an async export can snapshot at
     kickoff and stream lazily at download time.")
  (scan-type-as-of [this tenant-id resource-type basis]
    "Stream every resource of `resource-type` as of `basis` (a token from
     `current-basis`). Returns a REDUCIBLE (clojure.lang.IReduceInit) — NOT a
     realized collection — that pulls the resource set from the store one
     internal page at a time, holding at most one page in memory. The reduce is
     driven by the consumer: a reducing function that blocks (e.g. writing to a
     backpressured OutputStream) pauses the pull, so peak memory stays bounded
     regardless of type size. Early termination (a `reduced` accumulator) stops
     the scan without fetching further pages.

     No filtering is applied here beyond the snapshot: _type / _since /
     _typeFilter / compartment confinement and id-dedup are the export layer's
     responsibility, applied while consuming the stream. Deleted resources are
     excluded (only live rows as of the basis are streamed).")
  (count-as-of [this tenant-id resource-type basis]
    "Total number of live resources of `resource-type` as of `basis` (a token
     from `current-basis`). Unfiltered snapshot count, intended for manifest
     `output[].count` entries. Cheaper than realizing `scan-type-as-of`; a
     backend that cannot count cheaply may return 0."))

(defn create-store
  "Creates an IFHIRStore implementation. `impl-fn` is a function that takes
   a config map and returns an IFHIRStore instance.

   The config map contains:
   - :resource/schemas  — vector of compiled malli schemas (one per supported
                          resource type, each carrying :resourceType, :fhir/cap-schema,
                          :fhir/interactions, :fhir/search-registry in properties)
   - Implementation-specific keys (e.g., XTDB node config)"
  [impl-fn config]
  (impl-fn config))

;; ---------------------------------------------------------------------------
;; Bitemporal extension protocols.
;;
;; Deliberately separate from IFHIRStore, and deliberately split read from
;; write, so a store implements exactly what its engine can honour and
;; `satisfies?` stays a meaningful capability check:
;;
;;   fhir-store-xtdb2   ITemporalReadStore + IValidTimeStore  (both axes)
;;   fhir-store-datomic ITemporalReadStore only               (system time)
;;   fhir-store-mock    neither
;;
;; A store that implemented a verb it cannot honour -- even to throw -- would
;; make the capability undiscoverable ahead of the call, which is what the
;; server layer needs in order to answer 400 instead of a wrong number.
;; ---------------------------------------------------------------------------

(defprotocol ITemporalReadStore
  "Point-in-time reads across one or both time axes.

   A `basis` is {:system-time <Instant|nil> :valid-time <Instant|LocalDate|nil>}.
   nil on an axis means that axis's default: as-best-known for system time,
   valid-now for valid time. A nil basis is an ordinary current read.

   Implementations MUST reject a basis naming an axis absent from
   `temporal-axes` rather than ignoring it. Silently dropping an axis returns a
   plausible but wrong answer -- which is precisely the failure this surface
   exists to prevent."
  (temporal-axes [this]
    "The set of axes this store can honour. #{:system-time} for engines with a
     single append-only timeline (Datomic); #{:system-time :valid-time} for a
     bitemporal engine (XTDB v2).")
  (read-as-of [this tenant-id resource-type id basis]
    "The single resource as it stood at `basis`, or nil when no version of it
     was live at that point.")
  (search-as-of [this tenant-id resource-type params search-registry basis]
    "As `search`, evaluated against the snapshot `basis` names. The same
     search-registry machinery applies; only the snapshot differs.")
  (count-as-of-basis [this tenant-id resource-type params search-registry basis]
    "As `count-resources`, against the snapshot `basis` names.")
  (resource-timeline [this tenant-id resource-type id opts]
    "Every version of one resource, ordered oldest-first by system time.

     Each row is {:resource <fhir-resource>
                  :system-from <Instant> :system-to <Instant-or-nil>}
     plus :valid-from / :valid-to ONLY when :valid-time is in `temporal-axes`.

     The absence of the valid-time keys means 'this store has no such axis' --
     it does NOT mean [beginning-of-time, end-of-time). Callers rendering a
     timeline must branch on `temporal-axes`, never on key presence alone, and
     must not emit a null valid period for a single-axis store. A nil
     :system-to means 'still current', not 'deleted'."))

(defprotocol IValidTimeStore
  "Writes that place a fact on the valid-time axis: retroactive corrections and
   future-dated changes. Requires a bitemporal engine.

   Two engine behaviours make these verbs necessary rather than conveniences:

   1. Valid-time DML without a portion clause applies FROM NOW ON. A correction
      issued as a plain update is therefore a silent PROSPECTIVE change, not the
      retroactive one the user meant. Every retroactive write must name its
      portion, which is what these verbs enforce.
   2. System-time backfill cannot predate already-indexed transactions. Once a
      tenant is live there is no inserting an older system time; anything
      learned later about the past is a valid-time write recorded now. Plan
      historical import oldest-first, before live traffic."
  (put-valid-time [this tenant-id resource-type id resource vt]
    "Record `resource` as the truth over the valid-time portion
     vt = {:valid-from <t> :valid-to <t-or-nil>}, nil :valid-to meaning
     end-of-time.

     Exact replacement over the portion: elements absent from `resource` are
     absent from the result, never inherited from the version being replaced.
     Still a new system-time version, so versionId advances as for any update.")
  (close-valid-time
    [this tenant-id resource-type id valid-from]
    [this tenant-id resource-type id valid-from valid-to]
    "Retroactive termination over a portion of the valid-time axis, learned now:
     the resource ceases to be true over [valid-from, valid-to), and from
     `valid-to` onward it is again whatever was separately stated for it. This
     is the retro-eligibility primitive.

     `valid-to` is EXCLUSIVE, and nil means end-of-time: the five-argument form
     IS the six-argument form with nil. The axis is half-open throughout --
     `put-valid-time`'s vt bounds and the timeline's bounds are exclusive too --
     so a `valid-to` equal to a later portion's valid-from leaves that portion
     untouched, its system time included, while a `valid-to` falling inside a
     portion SPLITS it: what lies before `valid-from` stands, [valid-from,
     valid-to) is retracted, and what lies from `valid-to` on stands.

     The portion is a cut, not a lookup. Neither bound need coincide with an
     existing boundary, and a portion that overlaps nothing writes nothing and
     is not an error. `valid-to` must be later than `valid-from`: an empty or
     inverted portion is REFUSED, with nothing written, which is neither a
     no-op nor a close to end-of-time. Both bounds are Instants, and a FHIR
     Period.end is inclusive, so the caller converts it, to the start of the
     following day, before calling. `valid-from` is required: a nil there reads
     as beginning-of-time and erases the whole run.

     History is preserved: reads at an earlier system time still see the
     retracted portion live, and reads valid-outside the portion still find
     it."))

;; ---------------------------------------------------------------------------
;; Full-text search extension protocol.
;;
;; Same reasoning as the bitemporal split above: `_text` is answered by a
;; full-text index, not by the resource store's own query engine, and only a
;; store that fronts such an index can honour it. Keeping the verb off
;; IFHIRStore means a store implements it exactly when it can answer, so
;; `satisfies?` is a capability check the handler makes BEFORE the call and
;; turns into a 400 rather than a wrong number. Were `_text` instead let
;; through to every store's `search`, a store without an index would ignore
;; it and run an unfiltered search under a filtered request's parameters,
;; which is the failure the whole unsupported-parameter surface exists to
;; prevent.
;;
;;   flotilla's IndexedStore  ITextSearchStore, per realm and per type
;;   fhir-store-datomic       not implemented
;;   fhir-store-xtdb2         not implemented
;;   fhir-store-mock          not implemented
;;   CompartmentFilteringStore (fhir-server) not implemented: a patient
;;                            token never reaches the index
;; ---------------------------------------------------------------------------

(defprotocol ITextSearchStore
  "The standard `_text` search parameter (Resource-text) served from a
   full-text index the store fronts.

   The protocol is per tenant and per resource type because the index is: a
   realm may have no index configured at all, and one that does indexes only
   the types it was told to. `satisfies?` says the store CAN front an index;
   `text-searchable?` says whether it does for this tenant and type. The
   handler needs both answers ahead of the call, since `_text` never reaches
   the registry (the SearchParameter has no expression) and would otherwise
   be reported as unknown.

   A store answering true takes `_text` through its ordinary `search` and
   `count-resources` in `params`, alongside whatever other parameters it
   chooses to combine it with; the contract for how the value is matched, and
   which other parameters may accompany it, belongs to the implementation."
  (text-searchable? [this tenant-id resource-type]
    "Whether `_text` on `resource-type` is answered by a full-text index here
     for `tenant-id`. `resource-type` is a keyword, as `search` receives it.
     False means the handler refuses the parameter as not-supported; it must
     not mean the store will quietly ignore it."))

;; ---------------------------------------------------------------------------
;; Transaction-metadata extension protocol.
;;
;; Same reasoning as the two splits above, and the same failure it prevents.
;; Every write verb ACCEPTS `:tx-metadata` in its opts map, so `satisfies?
;; IFHIRStore` is true of a store that takes the stamp and drops it on the
;; floor. Without a separate capability, provenance would go missing exactly
;; where it is load-bearing -- an unattributed clinical write looks identical
;; to an attributed one -- and nothing would say so. Implementing this
;; protocol is the promise to keep the stamp; a store that cannot keep it
;; MUST NOT implement it, and may still take the opts arity to honour
;; `:if-match`.
;;
;;   fhir-store-xtdb2   planned: a per-tenant fhir_tx_metadata row written
;;                      inside the same xt/execute-tx as the data
;;   fhir-store-datomic planned: the transaction entity every write already
;;                      names, which is also the value of :fhir/version-id
;;   fhir-store-mock    planned: the per-version history record
;;   decorators         CompartmentFilteringStore (dromon) and IndexedStore
;;                      (flotilla) by delegation to their base
;;   as-of-store, test stubs  not implemented -- they persist nothing
;; ---------------------------------------------------------------------------

(defprotocol ITxMetadataStore
  "Provenance persisted BESIDE the data: the `:tx-metadata` map handed to a
   write verb is committed in the SAME transaction as the resource it
   describes.

   Same-transaction is the whole promise. A stamp written afterwards can
   name a write that never committed, and a stamp written before can be
   orphaned by a rollback; either way the record is evidence of something
   that did not happen, which is worse than no record. An implementation
   that cannot write the stamp atomically with the data must not implement
   this protocol.

   The log is not retired by this. A stamp exists only for a write that
   landed, so refusals, precondition failures and thrown writes remain the
   audit log's business; the two are complementary, not redundant."
  (tx-metadata-supported? [this]
    "Whether a stamp handed to this store's write verbs is actually kept.

     Separate from `satisfies?` because a DECORATOR cannot answer
     statically: it implements this protocol in its own source, but whether
     the stamp survives depends on the base it was handed at runtime. A
     decorator answers by delegating to its base. Prefer
     `supports-tx-metadata?`, which asks both questions in the right order.")
  (tx-metadata-of [this tenant-id resource-type id vid]
    "The stamp the named committed version was written under, or nil when
     that version carried none or does not exist.

     A read verb, so that \"beside the data\" is observable rather than a
     write-only claim: without it no test and no operator can tell a store
     that keeps stamps from one that accepts and discards them."))

(defn supports-tx-metadata?
  "Whether `store` will keep a `:tx-metadata` stamp -- the check a caller
   makes BEFORE deciding to pass one.

   Both halves are needed and the order matters: `satisfies?` alone is not
   enough for a decorator, whose base may keep nothing, and
   `tx-metadata-supported?` alone throws IllegalArgumentException on a store
   that does not implement the protocol at all. Writing that pair out at
   every call site is how one of the halves eventually goes missing."
  [store]
  (boolean (and (satisfies? ITxMetadataStore store)
                (tx-metadata-supported? store))))
