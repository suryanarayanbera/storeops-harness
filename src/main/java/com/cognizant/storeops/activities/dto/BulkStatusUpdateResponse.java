package com.cognizant.storeops.activities.dto;

import java.util.List;

/**
 * Result of {@code PATCH /api/tasks/bulk-status}.
 *
 * <p>Both lists are always present, possibly empty, and preserve request order. An activity
 * appears in exactly one of them. The endpoint answers {@code 200 OK} whichever way the items
 * went: the bulk request itself succeeded, and per-activity outcomes are data in this body rather
 * than transport status.
 *
 * @param succeeded activities that actually changed status, each of which published a
 *                  {@code TaskStatusChangedEvent}
 * @param failed    activities that were left untouched, with the reason
 */
public record BulkStatusUpdateResponse(List<TaskResponse> succeeded, List<BulkStatusFailure> failed) {

    public BulkStatusUpdateResponse {
        succeeded = succeeded == null ? List.of() : List.copyOf(succeeded);
        failed = failed == null ? List.of() : List.copyOf(failed);
    }
}
