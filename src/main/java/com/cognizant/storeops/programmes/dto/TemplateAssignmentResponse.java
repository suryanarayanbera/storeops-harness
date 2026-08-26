package com.cognizant.storeops.programmes.dto;

/**
 * One line of an applied template and who it landed on.
 *
 * <p>A null {@code assigneeId} means no member of the programme works in that department. It is a
 * reportable outcome rather than an error: the work still needs raising, and telling the caller which
 * lines have no owner is more useful than refusing the whole request.
 *
 * @param title      activity title from the template
 * @param department department the line belongs to
 * @param priority   default priority the activity will be created with
 * @param assigneeId member the department resolved to, or null when nobody covers it
 */
public record TemplateAssignmentResponse(
        String title,
        String department,
        String priority,
        String assigneeId) {
}
