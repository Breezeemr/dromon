# Multitenancy Design

## Overview
The FHIR server serves multiple isolated client organizations securely within a single
deployment.

## Approaches considered
1. **Siloed (Database per Tenant)** — physical isolation; each tenant gets a separate XTDB
   node/cluster (or Datomic database). Highest isolation, simple per-tenant wipe/backup, at the
   cost of operational overhead and harder cross-tenant analytics.
2. **Schema/Namespace per Tenant** — more applicable to Postgres than generic Datalog stores.
3. **Row-level / logical isolation** — a single database with a `tenant_id` tag per record;
   cheaper, but risks leakage if a query misses the tenant filter.

## Decision: Siloed (Database per Tenant) + Path-Based Routing
Dromon uses the **Database per Tenant (Siloed)** approach. Every tenant maps to a completely
separate database (XTDB node), with no sharing.

### API routing
The tenant ID is extracted from the **URL route**: `/:tenant-id/fhir/...`. Top-level middleware
identifies the tenant from the path and attaches the corresponding store connection to the
request map, making the target tenant unambiguous.

### Database layer
The `IFHIRStore` implementation routes connections per tenant; each `tenant-id` maps to an
independent node / connection pool. **Tenant provisioning is explicit** via the protocol's
`create-tenant` / `warmup-tenant` / `delete-tenant` lifecycle — the `test-server` seeder calls
`create-tenant {:if-exists :ignore}` and `warmup-tenant` at boot, shifting cold-start cost out
of the first request. See `backends.md` and `docs/tasks/fhir-store-tenant-lifecycle-protocol.md`.

### Authentication & authorization
- **Kratos** — tenant membership can be stored in Kratos identity `traits` (design; see
  `auth.md` for current runtime auth status).
- **Keto** — tenant-scoped relationships (`User U is a member of Tenant T`) let permissions
  descend from the tenant namespace.

## Open items
Remaining open multitenancy decisions are tracked in
[`../open-decisions.md`](../open-decisions.md) (a single user accessing multiple tenants
simultaneously; fully-automatic / on-demand tenant provisioning).
