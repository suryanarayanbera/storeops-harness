package com.cognizant.storeops.reports.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Store performance summary. Computed on demand from other modules' service layers; nothing here is
 * written back to those modules.
 *
 * @param storeId            store the summary covers
 * @param generatedAt        computation time
 * @param totalActivities    activity count for the store
 * @param completedActivities activities in DONE
 * @param completionRate     completed / total, 0.0 when there is nothing to do
 * @param overdueCount       activities past their due date and not DONE
 * @param blockedCount       activities in BLOCKED
 * @param activitiesByStatus count per {@code TaskStatus} name
 * @param overdueByCategory  overdue count per {@code TaskCategory} name
 * @param activeProgrammes   programmes not yet closed
 * @param headcount          staff registered to the store
 */
public record StoreSummaryResponse(
        String storeId,
        Instant generatedAt,
        int totalActivities,
        int completedActivities,
        double completionRate,
        int overdueCount,
        int blockedCount,
        Map<String, Integer> activitiesByStatus,
        Map<String, Integer> overdueByCategory,
        int activeProgrammes,
        int headcount) {

    public StoreSummaryResponse {
        activitiesByStatus = activitiesByStatus == null ? Map.of() : Map.copyOf(activitiesByStatus);
        overdueByCategory = overdueByCategory == null ? Map.of() : Map.copyOf(overdueByCategory);
    }
}
