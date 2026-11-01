# Bulk export job store and several replicas

`$export` kickoff, status, cancel and file download are separate HTTP requests.
Behind a load balancer they can land on different replicas, so every replica
must see the same job records. This note describes how dromon keeps that
correct and what a host must supply.

## What is shared and what is not

| State | Where | Shared across replicas? |
|---|---|---|
| Job records (basis, manifest, file descriptors, owner) | `server.bulk-job-store/JobStore` | Yes, when the host supplies a shared store |
| Stream slots (`:active-streams`, `:max-concurrent-streams`) | an atom on the bulk job store map | No, on purpose: the cap bounds one replica's memory |
| NDJSON bytes | nowhere; streamed from the FHIR store at download time | n/a |

The streaming design is unchanged: kickoff pins a basis and stores metadata
only, and a download streams `scan-type-as-of` at that basis straight to the
socket with TCP backpressure. Because the basis travels inside the job, any
replica streams the same snapshot.

## The protocol

```clojure
(defprotocol JobStore
  (-put-job!    [s tenant job])
  (-get-job     [s tenant id])
  (-update-job! [s tenant id f])   ; atomic; f may run more than once; nil removes
  (-remove-job! [s tenant id])     ; idempotent
  (-all-jobs    [s]))
```

- `MemoryJobStore` is the default of `create-store` and of the
  `:fhir/bulk-job-store` Integrant key. It is correct for one replica only.
- A host passes its own store as `(bjs/create-store config jobs)`, or as `:jobs`
  in the Integrant opts. Anything that does not satisfy the protocol throws at
  construction.
- The public fns `put-job!`, `get-job`, `update-job!`, `remove-job!` and
  `all-jobs` delegate to the protocol. The bulk handlers use nothing else:
  - kickoff puts;
  - status and file get;
  - cancel (`DELETE`) removes, so a later poll answers 404 on every replica;
  - the lazy TTL sweep at the head of each bulk request removes terminal jobs
    older than `:ttl-ms` through `-all-jobs` and `-remove-job!`.

## Serialization

A durable store keeps `(bjs/job->edn job)` and returns `(bjs/edn->job s)`.

- Everything in a job is plain data except the basis Instants. Every store's
  `current-basis` (mock, xtdb2, datomic) returns
  `{:tx-id <long-or-nil> :system-time <Instant>}`, so one basis codec serves
  all three. It writes each Instant as its ISO-8601 string and reads it back
  with `Instant/parse`.
- It does not use `#inst`, which reads back as `java.util.Date` at millisecond
  precision. xtdb2 reads `FOR SYSTEM_TIME AS OF` the basis `:system-time`, so a
  truncated basis would read a snapshot slightly before the pinned transaction
  and silently drop its writes from the export.
- `job->edn` throws `ex-info` when the job holds a value EDN cannot carry, the
  basis carries an unknown key, or a required key is missing. `edn->job`
  validates the record with a malli schema and throws on anything malformed;
  it never returns a partial job.
- A store whose basis carries more than `:tx-id`, `:system-time` and
  `:valid-time` must extend the codec, or encoding throws.
- One known limit: a POST kickoff's Parameters body is kept in `:params` as
  decoded. If a host's body decoder turns a `value[x]` into a non-EDN object,
  `job->edn` refuses the job (the kickoff fails loudly) rather than storing a
  lossy copy.

Verified round trips:

- mock: `server.bulk-job-store-test`.
- xtdb2: `test-server.bulk-job-basis-test`. It pins a basis, writes more,
  round-trips the basis, and asserts `count-as-of` and `scan-type-as-of` still
  read the pinned snapshot.
- datomic: the store lives outside this repository. The same check was run
  against `fhir-store-datomic` on an in-memory database (`count-as-of` with the
  restored basis = 1 while the live count = 3). `basis->as-of-db` resolves the
  `:tx-id` first, so precision does not matter there.

## A host without a job store

When `:bulk-job-store` is nil, every bulk route answers 501 with an
`OperationOutcome` (`not-supported`) instead of failing on the missing store.

## Durable store for flotilla (design, not built)

flotilla runs one replica (`Recreate`, for its Lucene index), so a durable
store is not urgent. But note: **flotilla passes no `:bulk-job-store` to
`resolve-options` today.** The CapabilityStatement it serves advertises
`$export`, yet every bulk request there fails: a NullPointerException before
this change, 501 after the dromon bump. Making Bulk Data work on flotilla needs
`:bulk-job-store (bjs/create-store ...)` in `fhir-middleware`'s options first,
with the in-memory store while it runs one replica.

The durable design, selected by flotilla config `bulk-job-store "accounts"`:

- An accounts-Datomic `JobStore`, following `live/ticket.clj` and
  `live/ticket-store`. Entity attributes:
  - `:flotilla.bulk-job/key`: string `"<tenant>/<id>"`, `:db.unique/identity`
  - `:flotilla.bulk-job/tenant`, `:flotilla.bulk-job/id`: strings
  - `:flotilla.bulk-job/status`: keyword
  - `:flotilla.bulk-job/created-at`, `:flotilla.bulk-job/finished-at`: instants
  - `:flotilla.bulk-job/version`: long, bumped by every update
  - `:flotilla.bulk-job/edn`: string, the `job->edn` blob
- The protocol maps onto it as follows:
  - `-put-job!` transacts the entity with version 0.
  - `-get-job` pulls it by key and runs `edn->job`.
  - `-update-job!` reads the entity, applies `f`, and transacts with
    `[:db/cas eid :flotilla.bulk-job/version v (inc v)]`. On a cas failure it
    retries, which is why `f` must be pure. A nil result retracts the entity.
  - `-remove-job!` runs `:db.fn/retractEntity` when the key resolves, else does
    nothing.
  - `-all-jobs` reads the key datoms (`d/datoms :aevt`) and decodes each.
  - The sweep could later query terminal jobs past the TTL instead, which is a
    protocol-compatible optimisation.
- **Blob size.** A job carries the compartment definitions (a few KB) and, for
  a Group export, every member Patient id. Datomic is a poor home for large
  strings, so before building it:
  - measure the largest real Group;
  - if blobs exceed a few KB, keep the blob in GCS (keyed by job, with the same
    TTL) and only the index attributes in Datomic.
- Stream slots stay per replica in either design.
