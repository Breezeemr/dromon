# Server Architecture

## Overview
The core HTTP server will be powered by `info.sunng/ring-jetty9-adapter`. Jetty 9 provides robust asynchronous support, WebSocket capabilities, and high performance, which are beneficial for handling a large volume of FHIR API requests.

## Server Model
1. **Ring Adapter**: We'll use the Ring adapter to map HTTP requests to our Clojure handler functions. 
2. **Asynchronous Handlers**: FHIR operations, especially complex searches and backend database queries, can be time-consuming. We will leverage Jetty 9's async capabilities (via `ring.util.http-response` and core.async/manifold/completable-futures) to avoid blocking threads.
3. **Component Management**: To manage the lifecycle of the server, database connection pools, and external service clients (like Ory endpoints), we will use a state management library like `mount` or `integrant`.

## Middleware Stack
The standard Ring middleware stack will include:
- Request/Response logging.
- GZIP compression.
- Content-type negotiation (`application/fhir+json`).
- Parsing of multipart forms (if we support binary uploads).
- Error handling that formats exceptions into FHIR `OperationOutcome` resources.

## Decision Points
- **Lifecycle Library**: Should we use Integrant or Mount? Integrant is more explicit and declarative (data-driven), while Mount is simpler and relies on namespace state. Given the complexity of multiple backends, auth providers, etc., Integrant might be preferable.
- **Asynchronous Model**: Do we want to build the entire handling chain asynchronously? If we use Reitit's async routing, we need to ensure all database calls (Datomic, XTDB) are also handled non-blockingly, or wrapped in a dedicated thread pool to avoid exhausting Jetty worker threads.
- **Observability**: We should include OpenTelemetry or Prometheus metrics collection middleware early on.

## Alternatives
- **Aleph / Netty**: Extremely fast and heavily async, but the ecosystem around Jetty 9 via the specified adapter is very stable and well-understood.
- **Immutant**: Built on Undertow, very solid, but development has slowed compared to the Ring Jetty 9 adapter.

## Additional Questions
1. Do we need to support HTTP/2 immediately? The Jetty 9 adapter can configure this.
2. What are our logging requirements (JSON structured logging vs plain text)?
