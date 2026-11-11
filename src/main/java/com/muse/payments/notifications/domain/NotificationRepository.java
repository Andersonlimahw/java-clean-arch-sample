package com.muse.payments.notifications.domain;

import java.util.List;
import java.util.UUID;

public interface NotificationRepository {

    Notification save(Notification notification);

    List<Notification> findByUserId(UUID userId);

    long countByPaymentId(UUID paymentId);
}
