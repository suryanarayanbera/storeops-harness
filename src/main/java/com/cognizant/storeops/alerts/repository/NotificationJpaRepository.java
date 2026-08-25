package com.cognizant.storeops.alerts.repository;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the {@code notifications} table. Package-private: only
 * {@code JpaNotificationRepository} may use it.
 */
interface NotificationJpaRepository extends JpaRepository<NotificationEntity, String> {

    /** Newest first, id as tie-breaker, so list responses are stable across calls. */
    Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));

    /** Oldest first, for callers asking when something was first alerted. */
    Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));

    @Query("""
            SELECT n FROM NotificationEntity n
            WHERE (:recipientId IS NULL OR n.recipientId = :recipientId)
              AND (:status IS NULL OR n.status = :status)
            """)
    List<NotificationEntity> search(
            @Param("recipientId") String recipientId,
            @Param("status") NotificationStatus status,
            Sort sort);

    /** Derived query; both arguments are required, so no null-tolerant JPQL is needed. */
    List<NotificationEntity> findBySourceRefAndAlertType(String sourceRef, AlertType alertType, Sort sort);
}
