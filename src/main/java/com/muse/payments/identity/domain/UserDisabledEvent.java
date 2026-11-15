package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record UserDisabledEvent(
        UUID eventId,
        UUID userId,
        Instant occurredAt
) implements DomainEvent {

    public UserDisabledEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public UserDisabledEvent(UUID userId, Instant occurredAt) {
        this(UUID.randomUUID(), userId, occurredAt);
    }
}
