# Store Initialization with Resource Schemas

## Problem

FHIR store implementations need access to compiled Malli schemas at construction time so they can build schema-aware transformers. Without schemas, the XTDB store cannot:

1. Rename `_`-prefixed primitive extension keys (e.g., `_birthDate`) to XTDB-safe column names (`primitive-ext-birthDate`) — XTDB interprets leading underscores as internal.
2. Coerce FHIR date/time strings to native XTDB temporal types (`LocalDate`, `OffsetDateTime`, `Instant`, `LocalTime`) for indexed date range queries.
3. Reverse these transforms on read, restoring `primitive-ext-` back to `_` and `ZonedDateTime` back to `OffsetDateTime`.

A standard initialization contract lets every `IFHIRStore` implementation receive schemas through a well-known key, while remaining open to implementation-specific config.

## Approach

### Protocol-level factory function

Add a `create-store` function to `fhir-store.protocol` that serves as the canonical entry point for constructing any store implementation. It takes an `impl-fn` (the implementation's constructor) and a config map:

```clojure
;; fhir-store-protocol/src/fhir_store/protocol.clj
(defn create-store
  "Creates an IFHIRStore implementation. `impl-fn` is a function that takes
   a config map and returns an IFHIRStore instance.

   The config map contains:
   - :resource/schemas  — vector of compiled malli schemas (one per supported
                          resource type, each carrying :resourceType, :fhir/cap-schema,
                          :fhir/interactions, :fhir/search-registry in properties)
   - Implementation-specific keys (e.g., XTDB node config)"
  [impl-fn config]
  (impl-fn config))
```

The `:resource/schemas` key is the one well-known key. Each schema in the vector is a compiled Malli schema whose `m/properties` carry at minimum `:resourceType` (e.g., `"Patient"`). Implementations can ignore schemas they don't need, and the vector may be empty for schema-unaware stores (e.g., the mock store).

### XTDB store constructor

`create-xtdb-store` destructures `:resource/schemas` from the config and uses them to precompile per-resource-type malli encoder/decoder functions:

```clojure
;; fhir-store-xtdb2/src/fhir_store_xtdb2/core.clj
(defn create-xtdb-store
  [{:keys [resource/schemas node-config] :or {node-config {} schemas []}}]
  (let [storage-encoders (xf/build-storage-encoders schemas)
        read-decoders    (xf/build-read-decoders schemas)]
    (->XTDBStore (atom {}) node-config storage-encoders read-decoders)))
```

### Malli transformers (fhir-store-xtdb2.transform)

Built once at init from schemas. Two transformer directions:

**Storage (FHIR -> XTDB, encode):**
- Root-level maps: rename `_`-prefixed keys to `primitive-ext-` prefix
- Nested maps: convert keyword keys to strings (XTDB struct format)
- Temporal leaf values: coerce via `datetime/fhir->xtdb` to native java.time types

**Read (XTDB -> FHIR, decode):**
- Root-level maps: rename `primitive-ext-` keys back to `_`-prefixed
- Nested maps: convert string keys back to keywords
- `ZonedDateTime` -> `OffsetDateTime` (FHIR canonical form)

Each resource type gets its own compiled encoder/decoder. A `:default` encoder/decoder (built from bare `:map`) handles unknown resource types.

### Mock store

The mock store constructor accepts the same config map shape but does not use `:resource/schemas` — it stores resources as-is in an atom:

```clojure
;; fhir-store-mock/src/fhir_store/mock/core.clj
(defn create-mock-store [options]
  (->MockStore (atom {}) options))
```

### Integrant wiring

```clojure
;; demo-fhir-server/src/demo/core.clj
{:fhir-store/xtdb2-node {}
 :fhir-store/xtdb2-store {:node (ig/ref :fhir-store/xtdb2-node)
                           :resource/schemas fhir-server/schemas}}
```

The Integrant init-key for `:fhir-store/xtdb2-store` passes the full config (including `:resource/schemas`) to `create-xtdb-store`.

## Dependencies

- Malli (`metosin/malli`) — schema introspection, `m/encoder`, `m/decoder`, `mt/transformer`
- Compiled resource schemas from `fhir/malli/uscore8/` (or r4b base)

## Files

- Modified: `fhir-store-protocol/src/fhir_store/protocol.clj` — add `create-store` factory fn
- Modified: `fhir-store-xtdb2/src/fhir_store_xtdb2/core.clj` — accept `:resource/schemas`, build encoders/decoders at init
- New file: `fhir-store-xtdb2/src/fhir_store_xtdb2/transform.clj` — malli transformer builders
- Modified: `demo-fhir-server/src/demo/core.clj` — pass `:resource/schemas` in Integrant config

## Status

Complete
