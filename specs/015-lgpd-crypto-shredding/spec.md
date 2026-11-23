# Spec 015 — LGPD/GDPR erasure through crypto-shredding

- **Status**: Implemented
- **Updated**: 2026-11-21
- **Related**: [ADR 0020](../../docs/adr/0020-crypto-shredding-and-kek-management.md), [ADR 0016](../../docs/adr/0016-event-sourcing-with-hybrid-projections.md), [Spec 012](../012-event-sourcing/spec.md)

## Goal

Honor the right to erasure (LGPD art. 18 VI, GDPR art. 17) without breaking the
append-only `event_store`. Personal data inside event payloads is encrypted with a
per-user data key. Erasure destroys that key, so the immutable history keeps its rows,
positions, and hashes while the personal fields become unreadable. Read models and
derived stores are sanitized in the same transaction.

## Decisions taken before coding

1. **Event-store count.** The erasure itself is a fact, so it appends exactly one
   `identity.user-anonymization-requested` event to the user stream. The acceptance
   criterion is therefore *"every pre-existing row is byte-identical
   (`global_position`, `event_id`, `md5(payload)`, `md5(metadata)`), the table has
   exactly one more row, that row belongs to the erased user and carries no personal
   data, and no `UPDATE`/`DELETE` reaches `event_store`"*. Any trigger hit would abort the
   transaction with SQLSTATE `55000`, so a committed erasure plus identical hashes proves
   the trigger never fired.
2. **Key encryption key (KEK).** Decided in ADR 0020: envelope encryption, the KEK comes
   from the environment (`LGPD_KEK_BASE64`, `LGPD_KEK_ID`), one previous KEK is accepted
   for unwrap during rotation, the `prod` profile fails at startup without a KEK, and
   local/test generate one ephemeral KEK per JVM. No key material is committed.
3. **User references.** `payments.user_id` and `notifications.user_id` stay. They are
   random UUIDs; once the user row, the credentials, and the data key are gone they no
   longer identify a person, and financial records must be kept (LGPD art. 16 I). The
   free-text columns that can carry personal data are redacted instead.

## Scope & acceptance criteria

1. **Migration `V10__add_user_crypto_keys.sql`**: `user_crypto_keys` with `user_id` UUID
   PK, `encrypted_data_key` BYTEA NOT NULL, `kek_id` VARCHAR(64) NOT NULL, `algorithm`
   VARCHAR(32) NOT NULL DEFAULT `'AES-256-GCM'`, `created_at` TIMESTAMPTZ NOT NULL
   DEFAULT `NOW()`, `destroyed_at` TIMESTAMPTZ NULL. `kek_id` is an addition to the
   original task text; it records which KEK wrapped the key so rotation can unwrap it.
2. **Crypto primitives** in `shared/infrastructure/security`:
   - `AesGcmCryptoService`: `AES/GCM/NoPadding`, 256-bit keys, a fresh 12-byte IV from
     `SecureRandom` on every call, a 128-bit tag, and caller-supplied AAD.
   - `KeyEncryptionKeys`: the active and optional previous KEK, built from
     configuration by `LgpdCryptoConfiguration`.
   - `UserCryptoKeyAdapter`: creates, unwraps, and destroys per-user data keys.
     Creation is `INSERT … ON CONFLICT DO NOTHING` followed by `SELECT … FOR SHARE`,
     so an erasure waits for an in-flight append that already holds the key.
     Destruction overwrites `encrypted_data_key` with an empty array and sets
     `destroyed_at`; a user without a key gets a tombstone row so no later append can
     create a fresh key for them.
3. **Payload protection in the serializer.** `EventSerializer` calls
   `PersonalDataPayloadProtector` on append and on every read path (aggregate load,
   async projections, rebuilds). Modules declare protected fields as
   `PersonalDataFields` beans next to their event registrations:

   | Event type | Subject field | Protected fields |
   |---|---|---|
   | `identity.user-registered` | `userId` | `email`, `displayName` |
   | `payments.payment-initiated` | `userId` | `description` |
   | `payments.payment-held-for-review` | `userId` | `description` |
   | `payments.payment-released-from-review` | `userId` | `description` |
   | `payments.payment-cancelled` | `userId` | `reason` |
   | `payments.payment-rejected-in-review` | `userId` | `reason` |
   | `notifications.notification-recorded` | `userId` | `message` |

   A protected value is stored as `lgpd:v1:<subjectId>:<base64(iv‖ciphertext‖tag)>`.
   The AAD binds the event type, field name, and subject, so a value cannot be moved to
   another event, field, or user. On read, a value whose key is destroyed or missing
   becomes `[REDACTED_LGPD]`; a tampered value or a wrong KEK fails closed. Values
   written before V10 have no prefix and are returned unchanged. A user whose key is
   already destroyed never gets plaintext written: new protected values are stored as
   `[REDACTED_LGPD]`. Schema versions do not change.
4. **Use case** `identity/application/AnonymizeUserUseCase`:
   - the caller must be the user or hold `ADMIN`, otherwise `403`; an unknown user is
     `404`;
   - an already anonymized user is a no-op (idempotent `204`, no new event);
   - in one transaction: destroy the data key, append `UserAnonymizationRequested`
     through the aggregate (the `users` projector writes
     `email = 'redacted-' || id || '@deleted.lgpd'`, `display_name = '[REDACTED_LGPD]'`,
     `enabled = false`), delete `user_credentials`, and run every
     `PersonalDataErasureParticipant`;
   - after commit, evict the user's Redis keys.
5. **Erasure participants** (one per module, same transaction):
   - payments: `payments.description` and the `reason` of cancelled/rejected rows in
     `payment_history` become `[REDACTED_LGPD]`; after commit the
     `payment:cache:<paymentId>` entries and `ratelimit:payments:write:<userId>` are
     deleted;
   - notifications: `notifications.message` becomes `[REDACTED_LGPD]`;
   - shared: `outbox_events` payloads whose `userId` is the subject get their
     `email`, `displayName`, `description`, and `reason` keys redacted.
   A later projection rebuild produces the same redacted values, because the encrypted
   fields can no longer be decrypted.
6. **HTTP**: `DELETE /api/v1/users/{userId}` with a bearer token. `204` on success and
   on repeat, `403 application/problem+json` for another non-admin user, `404` for an
   unknown id. After erasure the old credentials no longer log in and refresh tokens
   are rejected because the user is disabled.
7. **Tests**:
   - unit: AES-GCM round trip, unique IVs, tamper/AAD/key failures; KEK configuration
     (prod without KEK fails, wrong length fails, previous id must differ); payload
     protector (encrypt, reveal, destroyed → redacted, legacy passthrough, cross-field
     swap fails); `User.anonymize()`; use-case authorization;
   - integration `UserAnonymizationIT` (Testcontainers): ciphertext at rest, identical
     pre-existing rows plus exactly one new row, rehydration returns `[REDACTED_LGPD]`,
     read models and outbox redacted, cache evicted, login refused, `403`/`404`/repeat.
8. **Gate**: `scripts/ci-local.sh` (includes `mvn -B clean verify`),
   `scripts/validate-docs.sh`, and `python3 -I scripts/validate-ai-tooling.py` pass.

## Out of scope

- Re-wrapping existing data keys after a KEK rotation (a later job; unwrap with the
  previous KEK already works).
- A KMS/Vault adapter. The `KeyEncryptionKeys` seam is where it plugs in.
- Physical erasure from PostgreSQL dead tuples, WAL, and backups. The destroyed data key
  is unusable without the KEK; backup retention is an operational policy.
- Rows written before V10, which have no ciphertext to shred. Databases are treated as
  new (ADR 0016).
- Messages already delivered to RabbitMQ consumers.

## Eval

```bash
rtk mvn -B -Dit.test=UserAnonymizationIT test-compile failsafe:integration-test failsafe:verify
```

Expected result: `UserAnonymizationIT` passes with zero failures.
