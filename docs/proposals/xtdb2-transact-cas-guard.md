# Eliminate `current-versions` lookups in xtdb2 transact-transaction via in-tx CAS guards

## Status

Proposed. Not started. Larger and riskier than the parallel-fetch
proposal in [`xtdb2-transact-parallel-current-versions.md`](xtdb2-transact-parallel-current-versions.md);
worth pursuing only if that one fails to land us comfortably ahead of
datomic.

## Context

After the recent transact-transaction refactor, the timing breakdown
on the 85-entry inferno bundle is:

```
store/transact-transaction           260 ms
  build-tx                             1 ms
  current-versions                   144 ms   ← entire pre-read phase
  sql-encode                           9 ms
  execute-tx                          86 ms
  build-response                       0 ms
```

`current-versions` is a *pre-read* phase: before the tx commits, we
ask XTDB for the existing `fhir_version` of every PUT id so we can
compute `next-version`. This is fundamentally a round-trip to the
same node we are about to write to. The only reason we need it is to
generate a monotonic `versionId`.

The single-resource `update-resource` path does this differently. It
already uses an `ASSERT EXISTS / ASSERT NOT EXISTS` op embedded in the
same `xt/execute-tx` call, so the read and write happen as one atomic
transactor round-trip:

```clojure
;; from update-resource at fhir-store-xtdb2/.../core.clj :674-682
assert-op (if expected-vid
            [:sql (format "ASSERT EXISTS (SELECT 1 FROM %s WHERE _id = ? AND fhir_version = ?)" rt-name)
             [id expected-vid]]
            [:sql (format "ASSERT NOT EXISTS (SELECT 1 FROM %s WHERE _id = ?)" rt-name)
             [id]])
```

A failing ASSERT aborts the tx, so we get optimistic concurrency
control without an explicit pre-read. `transact-transaction` does
not use this pattern today — it does the pre-read instead, presumably
because it has to compute `next-version` before encoding the SQL row,
and the version becomes part of the tx-op payload.

This proposal asks: can we eliminate the pre-read phase entirely,
moving the version arbitration into the transactor where it belongs?

## Goal

Remove the `current-versions` phase from `transact-transaction`,
shifting the version-bump logic into the tx itself. Target: drop
the per-bundle latency from ~260 ms to ~100 ms (everything except
`current-versions` and the slop between phases).

## Approach options

Three possible designs, in increasing order of how much they change
xtdb2 semantics.

### Option A: Optimistic version assumption + ASSERT retry

For each PUT entry, **assume the row does not exist** and emit a
`POST`-style `[:sql INSERT ...]` with `fhir_version = "1"`. Wrap the
whole tx in a try/catch. On ASSERT-failure or constraint-violation,
fall back to a slower path: do the pre-read, recompute versions, and
retry the tx. For the common case (greenfield seed bundles, the
inferno test data path) the fast path commits in one round-trip.

Pseudo-code:

```clojure
(defn- attempt-fast [...]
  (let [tx-ops (build-tx-ops-assuming-version-1 entry-metas)]
    (xt/execute-tx node
                   (cons [:sql "ASSERT NOT EXISTS (SELECT 1 FROM ... WHERE _id IN (...))" ...]
                         tx-ops))))

(defn- attempt-slow [...]
  ;; existing pre-read + sql-encode + execute-tx path
  )

(try (attempt-fast ...)
     (catch Exception _ (attempt-slow ...)))
```

**Pros:** Zero extra round-trips on the happy path. Existing slow
path stays as a correctness fallback.

**Cons:**
- The ASSERT NOT EXISTS guard has to cover *all* PUT ids in one
  expression. xtdb2 supports `IN`-list subqueries, but the failure
  mode (which id collided?) is opaque from a single boolean assert.
  We would have to fall back to the slow path on any failure and let
  it figure out the conflicts.
- The fallback retry is wasted work whenever PUTs are routinely
  hitting existing rows (e.g., real-world FHIR transactions that
  update existing patients). The benchmark workload is greenfield,
  but production workloads might not be.
- Two execute-tx round-trips on the slow path. We need a metric to
  detect whether the fast path is winning in practice.

### Option B: Per-row tx-functions

xtdb2 supports user-defined tx-functions that run *inside* the
transactor and have read access to the database state. The version
bump can move there:

```sql
-- Pseudocode; xt SQL syntax for tx-fn invocation differs.
INVOKE put-resource('Patient', 'p1', $resource_json)
```

The tx-fn implementation reads the current `fhir_version` for the
row, computes `next-version`, and writes the new row in the same
transaction. From the client side, we send only one tx-op per entry
and zero pre-reads.

**Pros:** Cleanest separation. Transactor does the read + the
arbitration in a single pass over its in-memory state. No retries,
no fallbacks.

**Cons:**
- xtdb2 v2 tx-functions are still maturing; we would need to verify
  the API is stable enough for production use.
- The tx-fn body has to encode FHIR-shape-aware logic (extract
  `_id`, compute version, build the row). That logic currently lives
  in `extract-and-build-sql` on the client side and uses the
  per-resource malli storage encoders, which are in-process JVM
  state. Either the tx-fn must reach the same encoder code (deploy
  Clojure into the transactor namespace), or the encoding stays
  client-side and only the version bump moves into the tx-fn.
- Only the version bump moving into the tx-fn still helps: tx-fn
  receives the encoded row + the resource type, looks up the current
  version, rewrites the `fhir_version` column on the row, writes it.
  But that requires the tx-fn to mutate a row payload, which may not
  be a supported tx-fn shape in xtdb2 today.

### Option C: Schema-level identity column with auto-bump

Replace the explicit `fhir_version` column with a server-managed
auto-incrementing column. Every INSERT/UPDATE on the row implicitly
bumps the version on the storage side. The client sends the row
without a version, and reads back the assigned version after.

**Pros:** Removes the version bump from application code entirely.

**Cons:**
- Out of scope for "make transact-transaction fast." This is a
  schema change that touches every read and write path on every
  resource type, including history queries that already key on
  `fhir_version`. Multi-week effort with broad blast radius.
- Bigger change than the perf gain warrants.

## Recommendation

Pursue **Option A** first, behind a feature flag, with metrics. It is
the smallest change that could plausibly land us at ~100 ms on the
benchmark workload. The fast path is a single tx round-trip; the
slow-path fallback preserves correctness.

Defer **Option B** until xtdb2 tx-function API stability is verified.
File a separate spike to evaluate.

Reject **Option C** — too big, too broad, not justified by the gain.

## Open questions for Option A

1. **What does xtdb2 throw on ASSERT failure?** Need to identify the
   exception class and `ex-data` shape so the catch is precise. A
   blanket `(catch Exception _)` would also swallow real errors.
2. **Can ASSERT NOT EXISTS take an `IN` list?** If not, we'd need one
   ASSERT op per PUT id, which defeats the point — that's N round-trips
   inside the transactor instead of N round-trips before. Need to
   confirm via the xtdb2 docs or a quick REPL probe.
3. **What happens to a partially failed bundle's tempids?** xtdb2's
   transactor either commits the whole tx or none of it, so there
   should be no partial state to clean up. Confirm with a test that
   forces a fast-path failure mid-bundle.
4. **How do we measure fast-path hit rate in production?** Add a
   `t/event!` on each branch (`::transact-transaction.fast-path-hit`,
   `::transact-transaction.slow-path-fallback`) so OTel events tell
   us whether the optimization is paying off in real workloads.

## Verification plan

1. **Micro-bench** — same 85-entry inferno bundle. Target: total
   `store/transact-transaction` < 120 ms.
2. **Mixed bundle** — construct a bench bundle that is 50% inserts
   (fast-path) and 50% updates of existing rows (slow-path), measure
   both. The slow path should be no worse than the current 260 ms;
   the fast path should be ~100 ms.
3. **Inferno full suite under DROMON_OTEL=1** — confirm 505/505
   still passes and inspect Jaeger for the new span shape. The
   `:store/transact-transaction.fast-path-hit` event should fire on
   the seed bundle insertion.
4. **`test-transact-transaction` unit test** — primary regression
   guard for response shape and version monotonicity. Extend to
   cover the slow-path retry by injecting a pre-existing row with
   the same id as one of the bundle PUTs, then asserting the
   retried tx commits with `versionId = "2"` for that entry.
5. **Concurrent stress** — run the existing
   `concurrent-updates-version-conflict`-style harness against a
   bundle that includes the same id from N parallel callers. Exactly
   one should win, others should observe a 409 from the *outer*
   handler (not a swallowed ASSERT exception).

## Risks

- **Medium.** Touches the transactional semantics of the bundle
  write path. The fast/slow split adds a code path that only fires
  in the catch branch, which is exactly the kind of thing that rots
  silently if the catch branch never gets exercised in CI. The
  mixed-bundle bench above is the cheapest way to keep the slow
  path warm.
- The catch-and-retry pattern is easy to get wrong: catching too
  broadly hides real bugs; catching too narrowly misses the ASSERT
  failure shape and crashes the request.

## Depends on

- Confirmation of the ASSERT-failure exception shape in xtdb2 v2.
  The `update-resource` method already catches it (see the
  `(catch Exception e ...)` block at lines 681-691) but does not
  introspect — it just rewraps as a 409/412. We need to know the
  underlying exception class to write a precise catch in
  `transact-transaction`.
- The bench harness from
  [`xtdb2-transact-parallel-current-versions.md`](xtdb2-transact-parallel-current-versions.md)
  — same micro-bench reused for verification.

## Out of scope

- Conditional create / conditional update via search criteria. The
  fix here is purely about version bump latency, not about the
  search-then-write race the conditional task tracks.
- Schema migration to remove `fhir_version` (Option C above).
