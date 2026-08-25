package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateItem;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateRequest;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateResponse;
import com.cognizant.storeops.activities.dto.CreateTaskRequest;
import com.cognizant.storeops.activities.dto.UpdateTaskRequest;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.programmes.domain.Project;
import com.cognizant.storeops.programmes.dto.CreateProjectRequest;
import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.reports.domain.Report;
import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.support.FailingSubscriber;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Event delivery through the real container: publisher to subscriber, across a module boundary,
 * after commit.
 *
 * <p>These are the tests that fail if the wiring is wrong in the way that is hardest to notice.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} does nothing at all when no transaction is
 * active, so a missing {@code @Transactional} on a publishing service method would silently stop all
 * alerting while every other test still passed.
 */
@SpringBootTest
@Import(FailingSubscriber.class)
class EventDeliveryIntegrationTest {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ReportService reportService;

    @Autowired
    private EventBus eventBus;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FailingSubscriber failingSubscriber;

    /** The alerts module's breach table is read directly here: a test must not import its repository. */
    @Autowired
    private JdbcTemplate jdbc;

    private Task createTask(final String title) {
        return taskService.create(new CreateTaskRequest(
                title, null, null, null, "store-001", null, "user-004", Instant.now().plusSeconds(3_600)));
    }

    private List<Notification> alertsFor(final String recipientId) {
        return notificationService.list(recipientId, null);
    }

    /** SLA breach alerts raised for {@code task-001}, the seed activity the sweep breaches. */
    private List<Notification> breachAlertsFor(final String recipientId) {
        return alertsFor(recipientId).stream()
                .filter(notification -> notification.alertType() == AlertType.SLA_BREACH)
                .filter(notification -> "task-001".equals(notification.sourceRef()))
                .toList();
    }

    @Test
    @DisplayName("blocking an activity reaches the alerts module, with no import between them")
    void blockedTaskReachesAlertsModule() {
        final Task task = createTask("Delivery check - blocked");
        final int before = alertsFor("user-004").size();

        taskService.update(task.id(), new UpdateTaskRequest(TaskStatus.BLOCKED, null, null));

        final List<Notification> after = alertsFor("user-004");
        assertThat(after).hasSize(before + 1);
        assertThat(after.getFirst().alertType()).isEqualTo(AlertType.ESCALATION);
        assertThat(after.getFirst().sourceRef()).isEqualTo(task.id());
    }

    @Test
    @DisplayName("a handover batch delivers one alert per activity it blocked, and none for the rest")
    void bulkHandoverReachesAlertsModule() {
        final Task blocked = createTask("Delivery check - batch blocked");
        final Task closed = createTask("Delivery check - batch closed");
        final int before = alertsFor("user-004").size();

        final BulkStatusUpdateResponse response = taskService.bulkUpdateStatus(new BulkStatusUpdateRequest(List.of(
                new BulkStatusUpdateItem(blocked.id(), TaskStatus.BLOCKED),
                new BulkStatusUpdateItem("task-does-not-exist", TaskStatus.BLOCKED),
                new BulkStatusUpdateItem(closed.id(), TaskStatus.DONE))));

        assertThat(response.succeeded()).hasSize(2);
        assertThat(response.failed()).hasSize(1);

        // One alert only: the DONE entry is not alertable and the unknown id never published.
        final List<Notification> after = alertsFor("user-004");
        assertThat(after).hasSize(before + 1);
        assertThat(after).anySatisfy(notification -> {
            assertThat(notification.sourceRef()).isEqualTo(blocked.id());
            assertThat(notification.alertType()).isEqualTo(AlertType.ESCALATION);
        });
    }

    @Test
    @DisplayName("a swept overdue activity alerts the department lead once, however many sweeps run")
    void sweptOverdueBreachReachesTheDepartmentLeadExactlyOnce() {
        // Seed task-001 is HIGH, still open and past its 2026-01-07 due date, so the sweep breaches
        // it. No integration test moves it, unlike task-002. Its assignee user-004 works in GROCERY at
        // store-001, whose department lead is user-003.
        taskService.publishOverdueBreaches();
        taskService.publishOverdueBreaches();

        // Filtered by sourceRef rather than counted: the H2 database is shared with the rest of the
        // suite. Exactly one alert across two sweeps is the point - de-duplication has to survive a
        // real transaction boundary and a real database, which no fake can prove.
        assertThat(breachAlertsFor("user-003")).hasSize(1);
        assertThat(breachAlertsFor("user-004")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM sla_breaches WHERE task_id = 'task-001'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a transition the alerts module does not care about raises nothing")
    void unremarkableTransitionRaisesNothing() {
        final Task task = createTask("Delivery check - in progress");
        final int before = alertsFor("user-004").size();

        taskService.update(task.id(), new UpdateTaskRequest(TaskStatus.IN_PROGRESS, null, null));

        assertThat(alertsFor("user-004")).hasSize(before);
    }

    @Test
    @DisplayName("closing a programme reaches the reports module")
    void closedProgrammeReachesReportsModule() {
        final Project project = projectService.create(new CreateProjectRequest(
                "Delivery check programme", null, "store-001", "region-north", "user-002"));
        final int before = reportService.findByScopeId("store-001").size();

        projectService.close(project.id(), "user-002");

        final List<Report> reports = reportService.findByScopeId("store-001");
        assertThat(reports).hasSize(before + 1);
        assertThat(reports.getFirst().reportType()).isEqualTo(ReportType.STORE_SUMMARY);
    }

    @Test
    @DisplayName("a rolled back transaction delivers nothing - the point of after-commit dispatch")
    void rollbackDeliversNothing() {
        final Task task = createTask("Delivery check - rollback");
        final int before = alertsFor("user-004").size();

        transactionTemplate.executeWithoutResult(status -> {
            eventBus.publish(new TaskStatusChangedEvent(
                    task.id(), "store-001", "TODO", "BLOCKED", "HIGH", "user-004", Instant.now()));
            status.setRollbackOnly();
        });

        assertThat(alertsFor("user-004")).hasSize(before);
    }

    @Test
    @DisplayName("publishing with no transaction delivers nothing, so publishers must be transactional")
    void publishingOutsideATransactionDeliversNothing() {
        final int before = alertsFor("user-004").size();

        eventBus.publish(new TaskStatusChangedEvent(
                "task-no-tx", "store-001", "TODO", "BLOCKED", "HIGH", "user-004", Instant.now()));

        assertThat(alertsFor("user-004")).hasSize(before);
    }

    @Test
    @DisplayName("a failing subscriber does not break the publisher")
    void failingSubscriberIsContained() {
        // FailingSubscriber throws on every ProbeEvent. The ErrorHandler configured in
        // EventBusConfiguration must absorb it; without that bean this call propagates.
        final int before = failingSubscriber.invocationCount();

        assertThatCode(() -> eventBus.publish(new FailingSubscriber.ProbeEvent(Instant.now())))
                .doesNotThrowAnyException();

        // Asserted so the test cannot pass merely because dispatch was broken.
        assertThat(failingSubscriber.invocationCount()).isEqualTo(before + 1);
    }
}
