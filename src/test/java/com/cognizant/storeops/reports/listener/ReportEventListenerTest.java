package com.cognizant.storeops.reports.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.shared.events.InMemoryEventBus;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
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

/** The reports module's reaction to a programme closing. */
class ReportEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private InMemoryEventBus eventBus;
    private FakeReportRepository reportRepository;

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryEventBus();
        reportRepository = new FakeReportRepository();
        final ReportService reportService = new ReportService(
                reportRepository,
                Mockito.mock(TaskService.class),
                Mockito.mock(ProjectService.class),
                Mockito.mock(UserService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
        new ReportEventListener(eventBus, reportService).register();
    }

    @Test
    @DisplayName("a closed programme queues a PENDING STORE_SUMMARY for that store")
    void closedProgrammeQueuesStoreSummary() {
        eventBus.publish(new ProgrammeClosedEvent("project-001", "store-001", "user-002", NOW));

        final List<Report> reports = reportRepository.findAll();
        assertThat(reports).hasSize(1);
        assertThat(reports.getFirst().reportType()).isEqualTo(ReportType.STORE_SUMMARY);
        assertThat(reports.getFirst().status()).isEqualTo(ReportStatus.PENDING);
        assertThat(reports.getFirst().scopeId()).isEqualTo("store-001");
        assertThat(reports.getFirst().requestedBy()).isEqualTo("user-002");
    }

    @Test
    @DisplayName("the reports module ignores events it did not subscribe to")
    void unrelatedEventIsIgnored() {
        eventBus.publish(new TaskStatusChangedEvent(
                "task-001", "store-001", "TODO", "BLOCKED", "HIGH", "user-004", NOW));

        assertThat(reportRepository.findAll()).isEmpty();
    }
}
