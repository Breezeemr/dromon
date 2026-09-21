# Backends Design

## Overview
FHIR resources are persisted behind a pluggable store abstraction. **XTDB v2 is the primary
backend** (`fhir-store-xtdb2`); a `fhir-store-mock` atom store backs unit tests. A Datomic
backend lives in a separate `fhir-store-datomic` repo and is kept only for benchmarking
(see `fhir-search-bench`), not as a runtime target here. Both candidate engines are immutable
and bitemporal/point-in-time, which suits FHIR's `_history` and audit requirements.

## Abstraction Layer
Backends implement the `IFHIRStore` protocol (`fhir-store-protocol`), which encapsulates
create / read / vread / update / delete / search / history plus the transaction and tenant
lifecycle operations:

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

## XTDB v2 Backend
- **Data modeling** — FHIR JSON is natively exploded into discrete SQL columns mapped to its
  schema counterparts, so every attribute is intrinsically indexed for tabular search, while the
  original JSON is retained in a `fhir_source` column and full history is preserved without
  opaque-JSON extraction penalties.
- **Schema** — XTDB v2 is schema-on-write; tables are created dynamically per resource type.
- **Temporal features** — built-in system time and valid time make FHIR versioning (`_history`)
  nearly native.
- **Query mode** — search is translated to **SQL by default** (`:query-mode :sql` in
  `fhir-store-xtdb2/core.clj`); an **optional XTQL path** (`query_xtql.clj`,
  `:query-mode :xtql`) exists and is parity-tested against the SQL path. Both use `:sql` ASSERT
  ops for optimistic concurrency.
- **Transaction bundles** — `Bundle` type `transaction` is supported at the store layer via
  `transact-transaction`.

## Tenant Isolation
Dromon implements a "Database per Tenant (Siloed)" strategy: each `tenant-id` maps to a distinct
XTDB node / connection pool, selected by the `/:tenant-id/fhir/` route prefix. Tenant
provisioning is explicit via the `IFHIRStore` `create-tenant` / `warmup-tenant` /
`delete-tenant` lifecycle. See `multitenancy.md`.

## Transaction Metadata
Every write verb takes an `opts` map carrying `:tx-metadata`, an open map of provenance the
host builds and the store persists **in the same transaction as the data** — see
[`../tasks/fhir-store-tx-metadata-channel.md`](../tasks/fhir-store-tx-metadata-channel.md).
Where each backend keeps it:

| backend | storage home | status |
|---|---|---|
| `fhir-store-datomic` | the transaction entity every write already names, which is also the value of `:fhir/version-id` | planned |
| `fhir-store-xtdb2` | a per-tenant `fhir_tx_metadata` row written as an extra op inside the *same* `xt/execute-tx`, keyed by the transaction's system time | planned |
| `fhir-store-mock` | the per-version history record | planned |
| `CompartmentFilteringStore` | delegates to its base | planned |

A backend with no home for the stamp **must not implement `ITxMetadataStore`**. Accepting the
key and discarding it is permitted — the opts arity still has `:if-match` to honour — but only
because a caller can ask `supports-tx-metadata?` *before* the call and decide its own policy.
A store that claimed the capability and dropped the stamp would make an unattributed write
indistinguishable from an attributed one, which is exactly what the split prevents.

## Test Environments
### XTDB v2 Testing
- **Java NIO access** — XTDB v2 uses Apache Arrow, which needs reflective NIO access on
  JDK 16+. Test runners and REPLs must pass
  `--add-opens=java.base/java.nio=ALL-UNNAMED` (and the Arrow-specific `--add-opens`) to avoid
  `InaccessibleObjectException`. JDK 24+ additionally needs
  `--sun-misc-unsafe-memory-access=allow` (JEP 498). These args are already in `deps.edn`.
- **In-memory node** — tests use an in-memory XTDB node for clean, isolated state.

### Mock Store Testing
The atom-backed `fhir-store-mock` covers handler/business-logic tests without standing up a
database boundary.

## Open items
Remaining open backend decisions are tracked in
[`../open-decisions.md`](../open-decisions.md) (streaming large result sets / Bulk Data Export;
the long-term SQL-vs-XTQL direction).
