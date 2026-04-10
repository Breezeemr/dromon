# Multiple Backends Design

## Overview
The FHIR server must support multiple pluggable backends. Our initial targets are **Datomic** and **XTDB (v2)**. Both are Datalog-based, immutable, bitemporal (XTDB) or point-in-time (Datomic) databases, making them highly suitable for FHIR's `_history` and audit requirements.

## Abstraction Layer
To support multiple backends, we will define a set of Clojure Protocols or Multimethods that encapsulate database operations.
```clojure
(defprotocol IFHIRStore
  (create-resource [this resource])
  (read-resource [this resource-type id])
  (vread-resource [this resource-type id vid])
  (update-resource [this resource])
  (delete-resource [this resource-type id])
  (search [this resource-type params])
  (history [this resource-type id]))
```

## Datomic Backend
- **Schema**: Datomic requires an explicit schema. We need a strategy for mapping FHIR's extensive model to Datomic attributes.
- **Querying**: Native Datalog is excellent for traversing graph relationships (like FHIR `Reference` types).

## XTDB v2 Backend
- **Schema**: XTDB v2 uses SQL and Datalog. It is schema-on-write but flexible.
- **Temporal Features**: Built-in system time and valid time make implementing FHIR resource versioning (`_history`) almost native.

## Test Environments
Testing the different database backends requires specific considerations to ensure correct functionality and avoid environment-related crashes.

### XTDB v2 Testing
- **Java NIO Access**: XTDB v2 utilizes Apache Arrow extensively, which requires reflective access to Java NIO on JDK 16+. All test runners and REPLs must supply the JVM argument `--add-opens=java.base/java.nio=ALL-UNNAMED` to prevent `InaccessibleObjectException` crashes (`java.nio.Buffer.address`).
- **In-Memory Node**: Tests should leverage an in-memory XTDB node to ensure clean state and isolate test data.

### Datomic Testing
- **In-Memory Database**: For isolated testing, an in-memory database (`datomic:mem://test-realm`) should be used.
- **Schema Initialization**: Unlike XTDB's flexible schema-on-write, Datomic requires all required attributes to be transacted upfront. Because defining the extensive FHIR schema is heavy, unit tests will likely need to rely on shared test utilities to bootstrap the `datomic:mem://` environment with the requisite schema before making Datalog assertions, or mock the database boundaries altogether if purely testing business logic.

## Decision Points
- **Data Modeling**: We store FHIR resources by natively exploding the FHIR JSON into discrete database attributes (columns) mapped directly to their schema counterparts dynamically. This enables `XTDB` to index all attributes intrinsically for lightning-fast tabular searches while retaining full historical snapshots without dealing with opaque JSON extraction penalties.
- **Tenant Isolation**: Dromon implements a "Database per Tenant (Siloed)" strategy implicitly inside the `IFHIRStore` protocol operations. The routing layer pulls `/:tenant-id/fhir/` to distinguish operations, which can be configured per persistence node later.
- **Search Parameter Translation**: We will map complex FHIR search modifiers (e.g., `:exact`, `:contains`, chained parameters `Patient?general-practitioner.name=Doe`) to `XTQL` pipeline blocks logically utilizing the natively stored columns.

## Additional Questions
1. Xtdb v2 will be the "primary" or default for the initial MVP.
2. We are planning to support full transaction bundles (`Bundle` type `transaction`) out of the box? This needs to be handled at the database layer.
3. It would be nice to have the abstraction layer support streaming large result sets (e.g., for Bulk Data Export) but this is not a requirement for the initial MVP.
