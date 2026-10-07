# Prototype: the write lifecycle as a store wrapper

Branch `claude/lifecycle-store-wrapper`. A prototype to answer one question:
could `fhir-store.lifecycle` be "middleware and contracts" instead of a call
sequence every store reimplements? Ring middleware was ruled out first (it only
sees HTTP requests, sees a request rather than the write, and has no
transaction to join), so this tries the next layer down: an `IFHIRStore`
wrapper, like `CompartmentFilteringStore`, with the contract carried as an
opts key.

## What was built

- `fhir-store-protocol/src/fhir_store/lifecycle_store.clj`: the wrapper.
  It mints ids, runs `prepare`, pre-checks preconditions, rewrites Bundle POST
  entries, and fires `after-commit`. The store's whole remaining obligation is
  one opts key, `:lifecycle/tx-ops`: a function the store calls inside its
  transaction with `{:resource-type :id :db :entity}`, appending what it
  returns in its own dialect. Stores declare support with `ITxOpsStore`.
- The mock and HTTP stores migrated to that contract. Their constructors wrap
  themselves when given `:resource/lifecycle`, so callers are unchanged.
- xtdb2 (dromon) and Datomic (master-at-arms2) were NOT migrated, only read.

## Results

| check | result |
|---|---|
| mock: existing 22 tests, unchanged, through the wrapper | pass, including composed lifecycles and "refused before prepare" |
| mock: 8 new wrapper tests | pass: store-supplied `:db`, capability mirroring, refusal of a store that would drop ops, POSTs answered 201, preconditions before `prepare`, `urn:uuid` resolution |
| http: existing 17 unit tests, unchanged | pass, including the lifecycle test, where ops are applied as Bundle entries |
| fhir-store-protocol: 38 tests | pass |

Line counts:

| | change |
|---|---|
| mock store | 101 deleted, 68 added (net -33) |
| http store | 58 deleted, 43 added (net -15) |
| wrapper | +400, roughly half docstring |
| xtdb2, estimated | about 35 lines of helpers drop (`lifecycle-ctx`, `lifecycle-write`, `fire-after-commit!`); the call sites get simpler |
| datomic, estimated | about 25 lines drop (`lifecycle-write`, its after-commit helper) |

## What the wrapper cannot take from the store

These stay store work in either design:

1. **Computing ops against the transaction's db.** Flotilla's Composition
   narrative builds its ops from the exact `db` value the Datomic transaction is
   built from, and names the resource by its `:entity` (eid or tempid). Only the
   store has those, hence the opts fn rather than precomputed ops. That fn is
   the old `tx-ops` call under a new name.
2. **Validating and attributing ops.** xtdb2 refuses lifecycle ops carrying an
   unpaired surrogate (`lifecycle-tx-ops`), and tells a failure in a lifecycle
   op from a conflict on the resource itself (`lifecycle-op-failure?`). Both
   need the store's own op indexes.
3. **Installing schema.** Datomic installs `schema-tx` per tenant at tenant
   creation. A wrapper would need a further contract to hand the schema to the
   store.
4. **Non-protocol write paths.** Datomic's `transact-bundle-async` (pipelined
   loads) runs `prepare` and `tx-ops` itself. Anything a caller invokes on the
   base store outside `IFHIRStore` bypasses a wrapper entirely. Today it is
   covered, because the lifecycle lives in the store.

## What the wrapper costs

1. **An extra read per guarded write.** The lifecycle contract promises
   `prepare` only writes whose `:if-match` passed and whose create id is free.
   The existing mock test pins that. The wrapper can only keep the promise by
   reading the current version first; xtdb2 already does that read itself, so
   it would happen twice. A write that loses a race in between is still refused
   by the store, after `prepare` ran.
2. **Re-implementing Bundle semantics.** To give `prepare` a final id, the
   wrapper rewrites POST entries into PUTs under minted ids. That forces it to:
   - answer those entries `201 Created` again;
   - resolve `urn:uuid:` references itself, because Datomic resolves them only
     for POST entries. Without this, references inside a transaction Bundle
     would be left as `urn:uuid:` strings, silently;
   - refuse conditional creates (`ifNoneExist`), which cannot be rewritten.

   Each of these duplicates logic the stores already have.
3. **One wrapper type per capability set.** The server picks 400 or an answer
   with `satisfies? ITemporalReadStore`, so the wrapper must claim exactly what
   its base does. There are three record types (plain, temporal, bitemporal),
   built with `extend`.
4. **Copying map keys.** Hosts and the server read keys off whatever store they
   are handed (the mock's `:state` and `:operations`, flotilla's
   `:resource/lifecycle`), so the wrapper copies the base's keys onto itself.
   That works, but it is coupling nothing declares.
5. **Response ordering.** Batch-response entries are paired with requests by
   position. The mock answers transactions in processing order rather than
   request order, so 201 fix-ups match by id instead.

## Assessment

The wrapper works and keeps the contract, but it does not remove the stores'
involvement. Each store still computes ops in its transaction, validates and
attributes them, installs schema, and covers its own non-protocol paths. In
exchange it adds an extra read, a second implementation of POST, `urn:uuid` and
response-status semantics, and capability bookkeeping. For two stores the net
code change is negative only once the wrapper itself is ignored.

The duplication that does exist is narrower: every store defines a near-identical
`lifecycle-write` (mock, xtdb2, datomic, http) and an after-commit helper. A
shared helper in `fhir-store.lifecycle` that takes the store-supplied write
context (`:db`, `:entity`, final id) and returns the prepared write with its
ops would remove that, with no semantic change, no extra read and no Bundle
rewriting. That is the recommended follow-up. This branch is a record of why
the wrapper is not.
