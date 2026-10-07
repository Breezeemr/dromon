(ns fhir-store.http.sys
  (:require [integrant.core :as ig]
            [fhir-store.http.core :as http]))

(defmethod ig/init-key :fhir-store/http [_ options]
  (http/create-http-store options))
