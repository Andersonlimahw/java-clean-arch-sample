package com.muse.payments.shared.infrastructure.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Stores the active W3C trace on the outbox row and restores it when the relay publishes.
 * The HTTP span has ended by the time the scheduler runs, so the broker observation alone
 * would otherwise start a new trace.
 */
@Component
public class W3cTraceContextPropagation {

    private static final Logger log = LoggerFactory.getLogger(W3cTraceContextPropagation.class);

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    public W3cTraceContextPropagation(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    public void captureInto(Map<String, Object> headers) {
        Tracer currentTracer = tracer.getIfAvailable();
        Propagator currentPropagator = propagator.getIfAvailable();
        if (currentTracer == null || currentPropagator == null) {
            return;
        }
        Span current = currentTracer.currentSpan();
        if (current == null || current.context() == null) {
            return;
        }
        try {
            Map<String, String> carrier = new HashMap<>();
            currentPropagator.inject(current.context(), carrier, (target, key, value) -> target.put(key, value));
            carrier.forEach(headers::putIfAbsent);
        } catch (RuntimeException ex) {
            log.warn("Failed to capture trace context for the outbox: {}", ex.getMessage());
        }
    }

    public void runWithParent(Map<String, Object> headers, Runnable action) {
        Tracer currentTracer = tracer.getIfAvailable();
        Propagator currentPropagator = propagator.getIfAvailable();
        if (currentTracer == null || currentPropagator == null || headers == null || headers.get("traceparent") == null) {
            action.run();
            return;
        }
        Span span;
        try {
            span = currentPropagator.extract(headers, (carrier, key) -> {
                Object value = carrier.get(key);
                return value == null ? null : value.toString();
            }).name("outbox relay").start();
        } catch (RuntimeException ex) {
            log.warn("Ignoring unreadable outbox trace context: {}", ex.getMessage());
            action.run();
            return;
        }
        try (Tracer.SpanInScope scope = currentTracer.withSpan(span)) {
            action.run();
        } finally {
            span.end();
        }
    }
}
