package com.muse.payments.shared.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.application.IntegrationEvent;
import com.muse.payments.shared.application.OutboxPort;
import com.muse.payments.shared.infrastructure.observability.W3cTraceContextPropagation;
import com.muse.payments.shared.web.CorrelationContext;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@Component
public class OutboxJpaAdapter implements OutboxPort {

    private final EntityManager entityManager;
    private final ObjectMapper objectMapper;
    private final W3cTraceContextPropagation traceContext;

    public OutboxJpaAdapter(
            EntityManager entityManager,
            ObjectMapper objectMapper,
            W3cTraceContextPropagation traceContext
    ) {
        this.entityManager = Objects.requireNonNull(entityManager);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.traceContext = Objects.requireNonNull(traceContext);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(IntegrationEvent event) {
        Objects.requireNonNull(event, "event cannot be null");

        Map<String, Object> enrichedHeaders = new HashMap<>(event.headers());
        String correlationId = CorrelationContext.get();
        if (correlationId != null) {
            enrichedHeaders.putIfAbsent("correlationId", correlationId);
        }
        traceContext.captureInto(enrichedHeaders);

        try {
            String payloadJson = objectMapper.writeValueAsString(event.payload());
            String headersJson = objectMapper.writeValueAsString(enrichedHeaders);

            OutboxEventEntity entity = new OutboxEventEntity(
                    event.eventId(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.eventType(),
                    payloadJson,
                    headersJson,
                    event.occurredAt()
            );

            entityManager.persist(entity);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize outbox event " + event.eventId(), e);
        }
    }
}
