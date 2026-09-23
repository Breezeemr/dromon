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
   (`server.router/wrap-lifecycle`). `present-response` and
   `present-bundle-response` hand it every resource a read, create, update,
   patch, or transaction/batch response returns; search, history and vread
   return the stored resource. No injected lifecycle means responses carry
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
  "The type a bundle entry writes: the resource's own `resourceType`, falling
   back to the first segment of the request URL for a PUT whose body omits it."
  [entry]
  (or (get-in entry [:resource :resourceType])
      (some-> (get-in entry [:request :url])
              (str/split #"/")
              first
              not-empty)))

(defn- read-context
  "The read map `fhir-store.lifecycle/present` receives for a response to
   `req`."
  [req resource-type]
  {:tenant-id     (get-in req [:path-params :tenant-id])
   :resource-type (some-> resource-type name)
   :store         (:fhir/store req)
   :request       req})

(defn present-response
  "The resource to put on the response to `req`: the injected lifecycle's
   `present`, or `resource` unchanged when `req` carries no `:fhir/lifecycle`.
   The lifecycle filters its own resource types. A throwing lifecycle leaves
   the resource unchanged (see `fhir-store.lifecycle/present-resource`): a
   read must not 500 because presentation failed."
  [req resource-type resource]
  (if-let [lc (:fhir/lifecycle req)]
    (lifecycle/present-resource lc (read-context req resource-type) resource)
    resource))

(defn present-bundle-response
  "Present every entry resource of a transaction or batch response to `req`.
   Other bundle types pass through, search included: those return the stored
   resource."
  [req bundle]
  (if (and (:fhir/lifecycle req)
           (map? bundle)
           (#{"transaction-response" "batch-response"} (:type bundle)))
    (update bundle :entry
            (fn [entries]
              (mapv (fn [entry]
                      (if (map? (:resource entry))
                        (update entry :resource
                                #(present-response req (:resourceType %) %))
                        entry))
                    entries)))
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
