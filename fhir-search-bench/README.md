# fhir-search-bench

A FHIR-search performance harness for comparing the two dromon storage backends
— **fhir-store-xtdb2** (XTDB v2) and **fhir-store-datomic** (Datomic) — at larger
dataset scales.

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

The two backends are mutually exclusive on the classpath, so the bench runs
**once per backend** (selected by deps alias) — matching how the inferno perf
comparison sequences them.

## Prerequisites

- **Java 21** (XTDB v2). The bb tasks pin `/usr/lib/jvm/java-21-openjdk-amd64`.
- **Datomic dev transactor** for the datomic run — uses `../../local-datomic/datomic-pro`.

## Usage

```bash
# 1. Generate the dataset (downloads the Synthea jar on first run, ~80 MiB).
bb synthea                       # ~12 living patients
bb synthea :population 20        # larger

# 2a. Benchmark xtdb2 (writes an on-disk node under data/xtdb2/).
bb bench-xtdb

# 2b. Benchmark datomic. Start the transactor in another terminal first:
bb transactor
bb bench-datomic

# 3. Aggregate into target/REPORT.md.
bb report
```

`run-datomic.sh` is a one-shot convenience that starts a fresh transactor, runs
the datomic benchmark, and tears the transactor down again:

```bash
./run-datomic.sh    # transactor lifecycle + bb bench-datomic in one step
```

Each backend run writes `target/bench-<backend>.edn`; `bb report` reads both and
emits the side-by-side `target/REPORT.md`.

### Without bb

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  clojure -X:xtdb    fhir-search-bench.bench/run :backend :xtdb2
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  clojure -X:datomic fhir-search-bench.bench/run :backend :datomic
clojure -X fhir-search-bench.bench/report
```

`run` accepts `:max-resources N` to change the cap.

## Notes

- Both stores return `[]` for unsupported search params rather than erroring, so
  a 0-hit `:extended`-tier query may mean "not supported" rather than "no match".
- **Conditional references are stripped.** Synthea links cross-bundle resources
  with conditional references like
  `Organization?identifier=https://github.com/synthetichealth/synthea|<uuid>`.
  The Datomic backend cannot resolve these to an entity (it aborts the bundle
  load with `:db.error/not-an-entity`); xtdb2 keeps them as opaque strings.
  Since they are not part of the search workload, `dataset.clj` drops them so
  both backends load identical data and every query returns identical hit
  counts. (This is a real limitation of `fhir-store-datomic` on raw Synthea.)
- `synthea-with-dependencies.jar`, `synthea-output/`, `data/`, and `target/` are
  git-ignored (regenerable, large).
