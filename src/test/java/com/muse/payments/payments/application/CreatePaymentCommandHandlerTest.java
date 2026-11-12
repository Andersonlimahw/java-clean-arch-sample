package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.payments.domain.PaymentStatus;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.domain.ConflictException;
import com.muse.payments.shared.domain.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatePaymentCommandHandlerTest {

    private final InMemoryPayments payments = new InMemoryPayments();
    private final RecordingOutbox outbox = new RecordingOutbox();

    @Test
    @DisplayName("A cleared screening creates a pending payment")
    void clearScreeningPersistsPendingPayment() {
        CreatePaymentCommandHandler handler = handler(FraudScreeningResult.clear());

        PaymentResponse response = handler.handle(command());

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payments.saved).hasSize(1);
        assertThat(outbox.appended).isEqualTo(1);
    }

    @Test
    @DisplayName("A declined screening does not create or approve a payment")
    void declinedScreeningDoesNotPersist() {
        CreatePaymentCommandHandler handler = handler(
                FraudScreeningResult.declined("Fraud provider rejected the screening with HTTP 422"));

        assertThatThrownBy(() -> handler.handle(command()))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("declined")
                .hasMessageContaining("HTTP 422");
        assertThat(payments.saved).isEmpty();
        assertThat(outbox.appended).isZero();
    }

    @Test
    @DisplayName("An unavailable screening holds the payment for review and it cannot be confirmed")
    void underReviewIsPersistedAndCannotBeConfirmed() {
        CreatePaymentCommandHandler handler = handler(
                FraudScreeningResult.underReview("Fraud screening unavailable"));

        PaymentResponse response = handler.handle(command());

        assertThat(response.status()).isEqualTo(PaymentStatus.UNDER_REVIEW);
        assertThat(payments.saved).hasSize(1);
        assertThatThrownBy(payments.saved.getFirst()::complete)
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("UNDER_REVIEW");
    }

    private CreatePaymentCommandHandler handler(FraudScreeningResult result) {
        return new CreatePaymentCommandHandler(
                payments,
                outbox,
                new FraudScreeningService(request -> result));
    }

    private static CreatePaymentCommand command() {
        return new CreatePaymentCommand(UUID.randomUUID(), new BigDecimal("15.50"), "BRL", "Invoice");
    }

    private static final class InMemoryPayments implements PaymentRepository {
        private final List<Payment> saved = new ArrayList<>();

        @Override
        public Payment save(Payment payment) {
            saved.add(payment);
            return payment;
        }

        @Override
        public Optional<Payment> findById(UUID id) {
            return saved.stream().filter(payment -> payment.getId().equals(id)).findFirst();
        }

        @Override
        public List<Payment> findByUserId(UUID userId, int page, int size) {
            return List.of();
        }

        @Override
        public long countByUserId(UUID userId) {
            return 0;
        }
    }

    private static final class RecordingOutbox implements OutboxPort {
        private int appended;

        @Override
        public void append(IntegrationEvent event) {
            appended++;
        }
    }
}
