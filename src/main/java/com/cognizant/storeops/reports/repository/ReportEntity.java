package com.cognizant.storeops.reports.repository;

import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.domain.ReportType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Persistence mapping for the {@code reports} table.
 *
 * <p>The only table the reports module writes to. Aggregated figures are computed at read time from
 * other modules' service layers and are never persisted back into them.
 */
@Entity
@Table(name = "reports")
public class ReportEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 30)
    private ReportType reportType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "scope_id", nullable = false, length = 64)
    private String scopeId;

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    /** Required by JPA. Not for application use. */
    protected ReportEntity() {
        // Hibernate instantiates through reflection.
    }

    static ReportEntity fromDomain(final Report report) {
        final ReportEntity entity = new ReportEntity();
        entity.id = report.id();
        entity.reportType = report.reportType();
        entity.status = report.status();
        entity.scopeId = report.scopeId();
        entity.requestedBy = report.requestedBy();
        entity.requestedAt = report.requestedAt();
        entity.readyAt = report.readyAt();
        return entity;
    }

    Report toDomain() {
        return new Report(id, reportType, status, scopeId, requestedBy, requestedAt, readyAt);
    }
}
