package com.cognizant.storeops.activities.dto;

import java.util.List;

/**
 * Report returned by {@code PATCH /api/tasks/bulk-status}, always under {@code 207 Multi-Status}.
 *
 * <p>Both lists are always present, including when empty: a client reading the report must be able to
 * see that nothing failed, not have to infer it from an absent field. Both preserve request order.
 *
 * @param succeeded activities the batch settled without error
 * @param failed    activities the batch rejected, each with its own code and status
 */
public record BulkStatusUpdateResponse(
        List<BulkStatusUpdateSuccess> succeeded,
        List<BulkStatusUpdateFailure> failed) {

    public BulkStatusUpdateResponse {
        succeeded = succeeded == null ? List.of() : List.copyOf(succeeded);
        failed = failed == null ? List.of() : List.copyOf(failed);
    }
}
