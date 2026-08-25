package com.cognizant.storeops.activities.service;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.dto.BulkStatusFailure;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateItem;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateRequest;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateResponse;
import com.cognizant.storeops.activities.dto.TaskResponse;
import com.cognizant.storeops.activities.dto.UpdateTaskRequest;
import com.cognizant.storeops.shared.error.AppError;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.ValidationError;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Shift handover: moves several activities to {@code DONE} or {@code BLOCKED} in one request, each
 * one independently of the others.
 *
 * <p>Two deliberate absences in this class are load-bearing, and both fail silently if reversed.
 *
 * <p><strong>No {@code @Transactional} on {@link #bulkUpdateStatus}.</strong> With an outer
 * transaction open, every {@code taskService.update} call would join it, and the first
 * {@link AppError} thrown by a failing activity would mark that transaction rollback-only. The
 * catch below swallows the exception, but the eventual commit would then fail with
 * {@code UnexpectedRollbackException} and the whole batch would be lost - the opposite of the
 * per-activity independence this endpoint exists to provide. Calling a {@code @Transactional}
 * method with no transaction already active gives one transaction per activity, which is exactly
 * what is wanted: each commits or rolls back on its own.
 *
 * <p><strong>No {@code EventBus} here.</strong> {@link TaskService#update} publishes
 * {@code TaskStatusChangedEvent}, and it is reached through the injected bean rather than by
 * moving this loop into {@code TaskService} and self-invoking. A {@code this.update(...)} call
 * would bypass the Spring proxy, so no transaction would open and the
 * {@code @TransactionalEventListener(AFTER_COMMIT)} subscribers would never run - with no
 * exception and nothing in the log. Delegating also means the bulk path is the single-activity
 * path rather than a second implementation of it that can drift.
 */
@Service
public class TaskBulkStatusService {

    /** The only statuses an outgoing shift can hand an activity over in. */
    private static final Set<TaskStatus> HANDOVER_TARGETS = EnumSet.of(TaskStatus.DONE, TaskStatus.BLOCKED);

    private final TaskService taskService;

    public TaskBulkStatusService(final TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * Applies every instruction in the batch, in request order.
     *
     * <p>Each activity is attempted on its own: an unknown id, an unsupported target status or a
     * forbidden transition fails that activity and nothing else. Every activity reported in
     * {@code succeeded} really changed status, and therefore published exactly one
     * {@code TaskStatusChangedEvent}.
     *
     * @throws ValidationError when the batch names the same activity more than once, which is the
     *                         one failure that cannot be attributed to a single activity
     */
    public BulkStatusUpdateResponse bulkUpdateStatus(final BulkStatusUpdateRequest request) {
        requireDistinctTaskIds(request.updates());

        final List<TaskResponse> succeeded = new ArrayList<>();
        final List<BulkStatusFailure> failed = new ArrayList<>();
        for (final BulkStatusUpdateItem item : request.updates()) {
            try {
                succeeded.add(TaskResponse.from(handOver(item)));
            } catch (AppError error) {
                // AppError only: a genuine defect must still escape as a 500 rather than be
                // reported as though it were a business outcome for this activity.
                failed.add(BulkStatusFailure.from(item.taskId(), error));
            }
        }
        return new BulkStatusUpdateResponse(succeeded, failed);
    }

    private Task handOver(final BulkStatusUpdateItem item) {
        final Task current = taskService.getById(item.taskId());
        requireHandoverTarget(item.status());
        requireRealTransition(current, item.status());
        return taskService.update(item.taskId(), new UpdateTaskRequest(item.status(), null, null));
    }

    private static void requireDistinctTaskIds(final List<BulkStatusUpdateItem> updates) {
        final Set<String> seen = new HashSet<>();
        final Set<String> duplicates = new LinkedHashSet<>();
        for (final BulkStatusUpdateItem item : updates) {
            if (!seen.add(item.taskId())) {
                duplicates.add(item.taskId());
            }
        }
        if (!duplicates.isEmpty()) {
            throw new ValidationError(
                    "A shift handover must not name the same activity twice",
                    duplicates.stream().map(taskId -> "updates: duplicate taskId '" + taskId + "'").toList());
        }
    }

    private static void requireHandoverTarget(final TaskStatus target) {
        if (!HANDOVER_TARGETS.contains(target)) {
            throw new ValidationError(
                    "A shift handover can only set an activity to DONE or BLOCKED",
                    List.of("status: " + target + " is not a supported bulk handover status"));
        }
    }

    /**
     * Rejects an instruction that would not move the activity anywhere.
     *
     * <p>{@link TaskService#update} treats a request for the status an activity already holds as a
     * no-op: it changes nothing, publishes nothing and reports success. That is right for a partial
     * single-activity update, where the status may not be the field the caller meant to change, but
     * here it would put an activity in {@code succeeded} having published no event. Rejecting it
     * keeps the invariant that every reported success is a real transition.
     *
     * <p>An activity that is already {@code DONE} is reported under the terminal-status rule
     * instead, because that is the more specific reason and the one the single-activity path gives
     * for every other transition out of {@code DONE}.
     */
    private static void requireRealTransition(final Task current, final TaskStatus target) {
        if (current.status() != target) {
            return;
        }
        if (current.status() == TaskStatus.DONE) {
            throw new ConflictError(
                    "TASK_TRANSITION_NOT_ALLOWED",
                    "A DONE activity cannot move to " + target + "; raise a new activity instead");
        }
        throw new ConflictError(
                "TASK_STATUS_UNCHANGED",
                "Activity " + current.id() + " is already " + target + "; nothing to hand over");
    }
}
