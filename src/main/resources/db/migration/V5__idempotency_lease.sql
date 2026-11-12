-- Distinguishes an in-flight acquisition from a later reclaim of the same key.
-- Completion and release match this lease so a request that outlives expires_at
-- cannot overwrite or delete the row a newer request now owns.
ALTER TABLE idempotency_keys
    ADD COLUMN lease_id UUID;
