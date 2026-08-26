package com.cognizant.storeops.reports.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.shared.events.RegionalRollupRequestedEvent;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeReportRepository;
import com.cognizant.storeops.support.RecordingEventBus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The reports module's two inbound reactions: a programme closing, and a regional rollup being
 * requested. Both handlers are invoked directly; dispatch is verified end to end in
 * {@code EventDeliveryIntegrationTest} and {@code RegionalRollupIntegrationTest}.
 *
 * <p>Calling the handler directly cannot prove the annotations on it are right - that is what the
 * integration tests are for - but it is the only place the two handlers can be driven from the same
 * fixture, which is how {@code theTwoHandlersDoNotCrossOver} gets to assert they stay separate.
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
                new RecordingEventBus(),
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

    @Test
    @DisplayName("a requested rollup queues a PENDING REGIONAL_ROLLUP for that region")
    void requestedRollupQueuesRegionalRollup() {
        listener.onRegionalRollupRequested(
                new RegionalRollupRequestedEvent("region-north", "user-001", 2, NOW));

        final List<Report> reports = reportRepository.findAll();
        assertThat(reports).hasSize(1);
        assertThat(reports.getFirst().reportType()).isEqualTo(ReportType.REGIONAL_ROLLUP);
        assertThat(reports.getFirst().status()).isEqualTo(ReportStatus.PENDING);
        assertThat(reports.getFirst().scopeId()).isEqualTo("region-north");
        assertThat(reports.getFirst().requestedBy()).isEqualTo("user-001");
        assertThat(reports.getFirst().requestedAt()).isEqualTo(NOW);
        assertThat(reports.getFirst().readyAt()).isNull();
    }

    @Test
    @DisplayName("the rollup report is scoped to the region, and never typed STORE_SUMMARY")
    void rollupReportIsScopedToRegionAndCorrectlyTyped() {
        listener.onRegionalRollupRequested(
                new RegionalRollupRequestedEvent("region-north", "api", 2, NOW));

        assertThat(reportRepository.findByScopeId("region-north")).hasSize(1);
        // The store count travels on the event for the log line; it is not a scope and must not
        // become one.
        assertThat(reportRepository.findByScopeId("store-001")).isEmpty();
        assertThat(reportRepository.findAll())
                .extracting(Report::reportType)
                .doesNotContain(ReportType.STORE_SUMMARY);
    }

    @Test
    @DisplayName("the two handlers stay separate: each event produces only its own report type")
    void theTwoHandlersDoNotCrossOver() {
        listener.onProgrammeClosed(new ProgrammeClosedEvent("project-001", "store-001", "user-002", NOW));
        listener.onRegionalRollupRequested(
                new RegionalRollupRequestedEvent("region-north", "user-001", 2, NOW));

        assertThat(reportRepository.findByScopeId("store-001"))
                .singleElement()
                .extracting(Report::reportType)
                .isEqualTo(ReportType.STORE_SUMMARY);
        assertThat(reportRepository.findByScopeId("region-north"))
                .singleElement()
                .extracting(Report::reportType)
                .isEqualTo(ReportType.REGIONAL_ROLLUP);
        assertThat(reportRepository.findAll()).hasSize(2);
    }
}
