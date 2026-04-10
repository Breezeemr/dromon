# Keto Authorization Logic

## How it works

The `wrap-keto-authorization` middleware in `fhir-server/src/server/keto.clj` checks Ory Keto for permission tuples before allowing requests through.

### Permission model

- **Namespace**: Always `"fhir"`
- **Object**: Resource type (e.g. `"Patient"`) or instance (e.g. `"Patient/123"`)
- **Relation**: Mapped from HTTP method:
  - GET → `"read"`
  - POST/PUT/PATCH → `"write"`
  - DELETE → `"delete"`
- **Subject**: The `sub` claim from the JWT token

### Type-level vs instance-level grants

Keto permission tuples can be granted at two levels:

1. **Type-level**: `object = "Patient"` — grants access to ALL instances of that type
2. **Instance-level**: `object = "Patient/123"` — grants access to a specific instance

When checking authorization for an instance (e.g. `Patient/123`), the middleware first checks the instance-level permission. If denied, it falls back to checking the type-level permission for that resource type. This allows a single type-level grant to cover all instances.

### Grant flow (Inferno test runner)

The `grant-keto-permissions` function in `bb/src/server/inferno_runner.clj` grants type-level permissions for all US Core resource types:

```
grant("fhir", "Patient", "read", client-id)
grant("fhir", "Patient", "write", client-id)
grant("fhir", "Observation", "read", client-id)
...
```

These type-level grants authorize the test client to read/write any instance of those types, including resources created by Inferno during the test run.

### Public routes

Routes with `:public? true` in their route data bypass Keto authorization entirely. Currently this includes:
- `/.well-known/smart-configuration`
- `/:tenant-id/fhir/metadata`
