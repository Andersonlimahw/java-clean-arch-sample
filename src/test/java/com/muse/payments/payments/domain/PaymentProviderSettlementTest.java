package com.muse.payments.payments.domain;

import com.muse.payments.shared.domain.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentProviderSettlementTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    @DisplayName("A recorded intent plus a succeeded callback completes the payment once")
    void succeededCallbackCompletes() {
        Payment payment = pendingPayment();
        payment.recordProviderIntent("stripe", "pi_1");

        assertThat(payment.getProvider()).isEqualTo("stripe");
        assertThat(payment.getProviderReference()).isEqualTo("pi_1");

        payment.pullDomainEvents();
        payment.settleFromProvider("stripe", "pi_1", true, "Stripe reported payment success");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.pullDomainEvents()).singleElement().isInstanceOf(PaymentCompletedEvent.class);
    }

    @Test
    @DisplayName("A repeated success on the same aggregate is a no-op")
    void repeatedSuccessIsIdempotent() {
        Payment payment = pendingPayment();
        payment.recordProviderIntent("stripe", "pi_1");
        payment.settleFromProvider("stripe", "pi_1", true, "ok");
        payment.pullDomainEvents();

        payment.settleFromProvider("stripe", "pi_1", true, "ok again");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(payment.pullDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("A failure callback cancels a pending payment with a bounded reason")
    void failureCallbackCancels() {
        Payment payment = pendingPayment();
        payment.recordProviderIntent("stripe", "pi_1");
        payment.pullDomainEvents();

        payment.settleFromProvider("stripe", "pi_1", false, "Stripe reported payment failure");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(payment.pullDomainEvents()).singleElement().isInstanceOf(PaymentCancelledEvent.class);
    }

    @Test
    @DisplayName("A callback for another provider reference cannot settle the payment")
    void referenceMismatchIsRejected() {
        Payment payment = pendingPayment();
        payment.recordProviderIntent("stripe", "pi_1");

        assertThatThrownBy(() -> payment.settleFromProvider("stripe", "pi_other", true, "ok"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("A callback for another provider cannot settle the payment")
    void providerMismatchIsRejected() {
        Payment payment = pendingPayment();
        payment.recordProviderIntent("stripe", "pi_1");

        assertThatThrownBy(() -> payment.settleFromProvider("adyen", "pi_1", true, "ok"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("does not match");
    }

    @Test
    @DisplayName("A payment held for fraud review is never settled by a callback")
    void heldPaymentIsNotSettled() {
        UUID paymentId = UUID.randomUUID();
        Payment payment = Payment.rehydrate(List.of(
                new PaymentInitiatedEvent(paymentId, USER_ID, new BigDecimal("10.00"), "BRL", "Held"),
                new PaymentProviderIntentCreatedEvent(paymentId, "stripe", "pi_1"),
                new PaymentHeldForReviewEvent(paymentId, USER_ID, new BigDecimal("10.00"), "BRL", "Held", "provider down")));

        assertThatThrownBy(() -> payment.settleFromProvider("stripe", "pi_1", true, "ok"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("held for review");
    }

    @Test
    @DisplayName("A completed payment is not cancelled retroactively")
    void completedPaymentIsNotCancelled() {
        Payment payment = pendingPayment();
        payment.recordProviderIntent("stripe", "pi_1");
        payment.settleFromProvider("stripe", "pi_1", true, "ok");

        assertThatThrownBy(() -> payment.settleFromProvider("stripe", "pi_1", false, "late failure"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("completed");
    }

    @Test
    @DisplayName("An intent is opened only once, and only for a pending payment")
    void intentIsOpenedOnce() {
        Payment payment = pendingPayment();
        payment.recordProviderIntent("stripe", "pi_1");

        assertThatThrownBy(() -> payment.recordProviderIntent("stripe", "pi_2"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has a provider intent");

        Payment held = Payment.createUnderReview(UUID.randomUUID(), USER_ID,
                Money.of(new BigDecimal("10.00"), "BRL"), "Held", "reason");
        assertThatThrownBy(() -> held.recordProviderIntent("stripe", "pi_3"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("status");
    }

    private static Payment pendingPayment() {
        Payment payment = Payment.create(USER_ID, Money.of(new BigDecimal("10.00"), "BRL"), "Order");
        payment.pullDomainEvents();
        return payment;
    }
}
