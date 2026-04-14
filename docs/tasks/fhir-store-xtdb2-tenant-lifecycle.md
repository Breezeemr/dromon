# fhir-store-xtdb2 — Tenant Lifecycle Implementation

## Problem

`fhir-store-xtdb2` today creates tenants lazily via `get-or-create-node`
at `fhir-store-xtdb2/src/fhir_store_xtdb2/core.clj:591`, called from
every `IFHIRStore` method on the `XTDBStore` record. The effect in
practice (visible in today's OTel comparison run):

- First request against a tenant triggers a `store/node.start` span
  that takes ~2 493 ms per call.
- There is no way to pre-create, cleanly destroy, or warm up a tenant
  out of band.

The protocol-level tenant lifecycle methods are defined in
`fhir-store-tenant-lifecycle-protocol.md`. This task implements them in
the XTDB v2 backend.

## Approach

### create-tenant

```clojure
(create-tenant [this tenant-id]
  (create-tenant this tenant-id nil))

(create-tenant [this tenant-id opts]
  (t/trace!
    {:id :store/create-tenant
     :data {:tenant-id (str tenant-id) :opts opts}}
    (let [tid       (str tenant-id)
          if-exists (get opts :if-exists :error)
          existing  (contains? @(:nodes this) tid)]
      (cond
        (and existing (= :error if-exists))
        (throw (ex-info "Tenant already exists"
                        {:fhir/status 409 :fhir/code "conflict"
                         :tenant-id tid}))

        (and existing (= :replace if-exists))
        (do (fp/delete-tenant this tid {:if-absent :ignore
                                        :close-storage? true})
            (get-or-create-node this tid))

        :else
        (do (get-or-create-node this tid)
            nil)))))
```

This intentionally delegates to the existing `get-or-create-node`
helper so every code path goes through the same node-boot logic.
`get-or-create-node` already handles the swap!-race between concurrent
callers (see the comment at line 602).

### delete-tenant

```clojure
(delete-tenant [this tenant-id]
  (delete-tenant this tenant-id nil))

(delete-tenant [this tenant-id opts]
  (t/trace!
    {:id :store/delete-tenant
     :data {:tenant-id (str tenant-id) :opts opts}}
    (let [tid        (str tenant-id)
          if-absent  (get opts :if-absent :error)
          close?     (get opts :close-storage? false)
          existing   (get @(:nodes this) tid)]
      (cond
        (and (nil? existing) (= :error if-absent))
        (throw (ex-info "Tenant not found"
                        {:fhir/status 404 :fhir/code "not-found"
                         :tenant-id tid}))

        (some? existing)
        (do
          ;; Release the node first so no in-flight query holds a
          ;; handle to the backing storage when we drop it.
          (try (.close ^java.lang.AutoCloseable existing)
               (catch Throwable _ nil))
          (swap! (:nodes this) dissoc tid)
          (when (and close? (persistent-backend? (:node-config this)))
            ;; node-config for :xtdb2-disk contains a per-tenant base
            ;; path. Resolve, delete the directory tree, and leave no
            ;; trace. For in-memory xtdb2 nodes this branch is a
            ;; no-op.
            (delete-tenant-storage! this tid)))

        :else nil)
      nil)))
```

The `persistent-backend?` and `delete-tenant-storage!` helpers are
new; keep them private in `fhir-store-xtdb2.core`. The `node-config`
structure is already built up in
`test-server.core/store-presets :xtdb2-disk` and contains the
base path string — reuse that template to compute the per-tenant
directory.

### warmup-tenant

```clojure
(warmup-tenant [this tenant-id]
  (warmup-tenant this tenant-id nil))

(warmup-tenant [this tenant-id opts]
  (t/trace!
    {:id :store/warmup-tenant
     :data {:tenant-id (str tenant-id)}}
    (let [tid (str tenant-id)
          rts (or (:resource-types opts) #{:Patient})]
      ;; Force node creation + JIT of the search path.
      (get-or-create-node this tid)
      (doseq [rt rts]
        (fp/search this tid rt {"_count" "1"} {}))
      nil)))
```

The `search` call with `_count=1` exercises the full read pipeline:
XTDB SQL planning, the decoder chain, the malli read-decoders
registered on the `XTDBStore` record. Today's OTel data shows this
single hit absorbs the ~2 493 ms cold start; after the fix, that cost
is attributed to `store/warmup-tenant` instead of the first real
`store/search`.

## Refactoring opportunity

Once `create-tenant` and `warmup-tenant` exist, the other
`IFHIRStore` methods can drop their `(get-or-create-node …)` prelude
and instead assume the tenant is already created. The cleanest cut is
a two-step rollout:

1. **Step A:** add the new methods. Leave `get-or-create-node` calls
   in place in every other method. Zero behavioral change for
   callers that don't call `create-tenant`.
2. **Step B:** once the test-server seeder calls `create-tenant` at
   boot, remove the per-method `get-or-create-node` and replace with
   a plain `(get @(:nodes this) tid)` that errors on missing tenant.
   This is a strictly-more-strict API and may break existing callers
   that rely on implicit creation; do it only after the
   cross-service rollout in the protocol task's "call-site updates"
   section.

Do step A in this task. Step B gets its own follow-up.

## Verification

1. Unit tests in `fhir-store-xtdb2/test/fhir_store_xtdb2/core_test.clj`:
   - `create-tenant` then `read-resource` on a non-existent id returns
     `nil` (not an error). Proves creation succeeded.
   - `create-tenant` twice with default opts throws 409.
   - `create-tenant` twice with `{:if-exists :ignore}` is a no-op.
   - `delete-tenant` on a tenant that has resources, then `search`
     returns an empty Bundle.
   - `delete-tenant` with `{:close-storage? true}` against an
     `xtdb2-disk` config actually removes the on-disk directory.
   - `warmup-tenant` on a fresh store is idempotent and doesn't fail
     on the second call.

2. OTel shape check: after wiring the inferno runner warmup through
   `warmup-tenant`, Jaeger should show a `store/warmup-tenant` span
   during boot instead of the 2 493 ms spike under `store/node.start`
   inside the first `http/request`.

3. `bb inferno-test` still 505/505.

## Depends on

- `fhir-store-tenant-lifecycle-protocol.md` — protocol methods must be
  declared before any impl compiles.
