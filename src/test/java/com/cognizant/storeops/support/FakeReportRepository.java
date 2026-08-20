package com.cognizant.storeops.support;

import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.repository.ReportRepository;
import java.util.List;
import java.util.Objects;

/** Test double for {@link ReportRepository}. Starts empty; tests build the state they need. */
public class FakeReportRepository extends FakeRepository<Report, String> implements ReportRepository {

    public FakeReportRepository() {
        super(Report::id);
    }

    @Override
    public List<Report> findByScopeId(final String scopeId) {
        return findMatching(report -> Objects.equals(report.scopeId(), scopeId));
    }

    @Override
    public List<Report> findByStatus(final ReportStatus status) {
        return findMatching(report -> report.status() == status);
    }
}
