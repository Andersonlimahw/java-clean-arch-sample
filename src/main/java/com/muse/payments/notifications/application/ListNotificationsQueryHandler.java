package com.muse.payments.notifications.application;

import com.muse.payments.notifications.domain.NotificationRepository;
import com.muse.payments.shared.application.QueryHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
public class ListNotificationsQueryHandler
        implements QueryHandler<ListNotificationsQuery, List<NotificationResponse>> {

    private final NotificationRepository notificationRepository;

    public ListNotificationsQueryHandler(NotificationRepository notificationRepository) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> handle(ListNotificationsQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return notificationRepository.findByUserId(query.userId()).stream()
                .map(NotificationResponse::from)
                .toList();
    }
}
