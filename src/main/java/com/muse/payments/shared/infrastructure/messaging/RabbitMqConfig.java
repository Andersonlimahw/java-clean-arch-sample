package com.muse.payments.shared.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE_PAYMENTS = "payments.events";
    public static final String EXCHANGE_PAYMENTS_DLX = "payments.events.dlx";

    public static final String QUEUE_PAYMENT_CREATED_NOTIFICATIONS = "payments.created.notifications";
    public static final String QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ = "payments.created.notifications.dlq";

    public static final String ROUTING_KEY_PAYMENT_CREATED = "payment.created";
    public static final String ROUTING_KEY_PAYMENT_CREATED_DLQ = "payment.created.dlq";

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
        return QueueBuilder.durable(QUEUE_PAYMENT_CREATED_NOTIFICATIONS)
                .withArgument("x-dead-letter-exchange", EXCHANGE_PAYMENTS_DLX)
                .withArgument("x-dead-letter-routing-key", ROUTING_KEY_PAYMENT_CREATED_DLQ)
                .build();
    }

    @Bean
    public Queue paymentCreatedNotificationsDlq() {
        return QueueBuilder.durable(QUEUE_PAYMENT_CREATED_NOTIFICATIONS_DLQ).build();
    }

    @Bean
    public Binding paymentCreatedBinding(Queue paymentCreatedNotificationsQueue, TopicExchange paymentsExchange) {
        return BindingBuilder.bind(paymentCreatedNotificationsQueue)
                .to(paymentsExchange)
                .with(ROUTING_KEY_PAYMENT_CREATED);
    }

    @Bean
    public Binding paymentCreatedDlqBinding(Queue paymentCreatedNotificationsDlq, DirectExchange paymentsDlxExchange) {
        return BindingBuilder.bind(paymentCreatedNotificationsDlq)
                .to(paymentsDlxExchange)
                .with(ROUTING_KEY_PAYMENT_CREATED_DLQ);
    }

    /**
     * Replaces Boot's auto-configured factory, so listener properties such as
     * {@code spring.rabbitmq.listener.simple.auto-startup} are applied through Boot's
     * configurer. Without that, the migration profile cannot keep consumers stopped.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setRecoveryInterval(3000L);
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
        return template;
    }
}
