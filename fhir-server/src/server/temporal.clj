(ns server.temporal
  "Temporal selectors on the FHIR surface: `_asOf` (system time, \"as we knew
   it then\") and `_validAt` (valid time, \"as it was true in the world\").

   Neither is a standard FHIR search parameter -- FHIR versions records, not
   facts, and has no valid-time axis at all (see
   `rcm-design/design/02-bitemporality.md` §5.2). They are therefore additive
   selectors that a store must advertise support for, never something a plain
   `GET` honours: an unqualified read must keep returning current state.

   Two rules the rest of this namespace exists to enforce:

   - A selector naming an axis the store does not have is an ERROR, not a
     no-op. Ignoring `_validAt` on a single-axis store would answer the
     valid-now question while the caller believes they asked a historical one.
   - A temporal answer carries the basis it was computed at. `_asOf` omitted
     means \"latest indexed transaction\", which is a different instant on every
     request, so the response states the concrete one it used."
  (:require [clojure.string :as str]
            [fhir-store.protocol :as db]
            [server.search-registry :as sr])
  (:import [java.time Instant LocalDate OffsetDateTime ZoneOffset]))

(def basis-tag-system
  "CodeSystem for the resolved-basis tags stamped on temporal responses.
   Moves to the rcm-ig canonical when that IG exists."
  "https://breezeehr.com/fhir/CodeSystem/temporal-basis")

(def extension-base
  "Base URL for the timeline's temporal-bound extensions. Moves to the rcm-ig
   canonical when that IG exists."
  "https://breezeehr.com/fhir/StructureDefinition/purser-")

(defn- parse-temporal-value
  "Coerce a FHIR instant / dateTime / date string to an Instant, or nil when it
   is not a recognizable point in time. A date-only value means start of that
   day in UTC. Normalizing to one type keeps a single, proven binding path into
   the store rather than three."
  [^String v]
  (when-not (str/blank? v)
    (or (try (Instant/parse v) (catch Exception _ nil))
        (try (.toInstant (OffsetDateTime/parse v)) (catch Exception _ nil))
        (try (-> (LocalDate/parse v) (.atStartOfDay ZoneOffset/UTC) .toInstant)
             (catch Exception _ nil)))))

(defn- param-value [params k]
  (or (get params k) (get params (keyword k))))

(defn requested?
  "True when the request carries any temporal selector."
  [params]
  (boolean (some #(param-value params %) (keys sr/temporal-params))))

(defn parse-basis
  "Reads the temporal selectors out of `params`.

   Returns {:basis {...}} with Instant values on the axes that were named, or
   {:error <400 OperationOutcome>} when a value is unparseable. An empty basis
   (no selectors) yields {:basis nil}."
  [params]
  (reduce
   (fn [acc [pname axis]]
     (if-let [raw (param-value params pname)]
       (if-let [t (parse-temporal-value (str raw))]
         (assoc-in acc [:basis axis] t)
         (reduced
          {:error {:status 400
                   :body {:resourceType "OperationOutcome"
                          :issue [{:severity "error"
                                   :code "invalid"
                                   :diagnostics
                                   (str "Invalid value for " pname ": '" raw
                                        "' — expected a FHIR instant, dateTime, or date "
                                        "(e.g. 2026-09-30T00:00:00Z or 2026-09-30).")}]}}}))
       acc))
   {:basis nil}
   sr/temporal-params))

(defn- axis->param [axis]
  (some (fn [[p a]] (when (= a axis) p)) sr/temporal-params))

(defn unsupported-axis-response
  "400 OperationOutcome when the store cannot honour a requested axis, or nil
   when every requested axis is available.

   Two distinct failures, deliberately worded differently: a store with no
   temporal support at all, and a store that has one axis but not the other.
   The second is the one that matters operationally -- `_asOf` against Datomic
   works, `_validAt` against the same store must say so rather than quietly
   answering the valid-now question."
  [store resource-type basis]
  (let [requested (set (keys (into {} (remove (comp nil? val)) (or basis {}))))]
    (when (seq requested)
      (if-not (satisfies? db/ITemporalReadStore store)
        {:status 400
         :body {:resourceType "OperationOutcome"
                :issue (mapv (fn [axis]
                               {:severity "error"
                                :code "not-supported"
                                :details {:text (str "Temporal search is not supported for "
                                                     resource-type)}
                                :diagnostics
                                (str "The parameter \"" (axis->param axis)
                                     "\" needs a store with point-in-time reads; this "
                                     "deployment's store provides none.")})
                             (sort requested))}}
        (let [available (db/temporal-axes store)
              missing (sort (remove available requested))]
          (when (seq missing)
            {:status 400
             :body {:resourceType "OperationOutcome"
                    :issue (mapv (fn [axis]
                                   {:severity "error"
                                    :code "not-supported"
                                    :details {:text (str "No " (name axis) " axis on this store")}
                                    :diagnostics
                                    (str "The parameter \"" (axis->param axis)
                                         "\" asks for the " (name axis)
                                         " axis, which this store does not have. Available: "
                                         (str/join ", " (map name (sort available)))
                                         ". The request is refused rather than answered on the "
                                         "remaining axis, which would return a different "
                                         "question's answer.")})
                                 missing)}}))))))

(defn resolve-basis
  "Fills in the axes the caller left open, so the response can state the exact
   point it was computed at. An omitted `_asOf` means the latest indexed
   transaction; that instant is what a later re-run must pin to reproduce this
   answer."
  [store tenant-id basis]
  (cond-> basis
    (nil? (:system-time basis))
    (assoc :system-time (:system-time (db/current-basis store tenant-id)))))

(defn basis-tags
  "`meta.tag` entries recording the resolved basis of a temporal response."
  [basis]
  (into []
        (keep (fn [[axis t]]
                (when t
                  {:system basis-tag-system
                   :code (name axis)
                   :display (str t)})))
        (select-keys basis [:system-time :valid-time])))

(defn stamp-basis
  "Attach the resolved basis to a response body's `meta.tag`."
  [body basis]
  (let [tags (basis-tags basis)]
    (cond-> body
      (seq tags) (update-in [:meta :tag] (fnil into []) tags))))

(defn timeline-entry
  "One `Bundle.entry` for a timeline row.

   Valid-time extensions are emitted only when the store has that axis. A
   single-axis store must not produce a null valid period: absent means \"no
   such axis\", whereas a null bound would read as end-of-time."
  [base-url axes {:keys [resource valid-from valid-to system-from system-to]}]
  (let [ext (cond-> []
              system-from (conj {:url (str extension-base "system-from")
                                 :valueInstant (str system-from)})
              system-to   (conj {:url (str extension-base "system-to")
                                 :valueInstant (str system-to)})
              (and (contains? axes :valid-time) valid-from)
              (conj {:url (str extension-base "valid-from") :valueInstant (str valid-from)})
              (and (contains? axes :valid-time) valid-to)
              (conj {:url (str extension-base "valid-to") :valueInstant (str valid-to)}))]
    (cond-> {:fullUrl (str base-url "/" (:id resource))
             :resource resource}
      (seq ext) (assoc :extension ext))))

(defn timeline-bundle
  [base-url axes rows]
  {:resourceType "Bundle"
   :type "history"
   :total (count rows)
   :entry (mapv #(timeline-entry base-url axes %) rows)})
