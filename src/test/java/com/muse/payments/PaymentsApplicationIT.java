package com.muse.payments;

import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.infrastructure.OutboxJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PaymentsApplicationIT extends TestcontainersBase {

    @Autowired
    private OutboxPort outboxPort;

    @Autowired
    private OutboxJpaRepository outboxJpaRepository;

    @Autowired
    private TxTestService txTestService;

    @Test
    @DisplayName("Application context loads and outbox commits within transaction")
    void contextLoadsAndOutboxCommits() {
        UUID aggregateId = UUID.randomUUID();
        txTestService.doWithCommit(aggregateId);

        assertThat(outboxJpaRepository.findAll())
                .anyMatch(event -> event.getAggregateId().equals(aggregateId));
    }

    @Test
    @DisplayName("Outbox event rolls back when transaction fails")
    void outboxRollsBackOnFailure() {
        UUID aggregateId = UUID.randomUUID();

        assertThatThrownBy(() -> txTestService.doWithRollback(aggregateId))
                .isInstanceOf(RuntimeException.class);

        assertThat(outboxJpaRepository.findAll())
                .noneMatch(event -> event.getAggregateId().equals(aggregateId));
    }

    @TestConfiguration
    static class Config {
        @Bean
        public TxTestService txTestService(OutboxPort outboxPort) {
            return new TxTestService(outboxPort);
        }
    }

    static class TxTestService {
        private final OutboxPort outboxPort;

        public TxTestService(OutboxPort outboxPort) {
            this.outboxPort = outboxPort;
        }

        @Transactional
        public void doWithCommit(UUID aggregateId) {
            IntegrationEvent event = IntegrationEvent.create(
                    "Payment",
                    aggregateId,
                    "PaymentCreated",
                    Map.of("amount", "100.00"),
                    Map.of("traceId", "test-trace")
            );
            outboxPort.append(event);
        }

        @Transactional
        public void doWithRollback(UUID aggregateId) {
            IntegrationEvent event = IntegrationEvent.create(
                    "Payment",
                    aggregateId,
                    "PaymentCreated",
                    Map.of("amount", "200.00"),
                    Map.of("traceId", "test-trace-fail")
            );
            outboxPort.append(event);
            throw new RuntimeException("Forced rollback for testing");
        }
    }
}
