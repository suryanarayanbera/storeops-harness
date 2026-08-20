package com.cognizant.storeops.alerts.repository;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationChannel;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Persistence mapping for the {@code notifications} table. */
@Entity
@Table(name = "notifications")
public class NotificationEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "recipient_id", nullable = false, length = 64)
    private String recipientId;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 30)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private NotificationChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "subject", length = 200)
    private String subject;

    @Column(name = "body", length = 2000)
    private String body;

    @Column(name = "source_ref", length = 64)
    private String sourceRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** Required by JPA. Not for application use. */
    protected NotificationEntity() {
        // Hibernate instantiates through reflection.
    }

    static NotificationEntity fromDomain(final Notification notification) {
        final NotificationEntity entity = new NotificationEntity();
        entity.id = notification.id();
        entity.recipientId = notification.recipientId();
        entity.alertType = notification.alertType();
        entity.channel = notification.channel();
        entity.status = notification.status();
        entity.subject = notification.subject();
        entity.body = notification.body();
        entity.sourceRef = notification.sourceRef();
        entity.createdAt = notification.createdAt();
        entity.sentAt = notification.sentAt();
        return entity;
    }

    Notification toDomain() {
        return new Notification(id, recipientId, alertType, channel, status, subject, body,
                sourceRef, createdAt, sentAt);
    }
}
