package com.cognizant.storeops.alerts.repository;

import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** H2-backed {@link NotificationRepository}. */
@Repository
public class JpaNotificationRepository implements NotificationRepository {

    private final NotificationJpaRepository notifications;

    JpaNotificationRepository(final NotificationJpaRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    public Notification save(final Notification notification) {
        return notifications.save(NotificationEntity.fromDomain(notification)).toDomain();
    }

    @Override
    public Optional<Notification> findById(final String id) {
        return id == null ? Optional.empty() : notifications.findById(id).map(NotificationEntity::toDomain);
    }

    @Override
    public List<Notification> findAll() {
        return toDomain(notifications.findAll(NotificationJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Notification> search(final String recipientId, final NotificationStatus status) {
        return toDomain(notifications.search(recipientId, status, NotificationJpaRepository.DEFAULT_SORT));
    }

    private static List<Notification> toDomain(final List<NotificationEntity> entities) {
        return entities.stream().map(NotificationEntity::toDomain).toList();
    }
}
