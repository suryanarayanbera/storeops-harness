package com.cognizant.storeops.reports.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeReportRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The reports module's reaction to a programme closing. The handler is invoked directly; dispatch is
 * verified end to end in {@code EventDeliveryIntegrationTest}.
 */
class ReportEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private FakeReportRepository reportRepository;
    private ReportEventListener listener;

    @BeforeEach
    void setUp() {
        reportRepository = new FakeReportRepository();
        final ReportService reportService = new ReportService(
                reportRepository,
                Mockito.mock(TaskService.class),
                Mockito.mock(ProjectService.class),
                Mockito.mock(UserService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        listener = new ReportEventListener(reportService);
    }

    @Test
    @DisplayName("a closed programme queues a PENDING STORE_SUMMARY for that store")
    void closedProgrammeQueuesStoreSummary() {
        listener.onProgrammeClosed(new ProgrammeClosedEvent("project-001", "store-001", "user-002", NOW));

        final List<Report> reports = reportRepository.findAll();
        assertThat(reports).hasSize(1);
        assertThat(reports.getFirst().reportType()).isEqualTo(ReportType.STORE_SUMMARY);
        assertThat(reports.getFirst().status()).isEqualTo(ReportStatus.PENDING);
        assertThat(reports.getFirst().scopeId()).isEqualTo("store-001");
        assertThat(reports.getFirst().requestedBy()).isEqualTo("user-002");
        assertThat(reports.getFirst().requestedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("the report is scoped to the store, not the programme")
    void reportIsScopedToStore() {
        listener.onProgrammeClosed(new ProgrammeClosedEvent("project-002", "store-002", "user-005", NOW));

        assertThat(reportRepository.findByScopeId("store-002")).hasSize(1);
        assertThat(reportRepository.findByScopeId("project-002")).isEmpty();
    }
}
