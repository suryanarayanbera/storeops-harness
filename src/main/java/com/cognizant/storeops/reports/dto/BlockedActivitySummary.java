package com.cognizant.storeops.reports.dto;

/**
 * One blocked activity, as it appears in a regional rollup.
 *
 * <p>{@code category} and {@code priority} are the enum <em>names</em> rather than the enum types.
 * The reports module is allowed to read {@code activities.domain}, but the wire format should not
 * change shape because another module renames a constant, and a string keeps this record readable
 * from a JSON assertion.
 *
 * @param taskId     activity identifier
 * @param storeId    store the activity belongs to
 * @param title      short description of the work
 * @param category   {@code TaskCategory} name, null when the activity is uncategorised
 * @param priority   {@code TaskPriority} name
 * @param assigneeId staff member responsible, null when unassigned
 */
public record BlockedActivitySummary(
        String taskId,
        String storeId,
        String title,
        String category,
        String priority,
        String assigneeId) {
}
