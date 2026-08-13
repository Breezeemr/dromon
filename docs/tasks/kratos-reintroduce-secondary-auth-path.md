# Reintroduce Ory Kratos in a Secondary Auth Path

## Status

In progress. Steps 1-2 are shipped and validated: `docker/kratos.yml` and
`docker/identity.schema.json` are recreated with secrets supplied via the
`SECRETS_COOKIE` / `SECRETS_CIPHER` env-var mapping (never `$VAR` literals
in-file), and `server.docker-env/start-auth-stack!` / `stop-auth-stack!`
(`bb auth-stack-up` / `bb auth-stack-down`) boot Kratos on top of the main
pool with `assert-container-up!` loud failure, create the kratos database
idempotently (init-db.sql only runs on first ory-pg creation), and recreate
Hydra with its login/consent URLs pointed at the login-consent app
(`master-at-arms2/login-consent`, default http://host.docker.internal:3001).
The main pool and `bb setup` / `bb inferno-test` are untouched. Steps 3-4
(login/consent provider, e2e runner) are being built as the login-consent
app in the master-at-arms2 repo; step 5 (docs flip) lands with them.

## Context

Ory **Kratos** is the identity / login provider that backs the OAuth2
`authorization_code` (interactive login + consent) flow. It was **dropped from the
integration environment only as a temporary workaround**, not by design:

- A config bug (`$KRATOS_CIPHER_SECRET` / `$KRATOS_COOKIE_SECRET` literal
  placeholders that Kratos v1.3.0 does not env-substitute, failing the ≥32-char
  schema check) crashed the kratos container on boot. See
  [`kratos-cipher-secret-config.md`](kratos-cipher-secret-config.md).
- Because nothing in the test/dev path exercised Kratos, it was removed entirely
  from `server.docker-env` `start!`/`stop!` (only a best-effort legacy-container
  cleanup remains, `bb/src/server/docker_env.clj:148-151`), and `docker/kratos.yml`
  was deleted (the `docker/` dir now holds only `hydra.yml`, `keto.yml`,
  `init-db.sql`, `namespaces.ts`).

As a result the `authorization_code` grant advertised in SMART discovery has **no
backing login provider today** (`docs/design/auth.md:8-10`, `:40-44`). Only the
`client_credentials` (machine-to-machine) flow works end to end.

We want Kratos back so the interactive login/consent flow can be built and tested —
this is the prerequisite for **human-initiated delegation** (e.g. the
capability-based-security token-exchange work, where a human's `authorization_code`
session is the root authority that gets attenuated for agents). But Kratos must come
back **without** burdening the main test path.

## Goal

Reintroduce Kratos as a **secondary, opt-in auth path** that exercises the
`authorization_code` login/consent flow, while the **main path stays exactly as it
is**.

### Non-goals / hard constraints

- **Do not touch the main path.** `bb setup` and `bb inferno-test` must keep
  bringing up only `["ory-pg" "keto" "hydra"]` (`bb.edn:6,13`;
  `docker_env.clj` `start!`), stay green at **505/505**, and gain **no** Kratos
  dependency or startup cost. Kratos failing to start must never break
  `bb inferno-test`.
- Follow the existing **secondary-runner precedent**: `bb compartment-e2e`
  (`compartment_e2e_runner.clj`, the task at `bb.edn:133-136`) already stands up an
  extended Ory stack (Hydra token-hook + Keto), runs full-stack scenarios, and
  **skips gracefully if Docker is unavailable**. Mirror that shape.

## Approach

1. **Fix the original config bug first.** Recreate `docker/kratos.yml` with the
   cipher/cookie secrets supplied as **real ≥32-char values** (or via the documented
   `SECRETS_CIPHER` / `SECRETS_COOKIE` env-var mapping), not `$VAR` placeholders that
   Kratos does not substitute. See `kratos-cipher-secret-config.md` for the exact
   failure mode and the Ory config/env references.

2. **Add a separate boot routine — do not extend `start!`.** Add e.g.
   `server.docker-env/start-auth-stack!` (or a parameterized variant) that brings up
   Kratos (and any login/consent helper) **on top of** the existing `ory-pg` / `keto`
   / `hydra` containers, reusing `assert-container-up!`
   (`docker_env.clj:30-44`) so a boot failure is loud. Keep `start!`/`stop!`'s
   container list (`["ory-pg" "keto" "hydra"]`) unchanged; add Kratos teardown to the
   new routine (and keep the existing best-effort `kratos` cleanup in `stop!`).

3. **Stand up a login/consent provider for Hydra.** Hydra delegates login and
   consent to an external app; provide a minimal one wired to Kratos (the
   self-service login/registration/whoami flows) so the `authorization_code` +
   PKCE/SMART launch can complete. SMART discovery already advertises
   `authorization_endpoint` / `authorization_code`
   (`docs/design/auth.md:35-38`), so the server side mostly needs the provider, not
   new discovery.

4. **Add a secondary runner + bb task.** New `server.kratos-e2e-runner` (name TBD)
   and a `bb auth-code-e2e` task alongside `compartment-e2e` in `bb.edn`, that:
   - calls `start-auth-stack!`, runs an `authorization_code` login → token →
     authorized FHIR request scenario, asserts the issued token validates through the
     existing `server.auth/wrap-jwt-auth` (RS256/JWKS, `auth.clj:74-113`) and is
     authorized by Keto;
   - **skips if Docker is unavailable** (same guard as `compartment-e2e`);
   - never runs as part of `inferno-test`.

5. **Document the two paths.** Update `docs/design/auth.md` once shipped: the main
   path is `client_credentials` (Inferno); the secondary path adds Kratos +
   login/consent for `authorization_code`. Flip the "Kratos has been removed" /
   "authorization_code not yet built" notes to describe the secondary path.

## Testing

- `bb teardown && bb setup` then `bb inferno-test` — **still 505/505**, and `setup`
  still reports only `ory-pg` / `keto` / `hydra` (no kratos in the main pool).
- New `bb auth-code-e2e` — brings up the auth stack including Kratos, completes an
  `authorization_code` login/consent, obtains a token, and makes an authorized FHIR
  call; tears the extra containers down afterward; **skips cleanly with no failure
  when Docker is absent**.
- Regression: with Docker present but the auth stack intentionally broken (e.g. a
  short cipher secret), `bb auth-code-e2e` fails **loudly** via `assert-container-up!`
  — never silently — while `bb inferno-test` remains unaffected.

## References

- `bb/src/server/docker_env.clj` — `start!` / `stop!` container pool;
  `assert-container-up!` (`:30-44`); legacy kratos cleanup (`:148-151`).
- `bb.edn` — `setup`/`teardown` container checks (`:6`, `:13`); `inferno-test`
  (`:103-107`); `compartment-e2e` secondary-runner precedent (`:133-136`).
- `bb/src/server/compartment_e2e_runner.clj` — secondary full-stack runner pattern
  (Hydra token hook at `:28-67`; Docker-availability skip).
- `bb/src/server/inferno_runner.clj` — main test path (`client_credentials` + Keto).
- `docs/tasks/kratos-cipher-secret-config.md` — the original removal + the cipher
  secret config bug to fix.
- `docs/design/auth.md` — current auth status (`client_credentials` works,
  `authorization_code` not yet built, Kratos removed).
- Ory Kratos config / env-var mapping:
  https://www.ory.sh/docs/kratos/reference/configuration ·
  https://www.ory.sh/docs/ecosystem/configuring
- Ory Hydra login/consent flow:
  https://www.ory.sh/docs/hydra/concepts/login
