(ns fhir-search-bench.queries
  "The FHIR-search workload, modeled on the query families benchmarked by Blaze
   (https://samply.github.io/blaze/performance/fhir-search.html):

   - single code           Observation?code=<loinc>
   - multiple codes (OR)    Observation?code=<c1>,<c2>,...
   - code + date            Observation?code=<loinc>&date=<year>
   - category               Observation?category=<vital-signs|laboratory>
   - category + date        Observation?category=<...>&date=<year>
   - code + value-quantity  Observation?code=<loinc>&value-quantity=<op><n>

   LOINC codes are the common Synthea vitals/labs so a 10k-resource dataset still
   produces non-empty hit sets. `_count` is set high so the store materializes
   the whole match set, which is what Blaze measures.")

(def ^:private all "100000")

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

(def queries
  "Ordered vector of query specs. `:params` is the FHIR search param map passed
   straight to the store's `search`/`count-resources`. `:tier` distinguishes the
   queries that every backend should support (:core) from the ones that lean on
   richer search-param handling (:extended) and may legitimately be unsupported."
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
