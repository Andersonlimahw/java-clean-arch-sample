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
    private String screeningReason;
    private UUID reviewerId;
    private Instant reviewedAt;

    private Payment(
            UUID id,
            UUID userId,
            Money money,
            String description,
            PaymentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            String screeningReason,
            UUID reviewerId,
            Instant reviewedAt
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
        this.reviewerId = reviewerId;
        this.reviewedAt = reviewedAt;
    }

    public static Payment create(UUID userId, Money money, String description) {
        return create(UUID.randomUUID(), userId, money, description);
    }

    public static Payment create(UUID id, UUID userId, Money money, String description) {
        return cleared(id, userId, money, description);
    }

    /**
     * Holds a payment the provider did not clear. Confirm is rejected until a reviewer
     * who does not own the payment releases it. The owner may cancel it.
     */
    public static Payment createUnderReview(UUID id, UUID userId, Money money, String description, String reason) {
        return held(id, userId, money, description, reason);
    }

    private static Payment cleared(UUID id, UUID userId, Money money, String description) {
        Instant now = Instant.now();
        Payment payment = new Payment(id, userId, money, description, PaymentStatus.PENDING, now, now, null, null, null, null);
        payment.registerEvent(new PaymentCreatedEvent(id, userId, money.amount(), money.currency(), description));
        return payment;
    }

    private static Payment held(UUID id, UUID userId, Money money, String description, String reason) {
        Instant now = Instant.now();
        Payment payment = new Payment(id, userId, money, description, PaymentStatus.UNDER_REVIEW, now, now, null, reason, null, null);
        payment.registerEvent(new PaymentHeldForReviewEvent(
                id, userId, money.amount(), money.currency(), description, reason));
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
            Long version,
            String screeningReason,
            UUID reviewerId,
            Instant reviewedAt
    ) {
        return new Payment(id, userId, money, description, status, createdAt, updatedAt, version, screeningReason, reviewerId, reviewedAt);
    }

    /**
     * A reviewer who does not own the payment releases it to {@link PaymentStatus#PENDING}.
     * The stored screening reason is cleared because the payment is no longer held.
     */
    public void releaseFromReview(UUID reviewerId, Instant reviewedAt) {
        requireHeld(reviewerId);
        this.status = PaymentStatus.PENDING;
        this.screeningReason = null;
        this.reviewerId = Objects.requireNonNull(reviewerId, "reviewerId must not be null");
        this.reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt must not be null");
        this.updatedAt = reviewedAt;
        registerEvent(new PaymentCreatedEvent(this.id, this.userId, this.money.amount(), this.money.currency(), this.description));
    }

    /** A reviewer who does not own the payment cancels it. {@code reason} is the reviewer's reason. */
    public void rejectFromReview(UUID reviewerId, Instant reviewedAt, String reason) {
        requireHeld(reviewerId);
        this.status = PaymentStatus.CANCELLED;
        this.reviewerId = Objects.requireNonNull(reviewerId, "reviewerId must not be null");
        this.reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt must not be null");
        this.updatedAt = reviewedAt;
        registerEvent(new PaymentCancelledEvent(this.id, this.userId, reason));
    }

    private void requireHeld(UUID reviewerId) {
        if (this.status != PaymentStatus.UNDER_REVIEW) {
            throw new ConflictException("Cannot review payment in status: " + this.status);
        }
        if (reviewerId == null) {
            throw new ConflictException("Reviewer is required");
        }
        if (isOwnedBy(reviewerId)) {
            throw new ConflictException("The payment owner cannot review their own payment");
        }
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

    public UUID getReviewerId() {
        return reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
