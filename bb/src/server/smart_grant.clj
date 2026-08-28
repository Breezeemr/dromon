(ns server.smart-grant
  "Dev CLI for SMART patient-set grants: creates a Hydra OAuth2 client for an
   app and records the grant through the running FHIR server's /auth/grants
   API (server.grant), which writes the Keto relation tuples.

   Usage (from the dromon repo root, with `bb setup` stack and the FHIR server
   running):

     bb smart-grant --patients pa,pb                 ; new client + grant
     bb smart-grant --subject <id> --patients pa,pb  ; grant to existing subject
     bb smart-grant --subject <id> --list            ; show granted patients
     bb smart-grant --subject <id> --patients pa --revoke

   Bootstraps a `smart-grant-admin` Keto subject with the system read/write
   tuples the /auth/grants endpoint is gated on, and authenticates to the
   server either with an HS256 dev token (JWT_DEV_SECRET set) or with a
   dedicated Hydra admin client (JWKS mode)."
  (:require [babashka.curl :as curl]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util Base64]))

(def ^:private hydra-admin  "http://127.0.0.1:4445")
(def ^:private hydra-public "http://127.0.0.1:4444")
(def ^:private keto-admin   "http://127.0.0.1:4467")
(def ^:private server-base  (or (System/getenv "FHIR_SERVER_URL") "http://localhost:8080"))
(def ^:private admin-subject "smart-grant-admin")

(def ^:private app-scope
  "Scopes registered on app clients: SMART standalone patient launch,
   read-only plus `patient/Patient.c`. `launch/patient.*` (wildcard) lets the
   client select its launch patient per token via `launch/patient.<id>`.
   `patient/Patient.c` is the SMART v2 create scope on Patient, which is what
   server.scope's request->interaction derives for a POST to
   Patient/{id}/$request-demographic-change."
  "openid offline_access launch/patient launch/patient.* patient/*.read patient/Patient.c")

;; ── helpers ────────────────────────────────────────────────────────────────

(defn- fail! [msg data]
  (println "ERROR:" msg (pr-str data))
  (System/exit 1))

(defn- parse-args [args]
  (loop [args args, opts {}]
    (if-let [a (first args)]
      (case a
        "--patients" (recur (nnext args) (assoc opts :patients (str/split (second args) #",")))
        "--subject"  (recur (nnext args) (assoc opts :subject (second args)))
        "--relations" (recur (nnext args) (assoc opts :relations (str/split (second args) #",")))
        "--revoke"   (recur (next args) (assoc opts :revoke? true))
        "--list"     (recur (next args) (assoc opts :list? true))
        (fail! "unknown argument" {:arg a}))
      opts)))

(defn- b64url [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- hs256-token
  "Minimal HS256 JWT for the dev-secret auth path."
  [secret sub]
  (let [enc (fn [m] (b64url (.getBytes (json/generate-string m) "UTF-8")))
        head (enc {:alg "HS256" :typ "JWT"})
        payload (enc {:sub sub})
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256")))
        sig (b64url (.doFinal mac (.getBytes (str head "." payload) "UTF-8")))]
    (str head "." payload "." sig)))

;; ── Hydra / Keto ───────────────────────────────────────────────────────────

(defn- create-hydra-client! [client-name scope]
  (let [resp (curl/post (str hydra-admin "/admin/clients")
                        {:headers {"Content-Type" "application/json"}
                         :body (json/generate-string
                                {:client_name client-name
                                 :grant_types ["client_credentials"]
                                 :token_endpoint_auth_method "client_secret_basic"
                                 :scope scope})
                         :throw false})
        body (json/parse-string (:body resp) true)]
    (when (not= 201 (:status resp))
      (fail! "Hydra client creation failed" {:status (:status resp) :body (:body resp)}))
    {:id (:client_id body) :secret (:client_secret body)}))

(defn- hydra-token! [{:keys [id secret]} & [form-extra]]
  (let [resp (curl/post (str hydra-public "/oauth2/token")
                        {:basic-auth [id secret]
                         :form-params (merge {"grant_type" "client_credentials"} form-extra)
                         :throw false})]
    (when (not= 200 (:status resp))
      (fail! "Hydra token request failed" {:status (:status resp) :body (:body resp)}))
    (:access_token (json/parse-string (:body resp) true))))

(defn- keto-put! [tuple]
  (let [resp (curl/put (str keto-admin "/admin/relation-tuples")
                       {:headers {"Content-Type" "application/json"}
                        :body (json/generate-string tuple)
                        :throw false})]
    (when-not (#{200 201} (:status resp))
      (fail! "Keto tuple write failed" {:tuple tuple :status (:status resp)}))))

(defn- bootstrap-admin!
  "Ensures an authenticated admin identity that passes the /auth/grants system
   gate. With JWT_DEV_SECRET, mints an HS256 token for `smart-grant-admin`.
   Otherwise creates a Hydra admin client whose client_id is the subject."
  []
  (if-let [secret (System/getenv "JWT_DEV_SECRET")]
    (do (doseq [rel ["read" "write"]]
          (keto-put! {:namespace "fhir" :object "system" :relation rel
                      :subject_id admin-subject}))
        {:token (hs256-token secret admin-subject)
         :scheme "Token"})
    (let [client (create-hydra-client! "smart-grant-admin" "openid")]
      (doseq [rel ["read" "write"]]
        (keto-put! {:namespace "fhir" :object "system" :relation rel
                    :subject_id (:id client)}))
      {:token (hydra-token! client) :scheme "Bearer"})))

;; ── server /auth/grants API ────────────────────────────────────────────────

(defn- server-request [method path {:keys [token scheme]} & [body]]
  (let [f (case method :get curl/get :post curl/post :delete curl/delete)
        resp (f (str server-base path)
                (cond-> {:headers (cond-> {"Authorization" (str scheme " " token)
                                           "Accept" "application/json"}
                                    body (assoc "Content-Type" "application/json"))
                         :throw false}
                  body (assoc :body (json/generate-string body))))]
    (when-not (<= 200 (:status resp) 299)
      (fail! (str method " " path " failed") {:status (:status resp) :body (:body resp)}))
    (json/parse-string (:body resp) true)))

;; ── main ───────────────────────────────────────────────────────────────────

(defn -main [& args]
  (let [{:keys [patients subject relations revoke? list?]} (parse-args args)
        admin (bootstrap-admin!)
        subject (or subject
                    (let [client (create-hydra-client! "smart-app" app-scope)]
                      (println "Created Hydra app client:")
                      (println "  client_id:    " (:id client))
                      (println "  client_secret:" (:secret client))
                      (:id client)))]
    (cond
      list?
      (let [g (server-request :get (str "/auth/grants?subject=" subject) admin)]
        (println "Granted patients for" subject ":" (pr-str (:patients g))))

      revoke?
      (do (when (empty? patients) (fail! "--revoke requires --patients" {}))
          (server-request :delete "/auth/grants" admin
                          {:subject subject :patients patients})
          (println "Revoked" (pr-str patients) "from" subject))

      :else
      (do (when (empty? patients) (fail! "--patients is required" {}))
          (let [g (server-request :post "/auth/grants" admin
                                  (cond-> {:subject subject :patients patients}
                                    relations (assoc :relations relations)))]
            (println "Granted:" (pr-str g))
            (println)
            (println "Token request example (select the launch patient via scope):")
            (println (str "    curl -u <client_id>:<client_secret> " hydra-public
                          "/oauth2/token -d grant_type=client_credentials"
                          " -d 'scope=launch/patient." (first patients)
                          " patient/*.read patient/Patient.c'")))))))
