package com.cognizant.storeops.alerts.domain;

import java.time.Instant;

/**
 * An in-app operational alert. Immutable.
 *
 * @param id          stable identifier
 * @param recipientId staff member the alert is for
 * @param alertType   operational trigger
 * @param channel     delivery channel
 * @param status      delivery state
 * @param subject     short headline
 * @param body        detail text
 * @param sourceRef   id of the entity that triggered the alert, e.g. a task id
 * @param createdAt   creation time
 * @param sentAt      delivery time, null while PENDING
 */
public record Notification(
        String id,
        String recipientId,
        AlertType alertType,
        NotificationChannel channel,
        NotificationStatus status,
        String subject,
        String body,
        String sourceRef,
        Instant createdAt,
        Instant sentAt) {

    public Notification withStatus(final NotificationStatus newStatus, final Instant transitionedAt) {
        final Instant delivered = newStatus == NotificationStatus.SENT ? transitionedAt : sentAt;
        return new Notification(id, recipientId, alertType, channel, newStatus, subject, body, sourceRef,
                createdAt, delivered);
    }
}
