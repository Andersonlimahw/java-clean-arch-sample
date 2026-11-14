package com.muse.payments.notifications.web;

import com.muse.payments.notifications.application.ListNotificationsQuery;
import com.muse.payments.notifications.application.ListNotificationsQueryHandler;
import com.muse.payments.notifications.application.NotificationResponse;
import com.muse.payments.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications", description = "User event notifications")
@SecurityRequirement(name = "BearerAuth")
public class NotificationController {

    private final ListNotificationsQueryHandler listNotificationsHandler;

    public NotificationController(ListNotificationsQueryHandler listNotificationsHandler) {
        this.listNotificationsHandler = Objects.requireNonNull(listNotificationsHandler);
    }

    @GetMapping
    @Operation(summary = "List current user notifications")
    public ResponseEntity<List<NotificationResponse>> listUserNotifications(@Parameter(hidden = true) CurrentUser currentUser) {
        List<NotificationResponse> notifications = listNotificationsHandler.handle(
                new ListNotificationsQuery(currentUser.userId())
        );
        return ResponseEntity.ok(notifications);
    }
}
