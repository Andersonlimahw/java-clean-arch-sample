package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.payments.domain.PaymentStatus;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.domain.ForbiddenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReviewPaymentCommandHandlerTest {

    @Test
    @DisplayName("A reviewer who is not the owner approves the held payment")
    void reviewerApproves() {
        UUID owner = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        Payment payment = Payment.createUnderReview(
                UUID.randomUUID(), owner, com.muse.payments.payments.domain.Money.of(new BigDecimal("10.00"), "BRL"), "Held", "down");
        payment.pullDomainEvents();
        ReviewPaymentCommandHandler handler = handler(payment);

        PaymentResponse response = handler.handle(new ReviewPaymentCommand(payment.getId(), reviewer, ReviewDecision.APPROVE));

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getReviewerId()).isEqualTo(reviewer);
        assertThat(payment.getReviewedAt()).isNotNull();
        assertThat(payment.getScreeningReason()).isNull();
        assertThat(payment.getHeldReason()).isEqualTo("down");
    }

    @Test
    @DisplayName("The owner cannot review their own payment")
    void ownerIsRejected() {
        UUID owner = UUID.randomUUID();
        Payment payment = Payment.createUnderReview(
                UUID.randomUUID(), owner, com.muse.payments.payments.domain.Money.of(new BigDecimal("10.00"), "BRL"), "Held", "down");
        ReviewPaymentCommandHandler handler = handler(payment);

        assertThatThrownBy(() -> handler.handle(new ReviewPaymentCommand(payment.getId(), owner, ReviewDecision.APPROVE)))
                .isInstanceOf(ForbiddenException.class);
    }

    private static ReviewPaymentCommandHandler handler(Payment payment) {
        PaymentRepository repository = new PaymentRepository() {
            @Override
            public Payment save(Payment toSave) {
                return toSave;
            }

            @Override
            public Optional<Payment> findById(UUID id) {
                return Optional.of(payment);
            }

            @Override
            public java.util.List<Payment> findByUserId(
                    UUID userId, int page, int size, com.muse.payments.payments.domain.PaymentSort sort) {
                return java.util.List.of();
            }

            @Override
            public long countByUserId(UUID userId) {
                return 0;
            }

            @Override
            public java.util.List<Payment> findByStatus(
                    com.muse.payments.payments.domain.PaymentStatus status, int page, int size, com.muse.payments.payments.domain.PaymentSort sort) {
                return java.util.List.of();
            }

            @Override
            public long countByStatus(com.muse.payments.payments.domain.PaymentStatus status) {
                return 0;
            }
        };
        OutboxPort outbox = new OutboxPort() {
            @Override
            public void append(IntegrationEvent event) {
            }
        };
        return new ReviewPaymentCommandHandler(repository, outbox, new PaymentCachePort() {
            @Override
            public java.util.Optional<PaymentResponse> get(UUID paymentId) {
                return Optional.empty();
            }

            @Override
            public void put(UUID paymentId, PaymentResponse response) {
            }

            @Override
            public void evict(UUID paymentId) {
            }

            @Override
            public void evictAfterCommit(UUID paymentId) {
            }

            @Override
            public PaymentResponse getOrLoad(UUID paymentId, java.util.function.Supplier<PaymentResponse> dbLoader) {
                return dbLoader.get();
            }
        });
    }
}
