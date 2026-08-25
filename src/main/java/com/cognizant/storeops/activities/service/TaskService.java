package com.cognizant.storeops.activities.service;

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
import com.cognizant.storeops.activities.repository.TaskRepository;
import com.cognizant.storeops.shared.error.AppError;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * The only statuses a handover batch may set. A handover closes work down or hands a blockage on;
     * reopening or starting work is a single-activity decision and stays on {@code PATCH /{id}}.
     */
    private static final Set<TaskStatus> BULK_TARGET_STATUSES = Set.of(TaskStatus.DONE, TaskStatus.BLOCKED);

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
     * Applies a shift handover batch: each entry is settled on its own, and the report says which
     * ones took and which ones did not. An unknown id or a refused transition fails that entry only.
     *
     * <p>Every entry whose status actually moves publishes {@link TaskStatusChangedEvent}, exactly as
     * {@link #update} does. Entries that fail, and entries already holding the requested status,
     * publish nothing.
     *
     * <p>Transactional for the same reason {@link #update} is - the events are dispatched after this
     * transaction commits, and with no transaction to commit no subscriber would ever run. One
     * transaction spans the whole batch, so partial failure must be expressed by catching
     * {@link AppError} from a plain helper rather than by letting one escape: see
     * {@link #applyHandoverEntry}.
     */
    @Transactional
    public BulkStatusUpdateResponse bulkUpdateStatus(final BulkStatusUpdateRequest request) {
        final List<BulkStatusUpdateSuccess> succeeded = new ArrayList<>();
        final List<BulkStatusUpdateFailure> failed = new ArrayList<>();
        final Set<String> applied = new HashSet<>();

        for (final BulkStatusUpdateItem entry : request.updates()) {
            try {
                succeeded.add(applyHandoverEntry(entry, applied));
            } catch (final AppError error) {
                failed.add(BulkStatusUpdateFailure.from(entry.taskId(), error));
            }
        }
        return new BulkStatusUpdateResponse(succeeded, failed);
    }

    /**
     * Settles one entry of a handover batch, or throws describing why it could not be settled.
     *
     * <p>Deliberately a plain private method. It must <em>not</em> be annotated {@code @Transactional}
     * and the batch must <em>not</em> reach it by calling the public {@link #update} instead: either
     * route puts a transaction boundary between the loop and the failure, so a rejected entry marks
     * the batch transaction rollback-only and the whole handover is then lost to an
     * {@code UnexpectedRollbackException} at commit - after the report has already claimed success for
     * its neighbours. Thrown here and caught in the loop, a rejection costs nothing but its own entry.
     *
     * @param applied ids already settled by this batch, so a repeated id cannot be applied twice
     */
    private BulkStatusUpdateSuccess applyHandoverEntry(
            final BulkStatusUpdateItem entry, final Set<String> applied) {
        if (!BULK_TARGET_STATUSES.contains(entry.status())) {
            throw new ValidationError(
                    "A handover batch can only set DONE or BLOCKED",
                    List.of("status: '" + entry.status() + "' is not a handover target status"));
        }
        if (!applied.add(entry.taskId())) {
            throw new ValidationError(
                    "Activity '" + entry.taskId() + "' appears more than once in this batch",
                    List.of("taskId: duplicate entry for '" + entry.taskId() + "'"));
        }

        final Task existing = getById(entry.taskId());
        if (existing.status() == entry.status()) {
            return BulkStatusUpdateSuccess.unchanged(existing);
        }
        requireTransitionAllowed(existing.status(), entry.status());

        final Instant now = clock.instant();
        final Task saved = taskRepository.save(existing.withStatus(entry.status(), now));
        eventBus.publish(new TaskStatusChangedEvent(
                saved.id(),
                saved.storeId(),
                existing.status().name(),
                saved.status().name(),
                saved.priority().name(),
                saved.assigneeId(),
                now));
        return BulkStatusUpdateSuccess.changed(existing, saved);
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
     * returns how many events it published. Driven on a timer by
     * {@code activities.listener.OverdueSweepScheduler}.
     *
     * <p>Deliberately not idempotent: an activity that is still overdue is republished on every
     * sweep. The repeat is the signal the alerts module uses to tell an unresolved breach from a
     * resolved one, so this module keeps no record of what it has already announced - which is also
     * what keeps alerting state out of the module that must know nothing about alerting.
     *
     * <p>Transactional so the published events reach their after-commit subscribers. Without it
     * Spring skips every {@code @TransactionalEventListener} and the sweep silently does nothing.
     */
    @Transactional
    public int publishOverdueBreaches() {
        final Instant now = clock.instant();
        final List<Task> breached = taskRepository.findOpenPastDue(now).stream()
                .filter(Task::isSlaTracked)
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
