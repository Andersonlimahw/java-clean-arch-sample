package com.muse.payments.notifications.infrastructure;

import com.muse.payments.notifications.domain.Notification;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "notifications")
public class NotificationJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "type", nullable = false, length = 50)
    private String type;

    @Column(name = "title", nullable = false, length = 150)
    private String title;

    @Column(name = "message", nullable = false, columnDefinition = "text")
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public NotificationJpaEntity() {
    }

    public NotificationJpaEntity(
            UUID id,
            UUID userId,
            UUID paymentId,
            String type,
            String title,
            String message,
            Instant createdAt
    ) {
        this.id = id;
        this.userId = userId;
        this.paymentId = paymentId;
        this.type = type;
        this.title = title;
        this.message = message;
        this.createdAt = createdAt;
    }

    public static NotificationJpaEntity fromDomain(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        return new NotificationJpaEntity(
                notification.getId(),
                notification.getUserId(),
                notification.getPaymentId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.getCreatedAt()
        );
    }

    public Notification toDomain() {
        return new Notification(
                this.id,
                this.userId,
                this.paymentId,
                this.type,
                this.title,
                this.message,
                this.createdAt
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
