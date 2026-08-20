package com.cognizant.storeops.shared.events;

import java.time.Instant;

/**
 * Raised by the activities module when an activity passes its due date without reaching DONE.
 *
 * <p>The alerts module turns a HIGH or CRITICAL occurrence into an {@code SLA_BREACH} notification.
 * Activities has no knowledge of that consequence.
 *
 * @param taskId     activity that is overdue
 * @param storeId    store the activity belongs to
 * @param priority   {@code TaskPriority} name
 * @param assigneeId staff member the activity is assigned to, may be null
 * @param dueAt      due date that was missed
 * @param occurredAt when the breach was detected
 */
public record TaskOverdueEvent(
        String taskId,
        String storeId,
        String priority,
        String assigneeId,
        Instant dueAt,
        Instant occurredAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "TASK_OVERDUE";
    }
}
