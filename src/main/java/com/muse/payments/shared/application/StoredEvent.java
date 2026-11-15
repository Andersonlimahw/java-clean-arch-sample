package com.muse.payments.shared.application;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StoredEvent(
        long globalPosition,
        StreamRef stream,
        long streamVersion,
        UUID eventId,
        String eventType,
        int schemaVersion,
        DomainEvent event,
        EventMetadata metadata,
        Instant occurredAt,
        Instant recordedAt,
        String transactionId
) {

    public StoredEvent {
        if (globalPosition < 1 || streamVersion < 1 || schemaVersion < 1) {
            throw new IllegalArgumentException("Stored event positions and versions must be positive");
        }
        Objects.requireNonNull(stream, "Stream is required");
        Objects.requireNonNull(eventId, "Event id is required");
        Objects.requireNonNull(eventType, "Event type is required");
        Objects.requireNonNull(event, "Event is required");
        Objects.requireNonNull(metadata, "Event metadata is required");
        Objects.requireNonNull(occurredAt, "Event occurrence time is required");
        Objects.requireNonNull(recordedAt, "Event recording time is required");
        Objects.requireNonNull(transactionId, "Transaction id is required");
    }
}
