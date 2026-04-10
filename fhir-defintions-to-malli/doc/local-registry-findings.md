# Local Registry / Recursive Type Findings

## Context

Bundle and Parameters (and other types with `contentReference` like Questionnaire, ExampleScenario, etc.) use recursive types. These are handled via `[:lazy-ref "#Bundle.link"]` references that resolve from a local registry.

## Implementation

Local registry `IntoSchema` reify entries are stored on the acc as `:local-registry` and merged into the resource's `registry` def. This keeps `sch` as a plain `IntoSchema` reify for all types.

### Local registry entry types

Each entry in `:local-registry` is `{:type :own/:ref, ...}`:

- **`:own`** — the resource defines the contentReference backbone element itself. Stored as `{:type :own, :form <code-form>, :sch <compiled-schema>}`.
- **`:ref`** — the resource inherits an unchanged contentReference from its base. Stored as `{:type :ref, :source-kw <kw-of-owning-resource>}`. Profiles reference the base resource's def instead of duplicating it.

### Generated output

For a base resource (Bundle) with its own contentReference:
```clojure
(def Bundle-link (reify m/IntoSchema ...))

(def registry
  {:org.hl7.fhir.../Meta org.hl7.fhir...Meta/sch,
   "#Bundle.link" Bundle-link
   ...})

(def sch (reify m/IntoSchema ...))  ;; plain, no [:schema wrapper

(def full-sch
  (m/schema [:schema {:registry registry} sch]
            fhir-registry-options))
```

For a profile (bodyheight) that inherits unchanged contentReference from Observation:
```clojure
;; No local def — references Observation's def directly
(def registry
  {:org.hl7.fhir.../Meta org.hl7.fhir...Meta/sch,
   "#Observation.referenceRange" org.hl7.fhir...Observation/Observation-referenceRange
   ...})
```

### Key functions

1. **`wrap-local-registry`** — Classifies entries as `:own` or `:ref` by comparing forms with the base resource. When `*local-registry*` is empty but the acc inherits entries from a base, converts inherited `:own` entries to `:ref`.

2. **`resolve-local-registry-schemas`** — Builds a malli-compatible registry from local-registry entries for use during staging compilation. Uses stored `:sch` objects for `:own` entries and resolves through source for `:ref` entries.

3. **`resolve-malli-sch`** — Includes local-registry schemas in the malli options so `[:lazy-ref]` refs resolve during staging.

4. **`write-single-schema!`** — For `:own` entries, emits local `(def ...)`. For `:ref` entries, emits a namespace-qualified symbol reference. Uses a regular map (not `sorted-map`) when local entries exist due to mixed string/keyword key types.

## Gotchas

- **`sorted-map` with mixed key types**: The resource registry uses `sorted-map` for deterministic output. When merging string-keyed local registry entries, must convert to a regular map first. `(into (sorted-map :a 1) {"b" 2})` throws because keywords and strings aren't comparable.

- **Staging compilation**: During staging, `resolve-malli-sch` must include local-registry schemas in the malli options. Without this, `[:lazy-ref "#Observation.referenceRange"]` fails to resolve when downstream profiles compile the base resource's sch. The `resolve-local-registry-schemas` helper builds a real malli registry from stored `:sch` objects.

- **Inherited entries without `*local-registry*`**: When a profile doesn't modify any contentReference elements, `*local-registry*` stays empty. But the acc inherits the base's `:local-registry`. The `wrap-local-registry` empty-branch converts these to `:ref` entries, preventing duplication.

- **Non-resource recursive types**: In FHIR, `contentReference` is only used in resources, never in complex-types. So only resources need the local registry in their `registry` def.
