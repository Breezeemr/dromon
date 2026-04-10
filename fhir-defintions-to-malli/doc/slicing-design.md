# FHIR Slicing Design for Malli Schema Generation

## Two Categories of Slices

### 1. Extension Slices (current behavior - keep as-is)
When `extension` or `modifierExtension` is sliced, slices are promoted as sibling keys on the parent map. This is the ergonomic representation for named extensions.

```clojure
;; Patient.extension:race -> :race as sibling to :extension
(mu/assoc :race [:ref :us-core-race])
```

### 2. Non-Extension Slices (new behavior)

All non-extension slices use malli `:multi` with a dispatch function derived from the FHIR slicing discriminator. Single discriminator returns a scalar dispatch value; multiple discriminators return a vector.

#### Single slice, single discriminator (e.g., BMI's `coding:BMICode`)

```clojure
;; coding sliced by [code], single discriminator -> scalar dispatch
(mu/update :coding
  (fn [sch]
    [:sequential
     [:multi {:dispatch (fn [m] (:code m))}
      ["39156-5"
       (-> Coding/sch
           (m/schema options)
           (mu/update :system (fn [sch] [:enum {} "http://loinc.org"]))
           (mu/update :code (fn [sch] [:enum {} "39156-5"]))
           (mu/update-properties ...))]
      [:default
       (-> Coding/sch (m/schema options) ...)]]]))
```

#### Single slice, multiple discriminators (e.g., BMI's `coding:BMICode` with code+system)

```clojure
;; coding sliced by [code, system], multiple discriminators -> vector dispatch
(mu/update :coding
  (fn [sch]
    [:sequential
     [:multi {:dispatch (fn [m] [(:code m) (:system m)])}
      [["39156-5" "http://loinc.org"]
       (-> Coding/sch ...constrained...)]
      [:default
       (-> Coding/sch ...)]]]))
```

#### Multiple slices (e.g., BP's `component:SystolicBP` + `component:DiastolicBP`)

```clojure
;; component sliced by [code.coding.code, code.coding.system]
(mu/update :component
  (fn [sch]
    [:sequential
     [:multi {:dispatch (fn [m] [(get-in m [:code :coding 0 :code])
                                 (get-in m [:code :coding 0 :system])])}
      [["8480-6" "http://loinc.org"]
       (-> BackboneElement/sch ... SystolicBP-constraints ...)]
      [["8462-4" "http://loinc.org"]
       (-> BackboneElement/sch ... DiastolicBP-constraints ...)]
      [:default
       (-> BackboneElement/sch ... base-component-schema ...)]]]))
```

## Dispatch Function Generation

### FHIR Discriminator Types
- **`value`**: The most common. The discriminator path points to a field whose exact value distinguishes slices. The dispatch function navigates the path and returns the value.
- **`pattern`**: Similar to value but matches a pattern rather than exact value.
- **`type`**: Discriminates by the type of a polymorphic field (e.g., `value[x]`).
- **`profile`**: Discriminates by which profile the element conforms to.
- **`exists`**: Discriminates by whether a field exists or not.

### Path Navigation
The discriminator `path` is a simplified FHIRPath expression. For the `value` type:
- `code.coding.code` -> `(get-in m [:code :coding 0 :code])` (navigate through first element of sequential)
- `url` -> `(get m :url)`
- `code` -> `(get m :code)`

### Multiple Discriminators
When multiple discriminators are present, the dispatch function returns a vector of all discriminator values:
```clojure
;; discriminators: [{path: "code.coding.code"}, {path: "code.coding.system"}]
:dispatch (fn [m] [(get-in m [:code :coding 0 :code])
                   (get-in m [:code :coding 0 :system])])
```

### Dispatch Values
Extracted from the fixed values in each slice's sub-elements. For SystolicBP:
- `code.coding.code` = "8480-6" (from `fixedCode` on the slice's sub-element)
- `code.coding.system` = "http://loinc.org" (from `fixedUri`)
- Dispatch key: `["8480-6" "http://loinc.org"]`

### Slicing Rules and :default
- `rules: "open"` — add a `:default` entry using the base (unsliced) schema. Unmatched elements are allowed.
- `rules: "closed"` — no `:default` entry. Only the declared slices are valid.
- `rules: "openAtEnd"` — same as open for validation purposes (`:default` present), ordering is a serialization concern.

## Implementation Plan

### Phase 1 (done): Extension vs non-extension separation
- Extension slices keep existing promote-as-sibling behavior

### Phase 2 (next): All non-extension slices use :multi dispatch
1. Detect when a sequential field has multiple slices (slicing intro element has `:slicing`)
2. Collect all slices for that field during transduction
3. Extract discriminator paths from the slicing intro element
4. Extract dispatch values (fixed values) from each slice's sub-elements
5. Generate dispatch function from discriminator paths
6. Generate `:multi` schema with dispatch entries for each slice + `:default`
7. Each entry's schema is the constrained version of the base element

### Phase 3 (future): FHIRPath implementation
For complex discriminator paths, implement a basic FHIRPath evaluator that handles:
- Simple property navigation: `code` -> `:code`
- Dotted paths: `code.coding.code` -> nested `get-in`
- Array traversal: implicit first-element for sequential fields
- `resolve()` for reference following (profile discriminator type)
- `ofType()` for polymorphic field discrimination
