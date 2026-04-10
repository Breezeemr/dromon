# Dromon FHIR Server Resource Schema Properties

The Dromon FHIR server uses Malli schemas to define the resources it supports. These schemas are annotated with specific properties that drive the server's routing, capability statement generation, and database interactions.

Here is a breakdown of the properties used on the resource schemas (as seen in `server.core/make-basic-schema` and `server.core/PatientSchema`):

## `:fhir/type`
- **Type:** String (e.g., `"Patient"`, `"Observation"`)
- **Usage:** 
  - **Routing:** In `server.routing/build-resource-routes`, this property dictates the base path for the resource routes (e.g., `/:tenant-id/fhir/Patient`).
  - **Request Context:** The type is injected into the request map as `:fhir/resource-type` so that the handlers know which resource type they are operating on.
  - **Database:** Handlers pass this resource type to the `IFHIRStore` protocol. In the `XTDBStore` implementation, this directly translates to the SQL table name being queried or written to.
  - **Metadata:** Used in the `CapabilityStatement` to list the supported resource types.

## `:fhir/interactions`
- **Type:** Map of interaction keyword to configuration map (e.g., `{:read {} :search-type {:search-parameters [...]}}`)
- **Usage:**
  - **Routing:** Determines exactly which HTTP routes are generated for the resource type in `server.routing/build-interaction-routes`. If an interaction is missing from the keys of this map, its corresponding route will not be created. The configuration map for each interaction is injected into the request as `:fhir/interaction-config` to be used by the handler.
  - **Metadata:** Used heavily in `server.handlers/capability-statement` to advertise the exact RESTful interactions the server supports for this specific resource type.

## `:fhir/handlers`
- **Type:** Map of interaction keyword to fully qualified function symbol (e.g., `{:read 'server.handlers/read-resource}`)
- **Usage:**
  - **Routing:** Used during route generation in `server.routing/build-interaction-routes` to dynamically resolve the Ring handler function that will service the request for an interaction. This allows for customized handlers per resource type if needed, rather than hardcoding.

## `:xtdb/collection`
- **Type:** String (e.g., `"Patient"`)
- **Usage:** 
  - **Extraneous:** Currently, this property appears in the schema definitions in `server.core` but is **not actually used** anywhere in the active codebase. 
  - The XTDB v2 store implementation ([fhir-store-xtdb2/src/fhir_store_xtdb2/core.clj](file:///home/dspiteself/dromon/fhir-store-xtdb2/src/fhir_store_xtdb2/core.clj)) relies entirely on the `resource-type` parameter passed from the handlers (which itself comes from `:fhir/type`), converting it to a name/string to use as the table name in SQL queries (e.g., `(name resource-type)`).
