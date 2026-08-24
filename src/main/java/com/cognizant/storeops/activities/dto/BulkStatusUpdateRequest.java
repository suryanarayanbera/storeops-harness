package com.cognizant.storeops.activities.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Payload for {@code PATCH /api/tasks/bulk-status}.
 *
 * <p>The routes layer's whole share of checking is the shape of the batch: at least one entry, a
 * bounded number of them, and each entry naming an activity and a status. Everything else is a
 * service decision, because everything else needs stored state.
 *
 * <p>The 50-entry ceiling bounds the fan-out rather than the payload size: every entry that moves an
 * assigned activity to {@code BLOCKED} raises a notification in the alerts module.
 *
 * <p>A null {@code updates} is normalised to empty so that a missing field is reported by
 * {@code @Size} as a validation failure rather than as an unreadable body.
 *
 * @param updates activities to restatus, applied in the order given
 */
public record BulkStatusUpdateRequest(
        @Valid
        @Size(min = 1, max = 50, message = "must contain between 1 and 50 activities")
        List<BulkStatusUpdateItem> updates) {

    public BulkStatusUpdateRequest {
        updates = updates == null ? List.of() : List.copyOf(updates);
    }
}
