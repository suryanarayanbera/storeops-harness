package com.cognizant.storeops.alerts.routes;

import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.dto.NotificationResponse;
import com.cognizant.storeops.alerts.service.NotificationService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP surface of the alerts module. Read-only: alerts are created by events, not by clients. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationRoutes {

    private final NotificationService notificationService;

    public NotificationRoutes(final NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** Endpoint 8: {@code GET /api/notifications}. */
    @GetMapping
    public ResponseEntity<List<NotificationResponse>> listNotifications(
            @RequestParam(required = false) final String recipientId,
            @RequestParam(required = false) final NotificationStatus status) {
        final List<NotificationResponse> body = notificationService.list(recipientId, status).stream()
                .map(NotificationResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }
}
