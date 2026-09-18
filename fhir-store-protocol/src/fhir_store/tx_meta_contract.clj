(ns fhir-store.tx-meta-contract
  "The `ITxMetadataStore` obligations, executable.

   Rules 2 and 4 of that protocol -- persist on EVERY verb, thread the map
   into EVERY inner call of a bundle fan-out -- are the ones an implementation
   is most likely to satisfy in prose and miss in code, and the failure is
   silent: the store answers `tx-metadata-supported?` true, the writes
   succeed, and some of them name nobody. Nothing in dromon can detect that
   from outside, because only the implementation knows where it put the map.

   So an implementation proves it here instead. Hand this function a
   constructed store and a read-back accessor and it drives all five write
   verbs plus both bundle fan-outs, asserting the exact map comes back for the
   version each one wrote -- and that an unattributed write reads back as
   unattributed, which is the assertion that catches a store reporting a
   previous version's stamp.

   It lives in `src`, not `test`, precisely so an out-of-repo implementation
   -- fhir-store-datomic, and the delegating wrappers in front of it -- can
   require it. `clojure.test` ships with Clojure, so the dependency costs an
   implementor nothing.

   Usage:

     (deftest my-store-satisfies-the-tx-metadata-contract
       (check-tx-metadata-contract
        {:store    (my-store)
         :tenant-id \"default\"
         :recorded  (fn [store tenant-id resource-type id] ...)}))"
  (:require [clojure.test :refer [is testing]]
            [fhir-store.protocol :as db]))

(def default-stamp
  "A deliberately meaningless map. What a stamp SAYS is the host's business;
   what is under test is that whatever it says arrives intact."
  {:contract "tx-meta" :run 1})

(defn- post-entries
  [resource-type n]
  (mapv (fn [_] {:request {:method "POST" :url (name resource-type)}
                 :resource {:resourceType (name resource-type)}})
        (range n)))

(defn- written-ids
  [bundle-response]
  (into [] (keep #(get-in % [:resource :id])) (:entry bundle-response)))

(defn check-tx-metadata-contract
  "Assert that `store` honours `ITxMetadataStore` for `tenant-id`.

   Required keys:

     :store      a constructed store, satisfying ITxMetadataStore for
                 :tenant-id, writable and empty at :id.
     :tenant-id  the tenant to write under.
     :recorded   (fn [store tenant-id resource-type id] -> map-or-nil) giving
                 the metadata the store persisted for the resource's CURRENT
                 version. Reading the version's own record, not a sticky
                 field, is part of what is under test: a `recorded` that
                 returns an older version's stamp makes half of these
                 assertions hold vacuously.

   Optional: :resource-type (default :Patient), :id (the subject of the
   single-resource verbs), :stamp (the map to thread), :bundles? (false for a
   store with no bundle support).

   Call it inside a `deftest`; the assertions are ordinary `clojure.test`
   ones."
  [{:keys [store tenant-id recorded resource-type id stamp bundles?]
    :or   {resource-type :Patient
           id            "tx-meta-contract-subject"
           stamp         default-stamp
           bundles?      true}}]
  (let [opts     {db/tx-meta-key stamp}
        resource (fn [] {:resourceType (name resource-type) :id id})
        seen     (fn [rid] (recorded store tenant-id resource-type rid))]
    (testing "the store declares the capability for this tenant"
      (is (db/tx-metadata-store? store tenant-id)))

    (testing "create-resource stamps the version it writes"
      (db/create-resource store tenant-id resource-type id (resource) opts)
      (is (= stamp (seen id))))

    (testing "update-resource stamps the version it writes"
      (db/update-resource store tenant-id resource-type id
                          (assoc (resource) :active true) opts)
      (is (= stamp (seen id))))

    (testing "an unattributed write is unattributed: an earlier version's
              stamp must not carry forward onto it"
      (db/update-resource store tenant-id resource-type id (resource))
      (is (nil? (seen id))))

    (testing "delete-resource stamps the version it writes"
      (db/delete-resource store tenant-id resource-type id opts)
      (is (= stamp (seen id))))

    (when bundles?
      (testing "transact-transaction stamps every write the bundle opens"
        (let [ids (written-ids (db/transact-transaction
                                store tenant-id (post-entries resource-type 2) opts))]
          (is (= 2 (count ids)) "the response must report what it wrote")
          (is (every? #(= stamp (seen %)) ids))))

      (testing "transact-bundle stamps every entry of the fan-out -- the half
                a store that threads only the transaction path loses"
        (let [ids (written-ids (db/transact-bundle
                                store tenant-id (post-entries resource-type 3) opts))]
          (is (= 3 (count ids)))
          (is (every? #(= stamp (seen %)) ids))))

      (testing "a bundle carrying no metadata stamps nothing"
        (let [ids (written-ids (db/transact-bundle
                                store tenant-id (post-entries resource-type 2)))]
          (is (every? #(nil? (seen %)) ids)))))))
