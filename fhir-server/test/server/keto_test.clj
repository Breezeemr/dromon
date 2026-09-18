(ns server.keto-test
  (:require [clojure.test :refer [deftest is testing]]
            [server.keto :as keto]
            [hato.client :as hc]))

(deftest wrap-keto-authorization-test
  (let [handler (fn [req] {:status 200 :body "OK"})
        wrapped-handler (keto/wrap-keto-authorization handler {:keto-url "http://mock-keto"})]

    (testing "bypasses checks for public routes"
      (let [request {:reitit.core/match {:data {:public? true}}}
            response (wrapped-handler request)]
        (is (= 200 (:status response)))
        (is (= "OK" (:body response)))))

    (testing "a request with no subject-id is unauthenticated, not forbidden"
      (let [request {}
            response (wrapped-handler request)]
        (is (= 401 (:status response)))
        (is (= "OperationOutcome" (:resourceType (:body response))))
        (is (= "Missing subject in identity; cannot authorize."
               (-> response :body :issue first :diagnostics)))
        (is (nil? (:login-url (:body response)))
            "no login-url is advertised when the deployment configured none")))

    (testing "grants access when keto allows type-level"
      (with-redefs [hc/get (fn [url opts]
                             (is (= "http://mock-keto/relation-tuples/check" url))
                             (is (= {"namespace" "fhir"
                                     "object" "Patient"
                                     "relation" "read"
                                     "subject_id" "user123"}
                                    (:query-params opts)))
                             {:status 200 :body {:allowed true}})]
        (let [request {:identity {:sub "user123"}
                       :request-method :get
                       :fhir/resource-type "Patient"}
              response (wrapped-handler request)]
          (is (= 200 (:status response)))
          (is (= "OK" (:body response))))))

    (testing "route data :keto/relation overrides the method-derived relation"
      (with-redefs [hc/get (fn [_ opts]
                             (is (= "read" (get (:query-params opts) "relation"))
                                 "POST normally maps to write; the route pins read")
                             {:status 200 :body {:allowed true}})]
        (let [request {:reitit.core/match {:data {:keto/relation "read"}}
                       :identity {:sub "user123"}
                       :request-method :post
                       :uri "/default/fhir/Appointment/a1/$telehealth-signal"
                       :path-params {:id "a1"}}
              response (wrapped-handler request)]
          (is (= 200 (:status response))))))

    (testing "denies access when keto disallows"
      (with-redefs [hc/get (fn [_ _] {:status 403 :body {:allowed false}})]
        (let [request {:identity {:sub "user123"}
                       :request-method :get
                       :fhir/resource-type "Patient"}
              response (wrapped-handler request)]
          (is (= 403 (:status response)))
          (is (= "OperationOutcome" (:resourceType (:body response))))
          (is (= "Subject user123 is not allowed to read Patient"
                 (-> response :body :issue first :diagnostics))))))

    (testing "handles exception during keto check safely"
      (with-redefs [hc/get (fn [_ _] (throw (Exception. "Keto down!")))]
        (let [request {:identity {:sub "user123"}
                       :request-method :get
                       :fhir/resource-type "Patient"}
              response (wrapped-handler request)]
          (is (= 403 (:status response))))))

    (testing "maps to 'system' object if no fhir-type provided"
      (with-redefs [hc/get (fn [_ opts]
                             (is (= "system" (get-in opts [:query-params "object"])))
                             {:status 200 :body {:allowed true}})]
        (let [request {:identity {:sub "user123"}
                       :request-method :get}
              response (wrapped-handler request)]
          (is (= 200 (:status response))))))

    (testing "instance-level access granted via type-level permission"
      ;; When requesting Patient/123, should check type-level (Patient) first
      ;; and grant access if type-level permission exists
      (let [calls (atom [])]
        (with-redefs [hc/get (fn [_ opts]
                               (let [object (get-in opts [:query-params "object"])]
                                 (swap! calls conj object)
                                 (if (= "Patient" object)
                                   {:status 200 :body {:allowed true}}
                                   {:status 403 :body {:allowed false}})))]
          (let [request {:identity {:sub "user123"}
                         :request-method :get
                         :path-params {:id "123"}
                         :fhir/resource-type "Patient"}
                response (wrapped-handler request)]
            (is (= 200 (:status response)))
            ;; Should check type-level first for instance requests
            (is (= "Patient" (first @calls)))))))

    (testing "instance-level access granted via instance-level permission"
      ;; When type-level is denied but instance-level is granted
      (with-redefs [hc/get (fn [_ opts]
                             (let [object (get-in opts [:query-params "object"])]
                               (if (= "Patient/123" object)
                                 {:status 200 :body {:allowed true}}
                                 {:status 403 :body {:allowed false}})))]
        (let [request {:identity {:sub "user123"}
                       :request-method :get
                       :path-params {:id "123"}
                       :fhir/resource-type "Patient"}
              response (wrapped-handler request)]
          (is (= 200 (:status response))))))

    (testing "instance-level access denied when both checks fail"
      (with-redefs [hc/get (fn [_ _] {:status 403 :body {:allowed false}})]
        (let [request {:identity {:sub "user123"}
                       :request-method :get
                       :path-params {:id "123"}
                       :fhir/resource-type "Patient"}
              response (wrapped-handler request)]
          (is (= 403 (:status response)))
          (is (= "Subject user123 is not allowed to read Patient/123"
                 (-> response :body :issue first :diagnostics))))))))

(deftest unauthenticated-401-carries-login-url
  (testing "a deployment that configured a login URL advertises it on the 401,
            so the client learns where authentication starts instead of
            guessing at a built-in path"
    (let [handler (fn [_] {:status 200 :body "OK"})
          login-url "https://localflotilla.breezeehr.com:8444/auth/hydra/login"
          wrapped (keto/wrap-keto-authorization handler {:keto-url "http://mock-keto"
                                                         :login-url login-url})
          response (wrapped {:request-method :get :fhir/resource-type "Person"})]
      (is (= 401 (:status response)))
      (is (= login-url (:login-url (:body response))))
      (is (= "OperationOutcome" (:resourceType (:body response))))))

  (testing "a blank login-url is not advertised"
    (let [handler (fn [_] {:status 200 :body "OK"})
          wrapped (keto/wrap-keto-authorization handler {:keto-url "http://mock-keto"
                                                         :login-url "  "})]
      (is (nil? (:login-url (:body (wrapped {}))))))))

(deftest present-subject-but-denied-stays-403
  (testing "401 means 'no subject'; a subject Keto refuses is still forbidden"
    (with-redefs [hc/get (fn [_ _] {:status 403 :body {:allowed false}})]
      (let [handler (fn [_] {:status 200 :body "OK"})
            wrapped (keto/wrap-keto-authorization handler {:keto-url "http://mock-keto"
                                                           :login-url "https://example.test/login"})
            response (wrapped {:identity {:sub "user123"}
                               :request-method :get
                               :fhir/resource-type "Person"})]
        (is (= 403 (:status response)))
        (is (nil? (:login-url (:body response))))))))

(deftest request-objects-are-realm-scoped
  (testing "the realm is the object's first segment, matching the convention
            breezeehr-role and practitioner-id already use"
    (is (= ["r1/Patient"] (keto/request-objects "r1" "Patient" nil false)))
    (is (= ["r1/Patient" "r1/Patient/123"]
           (keto/request-objects "r1" "Patient" "123" false)))
    (is (= ["r1/system"] (keto/request-objects "r1" nil nil false))))

  (testing "the legacy realm-blind objects come AFTER the scoped ones, so once
            the backfill has run the first check answers and the extra calls
            stop being made"
    (is (= ["r1/Patient" "Patient"] (keto/request-objects "r1" "Patient" nil true)))
    (is (= ["r1/Patient" "r1/Patient/123" "Patient" "Patient/123"]
           (keto/request-objects "r1" "Patient" "123" true))))

  (testing "a request with no realm keeps the bare object: /auth/grants and the
            token hook are global surfaces, not unscoped ones, and an empty
            realm segment would ask Keto about an object nothing ever wrote"
    (is (= ["Patient"] (keto/request-objects nil "Patient" nil true)))
    (is (= ["system"] (keto/request-objects "" nil nil true)))))

(deftest realm-scoped-check-precedes-the-legacy-one
  (testing "a subject holding only the realm's tuple is allowed, and the legacy
            object is never asked about"
    (let [asked (atom [])]
      (with-redefs [hc/get (fn [_ opts]
                             (let [object (get-in opts [:query-params "object"])]
                               (swap! asked conj object)
                               {:status 200 :body {:allowed (= "r1/Patient" object)}}))]
        (let [handler (fn [_] {:status 200 :body "OK"})
              wrapped (keto/wrap-keto-authorization handler {:keto-url "http://mock-keto"})
              response (wrapped {:identity {:sub "user123"}
                                 :request-method :get
                                 :path-params {:tenant-id "r1"}
                                 :fhir/resource-type "Patient"})]
          (is (= 200 (:status response)))
          (is (= ["r1/Patient"] @asked))))))

  (testing "a grant written before realm-scoping still authorizes while the
            fallback is on -- this is what keeps the deploy from locking
            everyone out before the backfill runs"
    (with-redefs [hc/get (fn [_ opts]
                           {:status 200
                            :body {:allowed (= "Patient"
                                               (get-in opts [:query-params "object"]))}})]
      (let [handler (fn [_] {:status 200 :body "OK"})
            wrapped (keto/wrap-keto-authorization handler {:keto-url "http://mock-keto"})]
        (is (= 200 (:status (wrapped {:identity {:sub "user123"}
                                      :request-method :get
                                      :path-params {:tenant-id "r1"}
                                      :fhir/resource-type "Patient"})))))))

  (testing "with the fallback off, a realm-blind grant authorizes nothing --
            which is the whole point of the migration, and what step 4 turns on"
    (with-redefs [hc/get (fn [_ opts]
                           {:status 200
                            :body {:allowed (= "Patient"
                                               (get-in opts [:query-params "object"]))}})]
      (let [handler (fn [_] {:status 200 :body "OK"})
            wrapped (keto/wrap-keto-authorization
                      handler {:keto-url "http://mock-keto"
                               :legacy-realm-blind-fallback? false})
            response (wrapped {:identity {:sub "user123"}
                               :request-method :get
                               :path-params {:tenant-id "r1"}
                               :fhir/resource-type "Patient"})]
        (is (= 403 (:status response)))
        (is (= "Subject user123 is not allowed to read r1/Patient"
               (-> response :body :issue first :diagnostics)))))))

(deftest a-grant-in-one-realm-does-not-reach-another
  (testing "the defect this work closes: a subject granted Patient read in r1
            is refused in r2, where before realm-scoping the same tuple
            authorized every realm the server hosts"
    (with-redefs [hc/get (fn [_ opts]
                           {:status 200
                            :body {:allowed (= "r1/Patient"
                                               (get-in opts [:query-params "object"]))}})]
      (let [handler (fn [_] {:status 200 :body "OK"})
            wrapped (keto/wrap-keto-authorization
                      handler {:keto-url "http://mock-keto"
                               :legacy-realm-blind-fallback? false})
            in-realm (fn [realm]
                       (wrapped {:identity {:sub "user123"}
                                 :request-method :get
                                 :path-params {:tenant-id realm}
                                 :fhir/resource-type "Patient"}))]
        (is (= 200 (:status (in-realm "r1"))))
        (is (= 403 (:status (in-realm "r2"))))))))

(deftest denial-names-the-object-the-request-was-about
  (testing "an instance request denied on both objects reports the instance,
            not the type it happened to check first"
    (with-redefs [hc/get (fn [_ _] {:status 403 :body {:allowed false}})]
      (let [handler (fn [_] {:status 200 :body "OK"})
            wrapped (keto/wrap-keto-authorization handler {:keto-url "http://mock-keto"})
            response (wrapped {:identity {:sub "user123"}
                               :request-method :get
                               :path-params {:tenant-id "r1" :id "123"}
                               :fhir/resource-type "Patient"})]
        (is (= "Subject user123 is not allowed to read r1/Patient/123"
               (-> response :body :issue first :diagnostics)))))))

(deftest system-read-is-checked-per-realm
  (testing "the :public? bulk-data gate scopes to the realm in the path: a
            full-tenant export is the widest read the server offers, and these
            routes bypass both the Keto middleware and flotilla's realm check"
    (with-redefs [hc/get (fn [_ opts]
                           {:status 200
                            :body {:allowed (= "r1/system"
                                               (get-in opts [:query-params "object"]))}})]
      (is (keto/system-read-allowed? "http://mock-keto" "r1" "user123" false))
      (is (not (keto/system-read-allowed? "http://mock-keto" "r2" "user123" false)))))

  (testing "a subject with no id is never allowed, whatever Keto would say"
    (with-redefs [hc/get (fn [_ _] {:status 200 :body {:allowed true}})]
      (is (not (keto/system-read-allowed? "http://mock-keto" "r1" nil false))))))
