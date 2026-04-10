# Authentication and Authorization Design

## Overview
This FHIR server requires a robust, flexible authentication and authorization model capable of supporting both direct system-to-system integration and SMART on FHIR workflows. We will use the Ory ecosystem:
- **Ory Kratos**: Identity, user management, and authentication flows.
- **Ory Keto**: Zanzibar-inspired authorization and access control.
- **Ory Hydra**: OAuth 2.0 and OpenID Connect (OIDC) provider.

## SMART on FHIR Implementation
SMART on FHIR dictates that the server must expose OIDC and OAuth 2.0 endpoints for authorization and token generation. Hydra will act as the OAuth2 authorization server.
1. **App Launch**: The EHR or standalone app redirects the user to the authorize endpoint.
2. **Authentication**: Hydra delegates authentication to the Kratos Login UI.
3. **Consent**: After login, the user grants specific SMART scopes (e.g., `patient/*.read`, `launch`).
4. **Token Issuance**: Hydra issues a signed JWT containing the authorized SMART scopes and context (e.g., `patient_id`).

## Direct Authentication
For backend services or administrative access, direct authentication might be necessary. This can be achieved via client credentials grants in Hydra or direct API keys mapping to identities in Kratos.

## Authorization (Keto)
Whenever a request reaches the FHIR server:
1. The bearer token is validated via Hydra.
2. The extracted identity and scopes are evaluated.
3. Fine-grained permissions (e.g., "Can User A read Patient B's Encounter C?") are evaluated using Ory Keto.

## Decision Points
- **Login UI/Consent Provider**: Hydra requires a custom UI for login and consent. Should we build this in Clojure/Reagent, or use standard Ory React templates?
- **Identity Mapping**: How are FHIR `Patient` and `Practitioner` resources linked to Kratos identities? A common pattern is storing the Kratos identity ID as an identifier on the FHIR resource.
- **Keto Granularity**: Will Keto evaluate permissions at the individual resource level (very granular, huge graph) or at the compartment/patient level?

## Alternatives
- **Keycloak**: An all-in-one alternative to the Ory stack. It is heavier but avoids the need to run three separate services (Kratos, Keto, Hydra) and build a custom consent UI.
- **Auth0 / Okta**: Managed services. Excellent Developer Experience but can be costly and less flexible for self-hosted enterprise deployments.

## Additional Questions
1. Do we need to support both SMART EHR Launch and Standalone Launch from day one?
2. How will we manage Keto relational tuples provisioning? Will it happen asynchronously via events when FHIR resources are created/updated?
3. Should we enforce token-based rate limiting?
