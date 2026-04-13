# FHIR R4 HTTP Spec Compliance — Task List

Ordered by: prerequisites first, then easy -> hard within each tier.
Spec reference: https://hl7.org/fhir/R4/http.html

Inferno baseline: 391 passed, 12 failed, ~101 skipped, 0 errors
Inferno current:  504 passed,  0 failed,   0 skipped, 0 errors

## Open tasks

- [otel-telemere-instrumentation.md](otel-telemere-instrumentation.md) — Wire comprehensive OpenTelemetry via Telemere and a local Jaeger exporter; propagate context into XTDB v2 so its native spans nest under the FHIR request.
- [otel-per-request-rendering.md](otel-per-request-rendering.md) — Dev-only per-request span capture and ASCII tree rendering via `bb trace`. Blocked by the OTel instrumentation task.
- [kratos-cipher-secret-config.md](kratos-cipher-secret-config.md) — Fix the silent kratos container failure caused by un-expanded `$KRATOS_CIPHER_SECRET` in docker/kratos.yml; add a post-`docker run` running-check so future bugs of this shape fail loudly.
