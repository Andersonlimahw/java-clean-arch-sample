package com.muse.payments.notifications.application;

import com.muse.payments.notifications.domain.Notification;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID userId,
        UUID paymentId,
        String type,
        String title,
        String message,
        Instant createdAt
) {
    public static NotificationResponse from(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        return new NotificationResponse(
                notification.getId(),
                notification.getUserId(),
                notification.getPaymentId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getCreatedAt()
        );
    }
}
