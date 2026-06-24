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

## Solution (shipped)

Serialize the conditional-create path on a per-`(tenant, resource-type,
normalized-search-params)` in-memory named lock. Only the
conditional-create path pays the cost; plain POSTs are unaffected. This
is strictly better than the racy read-then-write and is correct for the
current single-node deployment. (If the lock ever becomes a bottleneck,
a post-create verify-and-rollback with a stable lowest-`_id` tiebreaker
is the fallback; a store-level uniqueness constraint was rejected as
impractical because search criteria are arbitrary.)

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
