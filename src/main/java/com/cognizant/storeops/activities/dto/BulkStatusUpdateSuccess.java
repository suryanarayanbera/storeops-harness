package com.cognizant.storeops.activities.dto;

import com.cognizant.storeops.activities.domain.Task;

/**
 * One activity a handover batch settled without error.
 *
 * <p>{@code changed} mirrors exactly whether a {@code TaskStatusChangedEvent} was published for this
 * activity, so a client can tell a real transition from a re-submitted one.
 *
 * @param taskId         the activity
 * @param previousStatus {@code TaskStatus} name before the batch
 * @param newStatus      {@code TaskStatus} name after the batch
 * @param changed        false when the activity already held the requested status and nothing was written
 */
public record BulkStatusUpdateSuccess(String taskId, String previousStatus, String newStatus, boolean changed) {

    /** Reports an activity the batch moved. */
    public static BulkStatusUpdateSuccess changed(final Task previous, final Task current) {
        return new BulkStatusUpdateSuccess(current.id(), previous.status().name(), current.status().name(), true);
    }

    /** Reports an activity that already held the requested status, so nothing was written. */
    public static BulkStatusUpdateSuccess unchanged(final Task task) {
        return new BulkStatusUpdateSuccess(task.id(), task.status().name(), task.status().name(), false);
    }
}
