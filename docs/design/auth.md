# Authentication and Authorization Design

## Status
Runtime auth is **JWT verification (RS256/JWKS by default, HS256 for dev) + Ory Keto
authorization**, with **Ory Hydra** providing OAuth2 tokens and a **SMART configuration
discovery** endpoint. The **`client_credentials`** (machine-to-machine) flow works end to end
(exercised by the Inferno suite) on the main path (`bb setup`, only ory-pg/keto/hydra). The
interactive **`authorization_code`** flow is served by an opt-in **secondary auth stack**
(`bb auth-stack-up`, see `docs/tasks/kratos-reintroduce-secondary-auth-path.md`): Ory Kratos
v26.2.0, production's version, backs credentials/identity (recreated after its removal over the
cipher-secret config bug, `docs/tasks/kratos-cipher-secret-config.md` — secrets now arrive via
the SECRETS_* env-var mapping, never `$VAR` literals in YAML), and Hydra's login/consent
challenges redirect to the login/consent app of the consuming host (default
`https://localhost:3001`). Kratos failing to start can never affect `bb inferno-test`.

`bb auth-stack-up` runs `kratos migrate sql up -e --yes` before every `serve`. An existing
`ory-pg` volume whose `kratos` database the old v1.3.0 pin created therefore migrates forward on
its next start. A rehearsal on 2026-10-17 took a v1.3.0 database from 315 to 338 applied
migrations, and the identity and the API and browser sessions it held stayed valid. The
migration cannot be reversed: a migrated database no longer runs under v1.3.0. To keep a copy,
dump it before that start (`docker exec ory-pg pg_dump -U ory kratos > kratos-v1.3.0.sql`).

v26 behaviour to expect: once an identity has enrolled a second factor (TOTP or lookup secrets,
both enabled as in production), a password-only browser login answers 422
`browser_location_change_required`, redirecting to `?aal=aal2` with `return_to` kept, and
`/sessions/whoami` answers that identity's aal1 sessions with 403.

## Authentication (`server.auth/wrap-jwt-auth`)
buddy-auth JWS token backend, two modes:
- **RS256 via JWKS** (default) — keys fetched from `:jwks-url` (default
  `http://localhost:4444/.well-known/jwks.json`, i.e. Hydra), resolved by `kid`, and **re-fetched
  on cache miss** to support key rotation. `fhir-app` wires this mode by default.
- **HS256 static secret** — `JWT_DEV_SECRET`, for dev/test.

A valid token populates `:identity` (subject in `:sub`). Verification is fail-closed: a JWKS
fetch/parse error yields no key and hence no identity. `wrap-jwt-auth` does not itself reject a
missing token (the optional `wrap-require-auth` exists but is not in the default chain);
enforcement is delegated to Keto.

## Authorization (`server.keto/wrap-keto-authorization`)
Checks Ory Keto `/relation-tuples/check` (namespace `"fhir"`). HTTP method maps to a relation
(GET→`read`, POST/PUT/PATCH→`write`, DELETE→`delete`). Objects are **realm-scoped**: the
request's `:tenant-id` is the object's first segment, so granularity is **two-tier within a
realm** — the **type-level** object (`<realm>/Patient`) is checked first, then the
**instance-level** object (`<realm>/Patient/123`) — and a grant in one realm authorizes nothing
in another. A request with no realm in its path keeps the bare object. While the realm-scoping
migration is in flight the legacy realm-blind objects are accepted as a further fallback; see
[keto-realm-scoping.md](../keto-realm-scoping.md). Routes marked `:public? true` (SMART
discovery, `metadata`) bypass authz — except the bulk-data routes, which are `:public?` and
therefore run the same `<realm>/system` check inline (`server.keto/system-read-allowed?`). A
missing subject is a `401`; a denial or a Keto error is **fail-closed** to `403`.

## OAuth2 / SMART (Ory Hydra)
- **Hydra** issues OAuth2 tokens. The dev/Inferno path provisions a Hydra client and obtains a
  token via the `client_credentials` grant.
- **SMART discovery** (SMART App Launch 2.2.0, `server.smart-discovery`) --
  `/.well-known/smart-configuration` advertises the authorize, token and revocation endpoints,
  grant types (`authorization_code`, `client_credentials`, `refresh_token`), the SMART v1 and v2
  scopes plus the US Core granular scopes, and capabilities. A host configures it through the
  `:smart-discovery` option (or the `SMART_*` environment): token, introspection, revocation and
  management endpoint URLs, and the flags that add `launch-ehr`, the `context-*` capabilities and
  `authorize-post`. Every flag defaults off, so discovery never claims a feature the deployment
  has not switched on. The CapabilityStatement advertises the `SMART-on-FHIR` security service,
  `cors`, and the same endpoints as the SMART `oauth-uris` extension.

### Granular scopes (`server.scope`)
A v2 scope may carry a query (`patient/Observation.rs?category=<system>|<code>`). Only the
parameters in `server.scope/granular-parameters` are accepted (today `category` on Condition and
Observation); a v1 scope, a wildcard type, an unknown parameter or an unparseable query makes the
whole scope malformed, and it is dropped and logged rather than read as the unqualified scope.
`server.scope/grant-filter` unions a token's grants per type and interaction: an unqualified
grant wins, otherwise a resource must match one of the qualified queries.

`::smart-scope` remains the type gate. `::smart-scope-filter`, inside `::patient-compartment`,
installs `server.scope/ScopeFilteringStore` for any token whose unqualified wildcards do not cover
everything it grants. The store pushes the filter down on search and count (a client's own
`category` is ANDed as a repeated parameter), answers 403 on a read or vread the grant does not
admit, filters history, judges `_include`/`_revinclude` targets under their own type, refuses a
qualified write that does not match, and gates the entries of a Bundle POSTed to the base. A
compartment search needs the search letter on its target type. Bulk export applies the same
filter while streaming, to a system-tuple export as well as a scope-authorized one.

Known limits: a token that may update or delete but not read a type cannot use a handler path
that reads first (PATCH, a conditional write), and a conditional create cannot see matches it
may not search. `_has` is not judged against the granular filter (the Datomic store does not
support `_has`).

Repeated search parameters must AND in the store for the client-category intersection to be
exact. The mock and Datomic stores do; the xtdb2 store stringifies a repeated value, so such a
search returns nothing there (fail closed) until it learns repeated parameters.

### Token response context, introspection and revocation
Hydra cannot add top-level fields to its token response, and serves RFC 7662 introspection only
on its admin API. The split, owned outside dromon, is:

- **Token proxy** -- the host's login-consent service answers `POST /oauth2/token` at Hydra's own
  public token URL (so a `private_key_jwt` assertion's `aud` still matches), forwards the raw body
  to Hydra, and copies a fixed allowlist of launch context (`patient`, `encounter`,
  `need_patient_banner`, `smart_style_url`) from the issued access token's claims into the
  response JSON. Discovery publishes it as `token_endpoint` only when configured.
- **Introspection** -- login-consent also answers `POST /oauth2/introspect`, authenticating the
  caller (client_secret_basic, checked against Hydra, or a bearer access token) and forwarding to
  Hydra's admin introspection. An inactive or foreign token answers exactly `{"active": false}`.
  Discovery publishes `introspection_endpoint` only when configured.
- **Revocation** -- Hydra's public `/oauth2/revoke` (RFC 7009) is published by default. A
  patient's connected-apps revocation deletes the Hydra consent session for that client, which
  revokes its access and refresh tokens. dromon verifies access tokens locally (JWKS), so a
  revoked access token keeps working here until it expires unless the host adds an
  introspection-backed check in front of the FHIR chain.

## Open items
Open auth decisions are tracked in [`../open-decisions.md`](../open-decisions.md): building the
interactive `authorization_code` login/consent UI, identity↔FHIR `Patient`/`Practitioner`
mapping, Keto relational-tuple provisioning (e.g. event-driven on resource writes), first-class
EHR vs standalone SMART launch, and token rate limiting.
