package com.cognizant.storeops.reports.repository;

import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** H2-backed {@link ReportRepository}. */
@Repository
public class JpaReportRepository implements ReportRepository {

    private final ReportJpaRepository reports;

    JpaReportRepository(final ReportJpaRepository reports) {
        this.reports = reports;
    }

    @Override
    public Report save(final Report report) {
        return reports.save(ReportEntity.fromDomain(report)).toDomain();
    }

    @Override
    public Optional<Report> findById(final String id) {
        return id == null ? Optional.empty() : reports.findById(id).map(ReportEntity::toDomain);
    }

    @Override
    public List<Report> findAll() {
        return toDomain(reports.findAll(ReportJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Report> findByScopeId(final String scopeId) {
        return toDomain(reports.findByScopeId(scopeId, ReportJpaRepository.DEFAULT_SORT));
    }

    @Override
    public List<Report> findByStatus(final ReportStatus status) {
        return toDomain(reports.findByStatus(status, ReportJpaRepository.DEFAULT_SORT));
    }

    private static List<Report> toDomain(final List<ReportEntity> entities) {
        return entities.stream().map(ReportEntity::toDomain).toList();
    }
}
