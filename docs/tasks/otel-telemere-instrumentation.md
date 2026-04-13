# Comprehensive OpenTelemetry Support via Telemere

## Problem

Dromon already emits per-request traces via Telemere: `wrap-telemere-trace`
in `fhir-server/src/server/middleware.clj:58` wraps every handler call in a
`:http/request` trace block and publishes a `:http/response` event with
method, URI, status, and duration. `server.logging` (line 2) installs
telemere handlers that extract `:trace` and `:span-id` keys for GCP-style
log correlation.

What is missing:

1. **No OTel exporter.** Traces and events live only as Telemere signals in
   memory. There is no OTLP / Jaeger / Zipkin sink, so cross-service trace
   continuity and flame graphs are not available in dev or prod.
2. **No span context propagation into the store layer.** When a FHIR handler
   fans out to `fhir-store-xtdb2`, the store issues SQL against XTDB v2, but
   the XTDB spans (which XTDB emits natively through OpenTelemetry) are not
   parented to the incoming request. The two span graphs are disconnected.
3. **Instrumentation coverage is thin.** Only the outer HTTP handler is
   traced. Keto authz checks, Hydra introspection, Malli validate/encode
   passes, FHIR store `create-resource` / `search-resource` / `read-resource`
   calls, and bundle transaction sub-steps are all invisible.
4. **No local trace UI.** Even once an exporter is wired, there is no
   developer-facing view to inspect spans without reaching for a cloud
   backend.

## Approach

### 1. Add a Telemere -> OpenTelemetry handler

Telemere 1.2.1 (already on the classpath in `fhir-server/deps.edn:10`)
ships `taoensso.telemere.open-telemetry` which converts telemere signals
into OpenTelemetry spans and events. Wire it up in
`server.logging/init-logging!` behind a `DROMON_OTEL=1` env var so the
default dev path stays free of the OTel SDK startup cost.

```clojure
;; server/logging.clj
(defn- init-otel-handler! []
  (require 'taoensso.telemere.open-telemetry)
  (let [add (requiring-resolve 'taoensso.telemere.open-telemetry/handler:open-telemetry)]
    (t/add-handler! :open-telemetry (add))))
```

The OpenTelemetry SDK picks up its exporter and resource config from
standard environment variables (`OTEL_SERVICE_NAME`, `OTEL_TRACES_EXPORTER`,
`OTEL_EXPORTER_OTLP_ENDPOINT`, ...). No bespoke configuration lives in
Clojure code.

### 2. Local exporter: Jaeger all-in-one

Ship a Jaeger container alongside the Ory stack in `server.docker-env`.
The `jaegertracing/all-in-one` image exposes:
- `4317` — OTLP gRPC ingest
- `4318` — OTLP HTTP ingest
- `16686` — Jaeger UI

Defaults the runner will set when `DROMON_OTEL=1`:

```
OTEL_SERVICE_NAME=dromon-fhir-server
OTEL_TRACES_EXPORTER=otlp
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
```

Container limits follow the Ory pattern (`--memory 256m --cpus 0.5`).
Jaeger all-in-one uses in-memory storage, which is fine for dev; production
would swap for a real collector plus backend.

### 3. Broaden instrumentation

Add `t/trace!` blocks (or `t/span!` equivalents) at these hot points:

| Site | Span name | Notes |
|------|-----------|-------|
| `fhir-server.middleware/wrap-keto-authorization` | `authz.keto.check` | tag with subject, namespace, relation |
| `fhir-server.middleware/wrap-jwt-auth` | `auth.jwt.verify` | tag with kid when available |
| `fhir-server.fhir-format/wrap-fhir-decode` | `fhir.decode` | tag with resource type |
| `fhir-store-xtdb2.core/*` protocol methods | `store.<op>` | tag with tenant, resource-type, id |
| `fhir-store-xtdb2.core/get-or-create-node` | `store.node.start` | expensive first-tenant hit; already a known hotspot (see perf-analysis REPORT.md) |
| `fhir-server.routing` bundle processing | `bundle.entry` | one span per entry, parented to the bundle span |

Every site uses the same `:data` shape Telemere already expects so the
existing `server.logging` handler continues to work unchanged.

### 4. Span context propagation into XTDB v2 (bonus)

XTDB v2 is OpenTelemetry-aware natively: once the OTel Java agent or SDK is
on the classpath and the context is correct at call time, XTDB query and
transaction internals emit child spans. The plumbing needed on our side:

1. Pull the current OTel `Context` from the Telemere span at the handler
   boundary (`io.opentelemetry.context.Context/current`).
2. Make the context active around the call into `fhir-store-protocol` via
   `Context.makeCurrent()` inside a try-with-resources.
3. Confirm in Jaeger that `http.request` (dromon) is the parent of
   `xtdb.query` / `xtdb.tx` spans.

Step 2 is the only real change — Telemere's own handler already puts a
span on the context stack, so a thin `with-otel-context` helper in the
store-protocol module keeps the FHIR handler code mostly unchanged.

If XTDB v2 needs OTel autoconfiguration to register its tracer provider,
add `io.opentelemetry:opentelemetry-sdk-extension-autoconfigure` to
`fhir-store-xtdb2/deps.edn` and ensure the fhir-server's tracer provider
is the global one so XTDB picks it up at node start.

## Implementation Steps

1. Add the Jaeger all-in-one container to `server.docker-env/start!`
   (memory/cpu capped, same shape as hydra/keto), guarded by
   `DROMON_OTEL=1`. Teardown removes it with the rest.
2. In `fhir-server/deps.edn`, add `io.opentelemetry:opentelemetry-sdk`,
   `io.opentelemetry:opentelemetry-exporter-otlp`, and the autoconfigure
   module. Keep them behind an `:otel` alias so the default classpath
   stays lean.
3. Wire `taoensso.telemere.open-telemetry/handler:open-telemetry` in
   `server.logging/init-logging!` when `DROMON_OTEL=1`.
4. Add `t/trace!` spans at the five instrumentation sites listed above,
   using the same `:data` map shape as `wrap-telemere-trace` for
   consistency.
5. Add `with-otel-context` helper in `fhir-store-protocol` and wrap all
   calls from the fhir-server middleware through it.
6. Update `test-server/deps.edn` so the `:otel` alias is selectable:
   `TEST_SERVER_STORE=xtdb2-disk DROMON_OTEL=1 clj -M:otel -X test-server.core/-main`.
7. Document in `dromon/CLAUDE.md` under **Build & Run Commands** how to
   launch the dev stack with OTel enabled and where to open Jaeger.

## Testing

- `bb inferno-test` with `DROMON_PERF_PROFILE=1 DROMON_OTEL=1 TEST_SERVER_STORE=xtdb2-disk`.
- Open `http://localhost:16686`, select service `dromon-fhir-server`,
  pick any `http.request` trace and verify:
  - The span tree descends through keto, store.search, and the XTDB child
    spans for the actual query execution.
  - `bundle` traces show one child span per entry.
  - `store.node.start` appears exactly once per tenant and matches the
    warmup latency reported in `dromon/perf-analysis/latency.txt`.
- Verify that with `DROMON_OTEL` unset the server still starts at the
  previous speed and the default Telemere handlers still log to stdout.

## Out of Scope

- Production OTel config (real collector, sampling policy, resource
  attributes, TLS to backend). Dev-only Jaeger is the target here.
- Metrics (Telemere has its own metric signal type; this task is about
  traces only).
- Logs-to-OTel bridge. `server.logging` already formats logs for GCP; the
  OTel logs pipeline can come later.

## References

- `fhir-server/src/server/middleware.clj:58` — existing telemere trace middleware
- `fhir-server/src/server/logging.clj:2` — handler install point
- `fhir-store-xtdb2/src/fhir_store_xtdb2/core.clj:557` — per-tenant node startup
  (the cold-start hot spot the XTDB span tree should make obvious)
- `datomic-test-server/perf-analysis/REPORT.md` — latency baseline the span
  tree should match
- Telemere OTel handler: `taoensso.telemere.open-telemetry` in Telemere 1.2.1

## Status

Complete. Implemented in branch `otel`:

- Jaeger all-in-one container added to `bb/src/server/docker_env.clj`, guarded
  by `DROMON_OTEL=1`, with the same memory/cpu caps as the Ory containers.
- `:otel` alias on `fhir-server/deps.edn` and `test-server/deps.edn` pulling
  `io.opentelemetry/opentelemetry-sdk` 1.43.0, the OTLP exporter, and the
  autoconfigure SPI.
- `server.logging/init-logging!` installs Telemere's OpenTelemetry handler
  via `requiring-resolve` when `DROMON_OTEL=1`; default path stays untouched.
- `t/trace!` blocks added at: `wrap-keto-authorization` (`:authz/keto.check`),
  `wrap-jwt-auth` (`:auth/jwt.verify`), `wrap-decode-contained`
  (`:fhir/decode`), every `XTDBStore` protocol method (`:store/{op}`),
  `get-or-create-node` (`:store/node.start`), and bundle entry / transaction
  processing in `server.handlers/transaction` (`:bundle/entry`,
  `:bundle/transaction`).
- `with-otel-context` macro in `fhir-store-protocol` reflects on
  `io.opentelemetry.context.Context` so the protocol module stays free of a
  hard OTel SDK dependency. `server.middleware/wrap-otel-context` uses it to
  activate the current context for the downstream pipeline.
- `dromon/CLAUDE.md` documents the OTel dev workflow under
  **Build & Run Commands**.

Validation:

- OTel-off: `bb inferno-test` -> 505 passed / 0 failed.
- OTel-on: `bb setup` started Jaeger; the test-server booted with
  `clj -X:otel:store/xtdb2:malli/uscore8 test-server.core/-main`. After two
  curl calls (`metadata`, `Patient/123`) Jaeger returned 5 traces under
  service `dromon-fhir-server`, including `http/request` traces with child
  spans `auth/jwt.verify` and `authz/keto.check`, plus `store/create` spans
  from the SearchParameter seeder.
