# fhir-xml-serde

FHIR XML parse/unparse driven by the malli schemas generated from FHIR
StructureDefinitions by `fhir-defintions-to-malli`.

FHIR XML is a much narrower format than general XSD, so this is a direct schema
walk rather than a content-model compiler. Every child is a named element,
child order is fixed by the StructureDefinition, primitives carry their value in
a `value` attribute, and the only mixed content in a resource is the Narrative
`div`. There are no sequence groups, no choice particles, no `xsi:type`, and
exactly three attributes in the whole of R4B (`value`, `id`, `url`).

## Use

```clojure
(require '[com.breezeehr.fhir-xml :as fx])

;; resources resolves a resource type name to its schema; needed for the
;; polymorphic Resource slot (contained, Bundle.entry.resource, ...)
(defn resources [t]
  (some-> (requiring-resolve
           (symbol (str "org.hl7.fhir.StructureDefinition." t ".v4-3-0") "full-sch"))
          deref))

(def patient (resources "Patient"))
(def parse   (fx/parser patient resources))
(def unparse (fx/unparser patient resources))

(-> xml parse unparse)
```

## Wire shape vs typed shape

`parse` produces the **wire shape**: every primitive is the lexical string
exactly as it appeared in the `value` attribute. That is what makes round-trip
exact — `OffsetDateTime/toString` drops the `:00` seconds that FHIR requires and
normalizes `+00:00` to `Z`, and `BigDecimal` cannot tell `1.0e0` from `1.0`.

Typed values are a separate step, mirroring how the JSON side layers
`com.breezeehr.fhir-json-transform` over a wire-shaped value:

```clojure
(fx/decode-typed schema data)   ; strings -> booleans, BigDecimal, java.time
(fx/encode-wire  schema typed)  ; back to the wire shape for unparse
```

## The round-trip gate

The acceptance gate is that every published R4B example round-trips:

```bash
curl -sSLO https://hl7.org/fhir/R4B/examples.zip
unzip -q examples.zip -d /tmp/r4b-examples
FHIR_XML_EXAMPLES=/tmp/r4b-examples clj -M:gate
```

Current status: **1156 / 1156 round-trip clean.**

Equivalence is canonical, not byte-for-byte — the FHIR data model has no slot
for XML comments, and 358 of the examples contain them. `fhir-xml-canonical`
normalizes away the XML declaration, comments, processing instructions,
attribute order, self-closing vs empty-pair tags, namespace prefix choice, and
whitespace between FHIR elements. It compares exactly: element order, qualified
names, attribute values, and all text inside the narrative, where whitespace is
content. The single deliberate exception is that a decimal in exponent notation
compares via `BigDecimal`, so `1.0e0` equals `1.0` while `1.0` still differs
from `1.00`.

## Independent conformance check

The gate compares our output to the input through a comparator in this repo,
which on its own is circular. `dev/xsd_check.clj` closes that by validating the
XML we emit against HL7's own `fhir-all.xsd`:

```bash
curl -sSLO https://hl7.org/fhir/R4B/fhir-all-xsd.zip
unzip -q fhir-all-xsd.zip -d /tmp/fhir-xsd
FHIR_XML_EXAMPLES=/tmp/r4b-examples \
FHIR_XML_XSD=/tmp/fhir-xsd/fhir-all-xsd/fhir-all.xsd clj -M:xsd
```

Current status: **1154 / 1156 emitted documents validate.** The two exceptions,
`dataelements.xml` and `valuesets.xml`, do not validate in their published form
either — validating the originals produces byte-identical errors
(`DataRequirement.subject[x]` is not a legal `anyURI`; a `ValueSet.experimental`
out of schema order). We reproduce the input faithfully, defects included.

## Scope and known limits

- Verified against **base R4B**. The profile packages (uscore8, sdc,
  fhir-extensions, xver, davinci) now carry `:fhir/element-order` too, but
  their extension-slice promotion — where a profile lifts a sliced extension
  into its own map key — is not covered by the gate. Such keys are written
  after the elements the order vector names, rather than at the position of the
  `extension` element they belong to.
- Seven corpus files parse and round-trip cleanly but do not pass
  `m/validate` after `decode-typed`: `Timing.event` is a repeating `dateTime`
  used with extensions and no value, which FHIR represents as a null in the
  value array. The generated schema does not admit `nil` there. That is a
  generator nullability gap shared with the JSON path, not an XML issue.

## What the schemas must carry

FHIR XML requires children in StructureDefinition order, which malli entry
order does not preserve (it is alphabetical). The generator stamps the snapshot
order onto each map as `:fhir/element-order`, and this project reads it. With
that property ignored the gate scores 0/1156, so it is load-bearing.

Also consumed: `:xml/attr` (from `ElementDefinition.representation`, which in
R4B means exactly `Element.id` and `Extension.url`), `:fhir/primitive` (the
value goes in the `value` attribute; `"xhtml"` marks the narrative), and the
`:resourceType "Resource"` marker on the polymorphic Resource slot.

## Tests

```bash
clj -M:test
```
