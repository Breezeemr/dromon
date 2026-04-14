# fhir-store-mock — Tenant Lifecycle Implementation

## Problem

`fhir-store-mock` at
`dromon/fhir-store-mock/src/fhir_store/mock/core.clj:82` is an atom-
backed in-memory store. It has no explicit tenant lifecycle today:
tenants appear in its `state` atom as a side effect of the first
write. For tests, that means:

- No way to pre-create a tenant so a read-before-write returns an
  empty Bundle instead of 404 on a missing tenant key.
- No way to reset a tenant mid-test without nuking the whole atom or
  doing per-key surgery.
- Warmup is a no-op on mock by definition (no I/O, no classloaders
  that aren't already loaded), but the method still needs to exist
  on the record for protocol parity with xtdb2 and datomic.

The protocol methods are defined in
`fhir-store-tenant-lifecycle-protocol.md`. This task implements them
on `MockStore`.

## Approach

The whole thing is a handful of atom swaps. Add the three methods to
the `MockStore` record body:

```clojure
(create-tenant [this tenant-id]
  (create-tenant this tenant-id nil))

(create-tenant [this tenant-id opts]
  (let [tid       (str tenant-id)
        if-exists (get opts :if-exists :error)]
    (swap! (:state this)
           (fn [s]
             (let [exists? (contains? s tid)]
               (cond
                 (and exists? (= :error if-exists))
                 (throw (ex-info "Tenant already exists"
                                 {:fhir/status 409 :fhir/code "conflict"
                                  :tenant-id tid}))

                 (and exists? (= :replace if-exists))
                 (assoc s tid {})

                 exists?
                 s

                 :else
                 (assoc s tid {})))))
    nil))

(delete-tenant [this tenant-id]
  (delete-tenant this tenant-id nil))

(delete-tenant [this tenant-id opts]
  (let [tid       (str tenant-id)
        if-absent (get opts :if-absent :error)]
    (swap! (:state this)
           (fn [s]
             (cond
               (and (not (contains? s tid)) (= :error if-absent))
               (throw (ex-info "Tenant not found"
                               {:fhir/status 404 :fhir/code "not-found"
                                :tenant-id tid}))
               :else (dissoc s tid))))
    nil))

(warmup-tenant [this tenant-id]
  (warmup-tenant this tenant-id nil))

(warmup-tenant [this tenant-id _opts]
  ;; Mock has no cold state worth warming. Ensure the tenant exists
  ;; so subsequent searches don't 404, then return.
  (swap! (:state this) update (str tenant-id) (fnil identity {}))
  nil)
```

Note that `:close-storage?` on `delete-tenant` is a no-op here — mock
has no persistent backend — but the option is still accepted so
callers can pass the same opts map to any store.

## Verification

1. Unit tests in
   `dromon/fhir-store-mock/test/fhir_store/mock/core_test.clj`:
   - `create-tenant` followed by `search` returns an empty Bundle.
   - `create-tenant` twice with default opts throws 409.
   - `create-tenant` twice with `{:if-exists :ignore}` is a no-op.
   - `create-tenant` with `{:if-exists :replace}` wipes prior data.
   - `delete-tenant` then `read-resource` returns nil/not-found.
   - `delete-tenant` with `{:if-absent :ignore}` is a no-op.
   - `warmup-tenant` on a new store creates the tenant key.
   - `warmup-tenant` on an existing tenant preserves prior data.

2. The existing handlers-test suite at
   `dromon/fhir-server/test/server/handlers_test.clj` still passes —
   most of its cases construct a fresh `MockStore` per test and rely
   on implicit tenant creation. With the new protocol in place,
   either the test helper or `create-resource` itself must continue
   to implicitly create tenants (see the protocol task's "Step A"
   note about keeping implicit creation in existing methods until
   callers are updated).

## Depends on

- Upstream: `fhir-store-tenant-lifecycle-protocol.md` — protocol
  methods must exist before this task compiles.
- Parallel: xtdb2 and datomic implementation tasks can land
  independently.
