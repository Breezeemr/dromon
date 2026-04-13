# FHIR R4 HTTP Spec Compliance — Task List

Ordered by: prerequisites first, then easy -> hard within each tier.
Spec reference: https://hl7.org/fhir/R4/http.html

Inferno baseline: 391 passed, 12 failed, ~101 skipped, 0 errors
Inferno current:  504 passed,  0 failed,   0 skipped, 0 errors

## Open tasks

## Completed

- [otel-per-request-rendering.md](otel-per-request-rendering.md) — Dev-only per-request span capture and ASCII tree rendering via `bb trace`. Server-side `server.dev.trace-tap` middleware (opt-in via `DROMON_DEV_TRACE_TAP=1`) plus a `bb trace` babashka task.
- [otel-telemere-instrumentation.md](otel-telemere-instrumentation.md) — Wired Telemere's OpenTelemetry handler behind `DROMON_OTEL=1`, added a Jaeger all-in-one container, broadened span coverage (keto, jwt, fhir-decode, store ops, store node start, bundle entries), and added a `with-otel-context` helper so XTDB v2 native spans nest under the request.
- [kratos-cipher-secret-config.md](kratos-cipher-secret-config.md) — Removed the unused kratos container from the integration environment and added a post-`docker run` running-check so future container start failures surface loudly.
