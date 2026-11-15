CREATE TABLE payment_history (
    payment_id UUID NOT NULL,
    stream_version BIGINT NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    status_after VARCHAR(30) NOT NULL,
    actor_id VARCHAR(120),
    reason VARCHAR(500),
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (payment_id, stream_version)
);

CREATE INDEX idx_event_store_stream_pos
    ON event_store (stream_type, global_position);
