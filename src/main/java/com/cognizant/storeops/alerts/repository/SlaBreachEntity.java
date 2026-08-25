package com.cognizant.storeops.alerts.repository;

import com.cognizant.storeops.alerts.domain.SlaBreach;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persistence mapping for the {@code sla_breaches} table.
 *
 * <p>The activity id is the primary key, which is what makes "one open episode per activity" a database
 * guarantee rather than a convention the service has to remember. It is not a foreign key: the activity
 * lives in another module's table and a cross-module constraint would couple the two schemas.
 *
 * <p>{@code priority} is stored as a plain string, not an {@code @Enumerated} column, because the value
 * arrives as a string on the event - the alerts module never imports {@code TaskPriority}.
 */
@Entity
@Table(name = "sla_breaches")
public class SlaBreachEntity {

    @Id
    @Column(name = "task_id", nullable = false, length = 64)
    private String taskId;

    @Column(name = "store_id", nullable = false, length = 64)
    private String storeId;

    @Column(name = "priority", nullable = false, length = 30)
    private String priority;

    @Column(name = "first_breach_at", nullable = false)
    private Instant firstBreachAt;

    @Column(name = "lead_recipient_id", nullable = false, length = 64)
    private String leadRecipientId;

    @Column(name = "lead_notified_at", nullable = false)
    private Instant leadNotifiedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "escalation_recipient_id", length = 64)
    private String escalationRecipientId;

    @Column(name = "escalated_at")
    private Instant escalatedAt;

    /** Required by JPA. Not for application use. */
    protected SlaBreachEntity() {
        // Hibernate instantiates through reflection.
    }

    static SlaBreachEntity fromDomain(final SlaBreach breach) {
        final SlaBreachEntity entity = new SlaBreachEntity();
        entity.taskId = breach.taskId();
        entity.storeId = breach.storeId();
        entity.priority = breach.priority();
        entity.firstBreachAt = breach.firstBreachAt();
        entity.leadRecipientId = breach.leadRecipientId();
        entity.leadNotifiedAt = breach.leadNotifiedAt();
        entity.lastSeenAt = breach.lastSeenAt();
        entity.escalationRecipientId = breach.escalationRecipientId();
        entity.escalatedAt = breach.escalatedAt();
        return entity;
    }

    SlaBreach toDomain() {
        return new SlaBreach(taskId, storeId, priority, firstBreachAt, leadRecipientId, leadNotifiedAt,
                lastSeenAt, escalationRecipientId, escalatedAt);
    }
}
