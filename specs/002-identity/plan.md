# Architecture Plan: PR2 — Identity Module

## 1. Domain Model
- `User` (`AggregateRoot<UUID>`):
  - Invariants: Valid email format, non-empty display name, secure password.
  - State: `id`, `email`, `passwordHash`, `displayName`, `roles` (`Set<String>`), `enabled`, `createdAt`, `updatedAt`, `version`.
- `RefreshToken`:
  - State: `id`, `userId`, `tokenHash` (SHA-256), `familyId` (UUID), `isRevoked`, `expiresAt`, `createdAt`.
  - Behavior: `rotate()`, `revoke()`, `isExpired()`.

## 2. CQRS Handlers
- `RegisterCommandHandler` -> checks email uniqueness via `UserRepository`, hashes password using `PasswordEncoder`, persists `User`.
- `LoginCommandHandler` -> retrieves user, verifies password, generates `familyId`, stores hashed refresh token, generates RS256 JWT access token via `JwtTokenIssuer`.
- `RefreshTokenCommandHandler` -> hashes incoming refresh token, finds record by hash:
  - If not found or expired -> throws `AuthenticationException` (401).
  - If already revoked -> triggers **family revocation** (all tokens with same `familyId` are marked revoked) -> throws `AuthenticationException` (401: Security breach detected).
  - If valid -> revokes current token, creates child token in same family, issues new access token.
- `LogoutCommandHandler` -> revokes the refresh token family.

## 3. Database Schema
Migration: `src/main/resources/db/migration/V2__identity.sql`.
Tables: `users`, `refresh_tokens`.
Constraints: `users_email_key` UNIQUE, `refresh_tokens_token_hash_key` UNIQUE.
Foreign Key: `refresh_tokens.user_id` -> `users.id` ON DELETE CASCADE (intra-module FK).

## 4. Rate Limiting
`AuthRateLimiter`: Sliding window tracking client IP addresses. Redis-backed with automatic fallback to local concurrent memory map if Redis is unreachable.
Limit: 20 requests per minute per IP for `/api/v1/auth/**`. Exceeding returns HTTP 429 with `Retry-After: 60`.
