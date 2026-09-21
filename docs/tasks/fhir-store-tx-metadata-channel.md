# FHIR Store Transaction Metadata Channel (`:tx-metadata`)

## Problem

Provenance for a FHIR write exists today only in a LOG, never beside the
data. The host (flotilla) computes who made a request and emits it through
telemere from a middleware in dromon's chain. Three things follow from that:

1. **A write and its attribution can be separated.** The record lives in a
   different system from the row it describes, with no key joining them
   beyond a version id that has to be parsed back out of a response.
   Retention, sampling, and a log sink outage each silently unmake the link.

2. **Writes that do not pass through dromon's chain are recorded nowhere.**
   The host's furl routes call `update-resource`, `delete-resource` and
   `transact-transaction` on the raw store under their own auth interceptor;
   no middleware of dromon's ever runs. The same is true of every in-process
   caller holding a store handle: back-office operations that write several
   resources under one HTTP request, and the EDI eligibility machine, which
   writes under a service credential with no human anywhere in the picture.
   Only the store can stamp those, because only the store is in the path.

3. **For machine traffic the interesting agent is the SOFTWARE, and no
   channel carries it.** How a 271 was read depends on the version of the
   mapping code that read it. Nothing emits a FHIR `Device` today, and the
   build's commit sha reaches no running process, so there is nowhere to put
   a version even if one were known.

### What this does NOT fix

The host's own TODO claims a store seam closes "the furl and narrative
hole". Half of that is wrong and it is worth stating plainly so nobody
retires the wrong ticket:

- **furl: yes.** furl writes go through `IFHIRStore` verbs, so the seam
  reaches them.
- **narrative: no.** The narrative server's Composition compaction transacts
  against a Datomic connection DIRECTLY, not through `IFHIRStore`. No
  protocol change here can reach it. It must stamp its own transaction (the
  host's existing transaction-entity shape is directly reusable) or route the
  write through the store. Separate work, tracked separately.

## The seam

`:tx-metadata` rides the existing `opts` map on every `IFHIRStore` write
verb. The three write verbs that had no opts arity gain one:

```
create-resource       [this tenant-id resource-type id resource]        ; unchanged
                      [this tenant-id resource-type id resource opts]   ; NEW
update-resource       both arities unchanged; opts gains :tx-metadata
delete-resource       both arities unchanged; opts gains :tx-metadata
transact-transaction  [this tenant-id entries]                          ; unchanged
                      [this tenant-id entries opts]                     ; NEW
transact-bundle       [this tenant-id entries]                          ; unchanged
                      [this tenant-id entries opts]                     ; NEW
```

Adoption is made DISCOVERABLE by a separate capability protocol, exactly the
way `ITemporalReadStore` and `ITextSearchStore` are kept off `IFHIRStore`:

```clojure
(defprotocol ITxMetadataStore
  (tx-metadata-supported? [this])
  (tx-metadata-of         [this tenant-id resource-type id vid]))
```

plus `supports-tx-metadata?`, the two-part check a caller makes before
deciding to pass a stamp, and `check-tx-metadata`, the shared validation rule
every adopting store runs — the analogue of `normalize-if-match`.

### Why a capability protocol and not just the opts key

Every store ACCEPTS `:tx-metadata` the moment the arity exists, including one
that drops it. `satisfies? IFHIRStore` is therefore true of a store that
keeps nothing, and provenance would go missing precisely where it is
load-bearing: an unattributed clinical write is byte-identical to an
attributed one. This is the same failure the bitemporal and `_text` splits
exist to prevent, and the answer is the same — implementing the protocol is
the promise, and a store that cannot keep the promise must not implement it.

`tx-metadata-supported?` is separate from `satisfies?` because a DECORATOR
cannot answer statically: it implements the protocol in its own source, but
whether the stamp survives depends on the base it was handed at runtime.

### Contract rules

1. The plain arity IS the opts arity with nil.
2. A store that implements `ITxMetadataStore` MUST persist the stamp in the
   SAME transaction as the data — a stamp must never be able to name a write
   that did not commit.
3. A store that does not persist it MUST NOT implement `ITxMetadataStore`,
   and MAY still take the opts arities to honour `:if-match`.
4. A decorator forwards the opts arity **iff** opts is non-nil:
   `(if opts (verb base ... opts) (verb base ...))`. Unconditional forwarding
   turns every plain write through the decorator into an
   `AbstractMethodError` against a base that predates the arity.
5. A transaction Bundle carries ONE stamp for the whole atomic transaction;
   a batch Bundle stamps each entry's own transaction with the same map.
6. The stamp may contain usernames and practitioner ids and MUST NOT appear
   in a trace span's `:data` — key presence only, per the `fhir-store.trace`
   rule.

## Payload

An OPEN map with two documented top-level keys, both optional, both maps.
Principal and device are orthogonal axes: who authorized the write, and what
code performed it.

```clojure
{:principal {:credential   "session" | "token" | "anonymous"
             :altId        <the real human, or a service's client_id>
             :userId       <acting practitioner uuid, bare string>
             :actAsAltId   <impersonated username>            ; session only
             :actAsUserId  <impersonated practitioner uuid>   ; session only
             :subject      <Kratos id>
             :actAsSubject <Kratos id of the impersonated>}
 :device    {:name      "purser"                  ; required inside :device
             :versions  {"purser" "<git sha>" "rcm-x12" "<git sha>"}
             :reference "Device/<id>"}}           ; optional
```

**`:principal` is the host's `attribution-identity` value verbatim**,
camelCase included. Those keys are the tails of the four
`SecurityEvent.participant` attributes the host already stamps on a Datomic
transaction entity, so the Datomic backend's stamp is a 1:1 attribute mapping
and the audit trail joins the in-store stamp with zero translation.
`:credential` is kept even though it is not one of the four: without it, an
absent act-as pair reads as "a session that was not impersonating" when it
was in fact a token, which cannot impersonate at all.

A service principal that went through the token hook has `:credential`
`"token"` and `:altId` = the OAuth client id. `:userId` is nil today, because
the hook writes a responsible practitioner only for an end-user subject. The
channel does not need to change when the hook starts naming one for services.

**`:device` is DATA, not a FHIR Reference.** Nothing mints `Device`
resources anywhere yet; committing the channel to `Device/<id>` would oblige
every producer to register a Device before it could stamp anything. The map
was chosen to render losslessly into both eventual targets: FHIR
`Device.deviceName` + `Device.version[]{type,value}`, and
`Provenance.agent.who`. `:versions` is a MAP rather than one sha because the
shas are plural by design — the service's own, plus whatever dependency
changes how the input was interpreted.

### Deliberately not carried

- **Request and outcome context** (`:route`, `:method`, `:query`, `:status`,
  `:exception`). A stamp exists only for a COMMITTED write, so an outcome
  field beside the data is meaningless. Refusals, precondition failures and
  thrown writes stay the audit log's job; the two surfaces are complementary
  and neither retires the other.
- **The resource type, id and version.** They ARE the row the stamp is beside.
- **Authorization evidence** (Keto decisions, OAuth scopes). Authorship and
  authorization are different questions.
- **Anything dromon would have to compute.** dromon threads the map opaquely
  and never reads `:principal` or `:device`; the host builds it. Same
  division the narrative seam draws between WHAT and WHEN.

Undocumented keys are permitted. A supporting store MUST persist the
documented keys it can and MAY persist or ignore the rest.

## Backend homes

| backend | where the stamp lives |
|---|---|
| fhir-store-datomic | the transaction entity every write already names — it is also the value of `:fhir/version-id`, so version-id -> tx entity -> stamp is one lookup |
| fhir-store-xtdb2 | a per-tenant `fhir_tx_metadata` row written as an extra op inside the SAME `xt/execute-tx` as the data, keyed by the transaction's system time |
| fhir-store-mock | the per-version history record |
| decorators | delegation to base (CompartmentFilteringStore here, IndexedStore in the host repo) |
| as-of stores, test stubs | nothing — they must not implement `ITxMetadataStore` |

## Adoption order

The protocol lives in this repository; its implementations live in the host
repository, which advances its submodule pointer to a dromon commit only
later. The two CANNOT change atomically, so every step below is additive and
the pointer advance is its own step.

1. **(this step, dromon)** The protocol seam, its documentation, and a test
   proving an implementation that knows nothing about `:tx-metadata` still
   works. No implementation adopts anything; no caller passes anything;
   no behaviour changes.
2. **(dromon)** Mock store adopts; CompartmentFilteringStore forwards and
   delegates; a `wrap-tx-metadata` injects a host-supplied
   `(fn [request] map-or-nil)` mirroring the narrative seam, and the write
   handlers thread it. Standalone dromon, with no injected fn, behaves
   identically to today.
3. **(dromon)** XTDB v2 persists the stamp; `IValidTimeStore` gains its opts
   arities there, where the code honouring them is reviewed alongside.
4. **(host)** Advance the submodule pointer. Pure pointer move — this is the
   step that proves the compatibility claim against production code rather
   than in a REPL.
5. **(host)** Datomic backend stamps the transaction entity; the host injects
   the fn, stamps the furl path, and carries the Device and its version shas
   on machine writes.

`IValidTimeStore` is deliberately untouched in step 1: `put-valid-time` would
need a 7-arity and `close-valid-time`'s sixth slot is already a positional
`valid-to`, so its opts form is an awkward 7-arity. Only one store implements
either, so those arities belong in that store's own adoption change. The
host's retroactive corrections do go through those verbs, so they are not
optional — just later.

## Open policy question, for step 2

When a caller supplies a stamp and the store answers `tx-metadata-supported?`
false, the handler either:

- **(recommended) fails open**, emitting a monitored
  `:fhir/tx-metadata-dropped` event and proceeding. The data is not damaged
  by a missing stamp, and the event is the same shape operators are already
  asked to monitor for a failed narrative.
- **refuses the write.** Strictly correct, but it means an outage of the
  metadata table blocks clinical writes.

Fail-open means a misconfigured deployment quietly produces unstamped writes
unless the event is actually alerted on. A strict mode can be a later flag.
Neither answer blocks step 1.
