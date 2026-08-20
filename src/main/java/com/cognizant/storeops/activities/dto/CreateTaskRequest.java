package com.cognizant.storeops.activities.dto;

import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/**
 * Payload for {@code POST /api/tasks}.
 *
 * <p>Bean validation here is the routes layer's whole share of input checking; anything that needs
 * to consult stored state belongs in the service.
 */
public record CreateTaskRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 200, message = "must be at most 200 characters")
        String title,

        @Size(max = 2000, message = "must be at most 2000 characters")
        String description,

        TaskPriority priority,

        TaskCategory category,

        @NotBlank(message = "must not be blank")
        String storeId,

        String projectId,

        String assigneeId,

        Instant dueAt) {
}
