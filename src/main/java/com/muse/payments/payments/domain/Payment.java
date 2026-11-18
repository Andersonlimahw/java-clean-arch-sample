package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.ConflictException;
import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.domain.EventSourcedAggregate;
import com.muse.payments.shared.domain.ForbiddenException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class Payment extends EventSourcedAggregate<UUID> {

    private UUID id;
    private UUID userId;
    private Money money;
    private String description;
    private PaymentStatus status;
    private Instant createdAt;
    private Instant updatedAt;
    private Long projectionVersion;
    private String screeningReason;
    private String heldReason;
    private UUID reviewerId;
    private Instant reviewedAt;
    private String provider;
    private String providerReference;

    private Payment() {
    }

    private Payment(
            UUID id,
            UUID userId,
            Money money,
            String description,
            PaymentStatus status,
            Instant createdAt,
            Instant updatedAt,
            Long projectionVersion,
            String screeningReason,
            String heldReason,
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
        this.projectionVersion = projectionVersion;
        this.screeningReason = screeningReason;
        this.heldReason = heldReason;
        this.reviewerId = reviewerId;
        this.reviewedAt = reviewedAt;
    }

    public static Payment create(UUID userId, Money money, String description) {
        return create(UUID.randomUUID(), userId, money, description);
    }

    public static Payment create(UUID id, UUID userId, Money money, String description) {
        Objects.requireNonNull(id, "Payment id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(money, "money must not be null");
        Payment payment = new Payment();
        payment.raise(new PaymentInitiatedEvent(id, userId, money.amount(), money.currency(), description));
        return payment;
    }

    /**
     * Holds a payment the provider did not clear. Confirm is rejected until a reviewer
     * who does not own the payment releases it. The owner may cancel it.
     */
    public static Payment createUnderReview(UUID id, UUID userId, Money money, String description, String reason) {
        Objects.requireNonNull(id, "Payment id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(money, "money must not be null");
        Payment payment = new Payment();
        payment.raise(new PaymentHeldForReviewEvent(id, userId, money.amount(), money.currency(), description, reason));
        return payment;
    }

    public static Payment rehydrate(List<? extends DomainEvent> history) {
        return EventSourcedAggregate.rehydrate(Payment::new, history);
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
            String heldReason,
            UUID reviewerId,
            Instant reviewedAt
    ) {
        return new Payment(id, userId, money, description, status, createdAt, updatedAt, version, screeningReason, heldReason, reviewerId, reviewedAt);
    }

    /**
     * A reviewer who does not own the payment releases it to {@link PaymentStatus#PENDING}.
     * The active screening reason is cleared. {@code heldReason} stays for audit.
     */
    public void releaseFromReview(UUID reviewerId, Instant reviewedAt) {
        requireHeld(reviewerId);
        Objects.requireNonNull(reviewedAt, "reviewedAt must not be null");
        raise(new PaymentReleasedFromReviewEvent(
                this.id, this.userId, this.money.amount(), this.money.currency(), this.description, reviewerId, reviewedAt));
    }

    /** A reviewer who does not own the payment cancels it. {@code reason} is the reviewer's reason. */
    public void rejectFromReview(UUID reviewerId, Instant reviewedAt, String reason) {
        requireHeld(reviewerId);
        Objects.requireNonNull(reviewedAt, "reviewedAt must not be null");
        raise(new PaymentRejectedInReviewEvent(this.id, this.userId, reviewerId, reason, reviewedAt));
    }

    private void requireHeld(UUID reviewerId) {
        if (this.status != PaymentStatus.UNDER_REVIEW) {
            throw new ConflictException("Cannot review payment in status: " + this.status);
        }
        if (reviewerId == null) {
            throw new ConflictException("Reviewer is required");
        }
        if (isOwnedBy(reviewerId)) {
            throw new ForbiddenException("The payment owner cannot review their own payment");
        }
    }

    public void complete() {
        if (this.status != PaymentStatus.PENDING) {
            throw new ConflictException("Cannot complete payment in status: " + this.status);
        }
        raise(new PaymentCompletedEvent(this.id, this.userId));
    }

    /**
     * Records the provider intent returned after the payment was created. Only a pending
     * payment is sent to a provider, and only once; the idempotency key at the provider is
     * the payment id.
     */
    public void recordProviderIntent(String provider, String providerReference) {
        if (this.status != PaymentStatus.PENDING) {
            throw new ConflictException("Cannot open a provider intent for a payment in status: " + this.status);
        }
        if (this.providerReference != null) {
            throw new ConflictException("Payment already has a provider intent");
        }
        if (provider == null || provider.isBlank() || providerReference == null || providerReference.isBlank()) {
            throw new ConflictException("Provider and provider reference are required");
        }
        raise(new PaymentProviderIntentCreatedEvent(this.id, provider, providerReference));
    }

    /**
     * Settles the payment from a verified provider callback. The provider and reference must
     * match the recorded intent, so a valid signature for one intent cannot settle another
     * payment. A repeated success is a no-op; a held payment stays held for a reviewer; a
     * completed payment is never cancelled retroactively.
     */
    public void settleFromProvider(String provider, String providerReference, boolean succeeded, String reason) {
        if (this.provider == null || this.providerReference == null) {
            throw new ConflictException("Payment has no provider intent");
        }
        if (!this.provider.equals(provider) || !this.providerReference.equals(providerReference)) {
            throw new ConflictException("Provider reference does not match the payment");
        }
        if (this.status == PaymentStatus.UNDER_REVIEW) {
            throw new ConflictException("Payment is held for review and cannot be settled by a provider callback");
        }
        if (this.status == PaymentStatus.COMPLETED) {
            if (succeeded) {
                return;
            }
            throw new ConflictException("Cannot cancel a completed payment");
        }
        if (this.status == PaymentStatus.CANCELLED) {
            throw new ConflictException("Payment is cancelled");
        }
        if (succeeded) {
            complete();
        } else {
            cancel(reason);
        }
    }

    public void cancel(String reason) {
        if (this.status != PaymentStatus.PENDING && this.status != PaymentStatus.UNDER_REVIEW) {
            throw new ConflictException("Cannot cancel payment in status: " + this.status);
        }
        raise(new PaymentCancelledEvent(this.id, this.userId, reason));
    }

    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case PaymentInitiatedEvent e -> {
                this.id = e.paymentId();
                this.userId = e.userId();
                this.money = Money.of(e.amount(), e.currency());
                this.description = e.description();
                this.status = PaymentStatus.PENDING;
                this.createdAt = e.occurredAt();
                this.updatedAt = e.occurredAt();
                this.screeningReason = null;
                this.heldReason = null;
                this.reviewerId = null;
                this.reviewedAt = null;
                this.projectionVersion = null;
            }
            case PaymentHeldForReviewEvent e -> {
                this.id = e.paymentId();
                this.userId = e.userId();
                this.money = Money.of(e.amount(), e.currency());
                this.description = e.description();
                this.status = PaymentStatus.UNDER_REVIEW;
                this.createdAt = e.occurredAt();
                this.updatedAt = e.occurredAt();
                this.screeningReason = e.reason();
                this.heldReason = e.reason();
                this.reviewerId = null;
                this.reviewedAt = null;
                this.projectionVersion = null;
            }
            case PaymentReleasedFromReviewEvent e -> {
                this.status = PaymentStatus.PENDING;
                this.screeningReason = null;
                this.reviewerId = e.reviewerId();
                this.reviewedAt = e.occurredAt();
                this.updatedAt = e.occurredAt();
                this.projectionVersion = null;
            }
            case PaymentRejectedInReviewEvent e -> {
                this.status = PaymentStatus.CANCELLED;
                this.reviewerId = e.reviewerId();
                this.reviewedAt = e.occurredAt();
                this.updatedAt = e.occurredAt();
                this.projectionVersion = null;
            }
            case PaymentCompletedEvent e -> {
                this.status = PaymentStatus.COMPLETED;
                this.updatedAt = e.occurredAt();
                this.projectionVersion = null;
            }
            case PaymentCancelledEvent e -> {
                this.status = PaymentStatus.CANCELLED;
                this.updatedAt = e.occurredAt();
                this.projectionVersion = null;
            }
            case PaymentProviderIntentCreatedEvent e -> {
                this.provider = e.provider();
                this.providerReference = e.providerReference();
                this.updatedAt = e.occurredAt();
                this.projectionVersion = null;
            }
            default -> throw new IllegalArgumentException("Unsupported payment event: " + event.getClass().getName());
        }
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
        if (projectionVersion != null) {
            return projectionVersion;
        }
        return version() == 0 ? null : version() - 1;
    }

    public String getScreeningReason() {
        return screeningReason;
    }

    public String getHeldReason() {
        return heldReason;
    }

    public UUID getReviewerId() {
        return reviewerId;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }
}
