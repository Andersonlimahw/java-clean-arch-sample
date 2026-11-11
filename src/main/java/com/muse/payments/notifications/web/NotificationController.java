package com.muse.payments.notifications.web;

import com.muse.payments.notifications.domain.Notification;
import com.muse.payments.notifications.domain.NotificationRepository;
import com.muse.payments.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
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

    private final NotificationRepository notificationRepository;

    public NotificationController(NotificationRepository notificationRepository) {
        this.notificationRepository = Objects.requireNonNull(notificationRepository);
    }

    @GetMapping
    @Operation(summary = "List current user notifications")
    public ResponseEntity<List<Notification>> listUserNotifications(CurrentUser currentUser) {
        List<Notification> notifications = notificationRepository.findByUserId(currentUser.userId());
        return ResponseEntity.ok(notifications);
    }
}
