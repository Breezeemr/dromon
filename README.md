# Dromon

<div align="center">
  <img src="docs/dromon_ship.png" alt="A majestic Byzantine dromon warship" width="600" />
</div>

A Clojure-based multitenant FHIR server with pluggable immutable storage backends, built on Reitit, Malli, and XTDB v2.

## The Name

The **Dromon** (from the Greek *dromōn*, "runner") was the primary warship of the Byzantine navy, known for its speed and maneuverability. Equipped with a bronze-tipped siphon at the prow, it discharged **Greek Fire** -- an incendiary weapon that burned even on water.

## Architecture

```
                    demo-fhir-server
                   (Integrant system)
                    /             \
            fhir-server      fhir-store-xtdb2
         (routing, auth,       (XTDB v2 SQL
          handlers, MW)         temporal DB)
                \                /
             fhir-store-protocol
               (IFHIRStore)
```

The server is built around the `IFHIRStore` protocol, which defines FHIR operations (create, read, update, delete, search, history, vread, transact-bundle). Storage backends implement this protocol, making the server database-agnostic.

Routes are generated dynamically from Malli schemas. Each schema carries metadata describing its FHIR type, supported interactions, handler functions, and custom operations. Reitit builds the route tree at startup.

All routes are tenant-scoped: `/:tenant-id/fhir/{ResourceType}/{id}`.

## Project Structure

```
dromon/
  bb.edn                        Babashka task runner
  docker/                       Ory Kratos/Keto/Hydra configs
  fhir-store-protocol/          IFHIRStore protocol definition
  fhir-store-xtdb2/             XTDB v2 backend (SQL, temporal queries)
  fhir-store-datomic/           Datomic backend
  fhir-store-mock/              In-memory backend for testing
  fhir-server/                  Core server (routing, handlers, auth, middleware)
  fhir-primitives/              FHIR primitive types & lazy refs for Malli
  malli-decimal/                Arbitrary-precision decimal schema for Malli
  fhir-defintions-to-malli/     FHIR StructureDefinition -> Malli schema generator
  demo-fhir-server/             Runnable server with XTDB v2
```

### Dependency Graph

```
demo-fhir-server --> fhir-server --> fhir-store-protocol
                 \-> fhir-store-xtdb2 --/

fhir-defintions-to-malli --> fhir-primitives --> malli-decimal
```

## Prerequisites

- **Java 21** (required by XTDB v2)
- **Clojure CLI** (`clj` / `clojure`)
- **Babashka** (`bb`) for task automation
- **Podman** or **Docker** for integration tests and Ory services
- **mkcert** for local TLS certificates (optional, for Inferno tests)

## Quick Start

Start the demo server with an in-memory XTDB v2 node:

```bash
cd demo-fhir-server
clj -X demo.core/-main
```

The server starts on port `8080` (HTTP) and `8443` (HTTPS).

Test it:

```bash
curl http://localhost:8080/default/fhir/metadata
```

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

### Datomic (`fhir-store-datomic`)

Datomic-based backend (work in progress).

## FHIR Schema Generation

The `fhir-defintions-to-malli` project downloads official FHIR StructureDefinitions and generates Malli schemas. The `fhir-primitives` and `malli-decimal` libraries provide the type foundations, including support for FHIR's recursive type references and arbitrary-precision decimals.

## License

This project is licensed under the [Mozilla Public License 2.0](LICENSE).
