(ns server.dev.trace-tap
  "Dev-only per-request OpenTelemetry span capture.

  When `DROMON_DEV_TRACE_TAP=1` is set, [[init!]] pre-builds an
  AutoConfigured OpenTelemetry SDK with an extra `SimpleSpanProcessor`
  whose exporter is a global shim that forwards every ended span to
  every currently-registered request capture. The pre-built SDK is
  installed into Telemere's `otel-default-providers_` delay so the
  Telemere OpenTelemetry handler adopts the SAME SDK instance -- no
  reflection on private final fields, no second SDK.

  We forward to every active capture (rather than thread-local
  routing) because Telemere's OpenTelemetry handler buffers
  `Span.end()` calls and emits them from a background timer thread
  several seconds later, on a thread that has no relationship to the
  request thread. Concurrent traced requests are rare in dev, and the
  client-side tree builder ignores spans that don't link up.

  The Ring middleware [[wrap-trace-tap]] short-circuits when neither
  the `X-Dromon-Trace` header nor the `_dromon-trace` query param are
  present, so the steady-state hot path is one atom deref per span
  emit (no captures registered means no work). When active, it
  registers a fresh capture, waits for the buffered spans to drain
  after the handler returns, and serializes them into a gzip+base64
  `X-Dromon-Trace-Json` response header."
  (:require [jsonista.core :as json]
            [taoensso.telemere :as t])
  (:import [io.opentelemetry.sdk.trace.export SpanExporter SimpleSpanProcessor]
           [io.opentelemetry.sdk.trace.data SpanData]
           [io.opentelemetry.sdk.trace SdkTracerProviderBuilder]
           [io.opentelemetry.sdk.common CompletableResultCode]
           [io.opentelemetry.sdk OpenTelemetrySdk]
           [io.opentelemetry.sdk.autoconfigure AutoConfiguredOpenTelemetrySdk
                                               AutoConfiguredOpenTelemetrySdkBuilder]
           [java.util.function BiFunction]
           [java.util Collection]
           [java.util.concurrent ConcurrentLinkedQueue]
           [java.util.concurrent.atomic AtomicInteger AtomicBoolean]
           [java.io ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream]
           [java.util Base64]))

(def ^:private max-spans 1024)

(defn make-capturing-exporter
  "Creates a fresh capturing exporter: a `SpanExporter` that appends each
  exported `SpanData` to a bounded `ConcurrentLinkedQueue`. Returns a map
  with `:exporter`, `:queue`, `:count`, and `:overflow` (an `AtomicBoolean`).

  When the queue exceeds `max-spans` spans, additional spans are dropped
  and `:overflow` is set."
  []
  (let [queue (ConcurrentLinkedQueue.)
        cnt (AtomicInteger. 0)
        overflow (AtomicBoolean. false)
        exporter (reify SpanExporter
                   (^CompletableResultCode export [_ ^Collection spans]
                     (doseq [^SpanData s spans]
                       (if (< (.get cnt) max-spans)
                         (do (.add queue s) (.incrementAndGet cnt))
                         (.set overflow true)))
                     (CompletableResultCode/ofSuccess))
                   (^CompletableResultCode flush [_]
                     (CompletableResultCode/ofSuccess))
                   (^CompletableResultCode shutdown [_]
                     (CompletableResultCode/ofSuccess)))]
    {:exporter exporter
     :queue queue
     :count cnt
     :overflow overflow}))

(def ^:private active-captures
  "Set of currently-registered capture maps. The global delegating
  exporter forwards every ended span to each member; serialization
  later filters by the request's trace id (or includes all spans if
  the trace id is unknown). Keyed by a synthetic UUID so concurrent
  requests don't clobber each other."
  (atom {}))

(defn- delegating-exporter
  "Singleton SpanExporter installed on the SDK's SimpleSpanProcessor.
  Forwards each ended span to every currently-registered capture.
  Steady-state cost when no capture is active is one atom deref."
  []
  (reify SpanExporter
    (^CompletableResultCode export [_ ^Collection spans]
      (let [snap @active-captures]
        (when (seq snap)
          (doseq [cap (vals snap)]
            (.export ^SpanExporter (:exporter cap) spans))))
      (CompletableResultCode/ofSuccess))
    (^CompletableResultCode flush [_]
      (CompletableResultCode/ofSuccess))
    (^CompletableResultCode shutdown [_]
      (CompletableResultCode/ofSuccess))))

(defn- enabled? []
  (= "1" (System/getenv "DROMON_DEV_TRACE_TAP")))

(defonce ^:private initialized? (atom false))

(defn init!
  "Idempotently pre-builds an AutoConfigured OpenTelemetry SDK with our
  delegating SimpleSpanProcessor attached, and installs it into Telemere's
  `otel-default-providers_` so Telemere's handler uses the same SDK.

  Must be called BEFORE `server.logging/init-logging!` (which forces the
  Telemere providers delay). Safe to call when `DROMON_DEV_TRACE_TAP` is
  unset -- it becomes a no-op."
  []
  (when (and (enabled?) (not @initialized?))
    (try
      (let [processor (SimpleSpanProcessor/create (delegating-exporter))
            customizer (reify BiFunction
                         (apply [_ tpb _config]
                           (.addSpanProcessor ^SdkTracerProviderBuilder tpb processor)))
            ^AutoConfiguredOpenTelemetrySdkBuilder builder
            (-> (AutoConfiguredOpenTelemetrySdk/builder)
                (.addTracerProviderCustomizer customizer))
            ^AutoConfiguredOpenTelemetrySdk built (.build builder)
            ^OpenTelemetrySdk sdk (.getOpenTelemetrySdk built)
            providers {:logger-provider (.getLogsBridge sdk)
                       :tracer-provider (.getTracerProvider sdk)
                       :via :sdk-extension-autoconfigure
                       :auto-configured-sdk sdk}
            providers-var (resolve 'taoensso.telemere/otel-default-providers_)]
        (when providers-var
          (alter-var-root providers-var (constantly (delay providers))))
        (reset! initialized? true)
        (t/log! {:level :info :id ::initialized
                 :msg "Trace-tap initialized: SDK customizer attached"}))
      (catch Throwable e
        (t/log! {:level :error :id ::init-failed
                 :msg (str "Trace-tap init failed: " (ex-message e))
                 :error e})))))

(defn- trace-requested? [request]
  (or (= "1" (get-in request [:headers "x-dromon-trace"]))
      (= "1" (get-in request [:query-params "_dromon-trace"]))
      (= "1" (get (:query-params request) "_dromon-trace"))))

(defn- span-data->map [^SpanData sd]
  {:spanId (.getSpanId sd)
   :parentSpanId (.getParentSpanId sd)
   :traceId (.getTraceId sd)
   :name (.getName sd)
   :startNanos (.getStartEpochNanos sd)
   :durationNanos (- (.getEndEpochNanos sd) (.getStartEpochNanos sd))
   :status (str (.getStatusCode (.getStatus sd)))
   :attributes (into {}
                     (map (fn [[k v]] [(str (.getKey k)) (str v)]))
                     (.asMap (.getAttributes sd)))})

(defn- gzip-base64 ^String [^String s]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. baos)]
      (.write gz (.getBytes s "UTF-8")))
    (.encodeToString (Base64/getEncoder) (.toByteArray baos))))

(defn- serialize-spans [capture trace-id overflow?]
  (let [spans (->> (seq (:queue capture))
                   (filter (fn [^SpanData sd]
                             (or (nil? trace-id)
                                 (= trace-id (.getTraceId sd)))))
                   (map span-data->map)
                   vec)
        payload {:spans spans
                 :overflow overflow?}]
    (gzip-base64 (json/write-value-as-string payload))))

(defn wrap-trace-tap
  "Ring middleware that captures OTel spans for the current request when
  `X-Dromon-Trace: 1` (or `?_dromon-trace=1`) is present, and emits them
  on the `X-Dromon-Trace-Json` response header as gzip+base64 JSON.

  Place AFTER `wrap-otel-context` so the request-scoped span is already
  active. Zero cost when the trace flag is absent."
  [handler]
  (fn [request]
    (if-not (trace-requested? request)
      (handler request)
      (let [capture (make-capturing-exporter)
            cap-key (str (java.util.UUID/randomUUID))
            _ (swap! active-captures assoc cap-key capture)]
        (try
          (let [response (handler request)
                ;; Telemere stages ended spans through TWO 3-second buffers
                ;; (span-buffer1 -> span-buffer2 -> Span.end()), so a span
                ;; ended just before a tick can take up to ~7s to actually
                ;; reach the SimpleSpanProcessor. Poll for up to 9s and exit
                ;; once the count stops growing for 300ms.
                deadline (+ (System/currentTimeMillis) 9000)
                _ (loop [last-count -1
                         stable-since 0]
                    (let [now (System/currentTimeMillis)
                          c (.get ^AtomicInteger (:count capture))]
                      (cond
                        (>= now deadline) nil
                        (and (pos? c) (= c last-count) (>= (- now stable-since) 300)) nil
                        :else (do (Thread/sleep 50)
                                  (recur c (if (= c last-count) stable-since now))))))
                overflow? (.get ^AtomicBoolean (:overflow capture))
                header-val (serialize-spans capture nil overflow?)]
            (-> response
                (update :headers (fnil assoc {}) "X-Dromon-Trace-Json" header-val)))
          (finally
            (swap! active-captures dissoc cap-key)))))))
