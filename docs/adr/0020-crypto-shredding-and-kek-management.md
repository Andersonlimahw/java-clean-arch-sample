# ADR 0020 — Crypto-shredding for erasure and key-encryption-key management

- **Status:** Accepted
- **Date:** 2026-11-21
- **Complements:** [ADR 0016](0016-event-sourcing-with-hybrid-projections.md), [ADR 0018](0018-production-surface-and-operational-hardening.md)

## Context

ADR 0016 made `event_store` append-only, enforced by a trigger, and recorded that event
payloads hold personal data (email, display name, free-text payment descriptions and
reasons) with no erasure flow. LGPD and GDPR require erasure on request. Deleting or
rewriting events would break replay, positions, and the trigger contract.

## Decision

**Crypto-shredding with envelope encryption.**

- Each user has one 256-bit data key (DEK), created on the first protected append and
  stored in `user_crypto_keys.encrypted_data_key`, wrapped with AES-256-GCM under a
  key-encryption key (KEK). The AAD is the user id and `kek_id`, so a wrapped key cannot
  be moved to another user.
- Protected payload fields are declared per event type by the owning module and
  encrypted with the subject's DEK (AES-256-GCM, random 12-byte IV per value, AAD =
  event type, field, subject). The serializer does this on append and reverses it on
  every read path, so domain events and aggregates stay plain Java.
- Erasure destroys the DEK (empty array plus `destroyed_at`) and appends one
  `identity.user-anonymization-requested` event. Existing rows are never touched; their
  protected fields read as `[REDACTED_LGPD]` from then on. Read models are sanitized in
  the same transaction by per-module participants, and a rebuild reaches the same state.
- The erasure criterion is "pre-existing rows byte-identical plus exactly one new row",
  not "identical row count", because the erasure is itself a recorded fact.

**KEK management.**

- The KEK is never in the repository, in logs, or in the database. It comes from
  `LGPD_KEK_BASE64` (32 bytes, base64) with an identifier `LGPD_KEK_ID`
  (default `lgpd-kek-v1`). `kek_id` is stored next to every wrapped DEK.
- Rotation mirrors JWT rotation (ADR 0018): set the new key as active and the old one as
  `LGPD_PREVIOUS_KEK_BASE64`/`LGPD_PREVIOUS_KEK_ID`. New DEKs are wrapped with the active
  KEK; old ones still unwrap. The identifiers must differ.
- The `prod` profile fails at startup without a valid KEK. `local`, `test`, `default`,
  and `migration` (without `prod`) generate one ephemeral KEK per JVM and log a warning.
  With an ephemeral KEK, a DEK wrapped by a KEK this JVM does not know reads as redacted
  instead of failing, so a restarted local app keeps working; with a configured KEK the
  same case fails closed.
- A GCM failure under a known KEK is tampering or corruption and always fails closed.
  Redaction happens only for a destroyed or missing DEK.
- In production the KEK is injected by the platform's secret manager. A KMS or Vault
  adapter can replace `KeyEncryptionKeys` without changing the schema or the payload
  format.

## Consequences

- Erasure is one transaction and leaves the event stream verifiable.
- Each read of a protected event costs one indexed key lookup; there is no DEK cache, so
  a destroyed key can never be served from memory by another instance.
- Losing the KEK makes all protected history unreadable. KEK backup and access control
  are operational duties, like the JWT private key.
- PostgreSQL keeps old tuple versions until `VACUUM`, and WAL and backups keep the
  wrapped DEK for their retention period. Without the KEK they are unusable; backup
  retention bounds the residual window.
- Rows written before this change are plaintext and cannot be shredded. Databases are
  treated as new (ADR 0016).
- `payments.user_id` and `notifications.user_id` stay as pseudonymous references
  needed for financial record retention.

## Alternatives

| Option | Why not |
|---|---|
| Disable the trigger and rewrite events | Breaks the immutability contract, replay hashes, and audit. |
| Store personal data outside events and keep references only | Larger redesign of every projection and event contract; crypto-shredding reaches the same erasure with the current events. |
| One global key | Cannot erase a single user. |
| KEK in configuration files or a well-known local default | A committed key is a leaked key; ephemeral local keys give the same developer experience. |
| Cache unwrapped DEKs | Faster reads, but another instance could keep decrypting after erasure. |

## Eval

```bash
rtk mvn -B -Dit.test=UserAnonymizationIT test-compile failsafe:integration-test failsafe:verify
```

Expected result: the integration test proves ciphertext at rest, byte-identical
pre-existing events plus one new row, redacted rehydration, and sanitized read models.
