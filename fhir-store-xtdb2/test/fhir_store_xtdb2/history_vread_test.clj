(ns fhir-store-xtdb2.history-vread-test
  "vread by versionId and the `_since` / `_at` window of type history, under
   both query modes. Both used to fail inside XTDB (a versionId read as a
   timestamp, `TIMESTAMP ?` refused by the parser), which the pool then
   reported only as \"Connection is closed\"."
  (:require [clojure.test :refer [deftest is testing]]
            [fhir-store-xtdb2.core :as core-db]
            [fhir-store.protocol :as db])
  (:import [java.time Instant ZoneOffset]
           [java.time.format DateTimeFormatter]))

(defn- close-store-nodes! [store]
  (doseq [[_ {:keys [node pool]}] @(:nodes store)]
    (when pool (.close pool))
    (.close node))
  (reset! (:nodes store) {}))

(defn- fhir-status-data
  "ex-data of the first exception in the cause chain carrying :fhir/status.
   Store spans rethrow wrapped, so the refusal sits on a cause."
  [^Throwable e]
  (loop [x e]
    (cond
      (nil? x) nil
      (:fhir/status (ex-data x)) (ex-data x)
      :else (recur (.getCause x)))))

(defn- refusal [f]
  (try (f) nil
       (catch Exception e (fhir-status-data e))))

(defn- versions [resources]
  (mapv #(get-in % [:meta :versionId]) resources))

(defn- last-updated ^Instant [resource]
  (Instant/parse (get-in resource [:meta :lastUpdated])))

(defn- write-three-versions!
  "Observation o1 at versions 1..3, a few ms apart so each has its own system
   time. Returns the three versions as read back by vread."
  [store tid]
  (db/create-resource store tid :Observation "o1" {:resourceType "Observation" :status "preliminary"})
  (Thread/sleep 5)
  (db/update-resource store tid :Observation "o1" {:resourceType "Observation" :status "final"})
  (Thread/sleep 5)
  (db/update-resource store tid :Observation "o1" {:resourceType "Observation" :status "amended"})
  (mapv #(db/vread-resource store tid :Observation "o1" %) ["1" "2" "3"]))

(defn- check-vread [store tid]
  (let [[v1 v2 v3] (write-three-versions! store tid)]
    (testing "each versionId reads back its own version"
      (is (= ["preliminary" "final" "amended"] (mapv :status [v1 v2 v3])))
      (is (= ["1" "2" "3"] (versions [v1 v2 v3])))
      (is (every? #(= "o1" (:id %)) [v1 v2 v3])))

    (testing "every version instance history lists reads back identically"
      (doseq [h (db/history store tid :Observation "o1")]
        (is (= h (db/vread-resource store tid :Observation "o1" (get-in h [:meta :versionId]))))))

    (testing "an integer versionId names the same version"
      (is (= v2 (db/vread-resource store tid :Observation "o1" 2))))

    (testing "an unknown version, an unknown id, and a timestamp all read nothing"
      (is (nil? (db/vread-resource store tid :Observation "o1" "9")))
      (is (nil? (db/vread-resource store tid :Observation "nope" "1")))
      (is (nil? (db/vread-resource store tid :Observation "o1" (str (last-updated v2))))
          "vread is not a point-in-time read; $as-of is"))

    (testing "a deleted resource keeps its versions"
      (db/delete-resource store tid :Observation "o1")
      (is (nil? (db/read-resource store tid :Observation "o1")))
      (is (= v3 (db/vread-resource store tid :Observation "o1" "3"))))))

(defn- check-history-window [store tid]
  (let [[v1 v2 v3] (write-three-versions! store tid)
        t2 (last-updated v2)
        t3 (last-updated v3)
        history (fn [params] (versions (db/history-type store tid :Observation params)))]
    (is (= ["3" "2" "1"] (history {})))

    (testing "_since keeps versions created at or after the instant"
      (is (= ["3" "2"] (history {"_since" (str t2)}))
          "a version's own lastUpdated includes that version")
      (is (= ["3" "2"] (history {:_since (str t2)})))
      (is (= ["3" "2"] (history {"_since" (.format DateTimeFormatter/ISO_OFFSET_DATE_TIME (.atOffset t2 (ZoneOffset/ofHours -5)))}))
          "the same instant at another offset")
      (is (= ["3" "2"] (history {"_since" t2})) "an Instant passes through")
      (is (= ["3"] (history {"_since" (str (.plusNanos t2 1000))})))
      (is (= ["3" "2" "1"] (history {"_since" "2000-01-01T00:00:00Z"})))
      (is (= [] (history {"_since" "2999-01-01T00:00:00Z"})))
      (is (= ["3" "2" "1"] (history {"_since" ""})) "a blank value is absent"))

    (testing "_at keeps versions current at some point during the period"
      (is (= ["2"] (history {"_at" (str t2)})) "an instant names one version")
      (is (= ["2"] (history {"_at" (str (.minusNanos t3 1000))}))
          "the version current just before the next one landed")
      (is (= ["3"] (history {"_at" (str t3)})))
      (is (= [] (history {"_at" "2020-06-01T00:00:00Z"})) "before the resource existed")
      (is (= [] (history {"_at" "2020"})))
      (let [month (subs (str t2) 0 7)]
        (is (every? (set (history {"_at" month})) ["1" "2"])
            (str "both versions were current during " month))))

    (testing "_since and _at together"
      (is (= ["2"] (history {"_since" (str t2) "_at" (str t2)})))
      (is (= [] (history {"_since" (str t3) "_at" (str t2)}))))

    (testing "_count still limits, newest first"
      (is (= ["3"] (history {"_count" "1" "_since" (str (last-updated v1))}))))

    (testing "a value that names no instant is refused, not ignored"
      (doseq [[pname bad] [["_since" "2026-01-01"]
                           ["_since" "2026-01-01T00:00:00"]
                           ["_since" "2026-01-01T00:00Z"]
                           ["_since" "2026-02-30T00:00:00Z"]
                           ["_since" "yesterday"]
                           ["_at" "yesterday"]
                           ["_at" "2026-13"]]]
        (let [data (refusal #(db/history-type store tid :Observation {pname bad}))]
          (is (= 400 (:fhir/status data)) (str pname "=" bad))
          (is (= "invalid" (:fhir/code data)))
          (is (= [(str "http." pname)] (:fhir/location data))))))))

(deftest vread-by-version-id
  (testing ":sql"
    (let [store (core-db/create-xtdb-store {})]
      (try (check-vread store "t-vread")
           (finally (close-store-nodes! store)))))
  (testing ":xtql"
    (let [store (core-db/create-xtdb-store {:query-mode :xtql})]
      (try (check-vread store "t-vread")
           (finally (close-store-nodes! store))))))

(deftest history-type-since-and-at
  (testing ":sql"
    (let [store (core-db/create-xtdb-store {})]
      (try (check-history-window store "t-hist")
           (finally (close-store-nodes! store)))))
  (testing ":xtql"
    (let [store (core-db/create-xtdb-store {:query-mode :xtql})]
      (try (check-history-window store "t-hist")
           (finally (close-store-nodes! store))))))

(deftest history-type-window-parsing
  (let [utc (fn [s] (Instant/parse s))]
    (testing "_since is an instant, whatever offset it is written in"
      (is (= {:since (utc "2026-01-01T05:00:00Z") :at nil}
             (core-db/history-type-window {"_since" "2026-01-01T00:00:00-05:00"})))
      (is (= (utc "2026-01-01T00:00:00.123456789Z")
             (:since (core-db/history-type-window {"_since" "2026-01-01T00:00:00.123456789Z"})))))

    (testing "_at names a point at instant precision and a UTC period otherwise"
      (is (= {:as-of (utc "2026-03-04T10:00:00Z")}
             (:at (core-db/history-type-window {"_at" "2026-03-04T12:00:00+02:00"}))))
      (is (= {:from (utc "2026-03-04T00:00:00Z") :to (utc "2026-03-05T00:00:00Z")}
             (:at (core-db/history-type-window {"_at" "2026-03-04"}))))
      (is (= {:from (utc "2026-03-01T00:00:00Z") :to (utc "2026-04-01T00:00:00Z")}
             (:at (core-db/history-type-window {"_at" "2026-03"}))))
      (is (= {:from (utc "2026-01-01T00:00:00Z") :to (utc "2027-01-01T00:00:00Z")}
             (:at (core-db/history-type-window {"_at" "2026"})))))

    (testing "absent and blank parameters restrict nothing"
      (is (= {:since nil :at nil} (core-db/history-type-window {})))
      (is (= {:since nil :at nil} (core-db/history-type-window {"_since" "  " "_at" ""}))))))
