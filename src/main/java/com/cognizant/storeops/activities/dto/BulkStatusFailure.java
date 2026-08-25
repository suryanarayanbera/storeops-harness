package com.cognizant.storeops.activities.dto;

import com.cognizant.storeops.shared.error.AppError;

/**
 * One activity's failure inside a shift handover batch.
 *
 * <p>Carries the three fields of {@link com.cognizant.storeops.shared.error.ErrorResponse} that
 * still mean something per item; {@code path} and {@code timestamp} describe the request as a
 * whole and would be identical on every entry.
 *
 * <p>This is a wire record, not an {@code AppError} subtype: the error vocabulary stays closed and
 * lives entirely in {@code shared/error}.
 *
 * @param taskId     activity the instruction named
 * @param code       stable machine-readable identifier, e.g. {@code TASK_NOT_FOUND}
 * @param message    human-readable explanation
 * @param statusCode the HTTP status this failure would have produced on its own
 */
public record BulkStatusFailure(String taskId, String code, String message, int statusCode) {

    /** Flattens a thrown error into its per-item reported form. */
    public static BulkStatusFailure from(final String taskId, final AppError error) {
        return new BulkStatusFailure(taskId, error.getCode(), error.getMessage(), error.getStatusCode());
    }
}
