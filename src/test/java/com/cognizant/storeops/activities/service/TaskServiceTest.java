package com.cognizant.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateFailure;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateItem;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateRequest;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateResponse;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateSuccess;
import com.cognizant.storeops.activities.dto.CreateTaskRequest;
import com.cognizant.storeops.activities.dto.UpdateTaskRequest;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeTaskRepository;
import com.cognizant.storeops.support.RecordingEventBus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Service-layer test for the activities module.
 *
 * <p>Uses a repository test double and the real event bus - only the cross-module collaborator
 * ({@code UserService}) is a mock, because the point of these tests is this module's rules, not the
 * staff module's or Hibernate's. The clock is fixed so timestamps are assertable. The JPA mapping
 * is covered separately by the {@code @SpringBootTest} integration tests.
 */
class TaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    /** Seed due date of {@code task-001}: past, so the sweep sees it as breached. */
    private static final Instant DUE_TASK_001 = Instant.parse("2026-01-07T08:00:00Z");

    private FakeTaskRepository taskRepository;
    private UserService userService;
    private RecordingEventBus eventBus;
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        taskRepository = new FakeTaskRepository();
        userService = mock(UserService.class);
        eventBus = new RecordingEventBus();
        taskService = new TaskService(taskRepository, userService, eventBus,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(userService.exists("user-004")).thenReturn(true);
    }

    private List<TaskStatusChangedEvent> statusEvents() {
        return eventBus.published(TaskStatusChangedEvent.class);
    }

    private List<TaskOverdueEvent> overdueEvents() {
        return eventBus.published(TaskOverdueEvent.class);
    }

    private CreateTaskRequest createRequest() {
        return new CreateTaskRequest("Restock aisle 4", "Weekend overflow", null, null,
                "store-001", "project-001", "user-004", NOW.plusSeconds(3_600));
    }

    private Task seedTask(final String id, final TaskStatus status, final TaskPriority priority, final Instant dueAt) {
        return seedTask(id, status, priority, dueAt, "user-004");
    }

    private Task seedTask(final String id, final TaskStatus status, final TaskPriority priority,
            final Instant dueAt, final String assigneeId) {
        return taskRepository.save(new Task(id, "Seeded " + id, null, status, priority,
                TaskCategory.RESTOCKING, "store-001", "project-001", assigneeId, dueAt, NOW, NOW));
    }

    private TaskStatus storedStatus(final String id) {
        return taskRepository.findById(id).orElseThrow().status();
    }

    private static BulkStatusUpdateRequest handover(final BulkStatusUpdateItem... entries) {
        return new BulkStatusUpdateRequest(List.of(entries));
    }

    @Test
    @DisplayName("create defaults status to TODO, priority to MEDIUM and category to GENERAL")
    void createAppliesDefaults() {
        final Task created = taskService.create(createRequest());

        assertThat(created.status()).isEqualTo(TaskStatus.TODO);
        assertThat(created.priority()).isEqualTo(TaskPriority.MEDIUM);
        assertThat(created.category()).isEqualTo(TaskCategory.GENERAL);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(created.updatedAt()).isEqualTo(NOW);
        assertThat(created.id()).isNotBlank();
        assertThat(taskRepository.findById(created.id())).contains(created);
    }

    @Test
    @DisplayName("create trims the title")
    void createTrimsTitle() {
        final CreateTaskRequest request = new CreateTaskRequest("  Padded title  ", null, null, null,
                "store-001", null, null, null);

        assertThat(taskService.create(request).title()).isEqualTo("Padded title");
    }

    @Test
    @DisplayName("create rejects an assignee the staff module does not know")
    void createRejectsUnknownAssignee() {
        final CreateTaskRequest request = new CreateTaskRequest("Restock", null, null, null,
                "store-001", null, "user-999", null);

        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> taskService.create(request))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.getStatusCode()).isEqualTo(400);
                    assertThat(error.getDetails()).hasSize(1);
                });
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("create publishes nothing - creation is not a status change")
    void createPublishesNothing() {
        taskService.create(createRequest());

        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("getById raises a typed NotFoundError for an unknown id")
    void getByIdRaisesTypedNotFound() {
        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> taskService.getById("nope"))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("TASK_NOT_FOUND");
                    assertThat(error.getStatusCode()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("update rejects a payload that would change nothing")
    void updateRejectsEmptyPayload() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, null);

        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> taskService.update("task-001", new UpdateTaskRequest(null, null, null)));
    }

    @Test
    @DisplayName("update publishes exactly one TaskStatusChangedEvent carrying both statuses")
    void updatePublishesStatusChange() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, null);

        final Task updated = taskService.update("task-001",
                new UpdateTaskRequest(TaskStatus.BLOCKED, null, null));

        assertThat(updated.status()).isEqualTo(TaskStatus.BLOCKED);
        assertThat(updated.updatedAt()).isEqualTo(NOW);
        assertThat(statusEvents()).hasSize(1);
        assertThat(statusEvents().getFirst().previousStatus()).isEqualTo("TODO");
        assertThat(statusEvents().getFirst().newStatus()).isEqualTo("BLOCKED");
        assertThat(statusEvents().getFirst().taskId()).isEqualTo("task-001");
        assertThat(statusEvents().getFirst().storeId()).isEqualTo("store-001");
        assertThat(statusEvents().getFirst().eventType()).isEqualTo("TASK_STATUS_CHANGED");
    }

    @Test
    @DisplayName("update publishes no event when only the priority changes")
    void updateWithoutStatusChangePublishesNothing() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.LOW, null);

        final Task updated = taskService.update("task-001",
                new UpdateTaskRequest(null, TaskPriority.CRITICAL, null));

        assertThat(updated.priority()).isEqualTo(TaskPriority.CRITICAL);
        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("update publishes no event when the requested status is the current one")
    void updateToSameStatusPublishesNothing() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.LOW, null);

        taskService.update("task-001", new UpdateTaskRequest(TaskStatus.TODO, TaskPriority.HIGH, null));

        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("update refuses to reopen a DONE activity")
    void updateRefusesToReopenDoneTask() {
        seedTask("task-003", TaskStatus.DONE, TaskPriority.CRITICAL, null);

        assertThatExceptionOfType(ConflictError.class)
                .isThrownBy(() -> taskService.update("task-003", new UpdateTaskRequest(TaskStatus.TODO, null, null)))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("TASK_TRANSITION_NOT_ALLOWED");
                    assertThat(error.getStatusCode()).isEqualTo(409);
                });
        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("update rejects reassignment to an unknown staff member")
    void updateRejectsUnknownAssignee() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.LOW, null);

        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> taskService.update("task-001",
                        new UpdateTaskRequest(null, null, "user-999")));
    }

    @Test
    @DisplayName("the sweep publishes only for SLA-tracked activities that are past due and unfinished")
    void publishOverdueBreachesFiltersByPriorityAndStatus() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, DUE_TASK_001);
        seedTask("task-002", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, Instant.parse("2026-01-08T08:00:00Z"));
        seedTask("task-003", TaskStatus.DONE, TaskPriority.CRITICAL, Instant.parse("2026-01-06T09:00:00Z"));
        seedTask("task-004", TaskStatus.BLOCKED, TaskPriority.LOW, null);

        final int published = taskService.publishOverdueBreaches();

        assertThat(published).isEqualTo(1);
        assertThat(overdueEvents()).singleElement().satisfies(event -> {
            assertThat(event.taskId()).isEqualTo("task-001");
            assertThat(event.storeId()).isEqualTo("store-001");
            assertThat(event.priority()).isEqualTo("HIGH");
            assertThat(event.assigneeId()).isEqualTo("user-004");
            assertThat(event.dueAt()).isEqualTo(DUE_TASK_001);
            assertThat(event.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("the sweep ignores an activity that is not yet past its due date")
    void publishOverdueBreachesIgnoresActivitiesNotYetDue() {
        seedTask("task-005", TaskStatus.TODO, TaskPriority.CRITICAL, Instant.parse("2026-02-01T18:00:00Z"));

        assertThat(taskService.publishOverdueBreaches()).isZero();
        assertThat(overdueEvents()).isEmpty();
    }

    @Test
    @DisplayName("the sweep republishes on every pass while an activity stays overdue")
    void publishOverdueBreachesRepublishesWhileStillOverdue() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, DUE_TASK_001);

        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);

        // The repetition is the contract, not a defect: the repeat event is how the alerts module
        // learns a breach is still unresolved, so activities keeps no record of what it announced.
        assertThat(overdueEvents()).extracting(TaskOverdueEvent::taskId)
                .containsExactly("task-001", "task-001", "task-001");
    }

    @Test
    @DisplayName("list applies the supplied criteria and ignores the null ones")
    void listAppliesCriteria() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, null);
        seedTask("task-002", TaskStatus.BLOCKED, TaskPriority.LOW, null);

        assertThat(taskService.list(null, null, null, null)).hasSize(2);
        assertThat(taskService.list(TaskStatus.BLOCKED, null, null, null))
                .extracting(Task::id).containsExactly("task-002");
        assertThat(taskService.list(null, null, null, "store-999")).isEmpty();
    }

    @Test
    @DisplayName("a handover batch marks every listed activity and publishes one event each")
    void bulkUpdateAppliesEveryEntry() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, null, "user-004");
        seedTask("task-002", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, null, "user-003");

        final BulkStatusUpdateResponse response = taskService.bulkUpdateStatus(handover(
                new BulkStatusUpdateItem("task-001", TaskStatus.DONE),
                new BulkStatusUpdateItem("task-002", TaskStatus.BLOCKED)));

        assertThat(response.failed()).isEmpty();
        assertThat(response.succeeded()).containsExactly(
                new BulkStatusUpdateSuccess("task-001", "TODO", "DONE", true),
                new BulkStatusUpdateSuccess("task-002", "IN_PROGRESS", "BLOCKED", true));
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.DONE);
        assertThat(storedStatus("task-002")).isEqualTo(TaskStatus.BLOCKED);
        assertThat(statusEvents()).containsExactly(
                new TaskStatusChangedEvent("task-001", "store-001", "TODO", "DONE", "HIGH", "user-004", NOW),
                new TaskStatusChangedEvent("task-002", "store-001", "IN_PROGRESS", "BLOCKED", "MEDIUM", "user-003", NOW));
    }

    @Test
    @DisplayName("a handover batch fails an unknown id without touching the rest")
    void bulkUpdateIsolatesAnUnknownId() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, null);

        final BulkStatusUpdateResponse response = taskService.bulkUpdateStatus(handover(
                new BulkStatusUpdateItem("task-999", TaskStatus.DONE),
                new BulkStatusUpdateItem("task-001", TaskStatus.DONE)));

        assertThat(response.failed()).containsExactly(
                new BulkStatusUpdateFailure("task-999", "TASK_NOT_FOUND", "Task 'task-999' was not found", 404));
        assertThat(response.succeeded()).containsExactly(
                new BulkStatusUpdateSuccess("task-001", "TODO", "DONE", true));
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.DONE);
        assertThat(statusEvents()).hasSize(1);
        assertThat(statusEvents().getFirst().taskId()).isEqualTo("task-001");
    }

    @Test
    @DisplayName("a handover batch fails a refused transition without touching the rest")
    void bulkUpdateIsolatesARefusedTransition() {
        final Task done = seedTask("task-003", TaskStatus.DONE, TaskPriority.CRITICAL, null);
        seedTask("task-002", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, null);

        final BulkStatusUpdateResponse response = taskService.bulkUpdateStatus(handover(
                new BulkStatusUpdateItem("task-003", TaskStatus.BLOCKED),
                new BulkStatusUpdateItem("task-002", TaskStatus.DONE)));

        assertThat(response.failed()).singleElement().satisfies(failure -> {
            assertThat(failure.taskId()).isEqualTo("task-003");
            assertThat(failure.code()).isEqualTo("TASK_TRANSITION_NOT_ALLOWED");
            assertThat(failure.statusCode()).isEqualTo(409);
        });
        assertThat(response.succeeded()).containsExactly(
                new BulkStatusUpdateSuccess("task-002", "IN_PROGRESS", "DONE", true));
        // Record equality: nothing at all was written for the refused entry, updatedAt included.
        assertThat(taskRepository.findById("task-003")).contains(done);
        assertThat(statusEvents()).hasSize(1);
        assertThat(statusEvents().getFirst().taskId()).isEqualTo("task-002");
    }

    @Test
    @DisplayName("a handover batch refuses a status other than DONE or BLOCKED, entry by entry")
    void bulkUpdateIsolatesAStatusOutsideTheHandoverPair() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, null);
        seedTask("task-002", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, null);

        final BulkStatusUpdateResponse response = taskService.bulkUpdateStatus(handover(
                new BulkStatusUpdateItem("task-001", TaskStatus.IN_PROGRESS),
                new BulkStatusUpdateItem("task-002", TaskStatus.DONE)));

        assertThat(response.failed()).singleElement().satisfies(failure -> {
            assertThat(failure.taskId()).isEqualTo("task-001");
            assertThat(failure.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(failure.statusCode()).isEqualTo(400);
        });
        assertThat(response.succeeded()).containsExactly(
                new BulkStatusUpdateSuccess("task-002", "IN_PROGRESS", "DONE", true));
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.TODO);
        assertThat(statusEvents()).hasSize(1);
        assertThat(statusEvents().getFirst().taskId()).isEqualTo("task-002");
    }

    @Test
    @DisplayName("a handover batch reports an activity already in the requested status, and publishes nothing")
    void bulkUpdateReportsAnAlreadySettledActivity() {
        final Task done = seedTask("task-003", TaskStatus.DONE, TaskPriority.CRITICAL, null);

        final BulkStatusUpdateResponse response = taskService.bulkUpdateStatus(
                handover(new BulkStatusUpdateItem("task-003", TaskStatus.DONE)));

        assertThat(response.failed()).isEmpty();
        assertThat(response.succeeded()).containsExactly(
                new BulkStatusUpdateSuccess("task-003", "DONE", "DONE", false));
        assertThat(taskRepository.findById("task-003")).contains(done);
        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("a handover batch applies a repeated id once and fails the repeat")
    void bulkUpdateAppliesARepeatedIdOnce() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH, null);

        final BulkStatusUpdateResponse response = taskService.bulkUpdateStatus(handover(
                new BulkStatusUpdateItem("task-001", TaskStatus.DONE),
                new BulkStatusUpdateItem("task-001", TaskStatus.BLOCKED)));

        assertThat(response.succeeded()).containsExactly(
                new BulkStatusUpdateSuccess("task-001", "TODO", "DONE", true));
        assertThat(response.failed()).singleElement().satisfies(failure -> {
            assertThat(failure.taskId()).isEqualTo("task-001");
            assertThat(failure.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(failure.statusCode()).isEqualTo(400);
        });
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.DONE);
        assertThat(statusEvents()).hasSize(1);
    }
}
