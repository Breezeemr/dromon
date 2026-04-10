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

    (testing "denies access when no subject-id is present"
      (let [request {}
            response (wrapped-handler request)]
        (is (= 403 (:status response)))
        (is (= "OperationOutcome" (:resourceType (:body response))))
        (is (= "Missing subject in identity; cannot authorize."
               (-> response :body :issue first :diagnostics)))))

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
