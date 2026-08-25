package com.cognizant.storeops.alerts.listener;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.alerts.service.SlaAlertProperties;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * <p>No {@code activities} import appears, which is why the events carry enum names as strings. The
 * only module type imported is {@code staff}, through {@link UserService} - a read-only lookup, which
 * is the sanctioned form of cross-module read and creates no cycle because {@code staff} imports
 * nothing back. Note what is <em>not</em> injected: {@code TaskService}. Deciding whether an activity
 * is still unresolved by asking {@code activities} would make this module depend on state it does not
 * own; the arrival of the event is that proof, because the sweep only publishes for activities that
 * are still overdue and still not {@code DONE}.
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

    /**
     * Subject of a first-stage SLA breach alert.
     *
     * <p>A constant because it is load-bearing, not cosmetic: it is how a breach alert is recognised
     * on a later sweep so the breach is not re-raised.
     */
    static final String SLA_BREACH_SUBJECT = "SLA breach";

    /**
     * Subject of a second-stage SLA escalation.
     *
     * <p>Load-bearing for the same reason as {@link #SLA_BREACH_SUBJECT}, and for one more:
     * {@code AlertType.ESCALATION} is shared with the blocked-activity path, which also writes
     * {@code sourceRef = taskId}. An activity that was blocked before it breached therefore already
     * has an {@code ESCALATION} row carrying its id, so the subject is the only thing distinguishing
     * "already escalated for its SLA" from "was blocked once". Matching on {@code alertType} alone
     * would suppress the SLA escalation permanently, for exactly those activities most likely to
     * need it.
     */
    static final String SLA_ESCALATION_SUBJECT = "SLA breach escalated";

    private final NotificationService notificationService;
    private final UserService userService;
    private final SlaAlertProperties slaAlertProperties;
    private final Clock clock;

    public AlertEventListener(
            final NotificationService notificationService,
            final UserService userService,
            final SlaAlertProperties slaAlertProperties,
            final Clock clock) {
        this.notificationService = notificationService;
        this.userService = userService;
        this.slaAlertProperties = slaAlertProperties;
        this.clock = clock;
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

    /**
     * SLA breach alerting, in two stages.
     *
     * <p>The sweep re-publishes this event on every cycle for as long as the activity stays overdue,
     * so deciding what each observation means is this method's job, not the publisher's. There is no
     * stored state machine: which stage an observation belongs to is derived from the alerts already
     * raised for the activity.
     *
     * <ul>
     *   <li>No prior {@code SLA_BREACH} - the breach is new. Alert the Department Lead responsible
     *       for the assignee's department. Not the assignee: a missed deadline is a supervisory
     *       concern, and the person who missed it is not the person who needs to act on it.
     *   <li>A prior {@code SLA_BREACH} younger than the grace period - nothing to do. The lead has
     *       been told and still has time.
     *   <li>A prior {@code SLA_BREACH} older than the grace period, not yet escalated - the lead has
     *       had their window. Escalate to the store manager.
     *   <li>Both already raised - nothing to do, however many further sweeps arrive.
     * </ul>
     *
     * <p>Both stages key de-duplication on the activity, never on the alert type alone: an activity
     * is identified by its {@code sourceRef}, and stage two narrows further by subject because
     * {@code ESCALATION} is shared with the blocked-activity path.
     *
     * <p>Note that nothing here asks whether the activity is still unresolved. The arrival of the
     * event is that proof - the sweep publishes only for activities that are still SLA-tracked, still
     * past due and still not {@code DONE}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onTaskOverdue(final TaskOverdueEvent event) {
        if (!SLA_TRACKED_PRIORITIES.contains(event.priority())) {
            LOG.debug("Overdue task {} is {} priority; no SLA alert", event.taskId(), event.priority());
            return;
        }
        final List<Notification> raisedBreaches =
                notificationService.findBySourceRefAndAlertType(event.taskId(), AlertType.SLA_BREACH);
        if (raisedBreaches.isEmpty()) {
            raiseBreach(event);
            return;
        }
        escalateIfGraceElapsed(event, raisedBreaches.getFirst().createdAt());
    }

    /** Stage one: tell the Department Lead, once. */
    private void raiseBreach(final TaskOverdueEvent event) {
        final String recipientId = resolveBreachRecipient(event);
        if (recipientId == null) {
            // Deliberately not thrown. This runs after commit, so Spring would swallow the exception
            // and the operator would see nothing at all - a log line is strictly more visible.
            LOG.warn("Cannot raise an SLA breach for activity {}: store {} has no active department "
                    + "lead and no active store manager", event.taskId(), event.storeId());
            return;
        }
        notificationService.raise(
                recipientId,
                AlertType.SLA_BREACH,
                SLA_BREACH_SUBJECT,
                "Activity " + event.taskId() + " at store " + event.storeId() + " is " + event.priority()
                        + " priority and passed its due date of " + event.dueAt()
                        + " without reaching DONE.",
                event.taskId());
    }

    /**
     * Stage two: once the grace period has run from the first breach alert, tell the store manager.
     *
     * @param breachedAt when the first {@code SLA_BREACH} for this activity was raised - the oldest
     *                   row, which is why the repository read is ordered ascending
     */
    private void escalateIfGraceElapsed(final TaskOverdueEvent event, final Instant breachedAt) {
        final Instant escalateFrom = breachedAt.plus(slaAlertProperties.gracePeriod());
        if (clock.instant().isBefore(escalateFrom)) {
            LOG.debug("Activity {} breached at {}; grace period runs to {}, nothing to escalate yet",
                    event.taskId(), breachedAt, escalateFrom);
            return;
        }
        if (alreadyEscalated(event.taskId())) {
            LOG.debug("Activity {} has already been escalated; suppressing the repeat", event.taskId());
            return;
        }
        final String managerId = storeManagerId(event.storeId());
        if (managerId == null) {
            LOG.warn("Cannot escalate the SLA breach on activity {}: store {} has no active store "
                    + "manager", event.taskId(), event.storeId());
            return;
        }
        notificationService.raise(
                managerId,
                AlertType.ESCALATION,
                SLA_ESCALATION_SUBJECT,
                "Activity " + event.taskId() + " at store " + event.storeId() + " is " + event.priority()
                        + " priority, passed its due date of " + event.dueAt()
                        + ", and is still not DONE " + slaAlertProperties.gracePeriod()
                        + " after the breach was raised.",
                event.taskId());
    }

    /**
     * Whether this activity has already been escalated <em>for its SLA</em>.
     *
     * <p>Matches on subject as well as alert type. An {@code ESCALATION} alone is not enough: the
     * blocked-activity handler writes one with the same {@code sourceRef}, so an activity that was
     * blocked before it breached would look permanently escalated and never reach its store manager.
     */
    private boolean alreadyEscalated(final String taskId) {
        return notificationService.findBySourceRefAndAlertType(taskId, AlertType.ESCALATION)
                .stream()
                .anyMatch(alert -> SLA_ESCALATION_SUBJECT.equals(alert.subject()));
    }

    /**
     * The Department Lead for the assignee's department at the activity's store, falling back to the
     * store's Store Manager.
     *
     * @return the recipient's id, or null when the store has nobody to tell
     */
    private String resolveBreachRecipient(final TaskOverdueEvent event) {
        final String department = departmentOf(event.assigneeId());
        if (department != null) {
            final Optional<User> lead = userService
                    .findByStoreIdAndRole(event.storeId(), StaffRole.DEPARTMENT_LEAD)
                    .stream()
                    .filter(candidate -> department.equals(candidate.profile().department()))
                    .findFirst();
            if (lead.isPresent()) {
                return lead.get().id();
            }
        }
        LOG.warn("No active department lead for department {} at store {}; routing the SLA breach on "
                + "activity {} to the store manager", department, event.storeId(), event.taskId());
        return storeManagerId(event.storeId());
    }

    /**
     * Department the assignee works in, or null when it cannot be established - which covers an
     * unassigned activity, a blank id, an assignee the staff module does not know, and a staff record
     * with no department recorded.
     */
    private String departmentOf(final String assigneeId) {
        if (assigneeId == null || assigneeId.isBlank()) {
            return null;
        }
        return userService.findById(assigneeId)
                .map(assignee -> assignee.profile().department())
                .orElse(null);
    }

    /** Lowest-id active Store Manager at the store, or null when there is none. */
    private String storeManagerId(final String storeId) {
        return userService.findByStoreIdAndRole(storeId, StaffRole.STORE_MANAGER)
                .stream()
                .findFirst()
                .map(User::id)
                .orElse(null);
    }

    private static boolean isUnassigned(final String assigneeId, final String taskId) {
        if (assigneeId == null || assigneeId.isBlank()) {
            LOG.warn("Cannot alert on task {}: no assignee to notify", taskId);
            return true;
        }
        return false;
    }
}
