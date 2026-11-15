# Specification: PR2 — Identity & Authentication Module

**Module**: `com.muse.payments.identity`  
**Feature Branch**: `feat/pr2-identity`  
**Status**: Delivered — merged in PR #3 (`af3760d`, 2026-11-11)  

---

## 1. Objectives

Implement the user identity, credential management, role-based access control (RBAC), and authentication lifecycle using Clean Architecture and DDD:
1. User registration with BCrypt password hashing.
2. User login issuing signed RS256 JWT access tokens (15-min TTL) and opaque refresh tokens (7-day TTL).
3. Secure refresh token rotation with token family tracking and automated reuse attack revocation.
4. User logout with token family invalidation.
5. Auth endpoint rate limiting (HTTP 429 Too Many Requests with `Retry-After`).
6. Zero cross-module database foreign keys (intra-module foreign keys only).

---

## 2. API Contract (`/api/v1/auth`)

### 2.1 POST `/api/v1/auth/register`
- **Request**:
  ```json
  {
    "email": "user@example.com",
    "password": "StrongPassword123!",
    "displayName": "Alice Smith"
  }
  ```
- **Responses**:
  - `201 Created`: User created. Response body: `{ "id": "uuid", "email": "user@example.com", "displayName": "Alice Smith" }`.
  - `400 Bad Request`: Validation failure (invalid email format, password < 8 characters).
  - `409 Conflict`: Email already registered (`ConflictException`).

### 2.2 POST `/api/v1/auth/login`
- **Request**:
  ```json
  {
    "email": "user@example.com",
    "password": "StrongPassword123!"
  }
  ```
- **Responses**:
  - `200 OK`:
    ```json
    {
      "accessToken": "<jwt-rs256>",
      "refreshToken": "<opaque-base64-random-token>",
      "tokenType": "Bearer",
      "expiresIn": 900
    }
    ```
  - `401 Unauthorized`: Bad credentials.

### 2.3 POST `/api/v1/auth/refresh`
- **Request**:
  ```json
  {
    "refreshToken": "<opaque-base64-random-token>"
  }
  ```
- **Responses**:
  - `200 OK`: Rotated pair (new `accessToken` and new `refreshToken`).
  - `401 Unauthorized`: Expired, revoked, or non-existent token.
  - **Reuse Detection**: If an already-rotated or revoked token is reused, all active tokens in that family are immediately revoked, and 401 is returned.

### 2.4 POST `/api/v1/auth/logout`
- **Request**:
  ```json
  {
    "refreshToken": "<opaque-base64-random-token>"
  }
  ```
- **Responses**:
  - `204 No Content`: Refresh token family revoked.
