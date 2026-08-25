package com.cognizant.storeops.activities.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Payload for {@code PATCH /api/tasks/bulk-status}.
 *
 * <p>An absent {@code updates} list is normalised to an empty one so that a missing field and an
 * empty array fail the same way, through {@code @NotEmpty}, rather than one of them reaching the
 * service as a null.
 *
 * <p>The list is copied into an unmodifiable wrapper rather than through {@code List.copyOf},
 * because a payload of {@code [null]} would make {@code copyOf} throw before bean validation ever
 * ran, turning a validation failure into an unreadable-body error. The {@code @NotNull} on the
 * type argument is what then rejects that null: {@code @Valid} cascades into a collection's
 * elements but skips the null ones, so without it a null item reached the service and threw.
 *
 * @param updates one instruction per activity, at most {@value #MAX_BATCH_SIZE} of them
 */
public record BulkStatusUpdateRequest(
        @NotEmpty(message = "must contain at least one update")
        @Size(max = MAX_BATCH_SIZE, message = "must contain at most {max} updates")
        List<@NotNull(message = "must not be null") @Valid BulkStatusUpdateItem> updates) {

    /** Upper bound on one batch, and the {@code max} the {@code @Size} message interpolates. */
    public static final int MAX_BATCH_SIZE = 50;

    public BulkStatusUpdateRequest {
        updates = updates == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(updates));
    }
}
