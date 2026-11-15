package com.muse.payments.shared.infrastructure.eventstore;

import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.domain.PaymentInitiatedEvent;
import com.muse.payments.payments.infrastructure.PaymentHistoryProjector;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.EventStore;
import com.muse.payments.shared.application.StreamRef;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.projections.scheduling.enabled=false")
@ActiveProfiles("test")
class AsyncProjectionIT extends TestcontainersBase {
    @Autowired EventStore store;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PaymentHistoryProjector projector;
    @Autowired EventSerializer serializer;
    @Autowired AsyncProjectionProperties properties;
    @Autowired io.micrometer.core.instrument.MeterRegistry meters;

    @Test
    void olderXidWithLaterPositionCannotSkipUncommittedEarlierPosition() throws Exception {
        var runner = new AsyncProjectionRunner(List.of(projector), jdbc, serializer, transactions, properties, meters);
        UUID earlyPosition = UUID.randomUUID();
        UUID laterPosition = UUID.randomUUID();
        CountDownLatch olderXidAllocated = new CountDownLatch(1);
        CountDownLatch earlierPositionAllocated = new CountDownLatch(1);
        CountDownLatch laterCommitted = new CountDownLatch(1);
        CountDownLatch releaseEarlier = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactions);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var older = pool.submit(() -> {
                tx.executeWithoutResult(status -> {
                    jdbc.queryForObject("SELECT pg_current_xact_id()::text", String.class);
                    olderXidAllocated.countDown();
                    await(earlierPositionAllocated);
                    append(laterPosition);
                });
                laterCommitted.countDown();
            });
            var earlier = pool.submit(() -> tx.executeWithoutResult(status -> {
                await(olderXidAllocated);
                append(earlyPosition);
                earlierPositionAllocated.countDown();
                await(releaseEarlier);
            }));
            try {
                await(laterCommitted);
                runner.poll(projector);
                assertThat(count(laterPosition)).isZero();
            } finally {
                releaseEarlier.countDown();
            }
            older.get(10, TimeUnit.SECONDS);
            earlier.get(10, TimeUnit.SECONDS);
        }
        runner.poll(projector);
        runner.poll(projector);
        assertThat(count(earlyPosition)).isEqualTo(1);
        assertThat(count(laterPosition)).isEqualTo(1);
        // Recreating the poller resumes from its durable checkpoint, without duplicates.
        var restarted = new AsyncProjectionRunner(List.of(projector), jdbc, serializer, transactions, properties, meters);
        restarted.poll(projector);
        assertThat(count(earlyPosition)).isEqualTo(1);
        assertThat(count(laterPosition)).isEqualTo(1);
    }

    private void append(UUID id) {
        store.append(new StreamRef("Payment", id), 0,
                List.of(new PaymentInitiatedEvent(id, UUID.randomUUID(), new BigDecimal("12.00"), "BRL", "interleaved")),
                EventMetadata.empty());
    }

    private int count(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_history WHERE payment_id = ?", Integer.class, id);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Transaction synchronization timed out");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }
}
