# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Dromon is a Clojure-based multitenant FHIR R4B server. It generates Malli schemas from FHIR StructureDefinitions, uses those schemas to dynamically build Reitit routes, and stores resources in XTDB v2. Auth is handled by Ory Hydra (OAuth2), Kratos (identity), and Keto (authorization).

## Build & Run Commands

### Start the FHIR server (in-memory XTDB v2)
```bash
cd test-server && clj -X test-server.core/-main
# Listens on :8080 (HTTP) and :8443 (HTTPS)
# Health check: curl http://localhost:8080/default/fhir/metadata
# Switch backends/schemas via env: TEST_SERVER_STORE=mock TEST_SERVER_SCHEMAS=uscore8
```

### Generate Malli schemas from FHIR definitions
```bash
cd fhir-defintions-to-malli
mkdir -p target/staging/src   # must exist before JVM starts
clj -X com.breezeehr.main/generate-uscore!
# Outputs to fhir/malli/{r4b,xver,fhir-extensions,sdc,uscore8}/src/
```

### Run unit tests
```bash
# From any module with a :test alias:
cd test-server && clj -M:test             # Kaocha, includes fhir-server tests
cd fhir-server && clj -M:test             # Kaocha, fhir-server unit tests only
cd fhir-defintions-to-malli && clj -M:test # Cognitect test-runner
```

JVM args required for XTDB v2 tests (already in deps.edn):
`--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED`

### Inferno FHIR compliance tests
```bash
bb tls-setup       # one-time: adds fhir.local to /etc/hosts, generates dev cert
bb setup           # starts Postgres + Ory services via Docker
bb inferno-setup   # clones US Core Test Kit, patches docker-compose
bb inferno-test    # runs headless compliance suite (starts server, seeds data, runs tests)
bb inferno-down    # stops Inferno containers
bb teardown        # stops Ory containers
```

### Run with OpenTelemetry tracing (Jaeger UI)
```bash
# Start the dev stack with Jaeger all-in-one alongside Ory.
DROMON_OTEL=1 bb setup
# Boot the test-server with the :otel alias so the OTel SDK + OTLP exporter
# are on the classpath. Telemere's open-telemetry handler installs itself
# automatically when DROMON_OTEL=1 is set.
cd test-server && \
  DROMON_OTEL=1 \
  OTEL_SERVICE_NAME=dromon-fhir-server \
  OTEL_TRACES_EXPORTER=otlp \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
  OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
  TEST_SERVER_STORE=xtdb2-disk \
  clj -X:otel:store/xtdb2:malli/uscore8 test-server.core/-main
# Browse traces at http://localhost:16686 (service: dromon-fhir-server).
# Tear down with: bb teardown (removes the jaeger container too).
```

### Dev Utilities

#### Per-request span capture (`bb trace`)
Dev-only middleware that captures every OTel span emitted while serving a
single request and returns the span tree inline with the response, so you
can iterate without leaving the terminal. Disabled unless explicitly
opted into.

```bash
# Start the test-server with both OTel and the trace-tap enabled.
DROMON_OTEL=1 DROMON_DEV_TRACE_TAP=1 \
  OTEL_SERVICE_NAME=dromon-fhir-server \
  OTEL_TRACES_EXPORTER=otlp \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
  OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
  clj -X:otel:store/xtdb2:malli/uscore8 test-server.core/-main

# From the dromon repo root, in another terminal:
bb trace -s http://localhost:8080/default/fhir/metadata
# stderr: ASCII span tree (http/request -> children)
# stdout: the metadata JSON, ready to pipe to jq
```

The middleware short-circuits when neither `X-Dromon-Trace: 1` nor
`?_dromon-trace=1` are present, so steady-state requests are not affected.
Captured spans are gzip+base64 encoded into the `X-Dromon-Trace-Json`
response header. `bb trace` decodes the header, builds the parent/child
tree, and prints it to stderr; the body flows through to stdout.

### Babashka tasks (run from repo root)
`bb setup` / `bb teardown` -- Ory auth infrastructure
`bb inferno-check` -- smoke test (containers + server health)
`bb inferno-run` -- Inferno web UI for interactive testing

## Architecture

### Module dependency graph
```
test-server --> fhir-server --> fhir-store-protocol (IFHIRStore)
            \-> fhir-store-xtdb2 (or fhir-store-mock) --/
            \-> fhir/malli/uscore8 (or other malli pkgs, alias-controlled)

fhir-defintions-to-malli --> fhir-primitives --> malli-decimal
```

`fhir-server` no longer has a static dependency on any malli schema
package or store implementation. `test-server.core/build-config` selects
the store and schema package at startup; the schemas are loaded from
config (e.g. `test-server.schemas.uscore8/specs`) and resolved by
`server.core/resolve-schemas` via `requiring-resolve`. Add `:store/*`
and `:malli/*` aliases from `test-server/deps.edn` to put alternate
backends or schema packages on the classpath.

### Key modules
- **fhir-store-protocol** -- `IFHIRStore` protocol: create, read, update, delete, search, history, vread, transact-bundle
- **fhir-store-xtdb2** -- Primary backend; maps FHIR resources to dynamic SQL tables, stores original JSON in `fhir_source` column
- **fhir-store-mock** -- Atom-backed in-memory store for tests
- **fhir-server** -- Reitit routing, Ring handlers, JWT auth, Keto authorization, Muuntaja content negotiation, FHIR exception middleware. No static dep on any malli schema package or store impl.
- **test-server** -- Config-driven Integrant system wiring the above together. Picks store backend and schema package via `build-config` opts (or `TEST_SERVER_STORE` / `TEST_SERVER_SCHEMAS` env vars). Dependencies for stores and malli packages are pulled in via deps.edn aliases (`:store/xtdb2`, `:store/mock`, `:malli/uscore8`, ...).
- **fhir-defintions-to-malli** -- Downloads FHIR StructureDefinitions and generates Malli schemas + CapabilityStatement :multi schemas
- **fhir-primitives** -- FHIR primitive type definitions and lazy reference support for Malli
- **fhir/malli/** -- Generated schema packages (r4b, xver, fhir-extensions, sdc, uscore8); do not edit by hand

### How routing works
1. Generated capability schemas in `fhir/malli/uscore8/src/us_core/capability/` carry metadata: `:fhir/type`, `:fhir/interactions`, `:fhir/handlers`, `:fhir/operations`, `:fhir/cap-schema`
2. A consumer (e.g. `test-server`) provides a vector of schema specs (fully qualified symbols pointing to `full-sch` Vars); `server.core/resolve-schemas` does `requiring-resolve` on each, runs them through `capability-schema->server-schema`, and hands the result to the `:fhir/schemas` integrant component
3. `server.routing/build-fhir-routes` dynamically creates Reitit routes from schema metadata
4. All routes are tenant-scoped: `/:tenant-id/fhir/{ResourceType}/{id}`

### How schema generation works
`com.breezeehr.main/generate-uscore!` runs a 6-step pipeline:
1. R4B base definitions -> `fhir/malli/r4b/`
2. xver-r5.r4 cross-version extensions -> `fhir/malli/xver/`
3. FHIR Extensions IG -> `fhir/malli/fhir-extensions/`
4. SDC IG -> `fhir/malli/sdc/`
5. US Core STU8 profiles -> `fhir/malli/uscore8/`
6. CapabilityStatement :multi schemas -> `fhir/malli/uscore8/src/us_core/capability/`

Each step writes to a staging directory first, then to the final output. A shared atom tracks already-generated URLs to avoid duplication across packages.

### Auth stack
- **JWT**: `server.auth/wrap-jwt-auth` -- HS256 with `JWT_DEV_SECRET` env var (dev) or RS256 with JWKS from `JWKS_URL` (prod)
- **Authorization**: `server.keto/wrap-keto-authorization` -- checks Ory Keto for instance-level permissions (GET->read, POST/PUT/PATCH->write, DELETE->delete)
- **FHIR format**: `server.fhir-format` -- Malli encode/decode middleware for FHIR JSON wire format

### Middleware chain (in order)
telemere trace -> wrap-params -> muuntaja format -> fhir-exceptions -> fhir-decode -> store injection -> jwt-auth -> keto-authz -> handler

## Important Conventions

- The project is named `fhir-defintions-to-malli` (note the typo in "defintions") -- use this spelling consistently in paths and references
- Java 21+ is required (XTDB v2 dependency)
- `target/staging/src` must be created with `mkdir -p` before running schema generation (classloader needs it at JVM startup)
- Tenant ID `default` is used in dev/test
- Test patient ID is `Patient/123` (hardcoded in inferno runner)
- Docker network `ory-net` connects all Ory service containers
- The server uses virtual threads (ring-jetty9-adapter)
