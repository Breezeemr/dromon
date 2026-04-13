# Conditional update/delete/patch by search criteria are TOCTOU-racy

## Problem

FHIR R4 §3.1.0.10 (conditional update), §3.1.0.7.1 (conditional
delete), and §3.1.0.10.3 (conditional patch) all support a
search-criteria form at the type-level endpoint:

- `PUT /{type}?<search>` — update the one matching resource, or
  create if zero matches.
- `DELETE /{type}?<search>` — delete the one matching resource.
- `PATCH /{type}?<search>` — apply the patch to the one matching
  resource.

All three are implemented in
`fhir-server/src/server/handlers.clj`:

- `conditional-update` (line 509)
- `conditional-delete` (line 551)
- `conditional-patch` (line 576)

Each follows the same pattern:

```clojure
1. db/search with ?_count=2 using the URL params
2. branch on (count results): 0 | 1 | >1
3. if 0 → create (conditional-update only) or 204/404
4. if 1 → update/delete/patch the matched id
5. if >1 → 412
```

The search and the subsequent write are not atomic. Three distinct
races exist:

1. **Phantom create** (conditional-update only): two concurrent no-
   match conditional updates both see zero rows, both create,
   producing duplicates. This is the same class as
   `conditional-create-if-none-exist-race.md` but via a different
   HTTP verb.
2. **Delete-underneath-update**: a concurrent `DELETE /{type}/{id}`
   removes the row that conditional-update/patch/delete just
   matched, so the follow-up write touches a missing resource or
   races the delete. Today the fallback throws an opaque store
   error.
3. **Update-under-patch**: a concurrent `PUT /{type}/{id}` bumps
   the version between search and `update-resource`. The patched
   body is now based on a stale snapshot, and the resulting version
   silently overwrites the concurrent update. If the caller also
   supplied `If-Match`, the store-level CAS added in the If-Match
   task catches this. Without `If-Match`, the patch wins blind.

## Approach

Three knobs:

### Fix 1 — Propagate `If-Match` implicitly across search → write

When the search returns exactly one match, capture its
`:meta :versionId` and pass `{:if-match <vid>}` into the store's
update/delete/patch call. If the store rejects with 412, either
retry the whole conditional (search + write) a small number of
times or return 412 to the client. This makes the "one match"
branch race-correct at no new infrastructure cost — the CAS
already exists from the If-Match task.

### Fix 2 — Serialize phantom-create path

Reuse the per-tenant-per-type lock from
`conditional-create-if-none-exist-race.md`. Only the zero-match →
create branch of `conditional-update` needs the lock. The one-match
branches don't need it (Fix 1 handles them).

### Fix 3 — Translate store-level missing-row into clean 404/412

When a store write throws because the row vanished mid-conditional
(delete-underneath), return a typed 412 with diagnostic text, not
a raw stack.

## Testing

- For each verb: 10 parallel conditional requests against the same
  search criteria. Assert no duplicates, no unexpected 500s, and
  a stable winning resource.
- Unit test verifying `If-Match` is auto-supplied by the
  conditional-update/delete/patch handlers when the search returns
  exactly one row.
- Inferno 505/505 preserved.

## References

- FHIR R4 §3.1.0.10, §3.1.0.7.1, §3.1.0.10.3
- `fhir-server/src/server/handlers.clj:509` — conditional-update
- `fhir-server/src/server/handlers.clj:551` — conditional-delete
- `fhir-server/src/server/handlers.clj:576` — conditional-patch
- Prior work: the If-Match CAS cascade across mock/xtdb2/datomic
  (this task reuses the store-level CAS from that work).
- `conditional-create-if-none-exist-race.md` — the phantom-create
  fix for conditional-update reuses its lock.
