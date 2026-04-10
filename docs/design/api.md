# API, Routing, and Validation Design

## Overview
The API layer will be built using **Reitit** for fast, data-driven routing and **Malli** for data-driven schema validation.

## Routing (Reitit)
Reitit will handle all incoming HTTP requests, dispatching them based on the HTTP method and path according to the FHIR RESTful specification.
- Ex: `GET /Patient`, `POST /Observation`, `GET /Encounter/123/_history/1`.

**Interceptors/Middleware:**
We will use Reitit's sophisticated middleware/interceptor chain to handle:
1. **Content Negotiation**: Automatically parsing `application/json` (and `application/fhir+json`) bodies into Clojure maps using `reitit.ring.middleware.muuntaja`.
2. **Database Context Injection**: Attaching the initialized `IFHIRStore` protocol instance into the Request map (`:fhir/store`) routing the handler layer.
3. **Authentication Injection**: Verifying JWTs and attaching Identity contexts to the request map.
4. **Paging & Links**: Generating standard FHIR navigation links (`next`, `prev`) for search results.

## Validation (Malli)
FHIR mandates strict validation of incoming resources. Malli will serve as our core validation engine.
1. **Schema Generation**: We can convert FHIR JSON schemas or StructureDefinitions directly into Malli syntax.
2. **Coercion**: Malli will handle coercion, such as translating string dates into `java.time.Instant` objects if necessary.
3. **Response Validation**: We can enforce Malli schemas on outbound payloads to prevent data leakage and ensure standard compliance.

## Routing Strategy
Our Reitit routes are generated dynamically based on the Malli schema definitions of each FHIR resource type you supply. We have formalized a **Database per Tenant (Siloed)** architecture. Therefore, all generated routes use a path-based tenant prefix: `/:tenant-id/fhir/...`.
This allows the server to immediately recognize the selected tenant environment by inspecting the URL request path.

## Decision Points
- **Static vs Dynamic Routing**: We have selected Dynamic generation of Reitit route maps based on Malli schemas to minimize routing repetition.
- **Schema Management**: Maintaining Malli schemas for every FHIR resource version is a huge task. Will we write a script to auto-generate Malli schemas from the official FHIR specification, or rely on an existing library?

## Alternatives
- **Compojure & clojure.spec**: The traditional stack. However, Reitit is faster, and Malli provides better programmatic schema manipulation and error reporting (essential for FHIR `OperationOutcome` generation).
- **Pedestal**: Offers a very robust interceptor model. Reitit provides similar interceptor capabilities but with a simpler learning curve and faster routing tree.

## Additional Questions
1. Which FHIR Release are we targeting? (R4 is the most common for SMART, but R4B and R5 exist). 
2. Should we implement custom Malli validators for FHIR-specific path validations (e.g., validate that `reference` strings follow the `ResourceType/id` format)?
3. How will we expose the Swagger/OpenAPI documentation, or do we only rely on the standard FHIR `CapabilityStatement`?
