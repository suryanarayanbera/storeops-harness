package com.cognizant.storeops.alerts.repository;

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
}
