# Dromon FHIR Server

A Clojure-based multitenant FHIR Server leveraging Reitit for routing, Malli for schema validation, and XTDB v2 / Datomic for immutable backend storage.

## Architecture

*   **Routing**: Database-per-tenant isolation using `/:tenant-id/fhir/` base paths.
*   **Validation**: Malli schemas defining FHIR resources.
*   **Database**: Pluggable storage architecture. Currently implementing **XTDB v2** natively mapping FHIR resources directly to dynamically constructed explicit table schemas.

## Running the Server

To start the development Jetty server locally (defaulting to port `8080`), you can execute the core namespace via the Clojure CLI:

```bash
clj -X server.core/-main
```

Or, to specify a custom port (e.g., `3000`):

```bash
clj -X server.core/-main :port 3000
```

## Running the Tests

The test suite uses **Kaocha** across an in-memory test environment.

### XTDB v2 Requirements

XTDB v2 utilizes Apache Arrow over Java NIO for columnar memory buffers. This requires reflective access to the JDK. The required JVM flags (`--add-opens=java.base/java.nio=ALL-UNNAMED`) have already been added to the `:test` alias in `deps.edn`.

To run the complete test suite:

```bash
clojure -M:test
```

This will spin up the in-memory XTDB node, execute the schema modeling assertions, and validate the `insert` and `select` JSON integrations map successfully against the local database instance.

## Local Integration Environment

To run integration tests against the complete authentication and authorization stack (Ory Kratos, Keto, and Hydra), the repository includes a Babashka script to orchestrate the Docker containers.

Ensure you have [Babashka](https://babashka.org/) installed, and then run:

```bash
bb setup
```

This will start an isolated `ory-net` Docker network, spin up a PostgreSQL instance, initialize databases for each service, run required SQL migrations, and boot up Kratos, Keto, and Hydra on localized ports.

To stop and remove these containers and the network, run:

```bash
bb teardown
```

## Inferno Test Suite

To test the server against the [Inferno framework](https://inferno-framework.github.io/), the project includes several babashka shortcut tasks that manage a local Docker-based Inferno environment.

1. **Setup the Test Kit**:
   Downloads the latest inferno template, pulls the required Docker images, and fixes local directory permissions.
   ```bash
   bb inferno-setup
   ```

2. **Start Interactive Testing Session**:
   Starts the Inferno web UI and backend services. You can then navigate to `http://localhost` to run interactive tests.
   ```bash
   bb inferno-run
   ```

3. **Run Automated CLI Tests**:
   Executes the test suite against the local FHIR server headlessly via the Inferno CLI, and exports the results to `target/inferno-report.json`.
   ```bash
   bb inferno-test
   ```

4. **Teardown**:
   Stops and removes the Inferno Docker containers.
   ```bash
   bb inferno-down
   ```
