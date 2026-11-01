(ns server.scope-granular-test
  "SMART v2 granular (query-qualified) scopes: the grammar, the union a set of
   grants makes, the in-memory match, the scope-filtering store, and the whole
   router with scope enforcement on."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [buddy.sign.jwt :as jwt]
            [fhir-store.mock.core :as mock]
            [fhir-store.protocol :as db]
            [hato.client :as hc]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.util :as mu]
            [reitit.ring :as ring]
            [server.auth :as auth]
            [server.core :as sc]
            [server.router :as router]
            [server.scope :as scope])
  (:import [java.io ByteArrayInputStream InputStream]))

(def ^:private tenant "default")

(def ^:private obs-cat "http://terminology.hl7.org/CodeSystem/observation-category")
(def ^:private cond-cat "http://terminology.hl7.org/CodeSystem/condition-category")

(def ^:private lab-scope
  (str "patient/Observation.rs?category=" obs-cat "|laboratory"))

(def ^:private vital-scope
  (str "patient/Observation.rs?category=" obs-cat "|vital-signs"))

;; ---------------------------------------------------------------------------
;; Grammar
;; ---------------------------------------------------------------------------

(deftest us-core-granular-scopes-all-parse
  (doseq [s scope/us-core-granular-scopes]
    (let [parsed (scope/parse-scope s)]
      (is (some? parsed) s)
      (is (= :v2 (:version parsed)))
      (is (= #{\r \s} (:permissions parsed)))
      (is (= #{"category"} (set (keys (:query parsed))))))))

(deftest parse-granular-scope
  (testing "system|code"
    (is (= {:compartment "patient" :resource "Observation" :permissions #{\r \s}
            :version :v2
            :query {"category" [{:system obs-cat :code "laboratory"}]}}
           (scope/parse-scope lab-scope))))
  (testing "comma values OR within one parameter"
    (is (= [{:system obs-cat :code "laboratory"} {:system nil :code "vital-signs"}]
           (get-in (scope/parse-scope (str "patient/Observation.rs?category="
                                           obs-cat "|laboratory,vital-signs"))
                   [:query "category"]))))
  (testing "the four token forms"
    (is (= [{:system nil :code "laboratory"}]
           (get-in (scope/parse-scope "patient/Observation.rs?category=laboratory")
                   [:query "category"])))
    (is (= [{:system obs-cat :code nil}]
           (get-in (scope/parse-scope (str "patient/Observation.rs?category=" obs-cat "|"))
                   [:query "category"])))
    (is (= [{:system nil :code "laboratory" :no-system? true}]
           (get-in (scope/parse-scope "patient/Observation.rs?category=|laboratory")
                   [:query "category"]))))
  (testing "percent-decoding is applied once, and + is not a space"
    (is (= [{:system obs-cat :code "laboratory"}]
           (get-in (scope/parse-scope (str "patient/Observation.rs?category="
                                           (str/replace obs-cat ":" "%3A") "%7Claboratory"))
                   [:query "category"])))
    (is (= [{:system nil :code "a+b"}]
           (get-in (scope/parse-scope "patient/Observation.rs?category=a+b")
                   [:query "category"])))
    (is (= [{:system nil :code "%7C"}]
           (get-in (scope/parse-scope "patient/Observation.rs?category=%257C")
                   [:query "category"]))
        "a double-encoded bar decodes to a literal %7C, not to a separator"))
  (testing "a qualified write scope keeps its letters"
    (is (= #{\c \r \u \s}
           (:permissions (scope/parse-scope (str "patient/Observation.crus?category=" obs-cat "|laboratory")))))))

(deftest malformed-granular-scopes-are-dropped-whole
  (doseq [s [;; a query is v2-only
             (str "patient/Observation.read?category=" obs-cat "|laboratory")
             (str "patient/Observation.*?category=laboratory")
             ;; and needs a concrete type
             "patient/*.rs?category=laboratory"
             ;; non-canonical letters
             "patient/Observation.sr?category=laboratory"
             ;; unparseable queries
             "patient/Observation.rs?"
             "patient/Observation.rs?category"
             "patient/Observation.rs?category="
             "patient/Observation.rs?=laboratory"
             "patient/Observation.rs?category=laboratory&"
             "patient/Observation.rs?category=a,,b"
             "patient/Observation.rs?category=|"
             "patient/Observation.rs?category=a|b|c"
             "patient/Observation.rs?category=%ZZ"
             ;; a duplicate parameter
             "patient/Observation.rs?category=a&category=b"
             ;; unsupported parameters
             "patient/Observation.rs?code=1234-5"
             "patient/Patient.rs?category=x"
             "patient/Observation.rs?category=laboratory&status=final"]]
    (is (nil? (scope/parse-scope s)) s))
  (testing "never read as the unqualified scope"
    (is (= [] (scope/parse-scopes "patient/Observation.rs?code=1234-5")))
    (is (not (scope/permitted? (scope/parse-scopes "patient/Observation.rs?code=1234-5")
                               "Observation" :read)))))

(deftest a-second-supported-parameter-ands
  (with-redefs [scope/granular-parameters {"Observation" #{"category" "status"}}]
    (is (= {"category" [{:system nil :code "laboratory"}]
            "status"   [{:system nil :code "final"}]}
           (:query (scope/parse-scope "patient/Observation.rs?category=laboratory&status=final"))))))

;; ---------------------------------------------------------------------------
;; grant-filter / unrestricted?
;; ---------------------------------------------------------------------------

(deftest grant-filter-union
  (let [lab   {"category" [{:system obs-cat :code "laboratory"}]}
        vital {"category" [{:system obs-cat :code "vital-signs"}]}]
    (testing "no granting scope"
      (is (= :none (scope/grant-filter (scope/parse-scopes lab-scope) "Condition" :read)))
      (is (= :none (scope/grant-filter (scope/parse-scopes lab-scope) "Observation" :create))))
    (testing "qualified scopes union"
      (is (= {:any-of [lab vital]}
             (scope/grant-filter (scope/parse-scopes [lab-scope vital-scope]) "Observation" :search))))
    (testing "an unqualified grant wins the union"
      (is (= :all (scope/grant-filter (scope/parse-scopes [lab-scope "patient/Observation.rs"])
                                      "Observation" :read)))
      (is (= :all (scope/grant-filter (scope/parse-scopes [lab-scope "patient/*.read"])
                                      "Observation" :read))))
    (testing "the letter decides which scopes grant"
      (is (= {:any-of [lab]}
             (scope/grant-filter (scope/parse-scopes [(str "patient/Observation.c?category=" obs-cat "|laboratory")
                                                      "patient/Observation.rs"])
                                 "Observation" :create))))))

(deftest unrestricted-tokens
  (is (scope/unrestricted? (scope/parse-scopes "user/*.*")))
  (is (scope/unrestricted? (scope/parse-scopes "system/*.read")))
  (is (scope/unrestricted? (scope/parse-scopes "patient/*.read patient/Patient.c patient/Encounter.cu")))
  (is (scope/unrestricted? (scope/parse-scopes (str "patient/*.read " lab-scope))))
  (is (not (scope/unrestricted? (scope/parse-scopes "patient/Observation.rs"))))
  (is (not (scope/unrestricted? (scope/parse-scopes lab-scope))))
  (is (not (scope/unrestricted? (scope/parse-scopes
                                 (str "patient/*.read patient/Observation.c?category=" obs-cat "|laboratory"))))))

;; ---------------------------------------------------------------------------
;; resource-matches?
;; ---------------------------------------------------------------------------

(defn- category [& codings]
  (mapv (fn [c] {:coding [c]}) codings))

(deftest token-matching
  (let [lab-obs  {:resourceType "Observation" :category (category {:system obs-cat :code "laboratory"})}
        bare-obs {:resourceType "Observation" :category (category {:code "laboratory"})}
        q        (fn [& tokens] {"category" (vec tokens)})]
    (testing "system|code is exact"
      (is (scope/resource-matches? (q {:system obs-cat :code "laboratory"}) lab-obs))
      (is (not (scope/resource-matches? (q {:system obs-cat :code "vital-signs"}) lab-obs)))
      (is (not (scope/resource-matches? (q {:system "http://other" :code "laboratory"}) lab-obs))))
    (testing "code matches in any system"
      (is (scope/resource-matches? (q {:system nil :code "laboratory"}) lab-obs))
      (is (scope/resource-matches? (q {:system nil :code "laboratory"}) bare-obs)))
    (testing "system| matches any code in the system"
      (is (scope/resource-matches? (q {:system obs-cat :code nil}) lab-obs))
      (is (not (scope/resource-matches? (q {:system obs-cat :code nil}) bare-obs))))
    (testing "|code matches only a coding without a system"
      (is (scope/resource-matches? (q {:system nil :code "laboratory" :no-system? true}) bare-obs))
      (is (not (scope/resource-matches? (q {:system nil :code "laboratory" :no-system? true}) lab-obs))))
    (testing "values OR"
      (is (scope/resource-matches? (q {:system obs-cat :code "vital-signs"}
                                      {:system obs-cat :code "laboratory"})
                                   lab-obs)))
    (testing "a resource without the element never matches"
      (is (not (scope/resource-matches? (q {:system nil :code "laboratory"})
                                        {:resourceType "Observation"}))))))

;; ---------------------------------------------------------------------------
;; ScopeFilteringStore
;; ---------------------------------------------------------------------------

(def ^:private obs-registry
  {"category" {:type "token" :columns [{:col "category" :fhir-type "CodeableConcept" :array? true}]}
   "patient"  {:type "reference" :target ["Patient"] :columns [{:col "subject"}]}
   "subject"  {:type "reference" :columns [{:col "subject"}]}
   "performer" {:type "reference" :columns [{:col "performer" :array? true}]}})

(def ^:private cond-registry
  {"category" {:type "token" :columns [{:col "category" :fhir-type "CodeableConcept" :array? true}]}
   "patient"  {:type "reference" :target ["Patient"] :columns [{:col "subject"}]}})

(def ^:private registries
  {"Observation" obs-registry
   "Condition"   cond-registry
   "Practitioner" {"name" {:type "string" :columns [{:col "name"}]}}
   "Patient"     {}})

(defn- obs [id & codings]
  {:resourceType "Observation" :id id
   :subject {:reference "Patient/123"}
   :performer [{:reference "Practitioner/pr-1"}]
   :category (apply category codings)})

(def ^:private lab {:system obs-cat :code "laboratory"})
(def ^:private vital {:system obs-cat :code "vital-signs"})

(defn- seeded []
  (let [store (mock/create-mock-store {:registries registries})]
    (db/create-resource store tenant :Observation "lab-1" (obs "lab-1" lab))
    (db/create-resource store tenant :Observation "vital-1" (obs "vital-1" vital))
    (db/create-resource store tenant :Observation "both-1" (obs "both-1" lab {:system "http://x" :code "other"}))
    (db/create-resource store tenant :Practitioner "pr-1" {:resourceType "Practitioner" :id "pr-1"})
    (db/create-resource store tenant :Condition "c-1"
                        {:resourceType "Condition" :id "c-1" :subject {:reference "Patient/123"}
                         :category (category {:system cond-cat :code "problem-list-item"})})
    store))

(defn- fstore
  ([base claim] (fstore base claim #{"Observation"}))
  ([base claim search-types]
   (scope/filtering-store base {:scopes (scope/parse-scopes claim)
                                :search-types search-types
                                :all-registries registries})))

(defn- status-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:fhir/status (ex-data e)))))

(defn- ids [rs] (set (map :id rs)))

(deftest store-search-pushes-the-filter-down
  (let [store (fstore (seeded) lab-scope)]
    (is (= #{"lab-1" "both-1"} (ids (db/search store tenant :Observation {} obs-registry))))
    (is (= 2 (db/count-resources store tenant :Observation {} obs-registry)))
    (testing "a client category intersects the grant"
      (is (= #{} (ids (db/search store tenant :Observation
                                 {"category" (str obs-cat "|vital-signs")} obs-registry))))
      (is (= 0 (db/count-resources store tenant :Observation
                                   {"category" (str obs-cat "|vital-signs")} obs-registry)))
      (is (= #{"both-1"} (ids (db/search store tenant :Observation
                                         {"category" "http://x|other"} obs-registry)))
          "the grant and the client's category may match different codings")
      (is (= #{"lab-1" "both-1"} (ids (db/search store tenant :Observation
                                                 {:category "laboratory"} obs-registry)))))
    (testing "two qualified grants union"
      (is (= #{"lab-1" "vital-1" "both-1"}
             (ids (db/search (fstore (seeded) [lab-scope vital-scope])
                             tenant :Observation {} obs-registry)))))
    (testing "a type with no grant returns nothing"
      (is (= [] (db/search store tenant :Condition {} cond-registry)))
      (is (= 0 (db/count-resources store tenant :Condition {} cond-registry))))
    (testing "a registry that cannot express the filter yields nothing"
      (is (= [] (db/search store tenant :Observation {} (dissoc obs-registry "category")))))))

(deftest store-reads-refuse-what-the-grant-does-not-admit
  (let [store (fstore (seeded) lab-scope)]
    (is (= "lab-1" (:id (db/read-resource store tenant :Observation "lab-1"))))
    (is (= 403 (status-of #(db/read-resource store tenant :Observation "vital-1"))))
    (is (= 403 (status-of #(db/vread-resource store tenant :Observation "vital-1" "1"))))
    (is (= "lab-1" (:id (db/vread-resource store tenant :Observation "lab-1" "1"))))
    (is (nil? (db/read-resource store tenant :Observation "absent")))
    (testing "an ungranted type is refused"
      (is (= 403 (status-of #(db/read-resource store tenant :Condition "c-1")))))))

(deftest store-history-omits-versions-the-grant-does-not-admit
  (let [base  (seeded)
        _     (db/update-resource base tenant :Observation "lab-1" (obs "lab-1" vital))
        store (fstore base lab-scope)]
    (is (= ["1"] (mapv #(get-in % [:meta :versionId]) (db/history store tenant :Observation "lab-1"))))
    (is (= #{"lab-1" "both-1"} (ids (db/history-type store tenant :Observation {}))))
    (is (= [] (db/history store tenant :Condition "c-1")))))

(deftest store-includes-are-judged-under-their-own-type
  (testing "an included type is read-gated: no grant, no rows"
    (is (= [] (db/search (fstore (seeded) lab-scope) tenant :Practitioner {"_id" "pr-1"} nil))))
  (testing "a read grant is enough for an included type"
    (is (= #{"pr-1"} (ids (db/search (fstore (seeded) [lab-scope "patient/Practitioner.r"])
                                     tenant :Practitioner {"_id" "pr-1"} nil)))))
  (testing "but a type the request searches directly needs the search letter"
    (is (= [] (db/search (fstore (seeded) [lab-scope "patient/Practitioner.r"] #{"Practitioner"})
                         tenant :Practitioner {"_id" "pr-1"} nil)))))

(deftest store-qualified-writes
  (let [scope-claim (str "patient/Observation.crud?category=" obs-cat "|laboratory")]
    (testing "a create must match the grant"
      (let [store (fstore (seeded) scope-claim)]
        (is (= 403 (status-of #(db/create-resource store tenant :Observation "n-1" (obs "n-1" vital)))))
        (is (nil? (status-of #(db/create-resource store tenant :Observation "n-2" (obs "n-2" lab)))))))
    (testing "an update must match, and so must the version it replaces"
      (let [base (seeded) store (fstore base scope-claim)]
        (is (= 403 (status-of #(db/update-resource store tenant :Observation "lab-1" (obs "lab-1" vital)))))
        (is (= 403 (status-of #(db/update-resource store tenant :Observation "vital-1" (obs "vital-1" lab)))))
        (is (= obs-cat (get-in (db/read-resource base tenant :Observation "vital-1")
                               [:category 0 :coding 0 :system])))
        (is (nil? (status-of #(db/update-resource store tenant :Observation "lab-1" (obs "lab-1" lab)))))))
    (testing "a delete must name a resource the grant admits"
      (let [base (seeded) store (fstore base scope-claim)]
        (is (= 403 (status-of #(db/delete-resource store tenant :Observation "vital-1"))))
        (is (some? (db/read-resource base tenant :Observation "vital-1")))
        (is (nil? (status-of #(db/delete-resource store tenant :Observation "lab-1"))))))
    (testing "an unqualified grant writes freely"
      (is (nil? (status-of #(db/create-resource (fstore (seeded) "patient/Observation.c")
                                                tenant :Observation "n-3" (obs "n-3" vital))))))))

(defn- entry
  ([method url] {:request {:method method :url url}})
  ([method url resource] {:request {:method method :url url} :resource resource}))

(deftest store-gates-bundle-entries
  (let [claim (str "patient/Observation.crs?category=" obs-cat "|laboratory")
        gated (fn [base] (scope/filtering-store base {:scopes (scope/parse-scopes claim)
                                                      :search-types :all
                                                      :all-registries registries
                                                      :gate-types? true}))]
    (testing "a transaction fails whole on an entry the grant does not admit"
      (let [base (seeded)]
        (is (= 403 (status-of #(db/transact-transaction (gated base) tenant
                                                        [(entry "POST" "Observation" (obs nil lab))
                                                         (entry "POST" "Observation" (obs nil vital))]))))
        (is (= 3 (count (db/search base tenant :Observation {} obs-registry))))))
    (testing "an entry of an ungranted type is refused at the base"
      (is (= 403 (status-of #(db/transact-transaction (gated (seeded)) tenant
                                                      [(entry "POST" "Condition"
                                                              {:resourceType "Condition"})])))))
    (testing "a conditional entry cannot be judged under a qualified grant"
      (is (= 403 (status-of #(db/transact-transaction (gated (seeded)) tenant
                                                      [(entry "POST" "Observation?identifier=x"
                                                              (obs nil lab))])))))))

;; Every search the base store receives, with the rows it answers regardless.
(defn- recording-store [rows calls]
  (reify db/IFHIRStore
    (search [_ _ _ params _] (swap! calls conj params) rows)
    (count-resources [_ _ _ params _] (swap! calls conj params) (count rows))))

(deftest store-pushdown-reaches-the-base-as-one-value-or-an-and
  (let [grant (str obs-cat "|laboratory")]
    (testing "with no client category the base receives one plain string"
      (let [calls (atom [])]
        (db/search (fstore (recording-store [] calls) lab-scope) tenant :Observation
                   {"patient" "123"} obs-registry)
        (is (= [{"patient" "123" "category" grant}] @calls))))
    (testing "a client category, under either key spelling, is ANDed as a repeated parameter"
      (doseq [k ["category" :category]]
        (let [calls (atom [])]
          (db/count-resources (fstore (recording-store [] calls) lab-scope) tenant :Observation
                              {k "vital-signs"} obs-registry)
          (is (= [{"category" ["vital-signs" grant]}] @calls)))))
    (testing "a repeated client category keeps its values and gains the grant"
      (let [calls (atom [])]
        (db/search (fstore (recording-store [] calls) lab-scope) tenant :Observation
                   {"category" ["a" "b"]} obs-registry)
        (is (= [{"category" ["a" "b" grant]}] @calls))))
    (testing "two qualified grants on one parameter push down as one OR set"
      (let [calls (atom [])]
        (db/search (fstore (recording-store [] calls) [lab-scope vital-scope]) tenant :Observation
                   {} obs-registry)
        (is (= [{"category" (str grant "," obs-cat "|vital-signs")}] @calls))))))

(deftest store-search-does-not-pass-summary-down
  (testing "the row check needs whole rows, so a projecting store is not asked to project"
    (let [calls (atom [])
          rows  [(obs "lab-1" lab)]]
      (doseq [k ["_summary" :_summary]]
        (reset! calls [])
        (is (= #{"lab-1"} (ids (db/search (fstore (recording-store rows calls) lab-scope)
                                          tenant :Observation {k "true"} obs-registry))))
        (is (not-any? #(or (contains? % "_summary") (contains? % :_summary)) @calls)))))
  (testing "an unfiltered type keeps its _summary"
    (let [calls (atom [])]
      (db/search (fstore (recording-store [] calls) "patient/Observation.rs")
                 tenant :Observation {"_summary" "true"} obs-registry)
      (is (= [{"_summary" "true"}] @calls)))))

(deftest search-types-of-a-request
  (let [st #'scope/search-types]
    (is (= #{"Observation"} (st {} "Observation")))
    (is (= :all (st {} nil)))
    (is (= #{"Patient" "Observation"} (st {:path-params {:target-type "Observation"}} "Patient")))
    (is (= :all (st {:path-params {:target-type "*"}} "Patient")))))

(deftest only-a-post-to-the-base-is-a-bundle-post
  (let [bp? #'scope/base-bundle-post?]
    (is (bp? {:request-method :post :uri "/default/fhir"} nil))
    (is (bp? {:request-method :post :uri "/default/fhir/"} nil))
    (is (not (bp? {:request-method :post :uri "/default/fhir/_search"} nil)))
    (is (not (bp? {:request-method :post :uri "/auth/grants"} nil)))
    (is (not (bp? {:request-method :get :uri "/default/fhir"} nil)))
    (is (not (bp? {:request-method :post :uri "/default/fhir"} "Observation")))))

(deftest store-search-results-are-checked-even-when-the-base-ignores-the-pushdown
  (let [rows [(obs "lab-1" lab) (obs "vital-1" vital) (obs "none-1")]]
    (is (= #{"lab-1"} (ids (db/search (fstore (recording-store rows (atom [])) lab-scope)
                                      tenant :Observation {} obs-registry))))))

(deftest store-refuses-bundle-entries-it-cannot-judge
  (let [claim (str "patient/Observation.crs?category=" obs-cat "|laboratory")
        gated (fn [base scopes] (scope/filtering-store base {:scopes (scope/parse-scopes scopes)
                                                             :search-types :all
                                                             :all-registries registries
                                                             :gate-types? true}))]
    (testing "a url with no resource type in front"
      (doseq [url ["/Observation" "/Observation/vital-1" ""]]
        (is (= 403 (status-of #(db/transact-transaction (gated (seeded) claim) tenant
                                                        [(entry "POST" url (obs nil vital))])))
            url)))
    (testing "a search entry, which the base would not filter"
      (doseq [method ["GET" "HEAD"]]
        (is (= 403 (status-of #(db/transact-transaction (gated (seeded) claim) tenant
                                                        [(entry method "Observation")])))
            method)))
    (testing "an unsupported method"
      (is (= 403 (status-of #(db/transact-transaction (gated (seeded) claim) tenant
                                                      [(entry "TRACE" "Observation/lab-1")])))))
    (testing "a batch answers the refusal in place and runs the rest"
      (let [res (db/transact-bundle (gated (seeded) claim) tenant
                                    [(entry "GET" "/Observation/vital-1")
                                     (entry "GET" "Observation/vital-1")
                                     (entry "GET" "Observation/lab-1")])]
        (is (= ["403 Forbidden" "403 Forbidden"]
               (mapv #(get-in % [:response :status]) (take 2 (:entry res)))))
        (is (= "lab-1" (get-in (nth (:entry res) 2) [:resource :id])))))
    (testing "an unrestricted token is not held to it"
      (is (nil? (#'scope/entry-refusal (gated (seeded) "user/*.*") tenant
                                       (entry "POST" "/Observation" (obs nil vital))))))))

;; ---------------------------------------------------------------------------
;; The whole router, scope enforcement on
;; ---------------------------------------------------------------------------

(defn- server-schema [fhir-type registry]
  (mu/update-properties
   (sc/capability-schema->server-schema
    (m/schema [:map {:resourceType fhir-type
                     :interactions ["read" "vread" "create" "update" "search-type"
                                    "history-instance" "history-type"]
                     :search-params []}]))
   assoc :fhir/search-registry registry))

(def ^:private schemas
  [(server-schema "Observation" obs-registry)
   (server-schema "Condition" cond-registry)
   (server-schema "Practitioner" (get registries "Practitioner"))
   (server-schema "Patient" {})])

(def ^:private keypair
  (let [gen (java.security.KeyPairGenerator/getInstance "RSA")]
    (.initialize gen 2048)
    (.generateKeyPair gen)))

(defn- bearer [claims]
  (str "Bearer " (jwt/sign (merge {:sub "patient-user" :patient "123"} claims)
                           (.getPrivate keypair)
                           {:alg :rs256 :header {:kid "kid-1"}})))

(defn- app [store]
  (ring/ring-handler
   (router/router schemas
                  (router/default-middleware
                   store
                   (router/resolve-options {:jwks-url "http://jwks.test/.well-known/jwks.json"
                                            :keto-url "http://keto.test"
                                            :enforce-smart-scopes? true
                                            :smart-discovery {}})))
   router/default-handler))

(defn- request [method uri scope-claim & {:keys [body query]}]
  (cond-> {:request-method method
           :uri uri
           :headers (cond-> {"accept" "application/fhir+json"
                             "authorization" (bearer {:scope scope-claim})}
                      body (assoc "content-type" "application/fhir+json"))}
    query (assoc :query-string query)
    body  (assoc :body (ByteArrayInputStream. (json/write-value-as-bytes body)))))

(defn- call [store req]
  (with-redefs [auth/fetch-jwks (fn [_] {"kid-1" (.getPublic keypair)})
                hc/get (fn [_ _] {:status 200 :body {:allowed true}})]
    (let [resp ((app store) req)
          b    (:body resp)]
      (assoc resp :body (when b (json/read-value (if (instance? InputStream b) (slurp b) b)
                                                 json/keyword-keys-object-mapper))))))

(defn- entry-ids [resp mode]
  (set (keep #(when (= mode (get-in % [:search :mode])) (get-in % [:resource :id]))
             (get-in resp [:body :entry]))))

(deftest router-search-is-filtered
  (let [store (seeded)]
    (is (= #{"lab-1" "both-1"}
           (entry-ids (call store (request :get "/default/fhir/Observation" lab-scope)) "match")))
    (testing "intersected with a client category"
      (is (= #{} (entry-ids (call store (request :get "/default/fhir/Observation" lab-scope
                                                 :query (str "category=" obs-cat "|vital-signs")))
                            "match")))
      (is (= #{"both-1"} (entry-ids (call store (request :get "/default/fhir/Observation" lab-scope
                                                         :query "category=http://x|other"))
                                    "match"))))
    (testing "the total reflects the filter"
      (is (= 2 (get-in (call store (request :get "/default/fhir/Observation" lab-scope
                                            :query "_count=0"))
                       [:body :total]))))))

(deftest router-read-of-a-non-matching-resource-is-403
  (let [resp (call (seeded) (request :get "/default/fhir/Observation/vital-1" lab-scope))]
    (is (= 403 (:status resp)))
    (is (= "forbidden" (get-in resp [:body :issue 0 :code])))
    (is (= "Insufficient scope: no granted scope permits read on Observation/vital-1."
           (get-in resp [:body :issue 0 :diagnostics]))))
  (is (= 200 (:status (call (seeded) (request :get "/default/fhir/Observation/lab-1" lab-scope)))))
  (is (= 403 (:status (call (seeded) (request :get "/default/fhir/Observation/vital-1/_history/1"
                                              lab-scope))))))

(deftest router-include-of-an-ungranted-type-is-omitted
  (is (= #{} (entry-ids (call (seeded) (request :get "/default/fhir/Observation" lab-scope
                                                :query "_include=Observation:performer"))
                        "include")))
  (is (= #{"pr-1"} (entry-ids (call (seeded) (request :get "/default/fhir/Observation"
                                                      (str lab-scope " patient/Practitioner.rs")
                                                      :query "_include=Observation:performer"))
                              "include"))))

(deftest router-system-history-is-filtered
  (let [resp (call (seeded) (request :get "/default/fhir/_history" lab-scope))
        seen (set (map #(str (get-in % [:resource :resourceType]) "/" (get-in % [:resource :id]))
                       (get-in resp [:body :entry])))]
    (is (= 200 (:status resp)))
    (is (= #{"Observation/lab-1" "Observation/both-1"} seen))))

(deftest router-type-history-is-filtered
  (let [resp (call (seeded) (request :get "/default/fhir/Observation/_history" lab-scope))]
    (is (= #{"lab-1" "both-1"} (set (map #(get-in % [:resource :id]) (get-in resp [:body :entry])))))))

(deftest router-qualified-write-is-refused
  (let [claim (str "patient/Observation.crs?category=" obs-cat "|laboratory")
        store (seeded)]
    (is (= 403 (:status (call store (request :post "/default/fhir/Observation" claim
                                             :body (dissoc (obs nil vital) :id))))))
    (is (= 201 (:status (call store (request :post "/default/fhir/Observation" claim
                                             :body (dissoc (obs nil lab) :id))))))))

(deftest router-unrestricted-tokens-are-not-filtered
  (is (= #{"lab-1" "vital-1" "both-1"}
         (entry-ids (call (seeded) (request :get "/default/fhir/Observation" "patient/*.read"))
                    "match"))))

(deftest router-batch-cannot-read-around-the-grant
  (let [bundle {:resourceType "Bundle" :type "batch"
                :entry [{:request {:method "GET" :url "Observation/vital-1"}}
                        {:request {:method "GET" :url "/Observation/vital-1"}}
                        {:request {:method "GET" :url "Observation/lab-1"}}]}
        resp   (call (seeded) (request :post "/default/fhir" lab-scope :body bundle))
        entries (get-in resp [:body :entry])]
    (is (= 200 (:status resp)))
    (is (= ["403 Forbidden" "403 Forbidden"] (mapv #(get-in % [:response :status]) (take 2 entries))))
    (is (not-any? #(= "vital-1" (get-in % [:resource :id])) entries))
    (is (= "lab-1" (get-in (nth entries 2) [:resource :id])))))

(deftest router-compartment-search-needs-the-search-letter-on-its-target
  (let [cs (fn [scope-claim]
             (let [resp (call (seeded) (request :get "/default/fhir/Patient/123/Observation"
                                                scope-claim))]
               [(:status resp) (entry-ids resp "match")]))]
    (is (= [200 #{"lab-1" "both-1"}] (cs (str "patient/Patient.rs " lab-scope))))
    (is (= [200 #{}] (cs (str "patient/Patient.rs patient/Observation.r?category="
                              obs-cat "|laboratory")))
        "a read-only grant on the target type does not let a compartment search find it")))
