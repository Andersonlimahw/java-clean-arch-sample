package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.domain.User;
import com.muse.payments.identity.domain.UserRepository;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.EventStore;
import com.muse.payments.shared.application.EventStream;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.infrastructure.EventMetadataProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventSourcedUserRepository implements UserRepository {

    private final EventStore eventStore;
    private final UserProjector userProjector;
    private final EventMetadataProvider metadataProvider;

    public EventSourcedUserRepository(
            EventStore eventStore,
            UserProjector userProjector,
            EventMetadataProvider metadataProvider
    ) {
        this.eventStore = Objects.requireNonNull(eventStore);
        this.userProjector = Objects.requireNonNull(userProjector);
        this.metadataProvider = Objects.requireNonNull(metadataProvider);
    }

    @Override
    @Transactional
    public User save(User user) {
        Objects.requireNonNull(user, "user must not be null");
        List<DomainEvent> uncommitted = user.pullDomainEvents();
        if (uncommitted.isEmpty()) {
            return user;
        }
        StreamRef stream = new StreamRef(IdentityEventRegistryConfiguration.USER_STREAM_TYPE, user.getId());
        long expectedVersion = user.version() - uncommitted.size();
        EventMetadata metadata = metadataProvider.current(user.getId().toString());
        eventStore.append(stream, expectedVersion, uncommitted, metadata);
        long streamVersion = expectedVersion;
        for (DomainEvent event : uncommitted) {
            streamVersion = Math.incrementExact(streamVersion);
            userProjector.project(stream, event, streamVersion, metadata);
        }
        return user;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<User> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        EventStream stream = eventStore.load(new StreamRef(IdentityEventRegistryConfiguration.USER_STREAM_TYPE, id));
        if (stream.events().isEmpty()) {
            return Optional.empty();
        }
        List<DomainEvent> history = stream.events().stream()
                .map(StoredEvent::event)
                .toList();
        return Optional.of(User.rehydrate(history));
    }
}
