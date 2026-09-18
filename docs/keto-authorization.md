# Keto Authorization Logic

## How it works

The `wrap-keto-authorization` middleware in `fhir-server/src/server/keto.clj` checks Ory Keto for permission tuples before allowing requests through.

### Permission model

- **Namespace**: Always `"fhir"`
- **Object**: The realm, then the resource type or instance --
  `"<realm>/Patient"`, `"<realm>/Patient/123"`, `"<realm>/system"`. The realm
  is the request's `:tenant-id` path parameter. A request that carries no
  realm (grant administration under `/auth/grants`, the Hydra token hook)
  keeps the bare object, because it is a global surface with no realm to scope
  to. See [keto-realm-scoping.md](keto-realm-scoping.md).
- **Relation**: Mapped from HTTP method, unless the route pins one with
  `:keto/relation` route data:
  - GET → `"read"`
  - POST/PUT/PATCH → `"write"`
  - DELETE → `"delete"`
- **Subject**: The `sub` claim from the JWT token, **bare**. The
  `breezeehr-role` and `practitioner-id` namespaces spell the same person
  `user:/<kratos-id>`; see the subject-spelling section of
  [keto-realm-scoping.md](keto-realm-scoping.md) before writing any tuple.

### Migration in progress

Until the backfill is confirmed complete, the reader accepts the legacy
realm-blind object as a fallback after the realm-scoped one, and writers emit
both. `KETO_LEGACY_REALM_BLIND_FALLBACK=0` turns the fallback off;
`KETO_DUAL_WRITE_LEGACY=0` stops the dual write. Neither should be set before
`bb keto-realm-backfill` reports nothing left to do.

### Type-level vs instance-level grants

Keto permission tuples can be granted at two levels:

1. **Type-level**: `object = "<realm>/Patient"` — grants access to ALL instances of that type in that realm
2. **Instance-level**: `object = "<realm>/Patient/123"` — grants access to a specific instance

When checking authorization for an instance, the middleware checks the
type-level object FIRST — it is the more common grant, so checking it first
usually settles the request in one call — and the instance-level object second.
A denial names the object the request was about (the instance), not the one
that happened to be checked first.

### Grant flow (Inferno test runner)

The `grant-keto-permissions` function in `bb/src/server/inferno_runner.clj` grants type-level permissions for all US Core resource types:

```
grant("fhir", "<realm>/Patient", "read", client-id)
grant("fhir", "<realm>/Patient", "write", client-id)
grant("fhir", "<realm>/Observation", "read", client-id)
...
```

The realm comes from the tenant segment of the URL the suite is pointed at
(`INFERNO_FHIR_URL`), so the grants follow the tenant being tested.

These type-level grants authorize the test client to read/write any instance of those types, including resources created by Inferno during the test run.

### Public routes

Routes with `:public? true` in their route data bypass Keto authorization entirely. Currently this includes:
- `/.well-known/smart-configuration`
- `/:tenant-id/fhir/metadata`

The bulk-data routes (`$export`, `$export-file`) are also `:public?`, which
means the middleware does NOT gate them. They perform the same check inline
instead, against `"<realm>/system"` — `server.keto/system-read-allowed?`,
called from `server.bulk-export`. A full-tenant export is the widest read the
server offers, so that check has to be realm-scoped too; it is the one place
where forgetting the realm would be worth the most to an attacker.
