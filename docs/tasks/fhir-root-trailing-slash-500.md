# `POST /default/fhir/` (trailing slash) returns HTTP 500

## Problem

Posting a transaction Bundle to `POST /default/fhir/` (trailing slash)
returns a 500 with:

```
java.lang.IllegalArgumentException: No implementation of method:
:write-body-to-stream of protocol: #'ring.core.protocols/StreamableResponseBody
found for class: clojure.lang.PersistentArrayMap
```

The same Bundle posted to `POST /default/fhir` (no trailing slash)
returns 200 and transacts correctly. Unrelated to the OTel work — the
trailing-slash variant is dispatching to a handler that returns a raw
Clojure map where Ring expects something that satisfies
`StreamableResponseBody`.

## Hypothesis

Reitit's trailing-slash handling is likely matching the trailing-slash
form to a different (default / fallback) route than the transaction
endpoint. That fallback route probably returns a plain map and relies
on muuntaja middleware to serialize it, but the trailing-slash path
bypasses the muuntaja format middleware — or the content negotiation
fails and the handler result flows directly to Jetty.

Two things to check:

1. `fhir-server.routing/build-fhir-routes` — is `/` (root) wired as a
   separate route, or is `/default/fhir/` implicitly matched by
   reitit's `:conflicting` / trailing-slash strategy to the wrong
   handler?
2. The middleware chain for whichever route the trailing-slash variant
   resolves to — is `wrap-muuntaja` applied? If so, why does it not
   serialize the map?

## Approach

- Reproduce against a test-server instance with `DROMON_OTEL=0`.
- Turn on reitit route-matching debug (`reitit.pretty`) to dump which
  route + handler the trailing-slash URI resolves to.
- If it is matching a stray `catch-all` / metadata-compatibility route,
  normalize both forms to the same transaction handler. Reitit has
  `reitit.ring.middleware.parameters` and route-data options for
  trailing-slash redirect — pick whichever matches the existing style.
- Add a unit test in `fhir-server/test/...` that asserts both
  `POST /default/fhir` and `POST /default/fhir/` return 200 for the
  same transaction Bundle.

## Testing

- New test case covers both forms.
- `bb inferno-test` must still pass 505/505 (Inferno uses the
  no-trailing-slash form today, so this is purely additive).
- Manual curl with a 2-entry Bundle to both URIs, same response.

## References

- Reitit trailing-slash handling:
  https://cljdoc.org/d/metosin/reitit/CURRENT/doc/advanced/different-default-for-routes
- `fhir-server/src/server/routing.clj` — route table
- Validation report that first surfaced the 500.
