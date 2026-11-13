# API auth-context contract checks

## Scenarios

1. `GET /api/v1/payments` with a valid bearer token and no request body returns `200`.
2. `/v3/api-docs` documents protected endpoints with bearer authentication and does not expose `CurrentUser`, `userId`, or `roles` as request input.
3. `POST /api/v1/payments` retains `CreatePaymentRequest` as its JSON body; the caller identity remains derived from the bearer token.

## Verification

Run `mvn -Dtest=SecurityWebTest test` with Docker available. The test uses MockMvc against the application context and Testcontainers.

For a running local app, the equivalent HTTP probes are:

```bash
curl -i -H "Authorization: Bearer <access-token>" http://127.0.0.1:8080/api/v1/payments
curl -fsS http://127.0.0.1:8080/v3/api-docs
```

The first request has no body and should return `200`; the OpenAPI document should not contain `CurrentUser` or `currentUser` as request input.
