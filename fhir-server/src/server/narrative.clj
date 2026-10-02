(ns server.narrative
  "The seam through which a host supplies `Resource.text` derivation.

   dromon does not render clinical prose and does not know what a Condition
   should read like. It owns WHEN a narrative is derived; a host owns WHAT it
   says.

   The host injects `:fhir/narrative` on the request, exactly the way
   `:fhir/store` is injected (`server.router`). The value is a function of
   `[resource-type resource]` returning the resource, with or without a derived
   `:text`.

   NO INJECTED FUNCTION MEANS NO NARRATIVE. A standalone dromon behaves exactly
   as it did before this seam existed: `:text` absent rather than empty, and a
   client-supplied `:text` left exactly as posted. That is also the kill switch
   -- a host with a bad renderer stops injecting and writes keep working.

   WHAT DROMON KEEPS, and why it is not the host's to get right:
   - derivation runs against the FINAL body, after a PATCH is applied, so a
     client's RFC 6902 ops can never author their own narrative;
   - for bundles it runs AFTER `resolve-patch-entries`, so a PATCH entry
     rewritten into a guarded PUT is described as it will be stored, not as it
     was;
   - a throwing host function never turns a valid clinical write into a 500.

   ONE CONSTRAINT A HOST MUST KNOW: on a create the narrative is derived from
   the posted body, BEFORE the server mints the id, so a renderer cannot
   reference the resource's own id. `server.narrative-seam-test` pins this.

   PRESENTATION is the other half and does not touch storage. A host that
   shows a resource differently on a response than it stores it injects a
   `fhir-store.lifecycle/IReadLifecycle` as `:fhir/lifecycle` on the request
   (`server.router/wrap-lifecycle`). Every response that carries a stored
   resource hands it to the lifecycle once, with the read map naming the
   interaction: read and vread, instance/type/system history, type,
   compartment and system search (`_include`/`_revinclude` companions each
   under their own type), `$as-of`, `$timeline`, the bulk-export NDJSON
   stream, and the create, update, patch and transaction/batch responses.

   The reads the server makes for its own use are NEVER presented: the base a
   PATCH is applied to, the existence check before an upsert, the matches a
   conditional interaction selects on, and the bulk-export manifest counts.
   A presented PATCH base would be written back, persisting presentation as
   if the client had sent it. No injected lifecycle means responses carry
   exactly what the store returned."
  (:require [clojure.string :as str]
            [fhir-store.lifecycle :as lifecycle]
            [taoensso.telemere :as tel]))

(defn ensure-narrative
  "Apply `narrative-fn` to one resource, or return it IDENTICAL.

   Containment is here rather than in the host because the cost of a host bug
   must be a missing narrative, never a failed write. The logged event carries
   the resource TYPE and the exception message, never the resource itself --
   see \"keep resource content out of store and handler trace signals\".

   A silently absent narrative is the production symptom, so
   `:fhir/narrative-failed` is meant to be MONITORED, not merely logged."
  [narrative-fn resource-type resource]
  (if (and narrative-fn (string? resource-type) (map? resource))
    (try
      (or (narrative-fn resource-type resource) resource)
      (catch Throwable t
        (tel/error! {:id   :fhir/narrative-failed
                     :data {:resource-type resource-type
                            :error         (ex-message t)}}
                    t)
        resource))
    resource))

(defn- entry-resource-type
  "The type of a bundle entry's resource: its own `resourceType`, falling back
   to the first segment of the request URL, as for a PUT whose body omits it."
  [entry]
  (or (get-in entry [:resource :resourceType])
      (some-> (get-in entry [:request :url])
              (str/split #"/")
              first
              not-empty)))

(defn- read-context
  "The read map `fhir-store.lifecycle/present` receives for a response to
   `req`. `:interaction` is left out when the caller names none, which a host
   reads as `:read`."
  [req interaction resource-type]
  (cond-> {:tenant-id     (get-in req [:path-params :tenant-id])
           :resource-type (some-> resource-type name)
           :store         (:fhir/store req)
           :request       req}
    interaction (assoc :interaction interaction)))

(defn present-response
  "The resource to put on the response to `req` for `interaction` (a keyword
   such as `:read`, `:search-type` or `:bulk-export`; see THE READ MAP in
   `fhir-store.lifecycle`): the injected lifecycle's `present`, or `resource`
   unchanged when `req` carries no `:fhir/lifecycle`. The lifecycle filters
   its own resource types. A throwing lifecycle leaves the resource unchanged
   (see `fhir-store.lifecycle/present-resource`): a read must not 500 because
   presentation failed.

   The 3-arity names no interaction."
  ([req resource-type resource]
   (present-response req nil resource-type resource))
  ([req interaction resource-type resource]
   (if-let [lc (:fhir/lifecycle req)]
     (lifecycle/present-resource lc (read-context req interaction resource-type) resource)
     resource)))

(defn present-entries
  "Present the `:resource` of every bundle entry in `entries` for
   `interaction`, each under its OWN type: `_include`/`_revinclude`
   companions and system-level searches and histories mix types in one
   bundle, so the request's type would misname them.

   An entry without a resource passes through, and so does a `search.mode`
   \"outcome\" entry: that OperationOutcome is the server's own warning, not a
   stored resource."
  [req interaction entries]
  (if (and (:fhir/lifecycle req) (seq entries))
    (mapv (fn [entry]
            (if (and (map? (:resource entry))
                     (not= "outcome" (get-in entry [:search :mode])))
              (update entry :resource
                      #(present-response req interaction (entry-resource-type entry) %))
              entry))
          entries)
    entries))

(def ^:private presented-bundle-types
  "The bundle types whose entries carry stored resources."
  #{"transaction-response" "batch-response" "searchset" "history"})

(defn present-bundle-response
  "Present every entry resource of a transaction, batch, search or history
   response bundle to `req` for `interaction` (see `present-entries`). Any
   other bundle type is returned identical."
  [req interaction bundle]
  (if (and (:fhir/lifecycle req)
           (map? bundle)
           (presented-bundle-types (:type bundle))
           (seq (:entry bundle)))
    (update bundle :entry #(present-entries req interaction %))
    bundle))

(defn ensure-bundle-narrative
  "Apply `narrative-fn` to every POST/PUT entry carrying a `:resource`.

   DELETE/GET/HEAD entries pass through untouched. PATCH entries are expected to
   have been rewritten into guarded PUTs by `resolve-patch-entries` BEFORE this
   runs; calling it the other way round would describe the pre-patch body and
   nothing would fail."
  [narrative-fn entries]
  (if (and narrative-fn entries)
    (mapv (fn [entry]
            (let [method (some-> (get-in entry [:request :method]) str/upper-case)]
              (if (and (#{"POST" "PUT"} method) (map? (:resource entry)))
                (if-let [rt (entry-resource-type entry)]
                  (update entry :resource #(ensure-narrative narrative-fn rt %))
                  entry)
                entry)))
          entries)
    entries))
