package com.cognizant.storeops.reports.repository;

import com.cognizant.storeops.reports.domain.ReportStatus;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the {@code reports} table. Package-private: only
 * {@code JpaReportRepository} may use it.
 */
interface ReportJpaRepository extends JpaRepository<ReportEntity, String> {

    /** Newest first, id as tie-breaker, so list responses are stable across calls. */
    Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("requestedAt"), Sort.Order.asc("id"));

    List<ReportEntity> findByScopeId(String scopeId, Sort sort);

    List<ReportEntity> findByStatus(ReportStatus status, Sort sort);
}
