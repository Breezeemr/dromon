(ns server.dev.trace-tap
  "Dev-only per-request OpenTelemetry span capture.

  When `DROMON_DEV_TRACE_TAP=1` is set, [[init!]] pre-builds an
  AutoConfigured OpenTelemetry SDK with an extra `SimpleSpanProcessor`
  whose exporter is a global shim that forwards every ended span to
  every currently-registered request capture. The pre-built SDK is
  installed into Telemere's `otel-default-providers_` delay so the
  Telemere OpenTelemetry handler adopts the SAME SDK instance -- no
  reflection on private final fields, no second SDK.

  Concurrency isolation. [[wrap-trace-tap]] is installed OUTSIDE the
  telemere trace middleware so that by the time it drains the capture
  queue, the outer `http/request` span has already ended and is
  visible to the SimpleSpanProcessor. On entry it starts a short
  sentinel span (`trace-tap/request-root`) and makes it current, so
  that telemere's `http/request` span and all descendants inherit the
  sentinel's trace id. After the handler returns and the buffer has
  drained, the middleware filters the queue to spans matching the
  sentinel's trace id only -- so concurrent traced requests each see
  only their own span tree. The sentinel itself is omitted from the
  serialized payload.

  The Ring middleware short-circuits when neither the `X-Dromon-Trace`
  header nor the `_dromon-trace` query param are present, so the
  steady-state hot path is one atom deref per span emit (no captures
  registered means no work)."
  (:require [jsonista.core :as json]
            [taoensso.telemere :as t])
  (:import [io.opentelemetry.api.trace Span Tracer]
           [io.opentelemetry.api.common Attributes]
           [io.opentelemetry.context Context Scope]
           [io.opentelemetry.sdk.trace.export SpanExporter SimpleSpanProcessor]
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
(defonce ^:private tracer_ (atom nil))

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
        (reset! tracer_ (.get (.getTracerProvider sdk) "dromon.trace-tap"))
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

(def ^:private sentinel-span-name "trace-tap/request-root")

(defn- serialize-spans [capture trace-id overflow?]
  (let [spans (->> (seq (:queue capture))
                   (filter (fn [^SpanData sd]
                             (and (or (nil? trace-id)
                                      (= trace-id (.getTraceId sd)))
                                  (not= sentinel-span-name (.getName sd)))))
                   (map span-data->map)
                   vec)
        payload {:spans spans
                 :overflow overflow?}]
    (gzip-base64 (json/write-value-as-string payload))))

(defn wrap-trace-tap
  "Ring middleware that captures OTel spans for the current request when
  `X-Dromon-Trace: 1` (or `?_dromon-trace=1`) is present, and emits them
  on the `X-Dromon-Trace-Json` response header as gzip+base64 JSON.

  Place OUTSIDE `wrap-telemere-trace` so that by the time the queue is
  drained, the outer `http/request` span has already ended and is visible
  to the SimpleSpanProcessor. On entry, starts a short sentinel span and
  makes it current; telemere's `http/request` span and all descendants
  become its children and share its trace id, which is used to filter
  the drained queue for this request only. Zero cost when the trace flag
  is absent."
  [handler]
  (fn [request]
    (if-not (trace-requested? request)
      (handler request)
      (let [capture (make-capturing-exporter)
            cap-key (str (java.util.UUID/randomUUID))
            ^Tracer tracer @tracer_
            ^Span sentinel (when tracer
                             (-> (.spanBuilder tracer sentinel-span-name)
                                 (.startSpan)))
            trace-id (some-> sentinel (.getSpanContext) (.getTraceId))
            ^Context ctx (when sentinel (.with (Context/current) sentinel))
            ^Scope scope (when ctx (.makeCurrent ctx))]
        (swap! active-captures assoc cap-key capture)
        (try
          (let [response (handler request)
                _ (when scope (.close scope))
                _ (when sentinel (.end sentinel))
                ;; Telemere stages ended spans through TWO 3-second buffers
                ;; (span-buffer1 -> span-buffer2 -> Span.end()), so a span
                ;; ended just before a tick can take up to ~7s to actually
                ;; reach the SimpleSpanProcessor. Poll for up to 9s and exit
                ;; once the count stops growing for 300ms AND an http/request
                ;; span for our trace-id has arrived.
                deadline (+ (System/currentTimeMillis) 9000)
                http-root-seen? (fn []
                                  (some (fn [^SpanData sd]
                                          (and (= "http/request" (.getName sd))
                                               (or (nil? trace-id)
                                                   (= trace-id (.getTraceId sd)))))
                                        (seq (:queue capture))))
                _ (loop [last-count -1
                         stable-since 0]
                    (let [now (System/currentTimeMillis)
                          c (.get ^AtomicInteger (:count capture))]
                      (cond
                        (>= now deadline) nil
                        (and (pos? c)
                             (= c last-count)
                             (>= (- now stable-since) 300)
                             (http-root-seen?)) nil
                        :else (do (Thread/sleep 50)
                                  (recur c (if (= c last-count) stable-since now))))))
                overflow? (.get ^AtomicBoolean (:overflow capture))
                header-val (serialize-spans capture trace-id overflow?)]
            (-> response
                (update :headers (fnil assoc {}) "X-Dromon-Trace-Json" header-val)))
          (finally
            (swap! active-captures dissoc cap-key)
            (when scope (try (.close scope) (catch Throwable _)))
            (when sentinel (try (.end sentinel) (catch Throwable _)))))))))
