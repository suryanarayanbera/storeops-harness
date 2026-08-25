package com.cognizant.storeops.alerts.listener;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.alerts.service.SlaBreachService;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
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

    private static final String BLOCKED = "BLOCKED";

    private static final String DONE = "DONE";

    private final NotificationService notificationService;
    private final SlaBreachService slaBreachService;

    public AlertEventListener(
            final NotificationService notificationService, final SlaBreachService slaBreachService) {
        this.notificationService = notificationService;
        this.slaBreachService = slaBreachService;
    }

    /**
     * A blocked activity needs somebody told; a finished one ends any SLA breach it was running. Every
     * other transition is informational.
     *
     * <p>The two branches are independent, and both belong on this handler: they are the same fact
     * ("this activity changed status") read for two different purposes.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskStatusChanged(final TaskStatusChangedEvent event) {
        if (DONE.equals(event.newStatus())) {
            slaBreachService.closeEpisode(event.taskId());
            return;
        }
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

    /**
     * An overdue activity is an observation, not a transition: the sweep republishes it every pass
     * while the activity stays overdue.
     *
     * <p>Which of those observations deserves an alert, and for whom, is
     * {@link SlaBreachService}'s decision - it needs a durable memory of the breach to answer, and that
     * memory is the alerts module's own table. This handler only translates the event into the call.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskOverdue(final TaskOverdueEvent event) {
        slaBreachService.observe(event);
    }

    private static boolean isUnassigned(final String assigneeId, final String taskId) {
        if (assigneeId == null || assigneeId.isBlank()) {
            LOG.warn("Cannot alert on task {}: no assignee to notify", taskId);
            return true;
        }
        return false;
    }
}
