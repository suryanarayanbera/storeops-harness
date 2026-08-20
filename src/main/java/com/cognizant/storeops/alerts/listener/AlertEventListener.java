package com.cognizant.storeops.alerts.listener;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import jakarta.annotation.PostConstruct;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The alerts module's inbound edge.
 *
 * <p>This class is the reason the activities module contains no alerting code. Activities publishes
 * a fact ("this activity is now BLOCKED"); the decision that the fact deserves an alert is made
 * here, by the module that owns alerting. Reversing that - having activities call
 * {@code NotificationService} directly - is the module boundary violation the harness exists to
 * catch.
 *
 * <p>Note that only {@code shared.events} types are imported: no {@code activities} import appears,
 * which is why the events carry enum names as strings.
 */
@Component
public class AlertEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(AlertEventListener.class);

    /** Priority bands that warrant an SLA breach alert. */
    private static final Set<String> SLA_TRACKED_PRIORITIES = Set.of("HIGH", "CRITICAL");

    private static final String BLOCKED = "BLOCKED";

    private final EventBus eventBus;
    private final NotificationService notificationService;

    public AlertEventListener(final EventBus eventBus, final NotificationService notificationService) {
        this.eventBus = eventBus;
        this.notificationService = notificationService;
    }

    @PostConstruct
    void register() {
        eventBus.subscribe(TaskStatusChangedEvent.class, this::onTaskStatusChanged);
        eventBus.subscribe(TaskOverdueEvent.class, this::onTaskOverdue);
        LOG.info("Alerts module subscribed to TASK_STATUS_CHANGED and TASK_OVERDUE");
    }

    /** A blocked activity needs somebody told. Every other transition is informational. */
    void onTaskStatusChanged(final TaskStatusChangedEvent event) {
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
    void onTaskOverdue(final TaskOverdueEvent event) {
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
