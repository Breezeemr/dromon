# Dromon

<div align="center">
  <img src="docs/dromon_ship.png" alt="A majestic Byzantine dromon warship" width="600" />
</div>

A Clojure-based multitenant FHIR server with pluggable immutable storage backends, built on Reitit, Malli, and XTDB v2.

## The Name

The **Dromon** (from the Greek *dromōn*, "runner") was the primary warship of the Byzantine navy, known for its speed and maneuverability. Equipped with a bronze-tipped siphon at the prow, it discharged **Greek Fire** -- an incendiary weapon that burned even on water.

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

The server is built around the `IFHIRStore` protocol, which defines FHIR operations (create, read, update, delete, search, history, vread, transact-bundle). Storage backends implement this protocol, making the server database-agnostic.

`fhir-server` has no static dependency on any storage backend or Malli schema package. `test-server` selects both at startup -- the store via the `:store/*` aliases (or `TEST_SERVER_STORE`) and the schema package via the `:malli/*` aliases (or `TEST_SERVER_SCHEMAS`). Schemas are resolved from config by `server.core/resolve-schemas` using `requiring-resolve`.

Routes are generated dynamically from Malli schemas. Each schema carries metadata describing its FHIR type, supported interactions, handler functions, and custom operations. Reitit builds the route tree at startup.

All routes are tenant-scoped: `/:tenant-id/fhir/{ResourceType}/{id}`.

## Project Structure

```
dromon/
  bb.edn                        Babashka task runner
  docker/                       Ory Kratos/Keto/Hydra configs
  fhir-store-protocol/          IFHIRStore protocol definition
  fhir-store-xtdb2/             XTDB v2 backend (SQL, temporal queries)
  fhir-store-mock/              In-memory backend for testing
  fhir-server/                  Core server (routing, handlers, auth, middleware)
  fhir-terminology/             FHIR terminology service support
  fhir-primitives/              FHIR primitive types & lazy refs for Malli
  fhir-defintions-to-malli/     FHIR StructureDefinition -> Malli schema generator
  test-server/                  Runnable server, configurable store + schema package
```

### Dependency Graph

```
test-server --> fhir-server --> fhir-store-protocol
            \-> fhir-store-xtdb2 (or fhir-store-mock) --/
            \-> fhir/malli/uscore8 (or other malli pkgs, alias-controlled)

fhir-defintions-to-malli --> fhir-primitives --> com.breezeehr/malli-decimal (external)
```

## Prerequisites

- **Java 21** (required by XTDB v2)
- **Clojure CLI** (`clj` / `clojure`)
- **Babashka** (`bb`) for task automation
- **Podman** or **Docker** for integration tests and Ory services
- **mkcert** for local TLS certificates (optional, for Inferno tests)

## Quick Start

Start `test-server` with an in-memory XTDB v2 node and the US Core STU8 schema package:

```bash
cd test-server
clj -A:store/xtdb2:malli/uscore8 -X test-server.core/-main
```

The server starts on port `8080` (HTTP) and `8443` (HTTPS).

Test it:

```bash
curl http://localhost:8080/default/fhir/metadata
```

Switch backends or schema packages by changing the aliases (e.g. `-A:store/mock:malli/r4b`) or via env vars `TEST_SERVER_STORE` and `TEST_SERVER_SCHEMAS`.

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

### Running Inferno Tests

```bash
bb tls-setup        # one-time: hosts entry + dev cert
bb setup            # start Ory auth services
bb inferno-setup    # clone and build Inferno test kit
bb inferno-test     # run compliance tests
```

Results are written to `target/inferno-report.json`.

## Key Technologies

| Component | Version | Role |
|-----------|---------|------|
| Clojure | 1.12.0 | Language |
| XTDB | 2.1.0 | Temporal database backend |
| Malli | 0.14.0+ | Schema validation and route generation |
| Reitit | 0.7.0-alpha7 | HTTP routing |
| Jetty | (ring-jetty9) | HTTP server with virtual threads |
| Integrant | 0.13.1 | Component lifecycle |
| Buddy | 3.x | JWT authentication |
| Ory Hydra/Kratos/Keto | v2.2/v1.3/v0.12 | OAuth2, identity, authorization |

## Storage Backends

### XTDB v2 (`fhir-store-xtdb2`)

The primary backend. Maps FHIR resources to dynamic SQL tables, stores original JSON alongside exploded columns, and uses XTDB's native temporal features for version history. Supports 20+ FHIR search parameter types including date ranges, token searches, reference resolution, and composite parameters.

### Mock (`fhir-store-mock`)

Atom-backed in-memory store for testing. Tracks version history and supports all protocol operations. No external dependencies.

## FHIR Schema Generation

The `fhir-defintions-to-malli` project downloads official FHIR StructureDefinitions and generates Malli schemas. The `fhir-primitives` and `malli-decimal` libraries provide the type foundations, including support for FHIR's recursive type references and arbitrary-precision decimals.

The generator runs a 6-step pipeline that produces independent schema packages under `fhir/malli/`: `r4b`, `xver` (cross-version extensions), `fhir-extensions`, `sdc`, and `uscore8` (which also writes CapabilityStatement `:multi` schemas). Each package is consumable as its own deps.edn dependency.

```bash
cd fhir-defintions-to-malli
mkdir -p target/staging/src
clj -X com.breezeehr.main/generate-uscore!
```

## License

This project is licensed under the [Mozilla Public License 2.0](LICENSE).
