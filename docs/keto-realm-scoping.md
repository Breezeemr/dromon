# Realm-scoping the `fhir` Keto namespace

## The defect

Every authorization tuple in the `fhir` Keto namespace is realm-blind. The
tuple `server.keto` checks is

    namespace "fhir", object "<Type>" | "<Type>/<id>" | "system",
    relation read|write|delete|..., subject_id "<kratos-id>"

The realm appears nowhere in it. A subject holding `fhir:Patient#read` can read
`Patient` in **every realm the deployment hosts**.

The only thing confining a request to a realm today is
`flotilla/.../fhir.clj`'s `acting-practitioner-middleware`, which requires a
`practitioner-id` tuple for the realm in the path. That is one tuple deep, it
lives in a different repository from the check it is standing in for, and
nothing declares that it is carrying the tenancy boundary. It is also bypassed
entirely by the `:public?` bulk-data routes, which call
`server.keto/system-read-allowed?` directly.

## The shape, and why the realm goes in the object

A Keto relation tuple has exactly three slots that could carry tenancy, and
only one of them is available:

- **namespace** -- configured statically. `dromon/docker/keto.yml` pins a fixed
  list (`User`, `Group`, `fhir`, `breezeehr-role`, `practitioner-id`) and
  deliberately does not use the OPL file, because the production namespace names
  carry hyphens and those are not valid OPL class names. A namespace per realm
  would make creating a clinic a Keto config deploy and restart. Rejected.
- **subject** -- wrong slot. Tenancy is a property of the resource, not of the
  actor, and one person legitimately holds grants in several realms
  (`resolve-first-party-claims` returns `:realms` plural).
- **object** -- an opaque string. This is the slot, and the estate already uses
  it this way in the other two first-party namespaces:
  `breezeehr-role#has-role` on `"<realm>/<role>"` and `practitioner-id#isa` on
  `"<realm>/<practitioner-uuid>"`.

So:

| request | object today | object after |
| --- | --- | --- |
| type-level | `Patient` | `<realm>/Patient` |
| instance-level | `Patient/123` | `<realm>/Patient/123` |
| system, realm in path | `system` | `<realm>/system` |
| system, no realm in path | `system` | `system` (unchanged, see below) |

This was verified against a real Keto v0.12.0 running the project's own
`dromon/docker/keto.yml`:

- A three-segment object writes (201) and checks true.
- `fhir:dev/Patient/123#read@kid` does **not** answer a check for
  `other/Patient/123` or for bare `Patient/123`. Realms isolate.
- A legacy `fhir:Patient#read@kid` tuple does **not** satisfy a
  `<realm>/Patient` check. This is why the reader needs an explicit
  either-shape window; it is not optional.
- `getRelationships` has no prefix or wildcard filter: `object=dev/` and
  `object=dev/*` both return `[]`. Only exact object matches. **All realm
  narrowing on a listing is client-side.**

A realm can never contain `/`, because it arrives as the reitit `:tenant-id`
path parameter and a path parameter cannot span segments. So `<realm>/Patient`
and `<realm>/Patient/<id>` are unambiguous.

### Realm-less routes keep the bare `system` object

`/auth/grants` and `/auth/token-hook` carry no `:tenant-id` segment, so there is
no realm to scope to. Those keep the bare `system` object. This is not the
reader failing to scope; it is an admin surface that is genuinely global.

**It is also a remaining cross-realm hole, and this work does not close it.**
`POST /auth/grants` can grant patients in any realm to any subject, gated only
on a global `system` write tuple. Closing it means adding a realm to the grant
API and to its callers (`bb/src/server/smart_grant.clj`, the e2e runners), which
is a separate change with its own callers to migrate. It is called out here so
it is not mistaken for finished work.

## What is idiomatic, and what this deliberately does not do yet

Putting the realm in the object is the idiomatic Keto answer to tenancy when
namespaces are static -- it is the only slot, and it is the convention already
in production here. But there is a second axis on which the current model is
**not** idiomatic, and it is worth writing down because the fix is blocked on
something specific.

`grant-tuples` writes direct per-subject tuples, fanned out:
`|patients| x (1 + |relations|) + |member-types| x |relations|` tuples **per
subject**. `flotilla/dev/README.md` provisions read+write for eighteen types by
hand and warns that a missing type "reads exactly like missing data". That
fan-out is the symptom.

Idiomatic Zanzibar/Keto would grant to a **subject set** instead:

    fhir:<realm>/Observation#read@(breezeehr-role:<realm>/provider#has-role)

One tuple per realm per type, and membership comes from the `breezeehr-role`
tuples that already exist. Adding a user to a realm would then be one tuple,
not forty.

This was verified to work on this deployment -- subject-set expansion resolves
with **no OPL rewrite rules configured**, which was the open question. But:

    check fhir:dev/Observation#read @ user:/kid-3  -> allowed: true
    check fhir:dev/Observation#read @ kid-3        -> allowed: FALSE

The userset resolves only for the `user:/`-prefixed subject spelling, because
that is how `breezeehr-role` membership tuples spell their subject. The `fhir`
namespace checks with the **bare** kratos id. So **the userset refactor is
blocked on reconciling the two subject spellings**, which is a larger migration
than this one and must not be smuggled into it. See "The subject spellings"
below.

Realm-scoping the object is a prerequisite for that refactor either way: the
userset's object has to name a realm.

## The subject spellings

The two tuple families use different subject spellings, and
`flotilla/dev/README.md:247-258` says so explicitly because it has already cost
a debugging session:

- The **realm view** the BFF resolves at login queries
  `subject_id = "user:/<kratos-id>"`. The prefix is applied inside
  `cookie-authentication/.../authentication.clj`'s `keto-relations`, not at the
  call site, so every caller of that helper gets it whether it wants it or not.
  `server.grant/breeze-claims-for` applies the same prefix by hand.
- **Data access** -- the `fhir` namespace, checked per request by
  `server.keto/check-permission` -- queries with the **bare** `<kratos-id>`.

Getting this wrong produces a login that succeeds and then behaves as though
the user has no access at all.

**Consequences for this work:**

1. Nothing in the migration may rewrite subjects. Only objects change.
2. The backfill has to infer a realm for each legacy tuple, and the only
   evidence available is the subject's `breezeehr-role` / `practitioner-id`
   tuples -- which are keyed on the **prefixed** spelling while the tuple being
   backfilled is keyed on the **bare** one. The script must bridge the two
   explicitly, per subject. A script that treats the families uniformly will
   attribute nothing and silently backfill zero tuples, or worse, corrupt one
   family. This is the single most dangerous line in the whole migration.

## Rollout

Four steps. They must not be collapsed: existing tuples are live production
authorization data, and a flag-day rename locks every user out at once.

### Step 1 and 2 -- one PR (dual-write, either-shape read)

- Writers emit the new realm-scoped shape **and** the legacy shape, so a
  rollback to the previous reader still finds grants it understands.
- The reader checks the realm-scoped object first and falls back to the legacy
  realm-blind object, so grants written before this PR keep working.

The fallback is behind config (`:keto/legacy-realm-blind-fallback?`, default
true) rather than hard-coded, so step 4 can be a config change that is
instantly reversible, before it is a code change. Turning it off is the real
test of whether the backfill is complete.

Cost to be aware of: until the backfill runs, the common case is a legacy
tuple, so an instance-level request makes up to four Keto calls instead of two.
That is transient and it is the safe direction -- once backfilled, the
realm-scoped check answers first and the legacy calls stop being made.

### Step 3 -- backfill script plus runbook

`bb/src/server/keto_realm_backfill.clj`. Requirements:

- **Dry-run by default.** It must report what it would change, and the counts,
  before it changes anything.
- It must page the whole `fhir` namespace. There is no prefix filter, so realm
  narrowing and shape detection are client-side.
- It must handle tuples carrying `subject_set` as well as `subject_id`. A
  listing returns either, and a script that assumes `subject_id` will drop or
  corrupt the userset tuples.
- Realm attribution per subject, from that subject's role and practitioner
  tuples, queried with the `user:/` prefix. One realm -> attribute there.
  Several realms -> write one new tuple per realm, because that is what the
  subject can reach today and the backfill must not narrow access silently.
  Zero realms -> **report, do not guess**: these are the machine subjects (the
  Inferno client, `smart-grant-admin`, dev tokens, bulk-export service
  accounts) and an operator has to say which realm each belongs to.
- It must be idempotent and re-runnable. **Keto's write is NOT an upsert** --
  see below -- so it has to do its own existence test rather than rely on the
  store to deduplicate.

The zero-realm report is also how the open cross-realm question gets answered
with data instead of a guess. No cross-realm carve-out is being designed:
flotilla's impersonation containment rule (`admin_routes/act-as-in-realm`)
already narrows even `superuser` to the realm in the path, and every role and
practitioner tuple is realm-scoped, so no cross-realm grant appears to be
intended for a human. The exposure is machine subjects, and the script names
them.

### Step 4 -- a later PR, and it must not merge until the backfill is confirmed

- Flip `:keto/legacy-realm-blind-fallback?` to false in config first. Watch for
  403s. This is the reversible half.
- Then remove the fallback code path, and stop writers dual-writing.
- Prerequisite: `login-consent` must have its realm configured (see below), or
  SMART launch grants stop resolving the moment the fallback goes.

## `login-consent` has no realm

`login-consent` writes the SMART launch tuple
(`linkage/launch-tuple`, whose own comment ties it to
`server.grant/grant-tuples`). It has **no realm concept anywhere** -- no realm
in its config, its routes, its Kratos traits or the Hydra consent request. It
cannot derive one, because the SMART surface it serves has no realm in its URLs
and therefore serves one realm per deployment.

So the realm arrives as deployment configuration: `FHIR_REALM`. When it is
unset the writer emits the legacy shape only and logs a warning, rather than
writing a corrupt `"/Patient/123"` object with an empty realm segment. That
keeps an unconfigured deployment behaving exactly as it does today, and makes
setting `FHIR_REALM` an explicit prerequisite for step 4 rather than a silent
breakage inside it.

## Every reader and writer

Found by sweeping the estate for `relation-tuples` call sites and for the
`"fhir"` namespace literal. `dromon` is a submodule with its own repository, so
its changes are a separate PR plus a submodule bump.

**Readers (dromon)**

| site | what it reads |
| --- | --- |
| `fhir-server/src/server/keto.clj` `wrap-keto-authorization` | the per-request check |
| `fhir-server/src/server/keto.clj` `system-read-allowed?` | the `:public?` bulk-data gate, bypassing the middleware |
| `fhir-server/src/server/grant.clj` `launch-authorized?` | the SMART launch tuple, from the Hydra token hook |
| `fhir-server/src/server/grant.clj` `granted-patients` | lists launch tuples and parses the object with `#"Patient/(.+)"` -- **this regex fails to match the new shape**, which would return an empty patient set and deny every patient-scoped token |

**Writers (dromon)**

| site | what it writes |
| --- | --- |
| `fhir-server/src/server/grant.clj` `grant-tuples` | the whole patient-set grant: `launch` per patient, each relation per patient, each relation per `patient-member-types` entry |
| `fhir-server/src/server/grant.clj` `revoke-patient-set!` | deletes by namespace/object/subject, so its object filter must follow |
| `bb/src/server/smart_grant.clj` | `system` read+write for the admin subject |
| `bb/src/server/inferno_runner.clj` | type-level read/write/search-type for 28 objects |
| `bb/src/server/compartment_e2e_runner.clj` | type-level grants for the compartment e2e |

**Writers (parent repo)**

| site | what it writes |
| --- | --- |
| `login-consent/src/com/breezeehr/login_consent/linkage.clj` `launch-tuple` | the SMART launch tuple. Its comment says "same shapes as server.grant/grant-tuples", so the two move together or SMART launch breaks |
| `flotilla/dev/README.md` | the documented dev provisioning loop, read+write for eighteen types. Not updating it means every dev environment provisions the old shape |

**Docs**

`dromon/docs/keto-authorization.md`, `dromon/docs/design/auth.md`,
`docs/flotilla-dromon2-removal.md`.

Not affected: the `str/split #"/" 2` object parsers in
`cookie-authentication/.../authentication.clj`, `flotilla/.../bff.clj` and
`flotilla/.../admin_routes.clj`. They read only the `breezeehr-role` and
`practitioner-id` namespaces, whose objects keep two segments.

## Keto's write is not an upsert

Discovered while testing the backfill, and it contradicts comments in
`server.grant/grant-patient-set!` and `linkage/ensure-launch-tuple!`, both of
which said "Idempotent: Keto tuple writes are upserts".

Three identical `PUT /admin/relation-tuples` of the same tuple produce **three
rows** (Keto v0.12.0; `PATCH ... insert` behaves identically). A
relationship's primary key is a generated id, not the tuple itself.

Why it matters, in order of how likely it is to bite:

- Checks still answer `allowed: true`, so the duplication is invisible from
  the authorization surface. Nothing looks wrong.
- `server.grant/list-tuples` takes ONE page of 500 and does not follow
  `next_page_token`. A subject whose tuples are duplicated often enough
  eventually has real grants pushed off the end of that page, and
  `granted-patients` silently stops seeing them -- which the token hook turns
  into a refusal to issue a patient-scoped token.
- The consent path writes a launch tuple on **every** consent accept
  (`login-consent handlers/accept-consent-and-link!`), so this accumulates per
  login, not per grant administration.

What this work does about it: the backfill script does its own existence test
and is verified idempotent across repeated `--apply` and `--apply --prune`
runs; `granted-patients` de-duplicates on the read side; and the two false
docstrings are corrected. Deduplicating the *write* paths, and paginating
`list-tuples`, are separate changes with their own blast radius and are not
folded in here.

## Runbook

Prerequisites: steps 1 and 2 deployed (dual-writing writers, either-shape
reader). Confirm with `KETO_LEGACY_REALM_BLIND_FALLBACK` unset or non-zero.

1. **Census.** From the dromon repo root, against the target environment's
   Keto:

   ```
   KETO_URL=... KETO_ADMIN_URL=... bb keto-realm-backfill
   ```

   Dry run. Reports four counts -- already realm-scoped, already backfilled,
   to backfill, unattributable -- and lists what it would write.

2. **Read the unattributable list.** It names every subject holding `fhir`
   tuples with no `breezeehr-role` or `practitioner-id` tuple to attribute a
   realm by. Expect machine accounts: the Inferno client, `smart-grant-admin`,
   dev tokens, bulk-export service accounts. **This list is the answer to
   whether anything in production relies on cross-realm access.** Decide a
   realm for each before continuing. A human appearing here means their realm
   view is missing, which is its own bug -- fix that rather than backfilling
   around it.

3. **Apply.** `bb keto-realm-backfill --apply`. Writes the realm-scoped tuples
   and KEEPS the legacy ones, because the reader's fallback is still what most
   requests are being answered by. Re-run the dry run; "to backfill" should be
   zero and "already backfilled" should equal what was just written.

4. **Resolve the exceptions.** For each subject from step 2, re-run scoped to
   the realm you decided on:

   ```
   bb keto-realm-backfill --realm <realm> --apply
   ```

   Note that `--realm` applies to every unattributable tuple in the pass, so
   run it per realm and check the dry run first.

5. **Verify against the real surface, not the counts.** Pick a subject per
   realm and confirm a FHIR read still succeeds, and that the same subject is
   refused in a realm it does not belong to.

6. **Turn the fallback off, reversibly.** Set
   `KETO_LEGACY_REALM_BLIND_FALLBACK=0` and restart. This is the moment the
   defect is actually closed. Watch for 403s carrying a `<realm>/...` object in
   the diagnostics: each one is a grant the backfill missed. Unset the variable
   to roll back instantly -- no deploy.

7. **Prune.** Once the fallback has been off long enough to trust, and only
   then: `bb keto-realm-backfill --apply --prune` removes the legacy tuples
   that have a realm-scoped replacement. It deliberately does NOT remove
   legacy tuples with no replacement -- an unattributable subject would
   otherwise lose all access.

8. **Step 4 PR.** Remove the fallback code path and the dual write. Do not
   merge it before step 7 is done in production.

Rollback at any point before step 7 is `KETO_LEGACY_REALM_BLIND_FALLBACK` unset
plus, if needed, reverting the step 1-2 deploy: the dual write means every
grant made in the meantime exists in the legacy shape too, so the previous
reader loses nothing.
