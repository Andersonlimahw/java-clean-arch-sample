package com.muse.payments.notifications.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Notification {

    private final UUID id;
    private final UUID userId;
    private final UUID paymentId;
    private final String type;
    private final String title;
    private final String message;
    private final Instant createdAt;

    public Notification(
            UUID id,
            UUID userId,
            UUID paymentId,
            String type,
            String title,
            String message,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "paymentId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static Notification paymentCreated(UUID userId, UUID paymentId, BigDecimal amount, String currency) {
        UUID id = UUID.randomUUID();
        String title = "Payment Received";
        String message = String.format("A new payment of %s %s was successfully created.", amount, currency);
        return new Notification(id, userId, paymentId, "PAYMENT_CREATED", title, message, Instant.now());
    }

    public static Notification paymentHeldForReview(UUID userId, UUID paymentId, BigDecimal amount, String currency) {
        return new Notification(
                UUID.randomUUID(),
                userId,
                paymentId,
                "PAYMENT_HELD_FOR_REVIEW",
                "Payment under review",
                String.format("A payment of %s %s is under review and was not approved.", amount, currency),
                Instant.now()
        );
    }

    public static Notification paymentCompleted(UUID userId, UUID paymentId) {
        return new Notification(
                UUID.randomUUID(),
                userId,
                paymentId,
                "PAYMENT_COMPLETED",
                "Payment Completed",
                "Payment " + paymentId + " was completed.",
                Instant.now()
        );
    }

    public static Notification paymentCancelled(UUID userId, UUID paymentId, String reason) {
        String why = reason == null || reason.isBlank() ? "no reason provided" : reason;
        return new Notification(
                UUID.randomUUID(),
                userId,
                paymentId,
                "PAYMENT_CANCELLED",
                "Payment Cancelled",
                "Payment " + paymentId + " was cancelled: " + why,
                Instant.now()
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
