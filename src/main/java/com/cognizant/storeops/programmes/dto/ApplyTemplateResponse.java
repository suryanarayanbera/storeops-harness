package com.cognizant.storeops.programmes.dto;

import java.util.List;

/**
 * Result of {@code POST /api/projects/{id}/templates}, answered {@code 202 Accepted}.
 *
 * <p>{@code 202} rather than {@code 201}, and no task ids in the body: the activities belong to the
 * {@code activities} module, so this endpoint publishes and that module writes. At the moment this
 * response is serialised the rows do not exist yet, and inventing ids for them would be a lie the
 * caller could dereference.
 *
 * <p>Which makes {@code taskCount} worth reading carefully. It is <strong>what the template resolved
 * to</strong>, not a count of activities created. A repeat call reports the same count and creates
 * nothing, because the subscriber skips titles already on the programme. Do not "fix" this to a
 * {@code 201} with a created-count; the count is knowable here and the created-count is not.
 *
 * <p>{@code assignments} is what makes the {@code 202} useful. The caller learns immediately which
 * lines found an owner and which came back unassigned, without waiting for the activities to appear
 * or polling {@code GET /api/tasks} to find out.
 *
 * @param projectId   programme the template was applied to
 * @param templateId  template that was applied
 * @param taskCount   how many activities the template resolved to
 * @param assignments one entry per resolved line, in catalogue order
 */
public record ApplyTemplateResponse(
        String projectId,
        String templateId,
        int taskCount,
        List<TemplateAssignmentResponse> assignments) {

    public ApplyTemplateResponse {
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
    }
}
