# Dromon

<div align="center">
  <img src="docs/dromon_ship.png" alt="A majestic Byzantine dromon warship" width="600" />
</div>

A Clojure-based multitenant FHIR R4B server built on storage that never
overwrites, with Malli validators generated from FHIR StructureDefinitions and
XTDB v2 underneath.

**[Getting Started](docs/getting-started.md)** covers prerequisites, running the
server, architecture, schema generation, and the test and compliance tasks.

## A FHIR Server Well Adapted to AI

A growing share of decisions against a clinical record are made by something
that reads the record, acts on it, and cannot be asked afterward what it was
thinking. Dromon is built for that setting on three properties: it never
forgets, it keeps provenance, and it is malleable.

### It never forgets

The store never overwrites. XTDB v2 is the primary backend and Datomic a
benchmarked alternative; a write adds a version and nothing is destroyed. Every
version carries two timestamps.

- **Valid time** is when the fact was true in the world.
- **System time** is when the system learned it.

A lab specimen drawn on Friday and corrected on Tuesday is valid from Friday
and known from Tuesday. A coverage termination backdated to the first of the
month was true from the first and recorded on the twentieth. Keep one axis and
you can reconstruct one of those. You will not find out which one you needed
until somebody asks why a claim was denied.

FHIR versions records, not facts: `_history` gives you system time, and the
specification has no valid-time axis at all. Dromon adds one as a selector a
store must advertise, so a plain `GET` still means current state.

| Surface | Question it answers |
|---|---|
| `_asOf` | As the server knew it then |
| `_validAt` | As it was true in the world |
| `$as-of`, `$timeline` | One resource at an instant, or every version with its bounds |

This is what makes a model's decision debuggable. When its input was database
reads and the database has since moved on, you have the prompt template but not
the data it was filled with, and a model that reasoned badly over correct data
looks identical to one that reasoned correctly over data since quietly fixed.
Re-read at the transaction the request ran against and the disagreement becomes
something a person can check.

The same read keeps the future out of training data. A "diagnosis at
admission" extracted from current state includes the diagnosis added three
weeks after discharge. Nothing errors; the numbers are just better than they
should be.

And a correction stops rewriting the past. On the twentieth the payer reports
that the coverage above ended on the first. On a forgetting database you set an
end date and the system looks as though it had always known. Here the fix is a
retroactive close, a store verb that names the portion of valid time it covers:

```clojure
(db/close-valid-time store "default" :Coverage "cov-1"
                     (Instant/parse "2026-03-01T00:00:00Z"))
```

The same instant now answers differently depending on which question you ask.

```
GET /default/fhir/Coverage/cov-1/$as-of?_asOf=2026-03-10T00:00:00Z&_validAt=2026-03-10T00:00:00Z
  -> 200, active: what was true on the tenth, as we knew it on the tenth

GET /default/fhir/Coverage/cov-1/$as-of?_validAt=2026-03-10T00:00:00Z
  -> 404: what was true on the tenth, as we know it now
```

The claim adjudicated on the tenth stays explicable, and the correction stays
dated to the twentieth. Valid-time writes are store verbs today, not HTTP; the
reads are. Mechanics are in
[Getting Started](docs/getting-started.md#point-in-time-reads).

### It keeps provenance

Immutability records that a row changed and when. It does not record who or
why. FHIR's answer is the `Provenance` resource, and Dromon treats it as a
first-class type: routed, validated, and compartment-indexed by `agent`.
Everything a given run touched is one search away.

Write it in the same transaction as the change. A `transaction` Bundle carrying
the resource and its `Provenance` commits atomically, so either both land or
neither does. Attribution written afterward can fail on its own and leave a
change with nothing to account for it. Underneath, XTDB stamps system time on
every commit and accepts a `:metadata` map per transaction for request or
agent-run ids, a seam `fhir-store-xtdb2` does not use yet. See
[Provenance and Attribution](docs/getting-started.md#provenance-and-attribution).

An agent that updates records or closes coverage is acting on the system of
record, where a mistake is a wrong row with payments downstream of it. On a
forgetting database the remedy is another `UPDATE`, which destroys the
evidence. Here the run is fully present: what it read, what it wrote, and what
the record looked like before it touched anything. An automated system that
quietly improves its own history cannot be audited, and an unauditable system
has no business making decisions that carry a cost.

### It is malleable

Nothing about the server's FHIR surface is compiled in. It comes from the
conformance resources of whichever implementation guide is on the classpath.

| Artifact | Determines |
|---|---|
| CapabilityStatement | Which types exist and which interactions each supports. A type that does not declare `read` has no read route. |
| StructureDefinition | The Malli validator for each profiled resource. |
| SearchParameter | The search registry, combined with schema introspection so no field name is hardcoded in the store. |
| OperationDefinition | The operations mounted per type, and what `/metadata` advertises. |

The served CapabilityStatement is generated from the same source as the routes,
so the server's description of itself cannot drift from its behaviour. The
schema package (`:malli/uscore8`, `:malli/r4b`, a private Breeze IG outside
this repository) and the store backend (`:store/xtdb2`, `:store/datomic`,
`:store/mock`) are both aliases chosen at startup, and `fhir-server` depends
statically on neither. A private guide can even ship a storage registry that
`server.core/resolve-schema` recompiles schemas under, adapting a profile to a
backend's storage model without forking the server.

A server whose behaviour is spread by hand across route tables and validators
has no single place where a requirement lives, so a model editing it is editing
a dozen loosely coupled guesses. Here a schema upgrade is mechanical.
The generator pins the guide (`download-and-extract-uscore! "STU8.0.1"`) and
emits namespaces that carry the version (`us-core.capability.v8-0-1.Patient`),
which the server's spec vector names. To take the next US Core release you
change the pin, regenerate, repoint the spec vector, and run the suite.
`validator-compile-test` requires those namespaces by name and fails to load
until it is repointed; a search parameter the new guide dropped fails
`search-param-contract-test`, which insists every declared parameter is
honoured or reported; a newly declared type shows up as a route with no
handler. That loop runs in CI, where an AI-assisted change should be judged,
and `bb inferno-test` gates US Core compliance the same way. TypeScript types for profiled resources
and operations are planned, not shipped. Details in
[Conformance-Driven Configuration](docs/getting-started.md#conformance-driven-configuration).

### What it costs

Storage grows with every version, not every record. Retroactive writes must
name the valid-time portion they cover, because DML without one applies from
now on and a plain update becomes a silent prospective change. System time
cannot be backfilled once a tenant is live, so historical imports run
oldest-first. Dromon encodes each of these in the API: a request naming an axis
its store lacks is a `400`, never a silently-current answer.

## Project Structure

```
dromon/
  bb.edn                        Babashka task runner
  docker/                       Ory Kratos/Keto/Hydra configs
  fhir-store-protocol/          IFHIRStore protocol definition
  fhir-store-xtdb2/             XTDB v2 backend (SQL, temporal queries)
  fhir-store-mock/              In-memory backend for testing
  fhir-server/                  Core server (routing, handlers, auth, middleware)
  fhir-terminology/             FHIR terminology service support
  fhir-primitives/              FHIR primitive types & lazy refs for Malli
  fhir-defintions-to-malli/     FHIR StructureDefinition -> Malli schema generator
  test-server/                  Runnable server, configurable store + schema package
```

The module name `fhir-defintions-to-malli` carries a typo in "defintions". It is
spelled that way on disk, so use that spelling in paths and references. How the
modules depend on each other is in
[Getting Started](docs/getting-started.md#dependency-graph).

## Documentation

- [Getting Started](docs/getting-started.md): setup, running, architecture, tasks
- [API, routing, and validation](docs/design/api.md)
- [Server architecture](docs/design/server.md)
- [Storage backends](docs/design/backends.md)
- [Authentication](docs/design/auth.md)
- [Multitenancy](docs/design/multitenancy.md)

## The Name

The **Dromon** (from the Greek *dromōn*, "runner") was the primary warship of
the Byzantine navy, known for its speed and maneuverability. Equipped with a
bronze-tipped siphon at the prow, it discharged **Greek Fire**, an incendiary
weapon that burned even on water.

## License

This project is licensed under the [Mozilla Public License 2.0](LICENSE).
