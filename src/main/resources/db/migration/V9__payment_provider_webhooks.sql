-- Provider-neutral PSP linkage on the payments projection and webhook dedupe state.
-- No FK to payments: the projection is rebuildable and truncation must not be blocked.

ALTER TABLE payments ADD COLUMN provider VARCHAR(30);
ALTER TABLE payments ADD COLUMN provider_reference VARCHAR(120);

CREATE UNIQUE INDEX uq_payments_provider_reference
    ON payments (provider, provider_reference)
    WHERE provider_reference IS NOT NULL;

CREATE TABLE payment_provider_events (
    provider    VARCHAR(30)  NOT NULL,
    event_id    VARCHAR(120) NOT NULL,
    event_type  VARCHAR(120) NOT NULL,
    payment_id  UUID,
    outcome     VARCHAR(30)  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (provider, event_id)
);

CREATE INDEX idx_payment_provider_events_received_at
    ON payment_provider_events (received_at);
