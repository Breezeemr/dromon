# Conditional create via `POST` with `If-None-Exist` is TOCTOU-racy

## Problem

FHIR R4 §3.1.0.8.1 defines conditional create: `POST /{type}` with
header `If-None-Exist: <search params>` must ensure only one
resource matching those search params exists. If zero match, create;
if one matches, return it as-is (no new row); if multiple match,
return `412 Precondition Failed`.

Today's implementation in `fhir-server/src/server/handlers.clj:298`
(`create-resource` fn) does:

```clojure
1. db/search with the If-None-Exist params
2. count results
3. if 0 → db/create-resource
4. if 1 → return existing
5. if >1 → 412
```

Steps 1 and 3 are not atomic. Two concurrent POSTs with the same
`If-None-Exist` can both see zero matches and both create,
producing exactly the duplication the header was meant to prevent.

## Approach

None of the options is free — pick based on invariant cost:

### Option A — Post-create verify-and-rollback

After the create returns, re-run the same search; if count > 1,
delete the row we just inserted and return 412. Simpler than
serializing writes, but still has a window where both creators
complete, both observe duplicates in their verify step, and both
roll back, leaving zero rows. Mitigate with a stable tiebreaker
(e.g. keep the row with the lowest `_id` when rolling back).

### Option B — Per-tenant per-type conditional-create lock

Serialize conditional creates for the same `(tenant, resource-type,
search-param-set)` tuple with an in-memory named lock. Only the
conditional-create path pays the cost; normal POSTs are unaffected.
Simpler to reason about, but breaks down in a multi-node deployment
(we're single-node today, so fine).

### Option C — Store-level uniqueness constraint

Add a computed column or unique index per resource type keyed by
the search-criteria hash. Cheap at query time, impossible to
generalize: search criteria can be arbitrary and the index shape
differs per request. Not practical.

### Option D — Punt

FHIR allows implementers to document that conditional create is
best-effort. Inferno doesn't assert the race semantic today.
Document the current behavior in the CapabilityStatement and move
on.

## Recommendation

Start with **B** (per-tenant lock). It's ~10 lines in the handler,
strictly better than today, and doesn't paint us into a corner for
multi-node. Revisit with **A** if the lock becomes a bottleneck.

## Testing

- Spin up 10 parallel `POST`s against the mock store with identical
  `If-None-Exist: identifier=abc`. Assert exactly one 201 and nine
  200s (or nine refusals). Today this test would surface duplicates.
- Integration test using the xtdb2 or mock store.
- Inferno 505/505 preserved.

## References

- FHIR R4 §3.1.0.8.1: https://hl7.org/fhir/R4/http.html#ccreate
- `fhir-server/src/server/handlers.clj:298` — `create-resource` fn
- `fhir-server/src/server/middleware.clj:124` — CORS whitelist
  already permits the header.
