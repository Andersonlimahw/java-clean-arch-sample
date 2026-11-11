package com.muse.payments.shared.application;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record IntegrationEvent(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        Object payload,
        Map<String, Object> headers,
        Instant occurredAt
) {
    public IntegrationEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(aggregateId, "aggregateId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public static IntegrationEvent create(
            String aggregateType,
            UUID aggregateId,
            String eventType,
            Object payload,
            Map<String, Object> headers
    ) {
        return new IntegrationEvent(
                UUID.randomUUID(),
                aggregateType,
                aggregateId,
                eventType,
                payload,
                headers,
                Instant.now()
        );
    }
}
