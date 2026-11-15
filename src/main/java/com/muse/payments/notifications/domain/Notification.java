package com.muse.payments.notifications.domain;

import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.domain.EventSourcedAggregate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Notification extends EventSourcedAggregate<UUID> {

    private UUID id;
    private UUID userId;
    private UUID paymentId;
    private String type;
    private String title;
    private String message;
    private Instant createdAt;

    private Notification() {
    }

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

    public static Notification record(
            UUID userId,
            UUID paymentId,
            String type,
            String title,
            String message,
            UUID sourceEventId
    ) {
        Notification notification = new Notification();
        notification.raise(new NotificationRecordedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                paymentId,
                type,
                title,
                message,
                sourceEventId,
                Instant.now()
        ));
        return notification;
    }

    public static Notification paymentCreated(UUID userId, UUID paymentId, BigDecimal amount, String currency) {
        return paymentCreated(userId, paymentId, amount, currency, null);
    }

    public static Notification paymentCreated(
            UUID userId, UUID paymentId, BigDecimal amount, String currency, UUID sourceEventId) {
        return record(
                userId,
                paymentId,
                "PAYMENT_CREATED",
                "Payment Received",
                String.format("A new payment of %s %s was successfully created.", amount, currency),
                sourceEventId
        );
    }

    public static Notification paymentHeldForReview(UUID userId, UUID paymentId, BigDecimal amount, String currency) {
        return paymentHeldForReview(userId, paymentId, amount, currency, null);
    }

    public static Notification paymentHeldForReview(
            UUID userId, UUID paymentId, BigDecimal amount, String currency, UUID sourceEventId) {
        return record(
                userId,
                paymentId,
                "PAYMENT_HELD_FOR_REVIEW",
                "Payment under review",
                String.format("A payment of %s %s is under review and was not approved.", amount, currency),
                sourceEventId
        );
    }

    public static Notification paymentCompleted(UUID userId, UUID paymentId) {
        return paymentCompleted(userId, paymentId, null);
    }

    public static Notification paymentCompleted(UUID userId, UUID paymentId, UUID sourceEventId) {
        return record(
                userId,
                paymentId,
                "PAYMENT_COMPLETED",
                "Payment Completed",
                "Payment " + paymentId + " was completed.",
                sourceEventId
        );
    }

    public static Notification paymentCancelled(UUID userId, UUID paymentId, String reason) {
        return paymentCancelled(userId, paymentId, reason, null);
    }

    public static Notification paymentCancelled(UUID userId, UUID paymentId, String reason, UUID sourceEventId) {
        String why = reason == null || reason.isBlank() ? "no reason provided" : reason;
        return record(
                userId,
                paymentId,
                "PAYMENT_CANCELLED",
                "Payment Cancelled",
                "Payment " + paymentId + " was cancelled: " + why,
                sourceEventId
        );
    }

    public static Notification rehydrate(List<? extends DomainEvent> history) {
        return EventSourcedAggregate.rehydrate(Notification::new, history);
    }

    @Override
    protected void apply(DomainEvent event) {
        if (event instanceof NotificationRecordedEvent e) {
            this.id = e.notificationId();
            this.userId = e.userId();
            this.paymentId = e.paymentId();
            this.type = e.type();
            this.title = e.title();
            this.message = e.message();
            this.createdAt = e.occurredAt();
            return;
        }
        throw new IllegalArgumentException("Unsupported notification event: " + event.getClass().getName());
    }

    @Override
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
