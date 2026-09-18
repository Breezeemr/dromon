(ns server.tx-meta
  "The seam through which a host supplies WHO a write is attributed to.

   dromon does not know what attribution looks like. It owns WHICH writes
   carry a stamp; a host owns WHAT the stamp says. That is the same split as
   `server.narrative` (dromon owns WHEN a narrative is derived, the host owns
   WHAT it says), and the value here is likewise opaque: a map dromon threads
   into the store's `opts` under `:fhir/tx-meta` without ever reading inside
   it.

   The host injects `:fhir/tx-meta` on the request. NO INJECTED MAP MEANS NO
   ATTRIBUTION: the handler calls exactly the arities it called before this
   seam existed, so a standalone dromon -- and any out-of-repo store or
   partial `reify` that never grew the new arities -- is untouched until a
   host switches attribution on.

   WHY DROMON SHIPS NO MIDDLEWARE FOR IT, unlike `wrap-narrative`. The
   injection middlewares in `server.router` all sit OUTSIDE `::jwt-auth`
   (composition rules 7 and 8), where no identity exists yet. A dromon-shipped
   `wrap-tx-meta` could therefore only inject a constant. The host already has
   a middleware positioned where the answer is knowable -- inside jwt-auth,
   after the acting practitioner is resolved, outside the authorization
   middlewares so refusals are still recorded -- and that is where the
   `(assoc request :fhir/tx-meta ...)` belongs.

   FAIL CLOSED, WHICH INVERTS THE NARRATIVE SEAM. `server.narrative` is
   explicit that the cost of a host bug must be a missing narrative, never a
   failed write. Attribution is the opposite: prose can be regenerated from
   the resource at any time, an audit record cannot be reconstructed
   afterwards from anything on disk, and a clinical record that silently lost
   its attribution is exactly the \"the trail looks present and is incomplete\"
   failure this exists to prevent. So a request carrying attribution against a
   store that cannot persist it is REFUSED.

   THE MAP NEVER REACHES A LOG. It typically names a human; `fhir-store.trace`
   already keeps resource content out of spans for the same reason, and the
   point of this channel is that attribution belongs beside the data, not in
   stdout."
  (:require [fhir-store.protocol :as db]))

(defn of
  "The transaction metadata the host injected on `req`, or nil.

   Delegates to `fhir-store.protocol/tx-meta`, which is the same key in the
   same shape: one set of rules for what a valid stamp is, applied at the
   request boundary rather than three layers down. A malformed map is a host
   programming error and throws rather than being coerced or dropped --
   including an EMPTY one, which is an attribution naming nobody. Rejecting it
   HERE also means the answer does not depend on whether the store could have
   persisted it: a host bug reads as a host bug, not as a store that cannot."
  [req]
  (db/tx-meta req))

(defn- ensure-supported!
  "Throw unless `store` will actually persist metadata for `tenant-id`.

   Rendered by `wrap-fhir-exceptions` as a 500 OperationOutcome. This is the
   request-time half of the detectability story; `assert-supported!` is the
   boot-time half, and neither subsumes the other -- boot cannot cover a
   tenant created later, and `server.compartment` REPLACES `:fhir/store` per
   request, so the object asserted at boot is not the object the handler
   holds."
  [store tenant-id]
  (when-not (db/tx-metadata-store? store tenant-id)
    (throw (ex-info "store cannot persist transaction metadata"
                    {:fhir/status 500
                     :fhir/code   "exception"
                     :tenant-id   (str tenant-id)}))))

(defn write-opts
  "Store `opts` carrying this request's transaction metadata, or NIL when the
   host injected none.

   Bind this once per handler and pass it to every write branch, the way
   `server.handlers` binds the narrative at the `let` rather than at each
   `db/...` call: one edit then covers a branch added later. nil is the whole
   contract -- a handler that got nil back calls the arity it always called,
   and `merge`ing nil into an existing opts map leaves it identical.

   Also where the capability check runs, once per request rather than once per
   call site."
  [req]
  (when-let [m (of req)]
    (ensure-supported! (:fhir/store req) (-> req :path-params :tenant-id))
    {db/tx-meta-key m}))

(defn assert-supported!
  "Throw unless `store` persists transaction metadata for every tenant in
   `tenant-ids`. For a host to call at system start.

   A wiring mistake -- a delegating wrapper that forgot the protocol, a realm
   whose schema lacks the attributes -- otherwise surfaces as a refused
   clinical write far from its cause. Calling this at boot moves the discovery
   to the moment the mistake was made."
  [store tenant-ids]
  (doseq [tenant-id tenant-ids]
    (when-not (db/tx-metadata-store? store tenant-id)
      (throw (ex-info "store cannot persist transaction metadata"
                      {:tenant-id (str tenant-id)
                       :store     (str (type store))}))))
  nil)
