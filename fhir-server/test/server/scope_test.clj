(ns server.scope-test
  "Tests for SMART on FHIR scope parsing and enforcement.
   Covers SMART v1 (patient/Observation.read, user/*.write) and SMART v2
   granular (patient/Observation.rs, system/*.cruds) scope syntax."
  (:require [clojure.test :refer [deftest is testing]]
            [server.scope :as scope]))

;; ---------------------------------------------------------------------------
;; parse-scope / parse-scopes
;; ---------------------------------------------------------------------------

(deftest parse-scope-v1
  (testing "v1 .read expands to read + search"
    (is (= {:compartment "patient" :resource "Observation" :permissions #{\r \s}}
           (scope/parse-scope "patient/Observation.read"))))

  (testing "v1 .write expands to create + update + delete"
    (is (= {:compartment "user" :resource "Patient" :permissions #{\c \u \d}}
           (scope/parse-scope "user/Patient.write"))))

  (testing "v1 wildcard resource"
    (is (= {:compartment "patient" :resource "*" :permissions #{\r \s}}
           (scope/parse-scope "patient/*.read"))))

  (testing "v1 .* grants all permissions"
    (is (= #{\c \r \u \d \s}
           (:permissions (scope/parse-scope "user/*.*"))))))

(deftest parse-scope-v2
  (testing "v2 granular letters parse to the literal letter set"
    (is (= {:compartment "patient" :resource "Observation" :permissions #{\r \s}}
           (scope/parse-scope "patient/Observation.rs")))
    (is (= {:compartment "patient" :resource "Observation" :permissions #{\c \r \u \d \s}}
           (scope/parse-scope "patient/Observation.cruds")))
    (is (= {:compartment "system" :resource "*" :permissions #{\c \r \u \d \s}}
           (scope/parse-scope "system/*.cruds")))
    (is (= #{\c \u} (:permissions (scope/parse-scope "user/Encounter.cu"))))))

(deftest parse-scope-non-resource
  (testing "context / identity scopes are not resource scopes"
    (doseq [s ["openid" "profile" "fhirUser" "launch" "launch/patient"
               "launch/encounter" "offline_access" "online_access"]]
      (is (nil? (scope/parse-scope s)) (str "should not parse as resource scope: " s)))))

(deftest parse-scope-malformed
  (testing "malformed scopes are dropped"
    (doseq [s ["" "patient/" "patient/Observation" "patient/Observation.xyz"
               "foo/Observation.read" "patient/Observation.readx" "/Observation.read"]]
      (is (nil? (scope/parse-scope s)) (str "should be rejected: " s)))))

(deftest parse-scopes-claim-forms
  (testing "space-delimited string claim"
    (let [parsed (scope/parse-scopes "openid patient/Observation.rs user/*.read")]
      (is (= 2 (count parsed)))
      (is (= #{"Observation" "*"} (set (map :resource parsed))))))

  (testing "collection claim"
    (let [parsed (scope/parse-scopes ["patient/Patient.read" "openid" "user/*.write"])]
      (is (= 2 (count parsed)))))

  (testing "nil / blank claim yields no scopes"
    (is (= [] (scope/parse-scopes nil)))
    (is (= [] (scope/parse-scopes "")))
    (is (= [] (scope/parse-scopes "   ")))))

;; ---------------------------------------------------------------------------
;; request-scopes — reads `scope` (string) or `scp` (array, RFC 9068 / Hydra)
;; ---------------------------------------------------------------------------

(deftest request-scopes-claim-sources
  (testing "OAuth2 `scope` string claim"
    (is (= #{"Observation"}
           (set (map :resource (scope/request-scopes
                                 {:identity {:scope "patient/Observation.rs openid"}}))))))
  (testing "RFC 9068 / Hydra `scp` array claim"
    (is (= #{"Observation" "Patient"}
           (set (map :resource (scope/request-scopes
                                 {:identity {:scp ["patient/Observation.rs" "patient/Patient.rs"]}}))))))
  (testing "`scope` wins when both are present"
    (is (= ["Condition"]
           (map :resource (scope/request-scopes
                            {:identity {:scope "patient/Condition.rs"
                                        :scp ["patient/Observation.rs"]}})))))
  (testing "no scope claim yields nothing"
    (is (= [] (scope/request-scopes {:identity {:sub "u"}})))))

;; ---------------------------------------------------------------------------
;; permitted?
;; ---------------------------------------------------------------------------

(deftest permitted-v1-read
  (let [scopes (scope/parse-scopes "patient/Observation.read")]
    (testing ".read grants read and search"
      (is (scope/permitted? scopes "Observation" :read))
      (is (scope/permitted? scopes "Observation" :search))
      (is (scope/permitted? scopes "Observation" :vread)))
    (testing ".read denies write interactions"
      (is (not (scope/permitted? scopes "Observation" :create)))
      (is (not (scope/permitted? scopes "Observation" :update)))
      (is (not (scope/permitted? scopes "Observation" :delete))))))

(deftest permitted-v1-write
  (let [scopes (scope/parse-scopes "user/Patient.write")]
    (testing ".write grants create / update / delete"
      (is (scope/permitted? scopes "Patient" :create))
      (is (scope/permitted? scopes "Patient" :update))
      (is (scope/permitted? scopes "Patient" :delete)))
    (testing ".write denies read / search"
      (is (not (scope/permitted? scopes "Patient" :read)))
      (is (not (scope/permitted? scopes "Patient" :search))))))

(deftest permitted-v2-granular
  (testing "v2 .rs grants read+search, denies create"
    (let [scopes (scope/parse-scopes "patient/Observation.rs")]
      (is (scope/permitted? scopes "Observation" :read))
      (is (scope/permitted? scopes "Observation" :search))
      (is (not (scope/permitted? scopes "Observation" :create)))))
  (testing "v2 .cruds grants every interaction"
    (let [scopes (scope/parse-scopes "patient/Observation.cruds")]
      (doseq [i [:read :search :create :update :delete]]
        (is (scope/permitted? scopes "Observation" i) (str "should permit " i))))))

(deftest permitted-wildcard-and-cross-resource
  (testing "wildcard resource matches any type"
    (let [scopes (scope/parse-scopes "user/*.read")]
      (is (scope/permitted? scopes "Patient" :read))
      (is (scope/permitted? scopes "Observation" :search))
      (is (scope/permitted? scopes "Condition" :read))))
  (testing "resource-specific scope does not grant other resources"
    (let [scopes (scope/parse-scopes "patient/Observation.rs")]
      (is (scope/permitted? scopes "Observation" :read))
      (is (not (scope/permitted? scopes "Patient" :read)))))
  (testing "no scopes permits nothing"
    (is (not (scope/permitted? [] "Patient" :read)))))

;; ---------------------------------------------------------------------------
;; request->interaction / request->fhir-type
;; ---------------------------------------------------------------------------

(deftest request->interaction-derivation
  (is (= :read   (scope/request->interaction {:request-method :get :path-params {:id "1"}})))
  (is (= :vread  (scope/request->interaction {:request-method :get :path-params {:id "1" :vid "2"}})))
  (is (= :search (scope/request->interaction {:request-method :get})))
  (is (= :search (scope/request->interaction {:request-method :post :uri "/default/fhir/Patient/_search"})))
  (is (= :create (scope/request->interaction {:request-method :post})))
  (is (= :update (scope/request->interaction {:request-method :put :path-params {:id "1"}})))
  (is (= :update (scope/request->interaction {:request-method :patch :path-params {:id "1"}})))
  (is (= :delete (scope/request->interaction {:request-method :delete :path-params {:id "1"}}))))

(deftest request->fhir-type-derivation
  (testing "prefers explicit :fhir/resource-type"
    (is (= "Patient" (scope/request->fhir-type {:fhir/resource-type "Patient"}))))
  (testing "falls back to URL parsing"
    (is (= "Observation" (scope/request->fhir-type {:uri "/default/fhir/Observation/123"}))))
  (testing "system endpoints yield no resource type"
    (is (nil? (scope/request->fhir-type {:uri "/default/fhir/metadata"})))))

;; ---------------------------------------------------------------------------
;; wrap-smart-scope middleware
;; ---------------------------------------------------------------------------

(def ^:private ok-handler (fn [_req] {:status 200 :body "OK"}))

(defn- wrapped [] (scope/wrap-smart-scope ok-handler {}))

(defn- req
  "Build a request with the given scope claim, method, type and (optional) id."
  [scope-claim method fhir-type & {:keys [id]}]
  (cond-> {:identity {:sub "user123" :scope scope-claim}
           :request-method method
           :fhir/resource-type fhir-type}
    id (assoc :path-params {:id id})))

(deftest middleware-public-route-bypass
  (testing "public routes skip scope enforcement entirely"
    (let [response ((wrapped) {:reitit.core/match {:data {:public? true}}})]
      (is (= 200 (:status response)))
      (is (= "OK" (:body response))))))

(deftest middleware-read-allowed-and-denied
  (testing "patient/Patient.read allows GET Patient/:id"
    (let [response ((wrapped) (req "patient/Patient.read" :get "Patient" :id "123"))]
      (is (= 200 (:status response)))))
  (testing "only patient/Patient.write denies a read"
    (let [response ((wrapped) (req "patient/Patient.write" :get "Patient" :id "123"))]
      (is (= 403 (:status response)))
      (is (= "OperationOutcome" (get-in response [:body :resourceType]))))))

(deftest middleware-create-requires-write
  (testing "POST Patient with only .read is denied"
    (is (= 403 (:status ((wrapped) (req "patient/Patient.read" :post "Patient"))))))
  (testing "POST Patient with .write is allowed"
    (is (= 200 (:status ((wrapped) (req "patient/Patient.write" :post "Patient"))))))
  (testing "POST Patient with v2 .c is allowed"
    (is (= 200 (:status ((wrapped) (req "patient/Patient.c" :post "Patient")))))))

(deftest middleware-delete-requires-d
  (testing "DELETE requires delete permission"
    (is (= 403 (:status ((wrapped) (req "patient/Patient.rs" :delete "Patient" :id "1")))))
    (is (= 200 (:status ((wrapped) (req "patient/Patient.d" :delete "Patient" :id "1")))))))

(deftest middleware-wildcard-scope
  (testing "user/*.read grants read on any resource type"
    (is (= 200 (:status ((wrapped) (req "user/*.read" :get "Condition" :id "1")))))
    (is (= 200 (:status ((wrapped) (req "user/*.read" :get "Observation")))))))

(deftest middleware-v2-cross-resource
  (testing "patient/Observation.rs allows search on Observation"
    (is (= 200 (:status ((wrapped) (req "patient/Observation.rs" :get "Observation"))))))
  (testing "patient/Observation.rs is denied for Patient"
    (is (= 403 (:status ((wrapped) (req "patient/Observation.rs" :get "Patient" :id "1")))))))

(deftest middleware-missing-scope
  (testing "no scope claim is denied"
    (let [response ((wrapped) (req nil :get "Patient" :id "1"))]
      (is (= 403 (:status response)))
      (is (= "OperationOutcome" (get-in response [:body :resourceType])))))
  (testing "scope present but granting nothing relevant is denied"
    (is (= 403 (:status ((wrapped) (req "openid profile" :get "Patient" :id "1")))))))

(deftest middleware-claim-forms
  (testing "space-delimited string claim is honored"
    (is (= 200 (:status ((wrapped) (req "openid patient/Patient.read" :get "Patient" :id "1"))))))
  (testing "collection claim is honored"
    (is (= 200 (:status ((wrapped) (req ["patient/Patient.read" "openid"] :get "Patient" :id "1")))))))

(deftest middleware-non-resource-endpoint-allowed
  (testing "requests that resolve to no resource type are allowed through"
    (let [response ((wrapped) {:identity {:sub "user123" :scope "openid"}
                               :request-method :get
                               :uri "/default/fhir/metadata"})]
      (is (= 200 (:status response))))))

;; ---------------------------------------------------------------------------
;; :smart/interaction route-data override
;; ---------------------------------------------------------------------------

(defn- op-req
  "An operation request whose matched route carries `route-data`."
  [scope-claim method route-data]
  (assoc (req scope-claim method "Appointment" :id "a1")
         :uri "/default/fhir/Appointment/a1/$telehealth-signal"
         :reitit.core/match {:data route-data}))

(deftest effective-interaction-resolution
  (testing "absent key keeps method-derived scoring (POST -> :create)"
    (is (= {:interaction :create :source :method}
           (scope/effective-interaction (op-req "x" :post {})))))
  (testing "keyword override wins for POST"
    (is (= {:interaction :update :source :route}
           (scope/effective-interaction (op-req "x" :post {:smart/interaction :update})))))
  (testing "per-method map picks by request method"
    (let [route {:smart/interaction {:get :read :post :update}}]
      (is (= :read (:interaction (scope/effective-interaction (op-req "x" :get route)))))
      (is (= :update (:interaction (scope/effective-interaction (op-req "x" :post route)))))))
  (testing "method missing from the map falls back to method-derived scoring"
    (is (= {:interaction :read :source :method}
           (scope/effective-interaction (op-req "x" :get {:smart/interaction {:post :update}})))))
  (testing "unknown values are reported as invalid"
    (is (= {:invalid :write :source :route}
           (scope/effective-interaction (op-req "x" :post {:smart/interaction :write}))))
    (is (= {:invalid "update" :source :route}
           (scope/effective-interaction (op-req "x" :post {:smart/interaction {:post "update"}}))))
    (is (= {:invalid nil :source :route}
           (scope/effective-interaction (op-req "x" :post {:smart/interaction nil}))))))

(deftest middleware-route-interaction-override
  (let [route {:smart/interaction {:get :read :post :update}}]
    (testing "patient/Appointment.u allows POST when the route says :update"
      (is (= 200 (:status ((wrapped) (op-req "patient/Appointment.u" :post route))))))
    (testing "patient/*.read is refused for POST when the route says :update"
      (is (= 403 (:status ((wrapped) (op-req "patient/*.read" :post route))))))
    (testing "patient/Appointment.c is refused for POST when the route says :update"
      (is (= 403 (:status ((wrapped) (op-req "patient/Appointment.c" :post route))))))
    (testing "GET stays a read under the per-method map"
      (is (= 200 (:status ((wrapped) (op-req "patient/*.read" :get route)))))))
  (testing "absent key keeps POST -> :create"
    (is (= 200 (:status ((wrapped) (op-req "patient/Appointment.c" :post {})))))
    (is (= 403 (:status ((wrapped) (op-req "patient/Appointment.u" :post {}))))))
  (testing "an unknown override value is refused even with every permission"
    (let [response ((wrapped) (op-req "patient/*.cruds" :post {:smart/interaction :write}))]
      (is (= 403 (:status response)))
      (is (= "OperationOutcome" (get-in response [:body :resourceType])))))
  (testing "a nil override value is refused, not treated as absent"
    (is (= 403 (:status ((wrapped) (op-req "patient/*.cruds" :post {:smart/interaction nil}))))))
  (testing "an unknown per-method value is refused"
    (is (= 403 (:status ((wrapped) (op-req "patient/*.cruds" :post {:smart/interaction {:post :write}})))))))
