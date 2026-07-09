# Bulk Data Access ($export) and SMART Backend Services

Status: proposal / implementation plan
Scope: add FHIR Bulk Data Access (`$export`) plus SMART Backend Services
authorization to the dromon FHIR server so it can pass the Inferno
`bulk_data_test_kit`.

This is a planning document. No code has been written. File and line references
point at `origin/main` at the time of writing.

## Motivation

The Inferno harness in this repo runs US Core (`us_core_v610`), SMART App Launch
discovery, and a minimal `base_fhir_r4` smoke suite. The Bulk Data Access API is
not covered because dromon does not implement it: a scan of `origin/main` for
`$export`, `respond-async`, `ndjson`, `bulk-data`, or any job/async machinery
returns nothing, and the advertised CapabilityStatement declares no export
operations. Adding the Inferno `bulk_data_test_kit` is only meaningful once the
server implements export, so this document scopes that server work.

## What the test kit requires

The Inferno `bulk_data_test_kit` (repo `inferno-framework/bulk-data-test-kit`)
ships two server suites:

- `bulk_data_v101` - Bulk Data Access IG STU1 (v1.0.1)
- `bulk_data_v200` - Bulk Data Access IG STU2 (v2.0.0)

`bulk_data_v200` has two top-level groups:

1. `bulk_data_smart_backend_services_v200` - imports `smart_discovery_stu2` and
   `backend_services_authorization` from the SMART App Launch test kit, requests
   scope defaulting to `system/*.read`, and drives the RFC 7523 `private_key_jwt`
   client-credentials flow against the discovered `token_endpoint`.
2. `bulk_data_export_tests_v200` - the export tests.

The export group asserts (from `export_operation_tests.rb`,
`export_kick_off_performer.rb`, `bulk_export_validation_tester.rb`):

1. CapabilityStatement returns 200 and valid JSON, its `instantiates` contains
   the Bulk Data IG canonical, and the `$export` operation is declared with the
   correct OperationDefinition URL.
2. A kickoff without a token returns 400 or 401.
3. Kickoff `GET` with `Prefer: respond-async`, `Accept: application/fhir+json`,
   and a Bearer token returns 202 Accepted with a `Content-Location` header (the
   poll URL). Query params (`_type`, `_since`, `_outputFormat`, `_typeFilter`,
   group id) are URL-encoded onto the base.
4. Status polling with `Accept: application/json` honors `Retry-After` and, on
   completion, returns 200 with `Content-Type: application/json` and a manifest
   containing `transactionTime`, `request`, `requiresAccessToken`, `output`,
   `error`. In-progress polls return 202 with `X-Progress`/`Retry-After`.
5. The `output` array is non-empty and each entry has `type` and `url`.
6. Each output file downloads with `Accept: application/fhir+ndjson`, the
   response `Content-Type` contains `application/fhir+ndjson`, every line parses
   as a FHIR resource whose `resourceType` matches the `output.type`, resources
   validate against profiles, and Patient files contain at least two distinct
   Patient ids.
7. Cancel: kick off, then `DELETE` the poll URL (expects 202).

Canonical URLs the CapabilityStatement must carry:

- IG CapabilityStatement: `http://hl7.org/fhir/uv/bulkdata/CapabilityStatement/bulk-data`
- OperationDefinitions: system `.../OperationDefinition/export`, patient
  `.../patient-export`, group `.../group-export`.

## Gap analysis (grounded)

### Routing (`fhir-server/src/server/routing.clj`)

System-level, non-resource routes live in `build-system-routes` (lines
242-277); the `:public? true` route-data flag bypasses auth. New `/$export`,
status, and file routes attach here (they need `all-registries` and custom auth
data, so the system-routes block is a cleaner home than the operation machinery
in `build-operation-routes`, lines 179-204). `resolve-handler` (line 42) turns a
fully-qualified symbol into the handler fn.

### Handlers (`fhir-server/src/server/handlers.clj`)

The response convention is to return a map body with no explicit `Content-Type`
so the muuntaja response middleware serializes it; setting `Content-Type` on a
map body bypasses muuntaja and returns 500 (see `smart-configuration`, lines
941-954). Consequence: the status manifest must be `application/json` (not
`fhir+json`) and NDJSON must be `application/fhir+ndjson`, and neither is what
muuntaja would negotiate. So the manifest, NDJSON, and 202 responses must be
pre-serialized to strings with an explicit `Content-Type`, deliberately
bypassing muuntaja (mirroring the default-404 handler in `core.clj`).

`system-search` is the closest existing "enumerate every type" pattern, but it
reads only the first page (`_count 50 _skip 0`); export must page to completion.
`capability-statement` (lines 880-923) sets `instantiates` to only the US Core
CS and must be extended with the bulk-data canonical and the `$export`
operation entries.

### Store protocol (`fhir-store-protocol`, `fhir-store-xtdb2`)

`IFHIRStore` has `search`, `history-type`, and `count-resources` but no
streaming "scan all of a type". The MVP can page via `search`
(`_count`/`_skip`/`_offset`), which works uniformly across xtdb2, datomic, and
mock, but is O(pages). A new optional `scan-type` method is recommended for
full-conformance and large-tenant resiliency. Patient/Group compartment
membership is already solved in `fhir-server/src/server/compartment.clj`
(`confine`, `member?`, `compartment-definitions`).

### Auth stack

- `server.scope` already accepts `system/...` scopes; `wrap-smart-scope` bypasses
  `:public?` routes and allows requests whose parsed fhir-type is nil.
- `server.keto` parses the 4th URL segment as the object and returns 403 when
  there is no subject. Inferno's "rejects without authorization" wants 400/401,
  not 403 - a real gap. The runner already grants the `system` object
  read/write/search-type.
- `server.auth` validates Hydra RS256 tokens; `wrap-require-auth` returns 401
  when identity is absent (the tool for the 401 gap). The `private_key_jwt`
  RS384/ES384 client-assertion validation is Hydra's job, not dromon's.
- `.well-known/smart-configuration` already advertises `token_endpoint`,
  `client_credentials`, and `private_key_jwt`. Missing for backend services:
  `token_endpoint_auth_signing_alg_values_supported` (RS384/ES384),
  `system/*.read` in `scopes_supported`, and the `client-confidential-asymmetric`
  capability.

### Wiring

`test-server/src/test_server/core.clj` `build-config` (lines 121-156) assembles
the Integrant system; a new job-store component threads into `:server/jetty` ->
`fhir-app` like the existing `terminology` and store injections. The runner
(`bb/src/server/inferno_runner.clj`) is suite-configurable, so no runner code
change is needed to run the bulk suite; only a Gemfile line plus image rebuild
in `bb.edn` `inferno-setup`.

## Architecture decisions

### Async job model

Introduce an Integrant component `:fhir/bulk-job-store` - an in-memory atom of
`{[tenant-id job-id] -> job}` - wired in `build-config` and injected per request
by a new `wrap-bulk-job-store` middleware. In-memory is the right MVP choice:
jobs are ephemeral and per-node, and it sidesteps the xtdb2-vs-datomic
divergence a store-backed registry would introduce. Multitenancy is handled by
keying on `[tenant-id job-id]`; the background worker closes over `tenant-id`.

Job record:
`{:id :tenant :kind #{:system :patient :group} :group-id :params :status
#{:in-progress :complete :error :cancelled} :transaction-time :request-url
:output [{:type :url :count}] :error [] :files {file-id ndjson-string}}`.

- Kickoff (`GET .../$export`): validate `_outputFormat`; mint a job; store
  `:in-progress`; spawn a virtual thread (the server is already virtual-thread
  based) to enumerate and serialize NDJSON then flip to `:complete`; return 202
  with an absolute `Content-Location` status URL and no body.
- Status (`GET .../$export-status/:job-id`): `:in-progress` -> 202 +
  `X-Progress` + `Retry-After`; `:complete` -> 200, pre-serialized JSON string,
  `Content-Type: application/json`, manifest body; `:cancelled`/unknown -> 404;
  `:error` -> 500 with the error array.
- Cancel (`DELETE .../$export-status/:job-id`): flip to `:cancelled`, 202.
- File (`GET .../$export-file/:job-id/:file-id`): NDJSON string with
  `Content-Type: application/fhir+ndjson`. MVP: `requiresAccessToken:false` and
  a `:public?` file route. Full: `requiresAccessToken:true`, gated on system
  scope/Keto.

### Enumeration

MVP: page each type with `search` + increasing `_skip` until a short page.
System = `(keys all-registries)` filtered by `_type`; Patient = `confine` per
member type per patient; Group = resolve `Group.member.entity` Patient refs and
union their compartments. `_since`/`_typeFilter` deferred to full conformance.

Full: add an optional `scan-type` method to `IFHIRStore` returning a
reducible/streaming cursor (xtdb2 query cursor, datomic, mock), so NDJSON can
stream to temp files instead of in-memory strings. The write-basis convention in
the protocol supplies a monotonic tx-id to stamp as `transactionTime`.

### Authorization shape for the new routes

- 401, not 403, when unauthenticated: front the kickoff handler with
  `auth/wrap-require-auth` (or an in-handler identity check), because
  `wrap-keto-authorization` returns 403 for a missing subject and Inferno wants
  400/401.
- Keto object = `system`: add `$export`/`$export-status`/`$export-file` to the
  URL-parse exclusion set in `keto.clj` (alongside `metadata`/`_history`/
  `_search`) so these gate on the already-granted `system` object rather than a
  bogus `$export` object. Mirror the same exclusion in `scope.clj`.
- `enforce-smart-scopes?` is off by default in the inferno runs, so for the
  green-the-kit path only jwt-auth + keto apply; the backend-services token just
  needs to be a valid Hydra token with the `system` Keto tuple. Full
  `system/*.read` scope enforcement is a nicety, not required to pass.

## Requirement: streaming and bounded memory

The first-cut MVP retained every export's NDJSON as in-heap strings in the job
map (`:files {file-id ndjson-string}`) with no eviction, and the background
worker built those strings in memory. On a large tenant this is an unbounded
heap / OOM risk: a single system-level export materializes every resource of
every type as one concatenated string, and completed jobs are never released.
The bulk APIs must therefore stream and bound memory:

1. Stream to temp files, not the heap. The worker writes each output type's
   NDJSON to a temp file on disk through a buffered writer as it pages the
   store, tracking bytes and line count per file. The job record stores file
   metadata only - `:files {file-id {:path :type :count :bytes}}` - never the
   content. Temp files live under a configurable base dir (default
   `java.io.tmpdir`) namespaced per tenant/job (`<dir>/dromon-bulk/<tenant>/<job-id>/`).

2. Serve files by streaming from disk. The `$export-file` handler returns
   `{:status 200 :headers {"Content-Type" "application/fhir+ndjson"} :body
   (clojure.java.io/file path)}`. Ring streams `File`/`InputStream` bodies, and
   the explicit `Content-Type` keeps muuntaja from touching it (the same reason
   the manifest and 202 responses are pre-serialized with an explicit CT). The
   401-tokenless and system-authorization behavior is preserved.

3. Configurable cap plus TTL, enforced by aborting (never silently
   truncating). The `:fhir/bulk-job-store` Integrant component carries a config
   map with env overrides. Defaults: `max-concurrent-jobs=4`
   (`BULK_MAX_CONCURRENT_JOBS`), `max-job-bytes=1GB` (`BULK_MAX_JOB_BYTES`),
   `max-total-bytes=5GB` across all jobs on disk (`BULK_MAX_TOTAL_BYTES`),
   `ttl-ms=3600000` / 1h (`BULK_JOB_TTL_MS`), plus `temp-dir` (`BULK_TEMP_DIR`).
   Enforcement:
   - Kickoff when the in-progress job count is at or above `max-concurrent-jobs`
     returns 429 with an `OperationOutcome` and a `Retry-After` header.
   - The worker checks caps after each page; if a job exceeds `max-job-bytes`,
     or the total on-disk bytes across all jobs exceeds `max-total-bytes`, it
     aborts that job: sets `:status :error` with a clear `OperationOutcome`
     message and deletes the job's temp files.
   - TTL eviction: completed/errored/cancelled jobs older than `ttl-ms` are
     removed and their temp files deleted. A lazy sweep runs on each bulk
     request.
   - Cancel deletes the job's temp files (the whole per-job temp dir tree).

## Phased plan

### Phase A - SMART Backend Services auth (mostly Ory config)

1. Extend `.well-known/smart-configuration`: add
   `token_endpoint_auth_signing_alg_values_supported ["RS384" "ES384"]`, add
   `system/*.read`/`system/*.rs` to `scopes_supported`, add
   `client-confidential-asymmetric` to `capabilities`. Ensure `token_endpoint`
   (`OAUTH_BASE_URL`) is reachable from inside the Inferno container.
2. Ory Hydra config (not dromon code): register a client with
   `grant_types:["client_credentials"]`,
   `token_endpoint_auth_method:"private_key_jwt"`, and a JWKS holding the public
   half of the key Inferno signs with; confirm Hydra accepts RS384/ES384 client
   assertions.
3. Keto: grant the client the `system` read tuple (the runner already does this).
4. dromon token validation is unchanged.

### Phase B - `$export` MVP (system-level)

1. New ns `server.bulk-export`: kickoff/status/cancel/file handlers,
   `_outputFormat` validation, 401-on-missing-token, manifest builder, NDJSON
   string writer.
2. New Integrant component `:fhir/bulk-job-store` + `wrap-bulk-job-store`; wire
   in `build-config` and `fhir-app`.
3. New routes in `build-system-routes`: `/:tenant-id/fhir/$export`,
   `/.../$export-status/:job-id`, `/.../$export-file/:job-id/:file-id`.
4. keto/scope exclusion-set edits.
5. CapabilityStatement: bulk-data `instantiates` + system `$export` operation.
6. System enumeration via paged `search`; NDJSON strings in the job; manifest
   with all five keys; `requiresAccessToken:false` + `:public?` file route.
7. Seed a second Patient so Patient NDJSON files satisfy the two-distinct-ids
   check (the runner currently seeds only `Patient/123`).

### Phase C - Patient/Group level, params, full authz

- `/:tenant-id/fhir/Patient/$export` and `/:tenant-id/fhir/Group/:id/$export`
  via `compartment/confine` + Group member resolution.
- `_type`, `_since` (needs `_lastUpdated` search support, verify per backend),
  `_typeFilter`, `_outputFormat` variants; error-state manifests;
  `requiresAccessToken:true` + system-scope-gated file routes; Patient/Group
  operation entries in the CapabilityStatement.
- Verify cancel: `DELETE` -> 202 and a subsequent status poll 404s.

### Phase D - Harness wiring and baseline

- Add `gem "bulk_data_test_kit"` to the cloned kit's Gemfile and rebuild (no
  runner code change).
- Run `INFERNO_SUITE=bulk_data_v200`; capture a baseline; optionally repeat for
  `bulk_data_v101`.

## Touch list

New namespaces:

- `fhir-server/src/server/bulk_export.clj` - kickoff/status/cancel/file
  handlers, `_outputFormat` validation, manifest builder, 401 guard, NDJSON
  writer.
- `fhir-server/src/server/bulk_job_store.clj` - job-store atom + the
  `:fhir/bulk-job-store` init-key, plus an enumeration helper.
- Optional `fhir-server/src/server/bulk_ndjson.clj` - resource-to-NDJSON
  serialization, reusing the per-type encoders from
  `handlers/build-resource-encoders`.

Changed files:

- `fhir-server/src/server/routing.clj` - new routes in `build-system-routes`.
- `fhir-server/src/server/handlers.clj` - `capability-statement` (bulk
  `instantiates` + `$export` operation) and `smart-configuration` (signing algs,
  `system/*.read` scope, asymmetric capability).
- `fhir-server/src/server/keto.clj` and `fhir-server/src/server/scope.clj` -
  add the `$export` endpoints to the system-endpoint exclusion sets.
- `fhir-server/src/server/core.clj` - thread `:fhir/bulk-job-store` into
  `fhir-app` and add `wrap-bulk-job-store` to the middleware chain.
- `test-server/src/test_server/core.clj` - add `:fhir/bulk-job-store` to
  `build-config` and a second seed Patient.
- `fhir-store-protocol/src/fhir_store/protocol.clj` + `fhir-store-xtdb2` (+
  datomic/mock) - Phase C only: optional `scan-type` streaming method.
- `bb.edn` `inferno-setup` - add the bulk kit gem before the image build.

## SMART Backend Services: Ory vs dromon split

- dromon code: discovery fields only. Token issuance and `private_key_jwt`
  RS384/ES384 client-assertion validation are entirely Hydra. dromon validates
  the resulting RS256 access token (unchanged) and enforces the `system` Keto
  tuple.
- Ory config: a Hydra client with `token_endpoint_auth_method: private_key_jwt`
  plus a registered public JWKS; confirm RS384/ES384 acceptance; Keto `system`
  read tuple for the client subject.
- Inferno input mapping: for the bulk suite Inferno runs the real
  backend-services flow, so `INFERNO_INPUTS` should provide `bulk_server_url`
  and a `smart_auth_info` configured for backend services (token URL = Hydra's
  reachable `/oauth2/token`, client id, signing alg, JWK set). Bring-up shortcut:
  run `INFERNO_GROUPS=bulk_data_export_tests_v200` with a pre-baked token to
  iterate on `$export` before wiring the auth group.

## Suggested PR sequence

1. PR1 - Backend Services auth + discovery: `smart-configuration` fields, Hydra
   `private_key_jwt` client + JWKS, Keto `system` tuple. Verify
   `bulk_data_smart_backend_services_v200` passes standalone.
2. PR2 - `$export` MVP: `bulk-export` ns, `:fhir/bulk-job-store` component +
   middleware, system routes, keto/scope exclusions, 401 guard,
   CapabilityStatement additions, in-memory NDJSON, second seed Patient. Greens
   system-level export + status + download.
3. PR3 - Patient/Group + params + cancel + full authz.
4. PR4 - harness wiring + baseline report.

## Risks and open questions

- 403-vs-401 (confirmed gap): unauthenticated kickoff currently yields 403 via
  Keto; Inferno needs 400/401. Front the kickoff with `wrap-require-auth`. Small,
  high-value fix.
- Manifest/NDJSON content types must bypass muuntaja (pre-serialized strings +
  explicit `Content-Type`); a map body with an explicit `Content-Type` returns
  500.
- Hydra RS384/ES384 support: confirm Ory Hydra can validate ES384/RS384 client
  assertions and that discovery advertises only algs it truly accepts.
- Token endpoint reachability: discovery's `token_endpoint` must be routable
  from inside the Inferno container, unlike the current pre-baked-token path
  that never calls Hydra.
- Enumeration cost: paged `search` is O(pages) and bounded by the registry
  `_count` cap; fine for Inferno's tiny dataset but not production - hence the
  optional `scan-type` streaming method and temp-file output for full
  conformance.
- In-memory job store: ephemeral and per-node, acceptable for the kit and
  single-node dev; a store-backed registry would diverge across backends.
- Patient two-id rule: the NDJSON validator requires two distinct Patient ids;
  the current seed inserts one `Patient/123` - add a second.
- `_since` depends on `_lastUpdated` search support in each backend; verify
  before promising it.

## References

- Bulk Data test kit: https://github.com/inferno-framework/bulk-data-test-kit
- SMART App Launch test kit: https://github.com/inferno-framework/smart-app-launch-test-kit
- SMART Backend Services (SMART App Launch v2.2.0):
  https://build.fhir.org/ig/HL7/smart-app-launch/backend-services.html
- Bulk Data Access IG STU1 authorization:
  https://hl7.org/fhir/uv/bulkdata/STU1/authorization/index.html
