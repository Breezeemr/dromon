# Authentication and Authorization Design

## Status
Runtime auth is **JWT verification (RS256/JWKS by default, HS256 for dev) + Ory Keto
authorization**, with **Ory Hydra** providing OAuth2 tokens and a **SMART configuration
discovery** endpoint. The **`client_credentials`** (machine-to-machine) flow works end to end
(exercised by the Inferno suite). The interactive **`authorization_code` login/consent flow is
not yet built** — there is no consent UI and **Ory Kratos has been removed** from the
environment (see `docs/tasks/kratos-cipher-secret-config.md`). The advertised `authorization_code`
grant therefore has no backing login provider today.

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
(GET→`read`, POST/PUT/PATCH→`write`, DELETE→`delete`). Granularity is **two-tier**: the
**type-level** object (`Patient`) is checked first, then the **instance-level** object
(`Patient/123`) as a fallback — so a type-level grant covers all instances of that type. Routes
marked `:public? true` (SMART discovery, `metadata`) bypass authz. A missing subject or a Keto
error is **fail-closed** to `403`.

## OAuth2 / SMART (Ory Hydra)
- **Hydra** issues OAuth2 tokens. The dev/Inferno path provisions a Hydra client and obtains a
  token via the `client_credentials` grant.
- **SMART discovery** — `/.well-known/smart-configuration` advertises `authorization_endpoint` /
  `token_endpoint`, grant types (`authorization_code`, `client_credentials`), SMART scopes
  (`launch`, `launch/patient`, `patient/*.read`, ...), and capabilities including
  `launch-standalone`. The CapabilityStatement advertises the `SMART-on-FHIR` security service.

## Open items
Open auth decisions are tracked in [`../open-decisions.md`](../open-decisions.md): building the
interactive `authorization_code` login/consent UI, identity↔FHIR `Patient`/`Practitioner`
mapping, Keto relational-tuple provisioning (e.g. event-driven on resource writes), first-class
EHR vs standalone SMART launch, and token rate limiting.
