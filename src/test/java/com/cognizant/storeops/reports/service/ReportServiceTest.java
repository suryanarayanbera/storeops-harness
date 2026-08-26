package com.cognizant.storeops.reports.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.cognizant.storeops.reports.dto.BlockedActivitySummary;
import com.cognizant.storeops.reports.dto.RegionalRollupResponse;
import com.cognizant.storeops.reports.dto.StoreRollupEntry;
import com.cognizant.storeops.reports.dto.StoreSummaryResponse;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.RegionalRollupRequestedEvent;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.domain.UserProfile;
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
    private RecordingEventBus eventBus;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        reportRepository = new FakeReportRepository();
        taskService = mock(TaskService.class);
        projectService = mock(ProjectService.class);
        userService = mock(UserService.class);
        eventBus = new RecordingEventBus();
        reportService = new ReportService(reportRepository, taskService, projectService, userService,
                eventBus, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** The single rollup event published by the last call, failing the test if there is not one. */
    private RegionalRollupRequestedEvent publishedRollupEvent() {
        final List<RegionalRollupRequestedEvent> events =
                eventBus.published(RegionalRollupRequestedEvent.class);
        assertThat(events).singleElement();
        return events.getFirst();
    }

    private static Task task(
            final String id, final TaskStatus status, final TaskCategory category, final Instant dueAt) {
        return task(id, status, category, dueAt, "store-001");
    }

    private static Task task(
            final String id,
            final TaskStatus status,
            final TaskCategory category,
            final Instant dueAt,
            final String storeId) {
        return new Task(id, "Activity " + id, null, status, TaskPriority.HIGH, category,
                storeId, "project-001", "user-004", dueAt, NOW, NOW);
    }

    private static User userAt(final String id, final String storeId, final String regionId) {
        return new User(id, id + "@storeops.example", "Staff " + id, StaffRole.ASSOCIATE,
                storeId, regionId, true, UserProfile.empty(), NOW);
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
    @DisplayName("regionalRollup aggregates every store in the region into one set of totals")
    void regionalRollupAggregatesAcrossStores() {
        givenSeedRegionNorth();

        final RegionalRollupResponse rollup = reportService.regionalRollup("region-north", "api");

        assertThat(rollup.regionId()).isEqualTo("region-north");
        assertThat(rollup.generatedAt()).isEqualTo(NOW);
        assertThat(rollup.storeCount()).isEqualTo(2);
        assertThat(rollup.totalActivities()).isEqualTo(4);
        assertThat(rollup.completedActivities()).isEqualTo(1);
        assertThat(rollup.completionRate()).isEqualTo(0.25);
        assertThat(rollup.overdueCount()).isEqualTo(2);
        assertThat(rollup.blockedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("regionalRollup breaks overdue counts down by category, zeroes included")
    void regionalRollupBreaksOverdueDownByCategory() {
        givenSeedRegionNorth();

        final RegionalRollupResponse rollup = reportService.regionalRollup("region-north", "api");

        // Every TaskCategory is present. COMPLIANCE is zero because task-003 is DONE despite being
        // past due, and AUDIT is zero because task-004 has no due date at all.
        assertThat(rollup.overdueByCategory()).containsOnly(
                entry("RESTOCKING", 1),
                entry("PLANOGRAM", 1),
                entry("AUDIT", 0),
                entry("COMPLIANCE", 0),
                entry("GENERAL", 0));
    }

    @Test
    @DisplayName("regionalRollup lists the blocked activities in full, not just a count")
    void regionalRollupListsBlockedActivities() {
        givenSeedRegionNorth();

        final RegionalRollupResponse rollup = reportService.regionalRollup("region-north", "api");

        assertThat(rollup.blockedActivities()).singleElement().satisfies(blocked -> {
            assertThat(blocked.taskId()).isEqualTo("task-004");
            assertThat(blocked.storeId()).isEqualTo("store-002");
            assertThat(blocked.category()).isEqualTo("AUDIT");
            assertThat(blocked.priority()).isEqualTo("HIGH");
            assertThat(blocked.assigneeId()).isEqualTo("user-004");
        });
    }

    @Test
    @DisplayName("regionalRollup sorts the store breakdown by store id and totals each store separately")
    void regionalRollupBreaksDownByStore() {
        givenSeedRegionNorth();

        final RegionalRollupResponse rollup = reportService.regionalRollup("region-north", "api");

        assertThat(rollup.storeBreakdown()).extracting(StoreRollupEntry::storeId)
                .containsExactly("store-001", "store-002");
        assertThat(rollup.storeBreakdown().get(0)).satisfies(store -> {
            assertThat(store.totalActivities()).isEqualTo(3);
            assertThat(store.completedActivities()).isEqualTo(1);
            assertThat(store.completionRate()).isEqualTo(0.3333);
            assertThat(store.overdueCount()).isEqualTo(2);
            assertThat(store.blockedCount()).isZero();
        });
        assertThat(rollup.storeBreakdown().get(1)).satisfies(store -> {
            assertThat(store.totalActivities()).isEqualTo(1);
            assertThat(store.completedActivities()).isZero();
            assertThat(store.completionRate()).isZero();
            assertThat(store.overdueCount()).isZero();
            assertThat(store.blockedCount()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("regionalRollup sorts blocked activities by store then activity id")
    void regionalRollupSortsBlockedActivitiesDeterministically() {
        when(userService.findByRegionId("region-north")).thenReturn(List.of(
                userAt("user-005", "store-002", "region-north"),
                userAt("user-002", "store-001", "region-north")));
        when(taskService.findByStoreId("store-001")).thenReturn(List.of(
                task("task-009", TaskStatus.BLOCKED, TaskCategory.GENERAL, null, "store-001"),
                task("task-002", TaskStatus.BLOCKED, TaskCategory.GENERAL, null, "store-001")));
        when(taskService.findByStoreId("store-002")).thenReturn(List.of(
                task("task-001", TaskStatus.BLOCKED, TaskCategory.AUDIT, null, "store-002")));

        final RegionalRollupResponse rollup = reportService.regionalRollup("region-north", "api");

        // store-001 before store-002 even though the roster named store-002 first, and task-002
        // before task-009 even though the repository returned them the other way round.
        assertThat(rollup.blockedActivities())
                .extracting(BlockedActivitySummary::taskId)
                .containsExactly("task-002", "task-009", "task-001");
    }

    @Test
    @DisplayName("regionalRollup keeps a store with no activities in the breakdown at a zero rate")
    void regionalRollupKeepsEmptyStores() {
        when(userService.findByRegionId("region-south"))
                .thenReturn(List.of(userAt("user-009", "store-009", "region-south")));
        when(taskService.findByStoreId("store-009")).thenReturn(List.of());

        final RegionalRollupResponse rollup = reportService.regionalRollup("region-south", "api");

        assertThat(rollup.storeCount()).isEqualTo(1);
        assertThat(rollup.totalActivities()).isZero();
        // isZero() already excludes NaN, which is the division-by-zero outcome being ruled out here.
        assertThat(rollup.completionRate()).isZero();
        assertThat(rollup.storeBreakdown()).singleElement().satisfies(store -> {
            assertThat(store.storeId()).isEqualTo("store-009");
            assertThat(store.totalActivities()).isZero();
            assertThat(store.completionRate()).isZero();
        });
        assertThat(rollup.blockedActivities()).isEmpty();
    }

    @Test
    @DisplayName("regionalRollup counts a store once however many staff it has")
    void regionalRollupDeduplicatesStores() {
        when(userService.findByRegionId("region-north")).thenReturn(List.of(
                userAt("user-002", "store-001", "region-north"),
                userAt("user-003", "store-001", "region-north"),
                userAt("user-009", null, "region-north")));
        when(taskService.findByStoreId("store-001"))
                .thenReturn(List.of(task("task-001", TaskStatus.DONE, TaskCategory.AUDIT, null)));

        final RegionalRollupResponse rollup = reportService.regionalRollup("region-north", "api");

        // Two staff at one store is one store, and a staff member with no store adds none.
        assertThat(rollup.storeCount()).isEqualTo(1);
        assertThat(rollup.totalActivities()).isEqualTo(1);
    }

    @Test
    @DisplayName("regionalRollup rejects a blank region id")
    void regionalRollupRejectsBlankRegionId() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> reportService.regionalRollup("  ", "api"))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED"));
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> reportService.regionalRollup(null, "api"));
    }

    @Test
    @DisplayName("regionalRollup raises REGION_NOT_FOUND rather than an all-zero report")
    void regionalRollupRaisesNotFoundForAnUnknownRegion() {
        when(userService.findByRegionId("region-atlantis")).thenReturn(List.of());

        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> reportService.regionalRollup("region-atlantis", "api"))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("REGION_NOT_FOUND");
                    assertThat(error.getStatusCode()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("regionalRollup rejects an unknown requestedBy before reading any activity")
    void regionalRollupRejectsAnUnknownRequester() {
        when(userService.exists("user-999")).thenReturn(false);

        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> reportService.regionalRollup("region-north", "user-999"))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("USER_NOT_FOUND"));
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("regionalRollup accepts a known requestedBy and defaults a missing one to api")
    void regionalRollupDefaultsTheRequester() {
        givenSeedRegionNorth();
        when(userService.exists("user-001")).thenReturn(true);

        assertThat(reportService.regionalRollup("region-north", "user-001").storeCount()).isEqualTo(2);
        assertThat(reportService.regionalRollup("region-north", null).storeCount()).isEqualTo(2);
        assertThat(reportService.regionalRollup("region-north", "  ").storeCount()).isEqualTo(2);
        // The default is never looked up as a staff id, so no USER_NOT_FOUND for "api".
        verify(userService, never()).exists("api");
    }

    @Test
    @DisplayName("the rollup event names its type and carries only ids, a count and a timestamp")
    void rollupEventTypeAndPayloadAreDecoupled() {
        final RegionalRollupRequestedEvent event =
                new RegionalRollupRequestedEvent("region-north", "user-001", 2, NOW);

        assertThat(event.eventType()).isEqualTo("REGIONAL_ROLLUP_REQUESTED");
        assertThat(event.regionId()).isEqualTo("region-north");
        assertThat(event.requestedBy()).isEqualTo("user-001");
        assertThat(event.storeCount()).isEqualTo(2);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        // Every component is a String, an int or an Instant. A module enum here would drag the
        // owning module into every subscriber, which is what ModuleBoundaryTest rule 3b forbids.
        assertThat(RegionalRollupRequestedEvent.class.getRecordComponents())
                .extracting(component -> component.getType().getName())
                .containsExactly("java.lang.String", "java.lang.String", "int", "java.time.Instant");
    }

    @Test
    @DisplayName("regionalRollup publishes exactly one event carrying the resolved store count")
    void regionalRollupPublishesOneEvent() {
        givenSeedRegionNorth();
        when(userService.exists("user-001")).thenReturn(true);

        final RegionalRollupResponse rollup = reportService.regionalRollup("region-north", "user-001");

        final RegionalRollupRequestedEvent event = publishedRollupEvent();
        assertThat(event.regionId()).isEqualTo("region-north");
        assertThat(event.requestedBy()).isEqualTo("user-001");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        // Tied to the response rather than asserted as a literal, so the event and the body it
        // describes cannot drift apart.
        assertThat(event.storeCount()).isEqualTo(rollup.storeCount()).isEqualTo(2);
        assertThat(eventBus.published()).hasSize(1);
    }

    @Test
    @DisplayName("regionalRollup attributes an unnamed requester to api on the event too")
    void regionalRollupPublishesTheDefaultRequester() {
        givenSeedRegionNorth();

        reportService.regionalRollup("region-north", null);

        assertThat(publishedRollupEvent().requestedBy()).isEqualTo("api");
    }

    @Test
    @DisplayName("a rejected rollup publishes nothing at all")
    void rejectedRollupPublishesNothing() {
        when(userService.findByRegionId("region-atlantis")).thenReturn(List.of());
        when(userService.exists("user-999")).thenReturn(false);

        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> reportService.regionalRollup("  ", "api"));
        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> reportService.regionalRollup("region-atlantis", "api"));
        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> reportService.regionalRollup("region-north", "user-999"));

        assertThat(eventBus.published()).isEmpty();
    }

    /** The seeded {@code region-north}: store-001 with three activities, store-002 with one. */
    private void givenSeedRegionNorth() {
        final Instant past = NOW.minusSeconds(3_600);
        when(userService.findByRegionId("region-north")).thenReturn(List.of(
                userAt("user-002", "store-001", "region-north"),
                userAt("user-005", "store-002", "region-north")));
        when(taskService.findByStoreId("store-001")).thenReturn(List.of(
                task("task-001", TaskStatus.TODO, TaskCategory.RESTOCKING, past),
                task("task-002", TaskStatus.IN_PROGRESS, TaskCategory.PLANOGRAM, past),
                task("task-003", TaskStatus.DONE, TaskCategory.COMPLIANCE, past)));
        when(taskService.findByStoreId("store-002")).thenReturn(List.of(
                task("task-004", TaskStatus.BLOCKED, TaskCategory.AUDIT, null, "store-002")));
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
