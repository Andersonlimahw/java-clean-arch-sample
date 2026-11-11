package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.AggregateRoot;
import com.muse.payments.shared.domain.ConflictException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class Payment extends AggregateRoot<UUID> {

    private final UUID id;
    private final UUID userId;
    private final Money money;
    private final String description;
    private PaymentStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private Long version;

    private Payment(
            UUID id,
            UUID userId,
            Money money,
            String description,
            PaymentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long version
    ) {
        this.id = Objects.requireNonNull(id, "Payment id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.description = description;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
    }

    public static Payment create(UUID userId, Money money, String description) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        Payment payment = new Payment(id, userId, money, description, PaymentStatus.PENDING, now, now, null);
        payment.registerEvent(new PaymentCreatedEvent(id, userId, money.amount(), money.currency(), description));
        return payment;
    }

    public static Payment rehydrate(
            UUID id,
            UUID userId,
            Money money,
            String description,
            PaymentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long version
    ) {
        return new Payment(id, userId, money, description, status, createdAt, updatedAt, version);
    }

    public void complete() {
        if (this.status != PaymentStatus.PENDING) {
            throw new ConflictException("Cannot complete payment in status: " + this.status);
        }
        this.status = PaymentStatus.COMPLETED;
        this.updatedAt = Instant.now();
        registerEvent(new PaymentCompletedEvent(this.id, this.userId));
    }

    public void cancel(String reason) {
        if (this.status != PaymentStatus.PENDING) {
            throw new ConflictException("Cannot cancel payment in status: " + this.status);
        }
        this.status = PaymentStatus.CANCELLED;
        this.updatedAt = Instant.now();
        registerEvent(new PaymentCancelledEvent(this.id, this.userId, reason));
    }

    public boolean isOwnedBy(UUID requestingUserId) {
        return this.userId.equals(requestingUserId);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public Money getMoney() {
        return money;
    }

    public String getDescription() {
        return description;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
