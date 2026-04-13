# Fix `test-transact-transaction` response-order assertions

## Problem

`fhir-store-xtdb2/test/fhir_store_xtdb2/core_test.clj` has seven
assertion failures in `test-transact-transaction` (the test was
renamed from `test-transact-bundle` as part of the protocol split
into `transact-transaction` / `transact-bundle`). The failures
predate the rename and were carried forward unchanged.

The test asserts that the transaction-response `entry` vector is in
input order, but the xtdb2 store reorders entries per
FHIR §3.1.0.11.2 (`DELETE -> POST -> PUT/PATCH -> GET/HEAD`) before
applying them, and returns the response in processed order. The test
therefore fails on any fixture that mixes method types. Verified by
running the suite against both `main` and a pristine checkout prior
to the protocol split — same seven failures, same assertions.

## Approach

Two options:

1. **Fix the assertions** to match the processed order the store
   returns. This matches what the code and the spec say. Straight
   rewrite of the affected `is` forms.
2. **Track input order** in the store impl and emit
   `transaction-response.entry` in input order while still applying
   the ops in processed order. FHIR §3.1.0.11.2 does not mandate
   response order, but the spec example (HL7 FHIR R4
   `transaction-response`) keeps input order. Many clients key
   responses by `fullUrl` rather than index, so this matters less in
   practice.

Option 1 is the cheaper fix and keeps parity with what the store
actually does today. Option 2 is more user-friendly but requires a
stable mapping from processed-order results back to input-order
slots, which the mock store would also need to grow. Pick 1 unless
there's a downstream consumer that relies on input-order responses.

## Testing

- `cd fhir-store-xtdb2 && clj -M:test` — all assertions must pass.
- `cd fhir-store-mock && clj -M:test` — baseline still passes; only
  adjust if option 2 is picked and the mock needs to match.
- `bb inferno-test` must still be 505/505 — inferno does not rely on
  response ordering, so a Option 1 fix should be a no-op for it.

## References

- `fhir-store-xtdb2/src/fhir_store_xtdb2/core.clj` — `transact-transaction`
  impl; entry reordering happens in `entry-metas`.
- `fhir-store-xtdb2/test/fhir_store_xtdb2/core_test.clj` —
  `test-transact-transaction` (formerly `test-transact-bundle`).
- FHIR R4 §3.1.0.11.2 — transaction processing rules.
