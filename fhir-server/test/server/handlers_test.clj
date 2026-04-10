(ns server.handlers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [fhir-store.mock.core :as mock]
            [server.handlers :as handlers]))

(def ^:private tenant "default")
(def ^:private resource-type "Patient")

(defn- make-store []
  (mock/create-mock-store {}))

(defn- base-request
  "Build a minimal request map that the handlers expect."
  [store & {:keys [id vid params body form-params headers]
            :or   {params {} headers {}}}]
  (cond-> {:fhir/store         store
           :fhir/resource-type resource-type
           :fhir/search-registry nil
           :path-params        {:tenant-id tenant}
           :query-params       params
           :headers            headers}
    id          (assoc-in [:path-params :id] id)
    vid         (assoc-in [:path-params :vid] vid)
    body        (assoc-in [:parameters :body] body)
    form-params (assoc :form-params form-params)))

(defn- create-patient!
  "Helper: create a Patient via the handler and return the response."
  [store & {:keys [body] :or {body {:resourceType "Patient" :name [{:family "Test"}]}}}]
  (handlers/create-resource (base-request store :body body)))

;; ---------------------------------------------------------------------------
;; read-resource
;; ---------------------------------------------------------------------------

(deftest read-existing-resource-returns-200
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/read-resource (base-request store :id id))]
    (is (= 200 (:status resp)))
    (is (= id (get-in resp [:body :id])))))

(deftest read-missing-resource-returns-404
  (let [store (make-store)
        resp  (handlers/read-resource (base-request store :id "nonexistent"))]
    (is (= 404 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; vread-resource
;; ---------------------------------------------------------------------------

(deftest vread-existing-version-returns-200
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        vid    (get-in create [:body :meta :versionId])
        resp   (handlers/vread-resource (base-request store :id id :vid vid))]
    (is (= 200 (:status resp)))
    (is (= vid (get-in resp [:body :meta :versionId])))))

(deftest vread-missing-version-returns-404
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/vread-resource (base-request store :id id :vid "999"))]
    (is (= 404 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; create-resource
;; ---------------------------------------------------------------------------

(deftest create-returns-201-with-location
  (let [store (make-store)
        resp  (create-patient! store)]
    (is (= 201 (:status resp)))
    (is (some? (get-in resp [:headers "Location"])))
    (is (clojure.string/includes? (get-in resp [:headers "Location"]) "/_history/"))
    (is (= "Patient" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; update-resource
;; ---------------------------------------------------------------------------

(deftest update-existing-returns-200
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/update-resource
                 (base-request store
                               :id id
                               :body {:resourceType "Patient"
                                      :id id
                                      :gender "female"}))]
    (is (= 200 (:status resp)))
    (is (= "female" (get-in resp [:body :gender])))))

(deftest update-id-mismatch-returns-400
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/update-resource
                 (base-request store
                               :id id
                               :body {:resourceType "Patient"
                                      :id "wrong-id"
                                      :gender "male"}))]
    (is (= 400 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; delete-resource
;; ---------------------------------------------------------------------------

(deftest delete-returns-204
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])
        resp   (handlers/delete-resource (base-request store :id id))]
    (is (= 204 (:status resp)))
    (is (nil? (:body resp)))))

(deftest delete-then-read-returns-410
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])]
    (handlers/delete-resource (base-request store :id id))
    (let [resp (handlers/read-resource (base-request store :id id))]
      (is (= 410 (:status resp)))
      (is (= "OperationOutcome" (get-in resp [:body :resourceType]))))))

;; ---------------------------------------------------------------------------
;; search-type
;; ---------------------------------------------------------------------------

(deftest search-type-returns-bundle
  (let [store (make-store)]
    (create-patient! store)
    (create-patient! store :body {:resourceType "Patient" :name [{:family "Other"}]})
    (let [resp (handlers/search-type (base-request store))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (get-in resp [:body :resourceType])))
      (is (= "searchset" (get-in resp [:body :type])))
      (is (= 2 (count (get-in resp [:body :entry])))))))

(deftest search-type-count-zero-returns-total-only
  (let [store (make-store)]
    (create-patient! store)
    (create-patient! store :body {:resourceType "Patient" :name [{:family "Other"}]})
    (let [resp (handlers/search-type (base-request store :params {"_count" "0"}))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (get-in resp [:body :resourceType])))
      (is (= 2 (get-in resp [:body :total])))
      (is (nil? (get-in resp [:body :entry]))))))

(deftest search-type-negative-skip-returns-400
  (let [store (make-store)
        resp  (handlers/search-type (base-request store :params {"_skip" "-1"}))]
    (is (= 400 (:status resp)))
    (is (= "OperationOutcome" (get-in resp [:body :resourceType])))))

;; ---------------------------------------------------------------------------
;; history-instance
;; ---------------------------------------------------------------------------

(deftest history-instance-returns-bundle-with-entries
  (let [store  (make-store)
        create (create-patient! store)
        id     (get-in create [:body :id])]
    ;; Create a second version via update
    (handlers/update-resource
      (base-request store
                    :id id
                    :body {:resourceType "Patient" :id id :gender "male"}))
    (let [resp (handlers/history-instance (base-request store :id id))]
      (is (= 200 (:status resp)))
      (is (= "Bundle" (get-in resp [:body :resourceType])))
      (is (= "history" (get-in resp [:body :type])))
      (is (= 2 (get-in resp [:body :total])))
      (is (= 2 (count (get-in resp [:body :entry])))))))
