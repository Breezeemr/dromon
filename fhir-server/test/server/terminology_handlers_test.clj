(ns server.terminology-handlers-test
  "The built-in terminology handlers: each reads the bound terminology
   service, falls back to the store's operation, and answers 501 without
   either."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-terminology.protocol :as proto]
            [server.handlers :as handlers]))

(defn- stub-terminology
  "A service that records what it was asked and answers `answer`, or throws
   `failure` when given one."
  [calls answer failure]
  (reify proto/ITerminologyService
    (expand-valueset [_ params] (swap! calls conj [:expand params]) answer)
    (lookup-code [_ params] (swap! calls conj [:lookup params]) answer)
    (validate-code [_ params]
      (swap! calls conj [:validate params])
      (if failure (throw failure) answer))))

(def ^:private result-true
  {:resourceType "Parameters" :parameter [{:name "result" :valueBoolean true}]})

(deftest valueset-validate-code-asks-the-terminology-service
  (testing "type level: the query is passed through"
    (let [calls (atom [])
          resp  (handlers/valueset-validate-code
                 {:fhir/terminology (stub-terminology calls result-true nil)
                  :path-params {:tenant-id "t"}
                  :query-params {"url" "http://x/vs" "system" "http://snomed.info/sct" "code" "22298006"}})]
      (is (= 200 (:status resp)))
      (is (= result-true (:body resp)))
      (is (= [[:validate {"url" "http://x/vs" "system" "http://snomed.info/sct" "code" "22298006"}]] @calls))))
  (testing "instance level: the id is folded in, as $expand does"
    (let [calls (atom [])]
      (handlers/valueset-validate-code
       {:fhir/terminology (stub-terminology calls result-true nil)
        :path-params {:tenant-id "t" :id "vs1"}
        :query-params {"code" "22298006"}})
      (is (= [[:validate {"code" "22298006" :id "vs1"}]] @calls))))
  (testing "a service refusal keeps its status"
    (let [resp (handlers/valueset-validate-code
                {:fhir/terminology (stub-terminology (atom []) nil
                                                     (ex-info "ValueSet not found" {:fhir/status 404}))
                 :path-params {:tenant-id "t"}
                 :query-params {"url" "http://x/missing"}})]
      (is (= 404 (:status resp)))
      (is (= "ValueSet not found" (get-in resp [:body :issue 0 :diagnostics]))))))

(deftest valueset-validate-code-without-a-service
  (testing "the store's operation answers when it has one"
    (let [seen (atom nil)
          resp (handlers/valueset-validate-code
                {:fhir/store {:operations {:valueset-validate-code
                                           (fn [_store tenant params id]
                                             (reset! seen [tenant params id])
                                             result-true)}}
                 :path-params {:tenant-id "t" :id "vs1"}
                 :query-params {"code" "1"}})]
      (is (= 200 (:status resp)))
      (is (= ["t" {"code" "1"} "vs1"] @seen))))
  (testing "else 501, naming the operation"
    (let [resp (handlers/valueset-validate-code {:fhir/store {} :path-params {:tenant-id "t"}})]
      (is (= 501 (:status resp)))
      (is (= "ValueSet $validate-code not supported" (get-in resp [:body :issue 0 :diagnostics]))))))

(deftest codesystem-lookup-asks-the-terminology-service
  (let [calls (atom [])
        resp  (handlers/codesystem-lookup
               {:fhir/terminology (stub-terminology calls result-true nil)
                :path-params {:tenant-id "t"}
                :query-params {"system" "http://snomed.info/sct" "code" "22298006"}})]
    (is (= 200 (:status resp)))
    (is (= [[:lookup {"system" "http://snomed.info/sct" "code" "22298006"}]] @calls)))
  (testing "without one, 501 names CodeSystem"
    (is (= "CodeSystem $lookup not supported"
           (get-in (handlers/codesystem-lookup {:fhir/store {} :path-params {:tenant-id "t"}})
                   [:body :issue 0 :diagnostics])))))
