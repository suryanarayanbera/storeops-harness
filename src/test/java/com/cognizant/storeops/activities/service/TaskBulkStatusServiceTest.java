package com.cognizant.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.dto.BulkStatusFailure;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateItem;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateRequest;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateResponse;
import com.cognizant.storeops.activities.dto.TaskResponse;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeTaskRepository;
import com.cognizant.storeops.support.RecordingEventBus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Service-layer test for the shift handover batch.
 *
 * <p>Wired to a real {@link TaskService} rather than a mock of it, because what these tests are
 * about is the composition: which errors the batch absorbs per activity, and which events come out
 * the other side. A mocked {@code TaskService} would prove only that this class calls it.
 *
 * <p>The transaction-per-activity behaviour cannot be seen here - there is no transaction manager
 * in a plain JUnit test - so it is proved end to end in {@code BulkStatusUpdateIntegrationTest}.
 */
class TaskBulkStatusServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private FakeTaskRepository taskRepository;
    private RecordingEventBus eventBus;
    private TaskBulkStatusService bulkStatusService;

    @BeforeEach
    void setUp() {
        taskRepository = new FakeTaskRepository();
        eventBus = new RecordingEventBus();
        final TaskService taskService = new TaskService(
                taskRepository, mock(UserService.class), eventBus, Clock.fixed(NOW, ZoneOffset.UTC));
        bulkStatusService = new TaskBulkStatusService(taskService);
    }

    private Task seedTask(final String id, final TaskStatus status, final TaskPriority priority) {
        return taskRepository.save(new Task(id, "Seeded " + id, null, status, priority,
                TaskCategory.RESTOCKING, "store-001", "project-001", "user-004", null, NOW, NOW));
    }

    private static BulkStatusUpdateRequest batch(final Object... taskIdThenStatus) {
        final List<BulkStatusUpdateItem> items = new ArrayList<>();
        for (int index = 0; index < taskIdThenStatus.length; index += 2) {
            items.add(new BulkStatusUpdateItem(
                    (String) taskIdThenStatus[index], (TaskStatus) taskIdThenStatus[index + 1]));
        }
        return new BulkStatusUpdateRequest(items);
    }

    private List<TaskStatusChangedEvent> statusEvents() {
        return eventBus.published(TaskStatusChangedEvent.class);
    }

    private TaskStatus storedStatus(final String id) {
        return taskRepository.findById(id).orElseThrow().status();
    }

    @Test
    @DisplayName("a clean batch moves every listed activity and reports no failures")
    void cleanBatchUpdatesEveryActivity() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH);
        seedTask("task-002", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM);

        final BulkStatusUpdateResponse response = bulkStatusService.bulkUpdateStatus(
                batch("task-001", TaskStatus.DONE, "task-002", TaskStatus.BLOCKED));

        assertThat(response.failed()).isEmpty();
        assertThat(response.succeeded()).extracting(TaskResponse::id).containsExactly("task-001", "task-002");
        assertThat(response.succeeded()).extracting(TaskResponse::status).containsExactly("DONE", "BLOCKED");
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.DONE);
        assertThat(storedStatus("task-002")).isEqualTo(TaskStatus.BLOCKED);
    }

    @Test
    @DisplayName("an unknown id fails alone and leaves its neighbour updated")
    void unknownIdFailsAlone() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH);

        final BulkStatusUpdateResponse response = bulkStatusService.bulkUpdateStatus(
                batch("task-001", TaskStatus.DONE, "task-999", TaskStatus.DONE));

        assertThat(response.succeeded()).extracting(TaskResponse::id).containsExactly("task-001");
        assertThat(response.failed()).containsExactly(
                new BulkStatusFailure("task-999", "TASK_NOT_FOUND", "Task 'task-999' was not found", 404));
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.DONE);
    }

    @Test
    @DisplayName("a DONE activity is refused, and listing it first does not stop the rest of the batch")
    void terminalActivityFailsAloneEvenWhenListedFirst() {
        seedTask("task-003", TaskStatus.DONE, TaskPriority.CRITICAL);
        seedTask("task-002", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM);

        final BulkStatusUpdateResponse response = bulkStatusService.bulkUpdateStatus(
                batch("task-003", TaskStatus.BLOCKED, "task-002", TaskStatus.DONE));

        assertThat(response.failed()).singleElement().satisfies(failure -> {
            assertThat(failure.taskId()).isEqualTo("task-003");
            assertThat(failure.code()).isEqualTo("TASK_TRANSITION_NOT_ALLOWED");
            assertThat(failure.statusCode()).isEqualTo(409);
        });
        assertThat(response.succeeded()).extracting(TaskResponse::id).containsExactly("task-002");
        assertThat(storedStatus("task-003")).isEqualTo(TaskStatus.DONE);
        assertThat(storedStatus("task-002")).isEqualTo(TaskStatus.DONE);
    }

    @Test
    @DisplayName("asking a DONE activity for DONE again is refused as a forbidden transition")
    void repeatingDoneIsRefusedAsAForbiddenTransition() {
        seedTask("task-003", TaskStatus.DONE, TaskPriority.CRITICAL);

        final BulkStatusUpdateResponse response = bulkStatusService.bulkUpdateStatus(
                batch("task-003", TaskStatus.DONE));

        assertThat(response.succeeded()).isEmpty();
        assertThat(response.failed()).singleElement()
                .extracting(BulkStatusFailure::code).isEqualTo("TASK_TRANSITION_NOT_ALLOWED");
        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("a target status other than DONE or BLOCKED fails that activity only")
    void unsupportedTargetStatusFailsThatActivityOnly() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH);
        seedTask("task-002", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM);

        final BulkStatusUpdateResponse response = bulkStatusService.bulkUpdateStatus(
                batch("task-001", TaskStatus.IN_PROGRESS, "task-002", TaskStatus.DONE));

        assertThat(response.failed()).singleElement().satisfies(failure -> {
            assertThat(failure.taskId()).isEqualTo("task-001");
            assertThat(failure.code()).isEqualTo("VALIDATION_FAILED");
            assertThat(failure.statusCode()).isEqualTo(400);
        });
        assertThat(response.succeeded()).extracting(TaskResponse::id).containsExactly("task-002");
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.TODO);
    }

    @Test
    @DisplayName("TODO is refused as a handover target just as IN_PROGRESS is")
    void todoIsAlsoAnUnsupportedTarget() {
        seedTask("task-001", TaskStatus.IN_PROGRESS, TaskPriority.HIGH);

        final BulkStatusUpdateResponse response = bulkStatusService.bulkUpdateStatus(
                batch("task-001", TaskStatus.TODO));

        assertThat(response.failed()).singleElement()
                .extracting(BulkStatusFailure::code).isEqualTo("VALIDATION_FAILED");
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("an activity already BLOCKED is reported as unchanged rather than as a silent success")
    void noOpTransitionIsReportedAsUnchanged() {
        final Task before = seedTask("task-004", TaskStatus.BLOCKED, TaskPriority.LOW);

        final BulkStatusUpdateResponse response = bulkStatusService.bulkUpdateStatus(
                batch("task-004", TaskStatus.BLOCKED));

        assertThat(response.succeeded()).isEmpty();
        assertThat(response.failed()).singleElement().satisfies(failure -> {
            assertThat(failure.taskId()).isEqualTo("task-004");
            assertThat(failure.code()).isEqualTo("TASK_STATUS_UNCHANGED");
            assertThat(failure.statusCode()).isEqualTo(409);
        });
        assertThat(taskRepository.findById("task-004")).contains(before);
        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("a batch in which everything fails still returns a result, not an error")
    void batchWhereEverythingFailsStillReturnsAResult() {
        seedTask("task-003", TaskStatus.DONE, TaskPriority.CRITICAL);

        final BulkStatusUpdateResponse response = bulkStatusService.bulkUpdateStatus(
                batch("task-003", TaskStatus.DONE, "task-999", TaskStatus.BLOCKED));

        assertThat(response.succeeded()).isEmpty();
        assertThat(response.failed()).extracting(BulkStatusFailure::taskId)
                .containsExactly("task-003", "task-999");
        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("naming the same activity twice rejects the whole batch before anything is written")
    void duplicateTaskIdsRejectTheWholeBatch() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH);

        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> bulkStatusService.bulkUpdateStatus(
                        batch("task-001", TaskStatus.DONE, "task-001", TaskStatus.BLOCKED)))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.getStatusCode()).isEqualTo(400);
                    assertThat(error.getDetails()).containsExactly("updates: duplicate taskId 'task-001'");
                });
        assertThat(storedStatus("task-001")).isEqualTo(TaskStatus.TODO);
        assertThat(statusEvents()).isEmpty();
    }

    @Test
    @DisplayName("each successful activity publishes exactly one status change and each failure publishes none")
    void publishesOneEventPerSuccessAndNoneForFailures() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH);

        bulkStatusService.bulkUpdateStatus(batch("task-001", TaskStatus.DONE, "task-999", TaskStatus.DONE));

        assertThat(statusEvents()).hasSize(1);
        final TaskStatusChangedEvent event = statusEvents().getFirst();
        assertThat(event.taskId()).isEqualTo("task-001");
        assertThat(event.storeId()).isEqualTo("store-001");
        assertThat(event.previousStatus()).isEqualTo("TODO");
        assertThat(event.newStatus()).isEqualTo("DONE");
        assertThat(event.priority()).isEqualTo("HIGH");
        assertThat(event.assigneeId()).isEqualTo("user-004");
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.eventType()).isEqualTo("TASK_STATUS_CHANGED");
    }

    @Test
    @DisplayName("event payloads carry statuses as Strings, so no activities type crosses the bus")
    void eventPayloadCarriesEnumsAsStrings() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.CRITICAL);

        bulkStatusService.bulkUpdateStatus(batch("task-001", TaskStatus.BLOCKED));

        final TaskStatusChangedEvent event = statusEvents().getFirst();
        assertThat(event.previousStatus()).isInstanceOf(String.class);
        assertThat(event.newStatus()).isInstanceOf(String.class);
        assertThat(event.priority()).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("a three-activity batch publishes one event per activity that actually moved")
    void publishesOneEventPerMovedActivity() {
        seedTask("task-001", TaskStatus.TODO, TaskPriority.HIGH);
        seedTask("task-002", TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM);
        seedTask("task-004", TaskStatus.BLOCKED, TaskPriority.LOW);

        bulkStatusService.bulkUpdateStatus(batch(
                "task-001", TaskStatus.BLOCKED,
                "task-004", TaskStatus.BLOCKED,
                "task-002", TaskStatus.DONE));

        assertThat(statusEvents()).extracting(TaskStatusChangedEvent::taskId)
                .containsExactly("task-001", "task-002");
    }
}
