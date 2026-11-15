package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record TokenRevokedEvent(
        UUID eventId,
        UUID familyId,
        String tokenHash,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    public TokenRevokedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(familyId, "familyId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
