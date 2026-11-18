CREATE INDEX idx_outbox_published_at
    ON outbox_events (published_at)
    WHERE published_at IS NOT NULL;

CREATE INDEX idx_processed_events_processed_at
    ON processed_events (processed_at);

CREATE INDEX idx_refresh_tokens_expires_at
    ON refresh_tokens (expires_at);
