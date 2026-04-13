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

## Approach

Two options, pick based on how invasive you want the fix to be:

### Option A — Move the tap outside the telemere wrapper

Reorder middleware so `wrap-trace-tap` sits outside `wrap-telemere-trace`.
The tap then knows the trace id of the outer span because by the time
it serializes, the `http/request` span has ended and its `SpanContext`
is on the response-path `Context`. Drain the queue, filter by
`trace-id`, return only spans matching the request's own trace. Trivial
change if the middleware order allows it — but needs a quick check
that `wrap-otel-context` still fires early enough for downstream
middleware and the store to see the active context.

### Option B — Capture the trace id at request start

In the tap middleware, before calling the inner handler, start a short
sentinel span (`trace-tap/request-root`), record its trace-id, pass the
handler the request, then on response drain the queue and filter by
the captured trace-id. The sentinel span can be discarded from the
serialized output or kept as a diagnostic. This works regardless of
middleware order, at the cost of one bookkeeping span per traced
request.

Option A is simpler; Option B is robust to future middleware shuffles.

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
