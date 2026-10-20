(ns server.grant-test
  (:require [clojure.test :refer [deftest is testing]]
            [hato.client :as hc]
            [server.compartment :as compartment]
            [server.grant :as grant]))

(deftest grant-tuples-shape
  (let [tuples (grant/grant-tuples nil "client-1" ["pa" "pb"] ["read"] false)]
    (testing "launch tuple per patient"
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "launch" :subject_id "client-1"}) tuples))
      (is (some #(= % {:namespace "fhir" :object "Patient/pb"
                       :relation "launch" :subject_id "client-1"}) tuples)))
    (testing "instance-level read per patient"
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "read" :subject_id "client-1"}) tuples)))
    (testing "type-level read on compartment member types"
      (is (some #(= % {:namespace "fhir" :object "Observation"
                       :relation "read" :subject_id "client-1"}) tuples))
      (is (some #(= % {:namespace "fhir" :object "Condition"
                       :relation "read" :subject_id "client-1"}) tuples)))
    (testing "no type-level Patient tuple (instance-confined)"
      (is (not-any? #(= (:object %) "Patient") tuples)))
    (testing "no write relations unless requested"
      (is (not-any? #(= (:relation %) "write") tuples)))))

(deftest grant-tuples-are-realm-scoped
  (testing "every object carries the realm as its first segment, so a grant in
            one realm authorizes nothing in another"
    (let [tuples (grant/grant-tuples "r1" "client-1" ["pa"] ["read"] false)]
      (is (some #(= % {:namespace "fhir" :object "r1/Patient/pa"
                       :relation "launch" :subject_id "client-1"}) tuples))
      (is (some #(= % {:namespace "fhir" :object "r1/Patient/pa"
                       :relation "read" :subject_id "client-1"}) tuples))
      (is (some #(= % {:namespace "fhir" :object "r1/Observation"
                       :relation "read" :subject_id "client-1"}) tuples))
      (is (every? #(clojure.string/starts-with? (:object %) "r1/") tuples)
          "a single unscoped object would be access in every realm")))

  (testing "dual-write emits the legacy realm-blind object beside the scoped
            one, so rolling the reader back still finds the grant"
    (let [tuples (grant/grant-tuples "r1" "client-1" ["pa"] ["read"] true)]
      (is (some #(= % {:namespace "fhir" :object "r1/Patient/pa"
                       :relation "read" :subject_id "client-1"}) tuples))
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "read" :subject_id "client-1"}) tuples))))

  (testing "dual-write adds nothing when there is no realm: the scoped object
            IS the legacy object, and duplicating it would double every write"
    (is (= (grant/grant-tuples nil "client-1" ["pa"] ["read"] false)
           (grant/grant-tuples nil "client-1" ["pa"] ["read"] true)))))

(deftest launch-object-parses-both-shapes
  (testing "a realm-scoped launch object yields its realm and patient"
    (is (= ["r1" "pa"] (grant/launch-object->patient "r1/Patient/pa"))))
  (testing "a legacy realm-blind object yields a nil realm"
    (is (= [nil "pa"] (grant/launch-object->patient "Patient/pa"))))
  (testing "a realm whose name is capitalized is still read as a realm, which
            a resource-type-shaped test of the first segment would get wrong"
    (is (= ["Clinic" "pa"] (grant/launch-object->patient "Clinic/Patient/pa"))))
  (testing "an object naming no patient is not a launch grant"
    (is (nil? (grant/launch-object->patient "r1/Observation")))
    (is (nil? (grant/launch-object->patient "system")))))

(def ^:private task-defs
  (compartment/with-overrides {"Patient" {"Task" ["requester"]}}))

(deftest type-level-tuples-follow-the-compartment-definitions
  (testing "R4B: Task is not a Patient member, so it gets no type-level tuple"
    (is (not-any? #(= (:object %) "Task")
                  (grant/grant-tuples nil "client-1" ["pa"] ["read"] false))))
  (let [tuples (grant/grant-tuples task-defs nil "client-1" ["pa"] ["read"] false)]
    (testing "a member the host's definitions add gets a type-level tuple"
      (is (some #(= % {:namespace "fhir" :object "Task"
                       :relation "read" :subject_id "client-1"}) tuples)))
    (testing "Patient is still instance-confined, with no type-level tuple"
      (is (not-any? #(= (:object %) "Patient") tuples)))))

(def ^:private test-keto
  {:read-url "http://keto-read.test" :admin-url "http://keto-write.test"})

(defn- captured-tuples
  "Runs `f` with the Keto write stubbed out, returning the tuples it would
   have written."
  [f]
  (let [tuples (atom [])]
    (with-redefs-fn {#'grant/put-tuple! (fn [_keto t] (swap! tuples conj t))}
      (fn [] (f)))
    @tuples))

(deftest default-relations-authorize-request-change
  (testing "a minted grant carries read and request-change on the patient instance"
    (let [tuples (captured-tuples #(grant/grant-patient-set! test-keto nil "client-1" ["pa"]))]
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "read" :subject_id "client-1"}) tuples))
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "request-change" :subject_id "client-1"}) tuples))))
  (testing "explicit relations override the default"
    (let [tuples (captured-tuples
                  #(grant/grant-patient-set! test-keto nil "client-1" ["pa"] :relations ["read"]))]
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "read" :subject_id "client-1"}) tuples))
      (is (not-any? #(= (:relation %) "request-change") tuples)))))

(deftest default-relations-authorize-message
  (testing "a minted grant carries message on the patient instance"
    (let [tuples (captured-tuples #(grant/grant-patient-set! test-keto nil "client-1" ["pa"]))]
      (is (some #(= % {:namespace "fhir" :object "Patient/pa"
                       :relation "message" :subject_id "client-1"}) tuples))
      (testing "and on no member type"
        (is (= #{"Patient/pa"}
               (into #{} (comp (filter #(= "message" (:relation %))) (map :object))
                     tuples))))
      (testing "while read and request-change still reach the member types"
        (is (some #(= % {:namespace "fhir" :object "Observation"
                         :relation "read" :subject_id "client-1"}) tuples))
        (is (some #(= % {:namespace "fhir" :object "Observation"
                         :relation "request-change" :subject_id "client-1"}) tuples)))))
  (testing "explicit relations without message mint no message tuple"
    (let [tuples (captured-tuples
                  #(grant/grant-patient-set! test-keto nil "client-1" ["pa"] :relations ["read"]))]
      (is (not-any? #(= (:relation %) "message") tuples))))
  (testing "an explicit message relation is still confined to the instance"
    (let [tuples (captured-tuples
                  #(grant/grant-patient-set! test-keto nil "client-1" ["pa"]
                                             :relations ["read" "message"]))]
      (is (= #{"Patient/pa"}
             (into #{} (comp (filter #(= "message" (:relation %))) (map :object))
                   tuples)))
      (is (some #(= % {:namespace "fhir" :object "Condition"
                       :relation "read" :subject_id "client-1"}) tuples)))))

(deftest message-is-instance-only-in-every-shape
  (testing "realm-scoped and dual-written legacy objects, instance only"
    (let [tuples (grant/grant-tuples "r1" "client-1" ["pa" "pb"]
                                     ["read" "message"] true)]
      (is (= #{"r1/Patient/pa" "Patient/pa" "r1/Patient/pb" "Patient/pb"}
             (into #{} (comp (filter #(= "message" (:relation %))) (map :object))
                   tuples))))))

(defn- hook-payload [& {:keys [client-id scopes patient]}]
  (cond-> {:request (cond-> {:client_id client-id
                             :granted_scopes (or scopes [])}
                      patient (assoc :payload {:patient patient}))}
    true identity))

(deftest resolve-launch-patient-explicit-request
  (testing "launch/patient.<id> scope selects the launch patient"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            (hook-payload :client-id "c1"
                          :scopes ["launch/patient.pa" "patient/*.read"])
            (constantly ["pa" "pb"])
            (fn [subject pid] (and (= subject "c1") (= pid "pa")))))))
  (testing "launch scope for an ungranted patient denies issuance"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1"
                              :scopes ["launch/patient.px" "patient/*.read"])
                (constantly ["pa"])
                (constantly false)))))
  (testing "explicit granted patient is injected"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            (hook-payload :client-id "c1" :scopes ["patient/*.read"] :patient "pa")
            (constantly ["pa" "pb"])
            (fn [subject pid] (and (= subject "c1") (= pid "pa")))))))
  (testing "explicit ungranted patient denies issuance"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1" :scopes ["patient/*.read"] :patient "px")
                (constantly ["pa"])
                (constantly false)))))
  (testing "array-valued form param is unwrapped"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            {:request {:client_id "c1" :granted_scopes ["patient/*.read"]
                       :payload {:patient ["pa"]}}}
            (constantly ["pa"])
            (constantly true))))))

(deftest resolve-launch-patient-implicit
  (testing "single granted patient is used when none requested"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            (hook-payload :client-id "c1" :scopes ["patient/*.read"])
            (constantly ["pa"])
            (constantly true)))))
  (testing "multiple granted patients without explicit request denies"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1" :scopes ["patient/*.read"])
                (constantly ["pa" "pb"])
                (constantly true)))))
  (testing "no grants at all denies a patient-scoped token"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1" :scopes ["patient/*.read"])
                (constantly [])
                (constantly true))))))

(deftest resolve-launch-patient-unscoped
  (testing "non-patient-scoped tokens pass through without a claim"
    (is (= {} (grant/resolve-launch-patient
               (hook-payload :client-id "c1" :scopes ["user/*.read"])
               (constantly ["pa" "pb"])
               (constantly true))))
    (is (= {} (grant/resolve-launch-patient
               (hook-payload :client-id "c1" :scopes [])
               (constantly [])
               (constantly false))))))

(defn- authorization-code-payload
  "The hook payload shape Hydra v2.2.0 sends when exchanging an
   authorization code: the end-user subject only inside the id_token
   session and an EMPTY request.granted_scopes."
  [subject client-id]
  {:session {:id_token {:subject subject
                        :id_token_claims {:sub subject}}
             :client_id client-id
             :consent_challenge "ch-1"}
   :request {:client_id client-id
             :granted_scopes []
             :granted_audience []
             :grant_types ["authorization_code"]
             :payload {}}})

(deftest resolve-launch-patient-authorization-code
  (testing "single launch grant injects the patient for interactive tokens"
    (is (= {:patient "pa"}
           (grant/resolve-launch-patient
            (authorization-code-payload "user-1" "c1")
            (constantly ["pa"])
            (constantly true)))))
  (testing "no launch grant issues without patient context (consent-side
            linkage already fail-closed patient-scoped grants)"
    (is (= {} (grant/resolve-launch-patient
               (authorization-code-payload "user-1" "c1")
               (constantly [])
               (constantly true)))))
  (testing "ambiguous grants issue without patient context"
    (is (= {} (grant/resolve-launch-patient
               (authorization-code-payload "user-1" "c1")
               (constantly ["pa" "pb"])
               (constantly true)))))
  (testing "client_credentials fail-closed behavior is unchanged"
    (is (:deny (grant/resolve-launch-patient
                (hook-payload :client-id "c1" :scopes ["patient/*.read"])
                (constantly [])
                (constantly true))))))

(deftest end-user-subject-extraction
  (testing "client_credentials payloads have no end user"
    (is (nil? (grant/end-user-subject
               {:session {:client_id "c1"} :request {:client_id "c1"}}))))
  (testing "session.subject wins when present"
    (is (= "u1" (grant/end-user-subject {:session {:subject "u1"}}))))
  (testing "authorization_code payloads fall back to the id_token session"
    (is (= "u2" (grant/end-user-subject
                 {:session {:subject "" :id_token {:subject "u2"}}}))))
  (testing "blank subjects everywhere is no end user"
    (is (nil? (grant/end-user-subject
               {:session {:subject "" :id_token {:subject ""}}})))))

;; ---------------------------------------------------------------------------
;; Keto endpoints
;; ---------------------------------------------------------------------------

(defmacro ^:private with-recorded-keto
  "Runs `body` with every Keto call answered locally, binding `calls` to an
   atom of [method url] in call order. Reads answer `tuples`."
  [[calls tuples] & body]
  `(let [~calls (atom [])]
     (with-redefs [hc/get    (fn [url# _#]
                               (swap! ~calls conj [:get url#])
                               {:status 200 :body {:relation_tuples ~tuples}})
                   hc/put    (fn [url# _#]
                               (swap! ~calls conj [:put url#])
                               {:status 201})
                   hc/delete (fn [url# _#]
                               (swap! ~calls conj [:delete url#])
                               {:status 204})]
       ~@body)))

(def ^:private keto-request
  "A request as `server.router/wrap-keto-url` leaves it."
  {:fhir/keto-url       "http://keto-read.test"
   :fhir/keto-admin-url "http://keto-write.test"})

(deftest keto-endpoints-come-from-the-request
  (is (= {:read-url "http://keto-read.test" :admin-url "http://keto-write.test"}
         (grant/keto-endpoints keto-request))))

(deftest handlers-talk-to-the-keto-the-request-names
  (testing "a grant writes to the admin URL and never to localhost"
    (with-recorded-keto [calls []]
      (is (= 201 (:status (grant/create-grant
                           (assoc keto-request
                                  :body-params {:subject "c1" :patients ["pa"]
                                                :realm "r1"})))))
      (is (seq @calls))
      (is (every? #(= [:put "http://keto-write.test/admin/relation-tuples"] %)
                  @calls))))

  (testing "a listing reads the read URL"
    (with-recorded-keto [calls [{:object "r1/Patient/pa"}]]
      (is (= {:status 200 :body {:subject "u1" :patients ["pa"]}}
             (grant/my-patients (assoc keto-request :identity {:sub "u1"}))))
      (is (= [[:get "http://keto-read.test/relation-tuples"]] @calls))))

  (testing "a revoke reads the read URL and deletes on the admin URL"
    (with-recorded-keto [calls [{:object "r1/Patient/pa"}]]
      (grant/delete-grant (assoc keto-request
                                 :body-params {:subject "c1" :patients ["pa"]}))
      (is (= #{"http://keto-read.test/relation-tuples"
               "http://keto-write.test/admin/relation-tuples"}
             (set (map second @calls))))))

  (testing "the token hook resolves the launch patient on the read URL"
    (with-recorded-keto [calls [{:object "r1/Patient/pa"}]]
      (is (= {:status 200 :body {:session {:access_token {:patient "pa"}}}}
             (grant/token-hook
              (assoc keto-request
                     :body-params {:request {:client_id "c1"
                                             :granted_scopes ["patient/*.read"]}}))))
      (is (= #{"http://keto-read.test/relation-tuples"}
             (set (map second @calls)))))))

(deftest an-unconfigured-keto-fails-instead-of-reaching-localhost
  (testing "no admin URL: the grant throws naming the key, and nothing is sent"
    (with-recorded-keto [calls []]
      (let [e (try (grant/create-grant {:fhir/keto-url "http://keto-read.test"
                                        :body-params {:subject "c1" :patients ["pa"]}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= {:keto-url-key :admin-url} (ex-data e)))
        (is (empty? @calls)))))
  (testing "no read URL: a listing throws naming the key"
    (with-recorded-keto [calls []]
      (let [e (try (grant/my-patients {:identity {:sub "u1"}})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= {:keto-url-key :read-url} (ex-data e)))
        (is (empty? @calls))))))

(deftest the-token-hook-secret-is-compared-in-constant-time
  (testing "secret-matches? is exact equality over UTF-8 bytes"
    (is (true? (grant/secret-matches? "s3cret" "s3cret")))
    (is (true? (grant/secret-matches? "caf\u00e9" "caf\u00e9")))
    (is (false? (grant/secret-matches? "s3cret" "s3cre")))
    (is (false? (grant/secret-matches? "s3cret" "s3cret ")))
    (is (false? (grant/secret-matches? "s3cret" "S3cret")))
    (is (false? (grant/secret-matches? "s3cret" "")))
    (is (false? (grant/secret-matches? "s3cret" nil))))
  (testing "with a secret set, the hook refuses a missing or wrong header before Keto"
    (with-redefs [grant/token-hook-secret (constantly "s3cret")]
      (with-recorded-keto [calls []]
        (doseq [headers [{} {"x-token-hook-secret" "wrong"} {"x-token-hook-secret" "s3cre"}]]
          (is (= {:status 401 :body {:error "invalid token hook secret"}}
                 (grant/token-hook (assoc keto-request :headers headers :body-params {})))))
        (is (empty? @calls)))
      (with-recorded-keto [calls []]
        (is (= 200 (:status (grant/token-hook
                             (assoc keto-request
                                    :headers {"x-token-hook-secret" "s3cret"}
                                    :body-params {:request {:client_id "c1"}})))))))))

(deftest the-grant-handlers-use-the-request's-compartment-definitions
  (testing "a grant writes type-level tuples for the request's members"
    (with-recorded-keto [calls []]
      (let [written (captured-tuples
                     #(grant/create-grant
                       (assoc keto-request
                              :fhir/compartment-definitions task-defs
                              :body-params {:subject "c1" :patients ["pa"] :realm "r1"})))]
        (is (some #(= "r1/Task" (:object %)) written)))))
  (testing "a revoke sweeps R4B's members and the request's, so neither is left behind"
    (let [deleted (atom [])]
      (with-redefs [grant/list-tuples (fn [_ q] (when (= "fhir" (get q "namespace")) []))
                    grant/granted-patients (constantly [])]
        (with-redefs-fn {#'grant/delete-tuples! (fn [_ q] (swap! deleted conj (get q "object")))}
          #(grant/delete-grant (assoc keto-request
                                      :fhir/compartment-definitions task-defs
                                      :body-params {:subject "c1" :patients ["pa"]}))))
      (is (some #{"Task"} @deleted))
      (is (some #{"Observation"} @deleted)))))

;; ---------------------------------------------------------------------------
;; Host claims (:token-claims)
;; ---------------------------------------------------------------------------

(defn- hook-request [payload token-claims]
  (assoc keto-request :body-params payload :fhir/token-claims token-claims))

(deftest the-token-hook-merges-the-host's-claims-for-an-end-user
  (let [seen (atom nil)
        claims (fn [m] (reset! seen (dissoc m :payload)) {:realms ["r1"] :patient "forged"})]
    (with-recorded-keto [_ [{:object "r1/Patient/pa"}]]
      (is (= {:status 200 :body {:session {:access_token {:realms ["r1"] :patient "pa"}}}}
             (grant/token-hook (hook-request (authorization-code-payload "user-1" "c1") claims)))
          "the launch decision's patient wins over a host claim of the same name"))
    (is (= {:keto (grant/keto-endpoints keto-request) :subject "user-1" :client-id "c1"}
           @seen))))

(deftest the-token-hook-calls-no-host-claims-without-an-end-user-or-after-a-deny
  (let [called (atom 0)
        claims (fn [_] (swap! called inc) {:x 1})]
    (with-recorded-keto [_ []]
      (testing "client_credentials: no end-user subject"
        (is (= {:status 200 :body {}}
               (grant/token-hook (hook-request {:request {:client_id "c1"}} claims)))))
      (testing "a denied issuance"
        (is (= 403 (:status (grant/token-hook
                             (hook-request (assoc-in (authorization-code-payload "user-1" "c1")
                                                     [:request :granted_scopes]
                                                     ["launch/patient.px" "patient/*.read"])
                                           claims)))))))
    (is (zero? @called))))

(deftest the-token-hook-refuses-host-claims-that-are-not-a-map
  (with-recorded-keto [_ []]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"token-claims must return a map"
          (grant/token-hook (hook-request (authorization-code-payload "user-1" "c1")
                                          (constantly ["r1"])))))))
