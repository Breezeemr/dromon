# Emit per-entry spans under `bundle/transaction`

## Problem

The `otel-telemere-instrumentation.md` design table promised a
`bundle/entry` span per entry inside a transaction Bundle, parented to
the outer `bundle/transaction` span. Validation against Jaeger and the
`bb trace` output confirms these spans are never emitted.

Observed span shape for `POST /default/fhir` with a 2-entry Bundle:

```
http/request
└── auth/jwt.verify
    └── authz/keto.check
        └── bundle/transaction
            └── store/transact-bundle
```

Expected:

```
http/request
└── auth/jwt.verify
    └── authz/keto.check
        └── bundle/transaction
            ├── bundle/entry (#0, POST Patient)
            ├── bundle/entry (#1, PUT Observation)
            └── store/transact-bundle
```

Without the per-entry spans, it is impossible to see which entry in a
large transaction is responsible for latency or a validation failure —
exactly the reason the design called for them.

## Root cause hypothesis

Either:

1. The `t/trace!` wrapper was only added at the outer `bundle/transaction`
   call site and never threaded through the per-entry processing loop
   (`fhir-server.routing` or the bundle handler), or
2. The per-entry work runs on a thread pool / future that does not
   inherit the current OTel `Context`, so the span is opened but never
   attached to the request trace.

The fix needs to identify where bundle entries are actually iterated
(`fhir-server.handlers` or `fhir-server.routing` bundle handler) and add
a `t/trace!` block with `:id :bundle/entry` and `:data {:index i
:method ... :resource-type ...}` around each iteration. If the work is
dispatched to another thread, wrap it in `with-otel-context` so the
span lineage is preserved.

## Testing

- Start the OTel stack (`DROMON_OTEL=1 DROMON_DEV_TRACE_TAP=1 bb setup`,
  test-server with the `:otel` alias).
- `POST /default/fhir` with a minimal 2-entry transaction Bundle via
  `bb trace`. Confirm the rendered tree contains two `bundle/entry`
  child spans, each with different `index` attributes.
- Cross-check in Jaeger: filter by operation `bundle/entry`, confirm
  hits correlate with the transaction trace id.
- `bb inferno-test` (OTel off) must still pass 505/505.

## References

- Source design: `otel-telemere-instrumentation.md` — see the
  instrumentation table, bundle processing row.
- Validation report: OTel HTTP validation pass that surfaced the gap.
- Likely code sites: `fhir-server/src/server/handlers.clj`,
  `fhir-server/src/server/routing.clj` — whichever iterates
  `Bundle.entry[*]` during transaction processing.
