package com.cognizant.storeops.reports.repository;

import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import java.util.List;
import java.util.Optional;

/**
 * Data access for report records. Owned by the reports module.
 *
 * <p>The only repository the reports module is permitted to write to. Enforced by
 * {@code ModuleBoundaryTest}.
 */
public interface ReportRepository {

    Report save(Report report);

    Optional<Report> findById(String id);

    List<Report> findAll();

    List<Report> findByScopeId(String scopeId);

    List<Report> findByStatus(ReportStatus status);
}
