CREATE TABLE payments (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    description VARCHAR(255),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    screening_reason VARCHAR(500),
    held_reason VARCHAR(500),
    reviewer_id UUID,
    reviewed_at TIMESTAMPTZ
);

CREATE INDEX idx_payments_user_created_at_id
    ON payments (user_id, created_at DESC, id DESC);
