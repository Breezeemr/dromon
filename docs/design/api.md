# API, Routing, and Validation Design

## Overview
The API layer is built on **Reitit** for fast, data-driven routing and **Malli** for
data-driven schema validation. The server targets **FHIR R4B** with **US Core STU8**
profiles.

## Routing (Reitit)
Reitit handles all incoming HTTP requests, dispatching them by HTTP method and path
according to the FHIR RESTful specification (e.g. `GET /Patient`, `POST /Observation`,
`GET /Encounter/123/_history/1`).

Routes are **generated dynamically** from the Malli schema metadata of each FHIR resource
type, rather than hand-written, to avoid routing repetition. `server.routing/build-fhir-routes`
reads `:fhir/type`, `:fhir/interactions`, `:fhir/handlers`, and `:fhir/operations` off the
generated capability schemas and emits the Reitit route tree. All routes are tenant-scoped
with a path prefix: `/:tenant-id/fhir/...` (see `multitenancy.md`).

**Interceptors / Middleware** handle, via Reitit's chain:
1. **Content Negotiation** — `application/json` / `application/fhir+json` parsing through
   `reitit.ring.middleware.muuntaja`.
2. **Database Context Injection** — the initialized `IFHIRStore` instance is attached to the
   request map.
3. **Authentication Injection** — JWT verification + identity context (see `auth.md`).
4. **Paging & Links** — standard FHIR `next` / `prev` navigation links for search results.

## Validation (Malli)
Malli is the core validation engine for inbound and outbound FHIR resources.

- **Schema generation** — Malli schemas are **auto-generated from FHIR StructureDefinitions**
  by the `fhir-defintions-to-malli` module (`com.breezeehr.main/generate-uscore!`), not
  maintained by hand. The pipeline emits R4B base, cross-version extensions, the FHIR
  Extensions IG, SDC, and US Core STU8 packages.
- **Coercion** — Malli coerces wire values to domain types, including precision-aware FHIR
  temporal types (`Year`, `YearMonth`, `LocalDate`, `OffsetDateTime`, `Instant`).
- **Response validation** — outbound payloads are validated to prevent data leakage and ensure
  conformance.

## Conformance
Conformance is published through the standard FHIR **`CapabilityStatement`** at
`/{tenant-id}/fhir/metadata`. The statement is generated from a canonical `capability` data map
emitted by the schema generator. There is no Swagger/OpenAPI surface.

## Open items
Remaining open API/validation decisions are tracked in
[`../open-decisions.md`](../open-decisions.md) (custom FHIR-specific Malli validators such as
`reference` string-format checks).
