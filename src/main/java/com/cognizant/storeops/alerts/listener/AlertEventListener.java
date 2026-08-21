package com.cognizant.storeops.alerts.listener;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The alerts module's inbound edge.
 *
 * <p>This class is the reason the activities module contains no alerting code. Activities publishes
 * a fact ("this activity is now BLOCKED"); the decision that the fact deserves an alert is made
 * here, by the module that owns alerting. Reversing that - having activities call
 * {@code NotificationService} directly - is the module boundary violation the harness exists to
 * catch.
 *
 * <p>Only {@code shared.events} types are imported: no {@code activities} import appears, which is
 * why the events carry enum names as strings.
 *
 * <p>Handlers run {@link TransactionPhase#AFTER_COMMIT}, so an alert is never raised for an activity
 * update that was rolled back. Two consequences that are easy to get wrong:
 *
 * <ul>
 *   <li>The publishing service method must be transactional, or these handlers never run at all -
 *       Spring skips after-commit callbacks when no transaction is active.
 *   <li>Each handler needs {@link Propagation#REQUIRES_NEW}. At after-commit time the original
 *       transaction has already committed, so a write that joined it would never be flushed. The
 *       alert would be silently discarded with no error anywhere.
 * </ul>
 */
@Component
public class AlertEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(AlertEventListener.class);

    /** Priority bands that warrant an SLA breach alert. */
    private static final Set<String> SLA_TRACKED_PRIORITIES = Set.of("HIGH", "CRITICAL");

    private static final String BLOCKED = "BLOCKED";

    private final NotificationService notificationService;

    public AlertEventListener(final NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /** A blocked activity needs somebody told. Every other transition is informational. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskStatusChanged(final TaskStatusChangedEvent event) {
        if (!BLOCKED.equals(event.newStatus())) {
            LOG.debug("No alert for {} -> {} on task {}", event.previousStatus(), event.newStatus(), event.taskId());
            return;
        }
        if (isUnassigned(event.assigneeId(), event.taskId())) {
            return;
        }
        notificationService.raise(
                event.assigneeId(),
                AlertType.ESCALATION,
                "Activity blocked",
                "Activity " + event.taskId() + " at store " + event.storeId()
                        + " moved from " + event.previousStatus() + " to BLOCKED.",
                event.taskId());
    }

    /** SLA breach alerting, stub: HIGH and CRITICAL only, no grace period or escalation chain yet. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskOverdue(final TaskOverdueEvent event) {
        if (!SLA_TRACKED_PRIORITIES.contains(event.priority())) {
            LOG.debug("Overdue task {} is {} priority; no SLA alert", event.taskId(), event.priority());
            return;
        }
        if (isUnassigned(event.assigneeId(), event.taskId())) {
            return;
        }
        notificationService.raise(
                event.assigneeId(),
                AlertType.SLA_BREACH,
                "SLA breach on " + event.priority() + " activity",
                "Activity " + event.taskId() + " at store " + event.storeId()
                        + " passed its due date of " + event.dueAt() + " without reaching DONE.",
                event.taskId());
    }

    private static boolean isUnassigned(final String assigneeId, final String taskId) {
        if (assigneeId == null || assigneeId.isBlank()) {
            LOG.warn("Cannot alert on task {}: no assignee to notify", taskId);
            return true;
        }
        return false;
    }
}
