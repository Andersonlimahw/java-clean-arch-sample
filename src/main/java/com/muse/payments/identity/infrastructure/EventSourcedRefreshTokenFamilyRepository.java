package com.muse.payments.identity.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.identity.domain.RefreshTokenFamily;
import com.muse.payments.identity.domain.RefreshTokenFamilyRepository;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.EventStore;
import com.muse.payments.shared.application.EventStream;
import com.muse.payments.shared.application.Snapshot;
import com.muse.payments.shared.application.SnapshotStore;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.infrastructure.EventMetadataProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventSourcedRefreshTokenFamilyRepository implements RefreshTokenFamilyRepository {

    static final int SNAPSHOT_SCHEMA_VERSION = 1;

    private final EventStore eventStore;
    private final SnapshotStore snapshotStore;
    private final RefreshTokenProjector projector;
    private final EventMetadataProvider metadataProvider;
    private final ObjectMapper objectMapper;
    private final int snapshotEvery;
    private final JdbcTemplate jdbcTemplate;

    public EventSourcedRefreshTokenFamilyRepository(
            EventStore eventStore,
            SnapshotStore snapshotStore,
            RefreshTokenProjector projector,
            EventMetadataProvider metadataProvider,
            ObjectMapper objectMapper,
            @Value("${app.event-store.snapshot-every:50}") int snapshotEvery,
            JdbcTemplate jdbcTemplate
    ) {
        this.eventStore = Objects.requireNonNull(eventStore);
        this.snapshotStore = Objects.requireNonNull(snapshotStore);
        this.projector = Objects.requireNonNull(projector);
        this.metadataProvider = Objects.requireNonNull(metadataProvider);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        if (snapshotEvery < 1) {
            throw new IllegalArgumentException("app.event-store.snapshot-every must be positive");
        }
        this.snapshotEvery = snapshotEvery;
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshTokenFamily> findById(UUID familyId) {
        Objects.requireNonNull(familyId, "familyId must not be null");
        StreamRef stream = streamRef(familyId);
        Optional<Snapshot> snapshot = snapshotStore.load(stream, SNAPSHOT_SCHEMA_VERSION);
        if (snapshot.isPresent()) {
            Snapshot snap = snapshot.get();
            FamilySnapshot state = deserializeState(snap.stateJson());
            List<DomainEvent> tail = eventStore.loadAfter(stream, snap.streamVersion())
                    .stream()
                    .map(StoredEvent::event)
                    .toList();
            return Optional.of(RefreshTokenFamily.fromSnapshot(
                    state.familyId(),
                    state.userId(),
                    state.familyRevoked(),
                    state.tokens(),
                    snap.streamVersion(),
                    tail
            ));
        }

        EventStream eventStream = eventStore.load(stream);
        if (eventStream.events().isEmpty()) {
            return Optional.empty();
        }
        List<DomainEvent> history = eventStream.events().stream()
                .map(StoredEvent::event)
                .toList();
        return Optional.of(RefreshTokenFamily.rehydrate(history));
    }

    @Override
    @Transactional
    public RefreshTokenFamily save(RefreshTokenFamily family) {
        Objects.requireNonNull(family, "family must not be null");
        List<DomainEvent> uncommitted = family.pullDomainEvents();
        if (uncommitted.isEmpty()) {
            return family;
        }
        StreamRef stream = streamRef(family.getId());
        long expectedVersion = family.version() - uncommitted.size();
        EventMetadata metadata = metadataProvider.current(
                family.getUserId() != null ? family.getUserId().toString() : null);
        long newVersion = eventStore.append(stream, expectedVersion, uncommitted, metadata);
        long streamVersion = expectedVersion;
        for (DomainEvent event : uncommitted) {
            streamVersion = Math.incrementExact(streamVersion);
            projector.project(stream, event, streamVersion, metadata);
        }

        long lastSnapshotVersion = snapshotStore.load(stream, SNAPSHOT_SCHEMA_VERSION)
                .map(Snapshot::streamVersion)
                .orElse(0L);
        if (newVersion - lastSnapshotVersion >= snapshotEvery) {
            snapshotStore.save(new Snapshot(
                    stream,
                    newVersion,
                    SNAPSHOT_SCHEMA_VERSION,
                    serializeState(family),
                    Instant.now()
            ));
        }
        return family;
    }

    @Override
    @Transactional
    public void revokeFamily(UUID familyId, String reason) {
        doRevokeFamily(familyId, reason);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamilyInNewTransaction(UUID familyId, String reason) {
        doRevokeFamily(familyId, reason);
    }

    private void doRevokeFamily(UUID familyId, String reason) {
        // Serialize revoke with rotation before loading, including concurrent reuse
        // requests. Locking only during append would make a second revoke fail 409.
        jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                statement.setString(1, "RefreshTokenFamily:" + familyId);
                statement.execute();
            }
            return null;
        });
        findById(familyId).ifPresent(family -> {
            family.revokeFamily(reason, Instant.now());
            save(family);
        });
    }

    private static StreamRef streamRef(UUID familyId) {
        return new StreamRef(IdentityEventRegistryConfiguration.REFRESH_TOKEN_FAMILY_STREAM_TYPE, familyId);
    }

    private String serializeState(RefreshTokenFamily family) {
        try {
            FamilySnapshot state = new FamilySnapshot(
                    family.getId(),
                    family.getUserId(),
                    family.isFamilyRevoked(),
                    family.getTokens()
            );
            return objectMapper.writeValueAsString(state);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize RefreshTokenFamily snapshot", ex);
        }
    }

    private FamilySnapshot deserializeState(String json) {
        try {
            return objectMapper.readValue(json, FamilySnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize RefreshTokenFamily snapshot", ex);
        }
    }

    record FamilySnapshot(
            UUID familyId,
            UUID userId,
            boolean familyRevoked,
            List<RefreshTokenFamily.TokenEntry> tokens
    ) {
    }
}
