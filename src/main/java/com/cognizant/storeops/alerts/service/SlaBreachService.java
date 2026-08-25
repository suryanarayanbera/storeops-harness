package com.cognizant.storeops.alerts.service;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.SlaBreach;
import com.cognizant.storeops.alerts.repository.SlaBreachRepository;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.service.UserService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The SLA breach policy: which breaches deserve an alert, who hears about it, and how often.
 *
 * <p>Reached only from {@code AlertEventListener}. It takes the event record directly - permitted
 * because {@code shared.events} depends on no module, so accepting it drags nothing from the activities
 * module across the boundary. The event's priority arrives as a {@code String} for exactly that reason.
 *
 * <p>Two rules of this class are easy to break by accident:
 *
 * <ul>
 *   <li><strong>De-duplication is the episode row, not a field.</strong> The activities module
 *       republishes {@code TaskOverdueEvent} on every sweep while an activity stays overdue, so
 *       "have I already alerted on this?" is asked once per sweep, forever. A {@code Set} field would
 *       answer it correctly until the first restart and then alert all over again.
 *   <li><strong>Nothing here throws.</strong> This runs inside an after-commit listener, where the
 *       {@code EventBus} error handler absorbs whatever escapes - so a thrown error would not reach a
 *       caller, it would just lose the alert. An unresolvable recipient is logged and left without an
 *       episode row, which means the next sweep tries again once the staff record is fixed.
 * </ul>
 */
@Service
public class SlaBreachService {

    private static final Logger LOG = LoggerFactory.getLogger(SlaBreachService.class);

    /** Priority bands that warrant an SLA breach alert. Belt-and-braces: the sweep filters too. */
    private static final Set<String> SLA_TRACKED_PRIORITIES = Set.of("HIGH", "CRITICAL");

    private final SlaBreachRepository slaBreachRepository;
    private final NotificationService notificationService;
    private final UserService userService;
    private final SlaEscalationProperties escalationProperties;
    private final Clock clock;

    public SlaBreachService(
            final SlaBreachRepository slaBreachRepository,
            final NotificationService notificationService,
            final UserService userService,
            final SlaEscalationProperties escalationProperties,
            final Clock clock) {
        this.slaBreachRepository = slaBreachRepository;
        this.notificationService = notificationService;
        this.userService = userService;
        this.escalationProperties = escalationProperties;
        this.clock = clock;
    }

    /**
     * Records one observation of an overdue activity.
     *
     * <p>The first observation alerts the responsible Department Lead. A later observation escalates to
     * the store manager once the configured grace period has passed, or simply notes that the breach is
     * still unresolved.
     *
     * <p>Transactional so the alert and the episode row it is recorded in cannot come apart. Its only
     * caller is a listener that already opens a transaction, which this joins; the annotation is here so
     * that a second caller cannot raise an alert this class then fails to remember.
     */
    @Transactional
    public void observe(final TaskOverdueEvent event) {
        if (!SLA_TRACKED_PRIORITIES.contains(event.priority())) {
            LOG.debug("Overdue activity {} is {} priority; no SLA alert", event.taskId(), event.priority());
            return;
        }
        slaBreachRepository.findByTaskId(event.taskId())
                .ifPresentOrElse(episode -> reobserve(episode, event), () -> openEpisode(event));
    }

    /**
     * Ends an episode, because the activity it tracked has been resolved.
     *
     * <p>This is what makes escalation conditional on the breach actually persisting. Without it the
     * only evidence of resolution would be the absence of further events, which is indistinguishable
     * from the sweep being switched off.
     */
    @Transactional
    public void closeEpisode(final String taskId) {
        if (slaBreachRepository.deleteByTaskId(taskId)) {
            LOG.info("Closed SLA breach episode for activity {}", taskId);
        }
    }

    /**
     * A breach already alerted on, seen again.
     *
     * <p>Escalation is only ever considered here, never on a first observation: it is a claim that the
     * breach has persisted, and one sighting cannot support that. Guarding on elapsed time alone would
     * make a zero grace period fire both alerts in the same sweep.
     */
    private void reobserve(final SlaBreach episode, final TaskOverdueEvent event) {
        final Instant now = clock.instant();
        if (episode.isEscalated()) {
            slaBreachRepository.save(episode.withLastSeen(now));
            LOG.debug("Activity {} is still breached and already escalated", event.taskId());
            return;
        }
        if (event.occurredAt().isBefore(episode.escalationDueAt(escalationProperties.gracePeriod()))) {
            slaBreachRepository.save(episode.withLastSeen(now));
            LOG.debug("Activity {} is still breached; grace period has not elapsed", event.taskId());
            return;
        }
        escalate(episode, now);
    }

    /** Hands an unresolved breach up to the store manager. */
    private void escalate(final SlaBreach episode, final Instant now) {
        final Optional<User> manager = storeManager(episode.storeId());
        if (manager.isEmpty()) {
            // escalatedAt stays null so a later sweep tries again once the store has a manager.
            LOG.warn("Breach on activity {} is due for escalation but store {} has no active manager",
                    episode.taskId(), episode.storeId());
            slaBreachRepository.save(episode.withLastSeen(now));
            return;
        }
        final String managerId = manager.get().id();
        if (managerId.equals(episode.leadRecipientId())) {
            // Marked escalated regardless, so this is not reconsidered on every later sweep.
            LOG.warn("Breach on activity {} escalates to {}, who was already alerted as the lead; "
                    + "no second alert raised", episode.taskId(), managerId);
            slaBreachRepository.save(episode.withEscalation(managerId, now));
            return;
        }
        notificationService.raise(
                managerId,
                AlertType.ESCALATION,
                "Escalated: SLA breach unresolved on " + episode.priority() + " activity",
                "Activity " + episode.taskId() + " at store " + episode.storeId()
                        + " has been breached since " + episode.firstBreachAt()
                        + " and is still not DONE after a grace period of "
                        + escalationProperties.gracePeriod() + ". " + episode.leadRecipientId()
                        + " was alerted as the responsible lead at " + episode.leadNotifiedAt() + ".",
                episode.taskId());
        slaBreachRepository.save(episode.withEscalation(managerId, now));
        LOG.info("Escalated SLA breach on activity {} to store manager {}", episode.taskId(), managerId);
    }

    /** Alerts the responsible lead and starts remembering the breach. */
    private void openEpisode(final TaskOverdueEvent event) {
        final Optional<User> recipient = resolveLead(event);
        if (recipient.isEmpty()) {
            // No row written on purpose: the next sweep retries once staff data can answer.
            LOG.warn("Cannot alert on breached activity {} at store {}: nobody to notify",
                    event.taskId(), event.storeId());
            return;
        }
        final String recipientId = recipient.get().id();
        final Instant now = clock.instant();
        notificationService.raise(
                recipientId,
                AlertType.SLA_BREACH,
                "SLA breach on " + event.priority() + " activity",
                "Activity " + event.taskId() + " at store " + event.storeId()
                        + " passed its due date of " + event.dueAt() + " without reaching DONE."
                        + " Assigned to " + event.assigneeId() + ".",
                event.taskId());
        slaBreachRepository.save(SlaBreach.opened(
                event.taskId(), event.storeId(), event.priority(), recipientId, now));
        LOG.info("Opened SLA breach episode for activity {}; lead {} notified", event.taskId(), recipientId);
    }

    /**
     * The staff member accountable for the assignee's work: their department's lead, or the store
     * manager when the department has none.
     *
     * <p>Reads staff through {@code UserService}, the sanctioned cross-module read. Uses its
     * {@code Optional} lookup rather than {@code getById}, which would throw {@code NotFoundError}
     * inside a listener where nothing would catch it.
     */
    private Optional<User> resolveLead(final TaskOverdueEvent event) {
        if (event.assigneeId() == null || event.assigneeId().isBlank()) {
            LOG.warn("Breached activity {} has no assignee, so no lead to resolve", event.taskId());
            return Optional.empty();
        }
        final Optional<User> assignee = userService.findById(event.assigneeId());
        if (assignee.isEmpty()) {
            LOG.warn("Assignee {} of breached activity {} is not a known staff member",
                    event.assigneeId(), event.taskId());
            return Optional.empty();
        }
        return departmentLead(event.storeId(), assignee.get())
                .or(() -> storeManager(event.storeId()));
    }

    /** The active lead of the assignee's department, the assignee themselves if they hold that role. */
    private Optional<User> departmentLead(final String storeId, final User assignee) {
        if (assignee.role() == StaffRole.DEPARTMENT_LEAD && assignee.active()) {
            return Optional.of(assignee);
        }
        final String department = assignee.profile().department();
        if (department == null || department.isBlank()) {
            return Optional.empty();
        }
        return firstById(userService.findByStoreIdAndRole(storeId, StaffRole.DEPARTMENT_LEAD).stream()
                .filter(candidate -> department.equals(candidate.profile().department())));
    }

    private Optional<User> storeManager(final String storeId) {
        return firstById(userService.findByStoreIdAndRole(storeId, StaffRole.STORE_MANAGER).stream());
    }

    /** Lowest id wins, so two equally eligible recipients cannot make the choice arbitrary. */
    private static Optional<User> firstById(final Stream<User> candidates) {
        return candidates.filter(User::active).min(Comparator.comparing(User::id));
    }
}
