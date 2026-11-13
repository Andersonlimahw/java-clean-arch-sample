# API findings

## API-FINDING-001 — CurrentUser appears as OpenAPI request input

- **Status:** Fixed.
- **Reproduction:** `mvn -Dtest=SecurityWebTest test` initially failed because `/v3/api-docs` listed `currentUser` as a required query parameter and exposed the `CurrentUser` schema on protected payment and notification routes.
- **Root cause:** Springdoc did not know that the custom MVC argument resolver supplies `CurrentUser` from the authenticated JWT.
- **Correction:** Mark resolver-backed controller parameters `@Parameter(hidden = true)`; keep the bearer security requirement and the payment creation body unchanged.
- **Regression coverage:** `SecurityWebTest.openApiDoesNotExposeCurrentUserAsRequestInput` and `SecurityWebTest.paymentListUsesBearerTokenWithoutCurrentUserBody`.
