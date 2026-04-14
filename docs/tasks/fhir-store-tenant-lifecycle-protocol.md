# FHIR Store Tenant Lifecycle Protocol

## Problem

`IFHIRStore` (`fhir-store-protocol/src/fhir_store/protocol.clj`) is
entirely resource-scoped: every method takes a `tenant-id` but there is
no method to create, destroy, or warm up a tenant. The side effects of
having no tenant lifecycle today:

1. **Implicit lazy creation is leaking into hot paths.**
   - `fhir-store-xtdb2/core.clj:591 get-or-create-node` spins up an XTDB
     node on the *first* request against a tenant. That first request
     pays ~2.5 s of cold-start cost (`store/node.start` span in
     today's OTel comparison, 2 calls × 2 493 ms each).
   - `fhir-store-datomic/core.clj:60 ensure-tenant-conn!` does the
     equivalent for a Datomic peer: creates the database, transacts
     the schema in 1 000-datum batches, and caches the connection.
     First hit eats the schema transact (~290 ms) and the
     classloader warm-up.
   - `fhir-store-mock` has no explicit lifecycle at all; tenants appear
     in its `state` atom as a side effect of the first write.

   Callers that want a deterministic "hot tenant" today have to issue
   a dummy read (e.g. `GET /Patient?_count=1`) just to prime the
   backend. `dromon/bb/src/server/inferno_runner.clj` does exactly this
   via `warmup-store!` on line 124; `datomic-test-server`'s runner
   delegates to the same helper. This works by accident.

2. **No deterministic cleanup for tests and multi-tenant provisioning.**
   Tearing down a tenant in tests today requires resetting the whole
   store and re-seeding, or in the datomic case, deleting the H2
   database files out-of-band. There is no way to say "give me a clean
   tenant" without bouncing the process.

3. **Setup and teardown are asymmetric across backends.**
   `test-server/seeder` in both dromon and datomic-test-server has to
   know that "seeding SearchParameters" will implicitly create the
   default tenant. Explicit lifecycle would make this intention
   visible at the seam instead of buried inside the store.

## Proposed protocol additions

Add three methods to `IFHIRStore`, placed immediately after the
docstring block for `resource-deleted?`:

```clojure
(create-tenant
  [this tenant-id]
  [this tenant-id opts]
  "Eagerly create whatever backing state a tenant needs: the per-tenant
   XTDB node, the Datomic database and connection, the mock store's
   state entry, etc. Schema transacts should run here so the first
   resource call is pure I/O against an already-warm backend.

   `opts` may contain:
   - :if-exists — one of :error (default), :ignore, :replace.
     :error throws ex-info {:fhir/status 409 :fhir/code \"conflict\"}
     when the tenant already has any state. :ignore is a no-op if
     the tenant already exists. :replace is equivalent to calling
     delete-tenant immediately followed by create-tenant.

   Returns nil. Safe to call concurrently for the same tenant; the
   first caller wins and losers see an atomic no-op.")

(delete-tenant
  [this tenant-id]
  [this tenant-id opts]
  "Remove all per-tenant state. After this call, reads and searches
   against the tenant behave as if the tenant was never created.
   Implementations must release any OS resources held open for the
   tenant (Datomic connection, XTDB node, file handles, JDBC pool).

   `opts` may contain:
   - :if-absent — one of :error (default), :ignore. :ignore is a
     no-op when the tenant has no state.
   - :close-storage? — boolean, default false. When true and the
     backend has persistent storage (datomic :dev/:peer, XTDB
     `xtdb2-disk`), also drop the underlying database/file storage,
     not just the in-process handle. In-memory backends ignore this.

   Returns nil.")

(warmup-tenant
  [this tenant-id]
  [this tenant-id opts]
  "Prime caches, classloaders, JIT, and any lazy per-tenant init
   without actually mutating data. Intended to be idempotent and safe
   to call on a tenant that already has data — unlike create-tenant,
   which treats an existing tenant as a conflict by default.

   Implementations should issue a representative no-op query against
   the tenant that exercises the same code path a real request would
   take (e.g. a 0-result search against a known-small resource type).
   This forces the read-side classloader to load the decoder, the
   query engine to plan/cache the query shape, and the backend to
   resolve any lazy per-tenant resources.

   `opts` may contain:
   - :resource-types — a collection of resource type keywords to
     exercise. Default is `#{:Patient}` which is enough to prime the
     common hot paths.

   Returns nil. Never throws on a missing tenant; instead, creates
   the tenant as a side effect and then runs the warmup. This mirrors
   the current `get-or-create-node` / `ensure-tenant-conn!` laziness
   but makes it explicit and callable at application boot.")
```

### Call-site updates

Once the protocol change lands, update the following call sites to
use the explicit methods:

1. **`dromon/bb/src/server/inferno_runner.clj:124 warmup-store!`** —
   replace the `curl ... GET /Patient?_count=1` hack with a direct
   call to `(db/warmup-tenant store "default")` via a small
   integrant-injected function, or an HTTP admin endpoint. The current
   warmup is out-of-protocol and duplicates logic across backends.

2. **`test-server/seeder` (dromon + datomic-test-server)** — add an
   explicit `(db/create-tenant store "default")` at the top of the
   `:test-server/seeder` init-key before the
   `doseq ... create-resource` loop that seeds SearchParameters. This
   makes the tenant provisioning visible at the config level instead
   of buried inside the first store call.

3. **Integration test suites** — replace any ad-hoc "reset the store"
   helper with `(db/delete-tenant store tid {:if-absent :ignore})`
   followed by `(db/create-tenant store tid)`. This removes
   backend-specific reset code from shared test helpers.

### Not in scope

- A **list-tenants** method. We have no caller that needs it, and it
  surfaces questions about whether transient/warmup-only tenants
  should be listable.
- **Tenant metadata / attributes.** If we ever need to attach a
  display name, a creation timestamp, or a plan tier to a tenant,
  that's a separate task.
- **Schema migration on existing tenants.** `create-tenant` is
  responsible for its own first-time schema transact. Schema
  migration for long-lived tenants (e.g. the schema-installed?
  fast-path in `fhir-store-datomic.core/ensure-tenant-conn!`) should
  continue to live inside the implementation.

## Acceptance criteria

- `fhir-store-protocol/src/fhir_store/protocol.clj` has the three new
  methods with the docstrings above.
- **All three backends implement them** via the follow-up tasks:
  `fhir-store-xtdb2-tenant-lifecycle.md`,
  `fhir-store-datomic/docs/tasks/35-tenant-lifecycle.md`,
  and `fhir-store-mock-tenant-lifecycle.md`.
- `bb inferno-test` still passes 505/505 (dromon xtdb2) and 500/505
  pass + 5 skip (datomic).
- `warmup-tenant` measurably shifts cold-start cost out of the first
  real request: the `store/node.start` span (xtdb2) and the
  first-hit `ensure-tenant-conn!` latency (datomic) should show up
  under a dedicated `store/warmup-tenant` span instead of under
  `http/request`.

## Depends on

Nothing structural. Ordering for the rollout:

1. This protocol task (signatures + docstrings only — no impls).
2. `fhir-store-xtdb2-tenant-lifecycle.md` (reference implementation;
   the existing `get-or-create-node` already has the shape we want).
3. `fhir-store-datomic/docs/tasks/35-tenant-lifecycle.md` (mirrors
   xtdb2; reuses `ensure-tenant-conn!`).
4. `fhir-store-mock-tenant-lifecycle.md` (trivial; mostly atom swaps).
5. Call-site cleanup: runners, seeders, test helpers. This last step
   is per-call-site and each conversion is small enough to not warrant
   its own task file.
