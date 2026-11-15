package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FamilyRevokedEvent(
        UUID eventId,
        UUID familyId,
        String reason,
        Instant occurredAt
) implements DomainEvent {

    public FamilyRevokedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(familyId, "familyId must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
