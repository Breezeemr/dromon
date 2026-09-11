(ns fhir-store.trace
  "Telemere spans that keep resource content out of the emitted signal.

  `taoensso.telemere/trace!` records the traced form's return value twice: in
  the signal's `:run-val` field, and in the default message it builds as
  \"<form> => <return value>\". Telemere's own defaults are min-level `:info`
  with a console handler installed, so a store verb wrapped in a bare `trace!`
  writes the resource it returns to stdout with no logging configuration at
  all. Reading a Coverage that way logged its `subscriberId`; reading a
  Patient logged names and birth dates.

  `trace!` here is a drop-in replacement for the two-argument `trace!` that
  records a `summarize` shape description in place of the value. The traced
  form still returns its real value unchanged -- only what the signal carries
  changes. Identifying detail belongs in the span's `:data`, which is written
  by hand and reviewable; the message is generated and is not."
  (:require [taoensso.telemere :as t]))

(def ^:dynamic *trace-values?*
  "When true, `trace!` records real return values instead of `summarize`
  descriptions. Development aid only: the values are FHIR resources, so
  enabling this in a deployed service writes PHI to the log sink.

  Deliberately not wired to any config file or environment variable, so no
  deployed configuration can turn it on. Rebind it from a REPL, or
  `alter-var-root` it when the store calls happen on other threads."
  false)

(defn- safe-count
  "Counts only collections that already know their size, so summarizing never
  realizes a lazy sequence as a side effect of logging."
  [coll]
  (when (counted? coll) (count coll)))

(defn- entry-resource-type
  "Resource type shared by a counted collection's first element, or nil.
  Reads only `:resourceType`, and only from collections whose `first` does not
  force computation."
  [coll]
  (when (counted? coll)
    (let [x (first coll)]
      (when (map? x) (:resourceType x)))))

(defn- bundle-summary
  "A Bundle's `:type` is a fixed FHIR enum (searchset, transaction-response,
  ...) so it is safe to name. Its entries are resources and are not."
  [b]
  (str "Bundle/" (or (:type b) "?")
       " entries=" (or (safe-count (:entry b)) "?")
       (when-let [total (:total b)] (str " total=" total))))

(defn summarize
  "Describes `v` in terms that cannot reproduce its content.

  Resource types, ids, collection sizes, HTTP statuses and string lengths are
  structural and are rendered. Element values -- names, dates, identifiers,
  free text -- never are. Values that cannot carry PHI in a store or handler
  return position (booleans, keywords, numbers, uuids) are rendered as-is,
  because authorization decisions and counts are the point of the span.

  Strings are rendered as a length rather than as text: the store returns ids,
  but it also returns search-parameter echoes and narrative."
  [v]
  (cond
    (nil? v)      "nil"
    (boolean? v)  v
    (keyword? v)  v
    (number? v)   v
    (uuid? v)     v
    (string? v)   (str "#String[" (count v) "]")

    (map? v)
    (cond
      ;; FHIR codes are strings, so an integer :status means a ring response.
      (int? (:status v))  (str "#Response[" (:status v) "]")
      (= "Bundle" (:resourceType v)) (bundle-summary v)
      (:resourceType v)   (str (:resourceType v) "/" (:id v))
      :else               (str "#Map[" (or (safe-count v) "?") "]"))

    (coll? v)
    (str "[" (or (safe-count v) "?")
         " " (or (entry-resource-type v) "items") "]")

    :else (str "#" (.getName (class v)))))

(defmacro trace!
  "Like `taoensso.telemere/trace!` with an options map, but the signal records
  `(summarize <return value>)` rather than the value itself. See the namespace
  docstring for why.

  A call site that needs a different description can pass its own `:run-val`,
  which is honoured as-is; that opt is how this macro does its work."
  [opts run]
  (when-not (map? opts)
    (throw (ex-info "fhir-store.trace/trace! needs a literal options map"
                    {:opts opts})))
  ;; `_run-val` is Telemere's binding for the traced form's value, in scope
  ;; wherever the `:run-val` opt is spliced. Merging the caller's `&form` meta
  ;; keeps Telemere's callsite coords pointing at the call site rather than at
  ;; this macro (CLJ-865; `taoensso.truss/keep-callsite` does the same).
  (vary-meta
   `(t/trace! ~(merge {:run-val `(if *trace-values?* ~'_run-val (summarize ~'_run-val))}
                      opts)
              ~run)
   merge (meta &form)))
