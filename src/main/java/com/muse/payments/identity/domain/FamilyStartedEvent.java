package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FamilyStartedEvent(
        UUID eventId,
        UUID familyId,
        UUID userId,
        UUID tokenId,
        String tokenHash,
        Instant expiresAt,
        Instant occurredAt
) implements DomainEvent {

    public FamilyStartedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(familyId, "familyId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tokenId, "tokenId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
