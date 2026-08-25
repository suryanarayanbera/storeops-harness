package com.cognizant.storeops.support;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.repository.NotificationRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Test double for {@link NotificationRepository}. Starts empty; tests build the state they need. */
public class FakeNotificationRepository
        extends FakeRepository<Notification, String>
        implements NotificationRepository {

    public FakeNotificationRepository() {
        super(Notification::id);
    }

    @Override
    public List<Notification> search(final String recipientId, final NotificationStatus status) {
        return findMatching(notification ->
                (recipientId == null || Objects.equals(notification.recipientId(), recipientId))
                        && (status == null || notification.status() == status));
    }

    /**
     * Oldest first, matching {@code JpaNotificationRepository}'s {@code OLDEST_FIRST}.
     *
     * <p>Sorted rather than left in insertion order on purpose: a caller reading the first element to
     * find the earliest alert would pass against a fake that preserved insertion order and fail
     * against the real query the moment rows arrived out of order.
     */
    @Override
    public List<Notification> findBySourceRefAndAlertType(final String sourceRef, final AlertType alertType) {
        if (sourceRef == null || alertType == null) {
            return List.of();
        }
        return findMatching(notification ->
                Objects.equals(notification.sourceRef(), sourceRef) && notification.alertType() == alertType)
                .stream()
                .sorted(Comparator.comparing(Notification::createdAt).thenComparing(Notification::id))
                .toList();
    }
}
