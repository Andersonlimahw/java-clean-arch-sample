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
    private final String screeningReason;

    private Payment(
            UUID id,
            UUID userId,
            Money money,
            String description,
            PaymentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            String screeningReason
    ) {
        this.id = Objects.requireNonNull(id, "Payment id must not be null");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.money = Objects.requireNonNull(money, "money must not be null");
        this.description = description;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.version = version;
        this.screeningReason = screeningReason;
    }

    public static Payment create(UUID userId, Money money, String description) {
        return create(UUID.randomUUID(), userId, money, description);
    }

    public static Payment create(UUID id, UUID userId, Money money, String description) {
        return open(id, userId, money, description, PaymentStatus.PENDING, null, true);
    }

    /**
     * Holds a payment the provider did not clear. It is not approved: confirm is rejected
     * until {@link #releaseFromReview()}. The owner may cancel, or a review may reject it.
     */
    public static Payment createUnderReview(UUID id, UUID userId, Money money, String description, String reason) {
        return open(id, userId, money, description, PaymentStatus.UNDER_REVIEW, reason, false);
    }

    private static Payment open(
            UUID id,
            UUID userId,
            Money money,
            String description,
            PaymentStatus status,
            String screeningReason,
            boolean created
    ) {
        Instant now = Instant.now();
        String storedReason = screeningReason;
        if (storedReason != null && storedReason.length() > 500) {
            storedReason = storedReason.substring(0, 500);
        }
        Payment payment = new Payment(id, userId, money, description, status, now, now, null, storedReason);
        if (created) {
            payment.registerEvent(new PaymentCreatedEvent(id, userId, money.amount(), money.currency(), description));
        } else {
            payment.registerEvent(new PaymentHeldForReviewEvent(
                    id, userId, money.amount(), money.currency(), description, screeningReason));
        }
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
        return rehydrate(id, userId, money, description, status, createdAt, updatedAt, version, null);
    }

    public static Payment rehydrate(
            UUID id,
            UUID userId,
            Money money,
            String description,
            PaymentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            String screeningReason
    ) {
        return new Payment(id, userId, money, description, status, createdAt, updatedAt, version, screeningReason);
    }

    /**
     * Review exit: the held payment becomes a normal pending payment and can be confirmed.
     * This is the only path from {@link PaymentStatus#UNDER_REVIEW} to a confirmable state.
     */
    public void releaseFromReview() {
        if (this.status != PaymentStatus.UNDER_REVIEW) {
            throw new ConflictException("Cannot release payment in status: " + this.status);
        }
        this.status = PaymentStatus.PENDING;
        this.updatedAt = Instant.now();
        registerEvent(new PaymentCreatedEvent(this.id, this.userId, this.money.amount(), this.money.currency(), this.description));
    }

    /** Review exit: the held payment is cancelled and is not approved. */
    public void rejectFromReview() {
        if (this.status != PaymentStatus.UNDER_REVIEW) {
            throw new ConflictException("Cannot reject payment in status: " + this.status);
        }
        this.status = PaymentStatus.CANCELLED;
        this.updatedAt = Instant.now();
        registerEvent(new PaymentCancelledEvent(this.id, this.userId, this.screeningReason));
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
        if (this.status != PaymentStatus.PENDING && this.status != PaymentStatus.UNDER_REVIEW) {
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

    public String getScreeningReason() {
        return screeningReason;
    }
}
