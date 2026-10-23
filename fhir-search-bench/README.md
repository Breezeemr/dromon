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
# xtdb2 (replica-log appends always pipeline from XTDB 2.2.0-rc2; the
# :xtdb-pipelined? option and XTDB_PIPELINED_REPLICA_APPENDS are gone).
bb bench-writes-xtdb

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
bb xtdb-kafka-up                 # podman pod: Apache Kafka 3.9.1 + XTDB 2.2.0 (xtdb-aws image)
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
primary database and local storage in the container. Replica-log appends
always pipeline from 2.2.0-rc2, which refuses the beta3
`indexer.pipelinedReplicaAppends` key at startup. The server runs with
`-Xmx8g`; Kafka with its image default.

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
bb flightsql-repro-release :version 2.2.0        # any published release: ghcr.io images + Maven Central client
bb flightsql-repro-nightly :focused-only true    # focused cases only, ~3 min
bb flightsql-repro-nightly :preload 20000 :repeats 1   # smaller benchmark
bb flightsql-repro-nightly :bench-only true :storage :kafka   # one storage variant (:disk, :kafka, :all)
bb flightsql-repro-from-sha :sha 9f8553ce2c3cd2133bdba7f63477caceb11ea294   # XTDB built from a commit
bb flightsql-repro-from-sha :repo https://github.com/<user>/xtdb.git :sha <sha>   # ... from a fork
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
   3 repeats) with `:xtdb-server`, over `:pgwire` as the control and over
   `:flight-sql`. The server has `rowsPerBlock: 10000`, so the preload
   flushes blocks. It runs once per storage variant (`:storage`, default
   `:all`):
   - **disk** (`docker/xtdb-flightsql/bench.yaml`): the plain `xtdb` image,
     with log and storage `!Local` on a fresh podman volume at
     `/var/lib/xtdb`. Tenant databases are attached with the same layout under
     `/var/lib/xtdb/dbs/` (`:xtdb-attach-template`).
   - **kafka** (`docker/xtdb-flightsql/bench-kafka.yaml`): the `xtdb-aws`
     image, which carries the Kafka module, in a podman pod with an Apache
     Kafka 3.9.1 broker (KRaft, one node; `message.max.bytes` raised to
     32 MiB to match the producer). Storage is local on a volume. Tenants get
     the store's default ATTACH config, as in production: a Kafka topic per
     tenant database plus local storage. The server log ends with the
     topics' end offsets, as evidence that the log went through Kafka.

   The data is kept out of memory:
   - XTDB's memory cache is capped at 256 MiB (`memoryCache.maxSizeBytes`).
     By default it takes half the JVM's max direct memory, enough for the
     whole dataset.
   - The server JVM runs with `-Xms3g -Xmx3g -XX:MaxDirectMemorySize=1g`
     under a 3500m cgroup limit (`--memory`, no extra swap), and the broker
     under 2g. cgroup v2 charges a container's page cache to its limit, so
     the OS cannot cache the data on the server's behalf either.
   - The 100k run leaves 1.1-1.6G of data on disk, and the server's JVM uses
     about 2.9G. Limits of 8g and 5g let the page cache hold 100% of the data;
     3500m keeps it near 30%.
   - Each run reports the cgroup's peak memory, its page cache against the
     data on disk, how often the limit was reached (reclaim), any OOM kills,
     and XTDB's `memory_cache_*` metrics.

   The timings are controlled for JVM warm-up and run order:
   - **Warm-up.** Each run starts a fresh server and a fresh client JVM, and
     the untimed 100k preload already exercises the write path on both. On top
     of that, one full repeat of every workload runs first and is discarded
     (`:warmup-repeats 1`, overridable), so reads, transactions and
     contention are JIT-compiled too before anything is timed. Single writes
     also discard their first 50 ops (`:warmup`).
   - **Heap.** The bench server runs with a fixed heap (`-Xms3g -Xmx3g`, see
     above) and the client JVMs with `-Xms4g`, so no timed window pays for
     heap resizing. The heaps are not pre-touched: the warm-up repeat touches
     what the timed repeats use, and a pre-touched 12g server heap was
     OOM-killed on a 64G machine shared with other work. A server that dies
     mid-run is reported with its exit code.
   - **Order.** `:rounds N` (default 2) runs the pair N times in ABBA order:
     round 1 pgwire then flight-sql, round 2 flight-sql then pgwire, and so
     on. Neither transport always runs first or last on the machine.
   - **Noise.** Each run records the 1-minute load average and the available
     memory before and after.
     `COMPARISON-<storage>.md` pools every measured repeat's p50 per
     transport across the rounds, and gives the ratio per round. It marks a workload "within
     noise" when the two transports' ranges of repeat p50s overlap.

`flightsql-repro-release :version <v>` runs against a published release. The
server images are `ghcr.io/xtdb/xtdb:<v>` and `ghcr.io/xtdb/xtdb-aws:<v>`; the
client JVMs get `com.xtdb/xtdb-core` and `xtdb-api` `<v>` from Maven Central.
It checks that the client artifacts exist and pulls the images before anything
runs, and stops with a message naming whichever is not published.

A nightly server needs the nightly client: XTDB after 2.2.0-beta3 refuses the
beta3 client's BEGIN/COMMIT over FlightSQL. So `flightsql-repro-nightly` runs
every client JVM with `com.xtdb/xtdb-core` and `xtdb-api` overridden to
`2.2.0-SNAPSHOT`. `fhir-store-xtdb2` builds its FlightSQL config with the
no-argument constructor for the same reason: the `(host, port)` constructor
is gone after beta3. The image and the snapshot are separate builds, so the
summary prints both: the image digest with its `org.opencontainers.image.revision`,
and the client jar's `Scm-Revision`.

Containers, the Kafka pod and the data volumes carry the label
`dromon.fhir-search-bench=flightsql-repro`. They are named
`fhirbench-flightsql-repro`, `fhirbench-flightsql-bench` and
`fhirbench-flightsql-kafka` (pod), and are removed when the run ends. The
task refuses to replace one of those names that it did not create, and
touches no other container, pod or volume.

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
concurrent workload then hits bug 3.

**Both PRs together fix all three bugs.** The test commit was `52cbd01c4479`, a
local merge of the two heads that exists only in a local clone and was not
pushed. It was run with `:repo <path to that clone>`, and the task exited 0:
- all 10 focused cases PASS, including 0 bad of 104429 concurrent FlightSQL reads;
- both benchmark runs PASS;
- FlightSQL's preload of 100121 resources took 31.4 s, and its contention
  workload had 0 errors besides the expected 412s and 0 lost updates.

The merge conflicted in `FixedSizeListVector.kt` and `StructVector.kt`. It
was resolved by keeping #6222's `unloadOwnPage` and using #6233's
`newEmptyBuffer()` in place of the shared `empty` buffer.

How fast FlightSQL writes are once correct was measured on the same commit,
in one full `bb flightsql-repro-from-sha` run. Every case and all 8 benchmark
runs PASSed, and the task exited 0. The setup:
- the disk and Kafka storage variants, 2 ABBA rounds each;
- per run, one warm-up repeat discarded and 3 repeats timed, so 6 repeats
  per transport and variant;
- the memory limits above: per run, the server's page cache ended at 8-19%
  of the data on disk (2.2-3.4G), the 3500m limit was reached 750-1800 times
  (reclaim), and there were no OOM kills;
- contention: 0 errors besides 412s and 0 lost updates in every run.

Median p50 in ms. `x` is flight-sql / pgwire, per round:

| workload | disk: flight-sql / pgwire | disk x | kafka: flight-sql / pgwire | kafka x |
|---|---:|---|---:|---|
| single create | 6.87 / 3.51 | 1.73, 2.68 | 7.82 / 7.21 | 1.07, 1.12 |
| single update-if-match | 18.2 / 9.07 | 1.79, 2.08 | 13.7 / 11.7 | 1.21, 1.14 |
| PUT Patient most fields | 8.16 / 5.67 | 1.23, 2.00 | 8.30 / 7.48 | 1.15, 1.09 |
| create x16 threads | 10.5 / 8.65 | 1.25, 1.18 | 4.06 / 2.72 | 1.54, 1.43 |
| update x16 threads | 10.5 / 8.74 | 1.26, 1.15 | 4.21 / 2.80 | 1.53, 1.53 |
| transaction 100 entries | 144 / 44.5 | 3.51, 3.43 | 84.0 / 40.1 | 2.05, 2.16 |
| transaction 1000 entries | 1406 / 362 | 2.93, 3.76 | 729 / 348 | 2.18, 2.03 |
| batch 1000 entries | 8062 / 3661 | 1.75, 2.26 | 8036 / 7223 | 1.11, 1.11 |
| mixed tx | 43.2 / 14.0 | 2.01, 3.09 | 22.2 / 16.8 | 1.29, 1.33 |
| contention | 57.4 / 31.3 | 1.13, 2.07 | 27.3 / 22.7 | 1.23, 1.12 |
| preload, 100121 resources | 41-45 s / 14-20 s | | 31 s / 14-16 s | |

How to read these:
- **pgwire is faster on every workload in both variants.** No per-round ratio
  is below 0.98.
- **Kafka log.** The runs are steady: the two rounds agree within about 0.1.
  A single write costs about 7 ms on either transport, mostly the Kafka round
  trip, so FlightSQL adds only 7-12% there. The gap stays at 1.4-1.5x for 16
  threads and 2x for transactions of 100+ entries.
- **Disk log.** Writes are slower and the rounds vary more (fsync on a shared
  NVMe). Many workloads are therefore "within noise" even at 1.5-2x medians.
  Transactions of 100+ entries are 3-4x slower over FlightSQL in both
  rounds.
- **Earlier unconstrained run.** It used an in-memory log and storage, with
  no memory limit. It measured smaller absolute times, about 0.9 ms per single
  write over pgwire, and the same ordering.

The full tables are in `COMPARISON-disk.md` and `COMPARISON-kafka.md`, with
per-run memory and Kafka offsets in `summary.txt` and the server logs.

### 2.2.0-rc2 (2026-10-20)

`bb flightsql-repro-release :version 2.2.0-rc2` was run on dromon main
(`bc3e12e`) and exited 0: **every case and all 8 benchmark runs PASS, and no
bug reproduces.**
- **Build:** server `ghcr.io/xtdb/xtdb:2.2.0-rc2` (`sha256:5b0406ad...`) and
  `xtdb-aws:2.2.0-rc2`, plus client `com.xtdb/xtdb-core 2.2.0-rc2`. All three
  are revision `4d86aab7b`.
- **Fixes in it:** rc2 carries upstream's squash-merge of #6222 (`69fdbb26a`,
  bugs 1 and 2) and its own empty-list offsets fix for the shared empty buffer
  (`7d299a523`, #6231); #6233 was closed unmerged.
- **Focused cases:** bugs 1 and 2 PASS with FlightSQL equal to pgwire, and
  bug 3 PASSes: 8 readers x 20 s, 0 bad of 124752 reads.
- **Benchmarks:** disk and Kafka, 2 ABBA rounds each, under the memory limits
  (page cache 12-18% of the data on disk, 0 OOM kills). Contention had 0
  errors besides 412s and 0 lost updates in every run.
- **The store's read path is not the same as in the earlier runs.** main
  includes #141, so every FlightSQL read here also goes through the store's
  `MaterializingSession` wrapper (`ROW_NUMBER() ... ORDER BY`). The wrapper
  would hide bugs 1 and 2 from the benchmark, which is why the focused cases,
  which bypass the store, are the evidence for them.

Median p50 in ms, with flight-sql / pgwire per round:

| workload | disk: flight-sql / pgwire | disk x | kafka: flight-sql / pgwire | kafka x |
|---|---:|---|---:|---|
| single create | 3.98 / 3.28 | 1.21, 1.22 | 7.91 / 7.22 | 1.10, 1.10 |
| single update-if-match | 10.1 / 7.87 | 1.31, 1.26 | 13.4 / 12.2 | 0.98, 1.17 |
| create x16 threads | 8.25 / 8.27 | 1.00, 1.00 | 4.14 / 2.81 | 1.19, 1.49 |
| transaction 100 entries | 78.8 / 39.2 | 2.03, 2.01 | 84.4 / 47.0 | 1.73, 1.97 |
| transaction 1000 entries | 730 / 345 | 2.11, 2.11 | 721 / 377 | 1.66, 2.15 |
| batch 1000 entries | 4219 / 3452 | 1.23, 1.23 | 8049 / 7280 | 1.07, 1.11 |
| mixed tx | 19.9 / 12.5 | 1.53, 1.53 | 22.3 / 17.5 | 1.10, 1.27 |
| contention | 36.5 / 25.6 | 1.46, 1.40 | 27.3 / 23.0 | 1.01, 1.26 |
| preload, 100121 resources | 29.5-30.1 s / 13.1-14.6 s | | 29.8-32.9 s / 15.8-17.8 s | |

The write gap is the same as on the local #6222 + #6233 build: about 1.1x for
single writes on Kafka and 2x for large transactions. As measured above, it
comes from FlightSQL's per-statement calls, not from serde.

For comparison:
- On 2.2.0-beta3 and nightly (2026-10-16) all three bugs reproduce, and the
  FlightSQL benchmark stops at bug 1 right after the preload (see Observed
  above). Those runs predate #141 and were not repeated on main.
- On rc2 FlightSQL is correct, including under concurrent streams, but still
  slower than pgwire for writes.

### From a git commit

`bb flightsql-repro-from-sha :sha <commit>` runs the same repro against XTDB
built from that commit. This is how to check an upstream fix before it is
released. The steps:

1. **Fetch.** A shallow fetch of the commit into `target/flightsql-repro/xtdb-src/`.
   - `:sha` takes a full 40-character sha (any commit GitHub serves, including
     PR heads), or a branch, tag or `pull/<n>/head` that `git ls-remote` resolves.
   - `:repo` is the git remote to fetch from, default
     `https://github.com/xtdb/xtdb.git`. It takes any URL or path `git fetch`
     does: a fork (`:repo https://github.com/<user>/xtdb.git`), or a local
     clone, for a commit that is not pushed anywhere
     (`:repo /path/to/xtdb :sha <its sha>`, or a local branch name as `:sha`).
2. **Build with Gradle.** `:docker:standalone:shadowJar`, plus the
   `xtdb-api`/`xtdb-core` jars and their generated POMs, with
   `XTDB_VERSION=2.2.0-repro-<sha12>`. Nothing is signed or published: the two
   jars are laid out as a Maven repository in `target/flightsql-repro/xtdb-m2/`.
   The client JVMs then resolve them from there, so server and client always
   come from the same commit.
3. **Build the images.** `podman build -f docker/Dockerfile`, labelled like the
   containers, twice:
   - `VARIANT=standalone`, tagged `localhost/xtdb-flightsql-repro:<sha12>`, for the
     focused cases and the disk-log bench;
   - `VARIANT=aws`, tagged `<sha12>-aws`, for the Kafka-log bench, since that
     variant carries the Kafka module.

   The `revision` the summary prints is the commit.
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
