package com.cognizant.storeops.alerts.service;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationChannel;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.repository.NotificationRepository;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Business logic for operational alerts.
 *
 * <p>Nothing outside the alerts module calls this service. Alerts are raised by
 * {@code AlertEventListener} reacting to events on the bus, which is what keeps the activities and
 * programmes modules free of any alerting import.
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final Clock clock;

    public NotificationService(final NotificationRepository notificationRepository, final Clock clock) {
        this.notificationRepository = notificationRepository;
        this.clock = clock;
    }

    /** Endpoint 8 backing call. Null criteria are ignored. */
    public List<Notification> list(final String recipientId, final NotificationStatus status) {
        return notificationRepository.search(recipientId, status);
    }

    /**
     * Loads one alert.
     *
     * @throws NotFoundError when no alert has that id
     */
    public Notification getById(final String id) {
        return notificationRepository.findById(id).orElseThrow(() -> NotFoundError.of("Notification", id));
    }

    /**
     * Raises an in-app alert in PENDING state.
     *
     * <p>Stub: delivery is not implemented, so the alert stays PENDING until {@link #markSent}
     * is called. A real channel adapter belongs behind this method, not in the routes layer.
     *
     * @throws ValidationError when no recipient is supplied
     */
    public Notification raise(
            final String recipientId,
            final AlertType alertType,
            final String subject,
            final String body,
            final String sourceRef) {
        if (recipientId == null || recipientId.isBlank()) {
            throw new ValidationError("An alert must have a recipient",
                    List.of("recipientId: must not be blank"));
        }
        final Instant now = clock.instant();
        final Notification notification = new Notification(
                UUID.randomUUID().toString(),
                recipientId,
                alertType,
                NotificationChannel.IN_APP,
                NotificationStatus.PENDING,
                subject,
                body,
                sourceRef,
                now,
                null);
        LOG.info("Raised {} alert for {} from source {}", alertType, recipientId, sourceRef);
        return notificationRepository.save(notification);
    }

    /**
     * Marks an alert delivered.
     *
     * @throws NotFoundError when no alert has that id
     */
    public Notification markSent(final String id) {
        final Notification existing = getById(id);
        return notificationRepository.save(existing.withStatus(NotificationStatus.SENT, clock.instant()));
    }
}
