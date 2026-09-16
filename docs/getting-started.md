# Getting Started

This guide covers running Dromon locally, how the modules fit together, and the
commands you will use day to day. For the reasoning behind the immutable,
bitemporal storage model, see the [README](../README.md).

## Prerequisites

- **Java 21** (required by XTDB v2)
- **Clojure CLI** (`clj` / `clojure`)
- **Babashka** (`bb`) for task automation
- **Podman** or **Docker** for integration tests and Ory services
- **mkcert** for local TLS certificates (optional, for Inferno tests)

## Quick Start

Start `test-server` with an in-memory XTDB v2 node and the US Core STU8 schema
package:

```bash
cd test-server && clj -X:store/xtdb2:malli/uscore8 test-server.core/-main
```

The server listens on port `8080` (HTTP) and `8443` (HTTPS).

Test it:

```bash
curl http://localhost:8080/default/fhir/metadata
```

Tenant `default` is the one used in development. Switch backends or schema
packages by changing the aliases (for example `-X:store/mock:malli/r4b`), or
with the `TEST_SERVER_STORE` and `TEST_SERVER_SCHEMAS` environment variables.

## Architecture

```
                       test-server
                   (Integrant system)
                    /       |        \
            fhir-server    store    malli schemas
         (routing, auth,  (xtdb2 /   (r4b, uscore8,
          handlers, MW)    mock)      ...)
                \           |          /
                 fhir-store-protocol
                    (IFHIRStore)
```

The server is built around the `IFHIRStore` protocol, which defines the FHIR
operations: create, read, update, delete, search, history, vread, and
transact-bundle. Storage backends implement this protocol, which is what makes
the server database-agnostic.

`fhir-server` has no static dependency on any storage backend or Malli schema
package. `test-server` selects both at startup: the store via the `:store/*`
aliases (or `TEST_SERVER_STORE`) and the schema package via the `:malli/*`
aliases (or `TEST_SERVER_SCHEMAS`). Schemas are resolved from config by
`server.core/resolve-schemas` using `requiring-resolve`.

Routes are generated dynamically from Malli schemas. Each schema carries
metadata describing its FHIR type, supported interactions, handler functions,
and custom operations; Reitit builds the route tree at startup.

All routes are tenant-scoped: `/:tenant-id/fhir/{ResourceType}/{id}`.

## Dependency Graph

```
test-server --> fhir-server --> fhir-store-protocol
            \-> fhir-store-xtdb2 (or fhir-store-mock) --/
            \-> fhir/malli/uscore8 (or other malli pkgs, alias-controlled)

fhir-defintions-to-malli --> fhir-primitives --> com.breezeehr/malli-decimal (external)
```

## Storage Backends

### XTDB v2 (`fhir-store-xtdb2`)

The primary backend. FHIR JSON is exploded into discrete SQL columns mapped to
its schema counterparts, so every attribute is indexed for tabular search,
while the original JSON is retained in a `fhir_source` column. Version history
falls out of XTDB's native temporal support rather than being maintained
separately. Supports 20+ FHIR search parameter types including date ranges,
token searches, reference resolution, and composite parameters.

### Mock (`fhir-store-mock`)

Atom-backed in-memory store for testing. Tracks version history and supports
the core protocol operations, with no external dependencies. It implements
neither temporal protocol, which makes it a useful check that capability gating
actually works.

### Datomic (`fhir-store-datomic`)

Lives in a separate repository and is kept for benchmarking (see
`fhir-search-bench`), not as a runtime target here. It has system time only,
which is why the temporal protocols are split the way they are.

## Point-in-Time Reads

Two optional protocols in `fhir-store-protocol` extend `IFHIRStore` with
point-in-time access. They are deliberately separate, and split read from
write, so `satisfies?` is a real capability check:

| Protocol | Verbs | Implemented by |
|---|---|---|
| `ITemporalReadStore` | `temporal-axes`, `read-as-of`, `search-as-of`, `count-as-of-basis`, `resource-timeline` | fhir-store-xtdb2, fhir-store-datomic |
| `IValidTimeStore` | `put-valid-time`, `close-valid-time` | fhir-store-xtdb2 |

On the wire there are two selectors and two instance operations:

| Surface | Meaning |
|---|---|
| `_asOf` | System time: as the server knew it then |
| `_validAt` | Valid time: as it was true in the world |
| `GET [type]/[id]/$as-of` | One resource at a point in time; requires at least one selector |
| `GET [type]/[id]/$timeline` | Every version of one resource, with its temporal bounds |

```bash
# The patient as the record stood on 1 March
curl "http://localhost:8080/default/fhir/Patient/123/\$as-of?_asOf=2026-03-01T00:00:00Z"

# Every version, oldest first
curl "http://localhost:8080/default/fhir/Patient/123/\$timeline"
```

Four rules are enforced in `server.temporal`, and each exists because the
alternative fails silently:

- **A selector for an axis the store lacks is a 400, never a no-op.** Datomic
  has system time only, so `_asOf` works there and `_validAt` is refused.
  Ignoring it would answer the valid-now question while the caller believes
  they asked a historical one. `Prefer: handling=lenient` does not excuse this.
- **A plain `GET` is never temporal.** FHIR versions records, not facts, and
  has no valid-time axis. Time travel is a parameter or an operation, and an
  unqualified read keeps returning current state.
- **Temporal responses state their resolved basis** in `meta.tag`. An omitted
  `_asOf` means "latest indexed transaction", which is a different instant on
  every request, so the response names the concrete one it used.
- **A timeline omits valid-time bounds on a single-axis store** rather than
  emitting null ones. Null reads as end-of-time; absent means no such axis.

## Provenance and Attribution

The temporal axes answer *when* a version was written. `Provenance` answers
*who* wrote it and *why*. Dromon ships it as an ordinary FHIR resource type in
the uscore8 and breeze schema packages, so it is routed, validated, and
searchable like any other, and `server.compartment` indexes it under both
`patient` and `agent`.

Write provenance in the same transaction as the change it describes. A FHIR
`transaction` Bundle maps to `transact-transaction`, which the protocol defines
as all entries succeeding or all failing as a single database transaction. A
Bundle carrying the updated resource plus its `Provenance` therefore commits
both or neither.

```
POST /default/fhir/
{
  "resourceType": "Bundle",
  "type": "transaction",
  "entry": [
    { "resource": { "resourceType": "Observation", ... },
      "request": { "method": "PUT", "url": "Observation/obs-1" } },
    { "resource": { "resourceType": "Provenance",
                    "target": [{ "reference": "Observation/obs-1" }],
                    "recorded": "2026-03-01T00:00:00Z",
                    "agent": [{ "who": { "identifier": { "value": "agent-run-42" } } }] },
      "request": { "method": "POST", "url": "Provenance" } }
  ]
}
```

A `batch` Bundle gives no such guarantee: `transact-bundle` processes entries
independently, so the resource can land while its provenance fails. Use
`transaction` whenever the attribution has to hold.

Two limits worth knowing:

- **The server does not synthesize `Provenance` from the authenticated
  principal.** A writer that wants attribution includes it in the Bundle. The
  JWT identity is used for authorization, not for generating provenance
  records.
- **There is no store-level transaction metadata API.** Provenance is carried
  as FHIR data rather than as annotations on the underlying transaction, which
  keeps it portable across backends and searchable through the normal FHIR
  surface. What the store contributes is system time: every version records the
  instant its transaction was indexed, which dates the attribution without a
  caller-supplied clock.

## Conformance-Driven Configuration

The server is configured by the conformance resources of the implementation
guide it targets, not by hand-written route or validation code. Four artifact
types do the work:

| Artifact | Consumed by | Determines |
|---|---|---|
| CapabilityStatement | `com.breezeehr.capability-statement`, then `server.routing/build-fhir-routes` | Which resource types exist and which interactions each supports |
| StructureDefinition | `com.breezeehr.fhir-defintions-to-malli` | The Malli schema for each profiled resource: the typed validator for requests and responses |
| SearchParameter | `server.search-registry` | The per-type search registry the store turns into SQL |
| OperationDefinition | `:fhir/operations` metadata, then `server.routing` | Custom operations mounted per type, and what `/metadata` advertises |

The CapabilityStatement is both input and output. It drives which routes are
emitted, and the statement served at `/{tenant-id}/fhir/metadata` is generated
from the same source, so the two cannot disagree.

SearchParameters are shipped as JSON inside the schema package, under a
URL-mapped classpath path such as `org/hl7/fhir/us/core/SearchParameter/`.
`server.search-registry` combines each one with Malli schema introspection to
produce a descriptor the store layer uses to build SQL conditions, which is why
no FHIR field name is hardcoded in the backend.

### TypeScript types (planned)

Generating TypeScript types for profiled resources and operations is planned
but not implemented in this repository. The StructureDefinitions that produce
the Malli validators already carry everything the declarations would need, so
a client could be given the same profile-accurate types the server validates
against, from the same source.

## FHIR Schema Generation

The `fhir-defintions-to-malli` module downloads official FHIR
StructureDefinitions and generates Malli schemas. `fhir-primitives` and
`malli-decimal` provide the type foundations, including FHIR's recursive type
references and arbitrary-precision decimals.

```bash
cd fhir-defintions-to-malli
mkdir -p target/staging/src
clj -X com.breezeehr.main/generate-uscore!
```

`target/staging/src` must exist before the JVM starts, because the classloader
needs it at startup.

The generator runs a six-step pipeline producing independent schema packages
under `fhir/malli/`:

1. R4B base definitions -> `fhir/malli/r4b/`
2. xver-r5.r4 cross-version extensions -> `fhir/malli/xver/`
3. FHIR Extensions IG -> `fhir/malli/fhir-extensions/`
4. SDC IG -> `fhir/malli/sdc/`
5. US Core STU8 profiles -> `fhir/malli/uscore8/`
6. CapabilityStatement `:multi` schemas -> `fhir/malli/uscore8/src/us_core/capability/`

Each step writes to a staging directory first, then to the final output, and a
shared atom tracks already-generated URLs to avoid duplication across packages.
Each package is consumable as its own deps.edn dependency. The generated
packages under `fhir/malli/` are not edited by hand.

To take up a revised implementation guide, point the generator at it, rerun the
pipeline, and run the test suites. Tightened cardinalities surface as
validation failures, changed search expressions as search contract failures,
and newly declared resource types as routes without handlers.

## Running the Tests

```bash
cd test-server && clj -M:test              # Kaocha, includes fhir-server tests
cd fhir-server && clj -M:test              # Kaocha, fhir-server unit tests only
cd fhir-defintions-to-malli && clj -M:test # Cognitect test-runner
```

XTDB v2 needs reflective NIO access for Apache Arrow. The required
`--add-opens` arguments are already in the relevant `deps.edn` files.

## Babashka Tasks

All tasks run from the repo root via `bb <task>`:

| Task | Description |
|------|-------------|
| `setup` | Start local integration env (Postgres, Ory Kratos/Keto/Hydra) |
| `teardown` | Stop and remove integration env containers |
| `tls-setup` | Add `fhir.local` to `/etc/hosts` and generate dev TLS cert |
| `inferno-setup` | Clone US Core Test Kit, build images, patch compose files |
| `inferno-test` | Run Inferno US Core compliance tests headlessly |
| `inferno-check` | Smoke test: verify containers and server health |
| `inferno-run` | Start Inferno web UI for interactive testing |
| `inferno-down` | Stop Inferno containers |
| `trace` | Fetch a URL and print the server-side span tree (dev) |

### Running Inferno Tests

```bash
bb tls-setup        # one-time: hosts entry + dev cert
bb setup            # start Ory auth services
bb inferno-setup    # clone and build Inferno test kit
bb inferno-test     # run compliance tests
```

Results are written to `target/inferno-report-<suite>.json`, and to the stable
`target/inferno-report.json`.

`bb inferno-test` defaults to the US Core v6.1.0 suite (`us_core_v610`,
`--groups 2`). The runner is suite-configurable through environment variables,
so the same flow can target other suites the image provides:

```bash
INFERNO_SUITE=smart_stu2 INFERNO_GROUPS=all bb inferno-test
```

| Variable | Meaning |
|---|---|
| `INFERNO_SUITE` | Suite id (default `us_core_v610`) |
| `INFERNO_GROUPS` | Space/comma group ids, or `all` for the whole suite (default `2`) |
| `INFERNO_FHIR_URL` | Server base URL (default the local `default` tenant) |
| `INFERNO_PATIENT_IDS` | `patient_ids` input (default `123`) |
| `INFERNO_INPUTS` | Full replacement for the `--inputs` tokens |

## Observability

OpenTelemetry instrumentation ships behind `DROMON_OTEL=1`. Start the dev stack
with a Jaeger all-in-one container alongside Ory, then boot the server with the
`:otel` alias so the SDK and OTLP exporter are on the classpath:

```bash
DROMON_OTEL=1 bb setup
```

```bash
cd test-server && \
  DROMON_OTEL=1 \
  OTEL_SERVICE_NAME=dromon-fhir-server \
  OTEL_TRACES_EXPORTER=otlp \
  OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318 \
  OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
  TEST_SERVER_STORE=xtdb2-disk \
  clj -X:otel:store/xtdb2:malli/uscore8 test-server.core/-main
```

Traces are at http://localhost:16686 under service `dromon-fhir-server`.
`bb teardown` removes the Jaeger container along with the Ory ones.

For iterating without leaving the terminal, `DROMON_DEV_TRACE_TAP=1` enables
dev-only middleware that captures the spans from a single request and returns
them with the response. `bb trace -s <url>` prints the span tree to stderr and
passes the body through to stdout. The middleware short-circuits unless
`X-Dromon-Trace: 1` or `?_dromon-trace=1` is present, so steady-state requests
are unaffected.

## Key Technologies

| Component | Version | Role |
|-----------|---------|------|
| Clojure | 1.12.x | Language |
| XTDB | 2.2.0-beta1 | Bitemporal database backend |
| Malli | 0.20.1 | Schema validation and route generation |
| Reitit | 0.10.1 | HTTP routing |
| Jetty | ring-jetty9 0.39.2 | HTTP server with virtual threads |
| Integrant | 0.13.1 | Component lifecycle |
| Buddy | 3.x | JWT authentication |
| Ory Hydra/Kratos/Keto | v2.2/v1.3/v0.12 | OAuth2, identity, authorization |

## Further Reading

- [API, routing, and validation](design/api.md)
- [Server architecture](design/server.md)
- [Storage backends](design/backends.md)
- [Authentication](design/auth.md)
- [Multitenancy](design/multitenancy.md)
- [Keto authorization](keto-authorization.md)
