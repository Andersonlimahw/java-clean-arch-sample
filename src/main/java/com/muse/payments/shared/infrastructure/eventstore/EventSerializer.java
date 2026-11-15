package com.muse.payments.shared.infrastructure.eventstore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.EventTypeRegistration;
import com.muse.payments.shared.application.EventUpcaster;
import com.muse.payments.shared.domain.DomainEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class EventSerializer {

    private final ObjectMapper objectMapper;
    private final EventTypeRegistry registry;
    private final List<EventUpcaster> upcasters;

    public EventSerializer(
            ObjectMapper objectMapper,
            EventTypeRegistry registry,
            List<EventUpcaster> upcasters
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.registry = Objects.requireNonNull(registry);
        this.upcasters = List.copyOf(Objects.requireNonNull(upcasters));
    }

    SerializedEvent serialize(DomainEvent event) {
        EventTypeRegistration registration = registry.forEvent(event);
        JsonNode payload = objectMapper.valueToTree(event);
        if (payload == null || !payload.isObject()) {
            throw new IllegalStateException("Domain events must serialize to JSON objects");
        }
        return new SerializedEvent(registration.name(), registration.schemaVersion(), payload);
    }

    DomainEvent deserialize(String eventType, int storedSchemaVersion, String payloadJson) {
        EventTypeRegistration registration = registry.forName(eventType);
        if (storedSchemaVersion < 1 || storedSchemaVersion > registration.schemaVersion()) {
            throw new IllegalStateException("Unsupported schema version " + storedSchemaVersion
                    + " for event type " + eventType);
        }
        try {
            String upcastedJson = payloadJson;
            int schemaVersion = storedSchemaVersion;
            while (schemaVersion < registration.schemaVersion()) {
                int sourceVersion = schemaVersion;
                List<EventUpcaster> matching = upcasters.stream()
                        .filter(upcaster -> upcaster.supports(eventType, sourceVersion))
                        .toList();
                if (matching.size() != 1) {
                    throw new IllegalStateException("Expected one upcaster for " + eventType
                            + " schema version " + sourceVersion + ", found " + matching.size());
                }
                upcastedJson = Objects.requireNonNull(matching.getFirst().upcast(upcastedJson),
                        "Upcaster returned null payload");
                JsonNode payload = objectMapper.readTree(upcastedJson);
                if (payload == null || !payload.isObject()) {
                    throw new IllegalStateException("Upcasters must return JSON object payloads");
                }
                schemaVersion++;
            }
            JsonNode payload = objectMapper.readTree(upcastedJson);
            if (payload == null || !payload.isObject()) {
                throw new IllegalStateException("Stored event payload must be a JSON object");
            }
            return objectMapper.treeToValue(payload, registration.eventClass());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored event payload is invalid for " + eventType, e);
        }
    }

    String serializeMetadata(EventMetadata metadata) {
        return objectMapper.valueToTree(Objects.requireNonNull(metadata)).toString();
    }

    EventMetadata deserializeMetadata(String metadataJson) {
        try {
            return objectMapper.readValue(metadataJson, EventMetadata.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored event metadata is invalid", e);
        }
    }
}
