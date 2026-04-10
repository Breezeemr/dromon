# XTDB v2 Search Implementation Findings

Accumulated knowledge from debugging FHIR search parameters against XTDB v2.

## XTDB v2 SQL Struct Access

**Correct syntax**: `("field")."subfield"` (parenthesized, double-quoted)

```sql
-- WORKS: returns the nested value, NULL when field is absent
SELECT ("period")."start" FROM Encounter

-- BROKEN: silently returns empty object {}
SELECT "period"."start" FROM Encounter

-- BROKEN: fails if `period` is a reserved keyword (parse error)
SELECT (period)."start" FROM Encounter
```

- `period` is a **reserved keyword** in XTDB v2 SQL. Must always be double-quoted.
- The dot notation `"a"."b"` is NOT struct field access in XTDB v2 — it silently returns `{}`.
- When a resource lacks the struct column entirely, `("field")."subfield"` returns NULL (no error), so WHERE clauses safely skip those rows.

## FHIR Date Search with Period Fields

The `date` search parameter may map to both DateTime and Period fields depending on the resource type (e.g., Encounter has `period`, Observation has `effectivePeriod` or `effectiveDateTime`).

### Prefix handling for Period fields

- **`gt`/`sa`**: `period.start > search_date` — the period starts after the search date
- **`lt`/`eb`**: `period.start < search_date` — the period starts before the search date
- **`ge`**: `period.start >= search_date`
- **`le`**: `period.end <= search_date`
- **`eq`** (default): Do NOT match against Period fields — only match against DateTime fields. Including Period matching on `eq` causes Inferno validation failures because returned resources have `effectivePeriod` when Inferno expects `effectiveDateTime`.

### Column naming

| Search param | FHIR field | XTDB column |
|---|---|---|
| `authored` | `QuestionnaireResponse.authored` | `authored` |
| `authored` | `ServiceRequest.authoredOn` | `authoredOn` |
| `asserted-date` | `Condition.recordedDate` | `recordedDate` |
| `abatement-date` | `Condition.abatementDateTime` | `abatementDateTime` |
| `period` | `DocumentReference.period` OR `DocumentReference.date` | `period` or `date` |

The `authored` search param must check **both** `authored` and `authoredOn` columns since different resource types use different field names for the same search param.

## Patient Reference Fields by Resource Type

Different FHIR resources store the patient reference in different fields. The `patient` search parameter must resolve to the correct column:

| Resource Type | Field | SQL |
|---|---|---|
| Most clinical resources | `subject` | `(subject).reference` |
| AllergyIntolerance, Immunization, RelatedPerson | `patient` | `(patient).reference` |
| Coverage | `beneficiary` | `(beneficiary).reference` |

Querying all three fields in a single OR clause fails silently on resources that lack the column (XTDB throws an error caught by the exception handler, returning empty results).

## Practitioner Search Param

The `practitioner` search param value may arrive with or without the `Practitioner/` prefix (e.g., `practitioner-1` or `Practitioner/practitioner-1`). Must check `str/starts-with?` before prepending to avoid double-prefixing like `Practitioner/Practitioner/practitioner-1`.

## Goal Description Search (UNRESOLVED)

`GET /Goal?description=43396009&patient=123` returns empty even though the Goal has `description.coding[].code = "43396009"`. The current SQL uses:
```sql
EXISTS (SELECT 1 FROM UNNEST((description).coding) AS c(val) WHERE (c.val).code = '43396009')
```
This may fail because XTDB v2 stores `description` as a nested struct and `UNNEST` on a struct's array subfield may not work as expected. Needs investigation with a live XTDB v2 instance.

## Condition asserted-date Search (UNRESOLVED)

`GET /Condition?patient=123&asserted-date=2024-01-15` — Inferno skips with "Could not find values for all search params". The Condition resources DO have `recordedDate` in their JSON (stored in `fhir_source`). But Inferno's FHIRPath extraction can't find the value. Possible causes:
- The server's Malli/Muuntaja response encoding strips `recordedDate` from the response
- Inferno's FHIRPath for `asserted-date` expects a specific extension in addition to `recordedDate`

## QuestionnaireResponse `_questionnaire` Extension (UNRESOLVED)

The `_questionnaire` FHIR primitive extension is stored correctly in `fhir_source` JSON and returned by the server. But Inferno reports "Could not find QuestionnaireResponse.questionnaire.extension:questionnaireDisplay". This might be:
- The extension URL used (`us-core-extension-questionnaire-uri`) may be wrong for the `questionnaireDisplay` slice
- The slice name `questionnaireDisplay` suggests it expects a display-type extension, not a URI extension
