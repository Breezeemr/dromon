# Conditional create via `PUT /{type}/{id}` with `If-None-Match: *`

## Problem

FHIR R4 and R4B define `If-None-Match` only for conditional read
(http.html §3.1.0.1.7, a `304 Not Modified`); their `update` takes only
`If-Match`. FHIR R5 adds it to conditional update (http.html §3.2.0.4.3):
`PUT /{type}/{id}` with `If-None-Match: W/"<vid>"` updates only if that
version does not already exist, and servers MAY support only the
`If-None-Match: *` form, meaning no existing version. R5 names no status
for a failed condition; RFC 9110 §13.1.2 answers a false `If-None-Match`
on any method but GET and HEAD with `412 Precondition Failed`.

A transaction Bundle's PUT entry already honours `request.ifNoneMatch`
this way in fhir-store-xtdb2 and fhir-store-mock (`*` fails 412 when the
resource exists, a version ETag fails 412 when that version exists,
current or historical). The single-resource PUT below does not.

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

- FHIR R5 conditional update: https://hl7.org/fhir/R5/http.html#cond-update
- FHIR R4B conditional read (the only R4B use of `If-None-Match`): https://hl7.org/fhir/R4B/http.html#cread
- RFC 9110 §13.1.2 If-None-Match: https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.2
- `fhir-server/src/server/handlers.clj` — `update-resource` handler
- Prior work: the If-Match CAS cascade across mock/xtdb2/datomic.
