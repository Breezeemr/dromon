# Base FHIR R4 Inferno suite

A minimal, headless-friendly Inferno test suite (id `base_fhir_r4`, title
"Base FHIR R4 Server") that smoke-tests a bare FHIR R4 / R4B server:

- `base_fhir_r4_capability_statement` — `GET [base]/metadata` returns 200 and a
  `CapabilityStatement` (declared `fhirVersion` recorded, not asserted).
- `base_fhir_r4_patient_read` — `GET [base]/Patient/[id]` returns 200, a
  `Patient`, with matching id.
- `base_fhir_r4_patient_search` — `GET [base]/Patient?_id=[id]` returns 200 and
  a searchset `Bundle` containing that Patient.

Inputs: `url` (required), `access_token` (optional OAuth2 bearer), `patient_id`
(default `123`).

## Install into the Inferno test-kit

Inferno auto-loads every top-level `lib/*.rb` at boot. Copy both artifacts into
the test-kit clone's `lib/` and rebuild the image:

```bash
cp base_fhir_r4_test_kit.rb <inferno-test-kit>/lib/base_fhir_r4_test_kit.rb
cp -r base_fhir_r4        <inferno-test-kit>/lib/base_fhir_r4
cd <inferno-test-kit> && docker compose build inferno worker
```

The `require_relative 'base_fhir_r4/base_fhir_r4_test_suite'` path is relative,
so the same layout works both here and under `lib/`.

## Run headless (from the dromon worktree)

```bash
INFERNO_SUITE=base_fhir_r4 \
INFERNO_GROUPS=all \
INFERNO_INPUTS='url:https://fhir.local:3001/default/fhir access_token:{{token}}' \
bb inferno-test
```

The runner substitutes `{{token}}` with the client-credentials access token it
obtains from Ory Hydra. The report is written to
`target/inferno-report-base_fhir_r4.json`.
