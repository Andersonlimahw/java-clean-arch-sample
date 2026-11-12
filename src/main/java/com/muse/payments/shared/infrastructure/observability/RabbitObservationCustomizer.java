package com.muse.payments.shared.infrastructure.observability;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Enables Micrometer observations on the application's Rabbit template and listener factory.
 * Publish writes the current trace as a W3C {@code traceparent}, and consume restores it.
 * The outbox relay restores the trace captured when the row was appended, because the HTTP
 * span has already ended before the scheduler publishes.
 */
@Component
public class RabbitObservationCustomizer implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof SimpleRabbitListenerContainerFactory factory) {
            factory.setObservationEnabled(true);
        }
        if (bean instanceof RabbitTemplate template) {
            template.setObservationEnabled(true);
        }
        return bean;
    }
}
