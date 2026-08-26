package com.cognizant.storeops.reports.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regional rollup of activity throughput across every store in one region.
 *
 * <p>Computed on demand from other modules' service layers; nothing here is written back to those
 * modules. The region's store set comes from the staff roster, because StoreOps records region
 * membership on {@code users} and has no {@code Store} entity.
 *
 * <p>Both lists arrive sorted, and {@code overdueByCategory} always carries every
 * {@code TaskCategory} name including the zeroes, in enum declaration order. Callers should not have
 * to distinguish "no overdue audits" from "the audit key was omitted", and repository iteration order
 * is not a contract.
 *
 * @param regionId            region the rollup covers
 * @param generatedAt         computation time
 * @param storeCount          distinct stores resolved from the staff roster
 * @param totalActivities     region-wide activity count
 * @param completedActivities region-wide activities in {@code DONE}
 * @param completionRate      completed / total, 0.0 when there is nothing to do
 * @param overdueCount        region-wide activities past their due date and not {@code DONE}
 * @param blockedCount        region-wide activities in {@code BLOCKED}
 * @param overdueByCategory   overdue count per {@code TaskCategory} name, zeroes included, key order
 *                            preserved from the caller
 * @param storeBreakdown      one entry per store, ascending by store id
 * @param blockedActivities   every blocked activity in the region, ascending by store then activity
 */
public record RegionalRollupResponse(
        String regionId,
        Instant generatedAt,
        int storeCount,
        int totalActivities,
        int completedActivities,
        double completionRate,
        int overdueCount,
        int blockedCount,
        Map<String, Integer> overdueByCategory,
        List<StoreRollupEntry> storeBreakdown,
        List<BlockedActivitySummary> blockedActivities) {

    public RegionalRollupResponse {
        // Not Map.copyOf: that makes no iteration-order guarantee, which would discard the category
        // ordering the caller built a LinkedHashMap to establish. A copy into a LinkedHashMap keeps
        // the order and is still an independent snapshot; unmodifiableMap stops the caller mutating
        // it afterwards.
        overdueByCategory = overdueByCategory == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(overdueByCategory));
        storeBreakdown = storeBreakdown == null ? List.of() : List.copyOf(storeBreakdown);
        blockedActivities = blockedActivities == null ? List.of() : List.copyOf(blockedActivities);
    }
}
