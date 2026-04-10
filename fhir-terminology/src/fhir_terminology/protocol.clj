(ns fhir-terminology.protocol)

(defprotocol ITerminologyService
  (expand-valueset [this params]
    "Expand a ValueSet. Params include :url, :valueSetVersion, :filter, :count, :offset, :context, :contextDirection.
     Returns a ValueSet resource with expansion, or throws.")
  (lookup-code [this params]
    "Look up a code. Params include :system, :code, :version, :coding, :displayLanguage.
     Returns a Parameters resource.")
  (validate-code [this params]
    "Validate a code against a ValueSet. Params include :url, :code, :system, :display, :coding, :valueSet.
     Returns a Parameters resource with result boolean and optional message."))
