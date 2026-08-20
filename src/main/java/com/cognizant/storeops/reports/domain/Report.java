package com.cognizant.storeops.reports.domain;

import java.time.Instant;

/**
 * A report record. Immutable.
 *
 * <p>This is the only entity the reports module writes. Aggregated figures come from other modules
 * at read time and are never persisted back into them.
 *
 * @param id          stable identifier
 * @param reportType  scope of the report
 * @param status      generation state
 * @param scopeId     store id or region id the report covers
 * @param requestedBy staff member who asked for it, or the event that triggered it
 * @param requestedAt request time
 * @param readyAt     completion time, null until READY
 */
public record Report(
        String id,
        ReportType reportType,
        ReportStatus status,
        String scopeId,
        String requestedBy,
        Instant requestedAt,
        Instant readyAt) {

    public Report withStatus(final ReportStatus newStatus, final Instant transitionedAt) {
        final Instant ready = newStatus == ReportStatus.READY ? transitionedAt : readyAt;
        return new Report(id, reportType, newStatus, scopeId, requestedBy, requestedAt, ready);
    }
}
