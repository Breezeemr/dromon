(ns fhir-store-xtdb2.declaration-test
  "Table and column declarations, and node-start settings, for XTDB 2.2.0-beta3.

   From 2.2.0-rc0 a read, DELETE, ASSERT or :delete-docs that names a table or
   column nothing has ever written fails at planning (\"Table not found\" /
   \"Column not found\") instead of answering empty. The store declares every
   schema type's table when a tenant node starts and every other type on first
   use; these tests pin that the empty answers stay empty, quietly, and that a
   later write is still found by the same queries."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [malli.core :as m]
            [taoensso.telemere :as t]
            [xtdb.api :as xt]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store-xtdb2.transform :as xf]
            [fhir-store.protocol :as db]
            [com.breezeehr.fhir-primitives :as fp]))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- root-ex-data
  "The first ex-data on the cause chain that carries :fhir/status."
  [e]
  (some #(when (:fhir/status (ex-data %)) (ex-data %))
        (take-while some? (iterate ex-cause e))))

(def ^:private failure-signal-ids
  #{::core-db/search-query-failed
    ::core-db/count-query-failed
    ::core-db/count-as-of-failed})

(defn- failure-signals
  "Runs `f` and returns [its value, the store's query-failed signals].
   with-signals traps a thrown error; it is rethrown so it fails the test."
  [f]
  (let [{:keys [value signals error]} (t/with-signals (f))]
    (when error (throw error))
    [value (filterv #(contains? failure-signal-ids (:id %)) signals)]))

(defn- node-of [store tenant]
  (:node (get @(:nodes store) tenant)))

(defn- column-names
  "Column names information_schema lists for `table` (lowercase table name)."
  [node table]
  (into #{}
        (map :column-name)
        (xt/q node ["SELECT column_name FROM information_schema.columns WHERE table_name = ?"
                    table])))

(def ^:private registry
  "Stand-ins for the generated FHIR datatype schemas, keyed the way the
   generated packages key them, so :ref classification is exercised."
  {:org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0
   [:map
    [:coding {:optional true}
     [:sequential [:map [:system {:optional true} :string] [:code {:optional true} :string]]]]
    [:text {:optional true} :string]]
   :org.hl7.fhir.StructureDefinition.Coding/v4-3-0
   [:map [:system {:optional true} :string] [:code {:optional true} :string]]
   :org.hl7.fhir.StructureDefinition.Identifier/v4-3-0
   [:map [:system {:optional true} :string] [:value {:optional true} :string]]
   :org.hl7.fhir.StructureDefinition.HumanName/v4-3-0
   [:map [:family {:optional true} :string] [:given {:optional true} [:sequential :string]]]})

(def ^:private schema-opts
  {:registry (merge (:registry fp/fhir-registry-options) registry)})

(def ^:private patient-schema
  (m/schema
   [:map {:resourceType "Patient"}
    [:resourceType {:optional true} :string]
    [:id {:optional true} :string]
    [:active {:optional true} :boolean]
    [:birthDate {:optional true} :time/local-date]
    [:_birthDate {:optional true} [:map [:id {:optional true} :string]]]
    [:name {:optional true} [:sequential [:ref :org.hl7.fhir.StructureDefinition.HumanName/v4-3-0]]]
    [:identifier {:optional true} [:sequential [:ref :org.hl7.fhir.StructureDefinition.Identifier/v4-3-0]]]
    [:maritalStatus {:optional true} [:ref :org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0]]
    [:extension {:optional true} [:sequential [:map [:url :string]]]]]
   schema-opts))

(def ^:private observation-schema
  (m/schema
   [:map {:resourceType "Observation"}
    [:resourceType {:optional true} :string]
    [:status {:optional true} :string]
    [:code {:optional true} [:ref :org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0]]
    [:category {:optional true} [:sequential [:ref :org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0]]]]
   schema-opts))

(def ^:private flag-schema
  (m/schema [:map {:resourceType "Flag"} [:status {:optional true} :string]]))

(def ^:private schemas [patient-schema observation-schema flag-schema])

(def ^:private search-registry
  {"birthdate"  {:type "date" :columns [{:col "birthDate" :fhir-type "date" :array? false}]}
   "name"       {:type "string" :columns [{:col "name" :fhir-type "HumanName" :array? true}]}
   "identifier" {:type "token" :columns [{:col "identifier" :fhir-type "Identifier" :array? true}]}
   "active"     {:type "token" :columns [{:col "active" :fhir-type "boolean" :array? false}]}})

(def ^:private obs-registry
  {"code"     {:type "token" :columns [{:col "code" :fhir-type "CodeableConcept" :array? false}]}
   "category" {:type "token" :columns [{:col "category" :fhir-type "CodeableConcept" :array? true}]}})

;; ---------------------------------------------------------------------------
;; Column derivation
;; ---------------------------------------------------------------------------

(deftest declared-columns-derivation
  (testing "a plain :map: the store's own columns first, then every entry by its storage name"
    (is (= ["_id" "fhir_version" "meta" "resourceType" "status"]
           (xf/declared-columns
            (m/schema [:map {:resourceType "Flag"} [:resourceType :string] [:status :string]])))))

  (testing "a primitive-extension entry is declared under its storage name"
    (let [cols (set (xf/declared-columns patient-schema))]
      (is (contains? cols "primitive-ext-birthDate"))
      (is (not (contains? cols "_birthDate")))))

  (testing "CodeableConcept / Coding entries carry a _tokens column, Identifier does not"
    (let [cols (set (xf/declared-columns patient-schema))]
      (is (contains? cols "maritalStatus"))
      (is (contains? cols "maritalStatus_tokens"))
      (is (contains? cols "identifier"))
      (is (not (contains? cols "identifier_tokens")))
      (is (not (contains? cols "name_tokens"))))
    (let [cols (set (xf/declared-columns observation-schema))]
      (is (contains? cols "code_tokens"))
      (is (contains? cols "category_tokens") "an array of CodeableConcept is still tokenized")))

  (testing "a :multi declares the union of its variants' entries"
    (let [sch (m/schema [:multi {:dispatch :kind :resourceType "Patient"}
                         [:base [:map {:resourceType "Patient"} [:gender {:optional true} :string]]]
                         [:us-core [:map {:resourceType "Patient"}
                                    [:race {:optional true :fhir/extension true :url "http://example.org/race"}
                                     [:map [:text {:optional true} :string]]]]]])]
      (is (= ["_id" "fhir_version" "meta" "gender" "race"] (xf/declared-columns sch)))))

  (testing "schemas are keyed by table-name's spelling"
    (is (= #{"\"patient\"" "\"observation\"" "\"flag\""}
           (set (keys (core-db/schema-declarations schemas)))))))

;; ---------------------------------------------------------------------------
;; Node-start declarations
;; ---------------------------------------------------------------------------

(deftest declared-columns-are-on-the-node
  (let [store (core-db/create-xtdb-store {:resource/schemas schemas})
        tenant "decl-oracle"]
    (try
      (db/create-tenant store tenant)
      (let [cols (column-names (node-of store tenant) "patient")]
        (doseq [c ["_id" "fhir_version" "resourceType" "birthDate" "name" "identifier"
                   "extension" "maritalStatus" "maritalStatus_tokens"]]
          (is (contains? cols c) (str c " is declared on patient")))
        (is (not (contains? cols "identifier_tokens")))
        (is (not (contains? cols "birthdate")) "column names are case-exact"))
      (is (contains? (column-names (node-of store tenant) "observation") "code_tokens"))
      (finally (close-store-nodes! store)))))

(deftest empty-tenant-reads-answer-empty
  (let [store (core-db/create-xtdb-store {:resource/schemas schemas})
        tenant "decl-empty"]
    (try
      (db/create-tenant store tenant)
      (let [basis (db/current-basis store tenant)
            [results failures]
            (failure-signals
             #(vector
               (db/read-resource store tenant :Patient "nope")
               (db/resource-deleted? store tenant :Patient "nope")
               (db/history store tenant :Patient "nope")
               (db/history-type store tenant :Patient {})
               (db/search store tenant :Patient {} search-registry)
               (db/count-resources store tenant :Patient {} search-registry)
               (db/read-as-of store tenant :Patient "nope" basis)
               (db/search-as-of store tenant :Patient {"birthdate" "ge1980"} search-registry basis)
               (db/count-as-of-basis store tenant :Patient {"name" "sm"} search-registry basis)
               (db/resource-timeline store tenant :Patient "nope" nil)
               (into [] (db/scan-type-as-of store tenant :Patient basis))
               (db/count-as-of store tenant :Patient basis)))]
        (is (= [nil false [] [] [] 0 nil [] 0 [] [] 0] results))
        (is (empty? failures) "no read was swallowed as a planning failure"))
      (finally (close-store-nodes! store)))))

(deftest search-on-declared-columns-without-rows
  (let [store (core-db/create-xtdb-store {:resource/schemas schemas})
        tenant "decl-columns"]
    (try
      (testing "every column the registry names answers empty before any row carries it"
        (let [[results failures]
              (failure-signals
               #(vector
                 (db/search store tenant :Patient {"birthdate" "ge1980"} search-registry)
                 (db/search store tenant :Patient {"name" "sm"} search-registry)
                 (db/search store tenant :Patient {"identifier" "sys|val"} search-registry)
                 (db/search store tenant :Patient {"active" true} search-registry)
                 (db/search store tenant :Patient {"_sort" "birthdate"} search-registry)
                 (db/search store tenant :Observation {"code" "a,b"} obs-registry)
                 (db/search store tenant :Observation {"category" "vital-signs"} obs-registry)
                 (db/count-resources store tenant :Observation {"code" "a,b"} obs-registry)))]
          (is (= [[] [] [] [] [] [] [] 0] results))
          (is (empty? failures))))

      (testing "a row written later is matched by the same predicates"
        (db/create-resource store tenant :Patient "p1"
                            {:resourceType "Patient" :active true :birthDate "1985-04-01"
                             :name [{:family "Smith"}]
                             :identifier [{:system "sys" :value "val"}]})
        (db/create-resource store tenant :Observation "o1"
                            {:resourceType "Observation" :status "final"
                             :code {:coding [{:system "http://loinc.org" :code "b"}]}})
        (is (= ["p1"] (mapv :id (db/search store tenant :Patient {"birthdate" "ge1980"} search-registry))))
        (is (= ["p1"] (mapv :id (db/search store tenant :Patient {"name" "sm"} search-registry))))
        (is (= ["p1"] (mapv :id (db/search store tenant :Patient {"identifier" "sys|val"} search-registry))))
        (is (= ["p1"] (mapv :id (db/search store tenant :Patient {"_sort" "birthdate"} search-registry))))
        (is (= ["o1"] (mapv :id (db/search store tenant :Observation {"code" "a,b"} obs-registry)))))
      (finally (close-store-nodes! store)))))

(deftest temporal-read-of-a-type-never-written
  (let [store (core-db/create-xtdb-store {:resource/schemas schemas})
        tenant "decl-temporal"]
    (try
      (db/create-resource store tenant :Patient "p1" {:resourceType "Patient" :active true})
      (let [basis (db/current-basis store tenant)]
        (is (nil? (db/read-as-of store tenant :Observation "o1" basis)))
        (is (= [] (db/resource-timeline store tenant :Observation "o1" nil)))
        (is (= [] (db/search-as-of store tenant :Observation {"code" "a"} obs-registry basis))))
      (finally (close-store-nodes! store)))))

(def ^:private asserted-date-url
  "http://hl7.org/fhir/StructureDefinition/condition-assertedDate")

(def ^:private condition-schema
  "A capability-style :multi: only the profile variant promotes the extension."
  (m/schema
   [:multi {:dispatch (fn [r] (if (:assertedDate r) :profile :base))
            :resourceType "Condition"}
    [:base [:map {:resourceType "Condition"}
            [:resourceType {:optional true} :string]
            [:extension {:optional true} [:sequential [:map [:url :string]]]]]]
    [:profile [:map {:resourceType "Condition"}
               [:resourceType {:optional true} :string]
               [:extension {:optional true} [:sequential [:map [:url :string]]]]
               [:assertedDate {:optional true :fhir/extension true :url asserted-date-url
                               :fhir/value-key :valueDateTime}
                [:sequential :time/local-date]]]]]
   fp/fhir-registry-options))

(def ^:private condition-registry
  {"asserted-date"
   {:type "date"
    :columns [{:col "assertedDate" :fhir-type "dateTime" :array? true
               :extension-url asserted-date-url :extension-promoted? true}
              {:col "extension" :fhir-type "Extension" :array? true
               :extension-url asserted-date-url}]}})

(deftest promoted-extension-column-before-any-row
  (let [store (core-db/create-xtdb-store {:resource/schemas [condition-schema]})
        tenant "decl-promoted"
        q {"asserted-date" "ge2024-01-01"}]
    (try
      (let [[hits failures] (failure-signals
                             #(db/search store tenant :Condition q condition-registry))]
        (is (= [] hits))
        (is (empty? failures) "the promoted column is declared from the profile variant"))
      (db/create-resource store tenant :Condition "c1"
                          {:resourceType "Condition"
                           :assertedDate [(java.time.LocalDate/parse "2024-03-01")]})
      (is (= ["c1"] (mapv :id (db/search store tenant :Condition q condition-registry))))
      (finally (close-store-nodes! store)))))

(deftest reserved-word-type-is-declared-through-table-name
  (let [store (core-db/create-xtdb-store {:resource/schemas schemas})
        tenant "decl-flag"]
    (try
      (is (nil? (db/read-resource store tenant :Flag "f1")))
      (let [node (node-of store tenant)]
        (is (contains? (column-names node "flag") "status"))
        (is (empty? (column-names node "Flag")) "no mixed-case twin table"))
      (finally (close-store-nodes! store)))))

;; ---------------------------------------------------------------------------
;; Types no schema names: declared lazily per (tenant, type)
;; ---------------------------------------------------------------------------

(deftest schemaless-types-are-declared-on-first-use
  (let [store (core-db/create-xtdb-store {})
        tenant "decl-lazy"]
    (try
      (testing "reads on a type never written answer empty"
        (is (nil? (db/read-resource store tenant :X12ControlSequence "x")))
        (is (= [] (into [] (db/scan-type-as-of store tenant :UnmatchedAcknowledgment
                                               (db/current-basis store tenant))))))

      (testing "create then a guarded update"
        (db/create-resource store tenant :X12ControlSequence "x" {:value 1})
        (is (= "2" (get-in (db/update-resource store tenant :X12ControlSequence "x"
                                               {:value 2} {:if-match "1"})
                           [:meta :versionId]))))

      (testing "a column search on an unwritten schemaless type is not declared, and says so"
        (let [[hits failures] (failure-signals
                               #(db/search store tenant :ClaimGroup {"status" "open"} nil))]
          (is (= [] hits))
          (is (= 1 (count failures)))
          (is (= ::core-db/search-query-failed (:id (first failures))))))
      (finally (close-store-nodes! store)))))

(deftest concurrent-first-writes-to-a-new-type
  (let [store (core-db/create-xtdb-store {})
        tenant "decl-race"]
    (try
      (db/create-tenant store tenant)
      (testing "distinct ids all land"
        (let [results (->> (range 8)
                           (mapv (fn [i] (future (db/create-resource store tenant :ClaimEvent
                                                                     (str "e" i) {:n i}))))
                           (mapv deref))]
          (is (= 8 (count (filter :id results))))))

      (testing "the same id: one wins, the ASSERT still refuses the rest with 409"
        (let [outcomes (->> (range 8)
                            (mapv (fn [i] (future
                                            (try (db/create-resource store tenant :ClaimFilingKey
                                                                     "k" {:n i})
                                                 :created
                                                 (catch Exception e
                                                   (:fhir/status (root-ex-data e)))))))
                            (mapv deref))]
          (is (= 1 (count (filter #{:created} outcomes))))
          (is (= 7 (count (filter #{409} outcomes))))))
      (finally (close-store-nodes! store)))))

(deftest transaction-on-a-type-never-written
  (let [store (core-db/create-xtdb-store {})
        tenant "decl-tx"]
    (try
      (testing "a DELETE of an absent resource commits"
        (let [resp (db/transact-transaction
                    store tenant
                    [{:request {:method "DELETE" :url "MedicationDispense/m1"}}])]
          (is (= "204 No Content" (get-in resp [:entry 0 :response :status])))))

      (testing "an If-Match PUT is a precondition failure, not a planning error"
        (let [e (try (db/transact-transaction
                      store tenant
                      [{:request {:method "PUT" :url "SupplyDelivery/s1" :ifMatch "W/\"1\""}
                        :resource {:status "completed"}}])
                     nil
                     (catch Exception e e))]
          (is (some? e))
          (is (= 412 (:fhir/status (root-ex-data e))))))
      (finally (close-store-nodes! store)))))

(deftest xtql-writes-quote-reserved-word-tables
  (let [store (core-db/create-xtdb-store {:resource/schemas schemas :query-mode :xtql})
        tenant "decl-xtql-flag"]
    (try
      (db/create-resource store tenant :Flag "f1" {:resourceType "Flag" :status "active"})
      (is (= "2" (get-in (db/update-resource store tenant :Flag "f1"
                                             {:resourceType "Flag" :status "inactive"}
                                             {:if-match "1"})
                         [:meta :versionId])))
      (db/delete-resource store tenant :Flag "f1" {:if-match "2"})
      (is (nil? (db/read-resource store tenant :Flag "f1")))
      (finally (close-store-nodes! store)))))

;; ---------------------------------------------------------------------------
;; On disk: an existing tenant opened by a build with more types
;; ---------------------------------------------------------------------------

(defn- delete-recursive! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (delete-recursive! c)))
  (.delete f))

(deftest on-disk-tenant-reopened-with-new-types
  (let [base (str (java.nio.file.Files/createTempDirectory
                   "dromon-xtdb2-decl-" (into-array java.nio.file.attribute.FileAttribute [])))
        log-path (str base "/log")
        storage-path (str base "/storage")
        node-config {:log [:local {:path log-path}]
                     :storage [:local {:path storage-path}]}
        tenant "t1"]
    (try
      (let [store-a (core-db/create-xtdb-store {:node-config node-config
                                                :resource/schemas [patient-schema]})]
        (try
          (db/create-tenant store-a tenant)
          (is (nil? (db/read-resource store-a tenant :Patient "p1")))
          (finally (close-store-nodes! store-a))))
      (let [store-b (core-db/create-xtdb-store {:node-config node-config
                                                :resource/schemas [patient-schema
                                                                   observation-schema]})]
        (try
          (db/create-tenant store-b tenant)
          (let [[results failures]
                (failure-signals
                 #(vector (db/read-resource store-b tenant :Patient "p1")
                          (db/read-resource store-b tenant :Observation "o1")
                          (db/count-resources store-b tenant :Observation {} nil)))]
            (is (= [nil nil 0] results))
            (is (empty? failures)))
          (is (contains? (column-names (node-of store-b tenant) "observation") "code_tokens")
              "the reopened tenant gained the new type's declaration")
          (is (contains? (column-names (node-of store-b tenant) "patient") "maritalStatus")
              "the earlier declaration survived the restart")
          (db/delete-tenant store-b tenant {:close-storage? true})
          (is (not (.exists (java.io.File. log-path))) "log directory, replica log included, removed")
          (is (not (.exists (java.io.File. storage-path))))
          (finally (close-store-nodes! store-b))))
      (finally (delete-recursive! (java.io.File. base))))))

;; ---------------------------------------------------------------------------
;; Pipelined replica-log appends
;; ---------------------------------------------------------------------------

(defn- pipelined?
  "Whether the node's log processor pipelines replica-log appends. Reads the
   private field xtdb.indexer.LogProcessor.pipelinedReplicaAppends (XTDB
   2.2.0-beta3); the next release removes the flag, and this test with it."
  [node]
  (let [lp (-> (:db-cat node) (.databaseOrThrow "xtdb") .getPartitions first .getLogProcessor)
        field (try (.getDeclaredField (class lp) "pipelinedReplicaAppends")
                   (catch NoSuchFieldException e
                     (throw (ex-info (str "xtdb.indexer.LogProcessor has no pipelinedReplicaAppends "
                                          "field: if this XTDB always pipelines, delete this test "
                                          "and the :pipelined-replica-appends? option")
                                     {:class (class lp)} e))))]
    (.setAccessible field true)
    (.get field lp)))

(defn- write-and-read [node]
  (xt/execute-tx node [[:sql "INSERT INTO probe (_id, n) VALUES (?, ?)" ["a" 1]]])
  (:n (first (xt/q node ["SELECT n FROM probe WHERE _id = ?" "a"]))))

(deftest start-node-sets-pipelining
  (testing "the config the store builds"
    (is (true? (.getPipelinedReplicaAppends (.getIndexer (core-db/->xtdb-config {} {})))))
    (is (false? (.getPipelinedReplicaAppends
                 (.getIndexer (core-db/->xtdb-config {} {:pipelined-replica-appends? false})))))
    (let [cfg (core-db/->xtdb-config {:indexer {:rows-per-block 1234}} {})]
      (is (= 1234 (.getRowsPerBlock (.getIndexer cfg))) "the map's own :indexer keys still apply")
      (is (true? (.getPipelinedReplicaAppends (.getIndexer cfg))))))

  (testing "a started node carries the flag either way, and works"
    (with-open [node (core-db/start-node {})]
      (is (true? (pipelined? node)))
      (is (= 1 (write-and-read node))))
    (with-open [node (core-db/start-node {} {:pipelined-replica-appends? false})]
      (is (false? (pipelined? node)))
      (is (= 1 (write-and-read node))))))

(deftest store-tenant-nodes-pipeline-by-default
  (doseq [[opts expected] [[{} true]
                           [{:pipelined-replica-appends? true} true]
                           [{:pipelined-replica-appends? false} false]]]
    (testing (pr-str opts)
      (let [store (core-db/create-xtdb-store (assoc opts :resource/schemas schemas))
            tenant "decl-pipe"]
        (try
          (db/create-resource store tenant :Patient "p1" {:resourceType "Patient" :active true})
          (is (= expected (pipelined? (node-of store tenant))))
          (is (= true (:active (db/read-resource store tenant :Patient "p1"))))
          (finally (close-store-nodes! store)))))))

(deftest node-start-declaration-failure-names-the-tenant
  (let [store (-> (core-db/create-xtdb-store {})
                  (assoc :declared-columns {"\"unbalanced" ["_id"]}))
        e (try (db/create-tenant store "decl-broken") nil (catch Exception e e))]
    (try
      (is (some? e))
      (is (some #(= "decl-broken" (:tenant-id (ex-data %)))
                (take-while some? (iterate ex-cause e))))
      (is (str/includes? (ex-message (some #(when (:tables (ex-data %)) %)
                                           (take-while some? (iterate ex-cause e))))
                         "decl-broken"))
      (is (empty? @(:nodes store)) "no half-started tenant is kept")
      (finally (close-store-nodes! store)))))

;; ---------------------------------------------------------------------------
;; Registry columns the schema lacks
;;
;; A shared SearchParameter lists other types' paths (clinical-identifier is
;; `Observation.identifier | DocumentReference.masterIdentifier`), and a
;; registry built from it can name a column this type never stores. The
;; search ORs every column, so at beta1 the stranger read as null and the real
;; column still matched; from rc0 the stranger fails the whole query at
;; planning, and search answered empty.
;; ---------------------------------------------------------------------------

(def ^:private stranger-registry
  {"identifier" {:type "token"
                 :columns [{:col "identifier" :fhir-type "Identifier" :array? true}
                           {:col "masterIdentifier" :fhir-type nil :array? false}]}
   "encounter"  {:type "reference" :target ["Encounter"]
                 :columns [{:col "encounter" :fhir-type "Reference" :array? false}
                           {:col "context" :fhir-type nil :array? false :sub-col "encounter"}]}
   "code"       {:type "token"
                 :columns [{:col "code" :fhir-type "CodeableConcept" :array? false}
                           {:col "medicationCodeableConcept" :fhir-type "CodeableConcept" :array? false}]}
   "_lastUpdated" {:type "date"
                   :columns [{:col "_system_from" :fhir-type "instant" :array? false}]}
   "unparsed"   {:type "token"
                 :columns [{:col "value.exists() and value != false"}]}})

(def ^:private registry-observation-schema
  "Observation as server.core/capability-schema->server-schema hands it to the
   store: the search registry rides on the schema's properties."
  (m/schema
   [:map {:resourceType "Observation" :fhir/search-registry stranger-registry}
    [:resourceType {:optional true} :string]
    [:status {:optional true} :string]
    [:identifier {:optional true} [:sequential [:ref :org.hl7.fhir.StructureDefinition.Identifier/v4-3-0]]]
    [:encounter {:optional true} [:map [:reference {:optional true} :string]]]
    [:code {:optional true} [:ref :org.hl7.fhir.StructureDefinition.CodeableConcept/v4-3-0]]]
   schema-opts))

(deftest registry-columns-are-declared
  (let [cols (xf/declared-columns registry-observation-schema)]
    (testing "a registry column the schema lacks is declared after the schema's own"
      (is (= ["_id" "fhir_version" "meta" "resourceType" "status" "identifier" "encounter"
              "code" "code_tokens" "masterIdentifier" "context"
              "medicationCodeableConcept" "medicationCodeableConcept_tokens"]
             cols)))
    (testing "system columns and unparsed FHIRPath are never declared"
      (is (not-any? #{"_system_from" "value.exists() and value != false"} cols))))
  (testing "a schema without a registry declares only the store's columns and its entries"
    (is (= ["_id" "fhir_version" "meta" "resourceType" "status"]
           (xf/declared-columns (m/schema [:map {:resourceType "Flag"}
                                           [:resourceType :string] [:status :string]]))))))

(deftest search-matches-the-real-column-beside-a-stranger
  (doseq [query-mode [:sql :xtql]]
    (testing (str query-mode)
      (let [store (core-db/create-xtdb-store {:resource/schemas [registry-observation-schema]
                                              :query-mode query-mode})
            tenant "decl-stranger"]
        (try
          (db/create-resource store tenant :Observation "o1"
                              {:resourceType "Observation" :status "final"
                               :identifier [{:system "sys" :value "val"}]
                               :encounter {:reference "Encounter/e1"}
                               :code {:coding [{:system "http://loinc.org" :code "b"}]}})
          (db/create-resource store tenant :Observation "o2"
                              {:resourceType "Observation" :status "final"
                               :identifier [{:system "sys" :value "other"}]})
          (let [[results failures]
                (failure-signals
                 #(vector
                   (mapv :id (db/search store tenant :Observation {"identifier" "sys|val"} stranger-registry))
                   (db/count-resources store tenant :Observation {"identifier" "sys|val"} stranger-registry)
                   (mapv :id (db/search store tenant :Observation {"encounter" "Encounter/e1"} stranger-registry))
                   (db/count-resources store tenant :Observation {"encounter" "Encounter/e1"} stranger-registry)
                   (mapv :id (db/search store tenant :Observation {"code" "a,b"} stranger-registry))
                   (db/count-resources store tenant :Observation {"code" "a,b"} stranger-registry)))]
            (is (= [["o1"] 1 ["o1"] 1 ["o1"] 1] results))
            (is (empty? failures) "no search was swallowed as a planning failure"))
          (finally (close-store-nodes! store)))))))

;; ---------------------------------------------------------------------------
;; _sort on fields the registry does not name
;; ---------------------------------------------------------------------------

(defn- sort-ignored-signals
  "Runs `f` and returns [its value, query-failed signals, sort-field-ignored
   signals]."
  [f]
  (let [{:keys [value signals error]} (t/with-signals (f))]
    (when error (throw error))
    [value
     (filterv #(contains? failure-signal-ids (:id %)) signals)
     (filterv #(= ::core-db/sort-field-ignored (:id %)) signals)]))

(deftest sort-on-resource-level-and-unknown-fields
  (doseq [query-mode [:sql :xtql]]
    (testing (str query-mode)
      (let [store (core-db/create-xtdb-store {:resource/schemas schemas :query-mode query-mode})
            tenant "decl-sort"]
        (try
          ;; Created out of id order, so a system-time sort is not an id sort.
          (db/create-resource store tenant :Patient "p2" {:resourceType "Patient" :active true})
          (db/create-resource store tenant :Patient "p1" {:resourceType "Patient" :active true})
          (testing "_lastUpdated sorts by system time, whatever the registry declares"
            (doseq [reg [search-registry nil]]
              (let [[asc f1 i1] (sort-ignored-signals
                                 #(mapv :id (db/search store tenant :Patient {"_sort" "_lastUpdated"} reg)))
                    [desc f2 i2] (sort-ignored-signals
                                  #(mapv :id (db/search store tenant :Patient {"_sort" "-_lastUpdated"} reg)))]
                (is (= ["p2" "p1"] asc))
                (is (= ["p1" "p2"] desc))
                (is (empty? (concat f1 f2 i1 i2))))))
          (testing "_id sorts by id without a registry entry"
            (is (= ["p1" "p2"] (mapv :id (db/search store tenant :Patient {"_sort" "_id"} search-registry))))
            (is (= ["p2" "p1"] (mapv :id (db/search store tenant :Patient {"_sort" "-_id"} search-registry)))))
          (testing "an unknown sort field is dropped with a warn and the rows still come back"
            (let [[ids failures ignored]
                  (sort-ignored-signals
                   #(mapv :id (db/search store tenant :Patient {"_sort" "nonsense"} search-registry)))]
              (is (= #{"p1" "p2"} (set ids)))
              (is (= 2 (count ids)))
              (is (empty? failures))
              (is (= ["nonsense"] (mapv #(get-in % [:data :field]) ignored)))))
          (testing "the known fields of a mixed sort still order the page"
            (let [[ids failures] (sort-ignored-signals
                                  #(mapv :id (db/search store tenant :Patient
                                                        {"_sort" "nonsense,-_lastUpdated"} search-registry)))]
              (is (= ["p1" "p2"] ids))
              (is (empty? failures))))
          (finally (close-store-nodes! store)))))))
