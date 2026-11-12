package com.muse.payments.shared.infrastructure.observability;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapPropagator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers W3C tracecontext as a {@link TextMapPropagator}.
 * With baggage left at its default, Boot's composite can end up with no trace fields,
 * so Rabbit observations and the outbox capture would not write {@code traceparent}.
 */
@Configuration
class W3cPropagationConfiguration {

    @Bean
    TextMapPropagator w3cTraceContextPropagator() {
        return W3CTraceContextPropagator.getInstance();
    }
}
