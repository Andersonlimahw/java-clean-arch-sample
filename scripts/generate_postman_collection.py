#!/usr/bin/env python3
"""
Generates the comprehensive, expert-level English Postman Collection v2.1.0 for Payments Platform.
"""
import json
import os

DOCS_DESCRIPTION = """# Payments Platform API · Complete End-to-End Test Suite & Living Contract

Comprehensive Postman Collection verifying all business-critical workflows, domain events, idempotency guarantees, security boundaries, and resilient integrations of the Payments Platform.

---

## 🏛 Architecture & Engineering Foundations

- **Clean Architecture & Modular Monolith**: Base package `com.muse.payments`, divided into bounded modules (`identity`, `payments`, `notifications`, `shared`) governed by Spring Modulith.
- **Domain Purity**: Domain models (`Payment`, `User`, `Notification`) remain strictly free from Spring, JPA, Hibernate, RabbitMQ, Redis, or Jackson dependencies.
- **CQRS Pattern**: Clear separation between Command handlers (state-mutating transactions recording domain events) and Query handlers (read-only projections).
- **Transactional Outbox**: Domain events are persisted into `outbox_events` atomically within the database transaction (`Propagation.MANDATORY`). The relay daemon publishes to RabbitMQ topic exchanges.
- **Distributed Idempotency**: Distributed locking and lease acquisition via Redis/PostgreSQL ensuring exactly-once execution for `POST /api/v1/payments`.
- **Resilient Fraud Screening**: Resilience4j Circuit Breaker, Bulkhead, TimeLimiter, and Retry decorating external provider calls. High-risk amounts (>= 5,000.00) or provider timeouts fail gracefully to `UNDER_REVIEW`.
- **Stateless RS256 JWT Security**: Asymmetric RS256 token verification with public key decoding and strict RBAC (`USER`, `ADMIN`, `FRAUD_REVIEWER`).
- **RFC 7807 Problem Details**: Consistent, standardized error response schemas (`application/problem+json`).

---

## 🚀 Prerequisites & Local Setup

Before executing this collection, ensure all backend dependencies are healthy:

```bash
# Start all infrastructure services (Postgres 16, Redis 7, RabbitMQ 3.13, WireMock 3.13)
make up
# Or run with remote debugger attached on port 5005:
make debug
```

- **Application API**: `http://127.0.0.1:8080` (mapped via `{{baseUrl}}`)
- **WireMock Mock Server**: `http://127.0.0.1:8090`
- **RabbitMQ Management**: `http://127.0.0.1:15672` (guest/guest)
- **PostgreSQL**: `127.0.0.1:5432` (`payments` / `postgres`)

---

## 📋 Execution Order & Test Sequence

Execute the collection folders in sequential order using Postman Runner or Newman.
Enable **"Keep variable values"** in Postman Runner to persist dynamic runtime variables.

| Folder | Name | Purpose & Invariants Tested |
|---|---|---|
| **01** | **Availability** | Verifies Spring Boot Actuator readiness probe (`/actuator/health/readiness`). Returns `200 UP`. |
| **02** | **Identity & Authentication** | Registers unique user (`201 Created`), logs in (`200 OK`), saves JWT Bearer tokens, rotates refresh token (`200 OK`) and verifies family revocation tracking. |
| **03** | **Payments & Transactions** | Full payment lifecycle: Creation (`201 PENDING`), Idempotency replay with same key (`201 Idempotent-Replayed: true`), Query by ID, Pagination, Confirmation (`200 COMPLETED`), Second payment creation and Cancellation (`200 CANCELLED`). Crucially, creates two high-value payments ($5,000.00 & $5,001.00) flagged as `UNDER_REVIEW` to populate the Fraud Review queue. |
| **04** | **Notifications & Event Delivery** | Asynchronous domain event verification. Polls `/api/v1/notifications` (up to 10 attempts with 1s backoff) for the `PAYMENT_COMPLETED` notification routed through RabbitMQ. |
| **05** | **Negative Scenarios & Error Contracts** | Rigorous negative assertions verifying RFC 7807 compliance: `401 Unauthorized` (missing bearer), `400 Bad Request` (missing key, zero amount, pagination limits, invalid UUID), `404 Not Found` (nonexistent resource), `403 Forbidden` (role escalation), `409 Conflict` (invalid state machine transitions). |
| **06** | **Fraud Review & Compliance** *(Optional)* | Dedicated compliance workflow for `ADMIN` or `FRAUD_REVIEWER` roles. Lists payments in review (`GET /under-review`), approves held payment to `PENDING`, and rejects held payment to `CANCELLED`. Invariant: Reviewer cannot own the reviewed payment. |
| **07** | **Session Teardown & Logout** | Calls `POST /api/v1/auth/logout` (`204 No Content`), then verifies that re-using the revoked refresh token produces `401 Unauthorized`. |

---

## 🔑 Collection Variables Reference

| Variable | Scope | Description & Lifecycle |
|---|---|---|
| `baseUrl` | Static | Base URL of the API gateway (default: `http://127.0.0.1:8080`). |
| `newUserOnRun` | Config | When `"true"`, generates a fresh UUID email upon registration. Set to `"false"` to reuse credentials. |
| `email` | Dynamic | User email generated as `postman-<guid>@example.com` or customized by the runner. |
| `password` | Dynamic | Strong password generated with uppercase, lowercase, numbers, and symbols. |
| `displayName` | Config | Display name stored on user entity (`Postman User`). |
| `userId` | Dynamic | User UUID extracted from `POST /api/v1/auth/register` response. |
| `accessToken` | Dynamic | RS256 Bearer JWT token extracted from login/refresh; inherited across all protected endpoints. |
| `refreshToken` | Dynamic | Opaque refresh token string rotated upon each `/refresh` call. |
| `tokenExpiresAt` | Dynamic | Unix timestamp (epoch ms) indicating access token expiration. |
| `amount` | Config | Default sample payment amount (`15.50`). |
| `currency` | Config | ISO 4217 3-letter currency code (`BRL`). |
| `paymentDescription` | Config | Sample human-readable description for payments. |
| `idempotencyKey` | Dynamic | UUID idempotency key generated per payment creation request. |
| `paymentId` | Dynamic | Primary payment UUID created in Folder 03 and tracked through confirmation and notification. |
| `cancelPaymentId` | Dynamic | Secondary payment UUID created exclusively for the cancellation flow test. |
| `heldPaymentIdApprove` | Dynamic | High-value payment UUID ($5,000.00) created in step 03.09, held in `UNDER_REVIEW`, approved in 06.02. |
| `heldPaymentIdReject` | Dynamic | High-value payment UUID ($5,001.00) created in step 03.10, held in `UNDER_REVIEW`, rejected in 06.03. |
| `notificationId` | Dynamic | UUID of the notification message delivered via RabbitMQ. |
| `notificationPoll` | Dynamic | Integer poll counter (0-10) for notification asynchronous polling loop. |
| `runFraudReview` | Config | Flag (`"false"` by default). Set to `"true"` to enable executing Folder 06. |
| `reviewerAccessToken` | Manual/Config | Bearer JWT belonging to a user with `ADMIN` or `FRAUD_REVIEWER` role. |

---

## 🛡️ Fraud Review Setup Guide

Because role escalation is deliberately forbidden via public endpoints, follow these steps to test Folder 06:

1. Create or designate a separate user in the database with the `FRAUD_REVIEWER` or `ADMIN` role:
   ```sql
   UPDATE users SET roles = 'USER,FRAUD_REVIEWER' WHERE email = 'reviewer@example.com';
   ```
2. Authenticate as the reviewer via `POST /api/v1/auth/login`.
3. Copy the resulting `accessToken` into the collection variable `reviewerAccessToken`.
4. Set collection variable `runFraudReview` to `"true"`.
5. Execute the collection: steps 03.09 and 03.10 will automatically generate distinct held payments, which Folder 06 will list, approve, and reject.

---

## 🧪 CLI Execution with Newman

```bash
# Run the complete test suite (Folders 01-05, 07):
npx newman run src/test/postman/payments-v1.postman_collection.json \\
  --delay-request 250 \\
  --timeout-request 10000 \\
  --timeout-script 5000

# Run with Fraud Review enabled (supplying reviewer token):
npx newman run src/test/postman/payments-v1.postman_collection.json \\
  --global-var "runFraudReview=true" \\
  --global-var "reviewerAccessToken=<YOUR_REVIEWER_TOKEN>" \\
  --delay-request 250 \\
  --timeout-request 10000 \\
  --timeout-script 5000
```
"""

# Schemas
USER_SCHEMA = json.dumps({
    "type": "object",
    "required": ["id", "email", "displayName", "roles"],
    "properties": {
        "id": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"},
        "email": {"type": "string", "minLength": 1},
        "displayName": {"type": "string", "minLength": 1},
        "roles": {"type": "array", "items": {"type": "string", "minLength": 1}}
    }
})

AUTH_TOKEN_SCHEMA = json.dumps({
    "type": "object",
    "required": ["accessToken", "refreshToken", "tokenType", "expiresIn"],
    "properties": {
        "accessToken": {"type": "string", "minLength": 1},
        "refreshToken": {"type": "string", "minLength": 1},
        "tokenType": {"enum": ["Bearer"]},
        "expiresIn": {"type": "integer", "minimum": 1}
    }
})

PAYMENT_SCHEMA = json.dumps({
    "type": "object",
    "required": ["id", "userId", "amount", "currency", "description", "status", "createdAt", "updatedAt", "version"],
    "properties": {
        "id": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"},
        "userId": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"},
        "amount": {"type": "number", "minimum": 0.01},
        "currency": {"type": "string", "pattern": "^[A-Z]{3}$"},
        "description": {"type": ["string", "null"]},
        "status": {"enum": ["PENDING", "UNDER_REVIEW", "COMPLETED", "CANCELLED"]},
        "createdAt": {"type": "string", "minLength": 1},
        "updatedAt": {"type": "string", "minLength": 1},
        "version": {"type": ["integer", "null"]}
    }
})

PAGE_PAYMENT_SCHEMA = json.dumps({
    "type": "object",
    "required": ["items", "page", "size", "totalElements", "totalPages", "sort"],
    "properties": {
        "items": {
            "type": "array",
            "items": json.loads(PAYMENT_SCHEMA)
        },
        "page": {"type": "integer"},
        "size": {"type": "integer"},
        "totalElements": {"type": "integer"},
        "totalPages": {"type": "integer"},
        "sort": {"type": "string", "minLength": 1}
    }
})

NOTIFICATIONS_SCHEMA = json.dumps({
    "type": "array",
    "items": {
        "type": "object",
        "required": ["id", "userId", "paymentId", "type", "title", "message", "createdAt"],
        "properties": {
            "id": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"},
            "userId": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"},
            "paymentId": {"type": "string", "pattern": "^[0-9a-fA-F-]{36}$"},
            "type": {"type": "string", "minLength": 1},
            "title": {"type": "string", "minLength": 1},
            "message": {"type": "string", "minLength": 1},
            "createdAt": {"type": "string", "minLength": 1}
        }
    }
})

def make_test_script(tests):
    return {
        "listen": "test",
        "script": {
            "type": "text/javascript",
            "exec": tests
        }
    }

def make_prerequest_script(lines):
    return {
        "listen": "prerequest",
        "script": {
            "type": "text/javascript",
            "exec": lines
        }
    }

def rfc7807_tests(expected_status):
    return [
        f'pm.test("Status code is {expected_status}", () => pm.response.to.have.status({expected_status}));',
        'pm.test("Content-Type is problem+json", () => pm.expect(pm.response.headers.get("Content-Type")).to.include("application/problem+json"));',
        f'pm.test("RFC 7807 problem details schema validated", () => {{ const b = pm.response.json(); pm.expect(b.status).to.eql({expected_status}); ["type","title","detail"].forEach(k => pm.expect(b[k]).to.be.a("string").and.not.empty); }});'
    ]

def build_collection():
    collection = {
        "info": {
            "name": "Payments Platform v1 · End-to-End API Flow",
            "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json",
            "description": DOCS_DESCRIPTION
        },
        "auth": {
            "type": "bearer",
            "bearer": [
                {
                    "key": "token",
                    "value": "{{accessToken}}",
                    "type": "string"
                }
            ]
        },
        "variable": [
            {"key": "baseUrl", "value": "http://127.0.0.1:8080", "type": "string"},
            {"key": "newUserOnRun", "value": "true", "type": "string"},
            {"key": "email", "value": "", "type": "string"},
            {"key": "password", "value": "", "type": "string"},
            {"key": "displayName", "value": "Postman User", "type": "string"},
            {"key": "userId", "value": "", "type": "string"},
            {"key": "accessToken", "value": "", "type": "string"},
            {"key": "refreshToken", "value": "", "type": "string"},
            {"key": "tokenExpiresAt", "value": "", "type": "string"},
            {"key": "amount", "value": "15.50", "type": "string"},
            {"key": "currency", "value": "BRL", "type": "string"},
            {"key": "paymentDescription", "value": "Postman Sample Payment", "type": "string"},
            {"key": "idempotencyKey", "value": "", "type": "string"},
            {"key": "paymentId", "value": "", "type": "string"},
            {"key": "cancelPaymentId", "value": "", "type": "string"},
            {"key": "heldPaymentIdApprove", "value": "", "type": "string"},
            {"key": "heldPaymentIdReject", "value": "", "type": "string"},
            {"key": "notificationId", "value": "", "type": "string"},
            {"key": "notificationPoll", "value": "0", "type": "string"},
            {"key": "runFraudReview", "value": "false", "type": "string"},
            {"key": "reviewerAccessToken", "value": "", "type": "string"}
        ],
        "item": []
    }

    # ==========================================================
    # Folder 01 · Availability
    # ==========================================================
    f1 = {
        "name": "01 · Availability",
        "description": "Verifies service health and readiness before initiating functional API test runs.",
        "item": [
            {
                "name": "01 · Application Readiness Probe",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/actuator/health/readiness",
                    "description": "Checks the Spring Boot Actuator readiness probe. Confirms database, cache, and broker connections are UP.",
                    "auth": {"type": "noauth"}
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        'pm.test("Application status is UP", () => pm.expect(pm.response.json().status).to.eql("UP"));'
                    ])
                ]
            }
        ]
    }
    collection["item"].append(f1)

    # ==========================================================
    # Folder 02 · Identity & Authentication
    # ==========================================================
    f2 = {
        "name": "02 · Identity & Authentication",
        "description": "Manages user registration, credential authentication, RS256 token issuance, and refresh token family rotation.",
        "item": [
            {
                "name": "01 · Register User & Preserve Credentials",
                "request": {
                    "method": "POST",
                    "header": [{"key": "Content-Type", "value": "application/json"}],
                    "url": "{{baseUrl}}/api/v1/auth/register",
                    "description": "Generates a unique email address on each run if newUserOnRun=true. Hashes password using BCrypt off virtual threads. Saves real UUID into userId.",
                    "auth": {"type": "noauth"},
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "email": "{{email}}",\n  "password": "{{password}}",\n  "displayName": "{{displayName}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_prerequest_script([
                        'if (pm.collectionVariables.get("newUserOnRun") === "true" || !pm.collectionVariables.get("email")) {',
                        '  pm.collectionVariables.set("email", "postman-" + pm.variables.replaceIn("{{$guid}}") + "@example.com");',
                        '}',
                        'if (!pm.collectionVariables.get("password")) {',
                        '  pm.collectionVariables.set("password", "Pm-" + pm.variables.replaceIn("{{$guid}}") + "!aA1");',
                        '}',
                        '["userId","accessToken","refreshToken","tokenExpiresAt","paymentId","cancelPaymentId","heldPaymentIdApprove","heldPaymentIdReject","idempotencyKey","notificationId"].forEach(k => pm.collectionVariables.set(k, ""));'
                    ]),
                    make_test_script([
                        'pm.test("Status code is 201 Created", () => pm.response.to.have.status(201));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches UserResponse schema", () => pm.response.to.have.jsonSchema({USER_SCHEMA}));',
                        'pm.test("Credentials and role preserved", () => {',
                        '  const b = pm.response.json();',
                        '  pm.expect(b.email).to.eql(pm.collectionVariables.get("email"));',
                        '  pm.expect(b.displayName).to.eql(pm.collectionVariables.get("displayName"));',
                        '  pm.expect(b.roles).to.include("USER");',
                        '});',
                        'if (pm.response.code === 201 && typeof pm.response.json().id === "string") {',
                        '  pm.collectionVariables.set("userId", pm.response.json().id);',
                        '}'
                    ])
                ]
            },
            {
                "name": "02 · Login & Extract JWT Access and Refresh Tokens",
                "request": {
                    "method": "POST",
                    "header": [{"key": "Content-Type", "value": "application/json"}],
                    "url": "{{baseUrl}}/api/v1/auth/login",
                    "description": "Authenticates user credentials and issues RS256 access JWT and opaque refresh token family header.",
                    "auth": {"type": "noauth"},
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "email": "{{email}}",\n  "password": "{{password}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches AuthTokenResponse schema", () => pm.response.to.have.jsonSchema({AUTH_TOKEN_SCHEMA}));',
                        'if (pm.response.code === 200) {',
                        '  const b = pm.response.json();',
                        '  if (typeof b.accessToken === "string" && b.accessToken && typeof b.refreshToken === "string" && b.refreshToken) {',
                        '    pm.collectionVariables.set("accessToken", b.accessToken);',
                        '    pm.collectionVariables.set("refreshToken", b.refreshToken);',
                        '    pm.collectionVariables.set("tokenExpiresAt", String(Date.now() + b.expiresIn * 1000));',
                        '  }',
                        '}'
                    ])
                ]
            },
            {
                "name": "03 · Rotate Refresh Token",
                "request": {
                    "method": "POST",
                    "header": [{"key": "Content-Type", "value": "application/json"}],
                    "url": "{{baseUrl}}/api/v1/auth/refresh",
                    "description": "Rotates refresh token within family. Old refresh token becomes invalid; new access token is issued.",
                    "auth": {"type": "noauth"},
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "refreshToken": "{{refreshToken}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_prerequest_script([
                        'pm.variables.set("previousRefreshToken", pm.collectionVariables.get("refreshToken"));'
                    ]),
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches AuthTokenResponse schema", () => pm.response.to.have.jsonSchema({AUTH_TOKEN_SCHEMA}));',
                        'pm.test("Refresh token rotated successfully", () => pm.expect(pm.response.json().refreshToken).not.to.eql(pm.variables.get("previousRefreshToken")));',
                        'if (pm.response.code === 200) {',
                        '  const b = pm.response.json();',
                        '  if (typeof b.accessToken === "string" && b.accessToken && typeof b.refreshToken === "string" && b.refreshToken) {',
                        '    pm.collectionVariables.set("accessToken", b.accessToken);',
                        '    pm.collectionVariables.set("refreshToken", b.refreshToken);',
                        '    pm.collectionVariables.set("tokenExpiresAt", String(Date.now() + b.expiresIn * 1000));',
                        '  }',
                        '}'
                    ])
                ]
            }
        ]
    }
    collection["item"].append(f2)

    # ==========================================================
    # Folder 03 · Payments & Transactions
    # ==========================================================
    f3 = {
        "name": "03 · Payments & Transactions",
        "description": "Complete payment lifecycle: creation, idempotency replay, query, pagination, confirmation, cancellation, and creation of fraud review candidates.",
        "item": [
            {
                "name": "01 · Create Payment",
                "request": {
                    "method": "POST",
                    "header": [
                        {"key": "Idempotency-Key", "value": "{{$guid}}"},
                        {"key": "Content-Type", "value": "application/json"}
                    ],
                    "url": "{{baseUrl}}/api/v1/payments",
                    "description": "Initiates payment with distributed idempotency lease. Fraud screening returns CLEAR, setting status to PENDING.",
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "amount": "{{amount}}",\n  "currency": "{{currency}}",\n  "description": "{{paymentDescription}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_prerequest_script([
                        'const key = pm.variables.replaceIn("{{$guid}}");',
                        'pm.request.headers.upsert({ key: "Idempotency-Key", value: key });',
                        'pm.collectionVariables.set("idempotencyKey", key);'
                    ]),
                    make_test_script([
                        'pm.test("Status code is 201 Created", () => pm.response.to.have.status(201));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment belongs to authenticated user", () => pm.expect(pm.response.json().userId).to.eql(pm.collectionVariables.get("userId")));',
                        'pm.test("Initial status is PENDING", () => pm.expect(pm.response.json().status).to.eql("PENDING"));',
                        'if (pm.response.code === 201 && typeof pm.response.json().id === "string") {',
                        '  pm.collectionVariables.set("paymentId", pm.response.json().id);',
                        '}'
                    ])
                ]
            },
            {
                "name": "02 · Replay Creation with Same Idempotency Key",
                "request": {
                    "method": "POST",
                    "header": [
                        {"key": "Idempotency-Key", "value": "{{idempotencyKey}}"},
                        {"key": "Content-Type", "value": "application/json"}
                    ],
                    "url": "{{baseUrl}}/api/v1/payments",
                    "description": "Replays previous creation request using the same Idempotency-Key. Must return stored 201 response with Idempotent-Replayed: true header without duplicate persistence.",
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "amount": "{{amount}}",\n  "currency": "{{currency}}",\n  "description": "{{paymentDescription}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 201 Created", () => pm.response.to.have.status(201));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        'pm.test("Header Idempotent-Replayed is true", () => pm.expect(pm.response.headers.get("Idempotent-Replayed")).to.eql("true"));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment ID matches original creation", () => pm.expect(pm.response.json().id).to.eql(pm.collectionVariables.get("paymentId")));'
                    ])
                ]
            },
            {
                "name": "03 · Get Payment by ID",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/{{paymentId}}",
                    "description": "Fetches payment details by UUID. Checks owner access validation."
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment ID and owner match", () => {',
                        '  const b = pm.response.json();',
                        '  pm.expect(b.id).to.eql(pm.collectionVariables.get("paymentId"));',
                        '  pm.expect(b.userId).to.eql(pm.collectionVariables.get("userId"));',
                        '});'
                    ])
                ]
            },
            {
                "name": "04 · List Payments — Descending Order",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments?page=0&size=20&sort=createdAt,desc",
                    "description": "Lists paginated payments for the authenticated user sorted by creation date descending."
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PageResponse schema", () => pm.response.to.have.jsonSchema({PAGE_PAYMENT_SCHEMA}));',
                        'pm.test("List contains created payment", () => {',
                        '  const b = pm.response.json();',
                        '  const ids = (b.items || []).map(p => p.id);',
                        '  pm.expect(ids).to.include(pm.collectionVariables.get("paymentId"));',
                        '});'
                    ])
                ]
            },
            {
                "name": "05 · Paginate Payments — Ascending Order, Second Page",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments?page=1&size=1&sort=createdAt,asc",
                    "description": "Tests pagination parameters (page=1, size=1) and ascending sort order."
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PageResponse schema", () => pm.response.to.have.jsonSchema({PAGE_PAYMENT_SCHEMA}));',
                        'pm.test("Pagination metadata verified", () => {',
                        '  const b = pm.response.json();',
                        '  pm.expect(b.page).to.eql(1);',
                        '  pm.expect(b.size).to.eql(1);',
                        '});'
                    ])
                ]
            },
            {
                "name": "06 · Confirm Payment",
                "request": {
                    "method": "POST",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/{{paymentId}}/confirm",
                    "description": "Transitions payment status from PENDING to COMPLETED and emits PaymentCompletedEvent via Transactional Outbox."
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment status transitioned to COMPLETED", () => pm.expect(pm.response.json().status).to.eql("COMPLETED"));'
                    ])
                ]
            },
            {
                "name": "07 · Create Second Payment for Cancellation",
                "request": {
                    "method": "POST",
                    "header": [
                        {"key": "Idempotency-Key", "value": "{{$guid}}"},
                        {"key": "Content-Type", "value": "application/json"}
                    ],
                    "url": "{{baseUrl}}/api/v1/payments",
                    "description": "Creates an independent payment to verify the cancellation state transition.",
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "amount": "{{amount}}",\n  "currency": "{{currency}}",\n  "description": "{{paymentDescription}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 201 Created", () => pm.response.to.have.status(201));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Initial status is PENDING", () => pm.expect(pm.response.json().status).to.eql("PENDING"));',
                        'if (pm.response.code === 201 && typeof pm.response.json().id === "string") {',
                        '  pm.collectionVariables.set("cancelPaymentId", pm.response.json().id);',
                        '}'
                    ])
                ]
            },
            {
                "name": "08 · Cancel Second Payment",
                "request": {
                    "method": "POST",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/{{cancelPaymentId}}/cancel",
                    "description": "Cancels the second payment, transitioning status from PENDING to CANCELLED."
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment status transitioned to CANCELLED", () => pm.expect(pm.response.json().status).to.eql("CANCELLED"));'
                    ])
                ]
            },
            {
                "name": "09 · Create Held Payment for Fraud Review (Approval Candidate)",
                "request": {
                    "method": "POST",
                    "header": [
                        {"key": "Idempotency-Key", "value": "{{$guid}}"},
                        {"key": "Content-Type", "value": "application/json"}
                    ],
                    "url": "{{baseUrl}}/api/v1/payments",
                    "description": "Creates a high-value payment ($5,000.00). WireMock flags amount >= 5000 as UNDER_REVIEW. ID is stored into heldPaymentIdApprove for Folder 06.",
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "amount": "5000.00",\n  "currency": "{{currency}}",\n  "description": "High-value transaction held for fraud review (approval candidate)"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 201 Created", () => pm.response.to.have.status(201));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment status is UNDER_REVIEW", () => pm.expect(pm.response.json().status).to.eql("UNDER_REVIEW"));',
                        'if (pm.response.code === 201 && typeof pm.response.json().id === "string") {',
                        '  pm.collectionVariables.set("heldPaymentIdApprove", pm.response.json().id);',
                        '}'
                    ])
                ]
            },
            {
                "name": "10 · Create Held Payment for Fraud Review (Rejection Candidate)",
                "request": {
                    "method": "POST",
                    "header": [
                        {"key": "Idempotency-Key", "value": "{{$guid}}"},
                        {"key": "Content-Type", "value": "application/json"}
                    ],
                    "url": "{{baseUrl}}/api/v1/payments",
                    "description": "Creates a second high-value payment ($5,001.00). Flagged as UNDER_REVIEW. ID is stored into heldPaymentIdReject for Folder 06.",
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "amount": "5001.00",\n  "currency": "{{currency}}",\n  "description": "High-value transaction held for fraud review (rejection candidate)"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 201 Created", () => pm.response.to.have.status(201));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment status is UNDER_REVIEW", () => pm.expect(pm.response.json().status).to.eql("UNDER_REVIEW"));',
                        'if (pm.response.code === 201 && typeof pm.response.json().id === "string") {',
                        '  pm.collectionVariables.set("heldPaymentIdReject", pm.response.json().id);',
                        '}'
                    ])
                ]
            }
        ]
    }
    collection["item"].append(f3)

    # ==========================================================
    # Folder 04 · Notifications & Event Delivery
    # ==========================================================
    f4 = {
        "name": "04 · Notifications & Event Delivery",
        "description": "Verifies asynchronous domain event propagation from Transactional Outbox through RabbitMQ into notifications table.",
        "item": [
            {
                "name": "01 · Poll and Await Payment Notification Event",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/notifications",
                    "description": "Polls notifications endpoint with bounded retry loop (up to 10 attempts, 1s backoff) until PAYMENT_COMPLETED event is received."
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches NotificationResponse array schema", () => pm.response.to.have.jsonSchema({NOTIFICATIONS_SCHEMA}));',
                        'const rows = pm.response.json();',
                        'pm.test("All notifications belong to authenticated user", () => rows.forEach(n => pm.expect(n.userId).to.eql(pm.collectionVariables.get("userId"))));',
                        'const found = rows.find(n => n.paymentId === pm.collectionVariables.get("paymentId"));',
                        'const attempt = Number(pm.collectionVariables.get("notificationPoll") || 0);',
                        'if (!found && attempt < 10 && pm.response.code === 200) {',
                        '  pm.collectionVariables.set("notificationPoll", String(attempt + 1));',
                        '  setTimeout(() => pm.execution.setNextRequest(pm.info.requestName), 1000);',
                        '} else {',
                        '  pm.test("Payment completed domain event delivered", () => pm.expect(found, "Run folder in Runner for up to 10 polling attempts").to.exist);',
                        '  if (found) pm.collectionVariables.set("notificationId", found.id);',
                        '  pm.collectionVariables.set("notificationPoll", "0");',
                        '}'
                    ])
                ]
            }
        ]
    }
    collection["item"].append(f4)

    # ==========================================================
    # Folder 05 · Negative Scenarios & Error Contracts
    # ==========================================================
    f5 = {
        "name": "05 · Negative Scenarios & Error Contracts",
        "description": "Validates RFC 7807 problem details error format and HTTP status codes across bad input, missing headers, unauthorized calls, and invalid state transitions.",
        "item": [
            {
                "name": "01 · Missing Bearer Token",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments",
                    "description": "Calling protected endpoint without Authorization header must return 401 Unauthorized.",
                    "auth": {"type": "noauth"}
                },
                "event": [make_test_script(rfc7807_tests(401))]
            },
            {
                "name": "02 · Missing Idempotency-Key Header",
                "request": {
                    "method": "POST",
                    "header": [{"key": "Content-Type", "value": "application/json"}],
                    "url": "{{baseUrl}}/api/v1/payments",
                    "description": "Creating payment without mandatory Idempotency-Key header must return 400 Bad Request.",
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "amount": "{{amount}}",\n  "currency": "{{currency}}",\n  "description": "{{paymentDescription}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [make_test_script(rfc7807_tests(400))]
            },
            {
                "name": "03 · Invalid Amount (Below 0.01 Minimum)",
                "request": {
                    "method": "POST",
                    "header": [
                        {"key": "Idempotency-Key", "value": "{{$guid}}"},
                        {"key": "Content-Type", "value": "application/json"}
                    ],
                    "url": "{{baseUrl}}/api/v1/payments",
                    "description": "Payment amount of 0 is rejected by validation with 400 Bad Request.",
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "amount": 0,\n  "currency": "{{currency}}",\n  "description": "{{paymentDescription}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [make_test_script(rfc7807_tests(400))]
            },
            {
                "name": "04 · Nonexistent Payment UUID",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/00000000-0000-0000-0000-000000000000",
                    "description": "Querying unknown payment UUID must return 404 Not Found."
                },
                "event": [make_test_script(rfc7807_tests(404))]
            },
            {
                "name": "05 · Malformed UUID Path Parameter",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/not-a-uuid",
                    "description": "Invalid UUID format in path returns 400 Bad Request."
                },
                "event": [make_test_script(rfc7807_tests(400))]
            },
            {
                "name": "06 · Page Size Exceeds Allowed Limit",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments?size=101",
                    "description": "Requesting page size > 100 must be rejected with 400 Bad Request."
                },
                "event": [make_test_script(rfc7807_tests(400))]
            },
            {
                "name": "07 · Page Offset Exceeds Allowed Limit",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments?page=101&size=100",
                    "description": "Offset > 10,000 items is blocked to protect database query performance."
                },
                "event": [make_test_script(rfc7807_tests(400))]
            },
            {
                "name": "08 · Invalid Sort Property",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments?sort=amount,desc",
                    "description": "Sorting by non-whitelisted property (amount) returns 400 Bad Request."
                },
                "event": [make_test_script(rfc7807_tests(400))]
            },
            {
                "name": "09 · Regular User Forbidden from Listing Fraud Queue",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/under-review",
                    "description": "USER role cannot access /under-review (requires ADMIN or FRAUD_REVIEWER)."
                },
                "event": [make_test_script(rfc7807_tests(403))]
            },
            {
                "name": "10 · Regular User Forbidden from Reviewing Payments",
                "request": {
                    "method": "POST",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/{{paymentId}}/review?decision=APPROVE",
                    "description": "USER role cannot call /{id}/review (requires ADMIN or FRAUD_REVIEWER)."
                },
                "event": [make_test_script(rfc7807_tests(403))]
            },
            {
                "name": "11 · Cannot Confirm Already Completed Payment",
                "request": {
                    "method": "POST",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/{{paymentId}}/confirm",
                    "description": "Transitioning COMPLETED payment to COMPLETED again is an invalid state transition (409 Conflict)."
                },
                "event": [make_test_script(rfc7807_tests(409))]
            },
            {
                "name": "12 · Cannot Cancel Already Completed Payment",
                "request": {
                    "method": "POST",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/{{paymentId}}/cancel",
                    "description": "Transitioning COMPLETED payment to CANCELLED is an invalid state transition (409 Conflict)."
                },
                "event": [make_test_script(rfc7807_tests(409))]
            }
        ]
    }
    collection["item"].append(f5)

    # ==========================================================
    # Folder 06 · Fraud Review & Compliance (Optional)
    # ==========================================================
    f6 = {
        "name": "06 · Fraud Review & Compliance (Optional)",
        "description": "Disabled by default. Set runFraudReview=true and provide reviewerAccessToken with ADMIN or FRAUD_REVIEWER role. Validates manual approval and rejection of held payments.",
        "auth": {
            "type": "bearer",
            "bearer": [{"key": "token", "value": "{{reviewerAccessToken}}", "type": "string"}]
        },
        "event": [
            make_prerequest_script([
                'if (pm.collectionVariables.get("runFraudReview") !== "true") {',
                '  pm.execution.skipRequest();',
                '} else {',
                '  if (!pm.collectionVariables.get("reviewerAccessToken")) {',
                '    throw new Error("Missing reviewerAccessToken variable. Supply a JWT with ADMIN or FRAUD_REVIEWER role.");',
                '  }',
                '}'
            ])
        ],
        "item": [
            {
                "name": "01 · List Payments Under Review",
                "request": {
                    "method": "GET",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/under-review?page=0&size=20&sort=createdAt,desc",
                    "description": "Lists payments currently in UNDER_REVIEW status. Automatically extracts heldPaymentIdApprove and heldPaymentIdReject if not already set."
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PageResponse schema", () => pm.response.to.have.jsonSchema({PAGE_PAYMENT_SCHEMA}));',
                        'const body = pm.response.json();',
                        'pm.test("Review queue has held payment items", () => pm.expect(body.totalElements).to.be.at.least(1));',
                        'if (body.items && body.items.length > 0) {',
                        '  if (!pm.collectionVariables.get("heldPaymentIdApprove")) {',
                        '    pm.collectionVariables.set("heldPaymentIdApprove", body.items[0].id);',
                        '  }',
                        '  if (!pm.collectionVariables.get("heldPaymentIdReject") && body.items.length > 1) {',
                        '    pm.collectionVariables.set("heldPaymentIdReject", body.items[1].id);',
                        '  }',
                        '}'
                    ])
                ]
            },
            {
                "name": "02 · Approve Held Payment",
                "request": {
                    "method": "POST",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/{{heldPaymentIdApprove}}/review?decision=APPROVE",
                    "description": "Reviewer approves payment held in UNDER_REVIEW. Status transitions to PENDING so owner can confirm it."
                },
                "event": [
                    make_prerequest_script([
                        'if (!pm.collectionVariables.get("heldPaymentIdApprove")) {',
                        '  throw new Error("Missing heldPaymentIdApprove. Ensure step 03.09 ran or under-review list returned items.");',
                        '}'
                    ]),
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment transitioned from UNDER_REVIEW to PENDING", () => pm.expect(pm.response.json().status).to.eql("PENDING"));'
                    ])
                ]
            },
            {
                "name": "03 · Reject Held Payment",
                "request": {
                    "method": "POST",
                    "header": [],
                    "url": "{{baseUrl}}/api/v1/payments/{{heldPaymentIdReject}}/review?decision=REJECT",
                    "description": "Reviewer rejects payment held in UNDER_REVIEW. Status transitions to CANCELLED."
                },
                "event": [
                    make_prerequest_script([
                        'if (!pm.collectionVariables.get("heldPaymentIdReject")) {',
                        '  throw new Error("Missing heldPaymentIdReject. Ensure step 03.10 ran or under-review list returned items.");',
                        '}',
                        'if (pm.collectionVariables.get("heldPaymentIdApprove") === pm.collectionVariables.get("heldPaymentIdReject")) {',
                        '  throw new Error("heldPaymentIdApprove and heldPaymentIdReject must be distinct payment UUIDs.");',
                        '}'
                    ]),
                    make_test_script([
                        'pm.test("Status code is 200 OK", () => pm.response.to.have.status(200));',
                        'pm.test("Content-Type is JSON", () => pm.expect(pm.response.headers.get("Content-Type")).to.match(/json/i));',
                        f'pm.test("Response matches PaymentResponse schema", () => pm.response.to.have.jsonSchema({PAYMENT_SCHEMA}));',
                        'pm.test("Payment transitioned from UNDER_REVIEW to CANCELLED", () => pm.expect(pm.response.json().status).to.eql("CANCELLED"));'
                    ])
                ]
            }
        ]
    }
    collection["item"].append(f6)

    # ==========================================================
    # Folder 07 · Session Teardown & Logout
    # ==========================================================
    f7 = {
        "name": "07 · Session Teardown & Logout",
        "description": "Revokes refresh token family and asserts invalidation of terminated session credentials.",
        "item": [
            {
                "name": "01 · Logout & Revoke Refresh Token Family",
                "request": {
                    "method": "POST",
                    "header": [{"key": "Content-Type", "value": "application/json"}],
                    "url": "{{baseUrl}}/api/v1/auth/logout",
                    "description": "Revokes refresh token family. Returns 204 No Content with empty body.",
                    "auth": {"type": "noauth"},
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "refreshToken": "{{refreshToken}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [
                    make_test_script([
                        'pm.test("Status code is 204 No Content", () => pm.response.to.have.status(204));',
                        'pm.test("Response body is empty", () => pm.expect(pm.response.text()).to.eql(""));'
                    ])
                ]
            },
            {
                "name": "02 · Attempt Refresh with Revoked Token (Rejected 401)",
                "request": {
                    "method": "POST",
                    "header": [{"key": "Content-Type", "value": "application/json"}],
                    "url": "{{baseUrl}}/api/v1/auth/refresh",
                    "description": "Attempting to use a revoked refresh token must be rejected with 401 Unauthorized.",
                    "auth": {"type": "noauth"},
                    "body": {
                        "mode": "raw",
                        "raw": '{\n  "refreshToken": "{{refreshToken}}"\n}',
                        "options": {"raw": {"language": "json"}}
                    }
                },
                "event": [make_test_script(rfc7807_tests(401))]
            }
        ]
    }
    collection["item"].append(f7)

    return collection

if __name__ == "__main__":
    col = build_collection()
    target_path = os.path.abspath("src/test/postman/payments-v1.postman_collection.json")
    with open(target_path, "w", encoding="utf-8") as f:
        json.dump(col, f, indent=2, ensure_ascii=False)
    print(f"Postman collection generated successfully at {target_path}")
