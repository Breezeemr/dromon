(ns test-server.bulk-job-basis-test
  "A bulk export job pinned on one replica is streamed by another from its
   serialized form (server.bulk-job-store/job->edn, edn->job). The pinned basis
   must survive that trip exactly: xtdb2 reads `FOR SYSTEM_TIME AS OF` the
   basis :system-time, so a basis that lost precision would read a different
   snapshot. Lives here because test-server has fhir-server and the xtdb2 store
   on one classpath."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store.protocol :as db]
            [fhir-store-xtdb2.core :as xtdb2]
            [server.bulk-job-store :as bjs])
  (:import [java.time Instant]))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- job-around [basis]
  {:id "job-1" :tenant "basis" :kind :system :status :complete
   :basis basis :created-at 0 :finished-at 0})

(deftest an-xtdb2-basis-reads-the-same-snapshot-after-the-codec
  (let [store  (xtdb2/create-xtdb-store {})
        tenant "basis"]
    (try
      (db/create-resource store tenant :Patient "p1" {:resourceType "Patient" :id "p1"})
      (let [basis (db/current-basis store tenant)]
        (db/create-resource store tenant :Patient "p2" {:resourceType "Patient" :id "p2"})
        (db/create-resource store tenant :Patient "p3" {:resourceType "Patient" :id "p3"})
        (let [restored (:basis (bjs/edn->job (bjs/job->edn (job-around basis))))]
          (is (instance? Instant (:system-time basis)) "xtdb2's basis is the protocol shape")
          (is (= basis restored) "tx-id and the exact instant survive")
          (testing "the restored basis reads the pinned snapshot, not a later one"
            (is (= 1 (db/count-as-of store tenant :Patient restored)))
            (is (= ["p1"] (into [] (map :id) (db/scan-type-as-of store tenant :Patient restored)))))
          (testing "while the live count has moved on"
            (is (= 3 (db/count-as-of store tenant :Patient (db/current-basis store tenant)))))))
      (finally (close-store-nodes! store)))))
