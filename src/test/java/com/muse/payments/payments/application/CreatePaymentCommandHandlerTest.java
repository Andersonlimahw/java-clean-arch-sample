package com.muse.payments.payments.application;

import com.muse.payments.payments.domain.Money;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentDeclinedException;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.payments.domain.PaymentStatus;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatePaymentCommandHandlerTest {

    private final InMemoryPayments payments = new InMemoryPayments();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final ScreenedPaymentRecorder recorder = new ScreenedPaymentRecorder(payments, outbox);

    @Test
    @DisplayName("A cleared screening records PaymentCreated with status PENDING")
    void clearScreeningRecordsCreatedEvent() {
        CreatePaymentCommandHandler handler = handler(FraudScreeningResult.clear());

        PaymentResponse response = handler.handle(command());

        assertThat(response.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outbox.events).singleElement().extracting(IntegrationEvent::eventType).isEqualTo("PaymentCreated");
        assertThat(payloadOf(outbox.events.getFirst())).containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("A declined screening throws without a provider reason and writes nothing")
    void declinedScreeningDoesNotPersist() {
        CreatePaymentCommandHandler handler = handler(FraudScreeningResult.declined("stolen card"));

        assertThatThrownBy(() -> handler.handle(command()))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessage(PaymentDeclinedException.PUBLIC_MESSAGE);
        assertThat(payments.saved).isEmpty();
        assertThat(outbox.events).isEmpty();
    }

    @Test
    @DisplayName("An unavailable screening records PaymentHeldForReview and not PaymentCreated")
    void underReviewRecordsHeldEvent() {
        CreatePaymentCommandHandler handler = handler(FraudScreeningResult.underReview("provider down"));

        PaymentResponse response = handler.handle(command());

        assertThat(response.status()).isEqualTo(PaymentStatus.UNDER_REVIEW);
        assertThat(response.screeningReason()).isEqualTo("provider down");
        assertThat(outbox.events).singleElement().extracting(IntegrationEvent::eventType).isEqualTo("PaymentHeldForReview");
        assertThat(payloadOf(outbox.events.getFirst())).containsEntry("status", "UNDER_REVIEW");
    }

    private CreatePaymentCommandHandler handler(FraudScreeningResult result) {
        return new CreatePaymentCommandHandler(request -> result, recorder);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(IntegrationEvent event) {
        return (Map<String, Object>) event.payload();
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
        private final List<IntegrationEvent> events = new ArrayList<>();

        @Override
        public void append(IntegrationEvent event) {
            events.add(event);
        }
    }
}
