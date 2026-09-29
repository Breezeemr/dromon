# Server Architecture

## Overview
The HTTP server is powered by `info.sunng/ring-jetty9-adapter`. It runs on **virtual threads**,
so blocking FHIR operations (complex searches, backend queries) do not exhaust a bounded worker
pool — each request gets its own virtual thread and the handler chain stays plainly synchronous.

## Server Model
1. **Ring Adapter** — the Ring adapter maps HTTP requests to Clojure handler functions.
2. **Concurrency** — virtual threads (Loom) back the Jetty adapter. The handler chain is
   synchronous; there is no async/core.async/manifold routing. Blocking store calls are safe
   because each runs on its own virtual thread.
3. **Component Management** — **Integrant** manages the lifecycle of the server, per-tenant store
   connections, and external service clients. `test-server` wires the system from data-driven
   config (`build-config`), selecting the store backend and schema package via aliases / env
   vars. Integrant was chosen over Mount for its explicit, data-driven wiring, which suits the
   multiple-backend / multiple-schema configuration surface.

## Middleware Stack
In order: telemere trace → `wrap-params` → muuntaja format → fhir-exceptions → fhir-decode →
store injection → jwt-auth → keto-authz → handler. This covers content-type negotiation
(`application/fhir+json`), request/response handling, and error formatting into FHIR
`OperationOutcome` resources.

## Observability
OpenTelemetry instrumentation is shipped and enabled behind `DROMON_OTEL=1` (Telemere's
OpenTelemetry handler, a Jaeger all-in-one dev container, and span coverage across keto, jwt,
fhir-decode, store ops, store node start, and bundle entries). Telemere also provides structured
logging. See `docs/tasks/otel-telemere-instrumentation.md`.

## Known limitations

### Telehealth signalling is single-instance

`Appointment/{id}/$telehealth-signal` (`server.telehealth`) keeps its mailbox
sessions in process memory. Signalling state is ephemeral by design and peers
renegotiate after a reconnect, but it means a deployment must run a single
server instance or route both participants of an appointment to the same
instance (sticky sessions). With several unsticky instances, a message posted to
one instance is invisible to a long poll parked on another, and the call fails
silently. Sessions are dropped when a `bye` is consumed and swept after two
hours idle. Long polls are capped at 55 seconds, below common proxy idle
timeouts.

Patient tokens need `patient/Appointment.cu`: the POST is scored as `create`
and the waiting room updates `Appointment.reasonCode`. The scope is registered
in `bb/src/server/smart_grant.clj` and requested by the cabotage2 portal.

## Open items
Remaining open server decisions are tracked in
[`../open-decisions.md`](../open-decisions.md) (HTTP/2 support).
