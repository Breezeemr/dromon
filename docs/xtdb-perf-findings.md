# XTDB v2 FHIR Store Performance Findings

Accumulated knowledge from profiling and optimizing the `fhir-store-xtdb2` backend
against the Inferno US Core v6.1.0 suite, using the datomic backend as a reference.
Work done 2026-06-02 on XTDB `com.xtdb/xtdb-core 2.2.0-beta1`.

## TL;DR

- Starting point: xtdb was ~1.9x slower than datomic on server-side per-request latency
  (p50 14.7 ms vs 8.4 ms) with identical conformance (505 pass / 0 fail / 0 errors).
- **The one big win was connection pooling** (`xt/q`/`execute-tx` were opening a fresh
  pgwire connection per call). A per-tenant HikariCP pool cut p50 to ~10.0 ms, closing
  roughly three-quarters of the gap.
- Cache/memory config, SQL-text stabilization, ADBC, and prepared statements were each
  investigated and found to be **not worth pursuing** for this workload (reasons below).
- The residual ~1.5 ms gap to datomic is dominated by per-query-shape compilation inside
  XTDB plus the protocol-independent HTTP/JSON/TLS floor — closing it further needs an
  XTDB-side lever, not a store-layer change.

## How to reproduce

The perf harness is the Inferno suite with JFR + GC profiling toggled on:

```bash
cd dromon && DROMON_PERF_PROFILE=1 DROMON_PERF_HEAP=6g bb inferno-test
# JFR + gc.log land in dromon/perf-analysis/ ; HTTP timings in dromon/server.log
```

Server-side latency is parsed from `server.log` (`[http]` entries carry `duration-ms`);
`perf-analysis/analyze-log.clj` summarizes per-endpoint p50/p95. Always run xtdb then
datomic in the same session — the HL7 validator cache warms on the first run, so
wall-clock comparison across a cold/warm split is meaningless; rely on the server-side
numbers. See the `inferno-perf-compare` skill for the full comparison protocol.

## Where the time goes (JFR attribution)

Profiled run, ~1000 execution samples over an 82 s suite:

| subsystem | share of CPU samples | notes |
|---|---|---|
| SQL parse + plan + emit (query compilation) | ~28-30% | server-side, protocol-independent |
| Jetty/HTTP + TLS handshake | ~40% | inherent to the REST API |
| FHIR JSON response encoding (jackson/cheshire) | ~17% | inherent to the API |
| per-call pgwire connection setup | significant (pre-pooling) | eliminated by pooling |
| malli decode (`xtdb->fhir`) | ~3.6% | minor |
| **GC** | **~0.3%** (255 ms total) | idle |
| **Arrow buffer pool** | **~1.4%** | idle |

The key takeaway: GC and the Arrow buffer pool are idle, so this is **not** a memory/cache
problem; the cost is in the query path and the inherent HTTP/JSON work.

## What did NOT help

### Cache / memory / node config

Ruled out by the idle GC + buffer-pool attribution, and confirmed empirically: an
aggressive node-config overlay
`{:indexer {:flush-duration "PT1H" :log-limit 1000000 :rows-per-block 1000000} :compactor {:threads 1}}`
produced identical latency (p50 13.9 vs 14.0 ms baseline) and identical compile share.
The hypothesis was that indexer block flushes invalidate XTDB's compiled-query cache; they
do not drive the cost here. The `XTDB_NODE_CONFIG` env hook (test-server) was added so such
overlays can be swept without code edits, and remains useful for future experiments.

### SQL-text stabilization (Step 2)

Parameterizing `LIMIT/OFFSET` and replacing variable `IN (?, ?, ...)` with a stable
`_id = ANY(?)` (bound to a Clojure vector) keeps query text constant across pages and id
counts, so repeated shapes hit XTDB's plan cache. Effect on Inferno was **within run-to-run
noise** (p50 10.05 -> 9.9 ms; compile share 29.2% -> 28.4%). It was kept as hygiene because
it matters for real deep-pagination / large-bundle workloads (unbounded distinct SQL texts
otherwise pressure the 4096-entry plan cache), not because it measurably helped the suite.
Why it was flat: the residual compile cost is dominated by the inherent variety of distinct
search `WHERE` shapes (each compiled once), which text stabilization cannot remove.

### ADBC / Arrow Flight SQL

XTDB ships an ADBC driver and already runs a Flight SQL server, so it is a supported
transport. Its strength is bulk columnar result transfer, which has almost nothing to
amortize over for FHIR reads (1 row) and searches (handful of rows). It does not touch the
dominant costs (server-side compilation, HTTP/JSON/TLS), so it was judged not worth the
rewrite. It would pay off for bulk export / analytics workloads returning many rows.

### Prepared statements

Reusing a prepared statement only skips the **ANTLR parse** on re-execution — planning is
already cached node-side (plan-cache, 4096 entries, shared across connections) and emit goes
through the per-plan emit-cache regardless. Parse is the small end of the compile cost, and
prepared statements only amortize over **repeated** execution of the same statement, whereas
the residual cost is shape diversity (one-shot compiles). Estimated upside ~3-8% (~0.3-0.7 ms
off p50), likely within noise, for meaningful added complexity (a per-pooled-connection
statement cache keyed by SQL text, with schema-change invalidation). Not pursued.

## What DID help: per-tenant connection pooling (Step 1)

In 2.2.0-beta1, `xtdb.api/q` and `execute-tx` are built on next.jdbc: handed the node, each
call opens a fresh pgwire JDBC connection (`BEGIN`, plan+exec, `ROLLBACK`/`COMMIT`, close).
`with-conn` reuses a `Connection`/`DataSource` if you pass one instead — exactly what the
XTDB docstring recommends. The fix: a bounded HikariCP pool per tenant over the node (which
implements `javax.sql.DataSource`), borrowing one pooled connection per `:sql`-mode store
operation. The `:xtql` pathway is untouched.

Server-side latency (Inferno US Core, 944 requests, same machine; datomic = 940 requests):

| run | total ms | p50 | p95 |
|---|---|---|---|
| baseline (no pool) | 16,418 | 14.71 | 27.35 |
| + connection pool (Step 1) | 12,229 | 10.05 | 23.55 |
| + SQL-text stabilization (Step 2) | 11,977 | 9.90 | 23.79 |
| datomic (reference) | 8,684 | 8.43 | 15.15 |

Pooling cut p50 by ~32% and total server time by ~26%, closing ~3/4 of the gap to datomic,
with conformance unchanged at 505/0/0. `min-idle 4` performed the same as `min-idle 24`,
confirming the win is connection **reuse**, not pre-warming. Pool sizing is tunable via
`:pool-opts` on the store / the `:fhir-store/xtdb2-store` integrant config.

## Notes / gotchas

- The XTDB node starts a pgwire server by default (`Server started at postgres://...`);
  `xt/q` connects to it as a JDBC client even in-process. This is why connection reuse
  matters even though everything is one JVM.
- XTDB's `emit-cache` (Clojure-codegen cache, maxSize 16) is built **per plan-cache entry**,
  so it sees only one table's `scan-vec-types` and does **not** thrash across resource types.
  Stable, repeated SQL text therefore caches fully; only genuinely distinct shapes recompile.
- The `fhir-store-xtdb2` kaocha unit suite currently errors at `xtn/start-node`
  (`ExceptionInInitializerError`) under 2.2.0-beta1 — this predates the pooling change
  (reproduced on the unmodified tree) and is a kaocha-harness/environment issue, not a store
  bug. The authoritative gate is the Inferno integration run.

## Remaining gap and possible future levers

The residual ~9.9 vs 8.4 ms p50 is small and dominated by (a) per-shape query compilation
inside XTDB and (b) the protocol-independent HTTP/JSON/TLS floor. Neither is cheaply
addressable at the store layer. Realistic further levers are XTDB-side: a larger/smarter
plan cache, or folding the ANTLR parse behind the plan-cache lookup so warm shapes skip it.
Worth re-checking on later XTDB 2.2.x builds.
