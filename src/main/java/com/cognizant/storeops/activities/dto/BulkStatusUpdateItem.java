package com.cognizant.storeops.activities.dto;

import com.cognizant.storeops.activities.domain.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One activity's instruction inside a shift handover batch.
 *
 * <p>Bean validation here only checks that the item is well formed. Whether the target status is a
 * legal handover target, and whether the transition is permitted for this activity's current state,
 * are stored-state questions and so belong to the service.
 *
 * @param taskId activity to move
 * @param status lifecycle state to move it to; only {@code DONE} and {@code BLOCKED} are accepted
 */
public record BulkStatusUpdateItem(
        @NotBlank(message = "must not be blank")
        String taskId,

        @NotNull(message = "must not be null")
        TaskStatus status) {
}
