package com.muse.payments.notifications.application;

import com.muse.payments.shared.application.Query;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ListNotificationsQuery(UUID userId) implements Query<List<NotificationResponse>> {

    public ListNotificationsQuery {
        Objects.requireNonNull(userId, "userId must not be null");
    }
}
