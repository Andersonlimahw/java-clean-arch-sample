package com.muse.payments.notifications.infrastructure;

import com.muse.payments.notifications.domain.Notification;
import com.muse.payments.notifications.domain.NotificationRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class NotificationJpaAdapter implements NotificationRepository {

    private final NotificationJpaRepository notificationJpaRepository;

    public NotificationJpaAdapter(NotificationJpaRepository notificationJpaRepository) {
        this.notificationJpaRepository = Objects.requireNonNull(notificationJpaRepository);
    }

    @Override
    public Notification save(Notification notification) {
        NotificationJpaEntity entity = NotificationJpaEntity.fromDomain(notification);
        NotificationJpaEntity saved = notificationJpaRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    public List<Notification> findByUserId(UUID userId) {
        return notificationJpaRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(NotificationJpaEntity::toDomain)
                .toList();
    }

    @Override
    public long countByPaymentId(UUID paymentId) {
        return notificationJpaRepository.countByPaymentId(paymentId);
    }
}
