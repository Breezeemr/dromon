# Parallelize `current-versions` lookups in xtdb2 transact-transaction

## Status

Proposed. Not started.

## Context

After the recent transact-transaction refactor (see commit history for
`fhir-store-xtdb2/src/fhir_store_xtdb2/core.clj` around
`current-versions-bulk` and the `:store/transact-transaction.*` sub-spans),
the bundle write path looks like:

```
store/transact-transaction           260 ms   (85-entry inferno bundle)
  build-tx                             1 ms
  current-versions                   144 ms   ← 27 IN-queries, one per type
  sql-encode                           9 ms
  execute-tx                          86 ms
  build-response                       0 ms
```

`current-versions` is now the single largest contributor at 144 ms. Its
job is to fetch the existing `fhir_version` for every PUT id so the
sql-encode phase can compute `next-version`. After the bulk fix it issues
one query per distinct resource type — for the inferno bundle that is
27 separate `SELECT _id, fhir_version FROM <type> WHERE _id IN (?, ?, ...)`
calls, each ~5 ms. The 5 ms is dominated by xt/q fixed overhead (SQL
planning + IPC to the XTDB node), not the row count: 26 of the 27 types
have a single id in the IN list.

These queries are independent of one another. They can be issued
concurrently. This proposal evaluates whether parallelism would actually
help and how to wire it up safely.

## Goal

Reduce `current-versions` from ~144 ms to something close to
`max(per-query-latency)` (~10–15 ms), without breaking transactional
semantics or destabilising the XTDB node under load.

## Proposal

Replace the sequential `reduce` over `(group-by :resource-type ...)` with
parallel issuance, then merge results.

```clojure
put-versions-by-type
(t/trace!
 {:id :store/transact-transaction.current-versions
  :data {:entry-count (count entry-metas)}}
 (let [grouped (group-by :resource-type
                         (filter #(= "PUT" (:method %)) entry-metas))]
   (->> grouped
        (mapv (fn [[rt metas]]
                (future
                  [rt (current-versions-bulk node rt (map :id metas))])))
        (mapv deref)
        (into {}))))
```

Use `future` (not `pvalues`) so the work runs on the bounded
`clojure.core/agent-soloExecutor` pool rather than blocking the
calling thread, and so we get exception propagation on `deref`.

## Open questions to validate before shipping

1. **Does parallelism actually help with xtdb2's query pipeline?** xt/q
   is a synchronous JVM call into the XTDB node. The node itself has
   internal concurrency (planner, executor, storage) but per-call
   contention may serialize work behind a single lock. Bench it against
   the existing micro-benchmark before committing.
2. **What is the upper bound on fan-out?** A bundle with 100 distinct
   resource types would spawn 100 futures. The `agent-soloExecutor`
   pool is unbounded, so this is fine for short-running queries but
   could starve the JVM under sustained pressure. Cap at e.g. 16
   concurrent futures via a semaphore if benchmarks show contention.
3. **Does the OTel span hierarchy survive?** `t/trace!` opens a span on
   the calling thread; spans started inside futures will be detached
   from the parent unless we explicitly propagate the OTel context via
   `(fhir-store.protocol/with-otel-context ...)` inside each future.
   Without that, the breakdown spans inside `current-versions-bulk`
   (none today, but planned) would dangle in Jaeger.
4. **Error handling.** If one future throws (e.g., schema lookup
   failure for an unknown resource type), the others still complete.
   We need to `(mapv deref ...)` after collection so the first failure
   surfaces as an exception out of `transact-transaction`. The
   transaction has not been committed yet at this point, so failing
   fast is correct.

## Verification plan

1. **Micro-bench against the inferno bundle** using the same harness
   that produced the original 891 ms → 260 ms numbers. Run with
   `iterations=5` (drop the first as warmup), record
   `:store/transact-transaction.current-versions` p50 and p95.
2. **Two-tail sanity check:** also run a 1-entry bundle (1 future) and
   a 200-entry single-type bundle (1 future, 200 ids in IN list) to
   make sure the parallel path doesn't regress either degenerate case.
3. **Inferno full suite under DROMON_OTEL=1** to confirm 505/505 still
   passes and inspect Jaeger for the new span shape.
4. **fhir-store-xtdb2 unit tests** — `test-transact-transaction` is
   the regression guard for response-shape correctness.

## Expected gain

Best case: `current-versions` drops from ~144 ms to ~15 ms, taking
total `transact-transaction` from 260 ms to ~130 ms. That would put
xtdb2 about 2× ahead of datomic (258 ms) on the same bundle.

Worst case: xtdb2's internal lock contention serializes the futures
and we save nothing. In that case, the alternative proposal
[`xtdb2-transact-cas-guard.md`](xtdb2-transact-cas-guard.md) is the
more promising path.

## Risks

- **Low.** The change is local to one phase of `transact-transaction`,
  is purely a read-side optimization (no transactional semantics
  change), and is gated behind sub-spans so a regression would be
  visible immediately in OTel data.
- The biggest behavioural risk is OTel context propagation through
  `future`, which is a known pattern (use `with-otel-context` inside
  each future) but is easy to forget.

## Out of scope

- Parallelizing the read-back path for GET/HEAD entries inside a
  transaction. Today GET/HEAD inside a `transaction` Bundle is
  vanishingly rare; the inferno bundle has none. Address only if
  Jaeger ever shows it as a cost.
- Parallelizing `execute-tx`. The XTDB commit is inherently serial.

## Depends on

- Nothing structural. Bench harness lives in the test-server test
  module as a `^:xtdb-bench`-tagged deftest (currently a throwaway,
  resurrect from git history if needed).
