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
| edit | `update-resource` that changes one field against one that changes most fields, for 200 Observations and 200 full Synthea Patients each; one field moves a single date leaf, most fields swaps in another resource's content of the same type (rounds run one/most/most/one after a discarded pair) |
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

# One workload only (any of :single :edit :concurrent :tx :mixed :rollback :contention).
bb bench-writes-xtdb :only '[:edit]' :out '"target/writes-edit-xtdb2.edn"'

# A quick smoke run.
bb bench-writes-xtdb :preload 10000 :n 50 :warmup 10 :repeats 1 :concurrent-n 100 \
  :mixed-rounds 10 :rollback-rounds 5 :contention-attempts 10
```

### Against a standalone XTDB server on Kafka

By default xtdb2 runs embedded: a node inside the benchmark JVM with its log
and storage on local disk. With `:xtdb-server "host:port"` the store instead
connects over pgwire to a standalone XTDB server (the store's `:server` mode),
the benchmark's tenant a database attached to that server with its own Kafka
topic, under a fresh `fhirbench_<hex>_` prefix.

```bash
bb xtdb-kafka-up                 # podman pod: Apache Kafka 3.9.1 + XTDB 2.2.0-beta3 (xtdb-aws image)
bb bench-writes-xtdb-server      # -> target/writes-xtdb2-server.edn
bb bench-xtdb-server :max-resources 100000   # -> target/bench-xtdb2-server.edn
bb xtdb-kafka-topics             # the tenant's topic and -replica topic, with offsets
bb xtdb-kafka-down
```

`:transport :flight-sql :xtdb-flight-sql-port 19832` runs the store's reads
and writes over the server's FlightSQL port instead (ATTACH and DETACH stay on
pgwire); `:transport` is recorded in the result and named in the report. On
XTDB 2.2.0-beta3 a FlightSQL run does not finish at the default scale
(2026-10-18):

- After the 100k preload has flushed blocks, a read of a row whose nested
  struct lacks a field the flushed blocks carry fails on the server with
  `no more buffers for field unit: Utf8. Expected 3` (or
  `result: List<Struct<reference, display>> ... Expected 2`). The same reads
  over pgwire succeed. Twenty lines reproduce it on an embedded node with
  `:indexer {:rows-per-block 20}`.
- Below that scale (`:preload 3000 :tx-sizes [1 10 100]`) the benchmark JVM
  itself died of SIGSEGV (`jbyte_disjoint_arraycopy` /
  `jlong_disjoint_arraycopy`) on a worker-pool thread, in two of two runs:
  once on this alias's netty 4.1.132 pin, with Arrow `flight-client`
  allocator leaks reported, and once with the netty family overridden to the
  store's 4.2.9 and `-Dio.netty.noUnsafe=false`. pgwire runs on the same
  classpath do not crash.

The causes are three XTDB/Arrow bugs; see
[Reproducing the XTDB FlightSQL bugs](#reproducing-the-xtdb-flightsql-bugs).

The preload is the one complete FlightSQL write measurement: 100121 resources
in 28.3 s against 12.9 s over pgwire, and 3513 in 3.5 s against 1.6 s.

Embedded, `bb bench-writes-xtdb :transport :adbc` runs the full default
benchmark to completion. On 2026-10-18 it beat embedded pgwire on every
workload: the preload in 6.6 s against 12.5 s, single writes about 1.2x,
transactions of 100 and 1000 entries 1.5-1.6x, and 16-thread creates and
updates 1.3x.

The server's config is `docker/xtdb-kafka/config.yaml`: a Kafka log for the
primary database, local storage in the container, and
`indexer.pipelinedReplicaAppends: true` (the server's YAML decoder refuses an
unknown key, so the setting is known to be read; `XTDB_PIPELINED_REPLICA_APPENDS`
is set as well). The server runs with `-Xmx8g`; Kafka with its image default.

XTDB writes each transaction as one Kafka message, so Kafka's 1 MiB default
refuses large transactions: a 1000-entry Synthea transaction serializes to
1.1-1.6 MB and fails with `max.request.size`. The pod raises the producer's
`max.request.size` and the broker's `message.max.bytes` to 32 MiB; a
production deployment carrying bundles this size needs the same.

The two stores' durability settings are not normalized. xtdb2 writes its log
to local disk inside the benchmark's own process. Datomic commits through the
dev transactor, a separate process on local storage. Neither store's fsync
behaviour is configured or verified by this harness.

## Reproducing the XTDB FlightSQL bugs

fhir-store-xtdb2's `:transport :flight-sql` is unsafe on XTDB 2.2.0-beta3. Three
bugs in XTDB's Arrow streaming make FlightSQL reads fail, return wrong values
without an error, or crash the client JVM. pgwire, embedded `xt/q` and the
store's `:adbc` transport read pages row by row, by name, and are unaffected.

| bug | cause | what it looks like |
|---|---|---|
| 1 | `ResultCursor.toArrowReader()` (`Xtdb.kt`) loads each result page, by position, into a root built from the query's declared (merged) result type. A page carries only the struct fields and union legs its own rows use, as when a flushed block and the live index differ. | Server-side `no more buffers for field X. Expected N` or `no more field nodes for field X`, sent to the client as the query's error. |
| 2 | The same positional load, when a page's struct fields come in another order than the declared type. A server JVM keeps the first order it saw for a given field set, across every database and table. | No error: each field gets another field's buffers, so strings come back swapped. When an f64 lands on a Utf8 field, the `xtdb.flight-sql` client decodes its bytes as offsets without bounds checks, and the client JVM dies of SIGSEGV in `VariableWidthVector.getByteArray`. |
| 3 | Arrow's zero-length buffer is one object per JVM, with mutable reader/writer indices. XTDB puts it into the batches it streams, and concurrent FlightSQL streams overwrite each other's indices. | Server-side `IndexOutOfBoundsException: index: 0, length: 4 (expected: range(0, 0))`, the stream cancelled (`RST_STREAM`), wrong rows, or a client SIGSEGV. Under write load the server can stop ingesting (`Ingestion stopped`). A single stream never fails. |

Upstream, draft [xtdb/xtdb#6222](https://github.com/xtdb/xtdb/pull/6222) fixes
bugs 1 and 2: it copies each page into the declared schema by name before
unloading it. It does **not** fix bug 3, which draft
[xtdb/xtdb#6233](https://github.com/xtdb/xtdb/pull/6233) addresses separately.
dromon's draft client-side workaround for bugs 1 and 2 is
[Breezeemr/dromon#141](https://github.com/Breezeemr/dromon/pull/141).

### Prerequisites

- podman (rootless is fine), with network access to ghcr.io.
- bb and the Clojure CLI, with network access to Maven Central and, for the
  nightly run, Maven Central's snapshot repository
  (`https://central.sonatype.com/repository/maven-snapshots/`).
- Java 24+, for the `:xtdb` alias's flags (`--add-opens=java.base/java.nio=ALL-UNNAMED`,
  `--sun-misc-unsafe-memory-access=allow`, `--enable-preview`). The task adds
  `-XX:ErrorFile=<evidence dir>/hs_err_*` and `-XX:-CreateCoredumpOnCrash`, so
  a client crash leaves a log rather than a core dump.
- The Synthea dataset for the write benchmark (`bb synthea`, or pass
  `:synthea-dir`); `:focused-only true` skips the benchmark and needs none.
- Free host ports 25432-25433, 28090-28091 and 29832-29833 on 127.0.0.1. They
  avoid the `fhirbench-xtdb` and `xtdb2-local` pods (15432/18090/19092/19832).

### Run

```bash
bb flightsql-repro-nightly                       # ghcr.io/xtdb/xtdb:nightly
bb flightsql-repro-beta3                         # ghcr.io/xtdb/xtdb:2.2.0-beta3
bb flightsql-repro-nightly :focused-only true    # focused cases only, ~3 min
bb flightsql-repro-nightly :preload 20000 :repeats 1   # smaller benchmark
bb flightsql-repro-from-sha :sha 9f8553ce2c3cd2133bdba7f63477caceb11ea294   # XTDB built from a commit
bb flightsql-repro-down                          # remove leftover containers
```

The task pulls the image (or, for `-from-sha`, builds it; see below), then runs the following, each against a fresh
container with an in-memory log and storage:

1. **Focused cases** (`fhir-search-bench.flightsql-repro`). Rows are written
   over pgwire, then each read runs over pgwire and over FlightSQL and the two
   are compared. Each group runs in its own JVM, so a client crash ends only
   that group.
   - Bug 1, on a server with `rowsPerBlock: 20`: 50 rows flush with
     `vq {value, unit}` and a long `v`; one live row has `vq {value}` and a
     string `v`. The cases are a by-id read of the live row, a by-id read of a
     flushed row, and `SELECT *`.
   - Bug 2: a struct `{alpha, bravo, charlie}` in one table, then the same
     fields in another order in a second table (strings swapped). The f64
     variant writes `{system, unit, value, code}` in two orders, and its
     second read can crash the client.
   - Bug 3, on a server that flushes no block: 200 Observation-shaped rows in
     one key order, where every third row carries a `component` list of
     structs holding a list. Eight threads read random rows for 20 s
     (`:race-secs`). The controls are the same reads over pgwire and over a
     single FlightSQL stream.
2. **The write benchmark** (`fhir-search-bench.writes`, defaults: 100k preload,
   3 repeats) with `:xtdb-server`, first over `:pgwire` as the control, then
   over `:flight-sql`. The server has `rowsPerBlock: 10000`, so the preload
   flushes blocks. Tenant databases are attached with
   `:xtdb-attach-template "log: !InMemory\nstorage: !InMemory"`, since the
   plain `xtdb` image has no Kafka module.

A nightly server needs the nightly client: XTDB after 2.2.0-beta3 refuses the
beta3 client's BEGIN/COMMIT over FlightSQL. So `flightsql-repro-nightly` runs
every client JVM with `com.xtdb/xtdb-core` and `xtdb-api` overridden to
`2.2.0-SNAPSHOT`. `fhir-store-xtdb2` builds its FlightSQL config with the
no-argument constructor for the same reason: the `(host, port)` constructor
is gone after beta3. The image and the snapshot are separate builds, so the
summary prints both: the image digest with its `org.opencontainers.image.revision`,
and the client jar's `Scm-Revision`.

Containers carry the label `dromon.fhir-search-bench=flightsql-repro`. They are
named `fhirbench-flightsql-repro` and `fhirbench-flightsql-bench`, and are
removed when the run ends. The task refuses to replace a container of either
name that it did not create, and touches no other container.

### Output

Everything lands in `target/flightsql-repro/<version>/`:
- `summary.txt`;
- each JVM's output (`focused-*.out`, `bench-*.out`);
- each server's log (`server-*.log`);
- the benchmark results (`writes-*.edn`);
- any client crash log (`hs_err_*.log`).

The task exits 0 only when every case and both benchmark runs PASS, so a fixed
XTDB build reads as exit 0.

A focused case passes when FlightSQL returns what pgwire returns. A benchmark
run passes when all of the following hold:
- the JVM exits 0 and writes its result;
- the result shows no errors other than the expected 412s, no readback
  mismatch and no lost update;
- neither the client output, the server log nor a crash log has a bug's
  signature: the error texts in the table above, plus `Not enough capacity
  for data buffer` / `Unable to allocate buffer of size` for bug 2.

A run that stops without a signature, e.g. a connection or classpath problem,
is reported as ERROR, not as a reproduction. If a pgwire control does not pass,
the summary says the FlightSQL results are not conclusive.

### Observed

These runs were on 2026-10-19 with OpenJDK 25. The nightly server was
`ghcr.io/xtdb/xtdb:nightly`, i.e. `nightly-20261016`
(`sha256:2277eb83...`, revision `c0923cdd3`). Its client was the
`2.2.0-SNAPSHOT` jar of 2026-10-19 (`Scm-Revision 287517d88`, the upstream
base of #6222 and #6233). The beta3 server and client were both revision
`591d5c593`. **All three bugs reproduce on nightly and on beta3**, with the
same error text:

| case | nightly | beta3 |
|---|---|---|
| bug1-live-row-lacks-struct-field | FAIL `no more buffers for field i64: Int(64, true) not null. Expected 2` | same |
| bug1-flushed-row-long-string-union | FAIL `no more field nodes for field value: FloatingPoint(DOUBLE) not null and vector []` | same |
| bug1-select-star | FAIL, as above | same |
| bug2-first-key-order (control) | PASS | PASS |
| bug2-second-key-order-strings | FAIL: written `{b2_charlie "C", b2_alpha "A", b2_bravo "B"}`, read `{b2_alpha "C", b2_bravo "A", b2_charlie "B"}`, no error | same |
| bug2-mixed-second-key-order-f64 | FAIL: client JVM SIGSEGV | same |
| bug3-control-pgwire-concurrent-reads | PASS, 0 bad of ~31k | PASS |
| bug3-control-flight-sql-one-reader | PASS, 0 bad of ~26k | PASS |
| bug3-flight-sql-concurrent-reads | FAIL: wrong rows, e.g. `:system ""` or `:text ""`, then a client SIGSEGV within seconds; server log 6-46x `index: 0, length: 4 (expected: range(0, 0))` | FAIL, same; 40x on the server |
| write bench, pgwire | PASS | not rerun |
| write bench, flight-sql | FAIL: the 100121-resource preload completes (31.5 s), then the first read aborts the run with `no more buffers for field code: Utf8 not null. Expected 3` | not rerun; failed the same way on 2026-10-18 (above) |

Notes on these results:
- On nightly the FlightSQL benchmark stops at bug 1 before it reaches the
  concurrent workloads. The bug 3 evidence is therefore the focused race
  case, not the benchmark.
- The client JVMs exit with status 1 rather than 134 on SIGSEGV, because the
  task passes `-XX:-CreateCoredumpOnCrash`.
- The pgwire benchmark's one failed check, the response order of a
  transaction, comes from fhir-store-xtdb2, not the transport. The summary
  lists it as "not judged": the store answers a transaction's DELETE entry
  first, whatever the request order.

The two upstream drafts were built from source with `bb flightsql-repro-from-sha`
(see below) on 2026-10-19. Each fixes exactly the bugs it claims:

| case | #6222 head `9f8553ce2c3c` (bugs 1-2), full run | #6233 head `979b221252f2` (bug 3), `:focused-only` |
|---|---|---|
| bug 1, all three cases | PASS | FAIL, same text as nightly |
| bug 2, strings only | PASS | FAIL, strings swapped |
| bug 2, f64 | PASS | FAIL, but no client crash: the server now throws `index: 0, length: 1078853632 (expected: range(0, 0))` (60.0's high word read as an offset) and cancels the stream |
| bug 3, 8 readers | FAIL: wrong rows (`:code "    "`), `RST_STREAM ... CANCEL`, client SIGSEGV; 5x `range(0, 0)` on the server | PASS, 0 bad of 123262 reads |
| bug 3 controls | PASS | PASS |
| write bench, pgwire | PASS | not run |
| write bench, flight-sql | FAIL, bug 3: the run completes, but `[:contention]` has 47 errors (`Interrupted` 40, `Fault` 6, `Incorrect` 1) and **one lost update**; 40x `range(0, 0)` on the server | not run |

So with #6222 the FlightSQL benchmark now gets past bug 1 and completes. Its
concurrent workload then hits bug 3. A build with both PRs applied has not been
run.

### From a git commit

`bb flightsql-repro-from-sha :sha <commit>` runs the same repro against XTDB
built from that commit. This is how to check an upstream fix before it is
released. The steps:

1. **Fetch.** A shallow fetch of the commit into `target/flightsql-repro/xtdb-src/`.
   - `:sha` takes a full 40-character sha (any commit GitHub serves, including
     PR heads), or a branch, tag or `pull/<n>/head` that `git ls-remote` resolves.
   - `:repo` names another remote, e.g. a fork; the default is
     `https://github.com/xtdb/xtdb.git`.
2. **Build with Gradle.** `:docker:standalone:shadowJar`, plus the
   `xtdb-api`/`xtdb-core` jars and their generated POMs, with
   `XTDB_VERSION=2.2.0-repro-<sha12>`. Nothing is signed or published: the two
   jars are laid out as a Maven repository in `target/flightsql-repro/xtdb-m2/`.
   The client JVMs then resolve them from there, so server and client always
   come from the same commit.
3. **Build the image.** `podman build -f docker/Dockerfile` with `VARIANT=standalone`,
   tagged `localhost/xtdb-flightsql-repro:<sha12>` and labelled like the
   containers. The `revision` the summary prints is the commit.
4. **Run** the focused cases and the benchmark, as above. Evidence lands in
   `target/flightsql-repro/sha-<sha12>/`, including `build-xtdb.out` and
   `build-image.out`.

A sha that is already built is reused. Pass `:rebuild true` to build it again.

XTDB's Gradle build compiles on JDK 21 and runs on 25. It finds them through
`JAVA_HOME_21_X64` and `JAVA_HOME_25_X64`, which default to
`/usr/lib/jvm/java-{21,25}-openjdk-amd64`. With a warm Gradle cache the build
takes about a minute; the image build pulls `ghcr.io/xtdb/xtdb-builder`.
To remove the built images:

```bash
podman rmi $(podman images -q --filter label=dromon.fhir-search-bench=flightsql-repro)
```

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
