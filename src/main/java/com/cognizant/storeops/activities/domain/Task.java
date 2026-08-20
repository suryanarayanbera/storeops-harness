package com.cognizant.storeops.activities.domain;

import java.time.Instant;

/**
 * An operational activity: a restocking run, planogram reset, compliance check or general store
 * task. Immutable; updates go through the {@code with*} copy methods.
 *
 * @param id          stable identifier
 * @param title       short description of the work
 * @param description optional detail
 * @param status      lifecycle state
 * @param priority    urgency band
 * @param category    kind of store work
 * @param storeId     store the work happens in
 * @param projectId   store programme this activity belongs to, may be null for ad-hoc work
 * @param assigneeId  staff member responsible, may be null when unassigned
 * @param dueAt       SLA deadline, may be null
 * @param createdAt   creation time
 * @param updatedAt   last modification time
 */
public record Task(
        String id,
        String title,
        String description,
        TaskStatus status,
        TaskPriority priority,
        TaskCategory category,
        String storeId,
        String projectId,
        String assigneeId,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt) {

    /** True when the SLA deadline has passed and the work is not finished. */
    public boolean isOverdueAt(final Instant moment) {
        return dueAt != null && status != TaskStatus.DONE && moment.isAfter(dueAt);
    }

    /** True for the priority bands the alerts module escalates on breach. */
    public boolean isSlaTracked() {
        return priority == TaskPriority.HIGH || priority == TaskPriority.CRITICAL;
    }

    public Task withStatus(final TaskStatus newStatus, final Instant modifiedAt) {
        return new Task(id, title, description, newStatus, priority, category,
                storeId, projectId, assigneeId, dueAt, createdAt, modifiedAt);
    }

    public Task withPriority(final TaskPriority newPriority, final Instant modifiedAt) {
        return new Task(id, title, description, status, newPriority, category,
                storeId, projectId, assigneeId, dueAt, createdAt, modifiedAt);
    }

    public Task withAssignee(final String newAssigneeId, final Instant modifiedAt) {
        return new Task(id, title, description, status, priority, category,
                storeId, projectId, newAssigneeId, dueAt, createdAt, modifiedAt);
    }
}
