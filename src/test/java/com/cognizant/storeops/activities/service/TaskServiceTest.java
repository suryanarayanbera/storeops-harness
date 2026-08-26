package com.cognizant.storeops.activities.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.dto.CreateTaskRequest;
import com.cognizant.storeops.activities.dto.UpdateTaskRequest;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.shared.events.TemplateTaskDefinition;
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
        return taskRepository.save(new Task(id, "Seeded " + id, null, status, priority,
                TaskCategory.RESTOCKING, "store-001", "project-001", "user-004", dueAt, NOW, NOW));
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
    @DisplayName("publishOverdueBreaches emits only for HIGH and CRITICAL activities past due")
    void publishOverdueBreachesFiltersByPriority() {
        final Instant past = NOW.minusSeconds(3_600);
        seedTask("task-high", TaskStatus.TODO, TaskPriority.HIGH, past);
        seedTask("task-critical", TaskStatus.IN_PROGRESS, TaskPriority.CRITICAL, past);
        seedTask("task-low", TaskStatus.TODO, TaskPriority.LOW, past);
        seedTask("task-done", TaskStatus.DONE, TaskPriority.CRITICAL, past);
        seedTask("task-future", TaskStatus.TODO, TaskPriority.HIGH, NOW.plusSeconds(3_600));

        final int published = taskService.publishOverdueBreaches();

        assertThat(published).isEqualTo(2);
        assertThat(overdueEvents()).extracting(TaskOverdueEvent::taskId)
                .containsExactlyInAnyOrder("task-high", "task-critical");
    }

    /**
     * The four activities from {@code data.sql}, with their real stores, assignees and due dates.
     *
     * <p>Reproduced here rather than approximated because the sweep's behaviour against the shipped
     * seed is what the integration tests and the curl examples will show: exactly one breach.
     */
    private void seedTheFourSeedActivities() {
        taskRepository.save(new Task("task-001", "Restock aisle 4 beverages", "Weekend promotion overflow",
                TaskStatus.TODO, TaskPriority.HIGH, TaskCategory.RESTOCKING, "store-001", "project-001",
                "user-004", Instant.parse("2026-01-07T08:00:00Z"), NOW, NOW));
        taskRepository.save(new Task("task-002", "Reset seasonal planogram bay 12", "Spring layout rollout",
                TaskStatus.IN_PROGRESS, TaskPriority.MEDIUM, TaskCategory.PLANOGRAM, "store-001", "project-001",
                "user-003", Instant.parse("2026-01-08T08:00:00Z"), NOW, NOW));
        taskRepository.save(new Task("task-003", "Chilled temperature compliance check", "Twice-daily log",
                TaskStatus.DONE, TaskPriority.CRITICAL, TaskCategory.COMPLIANCE, "store-001", null,
                "user-003", Instant.parse("2026-01-06T09:00:00Z"), NOW, NOW));
        taskRepository.save(new Task("task-004", "Stockroom cage audit", "Quarterly shrinkage audit",
                TaskStatus.BLOCKED, TaskPriority.LOW, TaskCategory.AUDIT, "store-002", "project-002",
                "user-005", null, NOW, NOW));
    }

    @Test
    @DisplayName("the sweep publishes one fully populated TaskOverdueEvent for the single seed breach")
    void publishOverdueBreachesPopulatesTheEventFromTheSeedData() {
        seedTheFourSeedActivities();

        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);

        assertThat(overdueEvents()).hasSize(1);
        final TaskOverdueEvent event = overdueEvents().getFirst();
        assertThat(event.taskId()).isEqualTo("task-001");
        assertThat(event.storeId()).isEqualTo("store-001");
        assertThat(event.priority()).isEqualTo("HIGH");
        assertThat(event.assigneeId()).isEqualTo("user-004");
        assertThat(event.dueAt()).isEqualTo(Instant.parse("2026-01-07T08:00:00Z"));
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.eventType()).isEqualTo("TASK_OVERDUE");
    }

    @Test
    @DisplayName("the sweep skips MEDIUM priority, DONE activities and activities with no due date")
    void publishOverdueBreachesSkipsEveryNonBreach() {
        seedTheFourSeedActivities();

        taskService.publishOverdueBreaches();

        // Asserted one exclusion at a time, so a failure names which filter broke rather than only
        // reporting that the count moved.
        assertThat(overdueEvents()).extracting(TaskOverdueEvent::taskId).doesNotContain("task-002");
        assertThat(overdueEvents()).extracting(TaskOverdueEvent::taskId).doesNotContain("task-003");
        assertThat(overdueEvents()).extracting(TaskOverdueEvent::taskId).doesNotContain("task-004");
    }

    @Test
    @DisplayName("the sweep treats an activity due in the future as no breach at all")
    void publishOverdueBreachesIgnoresActivitiesNotYetDue() {
        seedTask("task-future", TaskStatus.TODO, TaskPriority.CRITICAL, Instant.parse("2026-03-01T00:00:00Z"));

        assertThat(taskService.publishOverdueBreaches()).isZero();
        assertThat(overdueEvents()).isEmpty();
    }

    @Test
    @DisplayName("the sweep re-publishes on every cycle, because the alerts module de-duplicates")
    void publishOverdueBreachesRepublishesOnEveryCycle() {
        seedTheFourSeedActivities();

        // Not a bug. Re-publication is how the alerts module learns an activity is *still*
        // unresolved, which is what its grace-period escalation is built on. Suppressing the
        // repeats is the subscriber's job, added in Sprint 4.
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);

        assertThat(overdueEvents()).hasSize(3);
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

    // -------------------------------------------------------------- createFromTemplate

    private static TemplateTaskDefinition item(final String title, final String priority, final String assigneeId) {
        return new TemplateTaskDefinition(title, "from the standard planogram set", "PLANOGRAM",
                priority, assigneeId);
    }

    private List<Task> applyTwoItems() {
        return taskService.createFromTemplate("project-001", "store-001", List.of(
                item("Reset entrance promotional bay", "HIGH", "user-004"),
                item("Verify shelf-edge labelling", "MEDIUM", null)));
    }

    @Test
    @DisplayName("createFromTemplate raises each carried item as a TODO PLANOGRAM activity")
    void createFromTemplateRaisesEachItem() {
        final List<Task> created = applyTwoItems();

        assertThat(created).hasSize(2);
        assertThat(created).extracting(Task::title)
                .containsExactly("Reset entrance promotional bay", "Verify shelf-edge labelling");
        assertThat(created).allSatisfy(task -> {
            assertThat(task.status()).isEqualTo(TaskStatus.TODO);
            assertThat(task.category()).isEqualTo(TaskCategory.PLANOGRAM);
            assertThat(task.projectId()).isEqualTo("project-001");
            assertThat(task.storeId()).isEqualTo("store-001");
            assertThat(task.dueAt()).isNull();
            assertThat(task.createdAt()).isEqualTo(NOW);
            assertThat(task.updatedAt()).isEqualTo(NOW);
            assertThat(task.id()).isNotBlank();
        });
        assertThat(created).extracting(Task::priority)
                .containsExactly(TaskPriority.HIGH, TaskPriority.MEDIUM);
        assertThat(created).extracting(Task::assigneeId).containsExactly("user-004", null);
        // Persisted, not merely returned.
        assertThat(taskRepository.findByProjectId("project-001")).hasSize(2);
        assertThat(created).extracting(Task::id).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("createFromTemplate publishes nothing: the activities are born TODO, nothing transitioned")
    void createFromTemplatePublishesNothing() {
        applyTwoItems();

        // The whole event bus, not just TaskStatusChangedEvent - this path should be silent.
        assertThat(eventBus.published()).isEmpty();
    }

    @Test
    @DisplayName("createFromTemplate skips an item whose title is already on the programme")
    void createFromTemplateSkipsTitlesAlreadyPresent() {
        taskRepository.save(new Task("task-existing", "Verify shelf-edge labelling", "raised by hand",
                TaskStatus.IN_PROGRESS, TaskPriority.LOW, TaskCategory.GENERAL, "store-001",
                "project-001", "user-004", null, NOW, NOW));

        final List<Task> created = applyTwoItems();

        assertThat(created).extracting(Task::title).containsExactly("Reset entrance promotional bay");
        assertThat(taskRepository.findByProjectId("project-001")).hasSize(2);
        // The clash was with a GENERAL activity, so the skip is not category-scoped, and the existing
        // row is left exactly as it was.
        final Task untouched = taskRepository.findById("task-existing").orElseThrow();
        assertThat(untouched.category()).isEqualTo(TaskCategory.GENERAL);
        assertThat(untouched.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(untouched.updatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("createFromTemplate compares titles ignoring case and surrounding whitespace")
    void createFromTemplateComparesTitlesLoosely() {
        taskRepository.save(new Task("task-existing", "  verify SHELF-EDGE labelling  ", null,
                TaskStatus.TODO, TaskPriority.LOW, TaskCategory.PLANOGRAM, "store-001",
                "project-001", null, null, NOW, NOW));

        assertThat(applyTwoItems()).extracting(Task::title)
                .containsExactly("Reset entrance promotional bay");
    }

    @Test
    @DisplayName("createFromTemplate is idempotent: a second identical call creates nothing")
    void createFromTemplateIsIdempotent() {
        assertThat(applyTwoItems()).hasSize(2);
        assertThat(applyTwoItems()).isEmpty();

        assertThat(taskRepository.findByProjectId("project-001")).hasSize(2);
    }

    @Test
    @DisplayName("createFromTemplate ignores activities on a different programme when skipping")
    void createFromTemplateScopesTheSkipToTheProgramme() {
        taskRepository.save(new Task("task-elsewhere", "Verify shelf-edge labelling", null,
                TaskStatus.TODO, TaskPriority.LOW, TaskCategory.PLANOGRAM, "store-002",
                "project-002", null, null, NOW, NOW));

        // Same title, different programme, so it is not a clash here.
        assertThat(applyTwoItems()).hasSize(2);
    }

    @Test
    @DisplayName("createFromTemplate creates a duplicated title within one batch only once")
    void createFromTemplateDeduplicatesWithinTheBatch() {
        final List<Task> created = taskService.createFromTemplate("project-001", "store-001", List.of(
                item("Reset entrance promotional bay", "HIGH", null),
                item("reset entrance promotional bay", "LOW", null)));

        assertThat(created).hasSize(1);
        assertThat(created.getFirst().priority()).isEqualTo(TaskPriority.HIGH);
    }

    @Test
    @DisplayName("createFromTemplate falls back to MEDIUM for a priority it does not recognise")
    void createFromTemplateFallsBackToMediumPriority() {
        final List<Task> created = taskService.createFromTemplate("project-001", "store-001", List.of(
                item("Reset entrance promotional bay", "URGENT", null),
                item("Verify shelf-edge labelling", null, null),
                item("Photograph completed bays for compliance", "   ", null)));

        // Degrades rather than throwing: an exception here is swallowed after commit and would cost
        // the whole batch for one bad field.
        assertThat(created).extracting(Task::priority)
                .containsExactly(TaskPriority.MEDIUM, TaskPriority.MEDIUM, TaskPriority.MEDIUM);
    }

    @Test
    @DisplayName("createFromTemplate matches a priority name whatever its case")
    void createFromTemplateMatchesPriorityIgnoringCase() {
        final List<Task> created = taskService.createFromTemplate("project-001", "store-001",
                List.of(item("Reset entrance promotional bay", " critical ", null)));

        assertThat(created).singleElement()
                .extracting(Task::priority).isEqualTo(TaskPriority.CRITICAL);
    }

    @Test
    @DisplayName("createFromTemplate falls back to GENERAL for a category it does not recognise")
    void createFromTemplateFallsBackToGeneralCategory() {
        final List<Task> created = taskService.createFromTemplate("project-001", "store-001", List.of(
                new TemplateTaskDefinition("Reset entrance promotional bay", null, "SHELF_ART", "LOW", null),
                new TemplateTaskDefinition("Verify shelf-edge labelling", null, null, "LOW", null)));

        assertThat(created).extracting(Task::category)
                .containsExactly(TaskCategory.GENERAL, TaskCategory.GENERAL);
    }

    @Test
    @DisplayName("createFromTemplate drops an assignee the staff module does not know, keeping the activity")
    void createFromTemplateDropsUnknownAssignee() {
        final List<Task> created = taskService.createFromTemplate("project-001", "store-001",
                List.of(item("Reset entrance promotional bay", "HIGH", "user-999")));

        // Not the ValidationError create() raises for the same input: there is no caller to tell, and
        // refusing would lose the work as well as the assignment.
        assertThat(created).singleElement().satisfies(task -> {
            assertThat(task.assigneeId()).isNull();
            assertThat(task.title()).isEqualTo("Reset entrance promotional bay");
        });
    }

    @Test
    @DisplayName("createFromTemplate skips an item with no usable title")
    void createFromTemplateSkipsUntitledItems() {
        final List<Task> created = taskService.createFromTemplate("project-001", "store-001", List.of(
                item(null, "HIGH", null),
                item("   ", "HIGH", null),
                item("Reset entrance promotional bay", "HIGH", null)));

        assertThat(created).extracting(Task::title).containsExactly("Reset entrance promotional bay");
    }

    @Test
    @DisplayName("createFromTemplate trims the title it stores")
    void createFromTemplateTrimsTheStoredTitle() {
        final List<Task> created = taskService.createFromTemplate("project-001", "store-001",
                List.of(item("  Reset entrance promotional bay  ", "HIGH", null)));

        assertThat(created).singleElement()
                .extracting(Task::title).isEqualTo("Reset entrance promotional bay");
    }

    @Test
    @DisplayName("createFromTemplate writes nothing for an empty or null item list")
    void createFromTemplateHandlesNoItems() {
        assertThat(taskService.createFromTemplate("project-001", "store-001", List.of())).isEmpty();
        assertThat(taskService.createFromTemplate("project-001", "store-001", null)).isEmpty();

        assertThat(taskRepository.count()).isZero();
        assertThat(eventBus.published()).isEmpty();
    }
}
