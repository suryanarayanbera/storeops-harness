package com.cognizant.storeops.activities.dto;

import com.cognizant.storeops.shared.error.AppError;

/**
 * One activity a handover batch rejected, and why.
 *
 * <p>Carries the same {@code (code, message, statusCode)} triple as the {@code ErrorResponse} body a
 * single-activity {@code PATCH} would have returned, so a client switches on codes it already knows.
 * {@code ErrorResponse} itself is not reused: its {@code path} and {@code timestamp} describe the
 * request, and here the failure describes one entry within it.
 *
 * @param taskId     activity that was not updated
 * @param code       stable machine-readable identifier, e.g. {@code TASK_NOT_FOUND}
 * @param message    human-readable explanation
 * @param statusCode the HTTP status this failure would have produced on its own
 */
public record BulkStatusUpdateFailure(String taskId, String code, String message, int statusCode) {

    /** Flattens a raised error into the per-entry report. */
    public static BulkStatusUpdateFailure from(final String taskId, final AppError error) {
        return new BulkStatusUpdateFailure(taskId, error.getCode(), error.getMessage(), error.getStatusCode());
    }
}
