package com.cognizant.storeops.alerts.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.alerts.service.SlaBreachService;
import com.cognizant.storeops.alerts.service.SlaEscalationProperties;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.domain.User;
import com.cognizant.storeops.staff.domain.UserProfile;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeNotificationRepository;
import com.cognizant.storeops.support.FakeSlaBreachRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alerting decision: which operational events deserve an alert, for whom, and of what type.
 *
 * <p>Handlers are invoked directly rather than through the event bus. Dispatch is Spring's
 * responsibility and is verified once, end to end, in {@code EventDeliveryIntegrationTest}; what
 * belongs here is this module's judgement, which is worth testing without a container.
 *
 * <p>Nothing here imports the activities module: an event carrying strings is all the alerts module
 * ever sees, which is what makes the boundary rule hold.
 */
class AlertEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private FakeNotificationRepository notificationRepository;
    private FakeSlaBreachRepository slaBreachRepository;
    private AlertEventListener listener;

    @BeforeEach
    void setUp() {
        notificationRepository = new FakeNotificationRepository();
        slaBreachRepository = new FakeSlaBreachRepository();
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        final NotificationService notificationService =
                new NotificationService(notificationRepository, clock);

        // The real SlaBreachService, not a mock: these tests assert the alert that comes out, and the
        // handler's whole job is to hand the event over. Only the staff module is stubbed.
        final UserService userService = mock(UserService.class);
        when(userService.findById("user-003")).thenReturn(Optional.of(new User(
                "user-003", "lena.brandt@storeops.example", "Lena Brandt", StaffRole.DEPARTMENT_LEAD,
                "store-001", "region-north", true, new UserProfile(null, "GROCERY", "LATE"), NOW)));
        listener = new AlertEventListener(notificationService, new SlaBreachService(
                slaBreachRepository, notificationService, userService,
                new SlaEscalationProperties(Duration.ofHours(2)), clock));
    }

    private List<Notification> raisedAlerts() {
        return notificationRepository.findAll();
    }

    @Test
    @DisplayName("a BLOCKED transition raises an ESCALATION alert for the assignee")
    void blockedTransitionRaisesEscalation() {
        listener.onTaskStatusChanged(new TaskStatusChangedEvent(
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
        listener.onTaskStatusChanged(new TaskStatusChangedEvent(
                "task-001", "store-001", "IN_PROGRESS", "DONE", "HIGH", "user-004", NOW));

        assertThat(raisedAlerts()).isEmpty();
    }

    @Test
    @DisplayName("a DONE transition closes the activity's open SLA breach episode")
    void doneTransitionClosesTheBreachEpisode() {
        listener.onTaskOverdue(new TaskOverdueEvent(
                "task-002", "store-001", "CRITICAL", "user-003", NOW.minusSeconds(3_600), NOW));
        assertThat(slaBreachRepository.findByTaskId("task-002")).isPresent();

        listener.onTaskStatusChanged(new TaskStatusChangedEvent(
                "task-002", "store-001", "IN_PROGRESS", "DONE", "CRITICAL", "user-003", NOW));

        assertThat(slaBreachRepository.findByTaskId("task-002")).isEmpty();
        // The transition itself is not alertable: closing an episode is silent.
        assertThat(raisedAlerts()).hasSize(1);
    }

    @Test
    @DisplayName("a BLOCKED transition still alerts the assignee and closes no episode")
    void blockedTransitionLeavesTheBreachEpisodeAlone() {
        listener.onTaskOverdue(new TaskOverdueEvent(
                "task-002", "store-001", "CRITICAL", "user-003", NOW.minusSeconds(3_600), NOW));

        listener.onTaskStatusChanged(new TaskStatusChangedEvent(
                "task-002", "store-001", "IN_PROGRESS", "BLOCKED", "CRITICAL", "user-003", NOW));

        assertThat(slaBreachRepository.findByTaskId("task-002")).isPresent();
        assertThat(raisedAlerts()).hasSize(2);
        assertThat(raisedAlerts()).anySatisfy(alert -> {
            assertThat(alert.alertType()).isEqualTo(AlertType.ESCALATION);
            assertThat(alert.recipientId()).isEqualTo("user-003");
            assertThat(alert.sourceRef()).isEqualTo("task-002");
        });
    }

    @Test
    @DisplayName("a BLOCKED transition on an unassigned activity raises no alert")
    void blockedUnassignedRaisesNothing() {
        listener.onTaskStatusChanged(new TaskStatusChangedEvent(
                "task-001", "store-001", "TODO", "BLOCKED", "HIGH", null, NOW));

        assertThat(raisedAlerts()).isEmpty();
    }

    @Test
    @DisplayName("a CRITICAL overdue activity raises an SLA_BREACH alert for the responsible lead")
    void criticalOverdueRaisesSlaBreach() {
        // user-003 is a DEPARTMENT_LEAD, so they are their own lead: the handler passes the event on
        // and the service resolves the recipient.
        listener.onTaskOverdue(new TaskOverdueEvent(
                "task-002", "store-001", "CRITICAL", "user-003", NOW.minusSeconds(3_600), NOW));

        assertThat(raisedAlerts()).hasSize(1);
        final Notification alert = raisedAlerts().getFirst();
        assertThat(alert.alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(alert.recipientId()).isEqualTo("user-003");
        assertThat(alert.subject()).contains("CRITICAL");
        assertThat(slaBreachRepository.findByTaskId("task-002")).isPresent();
    }

    @Test
    @DisplayName("the same overdue activity observed twice raises one alert, not two")
    void repeatedOverdueObservationRaisesOneAlert() {
        final TaskOverdueEvent event = new TaskOverdueEvent(
                "task-002", "store-001", "CRITICAL", "user-003", NOW.minusSeconds(3_600), NOW);

        listener.onTaskOverdue(event);
        listener.onTaskOverdue(event);
        listener.onTaskOverdue(event);

        assertThat(raisedAlerts()).hasSize(1);
    }

    @Test
    @DisplayName("a LOW priority overdue activity raises no SLA alert")
    void lowPriorityOverdueRaisesNothing() {
        listener.onTaskOverdue(new TaskOverdueEvent(
                "task-003", "store-001", "LOW", "user-003", NOW.minusSeconds(3_600), NOW));

        assertThat(raisedAlerts()).isEmpty();
    }

    @Test
    @DisplayName("an overdue activity with no assignee raises no SLA alert")
    void overdueUnassignedRaisesNothing() {
        listener.onTaskOverdue(new TaskOverdueEvent(
                "task-004", "store-001", "HIGH", null, NOW.minusSeconds(3_600), NOW));

        assertThat(raisedAlerts()).isEmpty();
    }
}
