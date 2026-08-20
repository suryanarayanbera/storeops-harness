package com.cognizant.storeops.alerts.dto;

import com.cognizant.storeops.alerts.domain.Notification;
import java.time.Instant;

/** Wire representation of an operational alert. */
public record NotificationResponse(
        String id,
        String recipientId,
        String alertType,
        String channel,
        String status,
        String subject,
        String body,
        String sourceRef,
        Instant createdAt,
        Instant sentAt) {

    public static NotificationResponse from(final Notification notification) {
        return new NotificationResponse(
                notification.id(),
                notification.recipientId(),
                notification.alertType() == null ? null : notification.alertType().name(),
                notification.channel() == null ? null : notification.channel().name(),
                notification.status() == null ? null : notification.status().name(),
                notification.subject(),
                notification.body(),
                notification.sourceRef(),
                notification.createdAt(),
                notification.sentAt());
    }
}
