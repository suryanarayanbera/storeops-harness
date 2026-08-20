package com.cognizant.storeops.activities.dto;

import com.cognizant.storeops.activities.domain.Task;
import java.time.Instant;

/** Wire representation of an operational activity. */
public record TaskResponse(
        String id,
        String title,
        String description,
        String status,
        String priority,
        String category,
        String storeId,
        String projectId,
        String assigneeId,
        Instant dueAt,
        Instant createdAt,
        Instant updatedAt) {

    public static TaskResponse from(final Task task) {
        return new TaskResponse(
                task.id(),
                task.title(),
                task.description(),
                task.status() == null ? null : task.status().name(),
                task.priority() == null ? null : task.priority().name(),
                task.category() == null ? null : task.category().name(),
                task.storeId(),
                task.projectId(),
                task.assigneeId(),
                task.dueAt(),
                task.createdAt(),
                task.updatedAt());
    }
}
