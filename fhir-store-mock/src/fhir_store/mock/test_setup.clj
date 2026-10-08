(ns fhir-store.mock.test-setup
  "Test setup over the mock store, in the store's own terms: tenants, resources,
   searches and versions, not a FHIR client's request maps.

   It replaces the store half of furl's test harness (pyr's mock client):

   | furl / pyr                              | here                                   |
   |-----------------------------------------|----------------------------------------|
   | one base URL per dataset                | one tenant per base: `ensure-tenant!`, `reset-tenant!` |
   | `put-from-json-file`, `add-to-directory`| `seed!`, `seed-bundle!`, `seed-files!` |
   | `rest-swap`                             | `swap-resource!`                       |
   | hand-registered search answers          | the matcher; `pin-search!` only where it cannot evaluate a search or a fixture disagrees; `strict-search!` refuses what it cannot evaluate, as pyr's \"search not provided by mock\" did |
   | random-id service seeded, fixed time    | `seeded-ids`, `fixed-clock`, `set-clock!`, `advance-clock!` |
   | the client's `_history` of writes       | `writes`, `reads`, `searches`, `clear-log!` |
   | fresh client per test                   | `snapshot`, `restore!`, `with-snapshot` |

   Everything lives on the store (its :state, :harness, counters, clock and
   id-fn), so two stores never share setup. A store built by
   `fhir-store.mock.core/create-mock-store` and never touched here behaves
   exactly as one built before this namespace existed."
  (:require [clojure.java.io :as io]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as protocol]
            [jsonista.core :as json])
  (:import (java.nio.charset StandardCharsets)
           (java.time Duration Instant)
           (java.util UUID)))

;; ---------------------------------------------------------------------------
;; Clock and ids
;; ---------------------------------------------------------------------------

(deftype FixedClock [now]
  clojure.lang.IFn
  (invoke [_] @now)
  clojure.lang.IDeref
  (deref [_] @now))

(deftype SeededIds [seed counter]
  clojure.lang.IFn
  (invoke [_]
    (str (UUID/nameUUIDFromBytes
          (.getBytes (str seed ":" (swap! counter inc)) StandardCharsets/UTF_8)))))

(def default-instant
  "The instant a `fixed-clock` starts at when given none."
  (Instant/parse "2026-01-01T00:00:00Z"))

(defn- ->instant [x]
  (cond (instance? Instant x) x
        (string? x) (Instant/parse x)
        :else (throw (ex-info "Expected a java.time.Instant or an ISO-8601 instant string"
                              {:value-type (some-> x class .getName)}))))

(defn- ->duration [x]
  (cond (instance? Duration x) x
        (string? x) (Duration/parse x)
        :else (throw (ex-info "Expected a java.time.Duration or an ISO-8601 duration string"
                              {:value-type (some-> x class .getName)}))))

(defn fixed-clock
  "A clock for the store's `:clock` option that stands still until moved with
   `set-clock!` or `advance-clock!`. Starts at `instant` (an Instant or an
   ISO-8601 string), `default-instant` when omitted. Deref it for the time it
   reads."
  ([] (fixed-clock default-instant))
  ([instant] (->FixedClock (atom (->instant instant)))))

(defn- the-clock
  "`x` itself when it is a fixed clock, else the fixed clock of store `x`."
  ^FixedClock [x]
  (let [c (if (instance? FixedClock x) x (:clock x))]
    (if (instance? FixedClock c)
      c
      (throw (ex-info "The store's clock is not a fixed-clock; build it with mock-store or pass :clock (fixed-clock)"
                      {:clock-type (some-> c class .getName)})))))

(defn set-clock!
  "Sets the fixed clock of `store-or-clock` to `instant` (an Instant or an
   ISO-8601 string). Returns the new instant."
  [store-or-clock instant]
  (reset! (.-now (the-clock store-or-clock)) (->instant instant)))

(defn advance-clock!
  "Moves the fixed clock of `store-or-clock` forward by `duration` (a Duration
   or an ISO-8601 duration string such as \"PT5M\"). Returns the new instant."
  [store-or-clock duration]
  (let [d (->duration duration)]
    (swap! (.-now (the-clock store-or-clock)) #(.plus ^Instant % d))))

(defn seeded-ids
  "A deterministic id function for the store's `:id-fn` option: the n-th call
   returns the name-based UUID of \"<seed>:<n>\", so the same seed yields the
   same ids in the same order on every run. The ids are UUID strings, as furl's
   seeded random-id service minted, but not the same UUIDs: furl drew them
   from test.check's generator."
  ([] (seeded-ids 0))
  ([seed] (->SeededIds seed (atom 0))))

;; ---------------------------------------------------------------------------
;; Store and tenants
;; ---------------------------------------------------------------------------

(defn ensure-tenant!
  "Creates `tenant` unless it exists; an existing tenant keeps its resources."
  [store tenant]
  (protocol/create-tenant store tenant {:if-exists :ignore}))

(defn reset-tenant!
  "Empties `tenant`, creating it when absent. Pins and the log are setup, not
   data, and are kept; see `clear-pins!` and `clear-log!`."
  [store tenant]
  (protocol/create-tenant store tenant {:if-exists :replace}))

(defn mock-store
  "A mock store for tests. Options are create-mock-store's, with test
   defaults:

   - :clock, default `(fixed-clock)` at `default-instant`. Pass
     `#(java.time.Instant/now)` for wall-clock time.
   - :id-fn, default `(seeded-ids)`.
   - :registries, {resource-type search-registry} used when a search is
     called with no registry.
   - :record?, record verbs into the log from the start (default false).
   - :strict-search?, as `strict-search!` (default false).
   - :resource/lifecycle, as create-mock-store.
   - :tenants, tenants to create (`ensure-tenant!`) before returning."
  ([] (mock-store {}))
  ([opts]
   (let [store (mock/create-mock-store
                (merge {:clock (fixed-clock) :id-fn (seeded-ids)}
                       (dissoc opts :tenants)))]
     (doseq [tenant (:tenants opts)]
       (ensure-tenant! store tenant))
     store)))

;; ---------------------------------------------------------------------------
;; Seeding
;; ---------------------------------------------------------------------------

(defn- invalid! [message data]
  (throw (ex-info message (merge {:fhir/status 400 :fhir/code "invalid"} data))))

(defn- check-seedable!
  "`resource` names its type and id. `where` locates it in the input, never by
   content."
  [resource where]
  (when-not (map? resource)
    (invalid! "Seed input is not a resource map" where))
  (when-not (seq (some-> (:resourceType resource) name))
    (invalid! "Seed resource has no resourceType" where))
  (when-not (and (string? (:id resource)) (seq (:id resource)))
    (invalid! "Seed resource has no id"
              (assoc where :resource-type (name (:resourceType resource)))))
  resource)

(defn seed!
  "Upserts each resource under its own id and type (`:resourceType`, as a
   keyword type key) and returns the stored resources in input order.
   `resources` is a collection of resource maps, or one map.

   Every resource is checked before any is written: one without a
   resourceType or an id refuses the whole call with a 400 \"invalid\" naming
   its index. Writes are plain updates, so they pass through the store's
   lifecycle, are versioned like any write, and are recorded in the log when
   recording is on (call `clear-log!` after setup).

   Options:
   - :transform, (fn [resource]) applied to each resource before the check,
     e.g. to assign an id or strip meta."
  ([store tenant resources] (seed! store tenant resources nil))
  ([store tenant resources {:keys [transform]}]
   (let [transform (or transform identity)
         prepared (into []
                        (map-indexed (fn [i r] (check-seedable! (transform r) {:index i})))
                        (if (map? resources) [resources] resources))]
     (mapv (fn [r]
             (protocol/update-resource store tenant (keyword (name (:resourceType r))) (:id r) r))
           prepared))))

(defn seed-bundle!
  "Applies `bundle` as one transaction: every entry lands or none does.
   Entries with a `request` are kept as written. Entries without one (a
   collection or searchset Bundle) become a PUT of the entry's resource under
   its own type and id, so they need both (400 otherwise). Returns the
   transaction-response Bundle; its entries are in the store's processing
   order (DELETE, POST, PUT, GET), not the input's.

   Options:
   - :transform, (fn [resource]) applied to each entry's resource first."
  ([store tenant bundle] (seed-bundle! store tenant bundle nil))
  ([store tenant bundle {:keys [transform]}]
   (when-not (= "Bundle" (some-> (:resourceType bundle) name))
     (invalid! "seed-bundle! needs a Bundle" {:resource-type (some-> (:resourceType bundle) name)}))
   (let [transform (or transform identity)
         entries (into []
                       (map-indexed
                        (fn [i entry]
                          (let [resource (some-> (:resource entry) transform)]
                            (if (:request entry)
                              (cond-> entry resource (assoc :resource resource))
                              (let [r (check-seedable! resource {:index i})]
                                {:resource r
                                 :request {:method "PUT"
                                           :url (str (name (:resourceType r)) "/" (:id r))}})))))
                       (:entry bundle))]
     (protocol/transact-transaction store tenant entries))))

(def ^:private json-mapper
  ;; FHIR decimals keep their written precision (1.50 is not 1.5).
  (json/object-mapper {:decode-key-fn true :bigdecimals true}))

(defn read-json-file
  "Reads one FHIR JSON file (a path string, File, URL or resource) into maps
   with keyword keys; decimals are BigDecimals."
  [source]
  (with-open [r (io/reader source)]
    (json/read-value r json-mapper)))

(defn seed-files!
  "Reads FHIR JSON fixture files and `seed!`s what they hold, in file order.
   A file holds one resource, or a JSON array of resources. A Bundle in a
   file is stored as a Bundle; use `seed-bundle!` on `read-json-file`'s value
   to apply one as a transaction. `sources` is a collection, or one source.

   Options:
   - :transform, as `seed!`.
   - :read-json, (fn [source]) -> parsed value, default `read-json-file`."
  ([store tenant sources] (seed-files! store tenant sources nil))
  ([store tenant sources {:keys [transform read-json] :or {read-json read-json-file}}]
   (seed! store tenant
          (into []
                (mapcat (fn [source]
                          (let [v (read-json source)]
                            (if (sequential? v) v [v]))))
                (if (coll? sources) sources [sources]))
          {:transform transform})))

(defn swap-resource!
  "Reads `resource-type`/`id`, applies `(apply f resource args)`, and updates
   under If-Match of the version read, furl's `rest-swap`. Returns the stored
   result. A missing resource is a 404; a write landing between the read and
   the update is the store's 412."
  [store tenant resource-type id f & args]
  (let [current (protocol/read-resource store tenant resource-type id)]
    (when-not current
      (throw (ex-info (str (name resource-type) "/" id " not found")
                      {:fhir/status 404
                       :fhir/code "not-found"
                       :resource-type (name resource-type)
                       :id id})))
    (protocol/update-resource store tenant resource-type id
                              (apply f current args)
                              {:if-match (get-in current [:meta :versionId])})))

;; ---------------------------------------------------------------------------
;; Pinned and strict searches
;; ---------------------------------------------------------------------------

(defn- harness ^clojure.lang.Atom [store]
  (or (:harness store)
      (throw (ex-info "The store has no harness; build it with create-mock-store or mock-store"
                      {}))))

(defn- pin-path [tenant resource-type params]
  [(mock/tenant-key tenant) (mock/type-key resource-type) (mock/search-pin-key params)])

(defn pin-search!
  "Answers every search of `resource-type` in `tenant` whose filter
   parameters equal `params` with exactly the resources `ids` names, in that
   order, instead of evaluating it. Parameters compare as a set of name/value
   pairs (map order and keyword or string names do not matter; a repeated
   parameter's values compare as a set); result parameters (_count, _sort,
   _include, ...) are ignored when matching and still applied to the
   answer. A pinned id that is not stored when the search runs is a 500.

   Pin only what the matcher cannot evaluate, or where a fixture's recorded
   answer disagrees with the stored resources; say which in the test."
  [store tenant resource-type params ids]
  (when-not (every? string? ids)
    (throw (ex-info "Pinned ids must be strings" {:resource-type (name resource-type)})))
  (swap! (harness store) assoc-in [:pins (pin-path tenant resource-type params)] (vec ids))
  nil)

(defn unpin-search!
  "Removes the pin `pin-search!` made for the same tenant, type and params."
  [store tenant resource-type params]
  (swap! (harness store) update :pins dissoc (pin-path tenant resource-type params))
  nil)

(defn clear-pins!
  "Removes every pin, or every pin of `tenant`."
  ([store]
   (swap! (harness store) assoc :pins {})
   nil)
  ([store tenant]
   (let [tid (mock/tenant-key tenant)]
     (swap! (harness store) update :pins
            (fn [pins] (into {} (remove (fn [[[t] _]] (= tid t))) pins)))
     nil)))

(defn strict-search!
  "With `on?` true (the default), a search or count with a filter parameter
   the matcher cannot evaluate throws ex-info {:fhir/status 501 :fhir/code
   \"not-supported\" :param <name> :reason <why>} instead of falling back to
   the resource field of the same name, as pyr's mock refused a search it
   had no answer for. A pinned search is never refused.

   Not evaluated: a parameter with no descriptor in the registry (the call's,
   else the store's `:registries`), chained and `_has` parameters, composite
   and special types, a modifier the type's matcher ignores (`:text`,
   `:not`, `:above`, `:in`, ...), and a quantity with a system or unit."
  ([store] (strict-search! store true))
  ([store on?]
   (swap! (harness store) assoc :strict? (boolean on?))
   nil))

;; ---------------------------------------------------------------------------
;; Recording
;; ---------------------------------------------------------------------------

(defn recording!
  "Turns recording on (default) or off. Entries already logged are kept."
  ([store] (recording! store true))
  ([store on?]
   (swap! (harness store) assoc :record? (boolean on?))
   nil))

(defn log
  "Every recorded entry, oldest first. An entry is a map with :op, :tenant
   and :type, plus by op:

   - :create :update :delete -- :id, :version-id (of the version written; nil
     for a delete that wrote nothing), :opts (the opts keys present, e.g.
     #{:if-match :tx-metadata}, never their values); a delete adds :written?
   - :read :vread -- :id, :found?; a vread adds :version-id
   - :history -- :id, :count; :history-type -- :count
   - :search -- :params (parameter names, never values), :pinned?, :ids
   - :count -- :params, :pinned?, :count

   Entries never hold resource content, parameter values or stamps, the rule
   fhir-store.trace keeps for spans. A transaction Bundle's entries are
   logged once it commits, and not at all when it rolls back."
  [store]
  (:log @(harness store)))

(defn- log-of [store ops]
  (filterv #(contains? ops (:op %)) (log store)))

(defn writes
  "Recorded :create, :update and :delete entries, oldest first."
  [store]
  (log-of store #{:create :update :delete}))

(defn reads
  "Recorded :read, :vread, :history and :history-type entries, oldest first."
  [store]
  (log-of store #{:read :vread :history :history-type}))

(defn searches
  "Recorded :search and :count entries, oldest first."
  [store]
  (log-of store #{:search :count}))

(defn clear-log!
  "Empties the log; recording stays as it was."
  [store]
  (swap! (harness store) assoc :log [])
  nil)

;; ---------------------------------------------------------------------------
;; Snapshots
;; ---------------------------------------------------------------------------

(defn snapshot
  "The store's whole test state as a value: resources of every tenant, the
   harness (pins, strict mode, recording, log), the basis and write counters,
   and the position of a `fixed-clock` and of `seeded-ids` when the store
   uses them, so a restored store mints the same ids and times again."
  [store]
  {:state @(:state store)
   :harness (some-> (:harness store) deref)
   :basis-counter (some-> (:basis-counter store) deref)
   :write-counter (some-> (:write-counter store) deref)
   :clock (when (instance? FixedClock (:clock store)) @(:clock store))
   :ids (let [ids (:id-fn store)]
          (when (instance? SeededIds ids) @(.-counter ^SeededIds ids)))})

(defn restore!
  "Puts the store back to `snap`, a `snapshot` of the same store. Not atomic
   across its parts: restore while no other thread uses the store."
  [store snap]
  (reset! (:state store) (:state snap))
  (when-let [h (:harness store)]
    (when-let [v (:harness snap)] (reset! h v)))
  (when-let [c (:basis-counter store)]
    (when-let [v (:basis-counter snap)] (reset! c v)))
  (when-let [c (:write-counter store)]
    (when-let [v (:write-counter snap)] (reset! c v)))
  (when-let [v (:clock snap)]
    (set-clock! store v))
  (when-let [v (:ids snap)]
    (reset! (.-counter ^SeededIds (:id-fn store)) v))
  nil)

(defn with-snapshot
  "Runs `f` and restores the store to its state before, however `f` ends.
   With only `store`, returns a clojure.test fixture:
   `(use-fixtures :each (with-snapshot store))`."
  ([store] (fn [f] (with-snapshot store f)))
  ([store f]
   (let [snap (snapshot store)]
     (try (f)
          (finally (restore! store snap))))))
