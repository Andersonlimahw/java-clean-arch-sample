package com.muse.payments.payments.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.shared.application.IdempotencyDecision;
import com.muse.payments.shared.application.IdempotencyPort;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreatePaymentUseCaseTest {

    private final RecordingPayments payments = new RecordingPayments();
    private final RecordingOutbox outbox = new RecordingOutbox();
    private final RecordingIdempotency idempotency = new RecordingIdempotency();

    @Test
    @DisplayName("The payment id and the provider key are the lease seed, and a new lease is a new id")
    void leaseSeedIsThePaymentId() throws Exception {
        List<FraudScreeningRequest> seen = new ArrayList<>();
        CreatePaymentUseCase useCase = useCase(request -> {
            seen.add(request);
            return FraudScreeningResult.clear();
        });
        UUID userId = UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("10.00"), "BRL", "A");
        Instant firstLease = Instant.parse("2026-11-12T10:00:00Z");
        Instant secondLease = Instant.parse("2026-11-12T12:00:00Z");
        idempotency.decision = IdempotencyDecision.proceed(UUID.randomUUID(), firstLease);

        useCase.execute(userId, "key-1", request);
        idempotency.decision = IdempotencyDecision.proceed(UUID.randomUUID(), secondLease);
        useCase.execute(userId, "key-1", request);

        assertThat(seen).hasSize(2);
        assertThat(seen.getFirst().paymentId()).isEqualTo(PaymentIds.forLease(userId, "key-1", firstLease));
        assertThat(seen.get(1).paymentId()).isEqualTo(PaymentIds.forLease(userId, "key-1", secondLease));
        assertThat(seen.getFirst().paymentId()).isNotEqualTo(seen.get(1).paymentId());
        assertThat(outbox.events.getFirst().eventType()).isEqualTo("PaymentCreated");
    }

    @Test
    @DisplayName("A decline stores a domain outcome and does not persist a payment")
    void declineStoresDomainOutcome() throws Exception {
        CreatePaymentUseCase useCase = useCase(request -> FraudScreeningResult.declined("stolen card"));

        CreatePaymentResult result = useCase.execute(
                UUID.randomUUID(), "key", new CreatePaymentRequest(new BigDecimal("10.00"), "BRL", "A"));

        assertThat(result).isInstanceOf(CreatePaymentResult.Declined.class);
        assertThat(payments.saved).isEmpty();
        assertThat(idempotency.completedBody).contains("DECLINED");
        assertThat(idempotency.completedBody).doesNotContain("stolen card");
        assertThat(idempotency.released).isFalse();
    }

    private CreatePaymentUseCase useCase(FraudScreeningPort port) {
        return new CreatePaymentUseCase(
                idempotency,
                port,
                new ScreenedPaymentRecorder(payments, outbox),
                new TransactionTemplate(directTransactions()),
                new ObjectMapper().registerModule(new JavaTimeModule())
        );
    }

    private static PlatformTransactionManager directTransactions() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }

    private static final class RecordingPayments implements PaymentRepository {
        private final List<com.muse.payments.payments.domain.Payment> saved = new ArrayList<>();

        @Override
        public com.muse.payments.payments.domain.Payment save(com.muse.payments.payments.domain.Payment payment) {
            saved.add(payment);
            return payment;
        }

        @Override
        public Optional<com.muse.payments.payments.domain.Payment> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<com.muse.payments.payments.domain.Payment> findByUserId(
                UUID userId, int page, int size, com.muse.payments.payments.domain.PaymentSort sort) {
            return List.of();
        }

        @Override
        public long countByUserId(UUID userId) {
            return 0;
        }

        @Override
        public List<com.muse.payments.payments.domain.Payment> findByStatus(
                com.muse.payments.payments.domain.PaymentStatus status, int page, int size, com.muse.payments.payments.domain.PaymentSort sort) {
            return List.of();
        }

        @Override
        public long countByStatus(com.muse.payments.payments.domain.PaymentStatus status) {
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

    private static final class RecordingIdempotency implements IdempotencyPort {
        private IdempotencyDecision decision = IdempotencyDecision.proceed(UUID.randomUUID(), Instant.parse("2026-11-12T00:00:00Z"));
        private String completedBody;
        private boolean released;

        @Override
        public IdempotencyDecision acquireProcessing(UUID userId, String key, String payloadJson) {
            return decision;
        }

        @Override
        public void saveCompleted(UUID userId, String key, String payloadJson, UUID leaseId, String responseJson) {
            completedBody = responseJson;
        }

        @Override
        public void releaseProcessing(UUID userId, String key, String payloadJson, UUID leaseId) {
            released = true;
        }
    }
}
