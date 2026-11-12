-- Isolated payment optimistic-lock column.
-- V3 already creates payments.version for databases built from this repo.
-- IF NOT EXISTS keeps this migration safe there, and NOT NULL DEFAULT 0
-- backfills any payments table that does not have the column yet.
ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
