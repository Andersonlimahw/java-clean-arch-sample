# HTTP API

Base path: `/api/v1`. The application uses stateless RS256 bearer tokens. Roles are read from the JWT `roles` claim and mapped to `ROLE_USER`, `ROLE_ADMIN`, and `ROLE_FRAUD_REVIEWER`.

For protected endpoints, use Swagger UI's **Authorize** control or send `Authorization: Bearer <access-token>`. The server derives the caller's `CurrentUser` from that token; `CurrentUser`, `userId`, and `roles` are not request fields or query parameters. For example, payment creation sends only `amount`, `currency`, and optional `description` in its JSON body.

## Endpoints

| Method | Route | Required role | Contract |
|---|---|---|---|
| `POST` | `/api/v1/auth/register` | Public | Creates a user; returns `201`. |
| `POST` | `/api/v1/auth/login` | Public | Issues access and refresh tokens; returns `200`. |
| `POST` | `/api/v1/auth/refresh` | Public | Rotates the refresh token and issues a new pair; returns `200`. |
| `POST` | `/api/v1/auth/logout` | Public | Revokes the presented refresh-token family; body may be absent; returns `204`. |
| `POST` | `/api/v1/payments` | `USER` or `ADMIN` | Creates a payment owned by the caller; requires `Idempotency-Key`; returns `201` or `422` for an explicit fraud decline. |
| `GET` | `/api/v1/payments/{id}` | `USER`, `ADMIN`, or `FRAUD_REVIEWER` | Owner can read their payment. `ADMIN` and `FRAUD_REVIEWER` can read any payment. |
| `GET` | `/api/v1/payments` | `USER` or `ADMIN` | Lists only the caller's payments. `ADMIN` remains owner-scoped here. Supports the pagination and sort contract below. |
| `GET` | `/api/v1/payments/under-review` | `ADMIN` or `FRAUD_REVIEWER` | Lists payments held for review. Supports the same pagination and sort contract. |
| `POST` | `/api/v1/payments/{id}/confirm` | `USER` or `ADMIN`, payment owner | Confirms a `PENDING` payment; returns `200`. A non-owner receives `404`, including an `ADMIN`. |
| `POST` | `/api/v1/payments/{id}/review` | `ADMIN` or `FRAUD_REVIEWER`, not payment owner | Requires query parameter `decision=APPROVE` or `decision=REJECT`. Reviews an `UNDER_REVIEW` payment; returns `200`. Approval moves it to `PENDING`; rejection cancels it. |
| `POST` | `/api/v1/payments/{id}/cancel?reason={text}` | `USER` or `ADMIN`, payment owner | Cancels a `PENDING` or `UNDER_REVIEW` payment; `reason` is optional and has a server default; returns `200`. |
| `GET` | `/api/v1/notifications` | Any authenticated user | Lists only the caller's notifications; returns `200`. |

The API documentation endpoints `/v3/api-docs/**`, `/swagger-ui/**`, and `/swagger-ui.html` are public. Actuator health, info, and Prometheus endpoints are public; `/actuator/metrics` is exposed by Actuator but requires authentication under the fallback security rule. See `SecurityConfig` for the complete matcher order.

### Idempotency

Payment creation requires the `Idempotency-Key` request header. The controller trims the value and rejects blank keys or values longer than 120 characters with `400`. A completed request replay returns its stored response and sets `Idempotent-Replayed: true`. Reusing the same key with a different request payload, or while its unexpired processing lease is active, returns `409`. Other endpoints do not require this header.

```http
POST /api/v1/payments
Authorization: Bearer <access-token>
Idempotency-Key: <unique-request-key>
Content-Type: application/json
```

### Pagination and sorting

Both payment list routes use zero-based offset pagination:

| Parameter | Default | Allowed values |
|---|---:|---|
| `page` | `0` | Integer `>= 0`, with `page * size <= 10000`. |
| `size` | `20` | Integer from `1` through `100`. |
| `sort` | `createdAt,desc` | After trimming, case-sensitive values `createdAt,desc` or `createdAt,asc`. |

The order is deterministic for a static dataset: `createdAt,desc` uses `id DESC` as the tie-breaker and `createdAt,asc` uses `id ASC`. Invalid values return `400`; they are not clamped. A valid page beyond the last result returns `200` with an empty `items` array. The response contains `items`, `page`, `size`, `totalElements`, `totalPages`, and the canonical `sort` value. Concurrent writes can shift later offset pages because the list and count are separate reads.

### Error responses

Errors use `application/problem+json` (RFC 7807). The body contains `type`, `title`, `status`, `detail`, and `instance`; `correlationId` is included when available. Bean validation errors can include an `invalidParams` property. Stack traces are not returned.

| Status | Meaning |
|---|---|
| `400` | Invalid body or parameter, failed bean validation, missing/blank/oversized `Idempotency-Key`, or invalid pagination/sort. |
| `401` | Missing or invalid bearer token, or invalid login/refresh credentials. |
| `403` | Authenticated caller lacks a required role, or a reviewer is also the payment owner. |
| `404` | Resource is absent or payment ownership is intentionally hidden. |
| `409` | Domain transition, uniqueness, idempotency, or integrity conflict. An optimistic-lock race uses title `Concurrent Modification` and type `urn:problem-type:concurrent-modification`. |
| `422` | Fraud provider explicitly declines the payment. The provider's reason is not returned. |
| `429` | More than 20 register, login, or refresh attempts per minute for the address from `HttpServletRequest.getRemoteAddr()`. Response includes `Retry-After: 60`; logout is not rate-limited. |
| `503` | Transient database lock/deadlock or serialization failure. Type is `urn:problem-type:transient-lock`; response includes `Retry-After: 1`. Retry the same request after the indicated delay. |
| `500` | Unexpected server error; response contains a generic problem detail. |

## Implementation references

- Routes and pagination: `src/main/java/com/muse/payments/identity/web/AuthController.java`, `src/main/java/com/muse/payments/payments/web/PaymentController.java`, and `src/main/java/com/muse/payments/notifications/web/NotificationController.java`.
- Authentication and role matchers: `src/main/java/com/muse/payments/shared/security/SecurityConfig.java` and `src/main/java/com/muse/payments/shared/security/CurrentUser.java`.
- Pagination allowlist: `src/main/java/com/muse/payments/payments/application/ListPaymentsQueryHandler.java` and `src/main/java/com/muse/payments/payments/application/PaymentSorts.java`.
- Problem details: `src/main/java/com/muse/payments/shared/web/GlobalExceptionHandler.java`.
- Authentication rate limit: `src/main/java/com/muse/payments/identity/infrastructure/AuthRateLimiter.java`.
