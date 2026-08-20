package com.cognizant.storeops.shared.events;

import java.time.Instant;

/**
 * Raised by the activities module when an operational activity changes status or priority.
 *
 * <p>Enum-valued fields are carried as {@code String} on purpose: an event travelling between
 * modules must not drag {@code activities.domain} types into its consumers, or the module boundary
 * would be breached by the payload itself.
 *
 * @param taskId         activity that changed
 * @param storeId        store the activity belongs to
 * @param previousStatus {@code TaskStatus} name before the change
 * @param newStatus      {@code TaskStatus} name after the change
 * @param priority       {@code TaskPriority} name at the time of the change
 * @param assigneeId     staff member the activity is assigned to, may be null
 * @param occurredAt     when the change happened
 */
public record TaskStatusChangedEvent(
        String taskId,
        String storeId,
        String previousStatus,
        String newStatus,
        String priority,
        String assigneeId,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "TASK_STATUS_CHANGED";
    }
}
