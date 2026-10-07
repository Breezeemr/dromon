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
```

Resources are loaded one FHIR transaction bundle at a time (the shared
hospital/practitioner-information bundles first, then each patient bundle),
keeping Synthea's native `urn:uuid` intra-bundle references so each store's
`transact-transaction` resolves them atomically.

Another store runs this harness in its own JVM with its own classpath, passing
`:store-fn`: a qualified symbol naming a no-argument function that answers a
fresh store. Its result file is then compared with xtdb2's by `bb report`.

## Prerequisites

- **Java 21** (XTDB v2). The bb tasks pin `/usr/lib/jvm/java-21-openjdk-amd64`.

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
