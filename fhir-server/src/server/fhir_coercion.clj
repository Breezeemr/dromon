(ns server.fhir-coercion
  "FHIR-aware Reitit coercion using the standard :parameters/:responses route
   data convention. Provides a custom Malli coercion instance that applies the
   FHIR JSON transformer (extension promotion/demotion, java.time parsing).

   Use reitit.ring.coercion/coerce-request-middleware and
   coerce-response-middleware with this coercion instance."
  (:require [reitit.coercion.malli :as rcm]
            [malli.transform :as mt]
            [com.breezeehr.fhir-json-transform :as fjt]))

;; ---------------------------------------------------------------------------
;; Transformer
;; ---------------------------------------------------------------------------

(def ^:private fhir-transformer-provider
  "TransformationProvider that applies the FHIR JSON transformer."
  (reify rcm/TransformationProvider
    (-transformer [_ {:keys [strip-extra-keys default-values]}]
      (mt/transformer
        (when strip-extra-keys (mt/strip-extra-keys-transformer))
        (fjt/fhir-json-transformer)
        (when default-values (mt/default-value-transformer
                               {::mt/add-optional-keys true}))))))

(def coercion
  "Reitit Malli coercion configured with the FHIR JSON transformer.
   Use this as the :coercion value in router data.

   `:compile identity` overrides reitit-malli's default `mu/closed-schema`
   so generic placeholder maps (e.g. the `[:map {:short \"Any Resource\"}]`
   that DomainResource uses for `:contained`) stay implicitly open. The
   FHIR cap-schemas mark profile-strict maps with explicit
   `{:closed true}`, so those still validate strictly."
  (rcm/create
    {:transformers {:body     {:default fhir-transformer-provider
                               :formats {"application/json"      fhir-transformer-provider
                                         "application/fhir+json" fhir-transformer-provider}}
                    :string   {:default rcm/string-transformer-provider}
                    :response {:default fhir-transformer-provider
                               :formats {"application/json"      fhir-transformer-provider
                                         "application/fhir+json" fhir-transformer-provider}}}
     :compile (fn [schema _opts] schema)
     :validate true
     :strip-extra-keys false
     :default-values false
     :enabled true}))

;; ---------------------------------------------------------------------------
;; Schemas
;; ---------------------------------------------------------------------------

(def operation-outcome-schema
  "Malli schema for FHIR OperationOutcome error responses."
  [:map {:closed false}
   [:resourceType [:= "OperationOutcome"]]
   [:issue [:vector [:map {:closed false}
                     [:severity :string]
                     [:code :string]]]]])

(def json-patch-op-schema
  "Malli schema for a single RFC 6902 JSON Patch operation."
  [:map {:closed false}
   [:op [:enum "add" "remove" "replace" "move" "copy" "test"]]
   [:path :string]
   [:value {:optional true} :any]
   [:from {:optional true} :string]])

(def json-patch-schema
  "Malli schema for an RFC 6902 JSON Patch document (array of operations)."
  [:vector json-patch-op-schema])
