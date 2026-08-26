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
import com.cognizant.storeops.shared.events.TemplateTaskDefinition;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

    /**
     * Creates the activities carried by a {@code PROGRAMME_TEMPLATE_REQUESTED} event.
     *
     * <p>The items arrive fully resolved: the programmes module has already expanded the template and
     * chosen each assignee, so nothing here reads programme membership or a template catalogue. That
     * is what keeps this module free of any import of {@code programmes}.
     *
     * <p>Runs inside the listener's {@code REQUIRES_NEW} transaction rather than declaring one of its
     * own, and publishes nothing: these activities are born {@code TODO}, so no status transitioned
     * and there is no {@code TaskStatusChangedEvent} to raise.
     *
     * <p>Nothing here throws. By the time this runs the publishing transaction has committed and the
     * caller has its {@code 202}, so an exception would be swallowed by the {@code ErrorHandler} in
     * {@code EventBusConfiguration} and cost the whole batch for one bad field. Every unusable value
     * degrades instead: an unrecognised priority becomes {@code MEDIUM}, an unrecognised category
     * {@code GENERAL}, an assignee the staff module does not know becomes unassigned, and an item with
     * no title is skipped.
     *
     * @param projectId programme the activities belong to
     * @param storeId   store to create them in
     * @param items     resolved activities to create, in order
     * @return the activities actually created, which excludes every skipped item
     */
    public List<Task> createFromTemplate(
            final String projectId, final String storeId, final List<TemplateTaskDefinition> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        // Seeded with what the programme already holds, then added to as the batch is walked, so a
        // repeat call creates nothing and a template carrying a duplicate title creates it once.
        // Every category counts: a title clash is a clash, whatever kind of work it was.
        final Set<String> takenTitles = taskRepository.findByProjectId(projectId).stream()
                .map(task -> titleKey(task.title()))
                .collect(Collectors.toCollection(HashSet::new));
        final Instant now = clock.instant();

        final List<Task> created = new ArrayList<>();
        for (final TemplateTaskDefinition item : items) {
            if (item.title() == null || item.title().isBlank() || !takenTitles.add(titleKey(item.title()))) {
                continue;
            }
            created.add(taskRepository.save(new Task(
                    UUID.randomUUID().toString(),
                    item.title().trim(),
                    item.description(),
                    TaskStatus.TODO,
                    priorityOrDefault(item.priority()),
                    categoryOrDefault(item.category()),
                    storeId,
                    projectId,
                    knownAssigneeOrNull(item.assigneeId()),
                    null,
                    now,
                    now)));
        }
        return List.copyOf(created);
    }

    /** Titles are compared ignoring case and surrounding whitespace. */
    private static String titleKey(final String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    }

    /** Matches a {@code TaskPriority} by name, falling back to MEDIUM as {@link #create} does. */
    private static TaskPriority priorityOrDefault(final String name) {
        return Arrays.stream(TaskPriority.values())
                .filter(value -> value.name().equalsIgnoreCase(trimmed(name)))
                .findFirst()
                .orElse(TaskPriority.MEDIUM);
    }

    /** Matches a {@code TaskCategory} by name, falling back to GENERAL as {@link #create} does. */
    private static TaskCategory categoryOrDefault(final String name) {
        return Arrays.stream(TaskCategory.values())
                .filter(value -> value.name().equalsIgnoreCase(trimmed(name)))
                .findFirst()
                .orElse(TaskCategory.GENERAL);
    }

    private static String trimmed(final String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Keeps an assignee the staff module knows, and drops one it does not.
     *
     * <p>Deliberately not the {@code ValidationError} that {@link #create} raises for the same input.
     * That path has a caller to tell; this one does not, and refusing the activity would lose the work
     * as well as the assignment.
     */
    private String knownAssigneeOrNull(final String assigneeId) {
        return assigneeId != null && userService.exists(assigneeId) ? assigneeId : null;
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
     * Publishes {@link TaskOverdueEvent} for every SLA-tracked activity past its due date, and
     * returns the number of events published.
     *
     * <p>Driven by {@link SlaSweepScheduler} on a fixed delay. Deliberately stateless: it does not
     * record what it has already reported, so an activity that stays overdue is re-published on
     * every cycle. That is the signal the alerts module uses to tell "newly breached" from "still
     * unresolved"; suppressing the repeats is the subscriber's job.
     *
     * <p>Transactional so the published events reach their after-commit subscribers. Removing the
     * annotation is invisible to any test using {@code RecordingEventBus}, which records at publish
     * time, and silently stops every real subscriber: Spring skips after-commit callbacks when no
     * transaction is active.
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
