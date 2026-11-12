package com.muse.payments.shared.infrastructure.observability;

import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * Turns on Micrometer observations for the existing Rabbit template and listener factory
 * so HTTP traces continue as W3C {@code traceparent} headers on published and consumed messages.
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
