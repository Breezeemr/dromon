(ns fhir-store.mock.sys
  (:require [integrant.core :as ig]
            [fhir-store.mock.core :as mock]))

(defmethod ig/init-key :fhir-store/mock [_ options]
  (mock/create-mock-store options))

(defmethod ig/halt-key! :fhir-store/mock [_ store]
  (mock/halt-mock-store store))
