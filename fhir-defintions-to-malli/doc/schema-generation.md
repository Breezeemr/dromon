# Schema Generation Documentation

This document describes how `fhir-defintions-to-malli` processes the downloaded base FHIR JSON definitions and transpiles them into functional Clojure schemas using Malli.

## FHIR Bundle Data Shapes

The downloaded definitions come categorized in three primary "Bundle" documents, which follow the standard FHIR Bundle wrapper resource structure (`resourceType: Bundle`, `type: collection`, containing an array of `entry` objects). Each entry holds a `resource` definitions, mainly `StructureDefinition` resources mapping both base types and extensions. 

The importer operates specifically on the [`differential`](http://hl7.org/fhir/R4/profiling.html#differential) definitions (how types vary from their parents), dropping the `snapshot` data from constraint extensions, as the generation relies directly on diff reconstruction.

1. **`profiles-types.json`**: 
   - **Content**: 64 total entries.
   - **Shape**: Primarily contains the fundamental building blocks of FHIR (`kind: primitive-type` [20 count] and `kind: complex-type` [44 count]). This includes scalars like `string`, `date`, `instant`, and complex shared elements like `Identifier` or `Period`.
   - **Role**: Processed first, setting up leaf dependencies and primitives that more complex resources expand upon. Primitive types are systematically excluded from complex iteration loops as they map to our manually-defined `fhir-primitives` schemas.

2. **`profiles-resources.json`**:
   - **Content**: 196 total entries.
   - **Shape**: Represents the top-level Domain Resources (`kind: resource` [143 count]), operation definitions (`kind: operation` [46 count]), and some implicit framework capabilities.
   - **Role**: This is where major models like `Patient` or `Observation` come from. They often have a top-level `baseDefinition` linking back to a DomainResource schema. 

3. **`profiles-others.json`**: 
   - **Content**: 39 total entries.
   - **Shape**: Miscellaneous profiles, primarily encompassing extension definitions (`kind: resource` [38 count] and `kind: complex-type` [1 count]).
   - **Role**: Processed last, forming specific sub-constraints dependent on models from the first two bundles.

## Schema Construction: `sch` vs `form`

As `structure-definition->schema` loops through components recursively, every active structure definition under evaluation builds up two parallel properties: `sch` and `form`.

### `sch` (In-Memory Schema Structure)
The `sch` key maintains the literal, active Malli schema map struct (e.g., `[:map [:id :string]]`).
* **Why it exists**: It offers an instanced entity acting as the live source-of-truth. `fhir_defintions_to_malli.clj` requires the actual running schema to effectively look up dependencies, fetch nested attributes (`mu/get`), query parent properties when dealing with `baseDefinition` extensions, and safely perform incremental patches (`mu/assoc`, `mu/dissoc`).

### `form` (Quoted S-Expression AST)
The `form` key tracks an accumulative, quoted un-evaluated Clojure vector that functions as an AST. It retains every incremental transformation step applied to the schema (`(~'-> ~sch ... )`).
* **Why it exists**: When outputting final `.cljc` source files, we don't want to dump serialized raw vector maps which are both massive and completely unreadable. While Malli offers a core `m/form` function to convert an instantiated schema back into clojure syntax, it struggles significantly with recursive/cyclic definitions, nested sub-schemas, complex functional modifiers, and preserving exact functional semantics (like using `malli.util` namespaces properly). 

By distinctly rendering modifications over the `form` AST explicitly during build (e.g., prepending `(~'mu/update-properties ...)` or `(~'mu/assoc ...)` list blocks as each attribute of the differential array is encountered), the project successfully persists deterministic, highly traversable and idiomatic Clojure files on disk. 

## The Build Process
1. Data loaded from bundles mapping `baseDefinition` chains to generate a proper topological `dependency-order`.
2. A patch accumulator streams nested structures via XForms.
3. As the script encounters recursive references or primitives, those are redirected strictly against `lazy-refs` or registered statically to prevent cyclic blowouts.
4. For each resource, `write-resources` invokes Fast Idiomatic Pretty Printer (`fipp`) entirely on the generated AST contained in `form`, cleanly emitting a `def sch` block into target source directories.
