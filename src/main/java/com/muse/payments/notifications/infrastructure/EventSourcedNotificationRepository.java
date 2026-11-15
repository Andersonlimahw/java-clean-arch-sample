package com.muse.payments.notifications.infrastructure;

import com.muse.payments.notifications.domain.Notification;
import com.muse.payments.notifications.domain.NotificationRepository;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.EventStore;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.infrastructure.EventMetadataProvider;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Repository
public class EventSourcedNotificationRepository implements NotificationRepository {

    private final EventStore eventStore;
    private final NotificationJpaRepository notificationJpaRepository;
    private final EventMetadataProvider metadataProvider;

    public EventSourcedNotificationRepository(
            EventStore eventStore,
            NotificationJpaRepository notificationJpaRepository,
            EventMetadataProvider metadataProvider
    ) {
        this.eventStore = Objects.requireNonNull(eventStore);
        this.notificationJpaRepository = Objects.requireNonNull(notificationJpaRepository);
        this.metadataProvider = Objects.requireNonNull(metadataProvider);
    }

    @Override
    @Transactional
    public Notification save(Notification notification) {
        Objects.requireNonNull(notification, "notification must not be null");
        List<DomainEvent> uncommitted = notification.pullDomainEvents();
        if (uncommitted.isEmpty()) {
            return notification;
        }
        StreamRef stream = new StreamRef(NotificationEventRegistryConfiguration.STREAM_TYPE, notification.getId());
        long expectedVersion = notification.version() - uncommitted.size();
        EventMetadata metadata = metadataProvider.current(notification.getUserId().toString());
        eventStore.append(stream, expectedVersion, uncommitted, metadata);
        return notification;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Notification> findByUserId(UUID userId) {
        return notificationJpaRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(NotificationJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByPaymentId(UUID paymentId) {
        return notificationJpaRepository.countByPaymentId(paymentId);
    }
}
