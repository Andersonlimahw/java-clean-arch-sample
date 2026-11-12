package com.muse.payments.shared.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class OutboxRelayConfirmTest {

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    @Test
    void ackWithoutAReturnIsAccepted() throws Exception {
        CorrelationData correlation = correlation();
        correlation.getFuture().complete(new CorrelationData.Confirm(true, null));

        relay(Duration.ofSeconds(1)).awaitPublisherConfirm(correlation, UUID.randomUUID());
    }

    @Test
    void nackIsRejected() {
        CorrelationData correlation = correlation();
        correlation.getFuture().complete(new CorrelationData.Confirm(false, "broker unavailable"));

        assertThatThrownBy(() -> relay(Duration.ofSeconds(1)).awaitPublisherConfirm(correlation, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nacked");
    }

    @Test
    void returnedMessageIsRejectedEvenWhenTheBrokerAcks() {
        CorrelationData correlation = correlation();
        correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
        correlation.setReturned(new ReturnedMessage(
                new Message("payload".getBytes(StandardCharsets.UTF_8), new MessageProperties()),
                312,
                "NO_ROUTE",
                "payments.events",
                "payment.missing"
        ));

        assertThatThrownBy(() -> relay(Duration.ofSeconds(1)).awaitPublisherConfirm(correlation, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("returned");
    }

    @Test
    void confirmTimeoutDoesNotCountAsPublished() {
        CorrelationData correlation = correlation();

        assertThatThrownBy(() -> relay(Duration.ofMillis(30)).awaitPublisherConfirm(correlation, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timed out")
                .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
    }

    @Test
    void interruptStopsTheWaitAndKeepsTheInterruptFlag() {
        CorrelationData correlation = correlation();
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> relay(Duration.ofSeconds(5)).awaitPublisherConfirm(correlation, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void nonPositiveConfirmTimeoutIsRejected() {
        assertThatThrownBy(() -> relay(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confirm-timeout");
    }

    private static CorrelationData correlation() {
        return new CorrelationData(UUID.randomUUID().toString());
    }

    private static OutboxRelayService relay(Duration timeout) {
        return new OutboxRelayService(
                mock(OutboxJpaRepository.class),
                mock(RabbitTemplate.class),
                new ObjectMapper(),
                mock(PlatformTransactionManager.class),
                timeout
        );
    }
}
