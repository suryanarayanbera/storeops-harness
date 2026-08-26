package com.cognizant.storeops.reports.dto;

/**
 * One store's contribution to a regional rollup.
 *
 * <p>Deliberately narrower than {@link StoreSummaryResponse}: a rollup compares stores on activity
 * throughput, so programme and headcount figures are left out rather than repeated per store.
 *
 * @param storeId             store the row covers
 * @param totalActivities     activity count for the store
 * @param completedActivities activities in {@code DONE}
 * @param completionRate      completed / total, 0.0 when there is nothing to do
 * @param overdueCount        activities past their due date and not {@code DONE}
 * @param blockedCount        activities in {@code BLOCKED}
 */
public record StoreRollupEntry(
        String storeId,
        int totalActivities,
        int completedActivities,
        double completionRate,
        int overdueCount,
        int blockedCount) {
}
