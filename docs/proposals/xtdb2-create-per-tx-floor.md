# xtdb2 single-resource writes have a per-tx fixed-cost floor

## Status

Investigation report. The two action items it produced are landed (ASSERT
guard on `create-resource`, batched seeder); the long-term recommendation
("batch when you can; don't look for a pre-read to remove") is durable
guidance for future work on this backend.

## Context

When the perf comparison between `fhir-store-xtdb2` and
`fhir-store-datomic` showed xtdb2 trailing on `store/create` (mean 17.4 ms
vs datomic 6.8 ms), the natural assumption was a hidden pre-read in the
write path, mirroring the bulk read-back pattern that `transact-transaction`
turned out to have. This proposal documents the actual investigation,
which found something different, and codifies the resulting guidance so
the next person who reaches the same conclusion has a record.

## What the code actually does

`fhir-store-xtdb2.core/create-resource` was, before this investigation:

```clojure
(create-resource [this tenant-id resource-type id resource]
  (let [node (get-or-create-node this tenant-id)
        version "1"
        [sql args] (extract-and-build-sql resource-type id resource ...)]
    (xt/execute-tx node [[:sql sql args]])
    (-> resource (assoc :id id) (assoc-in [:meta :versionId] version))))
```

One `xt/execute-tx` call. One `[:sql INSERT ...]` op inside it. **No pre-read.**
The encoder runs entirely in-process; the only IPC the method does is
the single `execute-tx` to the XTDB node.

## Where the time actually goes

Bench: 50 sequential `create-resource` calls against a fresh in-memory
node, after 5 warmup creates, schemas resolved (uscore8 Patient).
Sub-spans `:store/create.sql-encode` and `:store/create.execute-tx` were
added inside the body so the cost can be attributed.

```
store/create                 16.48 ms   total
  sql-encode                   0.07 ms   ← negligible
  execute-tx                  15.86 ms   ← entire cost
```

`extract-and-build-sql` (malli encoder run + SQL string formatting) is
0.07 ms. The full 16 ms is `xt/execute-tx` itself. There is nothing in
the application layer to optimize.

Cross-reference the `transact-transaction` bench from the same session:
**85 inserts in one `xt/execute-tx` cost ~80 ms**, i.e. ~0.94 ms per
insert. So a single-call `execute-tx` is roughly:

```
xt/execute-tx ≈ 15 ms fixed-per-tx + 1 ms × N ops
```

The 16 ms median for `create-resource` is overwhelmingly the fixed
component. The marginal cost of an additional INSERT op inside a tx is
~1 ms.

## Implications

1. **There is no pre-read to remove from `create-resource`.** Anyone
   approaching this method looking for a "drop the SELECT, use ASSERT
   instead" optimization will not find one — there is no SELECT.

2. **Adding `ASSERT NOT EXISTS` to `create-resource` is a correctness
   improvement, not a perf improvement.** Without it, two concurrent
   POSTs with the same generated id (or any direct duplicate) silently
   produced overlapping bitemporal versions in xtdb2's store, or
   surfaced a low-level transactor error far from the request. The
   ASSERT runs in the same transactor round-trip as the INSERT, so
   it costs nothing measurable, and gives us a clean
   `{:fhir/status 409 :fhir/code "conflict"}` response to mirror the
   one `update-resource` already throws. This was landed as part of
   this investigation.

3. **The actual lever for "make creates faster" is batching.** The
   `transact-transaction` path amortizes the ~15 ms fixed-per-tx cost
   across N ops, dropping the per-row cost to ~1 ms. Any background
   workload that today calls `create-resource` in a loop should be
   rewritten to issue one `transact-transaction` instead.

4. **The 15 ms floor is xtdb2's commit-pipeline overhead, not
   addressable from application code.** It is the price of reaching
   the transactor and back. Short of changing storage layers, application
   code cannot push it lower.

## Action items

### Landed in this investigation

- **`create-resource` now uses `ASSERT NOT EXISTS`** in the same
  `xt/execute-tx` call. Failures rewrap as
  `{:fhir/status 409 :fhir/code "conflict" :resource-type ... :id ...}`
  ex-info, matching the existing 409 shape from `update-resource`.
  Sub-spans `:store/create.sql-encode` and `:store/create.execute-tx`
  added so future regressions show attribution. `fhir-store-xtdb2`
  unit tests (15/66) and `fhir-server` handlers tests (30/158) are
  green with the change.

- **Both test-server seeders batched.**
  `dromon/test-server/src/test_server/core.clj` and
  `dromon-datomic/src/com/breezeehr/dromon_datomic/core.clj` previously
  did `(doseq [p sp/search-parameters] (db/create-resource ...))` —
  one tx per SearchParameter, ~109 SearchParameters in the uscore8
  package, ~1.7 s of seeder cold-start cost on xtdb2. Both now build
  one `transact-transaction` Bundle of PUT entries and issue a single
  `db/transact-transaction` call. Expected savings: ~1.6 s off boot
  for xtdb2, similar magnitude for datomic. The boot path runs
  before Jetty accepts traffic, so this is paid back as faster server
  ready-time, not faster per-request latency.

### Future work (not pursued here)

- **Document the batching pattern in CLAUDE.md.** The "use
  `transact-transaction` for any N>1 write workload" guidance is
  general and applies to anything that lands future bulk-import code,
  data migrations, fixture loaders, etc.

- **Connect this investigation to the existing two
  `transact-transaction` proposals.** Neither
  [`xtdb2-transact-parallel-current-versions.md`](xtdb2-transact-parallel-current-versions.md)
  nor [`xtdb2-transact-cas-guard.md`](xtdb2-transact-cas-guard.md)
  changes the per-tx fixed floor — they target the
  pre-read phase inside `transact-transaction`. They are
  complementary to this one, not redundant.

- **Investigate the xtdb2 transactor side of the 15 ms floor.** Out of
  scope for application code, but worth tracking upstream: is the
  fixed cost network/IPC, log append, planner, or schema compilation?
  An xtdb2 v2 release that drops this floor would benefit every write
  path in the codebase without touching dromon at all. File an issue
  upstream if we can profile a representative trace.

## Verification

The `:store/create.{sql-encode,execute-tx}` sub-spans are now visible
in Jaeger under `DROMON_OTEL=1`. Any future regression where someone
adds a hidden read to `create-resource` will show up immediately as a
non-zero `sql-encode` span or a new sibling span eating into the budget.

The seeder batching can be verified by inspecting boot logs — the
"Seeding N SearchParameters in one transaction..." line appears in
both test-servers, and a single `:store/transact-transaction` span
covers the whole seed in Jaeger instead of N sibling `:store/create`
spans.

## Caveats

- The 15 ms floor is measured against an in-memory xtdb2 node. Persistent
  storage (`xtdb2-disk`) will add commit-log + storage-tier latency on
  top — the floor moves up but the per-row marginal cost should track
  the same shape. The batching guidance gets stronger under persistent
  storage, not weaker.

- The numbers are from a single bench run on one machine; treat
  single-digit-percent differences as noise. The 15 ms ≫ 1 ms gap is
  large enough to be unambiguous regardless.

- `extract-and-build-sql` cost may grow for resource types with very
  large schemas or deeply nested extensions. The 0.07 ms figure is for
  uscore8 Patient. If a future schema spec pushes encoding into the
  millisecond range, the relative shape of the cost breakdown changes,
  but the per-tx floor remains the dominant single line.
