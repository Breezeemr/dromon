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

A growing share of decisions against a clinical record are now made by
something that reads the record, acts on it, and cannot be asked afterward what
it was thinking. Two properties of Dromon carry most of the weight there.

The store never overwrites. XTDB v2 is the primary backend and Datomic is kept
as a benchmarked alternative; a write adds a version and nothing is destroyed.
Nobody has to switch history on, because there is no mode in which the database
discards it.

The server is also malleable. Its FHIR surface comes from conformance
resources rather than hand-written routing and validation code, so reshaping
what it serves and what it enforces is a data change with a test suite
attached.

Neither property was invented for machine learning. Both matter more now.

### A decision cannot be debugged against a database that moved on

A model's output is a function of its input. When that input was assembled from
database reads (the problem list, the last six months of encounters, the
eligibility on file) and the database has since moved on, the input is gone.
You still have the output and the prompt template, but not the data the
template was filled with.

That gap collapses two very different failures into one indistinguishable
event: the model reasoned badly over correct data, or it reasoned correctly
over data that was wrong at the time and has since been quietly fixed. The
first is worth retraining over. The second is a data pipeline incident. On a
forgetting database you cannot tell them apart, and teams end up arguing from
intuition about which one they are looking at.

With a system-time read, the input is addressable again. Re-read at the
transaction the request ran against and you get the exact context back, so the
disagreement becomes something a person can actually check.

### Point-in-time reads keep the future out of training data

Build a feature by querying current state and you have leaked the future into
the past. The "diagnosis at admission" extracted last night includes the
diagnosis that was added three weeks after discharge. The model learns from
information no deployed version of it will ever see at inference time, scores
well offline, and underperforms in production.

Look-ahead bias is difficult to catch because nothing fails. No query errors
and no test goes red; the numbers are just better than they should be. A store
that can reconstruct the record at an arbitrary instant makes honest feature
extraction the default, so it no longer rests on the discipline of whoever
wrote the query.

### Agents write, and they write with confidence

A read-only assistant is the easy case. An agent that updates records, closes
coverage, or files corrections is acting on the system of record, where a
mistake is a wrong row, not a wrong paragraph, with payments and care decisions
sitting downstream of it.

On a forgetting database the remedy for a bad agent run is another `UPDATE`,
which destroys the evidence of what the run did. On an immutable one the run is
still fully present: what the agent read, what it wrote, in what order, and
what the record looked like immediately before it touched anything. You can
reconstruct the whole run with a query instead of piecing it together from
whatever the logs happened to keep.

Immutability records that a row changed and when it changed. It does not on its
own record who changed it or why. FHIR answers that with the `Provenance`
resource, which Dromon treats as a first-class type: routed, validated, and
compartment-indexed by `agent`. Everything a given run touched is one search
away, with no grep through application logs.

A `transaction` Bundle carrying the changed resource and its `Provenance`
together commits atomically, so either both land or neither does. Attribution
recorded afterward, in a separate call, can fail on its own and leave a change
sitting in the record with nothing to account for it. Committing the two as one
transaction removes that failure mode, and the system time the store stamps on
that transaction dates the attribution without anyone supplying a clock. The
shape of that Bundle, and what the server does not do for you, are in
[Provenance and Attribution](docs/getting-started.md#provenance-and-attribution).

### A correction should not rewrite the past

When an agent retroactively fixes a record, the naive implementation makes the
system look as though it had been right all along. Two separate statements get
flattened into one. Valid time keeps them apart: the fact was true from Friday,
and we learned it from this run on Tuesday. The record still shows what it said
on Monday, so the decision made on Monday remains explicable, and the agent's
contribution stays attributable to the agent.

Without that separation, every correction erases exactly the evidence you would
need in order to judge whether the agent should be trusted with the next one.
An automated system that quietly improves its own history cannot be audited,
and an unauditable system has no business making decisions that carry a cost.

### The malleability of the server

Nothing about the server's FHIR surface is compiled in. Which resource types
exist, what they validate, what they can be searched by, and which operations
they expose all come from the conformance resources of whichever implementation
guide is on the classpath:

| FHIR artifact | What it determines |
|---|---|
| CapabilityStatement | Which resource types exist and which interactions each one supports. Routes are emitted from it, so a type that does not declare `read` has no read route. |
| StructureDefinition | The Malli schema for every profiled resource: the typed validator that decodes requests and checks responses on the way out. |
| SearchParameter | The search registry. Each parameter is combined with schema introspection to build SQL conditions, so no field name is hardcoded in the store layer. |
| OperationDefinition | The custom operations mounted on each type, and what the served CapabilityStatement then advertises. |

The CapabilityStatement is both input and output. It decides which routes exist,
and the statement served at `/{tenant-id}/fhir/metadata` is generated from the
same source, so the server's description of itself cannot drift away from its
behaviour. What each artifact drives is detailed in
[Conformance-Driven Configuration](docs/getting-started.md#conformance-driven-configuration).

Two choices happen at startup, not at build time. The schema package comes
from an alias (`:malli/r4b`, `:malli/uscore8`, `:malli/sdc` and others,
including a private Breeze IG that lives outside this repository), and the
store backend comes from another (`:store/xtdb2`, `:store/mock`,
`:store/datomic`). `fhir-server` carries a static dependency on neither, so the
same server code serves a different FHIR surface over a different database
depending on what you put on the classpath.

The seam is finer than whole packages. When a private guide ships a storage
registry, `server.core/resolve-schema` recompiles the resolved schema under it,
which is how the Breeze guide stores ordered multi-string fields
(`HumanName.given`, `Address.line`) as cardinality-one strings on Datomic. The
hook is classpath-detected and does nothing when the package is absent, so a
profile can adapt itself to a backend's storage model without forking the
server or leaking into the open-source build.

That malleability is what makes the server a reasonable target for automated
change. A server whose behaviour is spread by hand across route tables,
validation functions, and per-parameter search code has no single place where a
requirement lives, so a model editing it is really editing a dozen loosely
coupled guesses, and nothing catches the one it missed.

Here a specification change propagates mechanically. Point the generator at a
revised implementation guide, regenerate the schemas and validators, and run the
suite. Whatever breaks is the work: a profile that tightened a cardinality fails
validation, a search parameter that changed expression fails its contract test,
a resource type added to the CapabilityStatement shows up as a route with no
handler. None of that depends on anyone remembering what the revision touched.

That loop is short enough to run in CI, which is the right place to judge an
AI-assisted change. The model proposes a guide revision; the generator and the
test suite decide whether it holds. Compliance is checked the same way, since
`bb inferno-test` runs the Inferno US Core suite headlessly against a real
server and writes a report a build can gate on.

TypeScript type generation for profiled resources and operations is planned but
not yet implemented. The StructureDefinitions that produce the Malli validators
already carry what those declarations would need, which would give a client the
same profile-accurate types the server validates against.

### The questions are going to be asked

Healthcare and payment decisions have to be explainable after the fact, and
"the model said so" has never been a sufficient answer. The defensible answer
names the evidence: this is what the record said at the moment of the decision,
this is who or what wrote each part of it, and this is when we learned the part
we learned late. That answer requires a database that kept all of it.

## Two Kinds of Time

- **Valid time** is when a fact was true in the world.
- **System time** is when the system learned it.

A lab specimen drawn on Friday and corrected on Tuesday is valid from Friday
and known from Tuesday. A coverage termination backdated to the first of the
month was true from the first and recorded on the twentieth.

Keep one axis and you can reconstruct one of those. You will not find out which
one you needed until somebody asks why a claim was denied.

FHIR versions records, not facts. `_history` and `vread` give you system time,
and the specification has no valid-time axis at all. Dromon adds one as an
explicit selector a store must advertise support for, so an unqualified `GET`
still means current state.

## What It Costs

Storage grows with every version rather than every record.

Retroactive writes have to name the portion of the valid-time axis they apply
to. Valid-time DML without a portion clause applies from now on, so a
correction issued as a plain update is a silent prospective change, not the
retroactive one its author meant.

System time cannot be backfilled once a tenant is live, so historical imports
have to run oldest-first, before live traffic.

Dromon encodes each of these in the API surface so none of them survive as
folklore. A store advertises which axes it has, and a request naming an axis
the store lacks is a `400`, never a silently-current answer.

## How Dromon Exposes It

| Surface | Question it answers |
|---|---|
| `_asOf` | As the server knew it then (system time) |
| `_validAt` | As it was true in the world (valid time) |
| `$as-of` | One resource at a point in time |
| `$timeline` | Every version of one resource, with its temporal bounds |

Temporal responses state the basis they were computed at, since an omitted
`_asOf` resolves to a different instant on every request. A store declares
which axes it supports, and the server refuses a selector it cannot honour
instead of returning a plausible wrong number.

The wire format, the protocols behind it, and the rules the server enforces are
documented in [Getting Started](docs/getting-started.md#point-in-time-reads).

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
