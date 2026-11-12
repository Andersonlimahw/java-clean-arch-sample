# ADR 0009 — First-party RS256 JWT instead of an external IdP

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

The API needs authenticated callers, roles, and ownership. An external identity provider (Auth0, Cognito, Keycloak, Google) is the right long-term choice for a multi-application company. This repository is one service with its own user table and must run in local Docker without a third-party account.

## Decision

Identity is inside the monolith.

- Passwords are BCrypt strength 12 (`BCryptPasswordEncoder(12)` in `SecurityConfig`).
- Access tokens are RS256 JWTs. TTL is `app.jwt.access-token-ttl` / `ACCESS_TOKEN_TTL`, default `PT15M`. Claims set by `JwtTokenIssuer`: `sub` (user id), `roles`, `iss`, `aud`, `exp`, `iat`, `jti`, `typ=access`.
- The resource server validates signature, issuer, audience, and expiry. A missing `typ` is accepted; a present `typ` must be `access` (`JwtKeyConfig`). Keys come from `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY` (`app.jwt.private-key-pem` / `public-key-pem`). When the PEMs are blank, an ephemeral 2048-bit pair is generated only if `prod` is inactive and the active profiles include `local`, `test`, `default`, or `migration`. Any other case, including `prod` combined with `migration`, refuses to start.
- Refresh tokens are opaque, stored as SHA-256 hashes, TTL `REFRESH_TOKEN_TTL` default `P7D`, rotated on use, and revoked for the whole family if a rotated token is presented again.
- Register, login, and refresh are rate-limited by client IP (`AuthRateLimiter`): Redis key `ratelimit:auth:{ip}`, 20 requests per one-minute window, in-memory fallback if Redis throws. The response is 429 with `Retry-After: 60`. Logout is not limited.
- Problem details omit stack traces. `.env.example` leaves `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY` empty. `deploy/k8s/base/secret.example.yaml` contains truncated PEM placeholders, not a usable key pair.

## Consequences

- Password reset and account lockout are not implemented. Key rotation today means deploying a new pair; outstanding access tokens remain valid until `exp` (15 minutes at the default TTL). The rate limiter is the lockout control that exists, and it is per IP rather than per account.
- An external IdP can replace `LoginCommandHandler` later if the JWT claims stay the same. The resource server already treats the token as a bearer credential.
- Refresh tokens in our database are not reusable across products.

## Alternatives

| Option | Why not |
|---|---|
| External IdP now | Requires an account, network, and a contract change for local tests. |
| HS256 shared secret | Every instance that verifies can also mint tokens. RS256 keeps verification on the public key. |
| Sessions | Breaks horizontal scaling unless the session store is shared. JWT matches the stateless Deployment. |
