(ns fhir-search-bench.queries
  "The FHIR-search workload, modeled on the query families benchmarked by Blaze
   (https://samply.github.io/blaze/performance/fhir-search.html):

   - single code           Observation?code=<loinc>
   - multiple codes (OR)    Observation?code=<c1>,<c2>,...
   - code + date            Observation?code=<loinc>&date=<year>
   - category               Observation?category=<vital-signs|laboratory>
   - category + date        Observation?category=<...>&date=<year>
   - code + value-quantity  Observation?code=<loinc>&value-quantity=<op><n>

   Each base query also gets two more realistic variants:
   - `*-top50`        the same filter with `_count=50` (a single result page),
                      exercising the limit/pagination path.
   - `*-top50-date`   `_count=50` plus `_sort=-date,_id` (most recent first, id
                      as a deterministic tiebreak), exercising the sort path. The
                      total order makes the returned page identical across
                      backends, so the bench can compare result pages, not just
                      hit counts.

   LOINC codes are the common Synthea vitals/labs so a 10k-resource dataset still
   produces non-empty hit sets. The base queries set `_count` high so the store
   materializes the whole match set, which is what Blaze measures.")

(def ^:private all "100000")

;; The page size for the realistic limited/sorted variants. A FHIR client almost
;; always pages results; 50 matches the stores' own default `_count`.
(def ^:private page "50")

;; Most-recent-first, with id as a deterministic tiebreak so the returned page is
;; a total order and therefore identical across backends (Synthea emits many
;; observations sharing an effective date, so -date alone would leave the page
;; boundary ambiguous).
(def ^:private page-sort "-date,_id")

;; LOINC codes Synthea emits as top-level Observation.code. Blood pressure is a
;; panel (85354-9) whose systolic/diastolic are nested components, so we search
;; the panel code rather than 8480-6. CBC indices are the common lab codes.
(def ^:private loinc
  {:body-weight   "29463-7"
   :body-height   "8302-2"
   :heart-rate    "8867-4"
   :bmi           "39156-5"
   :bp-panel      "85354-9"
   :pain          "72514-3"
   :resp-rate     "9279-1"
   :rbc           "789-8"
   :hemoglobin    "718-7"
   :leukocytes    "6690-2"})

(def ^:private base-queries
  "The full-result-set query families (Blaze methodology)."
  (let [c (fn [k] (get loinc k))]
    [{:id :single-code-weight   :tier :core
      :desc "code=29463-7 (Body weight)"
      :params {:code (c :body-weight) :_count all}}
     {:id :single-code-bp       :tier :core
      :desc "code=85354-9 (Blood pressure panel)"
      :params {:code (c :bp-panel) :_count all}}
     {:id :single-code-hr       :tier :core
      :desc "code=8867-4 (Heart rate)"
      :params {:code (c :heart-rate) :_count all}}
     {:id :multi-code-10        :tier :core
      :desc "code=<10 LOINC codes OR>"
      :params {:code (clojure.string/join "," (vals loinc)) :_count all}}
     {:id :category-vitals      :tier :core
      :desc "category=vital-signs"
      :params {:category "vital-signs" :_count all}}
     {:id :category-labs        :tier :core
      :desc "category=laboratory"
      :params {:category "laboratory" :_count all}}
     {:id :code-and-date        :tier :extended
      :desc "code=29463-7&date=ge2015"
      :params {:code (c :body-weight) :date "ge2015" :_count all}}
     {:id :category-and-date    :tier :extended
      :desc "category=vital-signs&date=ge2015"
      :params {:category "vital-signs" :date "ge2015" :_count all}}
     {:id :code-and-value       :tier :extended
      :desc "code=29463-7&value-quantity=ge50"
      :params {:code (c :body-weight) :value-quantity "ge50" :_count all}}]))

(defn- with-id-suffix [q suffix]
  (update q :id #(keyword (str (name %) suffix))))

(defn- limited
  "A `_count=50` page of the same filter (limit path, no ordering guarantee)."
  [q]
  (-> q
      (with-id-suffix "-top50")
      (update :desc str " [top 50]")
      (assoc :limited? true)
      (assoc-in [:params :_count] page)))

(defn- limited-sorted
  "A `_count=50` page ordered by -date,_id (limit + sort path). The total order
   makes the page deterministic and therefore comparable across backends."
  [q]
  (-> q
      (with-id-suffix "-top50-date")
      (update :desc str " [top 50 by -date]")
      (assoc :limited? true :sorted? true)
      (update :params merge {:_count page :_sort page-sort})))

(def queries
  "Ordered vector of query specs. `:params` is the FHIR search param map passed
   straight to the store's `search`/`count-resources`. `:tier` distinguishes the
   queries that every backend should support (:core) from the ones that lean on
   richer search-param handling (:extended) and may legitimately be unsupported.
   `:limited?`/`:sorted?` flag the realistic pagination variants so the report
   can compare returned pages, not just hit counts."
  (into (vec base-queries)
        (concat (map limited base-queries)
                (map limited-sorted base-queries))))
