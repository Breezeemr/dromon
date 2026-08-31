(ns server.telehealth
  "WebRTC signaling for telehealth visits, exposed as the Appointment
   `$telehealth-signal` operation with long polling.

   Model: one signaling session per (tenant, appointment). Each session has
   two roles -- \"patient\" and \"provider\" -- with one inbox queue per
   role. A message POSTed by one role is enqueued on the other role's inbox;
   a GET long-polls the caller's own inbox, blocking (on a virtual thread)
   until a message arrives or the timeout elapses.

   Wire format is FHIR Parameters:

   POST /:tenant/fhir/Appointment/:id/$telehealth-signal
     {\"resourceType\":\"Parameters\",
      \"parameter\":[{\"name\":\"role\",\"valueCode\":\"patient\"},
                     {\"name\":\"type\",\"valueCode\":\"offer\"},
                     {\"name\":\"payload\",\"valueString\":\"<sdp/candidate json>\"}]}

   GET /:tenant/fhir/Appointment/:id/$telehealth-signal?role=patient&timeout=25
     -> {\"resourceType\":\"Parameters\",
         \"parameter\":[{\"name\":\"message\",
                         \"part\":[{\"name\":\"type\",\"valueCode\":\"answer\"},
                                   {\"name\":\"payload\",\"valueString\":\"...\"}]}]}

   Sessions live in process memory: signaling state is ephemeral by nature
   (peers re-negotiate on reconnect), but this does mean signaling requires
   a single server instance or sticky sessions."
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:const roles #{"patient" "provider"})

(def ^:const message-types #{"offer" "answer" "candidate" "bye"})

(def ^:private max-timeout-seconds
  "Upper bound for a single long poll, kept below common LB/proxy idle
   timeouts (usually 60s)."
  55)

(def ^:private default-timeout-seconds 25)

(def ^:private session-idle-millis
  "Sessions untouched for this long are swept."
  (* 2 60 60 1000))

(defonce ^:private sessions
  ;; {[tenant-id appointment-id] {:inboxes {role LinkedBlockingQueue}
  ;;                              :last-active-ms long}}
  (atom {}))

(defn- now-ms [] (System/currentTimeMillis))

(defn- sweep-idle
  "Removes sessions idle beyond the TTL. Runs opportunistically on access."
  [sessions-map now]
  (into {}
        (remove (fn [[_ {:keys [last-active-ms]}]]
                  (< (+ last-active-ms session-idle-millis) now)))
        sessions-map))

(defn- ensure-session
  "Returns the session for `k`, creating it (and sweeping idle sessions)
   when missing. Touches :last-active-ms."
  [k]
  (let [now (now-ms)]
    (-> (swap! sessions
               (fn [m]
                 (let [m (sweep-idle m now)]
                   (-> m
                       (update-in [k :inboxes]
                                  #(or % {"patient"  (LinkedBlockingQueue.)
                                          "provider" (LinkedBlockingQueue.)}))
                       (assoc-in [k :last-active-ms] now)))))
        (get k))))

(defn end-session!
  "Drops all signaling state for the session."
  [k]
  (swap! sessions dissoc k)
  nil)

(defn other-role [role]
  (case role
    "patient"  "provider"
    "provider" "patient"))

(defn post-message!
  "Enqueues `message` for the opposite role of `from-role`. A \"bye\"
   additionally wakes the peer and marks the session finished; state is
   dropped once the peer consumes it (or via the idle sweep)."
  [k from-role message]
  (let [^LinkedBlockingQueue inbox (get-in (ensure-session k) [:inboxes (other-role from-role)])]
    (.offer inbox message)
    nil))

(defn poll-messages!
  "Blocks up to `timeout-ms` for the first message addressed to `role`,
   then drains whatever else is queued. Returns a (possibly empty) vector."
  [k role timeout-ms]
  (let [^LinkedBlockingQueue inbox (get-in (ensure-session k) [:inboxes role])]
    (if-some [first-message (.poll inbox timeout-ms TimeUnit/MILLISECONDS)]
      (let [rest-messages (java.util.ArrayList.)]
        (.drainTo inbox rest-messages)
        (into [first-message] rest-messages))
      [])))

;; ---------------------------------------------------------------------------
;; FHIR Parameters (de)serialization
;; ---------------------------------------------------------------------------

(defn- parameters->map
  "Flattens a Parameters resource's top-level parameters into
   {name-keyword value}, taking the first value[x] key of each parameter."
  [parameters-body]
  (into {}
        (keep (fn [{:keys [name] :as parameter}]
                (when name
                  (when-some [value (some parameter [:valueCode :valueString :valueBoolean :valueInteger])]
                    [(keyword name) value]))))
        (:parameter parameters-body)))

(defn- message->part [{:keys [type payload]}]
  {:name "message"
   :part (cond-> [{:name "type" :valueCode type}]
           payload (conj {:name "payload" :valueString payload}))})

(defn- messages->parameters [messages]
  {:resourceType "Parameters"
   :parameter (mapv message->part messages)})

(defn- operation-outcome [severity code diagnostics]
  {:resourceType "OperationOutcome"
   :issue [{:severity severity :code code :diagnostics diagnostics}]})

(defn- bad-request [diagnostics]
  {:status 400 :body (operation-outcome "error" "invalid" diagnostics)})

(defn- session-key [req]
  (let [{:keys [tenant-id id]} (:path-params req)]
    (when id [tenant-id id])))

;; ---------------------------------------------------------------------------
;; Handlers
;; ---------------------------------------------------------------------------

(defn post-signal
  "POST handler for Appointment/$telehealth-signal. Body is a Parameters
   resource with role, type and (except for bye) payload parameters."
  [req]
  (let [k (session-key req)
        body (or (get-in req [:parameters :body]) (:body-params req))
        {:keys [role type payload]} (parameters->map body)]
    (cond
      (nil? k)
      (bad-request "Operation requires an appointment id: Appointment/{id}/$telehealth-signal")

      (not (contains? roles role))
      (bad-request (str "role parameter must be one of " roles))

      (not (contains? message-types type))
      (bad-request (str "type parameter must be one of " message-types))

      (and (nil? payload) (not= type "bye"))
      (bad-request "payload parameter is required for offer/answer/candidate")

      :else
      (do (post-message! k role (cond-> {:type type}
                                  payload (assoc :payload payload)))
          {:status 200
           :body {:resourceType "Parameters"
                  :parameter [{:name "queued" :valueBoolean true}]}}))))

(defn- parse-timeout [timeout-param]
  (let [t (or (some-> timeout-param parse-long) default-timeout-seconds)]
    (max 0 (min t max-timeout-seconds))))

(defn poll-signal
  "GET handler for Appointment/$telehealth-signal. Long-polls the caller's
   inbox; `role` selects the inbox, `timeout` (seconds) bounds the wait.
   Returns immediately with any queued messages, otherwise blocks until a
   message arrives or the timeout elapses (then returns an empty Parameters)."
  [req]
  (let [k (session-key req)
        query (:query-params req)
        role (get query "role")
        timeout-s (parse-timeout (get query "timeout"))]
    (cond
      (nil? k)
      (bad-request "Operation requires an appointment id: Appointment/{id}/$telehealth-signal")

      (not (contains? roles role))
      (bad-request (str "role query parameter must be one of " roles))

      :else
      (let [messages (poll-messages! k role (* timeout-s 1000))]
        (when (some #(= "bye" (:type %)) messages)
          (end-session! k))
        {:status 200
         :body (messages->parameters messages)}))))

(defn signal-operation
  "Entry point for the OperationDefinition-bound form of this operation.

   The operation catalog calls one implementation per OperationDefinition with
   a ctx map, while signalling is two handlers because it is a mailbox: GET
   polls the caller's inbox, POST publishes to the other one. The
   OperationDefinition declares both methods (operation-binding/method), and
   this dispatches to the handler each one has always used.

   Deployments that register the operation directly, as test-server does, keep
   naming `poll-signal` and `post-signal`; this only adds the catalog's shape
   on top of them."
  [{:keys [request]}]
  (case (:request-method request)
    :get  (poll-signal request)
    :post (post-signal request)
    (bad-request (str "$telehealth-signal answers GET and POST, not "
                      (some-> (:request-method request) name)))))
