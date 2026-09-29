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

   Where the inboxes live is the [[Mailbox]] protocol's business. The default
   is [[in-memory-mailbox]], which keeps sessions in process memory and so
   needs a single server instance or sticky sessions. A deployment that runs
   several instances installs a shared implementation with [[install-mailbox!]]
   (fhir-server stays store-agnostic; flotilla installs a Datomic-backed one).
   Signaling state is ephemeral by nature: peers re-negotiate on reconnect."
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:const roles #{"patient" "provider"})

(def ^:const message-types #{"offer" "answer" "candidate" "bye"})

(def ^:private max-timeout-seconds
  "Upper bound for a single long poll, kept below common LB/proxy idle
   timeouts (usually 60s)."
  55)

(def ^:private default-timeout-seconds 25)

(def session-idle-millis
  "Sessions untouched for this long are swept."
  (* 2 60 60 1000))

(def max-pending-messages
  "Upper bound on undelivered messages in one inbox. A peer that never polls
   must not be able to grow the mailbox without limit."
  500)

(def max-payload-chars
  "Upper bound on one message payload. SDP offers and ICE candidates are a few
   KB; this leaves headroom while refusing anything abusive."
  32768)

(defprotocol Mailbox
  "Per-(tenant, appointment) signaling inboxes. `k` is [tenant-id
   appointment-id]; `role` is \"patient\" or \"provider\"; a message is a map
   {:type .. :payload ..}. Implementations must deliver each message to at most
   one poll, in post order, and must be safe to call from many threads (and,
   for shared implementations, many processes)."
  (post! [mailbox k from-role message]
    "Enqueues `message` on the opposite role's inbox. Throws ex-info with
     {:type :server.telehealth/mailbox-full} when that inbox is at
     [[max-pending-messages]].")
  (poll! [mailbox k role timeout-ms]
    "Blocks up to `timeout-ms` for a message addressed to `role`, then
     consumes and returns everything queued as a vector (possibly empty).")
  (end! [mailbox k]
    "Drops all signaling state, both inboxes, for the session."))

(defn- mailbox-full! [k role]
  (throw (ex-info "Signaling inbox is full"
                  {:type ::mailbox-full :session k :role role})))

(defn other-role [role]
  (case role
    "patient"  "provider"
    "provider" "patient"))

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
  [sessions k]
  (let [now (now-ms)]
    (-> (swap! sessions
               (fn [m]
                 (let [m (sweep-idle m now)]
                   (-> m
                       (update-in [k :inboxes]
                                  #(or % {"patient"  (LinkedBlockingQueue. (int max-pending-messages))
                                          "provider" (LinkedBlockingQueue. (int max-pending-messages))}))
                       (assoc-in [k :last-active-ms] now)))))
        (get k))))

(defrecord InMemoryMailbox [sessions]
  Mailbox
  (post! [_ k from-role message]
    (let [role  (other-role from-role)
          ^LinkedBlockingQueue inbox (get-in (ensure-session sessions k) [:inboxes role])]
      (when-not (.offer inbox message)
        (mailbox-full! k role))
      nil))
  (poll! [_ k role timeout-ms]
    (let [^LinkedBlockingQueue inbox (get-in (ensure-session sessions k) [:inboxes role])]
      (if-some [first-message (.poll inbox timeout-ms TimeUnit/MILLISECONDS)]
        (let [rest-messages (java.util.ArrayList.)]
          (.drainTo inbox rest-messages)
          (into [first-message] rest-messages))
        [])))
  (end! [_ k]
    (swap! sessions dissoc k)
    nil))

(defn in-memory-mailbox
  "A process-local mailbox. Correct only when both participants of an
   appointment reach the same server instance."
  []
  (->InMemoryMailbox (atom {})))

(defonce ^:private installed (atom (in-memory-mailbox)))

(defn installed-mailbox [] @installed)

(defn install-mailbox!
  "Makes `mailbox` the one [[post-signal]], [[poll-signal]] and
   [[signal-operation]] use, and returns the previous one. Read on every
   request, so it may be called after the routes are built."
  [mailbox]
  (first (reset-vals! installed mailbox)))

(defn post-message! [k from-role message] (post! @installed k from-role message))

(defn poll-messages! [k role timeout-ms] (poll! @installed k role timeout-ms))

(defn end-session! [k] (end! @installed k))

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

(defn- too-many-requests [diagnostics]
  {:status 429 :body (operation-outcome "error" "throttled" diagnostics)})

(defn- post-signal* [mailbox req]
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

      (and (string? payload) (> (count payload) max-payload-chars))
      (bad-request (str "payload parameter is limited to " max-payload-chars " characters"))

      :else
      (try
        (post! mailbox k role (cond-> {:type type}
                                payload (assoc :payload payload)))
        {:status 200
         :body {:resourceType "Parameters"
                :parameter [{:name "queued" :valueBoolean true}]}}
        (catch clojure.lang.ExceptionInfo e
          (if (= ::mailbox-full (:type (ex-data e)))
            (too-many-requests "The peer has too many undelivered signaling messages")
            (throw e)))))))

(defn- parse-timeout [timeout-param]
  (let [t (or (some-> timeout-param parse-long) default-timeout-seconds)]
    (max 0 (min t max-timeout-seconds))))

(defn- poll-signal* [mailbox req]
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
      (let [messages (poll! mailbox k role (* timeout-s 1000))]
        (when (some #(= "bye" (:type %)) messages)
          (end! mailbox k))
        {:status 200
         :body (messages->parameters messages)}))))

(defn- signal-operation* [mailbox {:keys [request]}]
  (case (:request-method request)
    :get  (poll-signal* mailbox request)
    :post (post-signal* mailbox request)
    (bad-request (str "$telehealth-signal answers GET and POST, not "
                      (some-> (:request-method request) name)))))

(defn handlers
  "The three ring entry points bound to one `mailbox` instead of the installed
   one: {:post-signal :poll-signal :signal-operation}. Two servers sharing a
   mailbox store are two `handlers` calls over two mailbox instances, which is
   how the multi-instance behavior is tested."
  [mailbox]
  {:post-signal      #(post-signal* mailbox %)
   :poll-signal      #(poll-signal* mailbox %)
   :signal-operation #(signal-operation* mailbox %)})

(defn post-signal
  "POST handler for Appointment/$telehealth-signal. Body is a Parameters
   resource with role, type and (except for bye) payload parameters."
  [req]
  (post-signal* @installed req))

(defn poll-signal
  "GET handler for Appointment/$telehealth-signal. Long-polls the caller's
   inbox; `role` selects the inbox, `timeout` (seconds) bounds the wait.
   Returns immediately with any queued messages, otherwise blocks until a
   message arrives or the timeout elapses (then returns an empty Parameters).
   A consumed \"bye\" ends the session."
  [req]
  (poll-signal* @installed req))

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
  [ctx]
  (signal-operation* @installed ctx))
