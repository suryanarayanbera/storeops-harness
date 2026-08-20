package com.cognizant.storeops.reports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.domain.ProjectStatus;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportStatus;
import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.dto.StoreSummaryResponse;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.domain.UserProfile;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeReportRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Service-layer test for the reports module.
 *
 * <p>The three source modules are mocked at their <em>service</em> layer, which is exactly how
 * {@code ReportService} is allowed to reach them.
 */
class ReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private FakeReportRepository reportRepository;
    private TaskService taskService;
    private ProjectService projectService;
    private UserService userService;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportRepository = new FakeReportRepository();
        taskService = mock(TaskService.class);
        projectService = mock(ProjectService.class);
        userService = mock(UserService.class);
        reportService = new ReportService(reportRepository, taskService, projectService, userService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Task task(
            final String id, final TaskStatus status, final TaskCategory category, final Instant dueAt) {
        return new Task(id, "Activity " + id, null, status, TaskPriority.HIGH, category,
                "store-001", "project-001", "user-004", dueAt, NOW, NOW);
    }

    private static Project project(final String id, final ProjectStatus status) {
        return new Project(id, "Programme " + id, null, status, "store-001", "region-north",
                "user-002", List.of(), NOW, status == ProjectStatus.CLOSED ? NOW : null);
    }

    private static User user(final String id) {
        return new User(id, id + "@storeops.example", "Staff " + id, StaffRole.ASSOCIATE,
                "store-001", "region-north", true, UserProfile.empty(), NOW);
    }

    @Test
    @DisplayName("storeSummary aggregates counts, completion rate and overdue breakdown")
    void storeSummaryAggregates() {
        final Instant past = NOW.minusSeconds(3_600);
        final Instant future = NOW.plusSeconds(3_600);
        when(taskService.findByStoreId("store-001")).thenReturn(List.of(
                task("task-001", TaskStatus.DONE, TaskCategory.RESTOCKING, past),
                task("task-002", TaskStatus.TODO, TaskCategory.RESTOCKING, past),
                task("task-003", TaskStatus.BLOCKED, TaskCategory.COMPLIANCE, past),
                task("task-004", TaskStatus.IN_PROGRESS, TaskCategory.AUDIT, future)));
        when(projectService.findByStoreId("store-001")).thenReturn(List.of(
                project("project-001", ProjectStatus.ACTIVE),
                project("project-002", ProjectStatus.CLOSED)));
        when(userService.findByStoreId("store-001")).thenReturn(List.of(user("user-003"), user("user-004")));

        final StoreSummaryResponse summary = reportService.storeSummary("store-001");

        assertThat(summary.storeId()).isEqualTo("store-001");
        assertThat(summary.generatedAt()).isEqualTo(NOW);
        assertThat(summary.totalActivities()).isEqualTo(4);
        assertThat(summary.completedActivities()).isEqualTo(1);
        assertThat(summary.completionRate()).isEqualTo(0.25);
        assertThat(summary.blockedCount()).isEqualTo(1);
        // A DONE activity past its due date is not overdue; task-004 is not yet due.
        assertThat(summary.overdueCount()).isEqualTo(2);
        assertThat(summary.overdueByCategory())
                .containsEntry("RESTOCKING", 1)
                .containsEntry("COMPLIANCE", 1);
        assertThat(summary.activitiesByStatus())
                .containsEntry("TODO", 1)
                .containsEntry("IN_PROGRESS", 1)
                .containsEntry("DONE", 1)
                .containsEntry("BLOCKED", 1);
        assertThat(summary.activeProgrammes()).isEqualTo(1);
        assertThat(summary.headcount()).isEqualTo(2);
    }

    @Test
    @DisplayName("storeSummary reports a zero completion rate for a store with no activities")
    void storeSummaryHandlesEmptyStore() {
        when(taskService.findByStoreId("store-009")).thenReturn(List.of());
        when(projectService.findByStoreId("store-009")).thenReturn(List.of());
        when(userService.findByStoreId("store-009")).thenReturn(List.of());

        final StoreSummaryResponse summary = reportService.storeSummary("store-009");

        assertThat(summary.totalActivities()).isZero();
        assertThat(summary.completionRate()).isZero();
        assertThat(summary.overdueByCategory()).isEmpty();
        assertThat(summary.activitiesByStatus()).containsEntry("TODO", 0);
    }

    @Test
    @DisplayName("storeSummary rejects a blank store id")
    void storeSummaryRejectsBlankStoreId() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> reportService.storeSummary("  "));
    }

    @Test
    @DisplayName("queue records a PENDING report in the reports module's own store")
    void queueRecordsPendingReport() {
        final Report queued = reportService.queue(ReportType.STORE_SUMMARY, "store-001", "user-002");

        assertThat(queued.status()).isEqualTo(ReportStatus.PENDING);
        assertThat(queued.reportType()).isEqualTo(ReportType.STORE_SUMMARY);
        assertThat(queued.scopeId()).isEqualTo("store-001");
        assertThat(queued.requestedBy()).isEqualTo("user-002");
        assertThat(queued.requestedAt()).isEqualTo(NOW);
        assertThat(queued.readyAt()).isNull();
        assertThat(reportService.findByScopeId("store-001")).containsExactly(queued);
    }

    @Test
    @DisplayName("markReady transitions a queued report and stamps readyAt")
    void markReadyTransitionsReport() {
        final Report queued = reportService.queue(ReportType.REGIONAL_ROLLUP, "region-north", "user-001");

        final Report ready = reportService.markReady(queued.id());

        assertThat(ready.status()).isEqualTo(ReportStatus.READY);
        assertThat(ready.readyAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("markReady raises a typed NotFoundError for an unknown report")
    void markReadyRaisesTypedNotFound() {
        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> reportService.markReady("nope"))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("REPORT_NOT_FOUND"));
    }
}
