package com.muse.payments.notifications.domain;

import com.muse.payments.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record NotificationRecordedEvent(
        UUID eventId,
        UUID notificationId,
        UUID userId,
        UUID paymentId,
        String type,
        String title,
        String message,
        UUID sourceEventId,
        Instant occurredAt
) implements DomainEvent {

    public NotificationRecordedEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(notificationId, "notificationId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(paymentId, "paymentId must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(message, "message must not be null");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }
}
