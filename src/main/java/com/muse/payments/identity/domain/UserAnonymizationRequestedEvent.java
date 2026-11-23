package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The user's personal data was erased on request (LGPD art. 18 VI, GDPR art. 17). Carries no
 * personal data itself; the user's data key is destroyed in the same transaction.
 */
public record UserAnonymizationRequestedEvent(
        UUID eventId,
        UUID userId,
        UUID requestedBy,
        Instant occurredAt
) implements DomainEvent {

    public UserAnonymizationRequestedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(requestedBy, "requestedBy must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public UserAnonymizationRequestedEvent(UUID userId, UUID requestedBy, Instant occurredAt) {
        this(UUID.randomUUID(), userId, requestedBy, occurredAt);
    }
}
