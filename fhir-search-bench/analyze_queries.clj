;; Collects the Datalog queries the fhir-store-datomic backend builds for each of
;; the benchmark's FHIR searches. build-search-query is pure (no DB), and
;; create-datomic-store builds its catalogs offline, so this needs no transactor.
;;
;;   clojure -M:datomic -e "(load-file \"analyze_queries.clj\")"
(require '[fhir-search-bench.schema :as schema]
         '[fhir-search-bench.queries :as q]
         '[fhir-store-datomic.core :as dat]
         '[fhir-store-datomic.search :as search]
         '[taoensso.telemere :as tel]
         '[clojure.pprint :as pp]
         '[clojure.string :as str])
(tel/set-min-level! :warn)

(let [schemas  @schema/schemas
      registry (schema/registry-for "Observation")
      store    (dat/create-datomic-store {:resource/schemas schemas})
      catalog  (get (:resource-catalogs store) "Observation")
      out      (StringBuilder.)
      emit     (fn [& ss] (doseq [s ss] (.append out (str s))) (.append out "\n"))]
  (emit "# Datalog queries the Datomic backend builds for the FHIR-search benchmark")
  (emit)
  (emit "Resource type: Observation.  Observation catalog resolved: " (some? catalog)
        " (" (count catalog) " fields).")
  (emit "Every query also takes runtime `:in` args: `$` (the db) and `?rt-eid`")
  (emit "(the Observation resource-type entity id). `date` params add lower/upper")
  (emit "bound bindings shown as extra-args.")
  (emit)
  (doseq [{:keys [id desc tier params]} q/queries]
    (let [display (->> (dissoc params :_count)
                       (map (fn [[k v]] (str (name k) "=" v)))
                       (str/join "&"))
          {:keys [query extra-args unsupported-params]}
          (search/build-search-query :Observation params registry catalog)]
      (emit "## " (name id) "   [" (name tier) "]")
      (emit "FHIR:  `Observation?" display "`")
      (emit)
      (emit "```clojure")
      (.append out (with-out-str (pp/pprint query)))
      (emit "```")
      (when (seq extra-args)
        (emit "extra-args (runtime values for the extra :in bindings): " (pr-str extra-args)))
      (when (seq unsupported-params)
        (emit "UNSUPPORTED -> Datalog can't express these; the store falls back to")
        (emit "in-memory filtering after the Datalog pass: " (pr-str unsupported-params)))
      (emit)))
  (.mkdirs (java.io.File. "target"))
  (spit "target/datomic-queries.md" (str out))
  (println (str out))
  (println "Wrote target/datomic-queries.md"))
(System/exit 0)
