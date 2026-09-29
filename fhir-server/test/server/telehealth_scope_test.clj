(ns server.telehealth-scope-test
  "Telehealth signalling driven through the router and SMART scope
   middleware, the way a patient token reaches it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [malli.core :as m]
            [reitit.ring :as ring]
            [server.routing :as routing]
            [server.scope :as scope]
            [server.telehealth :as th]))

(use-fixtures :each (fn [f]
                      (reset! @#'th/sessions {})
                      (f)
                      (reset! @#'th/sessions {})))

(def ^:private portal-scope
  "Scope the cabotage2 portal requests, from oauth/scopes."
  "openid offline_access fhirUser launch/patient patient/*.read patient/Patient.c patient/Appointment.cu")

(def ^:private read-only-scope
  "The scope set before patient/Appointment.cu was granted."
  "openid offline_access fhirUser launch/patient patient/*.read patient/Patient.c")

(def ^:private handler
  (let [schema (m/schema
                [:map {:resourceType "Appointment"
                       :fhir/operations
                       {"$telehealth-signal" {:get  'server.telehealth/poll-signal
                                              :post 'server.telehealth/post-signal}}}])
        routes (routing/build-resource-routes [schema])]
    (ring/ring-handler
      (ring/router routes {:conflicts nil
                           :data {:middleware [[scope/wrap-smart-scope {}]]}}))))

(def ^:private uri "/default/fhir/Appointment/appt-1/$telehealth-signal")

(defn- post! [scope-claim role type payload]
  (handler {:request-method :post
            :uri uri
            :identity {:sub "patient-1" :scope scope-claim}
            :body-params {:resourceType "Parameters"
                          :parameter (cond-> [{:name "role" :valueString role}
                                              {:name "type" :valueString type}]
                                       payload (conj {:name "payload" :valueString payload}))}}))

(defn- poll! [scope-claim role timeout]
  (handler {:request-method :get
            :uri uri
            :identity {:sub "patient-1" :scope scope-claim}
            :query-params {"role" role "timeout" timeout}}))

(deftest post-is-scored-as-create-on-appointment
  (is (= :create (scope/request->interaction {:request-method :post :uri uri})))
  (is (= "Appointment" (scope/request->fhir-type {:uri uri}))))

(deftest patient-appointment-cu-allows-post
  (let [response (post! portal-scope "patient" "offer" "sdp")]
    (is (= 200 (:status response)))
    (is (= [{:type "offer" :payload "sdp"}]
           (th/poll-messages! ["default" "appt-1"] "provider" 0)))))

(deftest read-only-patient-scope-is-denied-on-post
  (let [response (post! read-only-scope "patient" "offer" "sdp")]
    (is (= 403 (:status response)))
    (is (re-find #"create on Appointment" (get-in response [:body :issue 0 :diagnostics])))
    (is (= [] (th/poll-messages! ["default" "appt-1"] "provider" 0))
        "a denied post publishes nothing")))

(deftest appointment-create-only-scope-also-allows-post
  (is (= 200 (:status (post! "patient/Appointment.c" "patient" "offer" "sdp")))))

(deftest read-scope-alone-still-allows-the-poll
  (is (= 200 (:status (poll! read-only-scope "patient" "0")))))

(deftest long-poll-through-router-is-released-by-post
  (let [poller (future (poll! portal-scope "provider" "25"))]
    (Thread/sleep 150)
    (is (not (realized? poller)) "the poll is parked, not answered empty")
    (is (= 200 (:status (post! portal-scope "patient" "offer" "sdp"))))
    (let [response (deref poller 2000 ::timed-out)]
      (is (not= ::timed-out response)
          "the parked poll is released by the post, not the 25 s timeout")
      (is (= 200 (:status response)))
      (is (= "message" (get-in response [:body :parameter 0 :name]))))))

(deftest bye-through-router-drops-the-session
  (post! portal-scope "patient" "bye" nil)
  (is (= 200 (:status (poll! portal-scope "provider" "0"))))
  (is (not (contains? @@#'th/sessions ["default" "appt-1"]))))
