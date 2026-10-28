# Inherited slicings and slice counts

Design for two generator gaps measured on the QRDA work (qrda-definitions
docs/plan.md 5.3 and the "P5c stage B3" records):

- GAP 1: a derived profile that restates or adds a slice of a slicing its base
  declared gets an arm the inherited dispatch never selects.
- GAP 2: slice `min`/`max` are not compiled; the `:multi` only routes.

Every new behaviour is off by default (section 6). A caller that binds none of the
switches and hooks gets exactly origin/main 7eba52a's output: the FHIR packages fdm
also generates (r4b, uscore8) and ccda are byte-identical until a driver opts in, and
only the qrda-definitions driver does.

## 1. Measurements (2026-10-25, baseline generated trees and `ccda/sds`)

Slice sites in each set's own package(s) whose sliced element has no `slicing` in the
same differential (the "No pending-slicing" branch of `apply-non-extension-slice`),
classified against the ancestors' snapshots. No set restates `slicing` itself on an
element an ancestor sliced (0 sites), so that case gets a guard and no design.

| set | restated (root / nested) | added (root) | merge forms emitted | direct threading |
|---|---|---|---|---|
| default | 8 / 0 | 22 | 24 in 17 files | 6 (type:$this on `Observation.value`) |
| r21 | 1 / 0 | 26 | 27 in 19 files | 0 |
| r11 | 0 / 0 | 9 | 9 in 9 files | 0 |
| qrda1 | 21 / 3 | 178 | 194 in 45 files | 8 (3 QDMBasedQRDA nested, 5 type:$this) |
| qrda3 | 10 / 0 | 8 | 18 in 8 files | 0 |
| cms-qrda3 | 9 / 3 | 9 | 18 in 7 files | 3 (QRDACategoryIIIReportCMS nested) |

Keys today versus the inherited dispatch:

| case | inherited arm key | key the restated/added arm gets | selected? |
|---|---|---|---|
| profile, restated, nested (QDMBasedQRDA `structuredBody.component:patient-data-section`) | `.../PatientDataSection` | none: `(mu/update sch 0 (fn [inner] (mu/update inner :section ...)))` runs on the `:multi` and adds an arm keyed `:section` | never |
| profile, restated, root (MeasureSectionQDM `entry:measure-reference`) | `.../MeasureReference` | `.../EMeasureReferenceQDM` (the derived body's profile) | never |
| profile, restated without restating the profile (QRDA I AllergyIntolerance `author:author-participation`) | the parent's profile URL | `:author-participation` (slice-name fallback) | never |
| value, restated (default AllergyIntoleranceObservation `participant:consumable`) | `"CSM"` | `:consumable` | never |
| value tuple, restated (QRDA I AllergyIntolerance `participant:csm-manu`) | `["CSM" "MANU"]` | `["CSM" "MANU" "MMAT"]`: every fixed value below the slice, in differential order | never |
| profile, added (QRDA I DeviceOrder `participant:prf-entity-practitioner`) | n/a | `.../EntityPractitioner`, a URL the inherited hook table does not list | never |
| value, added (`templateId:<profile>`, [root extension]) | n/a | `[root extension]` because the differential states root first | yes, by luck of order |

So every restated arm and every hook-dispatched added arm (about 133 in QRDA I) is
dead; the templateId adds (17 default, 19 r21, 9 r11, 45 QRDA I) work only because
their fixed values appear in discriminator order.

Slicing groups that need counts (some slice `min >= 1`, or a numeric `max` on a
repeating element), own groups / inherited groups a derived differential touches:

| set | own: templateId + other | inherited: templateId + other |
|---|---|---|
| default | 210 + 75 | 17 + 4 (1 on a max-1 element) |
| r21 | 182 + 78 | 19 + 4 |
| r11 | 169 + 71 | 9 + 0 |
| qrda1 | 92 + 94 | 45 + 67 |
| qrda3 | 18 + 32 | 8 + 7 |
| cms-qrda3 | 0 + 2 | 7 + 6 |

Every templateId slicing has a 1..1 slice, so GAP 2 touches almost every profile file.

## 2. GAP 1 mechanism

1. Nothing about an inherited slicing reaches the derived walk except the emitted
   schema. `apply-non-extension-slice` finds no `:pending-slicing` and falls back to
   `as-sequential-multi` on `base-sch`.
2. At the root, `base-sch` is the live inherited `[:sequential [:multi ...]]`, so it
   emits `merge-slice-into-multi-form` keyed by `standalone-dispatch-value`. That key
   comes from the derived differential (first profile URL, else every fixed value,
   else the slice name), not from the parent's discriminators, so it is not the key
   `extract-dispatch-value` gave the inherited arm. The dispatch fn is the parent's;
   for a profile slicing it is the templateId table `cda-template-dispatch` built from
   the parent's slices, which cannot name a profile the parent did not list.
3. Below the root, `compute-element-patch` resolves `(:ref-kw field-info)` to the core
   type (`StructuredBody`), so `base-sch` is the core `[:sequential [:ref Component]]`,
   `as-sequential-multi` is nil and the body is threaded directly. At runtime that
   thread lands on the inherited `:multi` and adds a dead arm.

## 3. GAP 1 fix: rebuild the inherited group from a record

### 3.1 Slicing records

New dynamic var `*slicings*`: an atom of `{sliced-element-id record}`, the id being the
snapshot element id (`"ClinicalDocument.component.structuredBody.component"`, enclosing
slice names included). `definition->schema` binds it to
`(atom (or (:slicings base-entry) {}))` beside `*base-refs*` and stores `@*slicings*` on
the definition's schema-atom entry as `:slicings`. The derived walk already starts from
the base's entry, so records chain down a derivation like `:shape`. The key is the id
string because ids agree across a derivation, while `base-k` means something only
inside one acc level, and nested accs are discarded after `patch-with-sub-elements`.

`apply-regular-element` stores `:element-id (str/join "." main-path)` on the pending
group; `flush-pending-slicing` writes the record once the arm keys are settled:

```clojure
{:discriminators [...]      ; normalized, after expand-wildcard-exists
 :keep-idx [...] :rules "open" :field-is-sequential? true
 :multi? true               ; false when the group compiled no :multi
 :counted? false            ; true when the field was emitted as a counted :and (5.1)
 :element-sch <the element schema the dispatch paths were resolved against>
 :arms [{:slice-name "patient-data-section"
         :dispatch-value <raw, full arity, as extract-dispatch-value returned it>
         :profile-candidates [...] :exists-underivable? false
         :key ".../PatientDataSection"            ; the emitted :multi key
         :routable? true   ; false when :key is the slice-name keyword fallback (5.1)
         :collided? false  ; true when the last-wins dedupe dropped this slice's arm
         :min 1 :max "1"}]
 :raw-discriminators [...]  ; as declared, before expand-wildcard-exists
 :default? true}            ; the :multi has a :malli.core/default arm (rules not closed)
```

Records are written only while `*rebuild-inherited-slicings*` is on, so the FHIR packages
generated with the gates off carry no new entry keys. `:element-sch` is the fallback base
for a nested body (3.2), not the primary one.

### 3.2 Collecting restated and added slices

In `apply-non-extension-slice`, with no pending group but a record with `:multi? true`
for the sliced id, open a pending group of kind `:inherited` from the record (once per
element) and append the slice:

- restated (slice name in `:arms`): `:dispatch-value` is the record's raw value, so the
  arm key equals the inherited key by construction, tuples included (`["CSM" "MANU"]`).
  `:profile-candidates` is, per discriminator position, the derived body's type.profile
  list where the differential states one, else the record's.
- added: `extract-dispatch-value` against the record's discriminators, as for an own
  slice, so `[root extension]` comes out in discriminator order whatever order the
  differential uses.

Precedence: the definition's own pending group, then a record, then today's path. A
record is looked up by id before `as-sequential-multi` is consulted, so a live `:multi`
that a body walk meets inside a narrowed profile ref never takes the merge path when the
id has a record.

Not rebuilt, each reported to `*uncompiled-slices*` with the inherited arms left as they
are: a restated slice whose record arm is `:collided?` (its own arm was dropped by the
last-wins dedupe, so a replacement would overwrite the arm another slice won); an added
slice when the record is not `:default?` (FHIR forbids adding a slice to a closed
slicing); and an added slice in a group whose `:raw-discriminators` hold the `*` wildcard,
because `expand-wildcard-exists` depends on the whole slice set and an added slice can
change the expansion and with it every inherited key (only r21 ProblemSection and
AdvanceDirectivesSection use `*`; neither is derived with slices today).

The body is computed as today (`prepare-slice-context`) with `base-sch` set to the
inherited arm it constrains (the default arm for an added slice), so the generation-time
decisions the body makes (existing versus new child, deref of a ref, repeating or not)
are taken against the schema the forms run on. At the root that arm is read off the live
field by its key. Below the root the walk holds the core type (section 2, item 3), not the
live schema, so the arm is reached from the derived definition's live base schema (bound
beside `*slicings*`) by the record's element id: each segment is a key, a `:sequential`
steps through child 0, and a `name:slice` segment steps into the enclosing record's arm for
that slice. Only when that navigation fails is the record's `:element-sch` used, and the
site is reported. Using `:element-sch` alone would compute a nested body against the core
element while its forms run on an arm whose children the parent narrowed to profile refs.

A record with `:multi? false` (the base declined the dispatch) keeps today's direct
threading only on a non-repeating field (the type:$this groups on `Observation.value`,
where the slice's constraints do describe the one value). On a repeating field direct
threading applies one slice's constraints to every occurrence, a false rejection rather
than a dead arm, so the slice is reported instead (0 such sites in the six sets today,
section 1). A missing record (a base loaded from the classpath rather than generated in
this run) keeps today's behaviour.

Guard for nested non-slice restatements: `update-existing-child-schema` treats an
element as sliced when `(shape/sliced? field-info)` or a record exists. Below the root
there is no field-info, so today a restated sliced element with deeper constraints
threads `(mu/update sch 0 ...)` onto the inherited `:multi`; with the guard it emits
entry properties only, as the root already does.

### 3.3 Flushing an inherited group

`flush-pending-slicing` merges the arms in record order (restated bodies replace the
inherited ones in place), then the added slices in differential order: snapshot order,
which the CDA hook relies on for first-matching-slice. Then:

1. `make-dispatch-form` and `dispatch-arm-keys` run over the merged arms. With the
   record's `keep-idx` and distinct keys, the inherited keys come back unchanged and
   added arms get theirs.
2. With no added arm and no changed candidate list, the dispatch is the inherited one,
   read at runtime as `(:dispatch (m/properties multi))`; no hook code is re-emitted.
   Otherwise the rebuilt dispatch form is emitted.
3. If the hook declines the merged group, `keep-idx` changes, or keys collide: replace
   restated arms in place under their inherited keys with the inherited dispatch, and
   report every added slice to `*uncompiled-slices*` (section 6).

Emitted for QDMBasedQRDA, inside the existing `structuredBody` update (URLs shortened;
counts are section 5):

```clojure
(mu/update
 :component
 (fn [sch]
   (let [multi (-> (mu/get sch 0) (m/schema options) m/deref)  ; (mu/get (mu/get sch 0) 0) when :counted?
         arm (fn [k] (some (fn [[k' _ s]] (when (= k k') s)) (m/children multi)))
         dispatch <hook form: the MeasureSectionQDM, ReportingParametersSection and
                   PatientDataSectionQDM templateIds -> the three inherited keys>]
     [:and {:fhir/slice-counts [[".../MeasureSection" "measure-section" 1 1]
                                [".../ReportingParametersSection" "reporting-parameters-section" 1 1]
                                [".../PatientDataSection" "patient-data-section" 1 1]]}
      [:sequential
       [:multi {:dispatch dispatch}
        [".../MeasureSection"
         (-> (arm ".../MeasureSection") (mu/update :section (fn [sch] [:ref :...MeasureSectionQDM/v5-3-0-breeze-1])))]
        [".../ReportingParametersSection"
         (-> (arm ".../ReportingParametersSection") (mu/update :section (fn [sch] [:ref :...ReportingParametersSection/v5-3-0-breeze-1])))]
        [".../PatientDataSection"
         (-> (arm ".../PatientDataSection") (mu/update :section (fn [sch] [:ref :...PatientDataSectionQDM/v5-3-0-breeze-1])))]
        [:malli.core/default (arm :malli.core/default)]]]   ; only when the record is :default?
      <three count :fn children, 5.1>])))
```

Before: three successive `(mu/update :component (fn [sch] (mu/update sch 0 ...)))` that
add an arm keyed `:section`. After: the patient data section is validated against
PatientDataSectionQDM, so the QDM entry profiles apply. A section carrying only the base
PatientDataSection templateId reaches the default arm and the 1..1 count reports it.

The rebuilt group writes its own record (same keys, merged arms, the derived snapshot's
cardinalities), so a grandchild rebuilds from the merged set.

`as-sequential-multi`, `merge-slice-into-multi-form` and `standalone-dispatch-value`
remain for the no-record and gate-off paths only.

## 4. GAP 2 mechanism

`prepare-slice-context` derives `:optional` from the slice `min` into `type-props`, but
`apply-non-extension-slice` never uses `final-props`, and the flushed `:multi` has no
place for a count. The element's own `min` compiles as entry presence through
`*element-min*`; nothing counts per slice. ccda README (R1.1 "Not done") and r11 OPEN-6
pin this as K2.

## 5. GAP 2 fix: counted sequences

### 5.1 Schema shape

A sliced repeating field with at least one counted slice becomes

```clojure
(let [dispatch <dispatch form>]
  [:and {:fhir/slice-counts [[<key> <slice-name> <min> <max-or-nil>] ...]}
   [:sequential [:multi {:dispatch dispatch} <arms> [:malli.core/default ...]]]
   [:fn {:fhir/slice-name "patient-data-section"
         :fhir/slice-id "ClinicalDocument.component.structuredBody.component:patient-data-section"
         :min 1 :max 1
         :error/message "slice patient-data-section: expected 1..1 occurrences"}
    (fn [xs] (or (not (sequential? xs))
                 (<= 1 (get (frequencies (map dispatch xs)) ".../PatientDataSection" 0) 1)))]
   ...])
```

- One `:fn` per counted slice, so `m/explain` reports each failing slice on its own,
  with `:in` the sliced field's value path (`[:component :structuredBody :component]`)
  and the slice name and element id in the `:fn` properties. The id is what the harness
  can look up (`slice-confs` reads snapshots by id); a name alone is ambiguous across
  nested slicings. Measured on malli 0.20.2: the `:and` explainer runs every child, so an
  arm error and a count error on the same list are both reported; a count error has
  `:type` nil and `:path` ending in the `:fn`'s index. A non-sequential value is left to
  the `:sequential` child, which already reports it.
- A slice is counted when `min > 0` or `max` is numeric (`"0"` included). Cardinalities
  come from the snapshot (`*element-min*` and a new sibling `*element-max*`, keyed the
  same way), then the differential, then the record. An occurrence belongs to a slice
  when the dispatch returns that slice's key; default-arm occurrences count for none.
- The `:sequential` is always child 0, so `(mu/get counted 0)` is the sequence.
- When some slice has `min >= 1` the entry also gets `{:optional false}`: an absent key
  would otherwise escape every count.

Not counted, and reported to `*uncompiled-slices*` with the reason: a non-repeating
field (`field-is-sequential?` false, e.g. type slices of `Observation.value`), a group
without a `:multi`, a key two slices share after the last-wins dedupe, and a slice whose
key the dispatch can never return (`:routable? false`: `finalize-dispatch-value`
substituted the slice-name keyword because the slice states no value at a kept
discriminator path). The last one is not hypothetical: r21 MedicationActivity
`effectiveTime:duration` is 1..1, keyed `:duration` under `(fn [m] (get-in m [:operator]))`;
a duration carries no operator, so counting it would reject every R2.1 MedicationActivity.
Its arm is dead today and stays dead; the count would turn that into a false rejection.
(Default PolicyActivity `entryRelationship:authorization` has the same key shape but is
0..*, so it is not counted anyway. The 72 keyword-keyed merged arms in QRDA I are restated
arms, which GAP 1 gives their inherited keys.)

Count semantics against FHIR. Open slicing: occurrences that match no slice reach the
default arm and count for no slice, so they are allowed. Closed: there is no default arm
(`flush-pending-slicing` omits it), so the `:multi` already rejects them and the counts
are unchanged. `openAtEnd` is treated as open and `ordered` is not enforced (unchanged
from today; reported nowhere, stated here). The sliced element's own `min > 1` and
numeric `max` (all occurrences, slices and others together) stay uncounted, as in
section 9. Extension slices are out of scope: `apply-extension-slice` makes each a key of
its own, and no record or count applies to `:extension`/`:modifierExtension`. Routing and
counting share one dispatch value, so they agree in every view. The wire view holds
lexical strings; no value-discriminated arm in the six sets has a non-string key (the only
boolean keys are exists keys, computed by `some?`), so the lexical view routes and counts
as the decoded one does.

Entry slice with `min 1` (MeasureSectionQDM `Section.entry:measure-reference`, 1..*):
today `[:sequential [:multi {:dispatch <MeasureSection's table>} [".../MeasureReference" ...] [:malli.core/default ...]]]`
plus a dead merged arm `".../EMeasureReferenceQDM"`; after, the rebuilt `:multi` (arm
`".../MeasureReference"` constrained to EMeasureReferenceQDM, dispatch table naming the
EMeasureReferenceQDM templateId) inside
`[:and {:fhir/slice-counts [[".../MeasureReference" "measure-reference" 1 nil]]} ... [:fn ... (<= 1 n)]]`.

### 5.2 fdm navigation through a counted field

One helper, `counted-sequential`, returns the `:sequential` of a counted `:and`
(recognized by `:fhir/slice-counts`), else nil. Used by `unwrap-sequential`,
`as-sequential-multi`, `discriminator-path->get-in-path` (a path through a counted
field gets its `0` index), `update-existing-child-schema` (`is-seq?`; a counted field is
sliced, so the 3.2 guard stops it before any `(mu/update sch 0 ...)`) and
`apply-regular-element` (`field-is-sequential?`). Also `apply-non-extension-slice`'s
`is-base-seq?` (`(m/type base-sch)` is `:and`) and `merge-slice-into-multi-form`, whose
`(mu/get sch 0)` returns the `:sequential` of a counted field and would wrap it as the
default arm of a new `:multi`. Rather than teach the no-record path about counted fields,
`*compile-slice-counts*` requires `*rebuild-inherited-slicings*` (an `ex-info` at
`definition->schema` when only the first is bound), and the no-record path never meets a
counted field because a counted field always has a record. The emitted unwrap is chosen
from the record's `:counted?` at generation time, never by a runtime type check, as
`field-is-sequential?` already is.

### 5.3 Consumers

| consumer | today, given a counted field | change |
|---|---|---|
| ccda `xml.clj` | `repeats?` is false and `element-schema` returns the `:and`, which `compile-node` reads as a primitive: the field's children become unknowns, and the parse holds one map where the schema wants a list | `repeats?` and `element-schema` look through an `:and` carrying `:fhir/slice-counts` to child 0 (the same rule as fdm's `counted-sequential`, restated in a few lines; ccda does not call fdm internals) |
| roundtrip2 `wire_view.clj` | the `:fn` falls to `lexical` and becomes `[:string]`, so every list fails | `rewrite` keeps `:fn` as `(m/form s)`; the dispatch already reads lexical strings (namespace doc) |
| roundtrip2 `cda_schema.clj` `required-template-ids` | `val-sch` is the entry's `::m/val`; its first child becomes the `:and`, whose first child is the `:sequential`, not the `:multi`: nil for every profile, so fc `envelope` emits no profile templateId and every fc document fails G1 | unwrap a counted `:and` first |
| roundtrip2 `explain.clj` `schema-label` | a count error's schema is the `:fn`; `(brief (m/form sch))` prints the fn object (`#object[...$fn__4762 0x6d420cdd ...]`, measured), so `mismatch-kind` buckets change from run to run | label a `:fn` carrying `:fhir/slice-id` by that id |
| ccda `dispatch.clj` | reads StructureDefinitions, not schemas | none |
| qrda-definitions `compare.clj` | count errors arrive at the field's `:in`; `slice-confs` sees a vector there, matches no slice, and attributes them to the sliced element's CONFs only. `malli-findings` keeps only `:in :path :type`, so the `:fn` properties are lost before attribution | follow-up: keep `(:fhir/slice-id (m/properties (:schema e)))` and add that element's CONFs. The F slice-count and inherited-multi rules of `classify` state "not compiled" as a premise; narrow them to slices `*uncompiled-slices*` reports (or remove them) rather than leave them matching. A missing field (`{:optional false}`) already reaches the min-1-slices branch of `slice-confs` |

GAP 1 alone changes no schema type, so it needs no consumer change: the parser folds
the rebuilt arms (the QDM sections' slots join the union, the `:section` arm goes away)
and the wire view rewrites the arms as before. GAP 2's `{:optional false}` also changes
entry properties that tests read (roundtrip2 `cf/encounters_test` reads every entry's
`:optional`).

## 6. Gates and diagnostics

- `*rebuild-inherited-slicings*` (GAP 1) and `*compile-slice-counts*` (GAP 2), false by
  default; the qrda-definitions driver binds both true. Every other new var is nil by
  default (`*discriminator-claims-fn*`, `*count-soft-discriminator-fn*`,
  `*uncompiled-slices*`, `*slicings*`, `*live-base-sch*`); the multi-profile union
  (section 10) hangs off the rebuild switch. With nothing bound, output equals
  origin/main's, pinned by `unbound-switches-reproduce-origin-main-test` against forms
  printed by 7eba52a itself, and by the ccda regeneration of "ccda unaffected".
- `*uncompiled-slices*`: an optional atom like `*suppressed-narrowings*`, collecting
  `{:definition :element :slice :reason}` for added slices the fallback could not route,
  restated slices not rebuilt (3.2) and slices left uncounted (5.1). ccda prints the count
  per set beside the unresolved and withheld lines, after `distinct` as it does for those
  (retries and re-sweeps report a site again), so a change in scope shows in the run output.
- `*compile-slice-counts*` without `*rebuild-inherited-slicings*` is refused (5.2).
- Land GAP 1 first (diff limited to the section 1 sites), then GAP 2 (diff in nearly
  every file), each measured on its own.

## 7. Expected effect per ccda set

| set | GAP 1 | GAP 2 |
|---|---|---|
| default | 24 merge sites in 17 files become rebuilds; 17 templateId adds keep their keys (form change only); 2 restated (`informant:non-provider`, `participant:consumable`) and 5 profile adds (BasicOccupationObservation, LongitudinalCareWoundObservation) become reachable; 6 type:$this sites unchanged | about 300 groups, nearly every file |
| r21 | 27 sites in 19 files; 19 templateId adds form only; 6 profile adds, 1 exists add (CarePlan `authenticator2`) and 1 restated exists slice become reachable | about 280 groups |
| r11 | 9 templateId adds (scalar root keys), form only, no behaviour change | about 250 groups; OPEN-6 rows 9 (a), 11, 13, 14 become detected |
| qrda1 | 194 merges and 3 nested sites become rebuilds; 24 restated and about 133 hook-dispatched adds become reachable; 5 type:$this unchanged | about 300 groups |
| qrda3 | 18 sites, 10 of them restated | about 65 groups |
| cms-qrda3 | 18 merges and 3 nested (QRDACategoryIIIReportCMS sections) | about 15 groups |

Plan sizes, outputs, 0 unresolved, 0 failures and unique dispatch roots must be
unchanged in all six runs. The withheld count may move: a slice body that today is
computed against the `:multi` (where no child is found and every child is created new)
is now computed against the arm, so `honor-at-use-site?` sees real children; each moved
site is listed and explained, not accepted as noise. Only file contents change otherwise.

## 8. Test plan

### fdm (synthetic StructureDefinitions in `test/`, no HL7 files)

Extract the bindings `definition->schema` establishes into one fn, so a test runs a base
and a derived synthetic logical model through `structure-definition->patch` and evals the
forms against the compiled base (the `nested-min-scenario` pattern). A stub hook maps one
fake templateId per profile URL, as in `profile-dispatch-requires-a-driver-hook-test`.

1. Restated profile slice two levels down (QDMBasedQRDA shape): keys are exactly the
   inherited keys plus the default, no `:section` arm; an instance claiming the narrowed
   profile reaches its arm and fails there under `[.. :component i :section]`.
2. Restated profile slice at the root that narrows the profile (MeasureSectionQDM shape):
   inherited key kept, rebuilt table routes the narrower templateId to it.
3. Restated value tuple slice with an extra fixed value below (csm-manu shape): key
   `["CSM" "MANU"]`, the extra fixed value enforced.
4. Added profile slice at the root: the rebuilt dispatch routes it; the inherited arm
   still routes.
5. Added `[root extension]` slice whose differential states extension first: key in
   discriminator order.
6. Hook declines the merged group: restated arm replaced in place under its inherited
   key, added slice reported.
7. Grandchild of a rebuilt group restates again: rebuilt from the merged record.
8. No record, and gates off: forms equal today's (golden from `merge-slice-into-multi-form`).
9. Counts on an own slicing (1..1, 0..1, 0..*, max 0): zero and two occurrences each give
   one error with `:in` the field path, `:fhir/slice-name` and `:fhir/slice-id`;
   default-arm items are not counted; `{:optional false}` added. A closed slicing: an
   unmatched occurrence is rejected by the `:multi` and the counts are unchanged.
10. Counts re-emitted by a rebuild from the derived snapshot (0..1 narrowed to 1..1).
11. Non-repeating field and collided keys: no `:and`, reported.
12. Navigation: a nested non-slice restatement of a counted field emits only entry props;
    `unwrap-sequential` and `discriminator-path->get-in-path` see through it.
13. Unroutable key (MedicationActivity `effectiveTime:duration` shape: a 1..1 slice with no
    value at the value discriminator's path): not counted, reported, and an instance whose
    occurrence lacks the discriminating value still validates.
14. Nested restated slice whose inherited arm narrowed a child to a profile ref and whose
    derived body constrains below that child: the body is computed against the live arm
    (navigation of 3.2), the forms load, and the constraint applies.
15. Not rebuilt (3.2): a restated collided slice, an added slice on a closed slicing, an
    added slice on a `*` group; each reported, inherited arms untouched.
16. Gate coupling: binding only `*compile-slice-counts*` throws.

Measure the baseline count of `clojure -M:test -m cognitect.test-runner -d test` first;
every existing test stays green (gates off where a test pins today's forms).

### ccda and downstream

- Regenerate the six sets and dispatch indexes one JVM at a time; compare with section 7.
- QRDA I: QDMBasedQRDA rejects a patient data section carrying the QDM templateId with an
  invalid QDM entry (error under the section), and a document without that section (count
  error at `[:component :structuredBody :component]`).
- r11 OPEN-6: the three `g1-gap-slice-counts-are-not-compiled-*` tests flip to the
  measured truth (gap instances rejected, error `:in` the field, slice named), renamed,
  with a measurement comment; `sequential-multi` unwraps the counted `:and`. Update README
  "Not done", OPEN-6's record and 9.8 rows 9, 11, 13, 14; rows 10 and 12 stay. OPEN-6 is a
  recorded user decision (2026-10-16: no generated cardinality check); its record states
  that GAP 2 supersedes it and when, rather than being rewritten as if it had always said so.
- Shape assertions to revisit: `template_dispatch_test`, `decor_slicing_test`, roundtrip2
  `r11_gate_test`, `r11_verdicts_test` and `cf/encounters_test` (entry `:optional`).
- XML: parse then unparse one corpus document per set, byte-identical to before.
- roundtrip2 G1 corpus runs (ONC and main): examine every pass-to-fail; a real missing
  slice is expected, a routing miss is a bug.
- qrda-definitions `-M:test`. `-M:compare-check` regenerates every triage file and
  compares it with the committed one, so after either gap it reports DIFFERENT by design:
  rewrite each with `clojure -M:compare <spec> <set>` (hand-set classes are kept by
  `triage-text`), review the diff (entries that turn `:agree` vanish; F slice-count and
  inherited-multi entries that remain must match `*uncompiled-slices*`), then run
  `-M:compare-check` to confirm it is stable, and recount README "Classes".

## 9. Risks

- Rebuilt tables map the narrowed profiles' templateIds to the inherited keys. A
  base-only occurrence then reaches the default arm instead of the slice arm: the count
  reports it, but the error moves from inside the arm to the field. That matches FHIR (a
  profile discriminator is judged against the derived slice's profile).
- Every templateId slicing is `[root extension]` with a 1..1 slice, so a document whose
  templateId carries the right root and another extension (an older template version)
  passes today and fails the count after GAP 2. That is a real missing slice, and it is
  expected to be the largest pass-to-fail class in the roundtrip2 corpus runs; it also
  makes `required-template-ids` load-bearing for fc output.
- Counts make `relax-section-entries!` load-bearing: it sets the entry slices to min 0 as
  well as `Section.entry`, which is what keeps a nullFlavor section valid once slice
  minimums count. A derived section in another package restating an entry slice's min 1
  is not relaxed by it.
- Count errors carry no slice segment in `:in`; the harness has to read the slice id
  from the `:fn` properties or it attributes them to the sliced element.
- Cost: each counted slice runs the dispatch over the list, so k counted slices on n
  occurrences cost k*n dispatch calls; k is small (templateId 1-3, sections up to ~10).
- Records live in the schema atom and hold an `:element-sch`. A base generated in another
  run has none and keeps today's dead arms; that case is silent unless the no-record path
  also reports a slice merged into a live `:multi` (it should, with reason `:no-record`).
  `sweep-until-stable!` compares only `:form`, so a record that changes without changing
  a form does not force another sweep; a record that changes a derived form does, through
  that form.
- A schema walker outside 5.3 (FHIR serde, fhir-store code) breaks on `:and`; hence the
  gates default to off.
- Not in scope: element `min > 1`, a `max` on a repeating element (`wrap-sequential-form`
  puts `{:max n}` on the item; QRDA I F max-on-repeating 37; done later behind its own
  switch, section 12) and a `max` on a string.

## Critique (adversarial review, 2026-10-25)

Checked against fdm at 7eba52a (`apply-non-extension-slice`, `flush-pending-slicing`,
`dispatch-arm-keys`, `prepare-slice-context`, `update-existing-child-schema`,
`definition->schema`), ccda `generate.clj` (`cda-template-dispatch`, `relax-section-entries!`,
`sweep-until-stable!`), the baseline generated trees, the source SDs under `ccda/sds`, and the
consumers named in 5.3, plus a malli 0.20.2 probe of a counted `:and` (explain paths, `mu/get`
of child 0, the printed form of a `:fn`). Confirmed as written: the section 1 merge counts
(24/17, 27/19, 9/9, 194/45, 18/8, 18/7), the QDMBasedQRDA dead `:section` arm, the
MeasureSectionQDM `EMeasureReferenceQDM` key, the csm-manu `["CSM" "MANU" "MMAT"]` key, the
nested core-type base, the hook's string-key contract (a restated arm keeping the inherited
URL as its key and passing the derived profiles as candidates is what the hook needs), the
`xml.clj` and `wire_view.clj` breakages, and that malli's `:and` explainer reports every
child. Changes made:

1. 5.1: counts exclude slices whose key the dispatch can never return (slice-name keyword
   fallback). Without this, r21 MedicationActivity `effectiveTime:duration` (1..1, keyed
   `:duration` under an `operator` dispatch) would reject every R2.1 MedicationActivity.
   Record field `:routable?` added (3.1), test 13 added.
2. 3.2: nested bodies are computed against the live inherited arm, reached from the
   derived definition's live base schema by element id, not against `:element-sch` (the
   core element). The forms run on the arm, whose children the parent may have narrowed to
   profile refs, so deciding update/assoc/deref against the core element can emit forms
   that do not fit. `:element-sch` stays as a reported fallback. Test 14 added.
3. 3.2: explicit precedence (own pending group, record, today's path) so a live `:multi`
   met inside a narrowed profile ref never takes the merge path when a record exists.
4. 3.2: not rebuilt, and reported: restated collided slices, added slices on a closed
   slicing (FHIR forbids them; the rebuilt form would also have read a default arm that
   does not exist, now marked in the 3.3 example), added slices on a `*` wildcard group
   (an addition can change the expansion and every key). Record fields `:collided?`,
   `:raw-discriminators`, `:default?` added. Test 15 added.
5. 3.2: `:multi? false` keeps direct threading only on a non-repeating field; on a
   repeating field it would apply one slice's constraints to every occurrence (a false
   rejection), so it reports instead (0 sites today).
6. 5.2: `*compile-slice-counts*` requires `*rebuild-inherited-slicings*`. The design left
   the no-record merge path (`merge-slice-into-multi-form`, `is-base-seq?`) meeting
   counted fields, where `(mu/get sch 0)` is the `:sequential` and would become the default
   arm of a new `:multi`. Test 16 added; section 6 updated.
7. 5.1: `:fn` properties carry `:fhir/slice-id` (the element id); the harness reads
   snapshots by id and a slice name is ambiguous. Measured explain shape stated (`:type`
   nil, every `:and` child reported).
8. 5.1: count semantics against FHIR made explicit: open (default arm, uncounted), closed
   (no default arm, `:multi` rejects), `openAtEnd`/`ordered` not enforced, element-level
   min > 1 and max uncounted, extension slices out of scope, wire-view lexical keys safe.
9. 5.3: `cda_schema.clj` row corrected (the entry is an `::m/val`, so the first child is
   the `:and`) and its consequence named (fc emits no profile templateIds); new row for
   roundtrip2 `explain.clj`, whose `schema-label` would print the `:fn` object and make the
   report buckets nondeterministic; `compare.clj` row corrected (`malli-findings` drops the
   schema before attribution; `classify`'s F rules carry a now-false premise);
   `cf/encounters_test` added for `{:optional false}`; `xml.clj` must not call fdm
   internals.
10. 6: `*uncompiled-slices*` is reported after `distinct`, as the unresolved and withheld
    lists are, and also covers slices not rebuilt.
11. 7: the withheld count may move (bodies now see real children), each moved site to be
    explained; the note claimed it unchanged.
12. 8: `-M:compare-check` fails by design after either gap; the triage files are rewritten
    with `-M:compare <spec> <set>` first. OPEN-6 is a recorded user decision; its record
    states that GAP 2 supersedes it.
13. 9: risks added for templateId version drift (expected largest pass-to-fail class) and
    for `relax-section-entries!` becoming load-bearing; the no-record path reports
    `:no-record`; record changes and `sweep-until-stable!`.
14. 2: "closed templateId table" reworded; the table is the parent's, not a closed slicing.

## Implementation record: GAP 1 (2026-10-25)

Implemented as designed in sections 3 and 6 (`*rebuild-inherited-slicings*`, `*slicings*`,
`*live-base-sch*`, `*uncompiled-slices*`; ccda `generate!` binds the gate and the report). Six
regenerations, 0 failures, plan sizes, outputs, withheld counts and dispatch indexes unchanged;
files changed 17, 19, 9, 45, 8 and 7 for default, r21, r11, qrda1, qrda3 and cms-qrda3, exactly
the section 7 counts. Where measurement departs from the design:

- A declined `:multi` on a repeating field is not a 0-site case. Eleven slices (qrda1 5:
  MedicationOrder and SubstanceRecommended `effectiveTime:pivl-ts-a`, `ivl-ts`, `eivl-ts-a`;
  default 6: TribalAffiliationObservation, BasicIndustryObservation and BasicOccupationObservation,
  each with `Observation.value` physical-quantity and coded; the old threading emitted no form
  for the six, so no default schema changes) sit in `type:$this` groups on a repeating field (CDA `effectiveTime` and
  `value` are 0..*), where the hook declined the `xsi:type` dispatch. They are reported as
  `:no-multi-repeating` and no longer threaded: the old threading applied the slice's
  constraints, for instance `operator` fixed to `A`, to every occurrence of the list. The
  section 1 table counted them as non-repeating.
- An `exists` slice added by a derived profile is declined when the differential is silent on
  a discriminator child's `min`, because `derive-exists-arm-key` reads the differential only:
  r21 CarePlan `authenticator2` and AllergyIntoleranceObservation `criticality` are reported
  `:dispatch-declined`, where section 7 expected them reachable. The snapshot states those
  minimums (`*element-min*` is keyed by the slice path plus the child), so reading it there
  would make both routable; that changes the own-group derivation too, so it is not done in
  GAP 1.
- Bodies are now computed against the inherited arm, so an added templateId slice also
  compiles its fixed `root` and `extension` pins and their `min 1` (before, the body found no
  child and compiled nothing). In r11 that removes nine `template-id-slice-descendant`
  exceptions (177 to 168).
- `*referencing-definition*` carries the definition's `:url`, so a report names its source
  without `*current-definition*`, which the ccda driver does not bind.
- When the hook declines the merged list, restated arms are still replaced in place under the
  inherited dispatch (section 3.3 item 3) and added slices are reported; a restated slice whose
  narrower profile the inherited table cannot route is reported `:restated-unroutable` beside
  them.

## Implementation record: GAP 2 (2026-10-25)

Implemented as designed in sections 4 to 6 (`*compile-slice-counts*`, `*element-max*`,
`counted-sequential`, `counted-arms`, `counted-form`), on top of GAP 1. Six regenerations (ccda
`generate!` binds both gates), 0 failures, plan sizes, outputs, withheld counts, unresolved 0 and
dispatch indexes unchanged; files changed 226 of 364, 199 of 220, 170 of 178, 137 of 144, 26 of 29
and 7 of 10 for default, r21, r11, qrda1, qrda3 and cms-qrda3. Where measurement departs from, or
adds to, the design:

- The count vector and the `:fn` properties carry the maximum as a number (`1`, nil for `*`), not
  the FHIR string; `slice-cardinality` reads the snapshot first (`*element-min*`, the new
  `*element-max*`), then the differential, so a tool that relaxes a bound in the snapshot only
  (ccda `relax-section-entries!`) is honored.
- The record gains `:counted?` and `:required?` (the `{:optional false}` entry property was already
  emitted by the base). A rebuild re-emits the whole field, unwrapping by the record's `:counted?` and
  wrapping by what the merged arms need now, so a 0..* slice narrowed to 1..* in a derived profile
  starts counting there (fdm test `slice-counts-start_at_a_derivation-test`). The fallback path of a
  declined dispatch also records and counts.
- Reasons reported to `*uncompiled-slices*`: `:count-no-multi`, `:count-non-repeating`,
  `:count-unroutable-key`, `:count-collided-key`. A rebuild reports only the slices it touched, so a
  derivation does not re-report its ancestors' uncounted slices. Per set (reports after `distinct`,
  with GAP 1's): default 25 (18 no-multi, 6 no-multi-repeating, 1 non-repeating), r21 14 (7 no-multi,
  4 collided-key, 2 dispatch-declined, 1 unroutable-key), r11 0, qrda1 17 (12 no-multi, 5
  no-multi-repeating), qrda3 0, cms-qrda3 0. The r21 MedicationActivity `effectiveTime:duration` is
  the unroutable key of section 5.1 and is not counted.
- Consumers changed: ccda `xml.clj` (`look-through-count`), roundtrip2 `wire_view.clj` (keeps a
  `:fn` that carries `:fhir/slice-id`), `cda_schema.clj` (`required-template-ids` unwraps a counted
  `:and`) and `explain.clj` (`schema-label`), and the qrda-definitions harness (`malli-findings` keeps the
  slice id; `slice-count-confs`; the F slice-count rules require a Schematron-only outcome).
- Measured on real documents: the roundtrip2 gate namespaces that run G1 on C-CDA R2.1 and HL7
  QRDA samples (`r11-verdicts-test`, `r11-gate-test`, `cda-schema-test`, `cf.encounters-test`,
  `r21-profile-set-test`) report 28 failures and 3 errors: templateId version drift (a document's
  templateId carries the right root and an extension other than the slice's, section 9, or a
  slice the R2.1 SD states with a root the document lacks, as `templateId1` of the R2.1 Allergy -
  Intolerance Observation), and pins that state "counts are not compiled". They are the corpus
  pass-to-fail class section 8 asks to be examined one by one; they are not examined here.

## Round 2: counts over matching occurrences (2026-10-25)

### The defect

Section 5.1 counted a slice's occurrences as the items for which the routing
dispatch returned the slice's key, `(= key (dispatch x))`. The dispatch answers
"which one arm validates this item" and returns one key per item. FHIR's count
is over the items that MATCH the slice, and an item may match several. In CDA an
occurrence claims every template it carries in `templateId`, and DECOR R1.1
states a section's entries-optional variant as a `1..1` slice beside the
entries-required variant (HL7's Discharge Summary 1 and HandP 1 samples carry
both templateIds). The dispatch credited such a section to the first slice in
slice order only, so the second slice reported a missing occurrence on a valid
document. Routing is unchanged; only the count changes.

### The match predicate

`make-matcher-form` emits, once per counted slicing, `matcher`: `(fn [m] #{slice-name ...})`,
the slices the occurrence `m` matches. The count `:fn` of a slice is then
`min <= (count (filter #(contains? (matcher %) slice-name) xs)) <= max`, and an
occurrence counts toward every slice it matches. A slice matches when every
discriminator the dispatch kept agrees with the slice's component of its arm
key; the components come from the same normalized discriminators and the same
`dispatch-component` the dispatch is built from, so the two cannot disagree on
what was kept. A nil component states nothing and agrees with any occurrence.

| discriminator | slice matches when |
|---|---|
| `value`, `pattern`, `$this` | some value of the occurrence at the discriminator path equals the slice's; every element of a repeating step is examined, where the dispatch reads the first |
| `exists` | the child's presence equals the slice's |
| `profile`, `type` | the occurrence claims the slice's profile through `*discriminator-claims-fn*`, else the dispatch's value equals the slice's |

`*discriminator-claims-fn*` is the companion of `*discriminator-dispatch-fn*`: the
same argument map, and it returns a form `(fn [m] #{dispatch-value ...})` of every
arm value the occurrence claims. nil by default, like `*count-soft-discriminator-fn*`;
only a driver that counts supplies them. The qrda-definitions driver's hook answers with the templateIds the
occurrence carries: an arm is claimed by an exact `[root extension]` pair, or by
its root when no arm names that root with the claimed extension (version drift,
as the dispatch absorbs it). The slice's own templateId pins reach the matcher
through the `value` discriminators on `root` and `extension` of a templateId
slicing, which are not hook-dispatched.

Consequences:

- A slice no longer needs a key of its own: two slices sharing a key (RiskConcernAct
  declares the same entryRelationship under two slice names) are both counted;
  `:count-collided-key` is gone. `:count-unroutable-key` stays: a slice that states no
  value at any kept discriminator would match everything.
- `:count-no-matcher` reports a counted slice whose matcher cannot be built (a kept
  discriminator the hook no longer answers on a rebuild).
- A rebuild (`flush-inherited-group`) rebuilds the matcher from the merged arms and
  the record's kept discriminators even when it keeps the inherited dispatch, because
  a cardinality narrowing can start a count; the claims hook is asked again.
- The explain error is unchanged: `:in` the sliced field's value path, the slice name
  and id in the `:fn` properties.

### Tests (fdm)

`slice-counts-count-every-matching-slice-test` (a repeating discriminated child, an
occurrence matching two `1..1` slices satisfies both, a second one exceeds both, an
occurrence matching neither is allowed by the open slicing);
`slice-counts-claims-hook-test` (a stub profile hook with a claims companion, and the
fallback to the dispatch value without one); the round-1 count tests pass, with the
collided-key expectations of `slice-counts-skip-what-the-dispatch-cannot-count-test`
changed to both slices being counted.

### What the match semantics exposed in C-CDA

Measured in an earlier experiment that bound counts for all six sets from ccda's
generator (since reverted: ccda is at its base and binds nothing). Real documents and
fc output met slice requirements that are defects of the published
StructureDefinitions rather than of the count: a pin on a template revision newer than
the release, a slice inherited through an inverted baseDefinition, a slice stated
`1..1` beside text that says MAY. Counting C-CDA sets again would need those corrected
in a patch layer of the driver (never by turning counts off).

## 10. Multi-profile slices (2026-10-26)

A slice-position element whose type lists several profiles used to compile to the
declared core type (`narrowed-child-profile-kw` never narrows an any-of list), so an
instance in the slice was held to none of the profiles. QRDA III has one site
(`PromotingInteroperabilityMeasureSection` `Section.entry:measure-reference.organizer`,
2 profiles); the C-CDA 5.0.0 package has seven (CarePlanAct, HealthConcernAct), up to 23
profiles.

Under `*rebuild-inherited-slicings*` and `*honor-child-type-profile*`, an element
that is a slice or hangs directly off a slice arm (a `:` in the last or the
penultimate id segment) whose type lists several resolvable profiles compiles to the
union of their schemas, own slice or restated alike (the restated body goes through
the same element patch):

- the driver's `*discriminator-dispatch-fn*` is called as for a `$this` profile
  discriminator (`:path nil`, one arm per profile, `:profiles [url]`). When it answers,
  the element is `[:multi {:dispatch <hook form> :fhir/profile-union true} [<url> [:ref
  <kw>]] ... [:malli.core/default [:or [:ref <kw>] ...]]]`: an instance is held to the
  profile its templateId names, and one that names none falls to the any-of default;
- when the hook declines (or none is bound), the element is the `[:or [:ref <kw>] ...]`
  alone.

`:fhir/profile-union` makes `type-union-multi?` true, so no walk merges a slice arm
into the union. Degradations keep the core type and are reported to
`*uncompiled-slices*`: `:multi-profile-unresolved` (a profile no package defines; a
union of the others would be narrower than any-of, and the profile also goes to
`*unresolved-profiles*`) and `:multi-profile-with-constraints` (the slice constrains
elements below it, which a union has no children to receive). A non-slice child with
several profiles is still never narrowed. Tests:
`multi-profile-slice-compiles-to-a-dispatched-union-test`,
`multi-profile-slice-falls-back-to-or-without-a-dispatch-test`,
`restated-multi-profile-slice-is-a-union-too-test`,
`multi-profile-slice-degradations-are-reported-test`.

## 11. ccda unaffected (proof, 2026-10-26)

Claim: with the defaults of this branch (`4bd1223` plus the working-tree changes of
sections 6 and 10), every set ccda generates is byte-identical to ccda's committed
output at superproject base 8612660924.

Method, all under qrda-definitions/target/ccda-proof/ (scratch, never in ccda):

1. `git -C <qrda-fdm worktree> archive 8612660924 ccda` extracted twice: `archive/ccda`
   (reference, untouched) and `ccda` (the working copy).
2. `dromon/` next to them holding two symlinks, `fhir-defintions-to-malli` and
   `fhir-primitives`, into this worktree, so the copy's `../dromon/...` roots resolve to
   the new fdm.
3. The gitignored input the R1.1 set needs, the pinned ART-DECOR file, copied from
   `~/claude-docs/ccda-r11-sources/art-decor-ccda/ccda-project.decor.xml` to
   `ccda/sources/art-decor/` (sha256 89fa9ad3957601b38db23e97796396b148ad9efd5ab3dce9cb8fc634b41e7ce0,
   the pin in `decor_source.clj`; the other inputs are committed package tarballs).
4. In `ccda/`, one JVM at a time: `clojure -M:generate default`, `clojure -M:generate r21`,
   `clojure -M:generate r11`. ccda's driver binds none of the new vars.
5. `diff -rq archive/ccda/<dir> ccda/<dir>` for `src`, `src-r21`, `src-r11` and `resources`,
   and `diff -rq archive/ccda ccda` for the whole tree minus the generated scratch
   (`sds`, `staging`, `sources`, `.cpcache`, `target`).

Measured:

| run | plan / process | output files | diff against the archive |
|---|---|---|---|
| default | 354 ok, 0 fail; unresolved 0; withheld 7 | `src` 364 | empty |
| r21 | 341 ok, 0 fail; unresolved 0; withheld 7 | `src-r21` 220 | empty |
| r11 | 299 ok, 0 fail; unresolved 0; withheld 0 | `src-r11` 178 | empty |

`resources` (the three schema indexes the runs rewrite and the three dispatch indexes they do not touch, 6 files) is also identical,
and so is the whole tree. Tree digest, `sha256sum` of every file of `src src-r21 src-r11
resources` in sorted order, then `sha256sum` of that list: both the archive and the
regenerated copy give
`9535e90c008da32c6841630bc9934f930525505468447262941c384e1872afd5` (768 files).
The real `ccda/` and `ccda-fhir-roundtrip2/` of the qrda-definitions worktree were never
run: `git diff 8612660924 HEAD -- ccda ccda-fhir-roundtrip2` and `git status --short --
ccda ccda-fhir-roundtrip2` are empty.

Re-run against the final commit `c580eae` (2026-10-26), because the run above predates the
last edit: the same method in a fresh scratch tree (`target/ccda-proof-final/`, `run/` and
`ref/` both from `git archive 8612660924 ccda`), plus `clojure -M:dispatch-index` for each
set. All six runs exit 0; process 354 / 341 / 299 ok, 0 fail, unresolved 0; `diff -r -q`
of the whole ccda tree, minus the gitignored `sds`, `staging`, `sources` and `.cpcache`,
reports no difference.

## 12. A narrowed max on a repeating field (2026-10-27)

**Mechanism.** A profile that narrows the `max` of a field the base already repeats (CDA
`Observation.value` is 0..*, nearly every QRDA observation states 1..1) goes through
`update-existing-child-schema`, which threads the element's patch onto the item:
`(mu/update sch 0 (fn [inner-sch] ...))`. `attr->value-schema-patch` adds the max to that
thread through `wrap-sequential-form`, so `(mu/update-properties merge {:max n})` lands on
the item: a map ignores it and a string reads it as a length. When the field info is absent
(below the root) it adds nothing at all. Either way a second occurrence passes.

**Fix, `*max-on-sequence*` (default false).** When true and the existing field repeats,
`update-existing-child-schema` takes a numeric `max` (not `*`, not `0`) off the element
before it builds the item patch, and appends
`(mu/update k (fn [sch] (mu/update-properties sch merge {:max n})))` after it, on the
`:sequential` itself, or on child 0 of a counted `:and` (`counted-sequential`). A sliced
field is not touched (its slices are counted by `*compile-slice-counts*`), nor is a new
field, which `wrap-sequential-form` already wraps as `[:sequential {:max n} ...]`.

**Tests.** `max-on-sequence-test`: a max of 1 and of 2 bound the sequence, a counted field is
bounded on child 0, `*` adds nothing, and with the switch off a second occurrence still
passes. `unbound-switches-reproduce-origin-main-test` checks the default. 58 tests, 362
assertions, 0 failures.

**ccda unaffected.** The method of section 11 with this commit: `git archive 8612660924 ccda`
twice (`run/`, `ref/`) in a scratch directory, `run/dromon/` symlinked to this worktree (the
cached classpath names it), the pinned ART-DECOR file copied in, then `clojure -M:generate` and
`clojure -M:dispatch-index` for `default`, `r21` and `r11`, one JVM at a time. All six exit 0;
process 354 / 341 / 299 ok, 0 fail. `diff -r -q` of the whole tree minus `sds`, `staging`,
`sources`, `.cpcache` and `target` is empty; the digest of `src src-r21 src-r11 resources`
(768 files) is the same for both copies.

**Effect on qrda-definitions** (which binds the switch): QRDA I / QRDA III / CMS 2027 QRDA III
mutants that agree 1,562 / 411 / 462 to 1,599 / 421 / 478. 63 of the 67 duplicates that only
the Schematron rejected are rejected by malli now, at the target CONF; the seeds are unchanged.
The 4 left duplicate a templateId slice the template's own schema rejects.

## 13. Scalar patterns and a profile on the slice root (2026-10-27)

Two switches, both default false, that let a template's own templateId reject another
version. qrda-definitions binds both.

**`*primitive-pattern-as-fixed*`.** `attr->value-schema-patch` compiles a `fixed*` value to an
`:enum` of one and leaves every `pattern*` value out. On a primitive type a pattern is the same
equality as a fixed value. The QRDA emitter states each template's own templateId the way
C-CDA 5.0.0 does, as `patternString` root and extension (532 of them in the three QRDA sets,
and no other pattern), so where a slicing discriminates by root alone a templateId with
another `@extension` passed. With the switch, a scalar pattern (not a map, so not
patternCodeableConcept or patternCoding) takes the fixed-value path; a fixed value still wins
when an element states both.

**`*honor-slice-root-profile*`.** `honor-at-use-site?` keeps a single-profile narrowing at an
optional element only for a child hanging off a slice arm (`slice-arm-child?`), the shape
C-CDA uses: the profile sits on `component:allergies.section`, never on the slice. QRDA states
it on the slice itself (`Observation.participant:facility-location` typed Facility Location,
`Observation.author:author` typed Author), and at `min` 0 with a profile that raises a child
requirement the gate fell back to the base type, so the arm the dispatch selects for the
template never applied it. With the switch, an element whose last id segment is a slice keeps
the narrowing: it is reached only through its slicing's dispatch, and every member of a slice
conforms to the slice's type profile. An unsliced optional child is gated as before.

**Tests.** `primitive-pattern-as-fixed-test`: a patternString compiles to an `:enum` that
rejects another value and leaves the element optional, a fixed value wins over a pattern, and
with the switch off another value passes. `child-type-profile-narrowing-test` gains the slice
root case: off it keeps the base type, on it refs the profile, and an unsliced optional child
keeps the base type either way. `unbound-switches-reproduce-origin-main-test` checks both
defaults. 59 tests, 375 assertions, 0 failures.

**ccda unaffected.** The method of section 11 with both switches in the tree: a fresh
`git archive 8612660924 ccda` copy (`run/`), `run/dromon/` symlinked to this worktree, the
pinned ART-DECOR file copied in, `clojure -Sforce -M:generate` and `clojure -M:dispatch-index`
for `default`, `r21` and `r11`, one JVM at a time, against the `ref/` copy of section 12. All six
exit 0; process 354 / 341 / 299 ok, 0 fail; the 7 AuthorParticipation narrowings C-CDA withholds
are still withheld. `diff -r -q` minus `sds`, `staging`, `sources`, `.cpcache` and `target` is
empty, and the digest of `src src-r21 src-r11 resources` (768 files) is the same for both copies.

**Effect on qrda-definitions** (which binds both): QRDA I / QRDA III / CMS 2027 QRDA III mutants
that agree 1,599 / 421 / 478 to 1,616 / 425 / 482; Schematron-only 83 / 12 / 23 to 66 / 8 / 19.
The seeds' findings are unchanged, and so are the false rejections over the Schematron-clean
mutants (24 / 2 / 6, every one a deleted templateId of the template itself).
