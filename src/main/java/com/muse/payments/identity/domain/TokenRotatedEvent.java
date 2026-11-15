package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TokenRotatedEvent(
        UUID eventId,
        UUID familyId,
        UUID userId,
        UUID newTokenId,
        String oldTokenHash,
        String newTokenHash,
        Instant expiresAt,
        Instant occurredAt
) implements DomainEvent {

    public TokenRotatedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(familyId, "familyId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(newTokenId, "newTokenId must not be null");
        Objects.requireNonNull(oldTokenHash, "oldTokenHash must not be null");
        Objects.requireNonNull(newTokenHash, "newTokenHash must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
