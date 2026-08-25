package com.cognizant.storeops.alerts.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationChannel;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.alerts.service.SlaAlertProperties;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import com.cognizant.storeops.staff.domain.StaffRole;
import com.cognizant.storeops.staff.service.UserService;
import com.cognizant.storeops.support.FakeNotificationRepository;
import com.cognizant.storeops.support.FakeUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The alerting decision: which operational events deserve an alert, for whom, and of what type.
 *
 * <p>Handlers are invoked directly rather than through the event bus. Dispatch is Spring's
 * responsibility and is verified once, end to end, in {@code SlaBreachAlertingIntegrationTest}; what
 * belongs here is this module's judgement, which is worth testing without a container.
 *
 * <p>Nothing here imports the activities module: an event carrying strings is all the alerts module
 * ever sees, which is what makes the boundary rule hold.
 *
 * <p>{@code UserService} is a real service over {@link FakeUserRepository} rather than a Mockito
 * mock, which is a departure from the usual "mock the other module's read" rule and a deliberate one.
 * The criteria under test here are roster-dependent - skip the leaver, skip the wrong department,
 * take the lowest id of two candidates - so stubbing {@code findByStoreIdAndRole} would assert the
 * stub's ordering rather than the system's, which is exactly the weak test the rubric warns about.
 */
class AlertEventListenerTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-01-07T08:00:00Z");

    private FakeNotificationRepository notificationRepository;
    private FakeUserRepository userRepository;
    private AlertEventListener listener;

    /**
     * Four hours, the shipped default. Long enough that every fixture in this class sits inside it,
     * so nothing here trips into the escalation stage - that stage has its own test class.
     */
    private static final Duration GRACE = Duration.ofHours(4);

    @BeforeEach
    void setUp() {
        notificationRepository = new FakeNotificationRepository();
        userRepository = new FakeUserRepository();
        listener = listenerOn(notificationRepository);
    }

    private AlertEventListener listenerOn(final FakeNotificationRepository repository) {
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new AlertEventListener(
                new NotificationService(repository, clock),
                new UserService(userRepository),
                new SlaAlertProperties(GRACE),
                clock);
    }

    private List<Notification> raisedAlerts() {
        return notificationRepository.findAll();
    }

    /** An overdue event for {@code task-001}: HIGH, store-001, assigned to the GROCERY associate. */
    private static TaskOverdueEvent seedBreach() {
        return new TaskOverdueEvent("task-001", "store-001", "HIGH", "user-004", DUE_AT, NOW);
    }

    private static TaskOverdueEvent breach(
            final String taskId, final String storeId, final String priority, final String assigneeId) {
        return new TaskOverdueEvent(taskId, storeId, priority, assigneeId, DUE_AT, NOW);
    }

    /** Pre-existing first-stage alert, as an earlier sweep would have left it. */
    private Notification existingBreachAlert(final String taskId, final String recipientId) {
        return notificationRepository.save(new Notification(
                "notification-existing-" + taskId, recipientId, AlertType.SLA_BREACH,
                NotificationChannel.IN_APP, NotificationStatus.PENDING, "SLA breach",
                "Raised on an earlier sweep.", taskId, NOW.minusSeconds(600), null));
    }

    // ------------------------------------------------------------------ status change, unchanged

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
    @DisplayName("a BLOCKED transition on an unassigned activity raises no alert")
    void blockedUnassignedRaisesNothing() {
        listener.onTaskStatusChanged(new TaskStatusChangedEvent(
                "task-001", "store-001", "TODO", "BLOCKED", "HIGH", null, NOW));

        assertThat(raisedAlerts()).isEmpty();
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("a breach alerts the assignee's department lead, not the assignee")
    void breachAlertsTheDepartmentLeadNotTheAssignee() {
        userRepository.withSeedRoster();

        listener.onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(1);
        final Notification alert = raisedAlerts().getFirst();
        assertThat(alert.recipientId()).isEqualTo("user-003");
        assertThat(alert.recipientId()).isNotEqualTo("user-004");
        assertThat(alert.alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(alert.channel()).isEqualTo(NotificationChannel.IN_APP);
        assertThat(alert.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(alert.subject()).isEqualTo("SLA breach");
        assertThat(alert.sourceRef()).isEqualTo("task-001");
        assertThat(alert.body())
                .contains("task-001")
                .contains("store-001")
                .contains("HIGH")
                .contains(DUE_AT.toString());
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName("a repeat sweep raises nothing and leaves the original alert untouched")
    void repeatSweepRaisesNothing() {
        userRepository.withSeedRoster();
        final Notification original = existingBreachAlert("task-001", "user-003");

        listener.onTaskOverdue(seedBreach());
        listener.onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(1);
        final Notification survivor = raisedAlerts().getFirst();
        // Same id and createdAt: suppression, not a silent overwrite.
        assertThat(survivor.id()).isEqualTo(original.id());
        assertThat(survivor.createdAt()).isEqualTo(original.createdAt());
    }

    // ------------------------------------------------------------------ AC 3

    @Test
    @DisplayName("de-duplication keys on the activity, so a second activity still breaches")
    void aDifferentActivityIsNotSuppressed() {
        userRepository.withSeedRoster();
        existingBreachAlert("task-001", "user-003");

        listener.onTaskOverdue(breach("task-009", "store-001", "CRITICAL", "user-004"));

        assertThat(raisedAlerts()).hasSize(2);
        assertThat(raisedAlerts())
                .filteredOn(alert -> "task-009".equals(alert.sourceRef()))
                .singleElement()
                .satisfies(alert -> {
                    assertThat(alert.alertType()).isEqualTo(AlertType.SLA_BREACH);
                    assertThat(alert.recipientId()).isEqualTo("user-003");
                });
    }

    // ------------------------------------------------------------------ AC 4

    @Test
    @DisplayName("MEDIUM and LOW priority breaches raise nothing")
    void untrackedPrioritiesRaiseNothing() {
        userRepository.withSeedRoster();

        listener.onTaskOverdue(breach("task-002", "store-001", "MEDIUM", "user-004"));
        assertThat(raisedAlerts()).isEmpty();

        listener.onTaskOverdue(breach("task-004", "store-001", "LOW", "user-004"));
        assertThat(raisedAlerts()).isEmpty();
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("a store with no department lead falls back to its store manager")
    void noDepartmentLeadFallsBackToTheStoreManager() {
        userRepository.with("user-005", StaffRole.STORE_MANAGER, "store-002", "OPERATIONS");

        listener.onTaskOverdue(breach("task-004", "store-002", "CRITICAL", "user-005"));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-005");
    }

    // ------------------------------------------------------------------ AC 6

    @Test
    @DisplayName("a breach with no determinable department reaches the store manager")
    void undeterminableDepartmentReachesTheStoreManager() {
        userRepository.withSeedRoster();

        // Null, blank, and an assignee the staff module does not know: all three leave the department
        // unknown, and none of them may throw out of an after-commit handler.
        for (final String assigneeId : new String[] {null, "  ", "user-999"}) {
            notificationRepository = new FakeNotificationRepository();
            final AlertEventListener freshListener = listenerOn(notificationRepository);

            assertThatCode(() -> freshListener.onTaskOverdue(
                    breach("task-001", "store-001", "HIGH", assigneeId))).doesNotThrowAnyException();

            assertThat(raisedAlerts()).hasSize(1);
            assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-002");
            assertThat(raisedAlerts().getFirst().alertType()).isEqualTo(AlertType.SLA_BREACH);
        }
    }

    // ------------------------------------------------------------------ AC 7

    @Test
    @DisplayName("a store with nobody to tell is silent, and raises no ValidationError")
    void noResolvableRecipientIsSilent() {
        userRepository.withSeedRoster();

        // store-003 has no staff at all, so recipient resolution yields null. Reaching
        // NotificationService.raise with that would throw ValidationError; it must not be reached.
        assertThatCode(() -> listener.onTaskOverdue(breach("task-007", "store-003", "CRITICAL", "user-004")))
                .doesNotThrowAnyException();

        assertThat(raisedAlerts()).isEmpty();
    }

    // ------------------------------------------------------------------ AC 8

    @Test
    @DisplayName("an inactive lead and a wrong-department lead are both skipped")
    void inactiveAndWrongDepartmentLeadsAreSkipped() {
        userRepository.withSeedRoster()
                .with("user-010", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY", false)
                .with("user-011", StaffRole.DEPARTMENT_LEAD, "store-001", "OPERATIONS");

        listener.onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId())
                .isEqualTo("user-003")
                .isNotEqualTo("user-010")
                .isNotEqualTo("user-011");
    }

    @Test
    @DisplayName("with the only active department lead gone, the fallback is the store manager")
    void withoutTheActiveLeadTheFallbackIsTheStoreManager() {
        userRepository.withSeedRoster()
                .with("user-010", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY", false)
                .with("user-011", StaffRole.DEPARTMENT_LEAD, "store-001", "OPERATIONS");
        userRepository.remove("user-003");

        listener.onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId())
                .isEqualTo("user-002")
                .isNotEqualTo("user-010")
                .isNotEqualTo("user-011");
    }

    // ------------------------------------------------------------------ AC 9

    @Test
    @DisplayName("two candidate leads resolve to the lowest id")
    void twoCandidateLeadsResolveToTheLowestId() {
        userRepository.withSeedRoster().with("user-007", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY");

        listener.onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-003");
    }

    @Test
    @DisplayName("the lowest id wins whichever order the roster was built in")
    void candidateOrderDoesNotChangeTheOutcome() {
        // Same two candidates, inserted the other way round. FakeUserRepository preserves insertion
        // order, so this fails if the service relies on it instead of sorting.
        userRepository
                .with("user-007", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY")
                .with("user-003", StaffRole.DEPARTMENT_LEAD, "store-001", "GROCERY")
                .with("user-002", StaffRole.STORE_MANAGER, "store-001", "OPERATIONS")
                .with("user-004", StaffRole.ASSOCIATE, "store-001", "GROCERY");

        listener.onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-003");
    }
}
