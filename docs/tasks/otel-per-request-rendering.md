# Quick-and-Dirty Per-Request OTel Rendering

> **Blocked by:** [otel-telemere-instrumentation.md](otel-telemere-instrumentation.md).
> Spans must exist before we can render them.

## Problem

Once OTel spans are flowing through Jaeger, developers still have to leave
their terminal and click through the Jaeger UI to see the span tree for a
request they just issued. For the hot iteration loop (make a change, curl
an endpoint, inspect the shape of the trace), that round-trip is too slow.

We want a one-request feedback path: a developer sends a request with an
opt-in flag, the server responds with the usual FHIR payload plus a
compact, human-readable dump of every span that ran under that request.

## Approach

Build a dev-only middleware that:

1. Intercepts any request with header `X-Dromon-Trace: 1` (or query param
   `?_dromon-trace=1`), installs a scoped in-memory `SpanExporter` on the
   OTel SDK for the duration of that request, and captures every span
   whose trace-id matches the incoming request span.
2. After the handler returns, serializes the captured spans as a flat
   JSON array (span-id, parent-span-id, name, start, duration, status,
   attributes) and either:
   - Appends them as a response header `X-Dromon-Trace-Json: <gzip+base64>`
     (for curl-friendly shell inspection), or
   - Writes the payload as a `resource.meta.tag` on the FHIR response body
     when the response is already a FHIR resource (cleaner for browser
     tools), or
   - Streams them to a sidecar SSE endpoint `/default/fhir/_trace/stream`
     that any dev can tail with `curl -N`.
3. Ships a small `bb trace <curl args>` babashka task that wraps curl,
   injects the header, pretty-prints the span tree as ASCII on stderr, and
   passes the body through to stdout so existing pipes keep working:

   ```
   $ bb trace -s https://fhir.local:3001/default/fhir/Patient/123
   http.request 42.1ms
   ├── auth.jwt.verify 0.3ms
   ├── authz.keto.check 3.1ms
   ├── fhir.decode 0.2ms
   └── store.read 34.6ms
       └── xtdb.query 31.2ms
           ├── xtdb.plan 2.4ms
           └── xtdb.execute 27.8ms
   {"resourceType":"Patient","id":"123",...}
   ```

The tree is built client-side from the JSON header so the server never has
to ASCII-render. Widths auto-scale to terminal columns via `tput cols`.

## Implementation Steps

1. Add `fhir-server.dev.trace-tap` namespace with:
   - `make-capturing-exporter` — wraps an in-memory bounded list.
   - `wrap-trace-tap` — ring middleware, threads a fresh exporter through
     a `Context.makeCurrent()` wrapper, collects spans on response, emits
     the header.
   - Registered only when `DROMON_DEV_TRACE_TAP=1` or in the test-server
     `:dev` profile. Never on in production.
2. The capturing exporter is a minimal `SpanExporter` that appends to an
   `ArrayList`. A `SimpleSpanProcessor` forwards spans to it synchronously,
   so by the time the handler finishes and the span is ended, the list is
   ready to serialize.
3. Serializer uses Cheshire; keep it under 200 lines of Clojure.
4. `bb/src/server/trace_cli.clj` implements the `bb trace` task: parses
   args, runs curl, reads the header, decodes gzip+base64, prints the
   tree. Use `clojure.tools.cli` for option parsing.
5. Document in `dromon/CLAUDE.md` under a new **Dev Utilities** section.

## Testing

- Manual: start the dev server with `DROMON_DEV_TRACE_TAP=1`, run:
  `bb trace -s https://fhir.local:3001/default/fhir/Patient/123` and
  verify the tree matches what Jaeger shows for the same request.
- Verify that `bb trace` without `DROMON_DEV_TRACE_TAP=1` set on the
  server falls back gracefully (no header -> prints a friendly "trace
  capture not enabled" notice, passes body through).
- Verify zero cost when the header is absent: the middleware should
  short-circuit before installing the scoped exporter, so steady-state
  `bb inferno-test` numbers do not regress.

## Out of Scope

- Non-dev trace capture. Production traces go through Jaeger / real OTel
  backend; `X-Dromon-Trace` is a local-only affordance.
- Metric capture. Only spans.
- Multi-request aggregation, flame graphs, percentile roll-ups. If you
  want those, open Jaeger.

## Stretch

- Colorize the tree by span kind (auth, store, xtdb, other).
- When the tree shows a span > 100ms and there is a Jaeger URL to link to,
  include it in the stderr output so developers can jump to the Jaeger UI
  for a deeper look.
- Automatically save the last N captured traces to
  `dromon/target/traces/<timestamp>.json` for offline comparison across
  code changes (good for reproducing "why did this get slower" bugs).

## References

- [otel-telemere-instrumentation.md](otel-telemere-instrumentation.md) — prerequisite
- `io.opentelemetry.sdk.trace.export.SimpleSpanProcessor` — sync forwarder
- `io.opentelemetry.sdk.trace.export.SpanExporter` — interface to implement
