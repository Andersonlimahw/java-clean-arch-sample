-- Per-user data keys for crypto-shredding (ADR 0020).
-- encrypted_data_key is the DEK wrapped by the KEK named in kek_id; the KEK never reaches the database.
-- Erasure empties encrypted_data_key and sets destroyed_at; the row stays as a tombstone.
-- No FK to users: the users table is a rebuildable projection.
CREATE TABLE user_crypto_keys (
    user_id            UUID PRIMARY KEY,
    encrypted_data_key BYTEA       NOT NULL,
    kek_id             VARCHAR(64) NOT NULL,
    algorithm          VARCHAR(32) NOT NULL DEFAULT 'AES-256-GCM',
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    destroyed_at       TIMESTAMPTZ NULL,
    CONSTRAINT ck_user_crypto_keys_destroyed
        CHECK ((destroyed_at IS NULL) = (octet_length(encrypted_data_key) > 0))
);
