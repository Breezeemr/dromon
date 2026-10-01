(ns fhir-store-xtdb2.unpaired-surrogate-test
  "A string holding an unpaired UTF-16 surrogate cannot be stored unchanged:
   every String -> UTF-8 step on the way into XTDB turns it into \"?\", and a
   reader cannot tell that from a real one. The store refuses such a write on
   every path instead, naming the element but never the value."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as jdbc]
            [xtdb.api :as xt]
            [xtdb.serde :as serde]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store-xtdb2.transform :as xf]
            [fhir-store.lifecycle :as lc]
            [fhir-store.protocol :as db])
  (:import [java.nio.charset StandardCharsets]
           [java.time Instant]
           [org.postgresql.core Encoding]))

(def ^:private high (str (char 0xD83D)))
(def ^:private low (str (char 0xDE00)))

(def ^:private lone-high
  "Practitioner family name carrying a high surrogate with no low half."
  (str "Rey" high "es"))

(def ^:private emoji
  "A well-formed pair (U+1F600), which UTF-8 encodes and XTDB keeps."
  (str "Rey" high low "es"))

(defn- utf16-units [s] (mapv #(format "%04X" (int %)) s))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defmacro ^:private with-store [[sym opts] & body]
  `(let [~sym (core-db/create-xtdb-store ~opts)]
     (try ~@body (finally (close-store-nodes! ~sym)))))

(defn- refusal
  "The ex-info carrying :fhir/status that calling `f` threw, or nil when it
   returned normally. Walks the cause chain the way the server's exception
   middleware does."
  [f]
  (try
    (f)
    nil
    (catch Exception e
      (loop [x e]
        (cond
          (nil? x) (throw e)
          (:fhir/status (ex-data x)) x
          :else (recur (ex-cause x)))))))

(defn- practitioner [family]
  {:resourceType "Practitioner" :name [{:family family :given ["Ana"]}]})

(defn- assert-refused
  "The refusal names `paths` and nothing of the value."
  [e paths]
  (is (some? e) "the write was refused")
  (when e
    (is (= 400 (:fhir/status (ex-data e))))
    (is (= "invalid" (:fhir/code (ex-data e))))
    (is (= paths (:fhir/expression (ex-data e))))
    (doseq [p paths]
      (is (str/includes? (ex-message e) p) "the message names the element"))
    (let [said (str (ex-message e) (pr-str (ex-data e)))]
      (is (not (str/includes? said "Rey")) "the value is not repeated")
      (is (not (str/includes? said high)) "the code unit is not repeated"))))

;; ---------------------------------------------------------------------------
;; Where the replacement happens (XTDB 2.2.0-beta3)
;; ---------------------------------------------------------------------------

(deftest xtdb-replaces-unpaired-surrogates-silently
  ;; Characterizes the engine, not the store. If this starts failing after an
  ;; XTDB upgrade, the engine now keeps or rejects the code unit: re-check
  ;; whether the store's refusal is still needed (it remains safe either way).
  (testing "the store's own encoder keeps the code unit"
    (let [encode (:default (xf/build-storage-encoders []))]
      (is (= (utf16-units lone-high)
             (utf16-units (get-in (encode (practitioner lone-high)) [:name 0 "family"]))))))

  (testing "pgjdbc's parameter encoding replaces it on the client side"
    (is (= "Rey?es" (String. (.encode (Encoding/getJVMEncoding "UTF-8") lone-high)
                             StandardCharsets/UTF_8))))

  (testing "a :put-docs transit payload still carries it as an escape"
    (is (str/includes? (String. ^bytes (serde/write-transit-seq [{:s lone-high}] :json)
                                StandardCharsets/UTF_8)
                       "\\uD83D")))

  (with-open [node (core-db/start-node {})]
    (xt/execute-tx node [[:sql "INSERT INTO probe (_id, s) VALUES (?, ?)" ["sql" lone-high]]])
    (with-open [conn (jdbc/get-connection node)]
      (xt/execute-tx conn [[:sql "INSERT INTO probe (_id, n) VALUES (?, ?)"
                            ["struct" {"family" lone-high}]]]))
    (xt/execute-tx node [[:put-docs :probe {:xt/id "put-docs" :s lone-high}]])
    (xt/execute-tx node [[:sql "INSERT INTO probe (_id, s) VALUES (?, ?)" ["pair" emoji]]])
    (let [rows (into {} (map (juxt :xt/id identity))
                     (xt/q node "SELECT _id, s, n FROM probe"))]
      (testing "an [:sql ...] parameter is stored as '?'"
        (is (= "Rey?es" (get-in rows ["sql" :s])))
        (is (= "Rey?es" (get-in rows ["struct" :n :family]))))
      (testing "so is :put-docs: the server's Arrow UTF-8 write replaces it too"
        (is (= "Rey?es" (get-in rows ["put-docs" :s]))))
      (testing "a well-formed pair round-trips"
        (is (= emoji (get-in rows ["pair" :s])))))))

;; ---------------------------------------------------------------------------
;; Detection
;; ---------------------------------------------------------------------------

(deftest unpaired-surrogate-detection
  (testing "unpaired-surrogate?"
    (is (false? (xf/unpaired-surrogate? "")))
    (is (false? (xf/unpaired-surrogate? "Reyes")))
    (is (false? (xf/unpaired-surrogate? emoji)) "a high-low pair is well formed")
    (is (true? (xf/unpaired-surrogate? lone-high)))
    (is (true? (xf/unpaired-surrogate? (str "Reyes" high))) "high at the end")
    (is (true? (xf/unpaired-surrogate? (str "Rey" low "es"))) "lone low")
    (is (true? (xf/unpaired-surrogate? (str low high))) "a reversed pair is two lone units")
    (is (true? (xf/unpaired-surrogate? (str high high low))) "the first high has no partner"))

  (testing "unpaired-surrogate-paths"
    (is (= [] (xf/unpaired-surrogate-paths "Practitioner" (practitioner emoji))))
    (is (= ["Practitioner.name[0].family"]
           (xf/unpaired-surrogate-paths "Practitioner" (practitioner lone-high))))
    (is (= ["Practitioner.name[1].given[2]"]
           (xf/unpaired-surrogate-paths
            "Practitioner" {:name [{:family "A"} {:given ["B" "C" lone-high]}]})))
    (is (= ["Practitioner.name[0].family"]
           (xf/unpaired-surrogate-paths "Practitioner" {:name [{"family" lone-high}]}))
        "string keys, as nested maps carry them after encoding")
    (is (= ["Practitioner.name[0]"]
           (xf/unpaired-surrogate-paths "Practitioner" {:name [{(keyword lone-high) "x"}]}))
        "an unencodable element NAME is reported at its parent, never spelled out")
    (is (= ["Practitioner.name[0].family"]
           (xf/unpaired-surrogate-paths "Practitioner" {:name (list {:family lone-high})}))
        "any sequential, not just vectors")
    (is (= #{"Practitioner.name[0].family" "Practitioner.text.div"}
           (set (xf/unpaired-surrogate-paths
                 "Practitioner" (assoc (practitioner lone-high)
                                       :text {:status "generated"
                                              :div (str "<div>" lone-high "</div>")}))))
        "every offending element, e.g. a narrative generated from the name")))

;; ---------------------------------------------------------------------------
;; Every write path refuses, writes nothing, and names the element
;; ---------------------------------------------------------------------------

(def ^:private family-path ["Practitioner.name[0].family"])

(deftest create-refuses-unpaired-surrogate
  (doseq [mode [:sql :xtql]]
    (testing (str mode)
      (with-store [store {:query-mode mode}]
        (assert-refused (refusal #(db/create-resource store "t" :Practitioner "p1"
                                                      (practitioner lone-high)))
                        family-path)
        (is (nil? (db/read-resource store "t" :Practitioner "p1")) "nothing was written")))))

(deftest update-refuses-unpaired-surrogate
  (doseq [mode [:sql :xtql]]
    (testing (str mode)
      (with-store [store {:query-mode mode}]
        (db/create-resource store "t" :Practitioner "p1" (practitioner "Reyes"))
        (assert-refused (refusal #(db/update-resource store "t" :Practitioner "p1"
                                                      (practitioner lone-high)))
                        family-path)
        (let [current (db/read-resource store "t" :Practitioner "p1")]
          (is (= "Reyes" (get-in current [:name 0 :family])) "the stored version is untouched")
          (is (= "1" (get-in current [:meta :versionId]))))))))

(deftest transaction-refuses-the-whole-bundle
  (doseq [mode [:sql :xtql]]
    (testing (str mode)
      (with-store [store {:query-mode mode}]
        (let [e (refusal #(db/transact-transaction
                           store "t"
                           [{:request {:method "PUT" :url "Practitioner/ok"}
                             :resource (practitioner "Reyes")}
                            {:request {:method "PUT" :url "Practitioner/bad"}
                             :resource (practitioner lone-high)}]))]
          (assert-refused e family-path)
          (is (= "bad" (:id (ex-data e))) "names which entry"))
        (is (nil? (db/read-resource store "t" :Practitioner "ok"))
            "the transaction is atomic: its valid entry was not written either")
        (is (nil? (db/read-resource store "t" :Practitioner "bad")))))))

(deftest batch-refuses-only-the-offending-entry
  (with-store [store {}]
    (let [res (db/transact-bundle
               store "t"
               [{:request {:method "PUT" :url "Practitioner/ok"}
                 :resource (practitioner "Reyes")}
                {:request {:method "PUT" :url "Practitioner/bad"}
                 :resource (practitioner lone-high)}])
          [ok bad] (:entry res)
          issue (-> bad :response :outcome :issue first)]
      (is (= "200 OK" (-> ok :response :status)))
      (is (= "400 Bad Request" (-> bad :response :status)))
      (is (= family-path (:expression issue)))
      (is (not (str/includes? (str (:diagnostics issue)) "Rey")))
      (is (= "Reyes" (get-in (db/read-resource store "t" :Practitioner "ok") [:name 0 :family])))
      (is (nil? (db/read-resource store "t" :Practitioner "bad"))))))

(deftest put-valid-time-refuses-unpaired-surrogate
  (with-store [store {}]
    (assert-refused (refusal #(db/put-valid-time store "t" :Practitioner "p1"
                                                 (practitioner lone-high)
                                                 {:valid-from (Instant/parse "2026-01-01T00:00:00Z")}))
                    family-path)
    (is (empty? (db/resource-timeline store "t" :Practitioner "p1" nil)) "nothing was written")))

(deftest an-unencodable-id-is-refused-without-repeating-it
  (testing "the store refuses it before anything runs"
    (with-store [store {}]
      (let [e (refusal #(db/create-resource store "t" :Practitioner (str "p" high)
                                            (practitioner "Reyes")))]
        (is (= 400 (:fhir/status (ex-data e))))
        (is (str/includes? (ex-message e) "the id"))
        (is (not (str/includes? (ex-message e) high)))
        (is (not (contains? (ex-data e) :id)) "the id is the value, so ex-data omits it"))))

  (testing "encode-resource-doc refuses it too, for callers that encode directly"
    (let [e (refusal #(core-db/encode-resource-doc :Practitioner (str "p" high)
                                                   (practitioner "Reyes")
                                                   (xf/build-storage-encoders [])))]
      (is (= ["Practitioner.id"] (:fhir/expression (ex-data e))))
      (is (not (contains? (ex-data e) :id))))))

(deftest well-formed-text-is-stored-exactly
  (doseq [mode [:sql :xtql]]
    (testing (str mode)
      (with-store [store {:query-mode mode}]
        (db/create-resource store "t" :Practitioner "p1" (practitioner emoji))
        (is (= (utf16-units emoji)
               (utf16-units (get-in (db/read-resource store "t" :Practitioner "p1")
                                    [:name 0 :family]))))))))

;; ---------------------------------------------------------------------------
;; Reads, searches and deletes refuse too: pgjdbc would send "?", which
;; MATCHES a stored one
;; ---------------------------------------------------------------------------

(def ^:private bad-id (str "p" high))

(defn- seed-legacy-row!
  "Writes Practitioner/p? with language \"Rey?es\" the way a write did before
   the store refused lone surrogates: straight through pgjdbc, which turns the
   lone code unit in both the id and the value into \"?\"."
  [store]
  (db/create-tenant store "t" {:if-exists :ignore})
  (let [{:keys [node]} (get @(:nodes store) "t")]
    (xt/execute-tx node [[:sql "INSERT INTO \"practitioner\" (_id, fhir_version, \"resourceType\", \"language\") VALUES (?, ?, ?, ?)"
                          [bad-id "1" "Practitioner" lone-high]]]))
  (is (= "Rey?es" (:language (db/read-resource store "t" :Practitioner "p?")))
      "the legacy row exists under the \"?\" spellings"))

(defn- assert-input-refused
  "The refusal names `what`, carries `location` (nil for none), and repeats
   nothing of the offending text."
  [e what location]
  (is (some? e) "the call was refused")
  (when e
    (is (= 400 (:fhir/status (ex-data e))))
    (is (= "invalid" (:fhir/code (ex-data e))))
    (is (str/includes? (ex-message e) what) "the message names the input")
    (is (= location (:fhir/location (ex-data e))))
    (let [said (str (ex-message e) (pr-str (ex-data e)))]
      (is (not (str/includes? said "Rey")) "the value is not repeated")
      (is (not (str/includes? said high)) "the code unit is not repeated"))))

(deftest reads-by-an-unencodable-id-are-refused
  (with-store [store {}]
    (seed-legacy-row! store)
    (doseq [[label f] [["read"        #(db/read-resource store "t" :Practitioner bad-id)]
                       ["vread"       #(db/vread-resource store "t" :Practitioner bad-id "1")]
                       ["deleted?"    #(db/resource-deleted? store "t" :Practitioner bad-id)]
                       ["history"     #(db/history store "t" :Practitioner bad-id)]
                       ["read-as-of"  #(db/read-as-of store "t" :Practitioner bad-id {})]
                       ["timeline"    #(db/resource-timeline store "t" :Practitioner bad-id nil)]]]
      (testing label
        (assert-input-refused (refusal f) "the id" nil)))

    (testing "vread by an unencodable version id"
      (assert-input-refused (refusal #(db/vread-resource store "t" :Practitioner "p?" (str "1" high)))
                            "the version id" nil))))

(deftest deletes-by-an-unencodable-id-are-refused
  (with-store [store {}]
    (seed-legacy-row! store)
    (assert-input-refused (refusal #(db/delete-resource store "t" :Practitioner bad-id))
                          "the id" nil)
    (assert-input-refused (refusal #(db/close-valid-time store "t" :Practitioner bad-id
                                                         (Instant/parse "2026-01-01T00:00:00Z")))
                          "the id" nil)
    (is (some? (db/read-resource store "t" :Practitioner "p?"))
        "Practitioner/p? was not deleted in the unencodable id's place")))

(deftest searches-for-unencodable-text-are-refused
  (doseq [mode [:sql :xtql]]
    (testing (str mode)
      (with-store [store {:query-mode mode}]
        (seed-legacy-row! store)
        (is (= ["p?"] (mapv :id (db/search store "t" :Practitioner {"language" "Rey?es"} nil)))
            "what pgjdbc would have turned the search into does match the legacy row")
        (assert-input-refused (refusal #(db/search store "t" :Practitioner {"language" lone-high} nil))
                              "parameter 'language'" ["http.language"])
        (assert-input-refused (refusal #(db/count-resources store "t" :Practitioner {"language" lone-high} nil))
                              "parameter 'language'" ["http.language"]))))

  (with-store [store {}]
    (seed-legacy-row! store)
    (testing "the temporal searches"
      (assert-input-refused (refusal #(db/search-as-of store "t" :Practitioner {"language" lone-high} nil {}))
                            "parameter 'language'" ["http.language"])
      (assert-input-refused (refusal #(db/count-as-of-basis store "t" :Practitioner {"language" lone-high} nil {}))
                            "parameter 'language'" ["http.language"]))

    (testing "history-type params"
      (assert-input-refused (refusal #(db/history-type store "t" :Practitioner {"_since" (str "2026" high)}))
                            "parameter '_since'" ["http._since"]))

    (testing "a repeated parameter, one of whose values is the problem"
      (assert-input-refused (refusal #(db/search store "t" :Practitioner
                                                 {:language ["Reyes" lone-high] "gender" "female"} nil))
                            "parameter 'language'" ["http.language"]))

    (testing "an unencodable parameter NAME is counted, never spelled out"
      (assert-input-refused (refusal #(db/search store "t" :Practitioner {(str "lang" high) "x"} nil))
                            "a parameter's name" nil))))

(deftest an-unencodable-resource-type-is-refused-before-its-table-is-declared
  (with-store [store {}]
    (db/create-tenant store "t" nil)
    (let [bad-type (keyword (str "Practi" high "tioner"))]
      (doseq [[label f] [["read"   #(db/read-resource store "t" bad-type "p1")]
                         ["search" #(db/search store "t" bad-type {} nil)]
                         ["create" #(db/create-resource store "t" bad-type "p1" {:language "x"})]
                         ["count-as-of" #(db/count-as-of store "t" bad-type {})]]]
        (testing label
          (let [e (refusal f)]
            (assert-input-refused e "the resource type" nil)
            (is (not (contains? (ex-data e) :resource-type)))))))
    (is (not-any? xf/unencodable? @(:declared (get @(:nodes store) "t")))
        "no table was declared under the unencodable name")))

(deftest bundles-refuse-an-unencodable-entry-id
  (with-store [store {}]
    (seed-legacy-row! store)
    (testing "a transaction refuses the whole Bundle"
      (assert-input-refused
       (refusal #(db/transact-transaction
                  store "t"
                  [{:request {:method "PUT" :url "Practitioner/ok"} :resource (practitioner "Reyes")}
                   {:request {:method "DELETE" :url (str "Practitioner/" bad-id)}}]))
       "the id" nil)
      (is (nil? (db/read-resource store "t" :Practitioner "ok")))
      (is (some? (db/read-resource store "t" :Practitioner "p?"))))

    (testing "a batch refuses only that entry"
      (let [res (db/transact-bundle
                 store "t"
                 [{:request {:method "GET" :url (str "Practitioner/" bad-id)}}
                  {:request {:method "GET" :url "Practitioner/p?"}}])
            [bad ok] (:entry res)]
        (is (= "400 Bad Request" (-> bad :response :status)))
        (is (not (str/includes? (str (-> bad :response :outcome :issue first :diagnostics)) high)))
        (is (= "200 OK" (-> ok :response :status)))))))

;; ---------------------------------------------------------------------------
;; Lifecycle tx-ops travel in the same transaction, through the same encoders
;; ---------------------------------------------------------------------------

(defn- lifecycle
  "Prepares with `prepare-fn` and contributes `(ops-fn write)` as tx-ops."
  [prepare-fn ops-fn]
  (reify lc/IWriteLifecycle
    (prepare [_ write] (prepare-fn (:resource write)))
    (tx-ops [_ write] (ops-fn write))
    (after-commit [_ _] nil)))

(defn- repair-family
  "A host that repairs the stored body: the family name becomes \"Reyes\"."
  [resource]
  (assoc-in resource [:name 0 :family] "Reyes"))

(defn- submitted-probe
  "One probe row holding the SUBMITTED body's family name."
  [{:keys [id submitted]}]
  [[:put-docs :lifecycle_probe {:xt/id id :note (get-in submitted [:name 0 :family])}]])

(defn- own-data-probe
  "A clean op, then two whose own text is unencodable: a SQL arg and a doc key."
  [{:keys [id]}]
  [[:put-docs :lifecycle_probe {:xt/id id :note "fine"}]
   [:sql "INSERT INTO lifecycle_probe (_id, note) VALUES (?, ?)" [(str id "-sql") lone-high]]
   [:put-docs :lifecycle_probe {:xt/id (str id "-doc") (keyword (str "k" high)) "v"}]])

(defn- probe-notes [store]
  (let [{:keys [node]} (get @(:nodes store) "t")]
    (xt/execute-tx node [[:sql "CREATE TABLE lifecycle_probe (_id, note)"]])
    (into {} (map (juxt :xt/id :note)) (xt/q node "SELECT _id, note FROM lifecycle_probe"))))

(deftest lifecycle-tx-ops-carrying-submitted-text-are-refused-as-the-clients
  (doseq [mode [:sql :xtql]]
    (testing (str mode)
      (with-store [store {:query-mode mode
                          :resource/lifecycle (lifecycle repair-family submitted-probe)}]
        (testing "create"
          (assert-refused (refusal #(db/create-resource store "t" :Practitioner "p1"
                                                        (practitioner lone-high)))
                          family-path))
        (db/create-resource store "t" :Practitioner "p2" (practitioner "Reyes"))
        (testing "update"
          (assert-refused (refusal #(db/update-resource store "t" :Practitioner "p2"
                                                        (practitioner lone-high)))
                          family-path))
        (testing "transaction"
          (assert-refused (refusal #(db/transact-transaction
                                     store "t"
                                     [{:request {:method "PUT" :url "Practitioner/p3"}
                                       :resource (practitioner lone-high)}]))
                          family-path))
        (is (nil? (db/read-resource store "t" :Practitioner "p1")))
        (is (= "1" (get-in (db/read-resource store "t" :Practitioner "p2") [:meta :versionId])))
        (is (nil? (db/read-resource store "t" :Practitioner "p3")))
        (is (= {"p2" "Reyes"} (probe-notes store))
            "only the clean write's probe row landed; no \"Rey?es\"")))))

(deftest lifecycle-tx-ops-with-their-own-unencodable-text-are-a-host-error
  (doseq [mode [:sql :xtql]]
    (testing (str mode)
      (with-store [store {:query-mode mode
                          :resource/lifecycle (lifecycle identity own-data-probe)}]
        (let [e (refusal #(db/create-resource store "t" :Practitioner "p1"
                                              (practitioner "Reyes")))]
          (is (= 500 (:fhir/status (ex-data e))))
          (is (= "exception" (:fhir/code (ex-data e))))
          (is (= [1 2] (:lifecycle/tx-op-indexes (ex-data e)))
              "SQL args and doc keys are both walked; the clean op is not named")
          (is (not (str/includes? (str (ex-message e) (pr-str (ex-data e))) high))))
        (is (nil? (db/read-resource store "t" :Practitioner "p1")))
        (is (empty? (probe-notes store)) "none of the lifecycle's ops landed")))))

(deftest a-body-is-refused-by-element-path-before-its-lifecycle-ops-are-checked
  (with-store [store {:resource/lifecycle (lifecycle identity submitted-probe)}]
    (let [e (refusal #(db/create-resource store "t" :Practitioner "p1"
                                          (practitioner lone-high)))]
      (assert-refused e family-path)
      (is (= 400 (:fhir/status (ex-data e))) "the client's text, not a host error"))))
