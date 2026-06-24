# Fix `bb trace` cross-request span contamination

## Problem

The dev-only trace-tap middleware (`fhir-server.dev.trace-tap`) is not
safe for concurrent use. Validation under 5 parallel `bb trace` calls
plus 3 background FHIR requests showed every one of the 5 trace
responses returning a byte-identical span payload that contained every
in-flight request's spans, not just the caller's. The existing
namespace docstring acknowledges a potential leak, but in practice the
contamination is total: no isolation at all once more than one request
runs at once.

## Why it happens

The current design registers a single global delegating `SpanExporter`
on the `SdkTracerProvider` at startup and attaches per-request
`ConcurrentLinkedQueue` captures. When the middleware drains the queue,
it takes every span the exporter saw during the request window, not
only spans whose trace-id matches the request's own root. Trace-id
filtering was dropped during implementation because
`Context.current()` inside the middleware does not yet contain the
outer `http/request` span (it is opened by `wrap-telemere-trace` which
wraps `wrap-otel-context` which wraps the tap).

## Solution (shipped)

`wrap-trace-tap` now sits outside `wrap-telemere-trace` and, on entry,
opens a short sentinel `trace-tap/request-root` span to capture the
request's trace-id. After the handler returns, the drained queue is
filtered to spans matching that trace-id, so concurrent `bb trace`
callers each see only their own span tree. This works regardless of
middleware order, at the cost of one bookkeeping span per traced
request.

## Testing

- Serialized case (baseline): `bb trace` against `metadata` returns
  only spans from that request.
- Concurrent case:
  ```
  for i in 1 2 3 4 5; do bb trace http://localhost:8080/default/fhir/metadata 2> trace.$i.txt & done
  for i in 1 2 3; do curl "http://localhost:8080/default/fhir/Patient?_id=123" & done
  wait
  diff trace.1.txt trace.2.txt  # should differ (different trace ids, possibly different span counts)
  ```
  Each `trace.*.txt` must contain at most one `http/request` root span
  and its descendants, nothing from the other in-flight requests.
- `bb inferno-test` (OTel off) must still pass 505/505 — the trace-tap
  namespace must not load on the default path, same as today.

## Also fix

- `bb trace http://localhost:8080/default/fhir/metadata` currently
  shows only `auth/jwt.verify` in the rendered tree. Jaeger shows the
  matching trace has `http/request` as a root with `auth/jwt.verify`
  as its child, so the tap is closing the drain before the outer span
  ends. Whichever fix above is picked should also ensure the outer
  `http/request` span is included in the serialized payload (it is the
  whole point of the feature).
- Update the namespace docstring once the leak is fixed so future
  readers know the dev tool is safe under parallel use.

## References

- `fhir-server/src/server/dev/trace_tap.clj` — current implementation +
  docstring acknowledging the leak.
- Validation report that quantified the leak under parallel load.
- `otel-per-request-rendering.md` — original design that called for
  trace-id filtering.
