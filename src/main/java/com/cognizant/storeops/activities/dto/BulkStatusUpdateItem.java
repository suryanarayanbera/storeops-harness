package com.cognizant.storeops.activities.dto;

import com.cognizant.storeops.activities.domain.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * One entry of a shift handover batch: the activity to restatus and the status to set.
 *
 * <p>Bean validation here covers presence only. Whether {@code status} is a legal handover target,
 * and whether the activity exists at all, are questions about stored state and business rules, so
 * they belong to {@code TaskService} - and failing either fails this entry alone, not the batch.
 *
 * @param taskId activity to restatus
 * @param status status to set; the service accepts only {@code DONE} and {@code BLOCKED}
 */
public record BulkStatusUpdateItem(
        @NotBlank(message = "must not be blank")
        String taskId,

        @NotNull(message = "must not be null")
        TaskStatus status) {
}
