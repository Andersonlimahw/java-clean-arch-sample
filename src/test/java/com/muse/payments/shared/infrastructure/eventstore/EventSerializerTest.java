package com.muse.payments.shared.infrastructure.eventstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.muse.payments.shared.application.EventUpcaster;
import com.muse.payments.shared.application.EventTypeRegistration;
import com.muse.payments.shared.domain.DomainEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventSerializerTest {

    private static final String STABLE_TYPE = "sample.account-credited";
    private static final UUID EVENT_ID = UUID.fromString("ec01a913-bd79-4e47-8c41-5d2e067f2c21");
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-15T12:00:00Z");

    @Test
    void serializesWithTheRegisteredStableNameAndCurrentVersion() {
        EventSerializer serializer = serializer(List.of());

        SerializedEvent serialized = serializer.serialize(new AccountCredited(
                EVENT_ID, OCCURRED_AT, "12.00", "api"));

        assertThat(serialized.eventType()).isEqualTo(STABLE_TYPE);
        assertThat(serialized.eventType()).isNotEqualTo(AccountCredited.class.getSimpleName());
        assertThat(serialized.schemaVersion()).isEqualTo(2);
        assertThat(serialized.payload().get("amount").asText()).isEqualTo("12.00");
    }

    @Test
    void upcastsAStoredVersionOneFixtureBeforeDeserializing() throws IOException {
        EventSerializer serializer = serializer(List.of(new AccountEventUpcaster()));
        JsonNode fixture;
        try (InputStream input = getClass().getResourceAsStream("/eventstore/sample-event-v1.json")) {
            assertThat(input).isNotNull();
            fixture = objectMapper().readTree(input);
        }

        DomainEvent event = serializer.deserialize(STABLE_TYPE, 1, fixture.toString());

        assertThat(event).isInstanceOf(AccountCredited.class);
        assertThat((AccountCredited) event).isEqualTo(new AccountCredited(
                EVENT_ID, OCCURRED_AT, "12.00", "legacy"));
    }

    @Test
    void rejectsAClassNameAsAnEventType() {
        assertThatThrownBy(() -> new EventTypeRegistration(
                AccountCredited.class.getSimpleName(), 1, AccountCredited.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Event type must be a stable, namespaced name");
    }

    @Test
    void requiresExactlyOneUpcasterForEachSchemaStep() throws IOException {
        EventSerializer serializer = serializer(List.of());
        JsonNode fixture;
        try (InputStream input = getClass().getResourceAsStream("/eventstore/sample-event-v1.json")) {
            assertThat(input).isNotNull();
            fixture = objectMapper().readTree(input);
        }

        assertThatThrownBy(() -> serializer.deserialize(STABLE_TYPE, 1, fixture.toString()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Expected one upcaster");
    }

    private static EventSerializer serializer(List<EventUpcaster> upcasters) {
        EventTypeRegistry registry = new EventTypeRegistry(List.of(
                new EventTypeRegistration(STABLE_TYPE, 2, AccountCredited.class)));
        return new EventSerializer(objectMapper(), registry, upcasters);
    }

    private static ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private record AccountCredited(UUID eventId, Instant occurredAt, String amount, String source)
            implements DomainEvent {
    }

    private static final class AccountEventUpcaster implements EventUpcaster {

        @Override
        public boolean supports(String eventType, int sourceSchemaVersion) {
            return STABLE_TYPE.equals(eventType) && sourceSchemaVersion == 1;
        }

        @Override
        public String upcast(String payloadJson) {
            try {
                ObjectNode payload = (ObjectNode) objectMapper().readTree(payloadJson);
                payload.put("source", "legacy");
                return payload.toString();
            } catch (IOException exception) {
                throw new IllegalArgumentException("Test event fixture is invalid", exception);
            }
        }
    }
}
