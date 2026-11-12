package com.muse.payments.shared.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;

import java.util.Map;

@Configuration
public class RabbitMqConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMqConfig.class);

    public static final String EXCHANGE_PAYMENTS = "payments.events";
    public static final String EXCHANGE_PAYMENTS_DLX = "payments.events.dlx";

    /** Broker queue name kept from the original created-only binding. All payment events use it. */
    public static final String QUEUE_PAYMENT_NOTIFICATIONS = "payments.created.notifications";
    /** Broker DLQ name kept from the original created-only binding. */
    public static final String QUEUE_PAYMENT_NOTIFICATIONS_DLQ = "payments.created.notifications.dlq";

    public static final String ROUTING_KEY_PAYMENT_CREATED = "payment.created";
    public static final String ROUTING_KEY_PAYMENT_COMPLETED = "payment.completed";
    public static final String ROUTING_KEY_PAYMENT_CANCELLED = "payment.cancelled";
    /**
     * Dead-letter routing key kept from the original created-only topology.
     * Created, completed, and cancelled notification failures all use this key.
     */
    public static final String ROUTING_KEY_NOTIFICATIONS_DLQ = "payment.created.dlq";

    @Bean
    public TopicExchange paymentsExchange() {
        return new TopicExchange(EXCHANGE_PAYMENTS, true, false);
    }

    @Bean
    public DirectExchange paymentsDlxExchange() {
        return new DirectExchange(EXCHANGE_PAYMENTS_DLX, true, false);
    }

    @Bean
    public Queue paymentCreatedNotificationsQueue() {
        return QueueBuilder.durable(QUEUE_PAYMENT_NOTIFICATIONS)
                .withArgument("x-dead-letter-exchange", EXCHANGE_PAYMENTS_DLX)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_NOTIFICATIONS_DLQ)
                .build();
    }

    @Bean
    public Queue paymentCreatedNotificationsDlq() {
        return QueueBuilder.durable(QUEUE_PAYMENT_NOTIFICATIONS_DLQ).build();
    }

    @Bean
    public Binding paymentCreatedBinding(Queue paymentCreatedNotificationsQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(paymentCreatedNotificationsQueue)
                .to(paymentsExchange)
                .with(ROUTING_KEY_PAYMENT_CREATED);
    }

    @Bean
    public Binding paymentCompletedBinding(Queue paymentCreatedNotificationsQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(paymentCreatedNotificationsQueue)
                .to(paymentsExchange)
                .with(ROUTING_KEY_PAYMENT_COMPLETED);
    }

    @Bean
    public Binding paymentCancelledBinding(Queue paymentCreatedNotificationsQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(paymentCreatedNotificationsQueue)
                .to(paymentsExchange)
                .with(ROUTING_KEY_PAYMENT_CANCELLED);
    }

    @Bean
    public Binding paymentCreatedDlqBinding(Queue paymentCreatedNotificationsDlq, DirectExchange paymentsDlxExchange) {
        return BindingBuilder.bind(paymentCreatedNotificationsDlq)
                .to(paymentsDlxExchange)
                .with(ROUTING_KEY_NOTIFICATIONS_DLQ);
    }

    /**
     * Replaces Boot's auto-configured factory. Boot's configurer still applies listener
     * properties such as {@code spring.rabbitmq.listener.simple.auto-startup}, so the
     * migration profile can keep consumers stopped. The advice chain is then replaced
     * with the outbox listener retry; {@code spring.rabbitmq.listener.simple.retry} is
     * not the retry mechanism.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            @Value("${app.messaging.listener-max-attempts:5}") int maxAttempts,
            @Value("${app.messaging.listener-initial-interval-ms:500}") long initialIntervalMs,
            @Value("${app.messaging.listener-multiplier:2.0}") double multiplier,
            @Value("${app.messaging.listener-max-interval-ms:10000}") long maxIntervalMs
    ) {
        ExponentialRandomBackOffPolicy backOff = new ExponentialRandomBackOffPolicy();
        backOff.setInitialInterval(initialIntervalMs);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxIntervalMs);

        // Permanent failures skip the backoff and go straight to the DLQ.
        // Transient failures (broker, database) are retried with jitter.
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
                maxAttempts,
                Map.of(
                        MessageConversionException.class, Boolean.FALSE,
                        org.springframework.amqp.AmqpRejectAndDontRequeueException.class, Boolean.FALSE
                ),
                true,
                true
        );

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setRecoveryInterval(3000L);
        factory.setDefaultRequeueRejected(false);
        factory.setObservationEnabled(true);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryPolicy(retryPolicy)
                .backOffPolicy(backOff)
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }

    @Bean
    public MessageConverter jackson2JsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        template.setObservationEnabled(true);
        template.setReturnsCallback(returned -> log.debug(
                "Broker returned {} {}: {}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyText()
        ));
        return template;
    }
}
