# Brainstorming: FHIR Extension Slicing to Custom Malli Attributes

## 1. Complex Extension Internal Slicing (e.g., `us-core-race`)

When a complex FHIR extension (like US Core Race) restricts its internal `Extension.extension` array, it does so by slicing. 

### The Slicing Declaration
```json
{
  "id": "Extension.extension",
  "path": "Extension.extension",
  "slicing": { "discriminator": [{ "type": "value", "path": "url" }], "rules": "open" }
}
```

### The Slice Definitions
```json
{
  "id": "Extension.extension:ombCategory",
  "sliceName": "ombCategory",
  "min": 0, "max": "6"
}
```
Child elements then specify fixed URIs and value types (e.g., `Extension.extension:ombCategory.url` fixed to `ombCategory`, and `Extension.extension:ombCategory.value[x]` set to `Coding`).

## 2. Resource-Level Extension Slicing (e.g., `us-core-patient`)

When a core resource profile like US Core Patient restricts the extensions that can be attached to it, it slices the top-level `Patient.extension` array.

### The Slicing Declaration
```json
{
  "id": "Patient.extension",
  "path": "Patient.extension",
  "slicing": { "discriminator": [{ "type": "value", "path": "url" }], "rules": "open" }
}
```

### The Slice Definitions
Instead of defining a simple `value[x]`, resource-level slices typically reference an external extension profile via the `type` element:

```json
{
  "id": "Patient.extension:race",
  "path": "Patient.extension",
  "sliceName": "race",
  "min": 0, "max": "1",
  "type": [
    {
      "code": "Extension",
      "profile": [ "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race" ]
    }
  ]
}
```
- **sliceName**: Identifies the semantic meaning (`race`, `ethnicity`, `genderIdentity`).
- **type.profile**: Provides the definitive StructureDefinition URL for the extension that must be used.

---

## 3. Using Slicing to Create Custom Attributes in Malli

If the goal is to make the Clojure representation more ergonomic by lifting slices into direct map keys (instead of a single large `[:extension ...]` array vector), we can map the `differential` data directly into customized Malli schemas for both resources and complex extensions.

### The Desired Ergonomic State for `us-core-race` (Complex Extension)
```clojure
;; Data
{:url "http://.../us-core-race"
 :ombCategory [{:system "...", :code "2106-3", :display "White"}]
 :text "Mixed"} 

;; Custom Malli Schema
[:map
 [:url [:= "http://.../us-core-race"]]
 [:ombCategory {:optional true} [:vector {:min 0 :max 6} [:ref :.../Coding]]]
 [:detailed {:optional true} [:vector {:min 0 :max nil} [:ref :.../Coding]]]
 [:text {:optional false} :string]]
```

### The Desired Ergonomic State for `us-core-patient` (Resource Level)
```clojure
;; Data
{:resourceType "Patient"
 :name [...]
 :race {:url "http://.../us-core-race", :ombCategory [...], :text "White"}
 :genderIdentity [{:url "...", :valueCodeableConcept {...}}]}

;; Custom Malli Schema
[:map
 [:resourceType [:= "Patient"]]
 [:name [:vector [:ref :.../HumanName]]]
 ;; Lifted explicit profile extensions based on sliceName:
 [:race {:optional true} [:ref :.../us-core-race]]
 [:ethnicity {:optional true} [:ref :.../us-core-ethnicity]]
 [:genderIdentity {:optional true} [:vector [:ref :.../us-core-genderIdentity]]]
 ;; Fallback for other standard extensions
 [:extension {:optional true} [:vector [:ref :.../Extension]]]]
```

## 4. Brainstorming Implementation Strategies

#### Strategy A: Custom AST Modification (`mu/assoc` generated dynamically)
When iterating over the `differential.element` array during syntax tree generation, look for `.slicing` on either `Extension.extension` or `[Resource].extension`.

**For Resource Top-Level Extensions (`[Resource].extension`):**
1. **Find slices**: Elements with a `sliceName`.
2. **Extract Schema Ref**: Look at `type[0].profile[0]`. This URL points to the referenced extension schema (e.g. `us-core-race`). Resolve this URL to the corresponding namespace/symbol in your AST output just like you do for other property types.
3. **Handle Cardinality**: If `max` > 1 (e.g. `"*"`, `"2"`), wrap the ref in `[:vector ...]`. 
4. **Generate Code**: Emit a `mu/assoc` on the base schema where the key is the `sliceName` as a keyword (e.g., `:race`), and the value is the resolved ref (e.g., `[:ref :org.hl7.fhir.us.core.../us-core-race]`).

**For Complex Internal Extensions (`Extension.extension`):**
1. **Find slices**: Elements with a `sliceName`.
2. **Extract Schema Ref**: Look for the child element `[SliceId].value[x]` and read its `type[0].code` (e.g., `Coding`).
3. **Handle Cardinality**: Same as above.
4. **Generate Code**: Emit `mu/assoc` with keys matching `sliceName` and values matching the internal primitives. Remove the standard `[:extension ...]` key entirely from this specific extension's custom schema using `mu/dissoc`.

#### Strategy B: Malli Transformations (Decoders/Encoders)
If keeping the standard FHIR schema shape is strictly required for serialization natively by Malli core, you can attach `::m/decode` and `::m/encode` interceptors to your schemas.

```clojure
(def us-core-patient-schema
  [:map
   {:decode/fhir
    (fn [x]
      ;; Sweeps x for `:extension` array, pulls out known profiles by `url`, 
      ;; and assigns them to top-level keys like `:race` based on a lookup map.
      ...)
    :encode/fhir
    (fn [x]
      ;; Takes custom keys like `:race` and repacks them into the `:extension` array vector.
      ...)}
   ...])
```

### Challenges to Consider
1. **Collisions**: When lifting a top-level resource extension like `:race`, does it collide with standard FHIR attributes on a Patient? (There is no native `:race` on Patient, so we are safe, but it's essential to ensure no `sliceName` arbitrarily matches an existing backbone element or base property).
2. **Open Slicing**: The slicing rules are almost always `"rules" : "open"`. For resources like `Patient`, we must leave the base `:extension` array available so un-profiled or miscellaneous extensions can still be captured and stored without breaking validation.
3. **Discriminator Mapping**: The discriminator `{ "type": "value", "path": "url" }` maps a profile URL to a `sliceName`. If moving completely to custom keys (Strategy A and B), any JSON read step must understand that `{ url: "http://.../us-core-race" }` maps exactly to the `:race` property.

## 5. Implementation Learnings: AST Generation

While implementing slicing directly into Malli schema ASTs based on Strategy A, we encountered specific challenges related to Clojure's sequence operations and Malli's compilation requirements:

### 1. The Perils of `reduce` with Missing Collections
When iterating over slicing definitions to build the patched schema map, omitting the collection argument in a `reduce` call (e.g., using `(if (nil? coll) ...)` poorly) caused `reduce` to fall back to its 2-arity form. This treated the accumulator map `acc` itself as the collection, breaking it down into `MapEntry` pairs (`[k v]`). Passing a `MapEntry` to functions expecting a map resulted in deep `IllegalArgumentException: Key must be integer` crashes when trying to `assoc` or `update` into what was supposed to be a map. Ensure `reduce` always receives 3 arguments when iterating over collections holding map state.

### 2. Malli Compilation Context Issues (`:malli.core/invalid-schema`)
When generating schemas for `[:lazy-ref]` instances representing sliced extensions (like `patient.race`), attempting to call `m/type` on the raw AST vector to check if it's `:sequential` caused Malli to panic. Malli requires an `external-registry` context to evaluate schema types natively, which is unavailable until final generation. 

**Solution:** Bypassed `m/type` strict schema validation for vectors during AST composition by directly inspecting the first element of the AST array (`(= :sequential (first new-sub-sch))`), falling back to `m/type` safely wrapped in a `try-catch` for non-vector literal schemas.

### 3. Macro Formatting Instabilities (`cond->`)
Code formatting tools (e.g., `clj-paren-repair`) aggressively close unbalanced parentheses. During AST creation, if bounds around a `cond->` block were left open, the formatter slurped subsequent chained functions or let bindings into the `cond->` expression, breaking the expected even parity of test-expression clauses and leading to `AssertionError: (even? (count clauses))` during macroexpansion. Always carefully audit parenthesis balances before letting automated repair tools fix syntax around threading macros.
