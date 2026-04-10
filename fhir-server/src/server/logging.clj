(ns server.logging
  (:require [taoensso.telemere :as t]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.stacktrace]
            [fipp.edn :as fipp])
  (:import [com.fasterxml.jackson.datatype.jsr310 JavaTimeModule]))

(def ^:private gcp-json-mapper
  (json/object-mapper
   {:strip-nils true
    :modules [(JavaTimeModule.)]}))

(defn- safe-for-json [v]
  (try
    (json/write-value-as-string v gcp-json-mapper)
    v
    (catch Throwable _
      (try
        (binding [*print-level* 4
                  *print-length* 50]
          (pr-str v))
        (catch Throwable _
          (str "<unserializable " (class v) ">"))))))

(defn- granular-fallback [data]
  (if (map? data)
    (reduce-kv (fn [m k v]
                 (assoc m k (safe-for-json v)))
               {} data)
    (safe-for-json data)))

(defn gcp-json-output-fn [signal]
  (let [{:keys [level id msg_ error inst file line ns data ctx]} signal
        severity (case level
                   :trace "DEBUG"
                   :debug "DEBUG"
                   :info "INFO"
                   :warn "WARNING"
                   :error "ERROR"
                   :fatal "CRITICAL"
                   "DEFAULT")
        base-message (str (when id (str "[" (or (namespace id) (name id)) "] "))
                          (when msg_ (force msg_)))
        full-message (if error
                       (str base-message "\n\n" (ex-message error) "\n"
                            (with-out-str (clojure.stacktrace/print-stack-trace error)))
                       base-message)
        ;; Extract GCP-specific top level fields from ctx or data
        extract-source (fn [k] (or (get ctx k) (get data k)))
        gcp-trace (extract-source :trace)
        gcp-span-id (extract-source :span-id)
        gcp-trace-sampled (extract-source :trace-sampled)
        gcp-labels (extract-source :labels)
        gcp-operation (extract-source :operation)

        merged-data (merge ctx data)
        clean-data (if (map? merged-data)
                     (into {}
                           (remove (fn [[k _]]
                                     (let [k-str (if (keyword? k) (name k) (str k))]
                                       (.startsWith ^String k-str "_"))))
                           (dissoc merged-data :trace :span-id :trace-sampled :labels :operation))
                     merged-data)

        log-map (cond-> {:severity severity
                         :time (some-> inst str)
                         :message full-message
                         :logging.googleapis.com/sourceLocation
                         {:file file
                          :line (str line)
                          :function ns}}
                  gcp-trace (assoc :logging.googleapis.com/trace gcp-trace)
                  gcp-span-id (assoc :logging.googleapis.com/spanId gcp-span-id)
                  (some? gcp-trace-sampled) (assoc :logging.googleapis.com/trace_sampled gcp-trace-sampled)
                  (seq gcp-labels) (assoc :logging.googleapis.com/labels gcp-labels)
                  (seq gcp-operation) (assoc :logging.googleapis.com/operation gcp-operation)
                  (seq clean-data) (assoc :data clean-data))]
    (try
      (str (json/write-value-as-string log-map gcp-json-mapper) "\n")
      (catch Throwable _
        ;; Fallback if `data` is not serializable
        (let [safe-log-map (cond-> log-map
                             (contains? log-map :data) (assoc :data (granular-fallback data)))]
          (try
            (str (json/write-value-as-string safe-log-map gcp-json-mapper) "\n")
            (catch Throwable _
              ;; Extreme fallback, drop data entirely
              (str (json/write-value-as-string
                    (assoc log-map :message (str full-message "\n\n[Log Serialization Error] Data dropped")) gcp-json-mapper)
                   "\n"))))))))

(defn fipp-edn-output-fn [signal]
  (let [cleaned-signal (cond-> (dissoc signal :msg_)
                         (:msg_ signal) (assoc :msg (force (:msg_ signal)))
                         (:error signal) (assoc :exception-msg (ex-message (:error signal))
                                                :stacktrace (with-out-str (clojure.stacktrace/print-stack-trace (:error signal)))))
        without-nils (persistent!
                      (reduce-kv (fn [m k v]
                                   (if (nil? v)
                                     m
                                     (assoc! m k v)))
                                 (transient {})
                                 cleaned-signal))]
    (with-out-str (fipp/pprint without-nils))))

(defn init-logging! []
  ;; Force tools.logging to use Telemere directly
  (require 'taoensso.telemere.tools-logging)
  (System/setProperty "clojure.tools.logging.factory" "taoensso.telemere.tools-logging/LoggerFactory")

  (let [config-file (io/file "logging.edn")
        config (if (.exists config-file)
                 (edn/read-string (slurp config-file))
                 {})
        min-level (get config :min-level :info)]

    ;; Set minimum log levels for chatty libraries
    (t/set-min-level! min-level)
    (t/set-min-level! "org.eclipse.jetty.*" :warn)
    (t/set-min-level! "xtdb.*" :warn)
    (t/set-min-level! "malli.*" :warn)

    ;; Configure default console handler securely, replacing any default ones
    (t/remove-handler! :default/console)
    (let [console-format (get-in config [:console :format] :gcp-json)
          console-opts (case console-format
                         :gcp-json {:output-fn gcp-json-output-fn}
                         :fipp-edn {:output-fn fipp-edn-output-fn}
                         {:output-fn
                          (fn [signal]
                            (let [{:keys [level id msg_ error]} signal]
                              (str (name level) " [" (when id (or (namespace id) (name id))) "] "
                                   (when msg_ (force msg_))
                                   (when error (str " - " (ex-message error))))))})]
      (t/add-handler! :console (t/handler:console console-opts)))

    ;; Configure file handler if requested
    (when-let [file-config (:file config)]
      (let [path (:path file-config)]
        (when-let [parent (.getParentFile (io/file path))]
          (when-not (.exists parent)
            (.mkdirs parent)))
        (let [file-opts (case (:format file-config)
                          :gcp-json (assoc file-config :output-fn gcp-json-output-fn)
                          :fipp-edn (assoc file-config :output-fn fipp-edn-output-fn)
                          file-config)]
          (t/add-handler! :file (t/handler:file (dissoc file-opts :format))))))))
