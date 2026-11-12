# Specification: PR3 — Payments Aggregate, CQRS & Idempotency RFC/IETF

**Module**: `com.muse.payments.payments`  
**Feature Branch**: `feat/pr3-payments`  
**Status**: In Progress  

---

## 1. Objectives

Implement the core Payments processing module adhering strictly to Domain-Driven Design (DDD), Clean Architecture, CQRS, and the IETF RFC Idempotency-Key standard (`specs/010-idempotency-immutability-patterns/`):
1. **Domain Purity**: `Payment` aggregate root, `Money` value object, `PaymentStatus` lifecycle, domain events (`PaymentCreatedEvent`, `PaymentCompletedEvent`, `PaymentCancelledEvent`). No Spring, JPA, or Jackson annotations in domain classes.
2. **Transactional Outbox**: All state transitions emit immutable integration events to the `outbox_events` table in the aggregate's local transaction with `Propagation.MANDATORY`.
3. **Optimistic Locking**: Concurrent updates on payments verify the `@Version` token to prevent lost updates (409 Conflict).
4. **Idempotency Standards**: `POST /api/v1/payments` mandates the `Idempotency-Key` HTTP header. The key, payment, and cached response commit in one transaction. Replayed requests with the same canonical payload (amount scaled to 2 decimals, currency uppercased) return the cached response with `Idempotent-Replayed: true`. Payload mismatches return HTTP 409 Conflict. A committed in-flight key returns HTTP 409 Conflict while it is unexpired. A failed create rolls the key back. Completion updates only the unexpired `PROCESSING` row for the lease that acquired it, and a missed completion rolls the payment back. An expired key is a new payment.
5. **Ownership & IDOR Protection**: Payments belong to a user (`user_id`). Accessing a payment owned by another user yields HTTP 404 Not Found to prevent resource enumeration. `ADMIN` may read any payment by id. List, confirm, and cancel stay owner-scoped for every role.
6. **Roles**: `/api/v1/payments` and `/api/v1/payments/**` require role `USER` or `ADMIN`. Any other authenticated role receives HTTP 403 `application/problem+json`. Missing or invalid tokens stay HTTP 401.
7. **Zero Cross-Module Foreign Keys**: `payments.user_id` has no database FK pointing to `users.id`. Boundaries are verified by ArchUnit and Spring Modulith.

---

## 2. API Contract (`/api/v1/payments`)

### 2.1 POST `/api/v1/payments`
- **Headers**:
  - `Authorization: Bearer <jwt>` (Required)
  - `Idempotency-Key: <string-or-uuid>` (Required, max 120 chars)
- **Request Body**:
  ```json
  {
    "amount": 150.00,
    "currency": "BRL",
    "description": "Subscription payment"
  }
  ```
- **Responses**:
  - `201 Created`:
    - Headers: `Location: /api/v1/payments/{id}`, `X-Correlation-Id: ...`
    - Body:
      ```json
      {
        "id": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
        "userId": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
        "amount": 150.00,
        "currency": "BRL",
        "description": "Subscription payment",
        "status": "PENDING",
        "createdAt": "2026-11-11T04:00:00Z",
        "updatedAt": "2026-11-11T04:00:00Z",
        "version": 0
      }
      ```
  - `201 Created` (Idempotent Replay): Same body as original, with header `Idempotent-Replayed: true`.
  - `400 Bad Request`: Missing `Idempotency-Key` header, amount <= 0, or unsupported currency. The idempotency key is not consumed, so the same key can be retried with a valid body.
  - `401 Unauthorized`: Missing or invalid Bearer JWT.
  - `403 Forbidden`: Authenticated caller does not have role `USER` or `ADMIN`.
  - `409 Conflict`: Idempotency key payload mismatch or concurrent in-flight request while the key is unexpired. An expired key is acquired again as a new payment.

### 2.2 GET `/api/v1/payments/{id}`
- **Headers**: `Authorization: Bearer <jwt>`
- **Responses**:
  - `200 OK`: Payment response representation. `ADMIN` receives this for any existing payment.
  - `403 Forbidden`: Authenticated caller does not have role `USER` or `ADMIN`.
  - `404 Not Found`: Payment does not exist, or the caller is not `ADMIN` and does not own it (anti-enumeration).

### 2.3 GET `/api/v1/payments`
- **Headers**: `Authorization: Bearer <jwt>`
- **Query Params**: `page` (default 0), `size` (default 20)
- **Responses**:
  - `200 OK`: Page list of payments owned by the authenticated user, including when the caller is `ADMIN`.
  - `403 Forbidden`: Authenticated caller does not have role `USER` or `ADMIN`.

### 2.4 POST `/api/v1/payments/{id}/confirm`
- **Headers**: `Authorization: Bearer <jwt>`
- **Responses**:
  - `200 OK`: Payment transitioned from `PENDING` to `COMPLETED`. Outbox event `PaymentCompleted` persisted.
  - `403 Forbidden`: Authenticated caller does not have role `USER` or `ADMIN`.
  - `404 Not Found`: Payment not found or not owned by the caller, including when the caller is `ADMIN`.
  - `409 Conflict`: Invalid state transition (e.g. payment already CANCELLED or COMPLETED) or optimistic lock conflict.

### 2.5 POST `/api/v1/payments/{id}/cancel`
- **Headers**: `Authorization: Bearer <jwt>`
- **Responses**:
  - `200 OK`: Payment transitioned from `PENDING` to `CANCELLED`. Outbox event `PaymentCancelled` persisted.
  - `403 Forbidden`: Authenticated caller does not have role `USER` or `ADMIN`.
  - `404 Not Found`: Payment not found or not owned by the caller, including when the caller is `ADMIN`.
  - `409 Conflict`: Invalid state transition (e.g. payment already COMPLETED or CANCELLED) or optimistic lock conflict.
