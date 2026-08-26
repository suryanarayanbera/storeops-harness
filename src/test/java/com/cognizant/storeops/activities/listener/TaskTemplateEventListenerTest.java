package com.cognizant.storeops.activities.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.shared.events.ProgrammeTemplateRequestedEvent;
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
 * The activities module's inbound reaction to a template being applied. The handler is invoked
 * directly; that the annotations on it are correct is proved end to end in
 * {@code PlanogramTemplateDeliveryIntegrationTest}, which is the only place they can be.
 *
 * <p>A real {@code TaskService} over the repository fake rather than a mocked service, because the
 * behaviour worth testing here is what ends up in the table - a mock would only prove the listener
 * called a method.
 */
class TaskTemplateEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private FakeTaskRepository taskRepository;
    private RecordingEventBus eventBus;
    private TaskTemplateEventListener listener;

    @BeforeEach
    void setUp() {
        taskRepository = new FakeTaskRepository();
        eventBus = new RecordingEventBus();
        final UserService userService = mock(UserService.class);
        when(userService.exists("user-003")).thenReturn(true);
        listener = new TaskTemplateEventListener(new TaskService(
                taskRepository, userService, eventBus, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static ProgrammeTemplateRequestedEvent event(final TemplateTaskDefinition... items) {
        return new ProgrammeTemplateRequestedEvent("project-001", "store-001", "PLANOGRAM_STANDARD",
                "user-002", List.of(items), NOW);
    }

    private static TemplateTaskDefinition item(
            final String title, final String priority, final String assigneeId) {
        return new TemplateTaskDefinition(title, "standard planogram work", "PLANOGRAM", priority, assigneeId);
    }

    @Test
    @DisplayName("a template event raises one TODO PLANOGRAM activity per carried item")
    void templateEventRaisesOneActivityPerItem() {
        listener.onProgrammeTemplateRequested(event(
                item("Reset entrance promotional bay", "HIGH", "user-003"),
                item("Verify shelf-edge labelling", "MEDIUM", null)));

        final List<Task> created = taskRepository.findByProjectId("project-001");
        assertThat(created).hasSize(2);
        assertThat(created).extracting(Task::title)
                .containsExactlyInAnyOrder("Reset entrance promotional bay", "Verify shelf-edge labelling");
        assertThat(created).allSatisfy(task -> {
            assertThat(task.status()).isEqualTo(TaskStatus.TODO);
            assertThat(task.category()).isEqualTo(TaskCategory.PLANOGRAM);
            assertThat(task.storeId()).isEqualTo("store-001");
            assertThat(task.dueAt()).isNull();
            assertThat(task.createdAt()).isEqualTo(NOW);
        });
    }

    @Test
    @DisplayName("the listener publishes nothing of its own")
    void theListenerPublishesNothing() {
        listener.onProgrammeTemplateRequested(event(item("Reset entrance promotional bay", "HIGH", null)));

        // No TaskStatusChangedEvent: these activities are born TODO, so nothing transitioned. A
        // status event here would raise spurious alerts for work nobody has touched yet.
        assertThat(eventBus.published()).isEmpty();
    }

    @Test
    @DisplayName("an unrecognised priority becomes MEDIUM rather than an exception")
    void anUnrecognisedPriorityBecomesMedium() {
        assertThatCode(() -> listener.onProgrammeTemplateRequested(
                event(item("Reset entrance promotional bay", "URGENT", null))))
                .doesNotThrowAnyException();

        assertThat(taskRepository.findByProjectId("project-001")).singleElement()
                .extracting(Task::priority).isEqualTo(TaskPriority.MEDIUM);
    }

    @Test
    @DisplayName("a null priority becomes MEDIUM and a null category becomes GENERAL")
    void nullPriorityAndCategoryFallBack() {
        listener.onProgrammeTemplateRequested(event(
                new TemplateTaskDefinition("Reset entrance promotional bay", null, null, null, null)));

        assertThat(taskRepository.findByProjectId("project-001")).singleElement().satisfies(task -> {
            assertThat(task.priority()).isEqualTo(TaskPriority.MEDIUM);
            assertThat(task.category()).isEqualTo(TaskCategory.GENERAL);
        });
    }

    @Test
    @DisplayName("an unknown assignee is dropped and the activity is still created")
    void anUnknownAssigneeIsDropped() {
        listener.onProgrammeTemplateRequested(
                event(item("Reset entrance promotional bay", "HIGH", "user-999")));

        assertThat(taskRepository.findByProjectId("project-001")).singleElement().satisfies(task -> {
            assertThat(task.assigneeId()).isNull();
            assertThat(task.title()).isEqualTo("Reset entrance promotional bay");
        });
    }

    @Test
    @DisplayName("an event carrying no items writes nothing and throws nothing")
    void anEmptyEventIsANoOp() {
        assertThatCode(() -> listener.onProgrammeTemplateRequested(event()))
                .doesNotThrowAnyException();

        assertThat(taskRepository.count()).isZero();
    }

    @Test
    @DisplayName("a repeat delivery of the same event creates nothing the second time")
    void aRepeatDeliveryIsANoOp() {
        final ProgrammeTemplateRequestedEvent delivered =
                event(item("Reset entrance promotional bay", "HIGH", null));

        listener.onProgrammeTemplateRequested(delivered);
        listener.onProgrammeTemplateRequested(delivered);

        // At-least-once delivery must not double the work. The skip-by-title rule is what makes a
        // redelivery safe.
        assertThat(taskRepository.findByProjectId("project-001")).hasSize(1);
    }
}
