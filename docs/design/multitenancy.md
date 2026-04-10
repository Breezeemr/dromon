# Multitenancy Design

## Overview
The FHIR server must support a robust multitenancy model to serve multiple isolated client organizations securely within a single deployment.

## Multitenancy Approaches in FHIR
There are generally three ways to achieve multitenancy in a FHIR database:
1. **Siloed (Database per Tenant)**: Physical isolation. Every tenant gets a totally separate XTDB node/cluster or Datomic database.
   - **Pros**: Highest isolation, simple to wipe a tenant, easy per-tenant backup.
   - **Cons**: High operational overhead, scaling connection pools becomes complicated, difficult to do cross-tenant analytics if occasionally needed.
2. **Schema/Namespace per Tenant**: (More applicable to Postgres than generic Datalog stores).
3. **Row-Level/Attribute Visibility (Logical Isolation)**: A single database where every record/resource is tagged with a `tenant_id`.
   - **Pros**: Easy to maintain, single unified schema, lower resource usage.
   - **Cons**: Risk of data leakage if queries miss the tenant filter.

## Proposed Strategy: Siloed (Database per Tenant) + Path-Based Routing
We have decided to proceed with the **Database per Tenant (Siloed)** approach. Every tenant will get a completely separate database (XTDB node or Datomic DB).

### API Routing
To support this, the tenant ID will be extracted from the **URL route**: `/{tenant-id}/Patient`. 
By prefixing every route with the tenant ID, the API gateway or top-level middleware can immediately identify the tenant context and attach the corresponding database connection to the request map.

Route injection (e.g., `/{tenant-id}/...`) makes the target tenant unambiguous and easily routable.

### Database Layer
Our `IFHIRStore` protocol will route connections based on the tenant.
```clojure
(defprotocol IFHIRStore
  (create-resource [this tenant-id resource])
  (read-resource [this tenant-id resource-type id])
  ;; ...
  )
```
- **Datomic / XTDB**: Every tenant `tenant-id` will map to a completely distinct and independent database connection pool or node configuration. There is no sharing of databases.

### Authentication & Authorization
- **Kratos**: Users can belong to tenants. We can store tenant membership in the Kratos identity `traits`.
- **Keto**: We establish Keto relationships defining tenant hierarchy: `User U is a member of Tenant T`. All permissions within the FHIR server can then logically descend from this tenant namespace.

## Decision Points Complete
- **Tenant Extraction**: We use path-based routing (`/:tenant-id/fhir/Patient`)
- **Database Architecture**: **Database per Tenant (Siloed)** approach is accepted. Every tenant gets a totally separate XTDB node or Datomic database.

## Additional Questions
1. Does a single practitioner/user need to access multiple tenants simultaneously (e.g., an admin dashboard spanning clinics)? If so, logical isolation is heavily favored over physical isolation.
2. How should tenant provisioning be handled automatically?
