module BaseFHIRR4TestKit
  # Minimal, headless-friendly smoke test suite for a base FHIR R4 (or R4B)
  # server. It intentionally avoids profile-specific (US Core) validation so it
  # can run against a bare conformant server. Auth is a plain OAuth2 bearer
  # token passed as the `access_token` input.
  class BaseFHIRR4TestSuite < Inferno::TestSuite
    id :base_fhir_r4
    title 'Base FHIR R4 Server'
    description %(
      A minimal smoke test suite for a base FHIR R4 / R4B server. It verifies
      that:

      * the `CapabilityStatement` is retrievable at `GET [base]/metadata`
        (HTTP 200, `resourceType == CapabilityStatement`),
      * a known `Patient` can be read by id (HTTP 200, correct type and id), and
      * `Patient` search by `_id` returns a searchset `Bundle` that contains
        that Patient.

      The declared `fhirVersion` is recorded for information only. The dromon
      server under test is FHIR R4B (`fhirVersion 4.3.0`), whose base RESTful
      semantics match R4, so this suite does not fail purely on 4.3.0 vs 4.0.1.

      The server requires OAuth2 bearer authentication; supply the token via the
      `access_token` input.
    )

    input :url,
      title: 'FHIR Endpoint',
      description: 'Base URL of the FHIR R4 endpoint, e.g. https://fhir.local:3001/default/fhir'

    input :access_token,
      title: 'Bearer Access Token',
      description: 'OAuth2 access token, sent as an Authorization: Bearer header on every request',
      optional: true

    input :patient_id,
      title: 'Patient ID',
      description: 'The id of a Patient resource known to exist on the server',
      optional: true,
      default: '123'

    fhir_client do
      url :url
      bearer_token :access_token
    end

    group do
      id :base_fhir_r4_capability
      title 'CapabilityStatement'
      description %(
        Confirms the server advertises its functionality via the FHIR
        capabilities interaction at `GET [base]/metadata`.
      )

      test do
        id :base_fhir_r4_capability_statement
        title 'Server returns a CapabilityStatement from /metadata'
        description %(
          Issues `GET [base]/metadata` and verifies an HTTP 200 response whose
          body is a `CapabilityStatement` resource. The server-declared
          `fhirVersion` is recorded as an info message but is not asserted.
        )
        makes_request :capability_statement

        run do
          fhir_get_capability_statement(name: :capability_statement)

          assert_response_status(200)
          assert_resource_type(:capability_statement)

          declared_version = resource.respond_to?(:fhirVersion) ? resource.fhirVersion : 'unknown'
          info "Server declared fhirVersion: #{declared_version}"
        end
      end
    end

    group do
      id :base_fhir_r4_patient
      title 'Patient read and search'
      description %(
        Confirms the server supports reading a Patient by id and searching for a
        Patient by `_id`.
      )

      test do
        id :base_fhir_r4_patient_read
        title 'Server returns the requested Patient by id (read)'
        description %(
          Issues `GET [base]/Patient/[id]` for the configured `patient_id` and
          verifies an HTTP 200 response containing a `Patient` resource whose id
          matches the requested id.
        )

        run do
          fhir_read(:patient, patient_id)

          assert_response_status(200)
          assert_resource_type(:patient)
          assert resource.id == patient_id,
                 "Requested Patient/#{patient_id} but received Patient/#{resource.id}"
        end
      end

      test do
        id :base_fhir_r4_patient_search
        title 'Server supports Patient search by _id'
        description %(
          Issues `GET [base]/Patient?_id=[id]` and verifies an HTTP 200 response
          containing a searchset `Bundle` that includes the requested Patient.
        )

        run do
          fhir_search(:patient, params: { _id: patient_id })

          assert_response_status(200)
          assert_resource_type(:bundle)

          bundle = resource
          returned_patient_ids =
            (bundle.entry || [])
              .map(&:resource)
              .select { |entry_resource| entry_resource.is_a?(FHIR::Patient) }
              .map(&:id)

          assert returned_patient_ids.include?(patient_id),
                 "Search Bundle did not contain Patient/#{patient_id}. " \
                 "Patient ids found: #{returned_patient_ids.inspect}"
        end
      end
    end
  end
end
