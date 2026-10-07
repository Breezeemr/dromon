# fhir-store-http

An `IFHIRStore` whose storage is another FHIR server, reached over its REST
API: a GCP Healthcare FHIR store, a HAPI server, or another dromon. It lets a
host put dromon's routing, authorization and lifecycle hooks in front of data
that lives elsewhere.

```clojure
(require '[fhir-store.http.core :as http])

(def store
  (http/create-http-store
   {:base-url      "https://healthcare.googleapis.com/v1/projects/p/locations/l/datasets/d/fhirStores/{tenant}/fhir"
    :authorization (fn [] (str "Bearer " (fetch-access-token)))   ; called per request
    :offset-param  nil                                            ; GCP has none: page by next links
    :resource/schemas schemas}))                                  ; encode bodies per type
```

Integrant key: `:fhir-store/http` (require `fhir-store.http.sys`).

## Options

| key | meaning |
|---|---|
| `:base-url` | string template containing `{tenant}`, or `(fn [tenant-id] url)`. Required. A fixed URL for every tenant must be written as a function that ignores its argument. |
| `:authorization` | nil, an `Authorization` header value, or a no-argument fn returning one per request. |
| `:offset-param` | the remote's offset search parameter (`_skip` for dromon, `_offset` for HAPI), or nil to emulate `_skip` by walking `next` links. |
| `:resource/schemas` | compiled malli schemas; bodies are encoded through `fhir-json-transformer` per resource type. |
| `:resource/lifecycle` | as for every store; see below. |
| `:http/request` | replaces the transport: `(fn [{:keys [method url headers body]}] {:status :headers :body})`. |
| `:connect-timeout-ms` / `:request-timeout-ms` | for the default `java.net.http` client (10 s / 60 s). |

## Verb mapping

| protocol | REST |
|---|---|
| `read-resource` / `vread-resource` | `GET Type/id`, `GET Type/id/_history/vid`; 404 and 410 read as nil |
| `create-resource` | `PUT Type/<id>` with `If-None-Match: *`; a missing id is minted as a UUID first |
| `update-resource` / `delete-resource` | `PUT` / `DELETE` with `If-Match: W/"vid"` from `:if-match` |
| `search` / `count-resources` | `GET Type?...` with `Prefer: handling=strict`; count via `_summary=count` |
| `history` / `history-type` | `GET .../_history`, following every `next` page |
| `transact-transaction` | `POST` a `transaction` Bundle |
| `transact-bundle` | each entry through the single-write verbs |
| `resource-deleted?` | 410 on read, or a 404 with history |
| `warmup-tenant`, `create-tenant {:if-exists :ignore}` | `GET metadata` |
| `current-basis`, `scan-type-as-of`, `count-as-of`, `delete-tenant`, other `create-tenant` | 501 |

## Things that differ from a local store

- **Creates.** The lifecycle contract needs the id final before `prepare`, so
  every create is a guarded `PUT` under a known id. A caller-supplied id is read
  first and refused with 409 when it exists. `If-None-Match: *` closes the race
  only on a remote that honours it on PUT; dromon's fhir-server does not. A
  remote that answers 200 rather than 201 to a create has updated an existing
  resource, and the store reports that as a 500.
- **Search never widens.** `Prefer: handling=strict` makes the remote refuse an
  unknown parameter. The synthetic `_compartment` push-down cannot be expressed
  in REST, so it is refused with 501. Parameters the fhir-server applies itself
  (`_include`, `_revinclude`, `_elements`, `_summary`, `_total`, ...) are not
  forwarded.
- **Paging without an offset** fetches every page before the one asked for.
- **Links.** A `next` link outside the tenant's base URL is refused (502), since
  the `Authorization` header would follow it.
- **No `:tx-metadata`.** The stamp is accepted and dropped; the store does not
  implement `ITxMetadataStore`. A `Provenance` entry in the same transaction
  Bundle could carry it later.
- **No `:fhir-store/basis`** on write results: REST exposes no transaction id.
- **Errors** carry `:fhir/status`, `:fhir/code`, and the remote OperationOutcome
  issues' `severity`/`code`/`expression` under `:fhir-store.http/remote-issues`.
  Remote `diagnostics` are never copied, because a remote may quote the
  submitted value and ex-data is logged.

## Lifecycle

`fhir-store.lifecycle` runs as on the other stores. `tx-ops` are FHIR Bundle
entries: a write that gains ops is sent as one `transaction` Bundle with its own
entry first, so the remote commits them together. `prepare` runs before the
remote checks `If-Match`, so it can run for a write the remote then refuses;
the contract already forbids `prepare` effects that outlive a refused write.
`:db` and `:entity` on the write map are nil. `after-commit` fires only after a
2xx.

## Tests

```bash
cd fhir-store-http && clojure -M:test
```

The unit tests run against `fhir-store.http.fake-remote`, a REST server built
on the mock store that behaves like a GCP store (page tokens, no offset, 410 on
deleted reads, strict handling). `remote-it-test` runs against a real server
when `FHIR_STORE_HTTP_IT_BASE` is set; see its namespace docstring.
