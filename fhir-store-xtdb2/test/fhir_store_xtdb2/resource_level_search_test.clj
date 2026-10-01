(ns fhir-store-xtdb2.resource-level-search-test
  "The search parameters FHIR defines on Resource: `_tag`, `_profile` and
   `_security` over the stored meta struct, and `_lastUpdated` over the row's
   system time.

   None has a column of its own name. Before they were mapped, each compiled
   into a comparison against a column such as \"_tag\"; at XTDB 2.2.0-beta1 that
   silently matched nothing, and from rc0 it fails at planning, which search
   reports as ::search-query-failed and answers empty. Every test therefore also
   asserts that no query-failed signal was raised, so an empty answer cannot
   pass by being a swallowed failure."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [taoensso.telemere :as t]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db]))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(def ^:private failure-signal-ids
  #{::core-db/search-query-failed
    ::core-db/count-query-failed})

(defn- failure-signals
  "Runs `f` and returns [its value, the store's query-failed signals].
   with-signals traps a thrown error; it is rethrown so it fails the test."
  [f]
  (let [{:keys [value signals error]} (t/with-signals (f))]
    (when error (throw error))
    [value (filterv #(contains? failure-signal-ids (:id %)) signals)]))

(def ^:private patient-schema
  "A Patient schema with no :meta entry, so the meta column is declared only
   because the store itself reads it."
  (m/schema [:map {:resourceType "Patient"}
             [:resourceType {:optional true} :string]
             [:active {:optional true} :boolean]]))

(def ^:private registry-without-meta-params
  "A registry like US Core Patient's or any purser type's: no `_lastUpdated`,
   and, as for every type, no `_tag` / `_profile` / `_security`."
  {"active" {:type "token" :columns [{:col "active" :fhir-type "boolean" :array? false}]}})

(def ^:private registry-with-last-updated
  "A registry declaring `_lastUpdated`, resolved the way server.search-registry
   resolves Resource.meta.lastUpdated."
  (assoc registry-without-meta-params
         "_lastUpdated" {:type "date"
                         :columns [{:col "_system_from" :fhir-type "instant" :array? false}]}))

(def ^:private tag-system "http://example.org/tags")
(def ^:private other-system "http://example.org/other-tags")
(def ^:private confidentiality "http://terminology.hl7.org/CodeSystem/v3-Confidentiality")
(def ^:private profile-1 "http://example.org/StructureDefinition/profile-1")
(def ^:private profile-2 "http://example.org/StructureDefinition/profile-2")

(def ^:private labelled
  {:resourceType "Patient" :active true
   :meta {:tag [{:system tag-system :code "vip"} {:code "unsystemed"}]
          :security [{:system confidentiality :code "R"}]
          :profile [profile-1]}})

(def ^:private other-labelled
  {:resourceType "Patient" :active true
   :meta {:tag [{:system other-system :code "vip"}]
          :profile [profile-2]}})

(def ^:private unlabelled
  {:resourceType "Patient" :active true})

;; ---------------------------------------------------------------------------
;; _tag / _profile / _security
;; ---------------------------------------------------------------------------

(deftest meta-parameters-plan-before-any-row-carries-meta
  (doseq [query-mode [:sql :xtql]]
    (testing (str query-mode)
      (let [store (core-db/create-xtdb-store {:resource/schemas [patient-schema]
                                              :query-mode query-mode})
            tenant "meta-empty"]
        (try
          (db/create-tenant store tenant)
          (let [[results failures]
                (failure-signals
                 #(vec (for [rt [:Patient :Flag]
                             params [{"_tag" "vip"} {"_profile" profile-1} {"_security" "R"}]]
                         [(db/search store tenant rt params nil)
                          (db/count-resources store tenant rt params nil)])))]
            (is (every? #{[[] 0]} results)
                "a schema type and a type no schema enumerates both answer empty")
            (is (empty? failures) "no search was swallowed as a planning failure"))
          (finally (close-store-nodes! store)))))))

(deftest tag-profile-and-security-match-the-stored-meta
  (doseq [query-mode [:sql :xtql]]
    (testing (str query-mode)
      (let [store (core-db/create-xtdb-store {:resource/schemas [patient-schema]
                                              :query-mode query-mode})
            tenant "meta-search"
            ids (fn [params]
                  (set (map :id (db/search store tenant :Patient params
                                           registry-without-meta-params))))
            cnt (fn [params]
                  (db/count-resources store tenant :Patient params
                                      registry-without-meta-params))]
        (try
          (db/create-resource store tenant :Patient "labelled" labelled)
          (db/create-resource store tenant :Patient "other-labelled" other-labelled)
          (db/create-resource store tenant :Patient "unlabelled" unlabelled)
          (let [[results failures]
                (failure-signals
                 #(hash-map
                   :tag-code             (ids {"_tag" "vip"})
                   :tag-system-code      (ids {"_tag" (str tag-system "|vip")})
                   :tag-system-only      (ids {"_tag" (str tag-system "|")})
                   :tag-no-system        (ids {"_tag" "|unsystemed"})
                   :tag-no-system-miss   (ids {"_tag" "|vip"})
                   :tag-absent           (ids {"_tag" "absent"})
                   :tag-or               (ids {"_tag" (str other-system "|vip,unsystemed")})
                   :tag-empty            (ids {"_tag" "|"})
                   :tag-and-id           (ids {"_tag" "vip" "_id" "other-labelled"})
                   :tag-keyword          (ids {:_tag "vip" :active true})
                   :tag-count            (cnt {"_tag" "vip"})
                   :security-code        (ids {"_security" "R"})
                   :security-system-code (ids {"_security" (str confidentiality "|R")})
                   :security-absent      (ids {"_security" "N"})
                   :security-count       (cnt {"_security" "R"})
                   :profile              (ids {"_profile" profile-1})
                   :profile-or           (ids {"_profile" (str profile-1 "," profile-2)})
                   :profile-is-not-prefix (ids {"_profile" "http://example.org/StructureDefinition"})
                   :profile-count        (cnt {"_profile" profile-2})))]
            (is (= {:tag-code             #{"labelled" "other-labelled"}
                    :tag-system-code      #{"labelled"}
                    :tag-system-only      #{"labelled"}
                    :tag-no-system        #{"labelled"}
                    :tag-no-system-miss   #{}
                    :tag-absent           #{}
                    :tag-or               #{"labelled" "other-labelled"}
                    :tag-empty            #{}
                    :tag-and-id           #{"other-labelled"}
                    :tag-keyword          #{"labelled" "other-labelled"}
                    :tag-count            2
                    :security-code        #{"labelled"}
                    :security-system-code #{"labelled"}
                    :security-absent      #{}
                    :security-count       1
                    :profile              #{"labelled"}
                    :profile-or           #{"labelled" "other-labelled"}
                    :profile-is-not-prefix #{}
                    :profile-count        1}
                   results))
            (is (empty? failures) "no search was swallowed as a planning failure"))
          (testing "a label removed by an update stops matching"
            (db/update-resource store tenant :Patient "labelled" unlabelled)
            (is (= #{"other-labelled"} (ids {"_tag" "vip"})))
            (is (= #{} (ids {"_security" "R"})))
            (is (= #{} (ids {"_profile" profile-1}))))
          (finally (close-store-nodes! store)))))))

;; ---------------------------------------------------------------------------
;; _lastUpdated
;; ---------------------------------------------------------------------------

(deftest last-updated-filters-by-system-time
  (doseq [query-mode [:sql :xtql]]
    (testing (str query-mode)
      (let [store (core-db/create-xtdb-store {:resource/schemas [patient-schema]
                                              :query-mode query-mode})
            tenant "last-updated"]
        (try
          ;; Separate transactions, so the two system times differ.
          (db/create-resource store tenant :Patient "first" unlabelled)
          (db/create-resource store tenant :Patient "second" unlabelled)
          (let [t1 (get-in (db/read-resource store tenant :Patient "first") [:meta :lastUpdated])
                t2 (get-in (db/read-resource store tenant :Patient "second") [:meta :lastUpdated])]
            (is (neg? (compare (java.time.Instant/parse t1) (java.time.Instant/parse t2))))
            (doseq [[label registry] [["no registry" nil]
                                      ["a registry without _lastUpdated" registry-without-meta-params]
                                      ["a registry declaring _lastUpdated" registry-with-last-updated]]]
              (testing label
                (let [ids (fn [params]
                            (set (map :id (db/search store tenant :Patient params registry))))
                      [results failures]
                      (failure-signals
                       #(hash-map
                         :ge-second     (ids {"_lastUpdated" (str "ge" t2)})
                         :gt-first      (ids {"_lastUpdated" (str "gt" t1)})
                         :lt-second     (ids {"_lastUpdated" (str "lt" t2)})
                         :le-first      (ids {"_lastUpdated" (str "le" t1)})
                         :eq-first      (ids {"_lastUpdated" (str "eq" t1)})
                         :bare-first    (ids {"_lastUpdated" t1})
                         :ne-first      (ids {"_lastUpdated" (str "ne" t1)})
                         :either        (ids {"_lastUpdated" (str t1 "," t2)})
                         :ge-2000       (ids {"_lastUpdated" "ge2000-01-01"})
                         :gt-year-2000  (ids {"_lastUpdated" "gt2000"})
                         :lt-2000       (ids {"_lastUpdated" "lt2000-01-01"})
                         :keyword-key   (ids {:_lastUpdated (str "ge" t2)})
                         :with-other    (ids {"_lastUpdated" (str "le" t2) "active" true})
                         :sorted-desc   (mapv :id (db/search store tenant :Patient
                                                             {"_lastUpdated" "ge2000"
                                                              "_sort" "-_lastUpdated"}
                                                             registry))
                         :count-ge      (db/count-resources store tenant :Patient
                                                            {"_lastUpdated" (str "ge" t2)} registry)))]
                  (is (= {:ge-second    #{"second"}
                          :gt-first     #{"second"}
                          :lt-second    #{"first"}
                          :le-first     #{"first"}
                          :eq-first     #{"first"}
                          :bare-first   #{"first"}
                          :ne-first     #{"second"}
                          :either       #{"first" "second"}
                          :ge-2000      #{"first" "second"}
                          :gt-year-2000 #{"first" "second"}
                          :lt-2000      #{}
                          :keyword-key  #{"second"}
                          :with-other   #{"first" "second"}
                          :sorted-desc  ["second" "first"]
                          :count-ge     1}
                         results))
                  (is (empty? failures) "no search was swallowed as a planning failure"))))
            (testing "an update moves the resource's lastUpdated forward"
              (db/update-resource store tenant :Patient "first" unlabelled)
              (is (= #{"first" "second"}
                     (set (map :id (db/search store tenant :Patient
                                              {"_lastUpdated" (str "ge" t2)} nil)))))))
          (finally (close-store-nodes! store)))))))
