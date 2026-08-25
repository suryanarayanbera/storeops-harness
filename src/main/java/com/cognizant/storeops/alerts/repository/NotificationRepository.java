package com.cognizant.storeops.alerts.repository;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import java.util.List;
import java.util.Optional;

/**
 * Data access for operational alerts. Owned by the alerts module.
 *
 * <p>No other module may import this interface. Other modules do not read alerts at all - they
 * cause them, by publishing on the event bus. Enforced by {@code ModuleBoundaryTest}.
 */
public interface NotificationRepository {

    Notification save(Notification notification);

    Optional<Notification> findById(String id);

    List<Notification> findAll();

    /** Any null criterion is ignored. */
    List<Notification> search(String recipientId, NotificationStatus status);

    /**
     * Alerts already raised from one source entity, of one type, oldest first.
     *
     * <p>Ascending rather than the newest-first default of the other reads: callers use this to ask
     * "when was this first alerted", which is the oldest row, and reversing the order would silently
     * change that answer once a second row exists.
     */
    List<Notification> findBySourceRefAndAlertType(String sourceRef, AlertType alertType);
}
