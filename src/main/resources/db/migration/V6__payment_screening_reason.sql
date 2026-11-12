ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS screening_reason VARCHAR(500);
