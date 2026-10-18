(ns fhir-search-bench.writes
  "Write and transaction benchmark over the IFHIRStore protocol, the write-side
   counterpart of `fhir-search-bench.bench`: same store construction
   (`:store-fn` for a store outside dromon), same Synthea dataset, same
   in-process measurement with no HTTP or auth.

     clojure -X:xtdb fhir-search-bench.writes/run :backend :xtdb2

   writes target/writes-xtdb2.edn; `report` compares two result files into
   target/WRITE-REPORT.md.

   Each run preloads the dataset untimed, so writes land in a populated
   database, then runs every workload `:repeats` times. Write inputs come from
   Synthea bundles the preload did not read. Single-resource writes reference
   anchor Patients created during setup, so no store has to resolve a
   reference it does not hold (fhir-store-datomic refuses a dangling one with
   a 422 where xtdb2 keeps it as a string).

   Workloads:
   - single     create, update, update with :if-match, delete; one thread
   - concurrent create and update at each `:concurrency` level, distinct ids
   - tx-size    transact-transaction (atomic) and transact-bundle (batch) at
                each `:tx-sizes` entry count, Synthea entries with their
                urn:uuid references kept
   - mixed      one transaction: POST an Encounter and Observations that
                reference it by urn:uuid, PUT (with ifMatch) and DELETE
                existing Observations
   - rollback   a transaction whose last entry carries a stale ifMatch; times
                the failure and checks that nothing it held was committed
   - contention threads read-then-update one resource with :if-match; counts
                successes and 412s and checks that no update was lost

   Checks recorded beside the timings: a write is readable as soon as the call
   returns, a sample of written resources reads back with the fields that were
   written, rollback leaves nothing behind, and contention loses no update."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [taoensso.telemere :as tel]
            [fhir-store.protocol :as db]
            [fhir-search-bench.bench :as bench]
            [fhir-search-bench.dataset :as dataset])
  (:import (java.util.concurrent Callable CountDownLatch Executors Future TimeUnit)
           (java.util.concurrent.atomic AtomicLong)))

(def ^:private tenant bench/tenant)

(def ^:private ^:dynamic *create-opts*
  "Opts for the timed creates. The benchmark's ids are fresh and unique, so by
   default they are passed as :id-minted?, as fhir-server does for a POST: a
   store may skip its existence check. :minted-creates? false measures
   caller-assigned ids instead."
  {:id-minted? true})

(defn- create! [store rt id body]
  (if *create-opts*
    (db/create-resource store tenant rt id body *create-opts*)
    (db/create-resource store tenant rt id body)))

(defn- log [& args] (apply println "[writes]" args))
(defn- now-ns ^long [] (System/nanoTime))
(defn- ->ms [ns] (/ (double ns) 1e6))

;; ── Measurement ─────────────────────────────────────────────────────────────

(defn- percentile [sorted p]
  (when (seq sorted)
    (nth sorted (min (dec (count sorted))
                     (int (Math/floor (* p (count sorted))))))))

(defn- error-key
  "A short, content-free label for a failed op: the FHIR status when the store
   gave one, else the exception class."
  [^Throwable e]
  (if-let [status (:fhir/status (ex-data e))]
    (str status)
    (.getSimpleName (class e))))

(defn- summarize
  "Latency and throughput for one batch of ops. `samples` is a seq of
   {:ns <elapsed> :error <key or nil>}; `wall-ns` the batch's wall-clock time.
   Failed ops stay in the latency numbers and are counted under :errors."
  [samples wall-ns]
  (let [lat    (vec (sort (map :ns samples)))
        errors (frequencies (keep :error samples))
        n      (count samples)
        secs   (/ (double wall-ns) 1e9)]
    {:n           n
     :errors      (reduce + 0 (vals errors))
     :error-kinds errors
     :p50-ms      (some-> (percentile lat 0.50) ->ms)
     :p95-ms      (some-> (percentile lat 0.95) ->ms)
     :p99-ms      (some-> (percentile lat 0.99) ->ms)
     :max-ms      (some-> (peek lat) ->ms)
     :mean-ms     (when (pos? n) (->ms (/ (reduce + lat) n)))
     :wall-ms     (->ms wall-ns)
     :ops-per-sec (when (pos? secs) (/ (- n (reduce + 0 (vals errors))) secs))}))

(defn- timed
  "Run `f`, returning {:ns :error :value}. Exceptions are caught and labelled."
  [f]
  (let [t0 (now-ns)]
    (try
      (let [v (f)]
        {:ns (- (now-ns) t0) :value v})
      (catch Throwable e
        {:ns (- (now-ns) t0) :error (error-key e) :ex e}))))

(defn- run-ops
  "Run `thunks` on `threads` threads, each op timed. Returns
   {:summary .. :results [..]} with results in thunk order."
  [threads thunks]
  (let [pool (Executors/newFixedThreadPool (int threads))
        t0   (now-ns)]
    (try
      (let [futs    (mapv (fn [f] (.submit pool ^Callable (fn [] (timed f)))) thunks)
            results (mapv #(.get ^Future %) futs)
            wall    (- (now-ns) t0)]
        {:summary (summarize results wall) :results results})
      (finally
        (.shutdown pool)
        (.awaitTermination pool 1 TimeUnit/MINUTES)))))

;; ── Inputs ──────────────────────────────────────────────────────────────────

(def ^:private observation-keys
  "Observation fields kept for single-resource writes. Everything that holds a
   reference is dropped and :subject set to an anchor Patient, so both stores
   take the same, fully resolvable body."
  [:resourceType :status :category :code :effectiveDateTime :issued
   :valueQuantity :valueCodeableConcept :valueString :component])

(defn- input-observations [entries]
  (->> entries
       (map :resource)
       (filter #(= "Observation" (:resourceType %)))
       (map #(select-keys % observation-keys))))

(defn- input-encounters [entries]
  (->> entries
       (map :resource)
       (filter #(= "Encounter" (:resourceType %)))
       (map #(select-keys % [:resourceType :status :class :type :period]))))

(defn- anchor-ref [anchors i]
  {:reference (str "Patient/" (nth anchors (mod i (count anchors))))})

(defn- obs-body [obs anchors i id]
  (-> obs
      (assoc :id id :subject (anchor-ref anchors i))))

(defn- bump
  "A content change every store must write as a new version: the status flips
   and the quantity (or a note-free issued instant) moves."
  [res k]
  (cond-> (assoc res :status (if (= "final" (:status res)) "amended" "final"))
    (get-in res [:valueQuantity :value])
    (update-in [:valueQuantity :value] #(+ (double %) 1.0 (* 0.001 k)))
    (not (get-in res [:valueQuantity :value]))
    (assoc :issued (str "2026-01-01T00:00:" (format "%02d" (mod k 60)) ".000Z"))))

(defn- vid-of [res] (some-> (get-in res [:meta :versionId]) str))

(defn- input-stream
  "An endless supply of write inputs, cycling through the pool."
  [coll]
  (let [v (vec coll)]
    (when (empty? v) (throw (ex-info "No write inputs of this kind in the input bundles" {})))
    (let [i (AtomicLong. 0)]
      (fn [] (nth v (mod (.getAndIncrement i) (count v)))))))

;; ── Checks ──────────────────────────────────────────────────────────────────

(defn- comparable
  "The written fields a read-back must preserve, numbers as doubles so a store
   that answers BigDecimal for a decimal still compares equal."
  [res]
  (let [norm (fn norm [x]
               (cond (number? x) (double x)
                     (map? x)    (into {} (map (fn [[k v]] [k (norm v)])) x)
                     (sequential? x) (mapv norm x)
                     :else x))]
    ;; subject by its reference alone: fhir-store-datomic adds the Reference's
    ;; optional `type` on read, which is not a change to what was written.
    (norm (cond-> (select-keys res [:status :code :valueQuantity :category :effectiveDateTime
                                    :name :birthDate :gender :address :telecom :identifier :extension])
            (:subject res) (assoc :subject (select-keys (:subject res) [:reference]))))))

(defn- readback-check
  "Read back `pairs` of [id written-body]; counts those whose fields differ."
  [store pairs]
  (let [diffs (keep (fn [[id written]]
                      (let [got (db/read-resource store tenant :Observation id)]
                        (when (not= (comparable written) (comparable got))
                          {:id id :written (comparable written) :read (comparable got)})))
                    pairs)]
    {:checked (count pairs) :mismatched (count diffs) :first-mismatch (first diffs)}))

(defn- visible-check
  "Whether a read issued right after each kind of write returns its result."
  [store anchors obs n]
  (let [ids (mapv #(str "wb-vis-" %) (range n))
        results
        (for [[i id] (map-indexed vector ids)]
          (let [body    (obs-body (obs) anchors i id)
                created (db/create-resource store tenant :Observation id body)
                c-ok    (= (vid-of created) (vid-of (db/read-resource store tenant :Observation id)))
                updated (db/update-resource store tenant :Observation id (bump body i))
                u-ok    (= (vid-of updated) (vid-of (db/read-resource store tenant :Observation id)))
                _       (db/delete-resource store tenant :Observation id)
                d-ok    (nil? (db/read-resource store tenant :Observation id))]
            {:create c-ok :update u-ok :delete d-ok}))]
    (into {} (map (fn [k] [k (every? k results)])) [:create :update :delete])))

;; ── Workloads ───────────────────────────────────────────────────────────────

(defn- single-writes
  "create -> update -> update with :if-match -> delete over `n` ids, one thread,
   after `warmup` discarded rounds of the same."
  [store anchors obs rep n warmup]
  (let [round
        (fn [tag cnt]
          (let [ids     (mapv #(str "wb-s-" rep "-" tag "-" %) (range cnt))
                bodies  (mapv #(obs-body (obs) anchors %1 %2) (range cnt) ids)
                create  (run-ops 1 (mapv (fn [id b] #(create! store :Observation id b)) ids bodies))
                bumped  (mapv bump bodies (range cnt))
                update  (run-ops 1 (mapv (fn [id b] #(db/update-resource store tenant :Observation id b)) ids bumped))
                vids    (mapv #(vid-of (:value %)) (:results update))
                bumped2 (mapv bump bumped (range cnt))
                upd-if  (run-ops 1 (mapv (fn [id b v] #(db/update-resource store tenant :Observation id b {:if-match v}))
                                         ids bumped2 vids))
                check   (readback-check store (take 20 (map vector ids bumped2)))
                delete  (run-ops 1 (mapv (fn [id] #(db/delete-resource store tenant :Observation id)) ids))]
            {:create (:summary create) :update (:summary update)
             :update-if-match (:summary upd-if) :delete (:summary delete)
             :readback check}))]
    (round "warm" warmup)
    (round "m" n)))

(defn- concurrent-writes
  "Create then update `n` distinct Observations at each concurrency level."
  [store anchors obs rep n levels]
  (into {}
        (for [c levels]
          (let [ids    (mapv #(str "wb-c-" rep "-" c "-" %) (range n))
                bodies (mapv #(obs-body (obs) anchors %1 %2) (range n) ids)
                create (run-ops c (mapv (fn [id b] #(create! store :Observation id b)) ids bodies))
                update (run-ops c (mapv (fn [id b k] #(db/update-resource store tenant :Observation id (bump b k)))
                                        ids bodies (range n)))]
            [c {:create (:summary create) :update (:summary update)}]))))

(defn- tx-readback
  "From a transaction-response, read back up to 10 created resources and count
   those that are missing or whose code differs from what was sent, plus
   urn:uuid references left unresolved in what was read."
  [store entries resp]
  (let [pairs (->> (map vector entries (:entry resp))
                   (keep (fn [[e r]]
                           (let [rt (get-in e [:resource :resourceType])
                                 id (get-in r [:resource :id])]
                             (when (and id (get-in e [:resource :code]))
                               [rt id (get-in e [:resource :code])]))))
                   (take 10))
        reads (mapv (fn [[rt id code]]
                      (let [got (db/read-resource store tenant (keyword rt) id)]
                        {:missing  (nil? got)
                         :code-ok  (= code (:code got))
                         :urn-refs (count (re-seq #"urn:uuid:" (pr-str got)))}))
                    pairs)]
    {:checked    (count reads)
     :missing    (count (filter :missing reads))
     :mismatched (count (remove :code-ok reads))
     :urn-refs   (reduce + 0 (map :urn-refs reads))}))

(defn- close-refs
  "Drops every urn:uuid reference in `chunk` that names no entry of the chunk.
   A slice of a Synthea bundle cuts some intra-patient links; fhir-store-datomic
   refuses such a reference with a 422 where xtdb2 keeps it as a string, so
   both stores get only references the transaction itself resolves."
  [chunk]
  (let [urls     (into #{} (keep :fullUrl) chunk)
        dangling? (fn [x] (and (map? x)
                               (let [r (:reference x)]
                                 (and (string? r) (str/starts-with? r "urn:uuid:")
                                      (not (contains? urls r))))))
        fix      (fn [x]
                   (if (map? x)
                     (into (empty x)
                           (keep (fn [[k v]]
                                   (cond (dangling? v) nil
                                         (vector? v) (let [v2 (into [] (remove dangling?) v)]
                                                       (when (seq v2) [k v2]))
                                         :else [k v])))
                           x)
                     x))]
    (mapv #(update % :resource (fn [r] (walk/postwalk fix r))) chunk)))

(defn- tx-size-sweep
  "transact-transaction and transact-bundle at each size, `iters` per size,
   each transaction a fresh slice of Synthea entries."
  [store next-entry sizes iters-for]
  (into {}
        (for [s sizes
              mode [:transaction :batch]]
          (let [iters   (iters-for s)
                ;; A batch resolves no reference between its entries, so its
                ;; slices carry none: without fullUrls every urn:uuid dangles.
                chunks  (vec (repeatedly iters #(close-refs (cond->> (vec (repeatedly s next-entry))
                                                              (= mode :batch) (mapv (fn [e] (dissoc e :fullUrl)))))))
                verb    (if (= mode :transaction) db/transact-transaction db/transact-bundle)
                out     (run-ops 1 (mapv (fn [ch] #(verb store tenant ch)) chunks))
                summary (:summary out)
                secs    (/ (:wall-ms summary) 1e3)
                check   (when-let [r (first (keep :value (:results out)))]
                          (tx-readback store (first chunks) r))]
            [[mode s] (assoc summary
                             :entries-per-sec (when (pos? secs) (/ (* s (- iters (:errors summary))) secs))
                             :readback check)]))))

(defn- mixed-tx
  "One transaction per round: POST an Encounter and `k` Observations
   referencing it by urn:uuid, PUT an existing Observation with ifMatch and
   DELETE another."
  [store anchors obs enc rep rounds k]
  (let [targets (vec (for [i (range rounds)]
                       (let [put-id (str "wb-mx-" rep "-p-" i)
                             del-id (str "wb-mx-" rep "-d-" i)
                             body   (obs-body (obs) anchors i put-id)
                             put    (db/create-resource store tenant :Observation put-id body)]
                         (db/create-resource store tenant :Observation del-id (obs-body (obs) anchors i del-id))
                         {:put-id put-id :del-id del-id :body body :vid (vid-of put)})))
        bundles (vec (for [[i {:keys [put-id del-id body vid]}] (map-indexed vector targets)]
                       (let [enc-url (str "urn:uuid:" (random-uuid))
                             pt      (anchor-ref anchors i)]
                         (-> [{:fullUrl enc-url
                               :request {:method "POST" :url "Encounter"}
                               :resource (assoc (enc) :subject pt)}]
                             (into (for [j (range k)]
                                     {:fullUrl (str "urn:uuid:" (random-uuid))
                                      :request {:method "POST" :url "Observation"}
                                      :resource (assoc (obs) :subject pt :encounter {:reference enc-url})}))
                             (conj {:fullUrl (str "Observation/" put-id)
                                    :request {:method "PUT" :url (str "Observation/" put-id)
                                              :ifMatch (str "W/\"" vid "\"")}
                                    :resource (bump body i)}
                                   {:request {:method "DELETE" :url (str "Observation/" del-id)}})))))
        out     (run-ops 1 (mapv (fn [b] #(db/transact-transaction store tenant b)) bundles))
        sample  (take 10 targets)
        put-ok  (count (filter true?
                               (map (fn [{:keys [put-id body]} i]
                                      (= (comparable (bump body i))
                                         (comparable (db/read-resource store tenant :Observation put-id))))
                                    sample (range))))
        del-ok  (count (filter #(nil? (db/read-resource store tenant :Observation (:del-id %))) sample))
        ;; Found by type rather than position, so the check holds whatever
        ;; order the store answers in; response-order-ok checks that order.
        enc-ok  (count (keep (fn [r]
                               (let [entries (:entry (:value r))
                                     of-type (fn [t] (keep #(when (and (= t (get-in % [:resource :resourceType]))
                                                                       (str/starts-with? (str (get-in % [:response :status])) "201"))
                                                              (get-in % [:resource :id]))
                                                           entries))
                                     enc-id  (first (of-type "Encounter"))
                                     obs-id  (first (of-type "Observation"))
                                     o       (when obs-id (db/read-resource store tenant :Observation obs-id))]
                                 (when (and enc-id (= (str "Encounter/" enc-id) (get-in o [:encounter :reference])))
                                   true)))
                             (take 10 (:results out))))
        kind    (fn [req-or-resp]
                  (if (or (= "DELETE" (get-in req-or-resp [:request :method]))
                          (str/starts-with? (str (get-in req-or-resp [:response :status])) "204"))
                    :delete
                    (get-in req-or-resp [:resource :resourceType])))
        orders   (map (fn [b r]
                        (when-let [resp (:value r)]
                          {:request (mapv kind b) :response (mapv kind (:entry resp))}))
                      (take 10 bundles) (:results out))
        order-ok (count (filter #(and % (= (:request %) (:response %))) orders))]
    (assoc (:summary out)
           :entries-per-tx (+ k 3)
           :checks {:checked (count sample) :put-applied put-ok :delete-applied del-ok
                    :encounter-ref-resolved enc-ok
                    :response-order-ok order-ok
                    :first-order-diff (first (filter #(and % (not= (:request %) (:response %))) orders))})))

(defn- rollback-tx
  "Transactions of `k` PUTs to fresh ids followed by a PUT with a stale
   ifMatch. Every one must fail and leave nothing behind."
  [store anchors obs rep rounds k]
  (let [rounds-data
        (vec (for [i (range rounds)]
               (let [target (str "wb-rb-" rep "-t-" i)
                     body   (obs-body (obs) anchors i target)
                     vid    (vid-of (db/create-resource store tenant :Observation target body))
                     fresh  (mapv #(str "wb-rb-" rep "-" i "-" %) (range k))]
                 {:target target :body body :vid vid :fresh fresh
                  :bundle (-> (mapv (fn [id] {:fullUrl (str "Observation/" id)
                                              :request {:method "PUT" :url (str "Observation/" id)}
                                              :resource (obs-body (obs) anchors i id)})
                                    fresh)
                              (conj {:fullUrl (str "Observation/" target)
                                     :request {:method "PUT" :url (str "Observation/" target)
                                               :ifMatch (str "W/\"" vid "0\"")}
                                     :resource (bump body i)}))})))
        out     (run-ops 1 (mapv (fn [{:keys [bundle]}] #(db/transact-transaction store tenant bundle)) rounds-data))
        leaked  (reduce + 0 (for [{:keys [fresh]} rounds-data, id fresh]
                              (if (db/read-resource store tenant :Observation id) 1 0)))
        moved   (count (remove (fn [{:keys [target vid]}]
                                 (= vid (vid-of (db/read-resource store tenant :Observation target))))
                               rounds-data))]
    (assoc (:summary out)
           :checks {:expected-failures rounds
                    :failed (:errors (:summary out))
                    :leaked-entries leaked
                    :targets-changed moved})))

(defn- contention
  "`threads` threads each make `attempts` read-then-update attempts with
   :if-match on one Observation. Returns successes, conflicts and whether the
   final version count accounts for every success."
  [store anchors obs rep threads attempts]
  (let [id      (str "wb-ct-" rep)
        _       (db/create-resource store tenant :Observation id (obs-body (obs) anchors 0 id))
        ok      (AtomicLong. 0)
        conflict (AtomicLong. 0)
        other   (AtomicLong. 0)
        start   (CountDownLatch. 1)
        pool    (Executors/newFixedThreadPool (int threads))
        lat     (java.util.concurrent.ConcurrentLinkedQueue.)
        work    (fn [t]
                  (.await start)
                  (dotimes [a attempts]
                    (let [r (timed (fn []
                                     (let [cur (db/read-resource store tenant :Observation id)]
                                       (db/update-resource store tenant :Observation id
                                                           (bump (dissoc cur :meta) (+ (* t 1000) a))
                                                           {:if-match (vid-of cur)}))))]
                      (.add lat r)
                      (cond (nil? (:error r))       (.incrementAndGet ok)
                            (= "412" (:error r))    (.incrementAndGet conflict)
                            :else                   (.incrementAndGet other)))))
        futs    (mapv (fn [t] (.submit pool ^Callable (fn [] (work t)))) (range threads))
        t0      (now-ns)]
    (.countDown start)
    (doseq [f futs] (.get ^Future f))
    (.shutdown pool)
    (let [wall     (- (now-ns) t0)
          versions (count (remove db/deleted-version? (db/history store tenant :Observation id)))]
      (assoc (summarize (vec lat) wall)
             :threads threads
             :succeeded (.get ok)
             :conflicts (.get conflict)
             :other-errors (.get other)
             :versions versions
             :lost-updates (- (inc (.get ok)) versions)))))

(defn- leaves
  "Every leaf of a resource as {path value}, for counting changed fields."
  [x]
  (letfn [(walk [path x]
            (cond (map? x)        (mapcat (fn [[k v]] (walk (conj path k) v)) x)
                  (sequential? x) (mapcat (fn [i v] (walk (conj path i) v)) (range) x)
                  :else           [[path x]]))]
    (into {} (walk [] (dissoc x :id :meta)))))

(defn- change-counts
  "How much a PUT of `b` over `a` changes: leaves and top-level fields that
   differ, out of those either body has."
  [a b]
  (let [la (leaves a) lb (leaves b)
        paths (distinct (concat (keys la) (keys lb)))
        tops  (disj (into (set (keys a)) (keys b)) :id :meta :resourceType)]
    {:leaves-changed (count (filter #(not= (get la %) (get lb %)) paths))
     :leaves-total   (count paths)
     :fields-changed (count (filter #(not= (get a %) (get b %)) tops))
     :fields-total   (count tops)}))

(defn- input-patients
  "Full Synthea Patients (identifiers, extensions, address, telecom, narrative),
   minus the fields that would hold references."
  [entries]
  (->> entries
       (map :resource)
       (filter #(= "Patient" (:resourceType %)))
       (map #(dissoc % :id :meta :generalPractitioner :managingOrganization :link :contact))))

(defn- edit-size
  "PUTs that change one field against PUTs that change most fields, per type.
   `n` resources of each type are created; each round then PUTs every one of
   them once: a one-field round moves a single date leaf (Observation
   effectiveDateTime, Patient birthDate), a most-fields round swaps in the
   content of a different resource of the same type, so bodies stay valid and
   similar in size. Rounds alternate one/most after one discarded pair."
  [store anchors obs patients rep n]
  (let [counter (AtomicLong. 0)
        one-field
        {:Observation (fn [b] (assoc b :effectiveDateTime
                                     (str (.plusSeconds (java.time.Instant/parse "2020-01-01T00:00:00Z")
                                                        (.incrementAndGet counter)))))
         :Patient     (fn [b] (assoc b :birthDate
                                     (str (.plusDays (java.time.LocalDate/of 1950 1 1)
                                                     (.incrementAndGet counter)))))}
        fresh
        {:Observation (fn [i id] (obs-body (obs) anchors i id))
         :Patient     (fn [_ id] (assoc (patients) :id id))}
        compare-keys
        {:Observation [:status :code :category :valueQuantity :effectiveDateTime :subject]
         :Patient     [:name :birthDate :gender :address :telecom :identifier :extension]}]
    (into {}
          (for [rt [:Observation :Patient]]
            (let [ids     (mapv #(str "wb-e-" rep "-" (name rt) "-" %) (range n))
                  current (atom (mapv (fn [i id] ((fresh rt) i id)) (range n) ids))
                  _       (doseq [[id b] (map vector ids @current)]
                            (db/create-resource store tenant rt id b))
                  round   (fn [variant]
                            (let [olds   @current
                                  bodies (mapv (fn [i id old]
                                                 (if (= variant :one-field)
                                                   ((one-field rt) old)
                                                   ((fresh rt) i id)))
                                               (range n) ids olds)
                                  out    (run-ops 1 (mapv (fn [id b] #(db/update-resource store tenant rt id b))
                                                          ids bodies))]
                              (reset! current bodies)
                              {:samples (:results out)
                               :wall    (* 1e6 (:wall-ms (:summary out)))
                               :changes (map change-counts olds bodies)
                               :chars   (map (comp count pr-str) bodies)}))
                  ;; A-B-B-A after a discarded pair, so neither variant always
                  ;; runs first.
                  _       (round :one-field)
                  _       (round :most-fields)
                  rounds  (mapv (fn [v] [v (round v)]) [:one-field :most-fields :most-fields :one-field])
                  merge-v (fn [v]
                            (let [rs   (keep (fn [[v' r]] (when (= v v') r)) rounds)
                                  chs  (mapcat :changes rs)
                                  avg  (fn [xs] (double (/ (reduce + xs) (max 1 (count xs)))))]
                              (assoc (summarize (mapcat :samples rs) (reduce + (map :wall rs)))
                                     :leaves-changed (avg (map :leaves-changed chs))
                                     :leaves-total   (avg (map :leaves-total chs))
                                     :fields-changed (avg (map :fields-changed chs))
                                     :fields-total   (avg (map :fields-total chs))
                                     :body-chars     (avg (mapcat :chars rs)))))
                  one     (merge-v :one-field)
                  most    (merge-v :most-fields)
                  sample  (take 10 (map vector ids @current))
                  norm    (fn [r] (comparable (select-keys r (compare-keys rt))))
                  bad     (count (remove (fn [[id b]] (= (norm b) (norm (db/read-resource store tenant rt id))))
                                         sample))]
              [rt {:one-field   one
                   :most-fields (assoc most :readback {:checked (count sample) :mismatched bad})}])))))

;; ── Driver ──────────────────────────────────────────────────────────────────

(defn- median [xs] (when (seq xs) (nth (vec (sort xs)) (quot (count xs) 2))))

(defn- combine
  "Fold the per-repeat summaries of one workload into medians across repeats,
   keeping each repeat's p50 so the spread is visible."
  [reps]
  (let [nums (fn [k] (keep k reps))]
    (cond-> {:repeats     (count reps)
             :n           (reduce + 0 (nums :n))
             :errors      (reduce + 0 (nums :errors))
             :p50-ms      (median (nums :p50-ms))
             :p95-ms      (median (nums :p95-ms))
             :p99-ms      (median (nums :p99-ms))
             :max-ms      (some->> (seq (nums :max-ms)) (apply max))
             :ops-per-sec (median (nums :ops-per-sec))
             :p50-by-rep  (vec (nums :p50-ms))}
      (seq (nums :entries-per-sec)) (assoc :entries-per-sec (median (nums :entries-per-sec)))
      (seq (nums :fields-changed)) (assoc :fields-changed (median (nums :fields-changed))
                                          :fields-total (median (nums :fields-total))
                                          :leaves-changed (median (nums :leaves-changed))
                                          :leaves-total (median (nums :leaves-total))
                                          :body-chars (median (nums :body-chars)))
      (seq (keep :error-kinds reps)) (assoc :error-kinds (apply merge-with + (keep :error-kinds reps)))
      (seq (nums :checks))   (assoc :checks (vec (nums :checks)))
      (seq (nums :readback)) (assoc :readback (vec (nums :readback)))
      (seq (nums :succeeded)) (assoc :succeeded (vec (nums :succeeded))
                                     :conflicts (vec (nums :conflicts))
                                     :other-errors (vec (nums :other-errors))
                                     :lost-updates (vec (nums :lost-updates))))))

(defn- flatten-rep
  "One repeat's nested results as {workload-key summary}."
  [{:keys [single concurrent tx mixed rollback contention edit]}]
  (into {} (remove (comp nil? val))
  (merge
   (into {} (for [[k v] (dissoc single :readback)]
              [[:single k] (cond-> v (= k :update-if-match) (assoc :readback (:readback single)))]))
   (into {} (for [[c m] concurrent, [k v] m] [[:concurrent k c] v]))
   (into {} (for [[[mode s] v] tx] [[:tx mode s] v]))
   (into {} (for [[rt m] edit, [variant v] m] [[:edit rt variant] v]))
   {[:mixed] mixed [:rollback] rollback [:contention] contention})))

(defn run
  "Benchmark one backend's writes and transactions. Options:
   - :backend       result label, e.g. :xtdb2 (required); names the result file
   - :store-fn      as in fhir-search-bench.bench/run
   - :xtdb-pipelined? pipelined replica-log appends on the built-in xtdb2 store
                    (default true; not with :xtdb-server)
   - :xtdb-server   \"host:port\" of a standalone XTDB server for the xtdb2
                    store (see fhir-search-bench.bench/make-store)
   - :transport     the xtdb2 store's :transport, :pgwire (default), :adbc or
                    :flight-sql (see fhir-search-bench.bench/make-store)
   - :xtdb-flight-sql-port the standalone server's FlightSQL port, for
                    :xtdb-server with :transport :flight-sql
   - :preload       resources preloaded, untimed (default 100000)
   - :n             ops per single-write kind per repeat (default 200)
   - :warmup        discarded ops per single-write kind (default 50)
   - :repeats       repeats of every workload (default 3)
   - :concurrency   thread counts for the concurrent workload (default [1 4 16])
   - :concurrent-n  ops per concurrency level (default 400)
   - :tx-sizes      entries per transaction (default [1 10 100 1000])
   - :mixed-rounds  mixed transactions per repeat (default 50)
   - :rollback-rounds rolled-back transactions per repeat (default 20)
   - :contention-threads / :contention-attempts (default 8 / 25)
   - :edit-n        resources per type in the one-field vs most-fields PUT
                    workload (default 200)
   - :minted-creates? pass :id-minted? on the timed creates, as a POST does
                    (default true; see *create-opts*)
   - :only          workloads to run, a subset of #{:single :edit :concurrent
                    :tx :mixed :rollback :contention} (default all)
   - :synthea-dir   Synthea fhir bundle dir (default synthea-output/fhir)
   - :out           result file (default target/writes-<backend>.edn)"
  [{:keys [backend store-fn xtdb-pipelined? preload n warmup repeats concurrency concurrent-n
           tx-sizes mixed-rounds rollback-rounds contention-threads contention-attempts
           synthea-dir out edit-n only xtdb-server minted-creates? transport xtdb-flight-sql-port]
    :or   {transport :pgwire minted-creates? true edit-n 200 xtdb-pipelined? true preload 100000 n 200 warmup 50 repeats 3 concurrency [1 4 16]
           concurrent-n 400 tx-sizes [1 10 100 1000] mixed-rounds 50 rollback-rounds 20
           contention-threads 8 contention-attempts 25 synthea-dir "synthea-output/fhir"}}]
  (when-not (keyword? backend)
    (throw (ex-info "Pass :backend, the result label (e.g. :xtdb2)" {:backend backend})))
  (tel/set-min-level! :warn)
  (let [base   (dataset/build {:dir synthea-dir :max-resources preload})
        inputs (dataset/build {:dir synthea-dir :max-resources 60000 :skip-files (:files-used base)})
        _      (log "Preload" (:total base) "resources; write inputs" (:total inputs)
                    "from" (count (:bundles inputs)) "further bundle(s)")
        store  (bench/make-store backend store-fn {:xtdb-pipelined? xtdb-pipelined?
                                                   :xtdb-server xtdb-server
                                                   :transport transport
                                                   :xtdb-flight-sql-port xtdb-flight-sql-port})]
    (db/create-tenant store tenant {:if-exists :replace})
    (db/warmup-tenant store tenant)
    (let [load    (bench/load-dataset-pooled! store (:bundles base) (:total base) 2000 4)
          _       (log "Preloaded" (:loaded load) "/" (:total base) "in" (format "%.1f" (:elapsed-ms load)) "ms")
          obs     (input-stream (input-observations (:entries inputs)))
          enc     (input-stream (input-encounters (:entries inputs)))
          tx-in   (input-stream (:entries inputs))
          pts     (input-stream (input-patients (:entries inputs)))
          run?    (if only (set only) (constantly true))
          anchors (vec (for [[i p] (map-indexed vector (take 20 (filter #(= "Patient" (:resourceType %))
                                                                        (map :resource (:entries inputs)))))]
                         (let [id (str "wb-pt-" i)]
                           (db/create-resource store tenant :Patient id
                                               (-> (select-keys p [:resourceType :name :gender :birthDate])
                                                   (assoc :id id)))
                           id)))
          visible (visible-check store anchors obs 20)
          _       (log "Visible on return:" visible)
          iters   (fn [s] (case s 1 100, 10 50, 100 10, 1000 3, (max 1 (quot 1000 s))))
          rep-results
          (binding [*create-opts* (when minted-creates? {:id-minted? true})]
          (vec (for [rep (range repeats)]
                 (do (log "Repeat" (inc rep) "/" repeats)
                     (let [r (cond-> {}
                               (run? :single)     (assoc :single (single-writes store anchors obs rep n warmup))
                               (run? :edit)       (assoc :edit (edit-size store anchors obs pts rep edit-n))
                               (run? :concurrent) (assoc :concurrent (concurrent-writes store anchors obs rep concurrent-n concurrency))
                               (run? :tx)         (assoc :tx (tx-size-sweep store tx-in tx-sizes iters))
                               (run? :mixed)      (assoc :mixed (mixed-tx store anchors obs enc rep mixed-rounds 5))
                               (run? :rollback)   (assoc :rollback (rollback-tx store anchors obs rep rollback-rounds 10))
                               (run? :contention) (assoc :contention (contention store anchors obs rep contention-threads contention-attempts)))]
                       (log "  done:" (str/join ", " (map name (keys r))))
                       r)))))
          flat    (map flatten-rep rep-results)
          workloads (into (sorted-map-by #(compare (str %1) (str %2)))
                          (for [k (keys (first flat))]
                            [k (combine (keep #(get % k) flat))]))
          result  {:backend   backend
                   :options   {:preload (:total base) :n n :warmup warmup :repeats repeats
                               :concurrency concurrency :concurrent-n concurrent-n :tx-sizes tx-sizes
                               :mixed-rounds mixed-rounds :rollback-rounds rollback-rounds
                               :contention-threads contention-threads
                               :contention-attempts contention-attempts
                               :edit-n edit-n :only only :minted-creates? minted-creates?
                               :xtdb-pipelined? (when (and (= :xtdb2 backend) (not xtdb-server))
                                                  xtdb-pipelined?)
                               :xtdb-server xtdb-server
                               :transport (when-not store-fn transport)
                               :xtdb-flight-sql-port xtdb-flight-sql-port}
                   :java      (System/getProperty "java.version")
                   :preload   load
                   :visible-on-return visible
                   :workloads workloads}
          path    (or out (str "target/writes-" (name backend) ".edn"))]
      (.mkdirs (io/file "target"))
      (spit path (with-out-str (pp/pprint result)))
      (log "Wrote" path)
      (try (db/delete-tenant store tenant {:if-absent :ignore :close-storage? true})
           (catch Exception _))
      (shutdown-agents)
      result)))

;; ── Report ──────────────────────────────────────────────────────────────────

(defn- read-result [path]
  (let [f (io/file path)]
    (when (.exists f) (edn/read-string (slurp f)))))

(defn- fmt [x] (if (number? x) (format "%.2f" (double x)) "—"))
(defn- fmt0 [x] (if (number? x) (format "%.0f" (double x)) "—"))

(defn- workload-label [[kind a b]]
  (case kind
    :single     (str "single " (name a))
    :concurrent (str (name a) " x" b " threads")
    :tx         (str (name a) " " b " entries")
    :mixed      "mixed tx (POST enc + 5 obs, PUT ifMatch, DELETE)"
    :rollback   "rollback tx (10 PUT + stale ifMatch)"
    :contention "contention (read + update ifMatch)"
    :edit       (str "PUT " (name a) " " (str/replace (name b) "-" " "))
    (pr-str kind)))

(defn- check-notes
  "Problems a workload's checks found, as short strings."
  [w]
  (concat
   (cond
     (seq (:succeeded w))
     nil ; contention: 412s are the expected outcome, reported in their own section

     (seq (keep :expected-failures (:checks w)))
     (let [expected (keep :expected-failures (:checks w))]
     (when (or (not= (reduce + expected) (:errors w))
               (not= #{"412"} (set (keys (:error-kinds w)))))
       [(str "expected " (reduce + expected) " failures (412), got " (:errors w) " " (pr-str (:error-kinds w)))]))

     (pos? (or (:errors w) 0))
     [(str (:errors w) " errors " (pr-str (:error-kinds w)))])
   (for [r (:readback w) :when (pos? (+ (or (:mismatched r) 0) (or (:missing r) 0)))]
     (str "readback " (:mismatched r) "/" (:checked r) " mismatched"
          (when (:missing r) (str ", " (:missing r) " missing"))))
   (for [c (:checks w)
         :when (or (pos? (or (:leaked-entries c) 0))
                   (pos? (or (:targets-changed c) 0))
                   (some #(and (% c) (< (% c) (:checked c)))
                         [:put-applied :delete-applied :encounter-ref-resolved :response-order-ok]))]
     (str "checks " (pr-str c)))
   (when (some pos? (:lost-updates w))
     [(str "LOST UPDATES " (:lost-updates w))])))

(def ^:private kind-order [:single :edit :concurrent :tx :mixed :rollback :contention])
(def ^:private sub-order {:create 0 :update 1 :update-if-match 2 :delete 3 :transaction 0 :batch 1
                          :one-field 0 :most-fields 1})

(defn- workload-sort-key [[kind a b]]
  [(.indexOf ^java.util.List kind-order kind) (get sub-order a 9) (str a)
   (if (keyword? b) (get sub-order b 9) (or b 0))])

(defn report
  "Compare two write-bench result files into target/WRITE-REPORT.md.
   `:a` defaults to target/writes-xtdb2.edn; `:b` is the other store's.
   `:label-a`/`:label-b` name the columns (default: each file's backend), so a
   before/after comparison of one store reads clearly."
  [{:keys [a b label-a label-b out] :or {a "target/writes-xtdb2.edn"}}]
  (let [ra (read-result a)
        rb (when b (read-result b))
        la (or label-a (some-> ra :backend name) "a")
        lb (or label-b (some-> rb :backend name) "b")
        keys* (sort-by workload-sort-key (distinct (concat (keys (:workloads ra)) (keys (:workloads rb)))))
        sb (StringBuilder.)
        emit (fn [& xs] (.append sb (str (apply str xs) "\n")))]
    (emit "# FHIR Write and Transaction Benchmark: " la (when rb (str " vs " lb)))
    (emit)
    (emit "In-process against IFHIRStore (no HTTP, no auth), after an untimed preload of the Synthea dataset. "
          "Latencies are the median across repeats of each repeat's percentile; failed ops stay in the numbers.")
    (emit)
    (emit "## Setup")
    (emit)
    (doseq [[l r] [[la ra] [lb rb]] :when r]
      (emit "- **" l "**: preload " (get-in r [:preload :loaded]) " resources in "
            (fmt0 (get-in r [:preload :elapsed-ms])) " ms; Java " (:java r)
            (when (some? (get-in r [:options :xtdb-pipelined?]))
              (str "; pipelined replica appends " (get-in r [:options :xtdb-pipelined?])))
            (when-let [srv (get-in r [:options :xtdb-server])]
              (str "; standalone XTDB server " srv " (pipelining set by the server's config)"))
            (when-let [t (get-in r [:options :transport])]
              (str "; transport " (name t)
                   (when-let [fp (get-in r [:options :xtdb-flight-sql-port])]
                     (str " (FlightSQL port " fp ")"))))
            "; repeats " (get-in r [:options :repeats])
            "; visible on return " (pr-str (:visible-on-return r))))
    (emit)
    (emit "## Results (ms; ops/s counts successful ops over wall time)")
    (emit)
    (emit "| workload | " la " p50 | " la " p95 | " la " ops/s | "
          (when rb (str lb " p50 | " lb " p95 | " lb " ops/s | faster (p50) |")) " notes |")
    (emit "|---|---:|---:|---:|" (when rb "---:|---:|---:|---|") "---|")
    (doseq [k keys*]
      (let [wa (get-in ra [:workloads k])
            wb (get-in rb [:workloads k])
            rate (fn [w] (if (:entries-per-sec w)
                           (str (fmt0 (:ops-per-sec w)) " (" (fmt0 (:entries-per-sec w)) " ent/s)")
                           (fmt0 (:ops-per-sec w))))
            faster (when (and (:p50-ms wa) (:p50-ms wb))
                     (let [x (:p50-ms wa) y (:p50-ms wb)]
                       (if (< x y)
                         (format "%s %.1fx" la (/ y x))
                         (format "%s %.1fx" lb (/ x y)))))
            notes (str/join "; " (concat (map #(str la ": " %) (check-notes wa))
                                         (map #(str lb ": " %) (check-notes wb))))]
        (emit "| " (workload-label k)
              " | " (fmt (:p50-ms wa)) " | " (fmt (:p95-ms wa)) " | " (rate wa) " | "
              (when rb (str (fmt (:p50-ms wb)) " | " (fmt (:p95-ms wb)) " | " (rate wb) " | " (or faster "—") " | "))
              notes " |")))
    (emit)
    (when (some (fn [k] (= :edit (first k))) keys*)
      (emit "## Edit size")
      (emit)
      (emit "What each PUT changes, averaged over its ops: top-level fields and leaf values that differ from the stored version, and body size.")
      (emit)
      (doseq [k keys* :when (= :edit (first k))
              [l r] [[la ra] [lb rb]] :when r
              :let [w (get-in r [:workloads k])] :when w]
        (emit "- " (workload-label k) ", " l ": " (format "%.1f" (double (:fields-changed w))) " of "
              (format "%.1f" (double (:fields-total w))) " fields, "
              (fmt0 (:leaves-changed w)) " of " (fmt0 (:leaves-total w)) " leaves, ~"
              (fmt0 (:body-chars w)) " chars"))
      (emit))
    (emit "## Contention")
    (emit)
    (doseq [[l r] [[la ra] [lb rb]] :when r]
      (let [c (get-in r [:workloads [:contention]])]
        (emit "- **" l "**: succeeded " (:succeeded c) ", 412 conflicts " (:conflicts c)
              ", other errors " (:other-errors c) ", lost updates " (:lost-updates c))))
    (emit)
    (emit "## Spread")
    (emit)
    (emit "Per-repeat p50s, for judging whether a difference exceeds run-to-run noise:")
    (emit)
    (doseq [k keys*]
      (emit "- " (workload-label k) ": " la " " (mapv #(Double/parseDouble (fmt %)) (get-in ra [:workloads k :p50-by-rep]))
            (when rb (str ", " lb " " (mapv #(Double/parseDouble (fmt %)) (get-in rb [:workloads k :p50-by-rep]))))))
    (let [md   (str sb)
          path (or out "target/WRITE-REPORT.md")]
      (println md)
      (.mkdirs (io/file "target"))
      (spit path md)
      (log "Wrote" path))))
