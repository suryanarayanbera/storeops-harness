package com.cognizant.storeops.alerts.listener;

import static org.assertj.core.api.Assertions.assertThat;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.shared.events.InMemoryEventBus;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.support.FakeNotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alerts module's reaction to events published by other modules.
 *
 * <p>Nothing here imports the activities module: an event carrying strings is all the alerts module
 * ever sees, which is what makes the boundary rule hold.
 */
class AlertEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private InMemoryEventBus eventBus;
    private FakeNotificationRepository notificationRepository;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryEventBus();
        notificationRepository = new FakeNotificationRepository();
        notificationService = new NotificationService(notificationRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        new AlertEventListener(eventBus, notificationService).register();
    }

    private List<Notification> raisedAlerts() {
        return notificationRepository.findAll();
    }

    @Test
    @DisplayName("a BLOCKED transition raises an ESCALATION alert for the assignee")
    void blockedTransitionRaisesEscalation() {
        eventBus.publish(new TaskStatusChangedEvent(
                "task-001", "store-001", "IN_PROGRESS", "BLOCKED", "HIGH", "user-004", NOW));

        assertThat(raisedAlerts()).hasSize(1);
        final Notification alert = raisedAlerts().getFirst();
        assertThat(alert.alertType()).isEqualTo(AlertType.ESCALATION);
        assertThat(alert.recipientId()).isEqualTo("user-004");
        assertThat(alert.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(alert.sourceRef()).isEqualTo("task-001");
        assertThat(alert.body()).contains("task-001").contains("store-001").contains("BLOCKED");
    }

    @Test
    @DisplayName("a DONE transition raises no alert")
    void doneTransitionRaisesNothing() {
        eventBus.publish(new TaskStatusChangedEvent(
                "task-001", "store-001", "IN_PROGRESS", "DONE", "HIGH", "user-004", NOW));

        assertThat(raisedAlerts()).isEmpty();
    }

    @Test
    @DisplayName("a BLOCKED transition on an unassigned activity raises no alert")
    void blockedUnassignedRaisesNothing() {
        eventBus.publish(new TaskStatusChangedEvent(
                "task-001", "store-001", "TODO", "BLOCKED", "HIGH", null, NOW));

        assertThat(raisedAlerts()).isEmpty();
    }

    @Test
    @DisplayName("a CRITICAL overdue activity raises an SLA_BREACH alert")
    void criticalOverdueRaisesSlaBreach() {
        eventBus.publish(new TaskOverdueEvent(
                "task-002", "store-001", "CRITICAL", "user-003", NOW.minusSeconds(3_600), NOW));

        assertThat(raisedAlerts()).hasSize(1);
        final Notification alert = raisedAlerts().getFirst();
        assertThat(alert.alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(alert.recipientId()).isEqualTo("user-003");
        assertThat(alert.subject()).contains("CRITICAL");
    }

    @Test
    @DisplayName("a LOW priority overdue activity raises no SLA alert")
    void lowPriorityOverdueRaisesNothing() {
        eventBus.publish(new TaskOverdueEvent(
                "task-003", "store-001", "LOW", "user-003", NOW.minusSeconds(3_600), NOW));

        assertThat(raisedAlerts()).isEmpty();
    }

    @Test
    @DisplayName("the alerts module ignores events it did not subscribe to")
    void unrelatedEventIsIgnored() {
        eventBus.publish(new ProgrammeClosedEvent("project-001", "store-001", "user-002", NOW));

        assertThat(raisedAlerts()).isEmpty();
    }
}
