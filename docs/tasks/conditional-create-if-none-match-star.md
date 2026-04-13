# Conditional create via `PUT /{type}/{id}` with `If-None-Match: *`

## Problem

FHIR R4 §2.38.1 says `PUT /{type}/{id}` with header
`If-None-Match: *` must create the resource only if no resource with
that logical id already exists, and must return `412 Precondition
Failed` if it does exist. This is the write counterpart to the
`*`-wildcard If-None-Match used for conditional GETs.

Today, `fhir-server/src/server/handlers.clj` parses `If-None-Match`
only on the read path (`not-modified?`, around line 74). The
`update-resource` handler (around line 126) ignores it entirely,
which means a PUT with `If-None-Match: *` silently performs an
upsert instead of rejecting an existing resource with 412.

## Approach

Thread `:if-none-match :wildcard` through the same opts map we
already use for `:if-match` (added in the If-Match task). Each
store enforces it atomically:

- **mock**: inside the existing `swap!` fn, if `:if-none-match` is
  `:wildcard` and the existing record is non-nil and not deleted,
  throw `{:fhir/status 412 :fhir/code "conflict"}`. Otherwise fall
  through to the existing create path.
- **xtdb2**: prepend an `ASSERT NOT EXISTS (SELECT 1 FROM {rt} WHERE
  _id = ?)` op to the insert tx. Translate the assert-failure catch
  to a 412 ex-info when the caller supplied `:if-none-match
  :wildcard` (today it's 409).
- **datomic**: use `[:db.fn/cas eid :fhir/version-id nil "1"]` where
  `eid` is a new tempid keyed to `[:fhir/ident [tenant rt id]]`. The
  `:fhir/ident` unique-identity tuple already prevents duplicates at
  the schema level; the cas adds the version precondition so
  racing creates also fail atomically.

Handler change: in `update-resource` (PUT with id), parse
`If-None-Match`; if the value is `*`, pass `:if-none-match
:wildcard` in the opts map. The rest of the handler doesn't need a
pre-read — let the store throw 412.

## Testing

- Unit tests in each store: create Patient/123, then attempt create
  with `:if-none-match :wildcard` on the same id → 412.
- Integration test in `test-server/test/test_server/fhir_test.clj`:
  PUT /Patient/abc with `If-None-Match: *` first time → 201, second
  time → 412.
- Inferno must still pass 505/505 — Inferno does not exercise this
  header today, so the task is purely additive.

## References

- FHIR R4 §2.38.1: https://hl7.org/fhir/R4/http.html#cond-update
- `fhir-server/src/server/handlers.clj` — `update-resource` handler
- Prior work: the If-Match CAS cascade across mock/xtdb2/datomic.
