# FHIR R4 HTTP Spec Compliance — Task List

Ordered by: prerequisites first, then easy -> hard within each tier.
Spec reference: https://hl7.org/fhir/R4/http.html

Inferno baseline: 391 passed, 12 failed, ~101 skipped, 0 errors
Inferno current:  505 passed,  0 failed,   0 skipped, 0 errors

## Open tasks


## Completed

- [trace-tap-concurrency-isolation.md](trace-tap-concurrency-isolation.md) — `wrap-trace-tap` now sits outside `wrap-telemere-trace` and opens a short sentinel `trace-tap/request-root` span on entry to capture a trace id. After the handler returns, the drained queue is filtered to spans matching that id, so concurrent `bb trace` callers each see only their own span tree; the outer `http/request` span is included in the serialized payload. Validated with 5 parallel `bb trace` calls plus 3 background Patient searches: each response carries exactly 2 spans (`http/request` + `auth/jwt.verify`) with a distinct trace id.

- [otel-bundle-entry-spans.md](otel-bundle-entry-spans.md) — Transaction Bundles now emit a `bundle/entry` span per entry under `bundle/transaction`, as a sibling of `store/transact-bundle`. Implemented in `server.handlers/transaction` by wrapping per-entry coercion in a `t/trace!` block tagged with `:index`, `:method`, `:resource-type`, and `:id`.
- [fhir-root-trailing-slash-500.md](fhir-root-trailing-slash-500.md) — `POST /default/fhir/` (trailing slash) no longer 500s. Added a `strip-trailing-slash` wrapper in `server.core/fhir-app` that rewrites any non-root URI ending in `/` before the Reitit router sees it, so both `/default/fhir` and `/default/fhir/` reach the transaction handler. Unit test covers both forms against the mock store.

- [otel-per-request-rendering.md](otel-per-request-rendering.md) — Dev-only per-request span capture and ASCII tree rendering via `bb trace`. Server-side `server.dev.trace-tap` middleware (opt-in via `DROMON_DEV_TRACE_TAP=1`) plus a `bb trace` babashka task.
- [otel-telemere-instrumentation.md](otel-telemere-instrumentation.md) — Wired Telemere's OpenTelemetry handler behind `DROMON_OTEL=1`, added a Jaeger all-in-one container, broadened span coverage (keto, jwt, fhir-decode, store ops, store node start, bundle entries), and added a `with-otel-context` helper so XTDB v2 native spans nest under the request.
- [kratos-cipher-secret-config.md](kratos-cipher-secret-config.md) — Removed the unused kratos container from the integration environment and added a post-`docker run` running-check so future container start failures surface loudly.
