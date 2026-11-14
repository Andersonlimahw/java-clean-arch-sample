package com.muse.payments.shared.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.infrastructure.observability.W3cTraceContextPropagation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayBatchTest {

    private final OutboxJpaRepository repository = mock(OutboxJpaRepository.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final List<String> sentPayloads = new ArrayList<>();
    private OutboxRelayService relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        relay = new OutboxRelayService(
                repository,
                rabbitTemplate,
                new ObjectMapper(),
                new W3cTraceContextPropagation(mock(ObjectProvider.class), mock(ObjectProvider.class)),
                new SimpleMeterRegistry(),
                transactionManager,
                Duration.ofSeconds(1)
        );
        when(repository.claimAllUntil(anyCollection(), any(Instant.class)))
                .thenAnswer(call -> ((Collection<UUID>) call.getArgument(0)).size());
    }

    @Test
    void oneClaimAndOneMarkCoverTheWholeBatch() {
        OutboxEventEntity first = event(UUID.randomUUID(), "PaymentCreated", "first");
        OutboxEventEntity second = event(UUID.randomUUID(), "PaymentCompleted", "second");
        OutboxEventEntity third = event(UUID.randomUUID(), "PaymentCancelled", "third");
        when(repository.findPendingForPublishing(any(Instant.class), anyInt()))
                .thenReturn(List.of(first, second, third));
        brokerAnswers(true);

        assertThat(relay.relayPendingEvents()).isEqualTo(3);

        verify(repository, times(1)).findPendingForPublishing(any(Instant.class), eq(OutboxRelayService.BATCH_SIZE));
        verify(repository, times(1)).claimAllUntil(eq(List.of(first.getId(), second.getId(), third.getId())), any(Instant.class));
        verify(repository, times(1)).markPublished(eq(List.of(first.getId(), second.getId(), third.getId())), any(Instant.class));
        verify(transactionManager, times(2)).getTransaction(any());
        assertThat(sentPayloads).containsExactly("first", "second", "third");
    }

    @Test
    void laterEventOfTheSameAggregateWaitsForTheNextBatch() {
        UUID aggregate = UUID.randomUUID();
        OutboxEventEntity created = event(aggregate, "PaymentCreated", "created");
        OutboxEventEntity other = event(UUID.randomUUID(), "PaymentCreated", "other");
        OutboxEventEntity completed = event(aggregate, "PaymentCompleted", "completed");
        when(repository.findPendingForPublishing(any(Instant.class), anyInt()))
                .thenReturn(List.of(created, other, completed))
                .thenReturn(List.of(completed))
                .thenReturn(List.of());
        brokerAnswers(true);

        assertThat(relay.relayPendingEvents()).isEqualTo(3);

        ArgumentCaptor<Collection<UUID>> marked = idsCaptor();
        verify(repository, times(2)).markPublished(marked.capture(), any(Instant.class));
        assertThat(marked.getAllValues().get(0)).containsExactly(created.getId(), other.getId());
        assertThat(marked.getAllValues().get(1)).containsExactly(completed.getId());
        assertThat(sentPayloads).containsExactly("created", "other", "completed");
    }

    @Test
    void nackedEventIsRescheduledAndTheRestOfTheBatchIsMarked() {
        OutboxEventEntity good = event(UUID.randomUUID(), "PaymentCreated", "good");
        OutboxEventEntity bad = event(UUID.randomUUID(), "PaymentCreated", "bad");
        when(repository.findPendingForPublishing(any(Instant.class), anyInt())).thenReturn(List.of(good, bad));
        when(repository.findById(bad.getId())).thenReturn(Optional.of(bad));
        brokerAnswers(payload -> !"bad".equals(payload));

        assertThat(relay.relayPendingEvents()).isEqualTo(1);

        verify(repository).markPublished(eq(List.of(good.getId())), any(Instant.class));
        assertThat(bad.getAttempts()).isEqualTo(1);
        assertThat(bad.getLastError()).contains("nacked");
        assertThat(bad.getNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void emptyOutboxDoesNotClaimOrPublish() {
        when(repository.findPendingForPublishing(any(Instant.class), anyInt())).thenReturn(List.of());

        assertThat(relay.relayPendingEvents()).isZero();

        verify(repository, never()).claimAllUntil(anyCollection(), any(Instant.class));
        verify(repository, never()).markPublished(anyCollection(), any(Instant.class));
    }

    @Test
    void fullBatchesKeepDrainingUntilThePollBound() {
        when(repository.findPendingForPublishing(any(Instant.class), anyInt()))
                .thenAnswer(call -> fullBatch());
        brokerAnswers(true);

        int published = relay.relayPendingEvents();

        assertThat(published).isEqualTo(OutboxRelayService.BATCH_SIZE * OutboxRelayService.MAX_BATCHES_PER_POLL);
        verify(repository, times(OutboxRelayService.MAX_BATCHES_PER_POLL))
                .markPublished(anyCollection(), any(Instant.class));
    }

    private static List<OutboxEventEntity> fullBatch() {
        List<OutboxEventEntity> batch = new ArrayList<>(OutboxRelayService.BATCH_SIZE);
        for (int i = 0; i < OutboxRelayService.BATCH_SIZE; i++) {
            batch.add(event(UUID.randomUUID(), "PaymentCreated", "p" + i));
        }
        return batch;
    }

    private void brokerAnswers(boolean ack) {
        brokerAnswers(payload -> ack);
    }

    private void brokerAnswers(java.util.function.Predicate<String> ack) {
        doAnswer(call -> {
            String payload = call.getArgument(2);
            CorrelationData correlation = call.getArgument(4);
            sentPayloads.add(payload);
            correlation.getFuture().complete(new CorrelationData.Confirm(ack.test(payload), ack.test(payload) ? null : "refused"));
            return null;
        }).when(rabbitTemplate).convertAndSend(
                anyString(), anyString(), any(Object.class), any(MessagePostProcessor.class), any(CorrelationData.class));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Collection<UUID>> idsCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    private static OutboxEventEntity event(UUID aggregateId, String type, String payload) {
        return new OutboxEventEntity(
                UUID.randomUUID(), "Payment", aggregateId, type, payload, "{}", Instant.now().minusSeconds(1));
    }
}
