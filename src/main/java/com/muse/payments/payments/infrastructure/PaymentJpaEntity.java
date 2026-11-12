package com.muse.payments.payments.infrastructure;

import com.muse.payments.payments.application.OutboundText;
import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class PaymentJpaEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "screening_reason", length = 500)
    private String screeningReason;

    @Column(name = "held_reason", length = 500)
    private String heldReason;

    @Column(name = "reviewer_id")
    private UUID reviewerId;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    public PaymentJpaEntity() {
    }

    public PaymentJpaEntity(
            UUID id,
            UUID userId,
            BigDecimal amount,
            String currency,
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
        this.id = id;
        this.userId = userId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.screeningReason = OutboundText.limit(screeningReason);
        this.heldReason = OutboundText.limit(heldReason);
        this.reviewerId = reviewerId;
        this.reviewedAt = reviewedAt;
    }

    public static PaymentJpaEntity fromDomain(Payment payment) {
        Objects.requireNonNull(payment, "payment must not be null");
        return new PaymentJpaEntity(
                payment.getId(),
                payment.getUserId(),
                payment.getMoney().amount(),
                payment.getMoney().currency(),
                payment.getDescription(),
                payment.getStatus(),
                payment.getCreatedAt(),
                payment.getUpdatedAt(),
                payment.getVersion(),
                payment.getScreeningReason(),
                payment.getHeldReason(),
                payment.getReviewerId(),
                payment.getReviewedAt()
        );
    }

    public Payment toDomain() {
        return Payment.rehydrate(
                this.id,
                this.userId,
                Money.of(this.amount, this.currency),
                this.description,
                this.status,
                this.createdAt,
                this.updatedAt,
                this.version,
                this.screeningReason,
                this.heldReason,
                this.reviewerId,
                this.reviewedAt
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
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
