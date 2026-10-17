# fhir-search-bench

A FHIR-search performance harness for dromon's store, **fhir-store-xtdb2**
(XTDB v2), at larger dataset scales. Any other `IFHIRStore` runs through the
same harness from its own project and is compared with `bb report`;
master-at-arms2's `dromon-datomic` does this for Datomic (`bb bench` there).

The methodology is adapted from
[Blaze's FHIR-search performance suite](https://samply.github.io/blaze/performance/fhir-search.html):
synthetic [Synthea](https://github.com/synthetichealth/synthea) data, a workload
of code / category / date searches over `Observation`, and per-query timing with
matched-resource throughput.

Unlike Blaze's 100k–1M patient runs, this starts small — the dataset is **capped
at ~10k resources** by default so it is fast to load and light on disk. Bump the
cap and the Synthea population to scale up later.

## What it measures

For each backend, in-process against the `IFHIRStore` protocol (no HTTP, no Ory
auth — so the numbers isolate storage + search, the layer Blaze measures):

1. **Load time** — wall-clock to write the whole dataset, plus resources/second.
2. **Search latency** — median of 5 runs per query, with hit counts and
   matched-resource throughput.

## Layout

```
src/fhir_search_bench/
  synthea.clj   download + run Synthea -> synthea-output/fhir/*.json
  dataset.clj   per-bundle entries (native POST + urn:uuid), filter, cap at ~10k
  schema.clj    resolve uscore8 schemas + per-type search registries
  queries.clj   the Blaze-style Observation query set
  bench.clj     per-backend load + search harness; report aggregator
  writes.clj    per-backend write + transaction harness; its report
```

Resources are loaded one FHIR transaction bundle at a time (the shared
hospital/practitioner-information bundles first, then each patient bundle),
keeping Synthea's native `urn:uuid` intra-bundle references so each store's
`transact-transaction` resolves them atomically.

Another store runs this harness in its own JVM with its own classpath, passing
`:store-fn`: a qualified symbol naming a no-argument function that answers a
fresh store. Its result file is then compared with xtdb2's by `bb report`.

## Prerequisites

- **Java 24+**. The `:xtdb` alias passes `--sun-misc-unsafe-memory-access=allow`,
  which Arrow's allocator needs from Java 24 on and which Java 23 and older
  reject.

## Usage

```bash
# 1. Generate the dataset (downloads the Synthea jar on first run, ~80 MiB).
bb synthea                       # ~12 living patients
bb synthea :population 20        # larger

# 2a. Benchmark xtdb2 (writes an on-disk node under data/xtdb2/).
bb bench-xtdb

# 3. Report into target/REPORT.md, alone or against another store's result.
bb report
bb report :b '"../../dromon-datomic/target/bench-datomic.edn"'
```

Each run writes `target/bench-<backend>.edn` in the directory it ran from;
`bb report` compares `:a` (default `target/bench-xtdb2.edn`) with `:b`.

### Without bb

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  clojure -X:xtdb    fhir-search-bench.bench/run :backend :xtdb2
clojure -X fhir-search-bench.bench/report
```

`run` accepts `:max-resources N` to change the cap.

## Write and transaction benchmark

`writes.clj` measures the write side through the same `IFHIRStore` protocol,
store construction and dataset. A run preloads the dataset untimed (default
100k resources, the search benchmark's scale) so writes land in a populated
database, then runs every workload `:repeats` times (default 3):

| workload | what is timed |
|---|---|
| single | `create-resource`, `update-resource`, update with `:if-match`, `delete-resource`; one thread, 200 ops each after 50 warmup |
| concurrent | create then update 400 distinct Observations at 1, 4 and 16 threads |
| tx-size | `transact-transaction` (atomic) and `transact-bundle` (batch) at 1, 10, 100 and 1000 entries |
| mixed | one transaction: POST an Encounter and 5 Observations referencing it by `urn:uuid`, PUT an Observation with `ifMatch`, DELETE another |
| rollback | 10 PUTs then a PUT with a stale `ifMatch`; the whole transaction must fail |
| contention | 8 threads x 25 attempts to read then update one Observation with `:if-match` |

Write inputs come from Synthea bundles the preload did not read
(`dataset/build :skip-files`). Single-resource writes keep only
Observation fields that hold no reference and point `subject` at anchor
Patients created during setup; transaction slices drop every `urn:uuid`
reference the slice itself does not resolve, and batch slices drop all of
them. Both are needed because fhir-store-datomic refuses a dangling reference
with a 422 where xtdb2 keeps it as a string, which would otherwise compare a
failure with a success. Updates always change content (status and value), since
Datomic diffs an update against the stored version.

Checks recorded beside the timings, flagged in the report's notes column:

- a write is readable as soon as the call returns (`visible-on-return`), so
  neither store is timed on work it finishes later;
- a sample of updated resources reads back with the status, code, quantity and
  subject reference that were written;
- transaction POSTs read back, and a mixed transaction's Observations point at
  the Encounter it created;
- a rolled-back transaction leaves none of its entries and does not move its
  target's version;
- contention loses no update: the final version count is successes + 1;
- the transaction-response lists entries in request order, as FHIR requires.

Failed ops stay in the latency numbers. The report gives, per workload, the
median across repeats of each repeat's p50 and p95, ops/s (successful ops over
wall time; entries/s for transactions), and each repeat's p50 so a difference
can be weighed against run-to-run spread.

```bash
# xtdb2 (pipelined replica appends on by default; the store sets the flag
# itself, so XTDB_PIPELINED_REPLICA_APPENDS has no effect).
bb bench-writes-xtdb
bb bench-writes-xtdb :xtdb-pipelined? false :out '"target/writes-xtdb2-nopipe.edn"'

# Datomic, from master-at-arms2/dromon-datomic (shared dev transactor on :4334).
bb bench-writes

# Compare; :label-a/:label-b name the columns for a before/after of one store.
bb writes-report :b '"../../dromon-datomic/target/writes-datomic.edn"'

# A quick smoke run.
bb bench-writes-xtdb :preload 10000 :n 50 :warmup 10 :repeats 1 :concurrent-n 100 \
  :mixed-rounds 10 :rollback-rounds 5 :contention-attempts 10
```

The two stores' durability settings are not normalized. xtdb2 writes its log
to local disk inside the benchmark's own process. Datomic commits through the
dev transactor, a separate process on local storage. Neither store's fsync
behaviour is configured or verified by this harness.

## Notes

- Both stores return `[]` for unsupported search params rather than erroring, so
  a 0-hit `:extended`-tier query may mean "not supported" rather than "no match".
- **Conditional references are stripped.** Synthea links cross-bundle resources
  with conditional references like
  `Organization?identifier=https://github.com/synthetichealth/synthea|<uuid>`.
  Not every store can resolve these to an entity (fhir-store-datomic aborts the
  bundle with `:db.error/not-an-entity`); xtdb2 keeps them as opaque strings.
  Since they are not part of the search workload, `dataset.clj` drops them so
  every store loads identical data and every query returns identical hit
  counts.
- `synthea-with-dependencies.jar`, `synthea-output/`, `data/`, and `target/` are
  git-ignored (regenerable, large).
