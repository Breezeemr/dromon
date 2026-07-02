(ns server.telehealth-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [server.telehealth :as th]))

(defn- reset-sessions! [f]
  (reset! @#'th/sessions {})
  (f)
  (reset! @#'th/sessions {}))

(use-fixtures :each reset-sessions!)

(def k ["default" "appt-1"])

(deftest post-poll-roundtrip
  (testing "a message posted by the patient lands in the provider inbox"
    (th/post-message! k "patient" {:type "offer" :payload "{\"sdp\":\"x\"}"})
    (is (= [{:type "offer" :payload "{\"sdp\":\"x\"}"}]
           (th/poll-messages! k "provider" 0))))
  (testing "the patient's own inbox stays empty"
    (th/post-message! k "patient" {:type "offer" :payload "p"})
    (is (= [] (th/poll-messages! k "patient" 0)))))

(deftest poll-drains-queued-messages
  (th/post-message! k "provider" {:type "answer" :payload "a"})
  (th/post-message! k "provider" {:type "candidate" :payload "c1"})
  (th/post-message! k "provider" {:type "candidate" :payload "c2"})
  (is (= ["answer" "candidate" "candidate"]
         (mapv :type (th/poll-messages! k "patient" 0)))
      "one poll returns everything queued so far"))

(deftest poll-times-out-empty
  (let [start (System/currentTimeMillis)
        messages (th/poll-messages! k "patient" 150)]
    (is (= [] messages))
    (is (<= 150 (- (System/currentTimeMillis) start))
        "poll blocks for the requested timeout before giving up")))

(deftest long-poll-wakes-on-post
  (let [poller (future (th/poll-messages! k "provider" 5000))]
    (Thread/sleep 100)
    (th/post-message! k "patient" {:type "offer" :payload "sdp"})
    (is (= [{:type "offer" :payload "sdp"}]
           (deref poller 2000 ::timed-out))
        "a parked poll is released by the peer's post, not the timeout")))

(deftest sessions-are-isolated
  (th/post-message! ["default" "appt-1"] "patient" {:type "offer" :payload "1"})
  (th/post-message! ["default" "appt-2"] "patient" {:type "offer" :payload "2"})
  (is (= ["1"] (mapv :payload (th/poll-messages! ["default" "appt-1"] "provider" 0))))
  (is (= ["2"] (mapv :payload (th/poll-messages! ["default" "appt-2"] "provider" 0)))))

;; ---------------------------------------------------------------------------
;; Ring handlers
;; ---------------------------------------------------------------------------

(defn- parameters-body [& name-value-pairs]
  {:resourceType "Parameters"
   :parameter (mapv (fn [[n k v]] {:name n k v}) name-value-pairs)})

(defn- post-req [tenant appt-id body]
  {:request-method :post
   :path-params {:tenant-id tenant :id appt-id}
   :body-params body})

(defn- get-req [tenant appt-id query]
  {:request-method :get
   :path-params {:tenant-id tenant :id appt-id}
   :query-params query})

(deftest post-signal-validates-input
  (testing "missing appointment id"
    (let [resp (th/post-signal {:path-params {:tenant-id "default"}
                                :body-params (parameters-body ["role" :valueCode "patient"]
                                                              ["type" :valueCode "offer"]
                                                              ["payload" :valueString "x"])})]
      (is (= 400 (:status resp)))))
  (testing "bad role"
    (let [resp (th/post-signal (post-req "default" "a1"
                                         (parameters-body ["role" :valueCode "spectator"]
                                                          ["type" :valueCode "offer"]
                                                          ["payload" :valueString "x"])))]
      (is (= 400 (:status resp)))))
  (testing "bad type"
    (let [resp (th/post-signal (post-req "default" "a1"
                                         (parameters-body ["role" :valueCode "patient"]
                                                          ["type" :valueCode "wave"]
                                                          ["payload" :valueString "x"])))]
      (is (= 400 (:status resp)))))
  (testing "payload required except for bye"
    (is (= 400 (:status (th/post-signal
                         (post-req "default" "a1"
                                   (parameters-body ["role" :valueCode "patient"]
                                                    ["type" :valueCode "offer"]))))))
    (is (= 200 (:status (th/post-signal
                         (post-req "default" "a1"
                                   (parameters-body ["role" :valueCode "patient"]
                                                    ["type" :valueCode "bye"]))))))))

(deftest signal-handlers-roundtrip
  (let [post-resp (th/post-signal (post-req "default" "a2"
                                            (parameters-body ["role" :valueCode "patient"]
                                                             ["type" :valueCode "offer"]
                                                             ["payload" :valueString "sdp-offer"])))
        poll-resp (th/poll-signal (get-req "default" "a2" {"role" "provider" "timeout" "0"}))]
    (is (= 200 (:status post-resp)))
    (is (= [{:name "queued" :valueBoolean true}]
           (get-in post-resp [:body :parameter])))
    (is (= 200 (:status poll-resp)))
    (is (= "Parameters" (get-in poll-resp [:body :resourceType])))
    (is (= [{:name "message"
             :part [{:name "type" :valueCode "offer"}
                    {:name "payload" :valueString "sdp-offer"}]}]
           (get-in poll-resp [:body :parameter])))))

(deftest poll-signal-validates-role
  (is (= 400 (:status (th/poll-signal (get-req "default" "a3" {"role" "nurse"})))))
  (is (= 400 (:status (th/poll-signal {:request-method :get
                                       :path-params {:tenant-id "default"}
                                       :query-params {"role" "patient"}})))
      "type-level operation URL (no id) is rejected"))

(deftest bye-ends-session
  (th/post-signal (post-req "default" "a4"
                            (parameters-body ["role" :valueCode "patient"]
                                             ["type" :valueCode "bye"])))
  (let [resp (th/poll-signal (get-req "default" "a4" {"role" "provider" "timeout" "0"}))]
    (is (= [{:name "message" :part [{:name "type" :valueCode "bye"}]}]
           (get-in resp [:body :parameter])))
    (is (not (contains? @@#'th/sessions ["default" "a4"]))
        "session state is dropped once the peer consumes the bye")))

(deftest timeout-is-clamped
  (let [parse-timeout @#'th/parse-timeout]
    (is (= 25 (parse-timeout nil)) "default")
    (is (= 10 (parse-timeout "10")))
    (is (= 55 (parse-timeout "3600")) "capped below proxy idle timeouts")
    (is (= 0 (parse-timeout "-5")) "negative clamps to immediate return")
    (is (= 25 (parse-timeout "garbage")) "unparseable falls back to default")))
