(ns fhir-store-xtdb2.tx-metadata
  "Persistence of the `:tx-metadata` stamp in a per-tenant `fhir_tx_metadata`
   table, written by an op inside the SAME `xt/execute-tx` as the data.

   `fhir-store.protocol/ITxMetadataStore` promises that the stamp commits iff
   the write does. An extra op in the write's own transaction gives that on
   every transport and both query modes. XTDB's own `:metadata` tx option
   (stored in `xt.txs.user_metadata`) does not: 2.2.0's FlightSQL client
   refuses it outright, and it would turn the stamp's keyword keys into
   strings.

   ONE ROW PER WRITTEN RESOURCE, keyed `<table>/<id>` (the lowercased type the
   resource's table is named for, so `Patient` and `patient` name one
   resource as they name one table). XTDB stamps the row's `_system_from` with
   the transaction's system time, which is also what a commit-time versionId
   encodes, so a version's stamp is a point lookup on (`_id`, `_system_from`),
   the same shape vread uses. The key is the ownership guard: a versionId
   naming another resource's transaction finds no row under this resource's
   key. A delete has no row in the resource table but has its own stamp row,
   addressed by the delete's commit time.

   The stamp is ONE edn string, exactly as fhir-store-datomic stores it on
   its transaction entity: the map is open by contract, and a projection onto
   columns would drop keys it did not anticipate (and XTDB would hand back
   keyword keys as strings). The write-time rules are Datomic's -- a record,
   an unprintable value, or text that does not read back equal is refused
   with a 500 naming the key path -- plus one XTDB adds: text holding an
   unpaired UTF-16 surrogate reaches XTDB as \"?\", so it would read back as a
   DIFFERENT stamp with no error anywhere. That is refused too.

   Nothing here logs, traces, or puts any part of a stamp in an exception: it
   carries usernames and practitioner ids."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fhir-store.protocol :as fp]
            [fhir-store-xtdb2.transform :as xf]))

(def table
  "The stamp table. FHIR type names hold no underscore, so no resource
   type's table can be named this."
  "fhir_tx_metadata")

(defn stamp-key
  "The `_id` of `resource-type`/`id`'s stamp rows."
  [resource-type id]
  (str (str/lower-case (name resource-type)) "/" id))

;; ---------------------------------------------------------------------------
;; Write side
;; ---------------------------------------------------------------------------

(defn- unprintable!
  "Refuse a stamp whose value at `path` cannot be stored and read back
   unchanged. `path` is the key path and NEVER the value."
  ([path] (unprintable! path nil))
  ([path reason]
   (throw (ex-info ":tx-metadata holds a value edn cannot round-trip"
                   (cond-> {:fhir/status 500
                            :fhir/code "exception"
                            :tx-metadata/problem :unprintable-value
                            :path (vec path)}
                     reason (assoc :tx-metadata/reason reason))))))

(defn- edn-scalar?
  "Whether `v` is a scalar `pr-str` writes and `clojure.edn/read-string`
   reads back equal. `#inst` and `#uuid` are clojure.edn's only default
   tagged readers, hence Date and UUID and nothing else Java-side."
  [v]
  (or (nil? v)
      (boolean? v)
      (string? v)
      (number? v)
      (keyword? v)
      (symbol? v)
      (char? v)
      (instance? java.util.Date v)
      (instance? java.util.UUID v)))

(defn- unencodable-scalar?
  [v]
  (cond
    (char? v)   (Character/isSurrogate (char v))
    (symbol? v) (or (some-> (namespace v) xf/unpaired-surrogate?)
                    (xf/unpaired-surrogate? (name v)))
    :else       (xf/unencodable? v)))

(defn- edn-safe!
  "Walk `v`, throwing at the first value edn cannot round trip or XTDB
   cannot store unchanged. `record?` is tested before `map?`: a defrecord
   passes `check-tx-metadata` as a map, prints as `#ns.Rec{...}`, and edn
   cannot read that back. Map keys are walked as well as values."
  [path v]
  (cond
    (record? v)
    (unprintable! path)

    (map? v)
    (doseq [[k mv] v]
      (edn-safe! (conj path ::map-key) k)
      (edn-safe! (conj path k) mv))

    (set? v)
    (doseq [sv v]
      (edn-safe! (conj path ::set-member) sv))

    (sequential? v)
    (dorun (map-indexed (fn [i sv] (edn-safe! (conj path i) sv)) v))

    (not (edn-scalar? v))
    (unprintable! path)

    (unencodable-scalar? v)
    (unprintable! path :unpaired-surrogate)))

(defn print-stamp
  "The edn text for `stamp`, or a throw naming the key path that stopped it.

   The print vars are bound because a process-wide `*print-length*` or
   `*print-level*` truncates to `...`, which edn then refuses to read, and a
   stamp truncated at write time is unrecoverable. The walk classifies by
   type so a refusal can name a path; reading the text back proves it, since
   a keyword or symbol edn cannot tokenize prints without complaint and can
   even read back as a different value. The reader's own exception is
   discarded, class name only: edn quotes the offending token."
  [stamp]
  (edn-safe! [] stamp)
  (let [text (binding [*print-length*         nil
                       *print-level*          nil
                       *print-readably*       true
                       *print-dup*            false
                       *print-meta*           false
                       *print-namespace-maps* false]
               (pr-str stamp))
        read-back (try
                    (edn/read-string text)
                    (catch Exception e
                      (unprintable! [] {:stage :read-back
                                        :reader-exception (.getName (class e))})))]
    (when-not (= stamp read-back)
      (unprintable! [] {:stage :round-trip-mismatch}))
    (when (xf/unpaired-surrogate? text)
      (unprintable! [] :unpaired-surrogate))
    text))

(defn stamp-text
  "The text to store for a write verb's `opts`, or nil when they carry no
   stamp. Runs `check-tx-metadata` first, so a malformed stamp is refused the
   way every adopting store refuses it. Call it before the write reads or
   prepares anything: a refused stamp must refuse the whole write."
  [opts]
  (some-> (fp/check-tx-metadata (:tx-metadata opts)) print-stamp))

(defn write-op
  "The op recording `text` against each [resource-type id] in `targets`, or
   nil for no text or no targets. Append it only to ops that write every one
   of them: a stamp row is evidence of a write."
  [text targets]
  (when (and text (seq targets))
    (into [:sql (format "INSERT INTO %s (_id, stamp) VALUES (?, ?)" table)]
          (map (fn [[resource-type id]] [(stamp-key resource-type id) text]))
          targets)))

(defn delete-op
  "The op recording `text` against a delete of `resource-type`/`id` from
   `resource-table`, or nil for no text. It must run BEFORE the delete in the
   same transaction: it writes a row only when the resource is current at
   that point, because a DELETE of a resource that does not exist still
   commits, and a stamp for it would name a version that never was."
  [text resource-table resource-type id]
  (when text
    [:sql (format "INSERT INTO %s (_id, stamp) SELECT ?, ? FROM %s WHERE _id = ?"
                  table resource-table)
     [(stamp-key resource-type id) text id]]))

;; ---------------------------------------------------------------------------
;; Read side
;; ---------------------------------------------------------------------------

(def read-sql
  (format "SELECT stamp FROM %s FOR ALL SYSTEM_TIME WHERE _id = ? AND _system_from = ?"
          table))

(def exists-sql
  "Whether the stamp table exists on the node. A SELECT naming a table
   nothing has written fails at planning, and nothing declares this one: the
   INSERT that writes the first stamp creates it."
  (format "SELECT 1 AS present FROM information_schema.tables WHERE table_name = '%s'"
          table))

(defn read-stamp
  "The stamp `text` holds. `coords` is merged into the ex-data of a refusal.

   Text that is present but unreadable THROWS: nil is the protocol's word for
   \"carried none\", and answering it for a stamp physically in the table
   would be the store lying about custody. The reader's exception is not
   chained, since edn quotes the offending token in its message."
  [text coords]
  (try
    (let [v (edn/read-string text)]
      (if (map? v)
        v
        (throw (ex-info "not a map" {}))))
    (catch Exception e
      (throw (ex-info "tx-metadata stamp is not readable"
                      (merge {:fhir/status 500
                              :fhir/code "exception"
                              :tx-metadata/problem :unreadable-stamp
                              :reader-exception (.getName (class e))}
                             coords))))))
