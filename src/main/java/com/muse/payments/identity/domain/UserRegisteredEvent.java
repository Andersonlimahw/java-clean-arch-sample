package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UserRegisteredEvent(
        UUID eventId,
        UUID userId,
        String email,
        String displayName,
        Set<String> roles,
        Instant occurredAt
) implements DomainEvent {

    public UserRegisteredEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(displayName, "displayName must not be null");
        roles = roles == null || roles.isEmpty() ? Set.of("USER") : Set.copyOf(roles);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    public UserRegisteredEvent(UUID userId, String email, String displayName, Set<String> roles, Instant occurredAt) {
        this(UUID.randomUUID(), userId, email, displayName, roles, occurredAt);
    }
}
