# Fix Kratos Cipher Secret Config Bug

## Problem

`dromon/docker/kratos.yml:27` references a literal
`$KRATOS_CIPHER_SECRET` placeholder in the `secrets.cipher` list:

```yaml
secrets:
  cookie:
    - $KRATOS_COOKIE_SECRET    # line 25, same bug
  cipher:
    - $KRATOS_CIPHER_SECRET    # line 27
```

Kratos v1.3.0 does not perform `$VAR`-style environment substitution in
YAML config values. It parses the literal string `$KRATOS_CIPHER_SECRET`
(21 characters) and fails schema validation because `secrets.cipher`
elements must be at least 32 characters long. The result is that the
kratos container started by
`dromon/bb/src/server/docker_env.clj` `start!` crashes on boot with:

```
secrets.cipher.0: $KRATOS_CIPHER_SECRET
                  ^-- length must be >= 32, but got 21
```

The failure has been silent for some time because `start!` does not
verify that the container is still running after `docker run`. Podman
returns `0` once the container has been created, even though it exits
immediately afterwards, so `bb setup` reports "Environment started
successfully!" with a dead kratos in the pool.

Inferno US Core v6.1.0 compliance tests keep passing because
`server.inferno-runner` only talks to hydra (OAuth2 `client_credentials`
grant) and keto (authz tuples). Nothing in the current test or dev path
actually exercises kratos, so nobody has noticed. The bug was found
while confirming that tightened Ory container memory/cpu limits did not
break anything: kratos was the one container that failed to start, and
it turned out to have nothing to do with the new limits.

## Approach — Pick One

Three options. Pick based on whether kratos is actually needed.

### Option 1 — Drop kratos entirely

If nothing in dromon genuinely depends on kratos (identity management,
self-service flows, recovery, verification), remove it from
`server.docker-env/start!` and `stop!`, remove `docker/kratos.yml` and
`docker/identity.schema.json`, remove the migration step, update
`bb.edn` docs, and close this task. Cleanest outcome; no broken
container to maintain.

Before picking this path, grep for anything that talks to kratos:

```
rg '4433|4434|kratos' dromon/ --type clj
```

### Option 2 — Use Kratos's native env-var override syntax

Kratos maps config paths onto env vars with `_` as a path delimiter. For
a list value like `secrets.cipher`, pass a JSON-encoded array:

```
-e "SECRETS_CIPHER=[\"dev-kratos-cipher-secret-0123456789\"]"
-e "SECRETS_COOKIE=[\"dev-kratos-cookie-secret-0123456789\"]"
```

Remove the `$KRATOS_CIPHER_SECRET` placeholders from `docker/kratos.yml`
(leave the keys out entirely). `start!` defines the secrets via
`-e` just like it already defines `DSN`.

Add defs near the top of `docker_env.clj` following the `pg-password`
pattern:

```clojure
(def kratos-cipher-secret
  (or (System/getenv "KRATOS_CIPHER_SECRET")
      "dev-kratos-cipher-secret-0123456789"))
(def kratos-cookie-secret
  (or (System/getenv "KRATOS_COOKIE_SECRET")
      "dev-kratos-cookie-secret-0123456789"))
```

Each must be >= 32 chars. Assertion inside `start!` should fail fast
if either env override is shorter than 32 chars.

### Option 3 — Preprocess kratos.yml at setup time

Read `docker/kratos.yml`, substitute `$KRATOS_CIPHER_SECRET` and
`$KRATOS_COOKIE_SECRET` with the dev defaults (or env overrides),
write the rendered copy to `dromon/target/kratos.yml`, mount that
path into the container. Keeps the source file template-friendly for
future multi-environment setups.

```clojure
(defn- render-kratos-yml! []
  (let [src (slurp "docker/kratos.yml")
        out (io/file "target/kratos.yml")]
    (.mkdirs (.getParentFile out))
    (spit out (-> src
                  (str/replace "$KRATOS_CIPHER_SECRET" kratos-cipher-secret)
                  (str/replace "$KRATOS_COOKIE_SECRET" kratos-cookie-secret)))
    (.getAbsolutePath out)))
```

Add `target/kratos.yml` to `.gitignore` if it is not covered by the
existing `target` rule (it is — line 14).

## Recommendation

**Option 1** if `rg kratos` comes up empty outside the `docker_env` boot
path — simpler is better. **Option 2** otherwise. Option 3 is only
worth it if there is a concrete need to keep the yaml environment-
agnostic.

## Defensive Fix Regardless of Option

`start!` currently shells out `docker run -d ...` and assumes success if
the shell returns 0. Add a post-start health check for each long-running
container: after the `shell` call, sleep briefly, then
`(container-running? name)` and throw a clear `ex-info` if the container
exited. That turns every future config bug of this shape into a loud
failure instead of a silent one.

```clojure
(defn- assert-container-up! [name]
  (Thread/sleep 1500)
  (when-not (container-running? name)
    (let [{:keys [out err]} @(process ["docker" "logs" name] {:out :string :err :string})]
      (throw (ex-info (str "Container " name " failed to start")
                      {:container name
                       :stdout out
                       :stderr err})))))
```

Call it after each `docker run -d` in `start!`. Fixing this is what
would have caught the kratos bug originally.

## Testing

- `bb teardown && bb setup` — verify all four containers report
  `running=true` (or three, if Option 1 was picked and kratos was
  removed).
- `bb inferno-test` — still passes 505/505.
- Post-fix: deliberately break kratos.yml (e.g., set a 10-char cipher
  secret) and confirm `bb setup` now fails loudly with the assertion
  from the "Defensive Fix" section.

## References

- `dromon/bb/src/server/docker_env.clj` — the `start!` function
- `dromon/docker/kratos.yml:25-27` — the two `$`-prefixed placeholders
- Ory Kratos config reference:
  https://www.ory.sh/docs/kratos/reference/configuration
- Ory Kratos env-var mapping:
  https://www.ory.sh/docs/ecosystem/configuring (look for the section
  on "Environment variables")
