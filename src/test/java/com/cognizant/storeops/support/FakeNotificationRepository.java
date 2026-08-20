package com.cognizant.storeops.support;

import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.repository.NotificationRepository;
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
}
