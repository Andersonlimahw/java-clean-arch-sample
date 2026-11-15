CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(150) NOT NULL,
    payload JSONB NOT NULL,
    headers JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT NULL,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_pending
    ON outbox_events (next_attempt_at)
    WHERE published_at IS NULL;

CREATE TABLE processed_events (
    consumer VARCHAR(150) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE idempotency_keys (
    key VARCHAR(120) NOT NULL,
    user_id UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    response_code INTEGER NULL,
    response_body JSONB NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    lease_id UUID,
    PRIMARY KEY (user_id, key)
);

CREATE INDEX idx_idempotency_expires
    ON idempotency_keys (expires_at);

CREATE TABLE event_store (
    global_position BIGSERIAL PRIMARY KEY,
    stream_type VARCHAR(100) NOT NULL,
    stream_id UUID NOT NULL,
    stream_version BIGINT NOT NULL CHECK (stream_version > 0),
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(150) NOT NULL,
    schema_version INT NOT NULL CHECK (schema_version > 0),
    payload JSONB NOT NULL,
    metadata JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    transaction_id XID8 NOT NULL DEFAULT pg_current_xact_id(),
    UNIQUE (stream_type, stream_id, stream_version)
);

CREATE TABLE snapshots (
    stream_type VARCHAR(100) NOT NULL,
    stream_id UUID NOT NULL,
    stream_version BIGINT NOT NULL CHECK (stream_version > 0),
    schema_version INT NOT NULL CHECK (schema_version > 0),
    state JSONB NOT NULL,
    taken_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (stream_type, stream_id)
);

CREATE TABLE projection_checkpoints (
    projection VARCHAR(100) PRIMARY KEY,
    last_position BIGINT NOT NULL DEFAULT 0 CHECK (last_position >= 0),
    last_transaction_id XID8,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE FUNCTION reject_event_store_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'event_store rows are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER event_store_immutable
    BEFORE UPDATE OR DELETE ON event_store
    FOR EACH ROW EXECUTE FUNCTION reject_event_store_mutation();
