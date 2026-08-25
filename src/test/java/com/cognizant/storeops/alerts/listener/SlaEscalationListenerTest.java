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
 * Stage two of SLA breach alerting: escalation to the store manager once the grace period has run.
 *
 * <p>Split from {@code AlertEventListenerTest} because the two stages have different fixtures. Stage
 * one starts from an empty repository; stage two only happens when a breach alert already exists, and
 * every assertion here turns on how old that alert is relative to a pinned clock.
 *
 * <p>Time is always a fixed {@code Clock} and breach ages are always expressed relative to it. That
 * is the only way Scenarios 2, 3 and 4 can exist at all: a direct {@code Instant.now()} in the
 * listener would make the grace boundary untestable, and it would not show up in Checkstyle.
 */
class SlaEscalationListenerTest {

    /**
     * The project's canonical test instant. Breach ages below are relative to it rather than absolute,
     * so the arithmetic stays legible and the class keeps one notion of "now".
     */
    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private static final Instant DUE_AT = Instant.parse("2026-01-07T08:00:00Z");
    private static final Duration GRACE = Duration.ofHours(4);

    private FakeNotificationRepository notificationRepository;
    private FakeUserRepository userRepository;

    @BeforeEach
    void setUp() {
        notificationRepository = new FakeNotificationRepository();
        userRepository = new FakeUserRepository().withSeedRoster();
    }

    private AlertEventListener listenerWithGrace(final Duration gracePeriod) {
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new AlertEventListener(
                new NotificationService(notificationRepository, clock),
                new UserService(userRepository),
                new SlaAlertProperties(gracePeriod),
                clock);
    }

    private AlertEventListener listener() {
        return listenerWithGrace(GRACE);
    }

    /** The stage-one alert an earlier sweep would have left, raised {@code age} ago. */
    private Notification breachAlertRaised(final Duration age) {
        return notificationRepository.save(new Notification(
                "notification-breach", "user-003", AlertType.SLA_BREACH, NotificationChannel.IN_APP,
                NotificationStatus.PENDING, "SLA breach", "Raised on an earlier sweep.",
                "task-001", NOW.minus(age), null));
    }

    private static TaskOverdueEvent seedBreach() {
        return new TaskOverdueEvent("task-001", "store-001", "HIGH", "user-004", DUE_AT, NOW);
    }

    private List<Notification> raisedAlerts() {
        return notificationRepository.findAll();
    }

    private List<Notification> slaEscalations() {
        return notificationRepository.findAll().stream()
                .filter(alert -> alert.alertType() == AlertType.ESCALATION)
                .filter(alert -> "SLA breach escalated".equals(alert.subject()))
                .toList();
    }

    // ------------------------------------------------------------------ AC 1

    @Test
    @DisplayName("a breach older than the grace period escalates to the store manager")
    void agedBreachEscalatesToTheStoreManager() {
        final Notification original = breachAlertRaised(Duration.ofHours(5));

        listener().onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(2);
        assertThat(slaEscalations()).singleElement().satisfies(escalation -> {
            assertThat(escalation.alertType()).isEqualTo(AlertType.ESCALATION);
            assertThat(escalation.recipientId()).isEqualTo("user-002");
            assertThat(escalation.subject()).isEqualTo("SLA breach escalated");
            assertThat(escalation.sourceRef()).isEqualTo("task-001");
            assertThat(escalation.status()).isEqualTo(NotificationStatus.PENDING);
            assertThat(escalation.body())
                    .contains("task-001")
                    .contains("store-001")
                    .contains("HIGH");
        });

        // The stage-one alert is untouched: escalating adds a row, it does not move one.
        assertThat(notificationRepository.findById(original.id())).get().satisfies(breach -> {
            assertThat(breach.recipientId()).isEqualTo("user-003");
            assertThat(breach.createdAt()).isEqualTo(original.createdAt());
            assertThat(breach.alertType()).isEqualTo(AlertType.SLA_BREACH);
        });
    }

    // ------------------------------------------------------------------ AC 2

    @Test
    @DisplayName("a breach inside the grace period does not escalate")
    void breachInsideTheGracePeriodDoesNotEscalate() {
        breachAlertRaised(Duration.ofHours(3));

        listener().onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(slaEscalations()).isEmpty();
    }

    // ------------------------------------------------------------------ AC 3

    @Test
    @DisplayName("a breach exactly one grace period old escalates - the boundary is inclusive")
    void theGraceBoundaryIsInclusive() {
        breachAlertRaised(GRACE);

        listener().onTaskOverdue(seedBreach());

        assertThat(slaEscalations()).hasSize(1);
    }

    @Test
    @DisplayName("a breach one second short of the grace period does not escalate")
    void oneSecondShortOfTheGraceBoundaryDoesNotEscalate() {
        breachAlertRaised(GRACE.minusSeconds(1));

        listener().onTaskOverdue(seedBreach());

        assertThat(slaEscalations()).isEmpty();
        assertThat(raisedAlerts()).hasSize(1);
    }

    // ------------------------------------------------------------------ AC 4

    @Test
    @DisplayName("a zero grace period escalates on the very next observation")
    void aZeroGracePeriodEscalatesImmediately() {
        breachAlertRaised(Duration.ZERO);

        listenerWithGrace(Duration.ZERO).onTaskOverdue(seedBreach());

        assertThat(slaEscalations()).singleElement()
                .satisfies(escalation -> assertThat(escalation.recipientId()).isEqualTo("user-002"));
    }

    // ------------------------------------------------------------------ AC 5

    @Test
    @DisplayName("escalation happens once, however many sweeps follow")
    void escalationHappensOnce() {
        final Notification breach = breachAlertRaised(Duration.ofHours(5));
        final AlertEventListener listener = listener();

        listener.onTaskOverdue(seedBreach());
        final Notification escalation = slaEscalations().getFirst();

        listener.onTaskOverdue(seedBreach());
        listener.onTaskOverdue(seedBreach());
        listener.onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(2);
        assertThat(notificationRepository.findById(breach.id())).get()
                .extracting(Notification::createdAt).isEqualTo(breach.createdAt());
        assertThat(notificationRepository.findById(escalation.id())).get()
                .extracting(Notification::createdAt).isEqualTo(escalation.createdAt());
    }

    // ------------------------------------------------------------------ AC 6

    @Test
    @DisplayName("a blocked-activity ESCALATION does not suppress the SLA escalation")
    void aBlockedActivityEscalationDoesNotSuppressTheSlaEscalation() {
        breachAlertRaised(Duration.ofHours(5));
        // What onTaskStatusChanged writes when an activity is blocked: same alertType, same
        // sourceRef, different subject. A stage-two check keyed on alertType alone would read this
        // as "already escalated" and this activity would never reach its store manager - silently,
        // and only for activities that had been blocked, which are the likeliest to breach.
        notificationRepository.save(new Notification(
                "notification-blocked", "user-004", AlertType.ESCALATION, NotificationChannel.IN_APP,
                NotificationStatus.PENDING, "Activity blocked", "Moved from TODO to BLOCKED.",
                "task-001", NOW.minus(Duration.ofHours(6)), null));

        listener().onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(3);
        assertThat(slaEscalations()).singleElement()
                .satisfies(escalation -> assertThat(escalation.recipientId()).isEqualTo("user-002"));
    }

    @Test
    @DisplayName("a blocked-activity ESCALATION does not suppress the stage-one breach either")
    void aBlockedActivityEscalationDoesNotSuppressTheBreach() {
        // Sprint 4's carried finding: stage one reads by sourceRef AND alertType, but nothing at this
        // layer proved it discriminated. If it narrowed to sourceRef alone, a previously-blocked
        // activity would never get its SLA_BREACH at all.
        notificationRepository.save(new Notification(
                "notification-blocked", "user-004", AlertType.ESCALATION, NotificationChannel.IN_APP,
                NotificationStatus.PENDING, "Activity blocked", "Moved from TODO to BLOCKED.",
                "task-001", NOW.minus(Duration.ofHours(6)), null));

        listener().onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(2);
        assertThat(raisedAlerts())
                .filteredOn(alert -> alert.alertType() == AlertType.SLA_BREACH)
                .singleElement()
                .satisfies(breach -> {
                    assertThat(breach.recipientId()).isEqualTo("user-003");
                    assertThat(breach.subject()).isEqualTo("SLA breach");
                });
    }

    // ------------------------------------------------------------------ AC 7

    @Test
    @DisplayName("a first observation raises stage one only, even with a zero grace period")
    void aFirstObservationRaisesStageOneOnly() {
        listenerWithGrace(Duration.ZERO).onTaskOverdue(seedBreach());

        assertThat(raisedAlerts()).hasSize(1);
        final Notification only = raisedAlerts().getFirst();
        assertThat(only.alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(only.recipientId()).isEqualTo("user-003");
        // A zero grace period must not collapse both stages into a single sweep: the lead has to be
        // told before the manager can be told the lead did not act.
        assertThat(slaEscalations()).isEmpty();
    }

    // ------------------------------------------------------------------ AC 8

    @Test
    @DisplayName("a store with no store manager escalates to nobody, silently")
    void noStoreManagerIsSilent() {
        final Notification breach = notificationRepository.save(new Notification(
                "notification-breach", "user-003", AlertType.SLA_BREACH, NotificationChannel.IN_APP,
                NotificationStatus.PENDING, "SLA breach", "Raised on an earlier sweep.",
                "task-007", NOW.minus(Duration.ofHours(5)), null));

        // store-003 has no staff at all. Reaching NotificationService.raise with a null recipient
        // would throw ValidationError out of an after-commit handler, where nobody would see it.
        assertThatCode(() -> listener().onTaskOverdue(
                new TaskOverdueEvent("task-007", "store-003", "CRITICAL", "user-004", DUE_AT, NOW)))
                .doesNotThrowAnyException();

        assertThat(slaEscalations()).isEmpty();
        assertThat(raisedAlerts()).hasSize(1);
        assertThat(notificationRepository.findById(breach.id())).get()
                .extracting(Notification::createdAt).isEqualTo(breach.createdAt());
    }

    // ------------------------------------------------------------------ additional

    @Test
    @DisplayName("escalation goes to the lowest-id active store manager")
    void escalationPicksTheLowestIdActiveStoreManager() {
        userRepository
                .with("user-020", StaffRole.STORE_MANAGER, "store-001", "OPERATIONS")
                .with("user-000", StaffRole.STORE_MANAGER, "store-001", "OPERATIONS", false);
        breachAlertRaised(Duration.ofHours(5));

        listener().onTaskOverdue(seedBreach());

        // user-000 sorts first but is inactive; user-002 is the lowest-id active manager.
        assertThat(slaEscalations()).singleElement()
                .satisfies(escalation -> assertThat(escalation.recipientId()).isEqualTo("user-002"));
    }

    @Test
    @DisplayName("an untracked priority never reaches the escalation stage")
    void untrackedPriorityNeverEscalates() {
        breachAlertRaised(Duration.ofHours(5));

        listener().onTaskOverdue(new TaskOverdueEvent("task-001", "store-001", "MEDIUM", "user-004", DUE_AT, NOW));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(slaEscalations()).isEmpty();
    }
}
