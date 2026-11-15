package com.muse.payments.shared.infrastructure.eventstore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.EventStore;
import com.muse.payments.shared.application.EventStream;
import com.muse.payments.shared.application.EventUpcaster;
import com.muse.payments.shared.application.EventTypeRegistration;
import com.muse.payments.shared.application.Snapshot;
import com.muse.payments.shared.application.SnapshotStore;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.domain.ConflictException;
import com.muse.payments.shared.domain.DomainEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(EventStoreIT.EventStoreTestConfiguration.class)
class EventStoreIT extends TestcontainersBase {

    private static final String EVENT_TYPE = "test.sample-event";
    private static final Instant OCCURRED_AT = Instant.parse("2026-11-15T12:00:00Z");
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Autowired
    private EventStore eventStore;

    @Autowired
    private SnapshotStore snapshotStore;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Append stores ordered events and load reconstructs their registered types and metadata")
    void appendAndLoadPreserveStreamOrderAndMetadata() {
        StreamRef stream = stream();
        EventMetadata metadata = new EventMetadata("correlation-1", "cause-1", TRACEPARENT, "actor-1");

        long version = eventStore.append(stream, 0, List.of(
                event(1, "first"),
                event(2, "second")), metadata);

        EventStream loaded = eventStore.load(stream);
        assertThat(version).isEqualTo(2);
        assertThat(loaded.currentVersion()).isEqualTo(2);
        assertThat(loaded.events()).extracting(StoredEvent::streamVersion).containsExactly(1L, 2L);
        assertThat(loaded.events()).extracting(StoredEvent::event)
                .containsExactly(event(1, "first"), event(2, "second"));
        assertThat(loaded.events()).allSatisfy(stored -> {
            assertThat(stored.eventType()).isEqualTo(EVENT_TYPE);
            assertThat(stored.schemaVersion()).isEqualTo(2);
            assertThat(stored.metadata()).isEqualTo(metadata);
            assertThat(stored.transactionId()).isNotBlank();
        });
    }

    @Test
    @DisplayName("Concurrent appends with the same expected version yield one commit and one conflict")
    void concurrentAppendConflictsOnTheSameExpectedVersion() throws Exception {
        StreamRef stream = stream();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AppendResult> first = pool.submit(() -> appendWhenReleased(stream, event(3, "left"), ready, start));
            Future<AppendResult> second = pool.submit(() -> appendWhenReleased(stream, event(4, "right"), ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<AppendResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(AppendResult::success)).hasSize(1);
            assertThat(results.stream().filter(AppendResult::conflict)).hasSize(1);
            assertThat(eventStore.load(stream).events()).hasSize(1);
            assertThat(eventStore.load(stream).currentVersion()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("An expected version ahead of the stream cannot create a version gap")
    void appendRejectsAnExpectedVersionAheadOfTheStream() {
        StreamRef stream = stream();

        assertThatThrownBy(() -> eventStore.append(stream, 2, List.of(event(6, "gap")), EventMetadata.empty()))
                .isInstanceOf(ConflictException.class);
        assertThat(eventStore.load(stream).events()).isEmpty();
    }

    @Test
    @DisplayName("The event store trigger rejects updates and deletes")
    void eventRowsAreImmutable() {
        StreamRef stream = stream();
        eventStore.append(stream, 0, List.of(event(5, "immutable")), EventMetadata.empty());

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE event_store SET event_type = 'test.changed' WHERE stream_type = ? AND stream_id = ?",
                stream.type(), stream.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM event_store WHERE stream_type = ? AND stream_id = ?",
                stream.type(), stream.id())).isInstanceOf(RuntimeException.class);
        assertThat(eventStore.load(stream).events()).hasSize(1);
    }

    @Test
    @DisplayName("A snapshot with a stale schema is ignored while a matching schema is available")
    void snapshotWithDifferentSchemaVersionIsIgnored() throws IOException {
        StreamRef stream = stream();
        Snapshot snapshot = new Snapshot(stream, 50, 1, "{\"balance\":42}", OCCURRED_AT);
        snapshotStore.save(snapshot);

        Snapshot loaded = snapshotStore.load(stream, 1).orElseThrow();
        assertThat(loaded.stream()).isEqualTo(snapshot.stream());
        assertThat(loaded.streamVersion()).isEqualTo(snapshot.streamVersion());
        assertThat(loaded.schemaVersion()).isEqualTo(snapshot.schemaVersion());
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(loaded.stateJson())
                .get("balance").asInt()).isEqualTo(42);
        assertThat(loaded.takenAt()).isEqualTo(snapshot.takenAt());
        assertThat(snapshotStore.load(stream, 2)).isEmpty();
    }

    @Test
    @DisplayName("Loading a stored schema version one event runs its upcaster")
    void loadUpcastsStoredEvents() throws IOException {
        StreamRef stream = stream();
        JsonNode fixture;
        try (InputStream input = getClass().getResourceAsStream("/eventstore/it-sample-event-v1.json")) {
            assertThat(input).isNotNull();
            fixture = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                    .readTree(input);
        }
        jdbcTemplate.update("""
                        INSERT INTO event_store (
                            stream_type, stream_id, stream_version, event_id, event_type, schema_version,
                            payload, metadata, occurred_at
                        ) VALUES (?, ?, 1, ?, ?, 1, ?::jsonb, '{}'::jsonb, ?)
                        """,
                stream.type(), stream.id(), UUID.fromString(fixture.get("eventId").asText()), EVENT_TYPE,
                fixture.toString(), java.sql.Timestamp.from(OCCURRED_AT));

        EventStream loaded = eventStore.load(stream);

        assertThat(loaded.events()).singleElement().satisfies(stored -> {
            assertThat(stored.schemaVersion()).isEqualTo(1);
            assertThat(stored.event()).isInstanceOf(SampleEvent.class);
            SampleEvent sample = (SampleEvent) stored.event();
            assertThat(sample.eventId()).isEqualTo(UUID.fromString("583e5b99-2378-5e35-a2e7-ae94612e02ae"));
            assertThat(sample.sequence()).isEqualTo(6);
            assertThat(sample.value()).isEqualTo("legacy");
        });
    }

    private static StreamRef stream() {
        return new StreamRef("TestStream", UUID.randomUUID());
    }

    private static SampleEvent event(int sequence, String value) {
        return new SampleEvent(UUID.nameUUIDFromBytes(
                (sequence + ":" + value).getBytes(java.nio.charset.StandardCharsets.UTF_8)), OCCURRED_AT,
                sequence, value);
    }

    private AppendResult appendWhenReleased(StreamRef stream, SampleEvent event,
                                            CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent append start timed out");
            }
            eventStore.append(stream, 0, List.of(event), EventMetadata.empty());
            return new AppendResult(true, false);
        } catch (ConflictException exception) {
            return new AppendResult(false, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent append was interrupted", exception);
        }
    }

    private record AppendResult(boolean success, boolean conflict) {
    }

    public record SampleEvent(UUID eventId, Instant occurredAt, int sequence, String value) implements DomainEvent {
    }

    @TestConfiguration
    static class EventStoreTestConfiguration {

        @Bean
        EventTypeRegistration sampleEventRegistration() {
            return new EventTypeRegistration(EVENT_TYPE, 2, SampleEvent.class);
        }

        @Bean
        EventUpcaster sampleEventV1Upcaster() {
            return new EventUpcaster() {
                @Override
                public boolean supports(String eventType, int sourceSchemaVersion) {
                    return EVENT_TYPE.equals(eventType) && sourceSchemaVersion == 1;
                }

                @Override
                public String upcast(String payloadJson) {
                    try {
                        ObjectNode payload = (ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(payloadJson);
                        payload.put("value", "legacy");
                        return payload.toString();
                    } catch (IOException exception) {
                        throw new IllegalArgumentException("Test event fixture is invalid", exception);
                    }
                }
            };
        }
    }
}
