package com.cognizant.storeops.programmes.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for {@code POST /api/projects/{id}/templates}.
 *
 * <p>{@code templateId} is checked for presence here and for existence in the service - the split
 * {@code CreateTaskRequest} already follows, because whether a value is blank is knowable from the
 * request alone and whether it names a real template is not.
 *
 * @param templateId  template to apply, e.g. {@code PLANOGRAM_STANDARD}
 * @param requestedBy staff member asking; optional, and defaults to {@code api}
 */
public record ApplyTemplateRequest(
        @NotBlank(message = "must not be blank")
        String templateId,

        String requestedBy) {
}
