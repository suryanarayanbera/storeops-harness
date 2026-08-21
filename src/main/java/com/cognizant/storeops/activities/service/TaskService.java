package com.cognizant.storeops.activities.service;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.dto.CreateTaskRequest;
import com.cognizant.storeops.activities.dto.UpdateTaskRequest;
import com.cognizant.storeops.activities.repository.TaskRepository;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business logic for operational activities.
 *
 * <p>Two module boundary rules are visible in this class:
 *
 * <ul>
 *   <li>It reads staff data through {@link UserService} - a service-layer read-only lookup, which
 *       is the sanctioned form of cross-module read.
 *   <li>It never imports the alerts or reports modules. When a status change should raise an alert
 *       or feed a report, it publishes on the {@link EventBus} and stops caring.
 * </ul>
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final UserService userService;
    private final EventBus eventBus;
    private final Clock clock;

    public TaskService(
            final TaskRepository taskRepository,
            final UserService userService,
            final EventBus eventBus,
            final Clock clock) {
        this.taskRepository = taskRepository;
        this.userService = userService;
        this.eventBus = eventBus;
        this.clock = clock;
    }

    /** Endpoint 1 backing call. Null criteria are ignored. */
    public List<Task> list(
            final TaskStatus status,
            final TaskPriority priority,
            final TaskCategory category,
            final String storeId) {
        return taskRepository.search(status, priority, category, storeId);
    }

    /**
     * Loads one activity.
     *
     * @throws NotFoundError when no activity has that id
     */
    public Task getById(final String id) {
        return taskRepository.findById(id).orElseThrow(() -> NotFoundError.of("Task", id));
    }

    /**
     * Creates an activity. Unset priority and category fall back to MEDIUM and GENERAL.
     *
     * @throws ValidationError when the named assignee is not a known staff member
     */
    public Task create(final CreateTaskRequest request) {
        if (request.assigneeId() != null && !userService.exists(request.assigneeId())) {
            throw new ValidationError(
                    "Assignee is not a known staff member",
                    List.of("assigneeId: unknown staff member '" + request.assigneeId() + "'"));
        }
        final Instant now = clock.instant();
        final Task task = new Task(
                UUID.randomUUID().toString(),
                request.title().trim(),
                request.description(),
                TaskStatus.TODO,
                request.priority() == null ? TaskPriority.MEDIUM : request.priority(),
                request.category() == null ? TaskCategory.GENERAL : request.category(),
                request.storeId(),
                request.projectId(),
                request.assigneeId(),
                request.dueAt(),
                now,
                now);
        return taskRepository.save(task);
    }

    /**
     * Applies a partial update. A status change publishes {@link TaskStatusChangedEvent}; the alerts
     * and reports modules decide for themselves whether they care.
     *
     * <p>Transactional for two reasons: the read-modify-write must not interleave, and the event is
     * delivered after this transaction commits. Without the annotation there is no transaction to
     * commit and the subscribers would never run.
     *
     * @throws ValidationError when the payload changes nothing or names an unknown assignee
     * @throws NotFoundError   when no activity has that id
     * @throws ConflictError   when the status transition is not permitted
     */
    @Transactional
    public Task update(final String id, final UpdateTaskRequest request) {
        if (request.isEmpty()) {
            throw new ValidationError("Update must change at least one of status, priority or assigneeId");
        }
        final Task existing = getById(id);
        final Instant now = clock.instant();

        Task updated = existing;
        if (request.status() != null && request.status() != existing.status()) {
            requireTransitionAllowed(existing.status(), request.status());
            updated = updated.withStatus(request.status(), now);
        }
        if (request.priority() != null && request.priority() != existing.priority()) {
            updated = updated.withPriority(request.priority(), now);
        }
        if (request.assigneeId() != null && !request.assigneeId().isBlank()) {
            if (!userService.exists(request.assigneeId())) {
                throw new ValidationError(
                        "Assignee is not a known staff member",
                        List.of("assigneeId: unknown staff member '" + request.assigneeId() + "'"));
            }
            updated = updated.withAssignee(request.assigneeId(), now);
        }

        final Task saved = taskRepository.save(updated);
        if (saved.status() != existing.status()) {
            eventBus.publish(new TaskStatusChangedEvent(
                    saved.id(),
                    saved.storeId(),
                    existing.status().name(),
                    saved.status().name(),
                    saved.priority().name(),
                    saved.assigneeId(),
                    now));
        }
        return saved;
    }

    /** Read-only view for the programmes and reports modules. */
    public List<Task> findByProjectId(final String projectId) {
        return taskRepository.findByProjectId(projectId);
    }

    /** Read-only view for the reports module. */
    public List<Task> findByStoreId(final String storeId) {
        return taskRepository.findByStoreId(storeId);
    }

    /**
     * Publishes {@link TaskOverdueEvent} for every SLA-tracked activity past its due date.
     *
     * <p>Stub: nothing schedules this yet. It exists so the SLA breach feature has a seam that
     * already respects the event-bus rule, and returns the number of events published.
     *
     * <p>Transactional so the published events reach their after-commit subscribers.
     */
    @Transactional
    public int publishOverdueBreaches() {
        final Instant now = clock.instant();
        final List<Task> breached = taskRepository.findAll().stream()
                .filter(Task::isSlaTracked)
                .filter(task -> task.isOverdueAt(now))
                .toList();
        breached.forEach(task -> eventBus.publish(new TaskOverdueEvent(
                task.id(), task.storeId(), task.priority().name(), task.assigneeId(), task.dueAt(), now)));
        return breached.size();
    }

    private static void requireTransitionAllowed(final TaskStatus from, final TaskStatus to) {
        if (from == TaskStatus.DONE) {
            throw new ConflictError(
                    "TASK_TRANSITION_NOT_ALLOWED",
                    "A DONE activity cannot move to " + to + "; raise a new activity instead");
        }
    }
}
