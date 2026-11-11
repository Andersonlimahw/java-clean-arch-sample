package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    @DisplayName("Payment.create sets status to PENDING and records PaymentCreatedEvent")
    void create_shouldInitializePendingPaymentAndRecordEvent() {
        UUID userId = UUID.randomUUID();
        Money money = Money.of(new BigDecimal("99.90"), "BRL");

        Payment payment = Payment.create(userId, money, "Test subscription");

        assertThat(payment.getId()).isNotNull();
        assertThat(payment.getUserId()).isEqualTo(userId);
        assertThat(payment.getMoney()).isEqualTo(money);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getVersion()).isNull();

        var events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        var event = (PaymentCreatedEvent) events.getFirst();
        assertThat(event.paymentId()).isEqualTo(payment.getId());
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.amount()).isEqualByComparingTo("99.90");
        assertThat(event.currency()).isEqualTo("BRL");
    }

    @Test
    @DisplayName("Payment.complete transitions from PENDING to COMPLETED and records event")
    void complete_shouldTransitionToCompletedAndRecordEvent() {
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(userId, Money.of(new BigDecimal("100.00"), "USD"), "Order 123");
        payment.pullDomainEvents(); // clear creation event

        payment.complete();

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        var events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        var event = (PaymentCompletedEvent) events.getFirst();
        assertThat(event.paymentId()).isEqualTo(payment.getId());
        assertThat(event.userId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("Payment.complete throws ConflictException when already COMPLETED or CANCELLED")
    void complete_whenNotPending_shouldThrowConflictException() {
        Payment payment = Payment.create(UUID.randomUUID(), Money.of(new BigDecimal("50.00"), "BRL"), "Order");
        payment.complete();

        assertThatThrownBy(payment::complete)
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot complete payment in status: COMPLETED");

        Payment cancelled = Payment.create(UUID.randomUUID(), Money.of(new BigDecimal("50.00"), "BRL"), "Order");
        cancelled.cancel("User changed mind");

        assertThatThrownBy(cancelled::complete)
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot complete payment in status: CANCELLED");
    }

    @Test
    @DisplayName("Payment.cancel transitions from PENDING to CANCELLED and records event")
    void cancel_shouldTransitionToCancelledAndRecordEvent() {
        UUID userId = UUID.randomUUID();
        Payment payment = Payment.create(userId, Money.of(new BigDecimal("10.00"), "EUR"), "Quick order");
        payment.pullDomainEvents(); // clear creation event

        payment.cancel("Out of stock");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        var events = payment.pullDomainEvents();
        assertThat(events).hasSize(1);
        var event = (PaymentCancelledEvent) events.getFirst();
        assertThat(event.paymentId()).isEqualTo(payment.getId());
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.reason()).isEqualTo("Out of stock");
    }

    @Test
    @DisplayName("Payment.isOwnedBy validates owner matching")
    void isOwnedBy_shouldValidateCorrectOwnership() {
        UUID ownerId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Payment payment = Payment.create(ownerId, Money.of(new BigDecimal("10.00"), "BRL"), "Check");

        assertThat(payment.isOwnedBy(ownerId)).isTrue();
        assertThat(payment.isOwnedBy(otherId)).isFalse();
    }
}
