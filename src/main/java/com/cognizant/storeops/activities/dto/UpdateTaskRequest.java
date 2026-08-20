package com.cognizant.storeops.activities.dto;

import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;

/**
 * Payload for {@code PATCH /api/tasks/{id}}. Every field is optional; a null field means "leave
 * unchanged". The service rejects a request in which everything is null.
 *
 * @param status     new lifecycle state
 * @param priority   new urgency band
 * @param assigneeId new owner
 */
public record UpdateTaskRequest(TaskStatus status, TaskPriority priority, String assigneeId) {

    public boolean isEmpty() {
        return status == null && priority == null && (assigneeId == null || assigneeId.isBlank());
    }
}
