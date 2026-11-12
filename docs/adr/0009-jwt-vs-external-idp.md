# ADR 0009 — First-party RS256 JWT instead of an external IdP

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The API needs authenticated callers, roles, and ownership. An external identity provider (Auth0, Cognito, Keycloak, Google) is the right long-term choice for a multi-application company. This repository is one service with its own user table and must run in local Docker without a third-party account.

## Decision

Identity is inside the monolith.

- Passwords are BCrypt strength 12.
- Access tokens are RS256 JWTs, 15 minutes, claims `sub`, `roles`, `iss`, `aud`, `exp`, `iat`, `jti`, `typ=access`.
- The resource server validates signature, issuer, audience, and expiry. Keys come from `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY`. Local and test profiles may generate an ephemeral pair. Any other profile refuses to start without the PEM values.
- Refresh tokens are opaque, stored as SHA-256 hashes, rotated on use, and revoked for the whole family if a rotated token is presented again.
- Auth endpoints are rate-limited (Redis, with an in-memory fallback) and return 429 plus `Retry-After`.
- No private key, password, or stack trace is written to git or to problem details. `.env.example` has empty key fields.

## Consequences

- We operate password reset, lockout, and key rotation ourselves. Key rotation today means deploying a new pair and accepting that outstanding access tokens die within 15 minutes.
- An external IdP can replace `LoginCommandHandler` later if the JWT claims stay the same. The resource server already treats the token as a bearer credential.
- Refresh tokens in our database are not reusable across products.

## Alternatives

| Option | Why not |
|---|---|
| External IdP now | Requires an account, network, and a contract change for local tests. |
| HS256 shared secret | Every instance that verifies can also mint tokens. RS256 keeps verification on the public key. |
| Sessions | Breaks horizontal scaling unless the session store is shared. JWT matches the stateless Deployment. |
