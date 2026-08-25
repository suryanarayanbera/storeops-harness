package com.cognizant.storeops.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.domain.SlaBreach;
import com.cognizant.storeops.shared.events.TaskOverdueEvent;
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
 * The alerts module's SLA judgement: who hears about a breach, and how often.
 *
 * <p>{@code UserService} is a Mockito mock rather than a fake because it belongs to another module -
 * the only case {@code how-to-test} sanctions mocking. The repositories are fakes, and the clock is
 * fixed, so every assertion is on real recorded state.
 *
 * <p>Time moves by rebuilding the service on a later clock over the same repositories: the episode is
 * persisted, not held in the service, so a second observation reads what the first wrote. That is the
 * property this test exists to prove.
 */
class SlaBreachServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-01-07T08:00:00Z");

    /** The default grace period, used unless a test is specifically about configuring it. */
    private static final Duration GRACE_PERIOD = Duration.ofHours(2);

    private static final String STORE = "store-001";
    private static final String GROCERY = "GROCERY";

    private FakeSlaBreachRepository breaches;
    private FakeNotificationRepository notifications;
    private UserService userService;
    private SlaBreachService service;

    @BeforeEach
    void setUp() {
        breaches = new FakeSlaBreachRepository();
        notifications = new FakeNotificationRepository();
        userService = mock(UserService.class);
        service = serviceAt(NOW);

        when(userService.findById("user-004")).thenReturn(Optional.of(associate("user-004", GROCERY)));
        when(userService.findByStoreIdAndRole(STORE, StaffRole.DEPARTMENT_LEAD))
                .thenReturn(List.of(lead("user-003", GROCERY, true)));
        when(userService.findByStoreIdAndRole(STORE, StaffRole.STORE_MANAGER))
                .thenReturn(List.of(manager("user-002", true)));
    }

    private SlaBreachService serviceAt(final Instant moment) {
        return serviceAt(moment, GRACE_PERIOD);
    }

    private SlaBreachService serviceAt(final Instant moment, final Duration gracePeriod) {
        final Clock clock = Clock.fixed(moment, ZoneOffset.UTC);
        return new SlaBreachService(breaches, new NotificationService(notifications, clock), userService,
                new SlaEscalationProperties(gracePeriod), clock);
    }

    /** Observes the same breach again at {@code moment}, as a later sweep would. */
    private void observeAgainAt(final Instant moment, final String taskId, final Duration gracePeriod) {
        serviceAt(moment, gracePeriod).observe(overdue(taskId, "HIGH", "user-004", moment));
    }

    private List<Notification> alertsOfType(final AlertType type) {
        return raisedAlerts().stream().filter(alert -> alert.alertType() == type).toList();
    }

    private static User staff(final String id, final StaffRole role, final String department, final boolean active) {
        return new User(id, id + "@storeops.example", "Staff " + id, role, STORE, "region-north", active,
                new UserProfile("+44 20 7000 0000", department, "EARLY"), DUE_AT);
    }

    private static User associate(final String id, final String department) {
        return staff(id, StaffRole.ASSOCIATE, department, true);
    }

    private static User lead(final String id, final String department, final boolean active) {
        return staff(id, StaffRole.DEPARTMENT_LEAD, department, active);
    }

    private static User manager(final String id, final boolean active) {
        return staff(id, StaffRole.STORE_MANAGER, "OPERATIONS", active);
    }

    private static TaskOverdueEvent overdue(final String taskId, final String priority, final String assigneeId) {
        return overdue(taskId, priority, assigneeId, NOW);
    }

    private static TaskOverdueEvent overdue(
            final String taskId, final String priority, final String assigneeId, final Instant observedAt) {
        return new TaskOverdueEvent(taskId, STORE, priority, assigneeId, DUE_AT, observedAt);
    }

    private List<Notification> raisedAlerts() {
        return notifications.findAll();
    }

    private SlaBreach episode(final String taskId) {
        return breaches.findByTaskId(taskId).orElseThrow();
    }

    @Test
    @DisplayName("a first breach notifies the assignee's department lead, not the assignee")
    void firstObservationNotifiesTheDepartmentLead() {
        service.observe(overdue("task-001", "HIGH", "user-004"));

        assertThat(raisedAlerts()).hasSize(1);
        final Notification alert = raisedAlerts().getFirst();
        assertThat(alert.recipientId()).isEqualTo("user-003");
        assertThat(alert.alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(alert.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(alert.sourceRef()).isEqualTo("task-001");
        assertThat(alert.subject()).contains("HIGH");
        assertThat(alert.body()).contains("task-001").contains(STORE);
        assertThat(raisedAlerts()).noneMatch(raised -> "user-004".equals(raised.recipientId()));

        final SlaBreach episode = episode("task-001");
        assertThat(episode.firstBreachAt()).isEqualTo(NOW);
        assertThat(episode.leadRecipientId()).isEqualTo("user-003");
        assertThat(episode.leadNotifiedAt()).isEqualTo(NOW);
        assertThat(episode.storeId()).isEqualTo(STORE);
        assertThat(episode.priority()).isEqualTo("HIGH");
    }

    @Test
    @DisplayName("a repeat observation raises nothing and moves only lastSeenAt")
    void repeatObservationRaisesNothingAndOnlyMovesLastSeen() {
        final Instant later = Instant.parse("2026-02-01T10:05:00Z");
        service.observe(overdue("task-001", "HIGH", "user-004"));

        serviceAt(later).observe(overdue("task-001", "HIGH", "user-004", later));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(episode("task-001").firstBreachAt()).isEqualTo(NOW);
        assertThat(episode("task-001").leadNotifiedAt()).isEqualTo(NOW);
        assertThat(episode("task-001").lastSeenAt()).isEqualTo(later);

        final Instant laterStill = Instant.parse("2026-02-01T10:10:00Z");
        serviceAt(laterStill).observe(overdue("task-001", "HIGH", "user-004", laterStill));
        serviceAt(laterStill).observe(overdue("task-001", "HIGH", "user-004", laterStill));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(episode("task-001").lastSeenAt()).isEqualTo(laterStill);
    }

    @Test
    @DisplayName("an assignee who is themselves a department lead is notified directly")
    void assigneeWhoIsADepartmentLeadIsNotifiedDirectly() {
        when(userService.findById("user-003")).thenReturn(Optional.of(lead("user-003", GROCERY, true)));

        service.observe(overdue("task-500", "CRITICAL", "user-003"));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-003");
        assertThat(raisedAlerts().getFirst().alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(episode("task-500").leadRecipientId()).isEqualTo("user-003");
    }

    @Test
    @DisplayName("with no department lead for the department, the store manager is notified instead")
    void withNoDepartmentLeadTheStoreManagerIsNotified() {
        when(userService.findById("user-900")).thenReturn(Optional.of(associate("user-900", "BAKERY")));

        service.observe(overdue("task-600", "HIGH", "user-900"));

        assertThat(raisedAlerts()).hasSize(1);
        // Still SLA_BREACH: the fallback changes who hears about it, not what happened.
        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-002");
        assertThat(raisedAlerts().getFirst().alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(episode("task-600").leadRecipientId()).isEqualTo("user-002");
    }

    @Test
    @DisplayName("an assignee with no department at all falls back to the store manager")
    void assigneeWithNoDepartmentFallsBackToTheStoreManager() {
        when(userService.findById("user-901")).thenReturn(Optional.of(associate("user-901", null)));

        service.observe(overdue("task-601", "CRITICAL", "user-901"));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-002");
        assertThat(episode("task-601").leadRecipientId()).isEqualTo("user-002");
    }

    @Test
    @DisplayName("an inactive department lead is skipped in favour of the store manager")
    void inactiveDepartmentLeadIsSkipped() {
        when(userService.findByStoreIdAndRole(STORE, StaffRole.DEPARTMENT_LEAD))
                .thenReturn(List.of(lead("user-003", GROCERY, false)));

        service.observe(overdue("task-001", "HIGH", "user-004"));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-002");
        assertThat(raisedAlerts()).noneMatch(raised -> "user-003".equals(raised.recipientId()));
    }

    @Test
    @DisplayName("an unresolvable assignee alerts nobody and records no episode")
    void unresolvableAssigneeAlertsNobodyAndRecordsNothing() {
        when(userService.findById("user-does-not-exist")).thenReturn(Optional.empty());

        service.observe(overdue("task-700", "HIGH", "user-does-not-exist"));
        service.observe(overdue("task-701", "HIGH", null));
        service.observe(overdue("task-702", "CRITICAL", "   "));

        assertThat(raisedAlerts()).isEmpty();
        assertThat(breaches.findAll()).isEmpty();
    }

    @Test
    @DisplayName("a store with neither lead nor manager records nothing, so a later sweep can still alert")
    void storeWithNoLeadAndNoManagerIsRetriedRatherThanSwallowed() {
        when(userService.findById("user-950")).thenReturn(Optional.of(associate("user-950", GROCERY)));
        when(userService.findByStoreIdAndRole(STORE, StaffRole.DEPARTMENT_LEAD)).thenReturn(List.of());
        when(userService.findByStoreIdAndRole(STORE, StaffRole.STORE_MANAGER)).thenReturn(List.of());

        service.observe(overdue("task-800", "HIGH", "user-950"));

        assertThat(raisedAlerts()).isEmpty();
        // The absence of the row is what lets the retry alert once staff data is corrected.
        assertThat(breaches.findByTaskId("task-800")).isEmpty();

        when(userService.findByStoreIdAndRole(STORE, StaffRole.STORE_MANAGER))
                .thenReturn(List.of(manager("user-002", true)));
        service.observe(overdue("task-800", "HIGH", "user-950"));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-002");
        assertThat(episode("task-800").leadRecipientId()).isEqualTo("user-002");
    }

    @Test
    @DisplayName("a priority outside the SLA bands is ignored, and the tracked bands are not")
    void priorityOutsideTheSlaBandsIsIgnored() {
        service.observe(overdue("task-900", "MEDIUM", "user-004"));
        service.observe(overdue("task-901", "LOW", "user-004"));

        assertThat(raisedAlerts()).isEmpty();
        assertThat(breaches.findAll()).isEmpty();

        service.observe(overdue("task-902", "HIGH", "user-004"));
        service.observe(overdue("task-903", "CRITICAL", "user-004"));

        assertThat(raisedAlerts()).hasSize(2);
        assertThat(breaches.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("two leads in one department resolve deterministically to the lowest id")
    void tiedLeadsResolveToTheLowestId() {
        when(userService.findByStoreIdAndRole(STORE, StaffRole.DEPARTMENT_LEAD))
                .thenReturn(List.of(lead("user-030", GROCERY, true), lead("user-003", GROCERY, true)));

        service.observe(overdue("task-001", "HIGH", "user-004"));

        assertThat(raisedAlerts().getFirst().recipientId()).isEqualTo("user-003");
    }

    // ---------------------------------------------------------------- escalation

    @Test
    @DisplayName("a breach still observed after the grace period escalates to the store manager")
    void breachStillObservedAfterTheGracePeriodEscalates() {
        final Instant afterGrace = NOW.plus(GRACE_PERIOD);
        service.observe(overdue("task-001", "HIGH", "user-004"));

        observeAgainAt(afterGrace, "task-001", GRACE_PERIOD);

        assertThat(raisedAlerts()).hasSize(2);
        final Notification escalation = alertsOfType(AlertType.ESCALATION).getFirst();
        assertThat(escalation.recipientId()).isEqualTo("user-002");
        assertThat(escalation.sourceRef()).isEqualTo("task-001");
        assertThat(escalation.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(escalation.subject()).isEqualTo("Escalated: SLA breach unresolved on HIGH activity");
        assertThat(escalation.body()).contains("task-001").contains("user-003");

        final SlaBreach episode = episode("task-001");
        assertThat(episode.escalationRecipientId()).isEqualTo("user-002");
        assertThat(episode.escalatedAt()).isEqualTo(afterGrace);
        // The first half of the episode is untouched by escalating it.
        assertThat(episode.firstBreachAt()).isEqualTo(NOW);
        assertThat(episode.leadRecipientId()).isEqualTo("user-003");
        assertThat(episode.leadNotifiedAt()).isEqualTo(NOW);
        assertThat(alertsOfType(AlertType.SLA_BREACH)).hasSize(1);
    }

    @Test
    @DisplayName("before the grace period elapses, an observation raises nothing")
    void beforeTheGracePeriodElapsesNothingIsRaised() {
        final Instant justInsideGrace = NOW.plus(GRACE_PERIOD).minusSeconds(60);
        service.observe(overdue("task-001", "HIGH", "user-004"));

        observeAgainAt(justInsideGrace, "task-001", GRACE_PERIOD);

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(alertsOfType(AlertType.ESCALATION)).isEmpty();
        assertThat(episode("task-001").escalatedAt()).isNull();
        assertThat(episode("task-001").lastSeenAt()).isEqualTo(justInsideGrace);
    }

    @Test
    @DisplayName("a first observation never escalates, even with a zero grace period")
    void firstObservationNeverEscalates() {
        serviceAt(NOW, Duration.ZERO).observe(overdue("task-001", "HIGH", "user-004"));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(raisedAlerts().getFirst().alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(episode("task-001").escalatedAt()).isNull();

        // Escalation is a statement about persistence, so it needs a second observation - not a
        // second instant.
        observeAgainAt(NOW, "task-001", Duration.ZERO);

        assertThat(alertsOfType(AlertType.ESCALATION)).singleElement()
                .satisfies(escalation -> assertThat(escalation.recipientId()).isEqualTo("user-002"));
    }

    @Test
    @DisplayName("escalation happens once, however many observations follow")
    void escalationHappensOnlyOnce() {
        final Instant escalatedAt = NOW.plus(GRACE_PERIOD);
        service.observe(overdue("task-001", "HIGH", "user-004"));
        observeAgainAt(escalatedAt, "task-001", GRACE_PERIOD);

        observeAgainAt(NOW.plusSeconds(3 * 3_600), "task-001", GRACE_PERIOD);
        observeAgainAt(NOW.plusSeconds(4 * 3_600), "task-001", GRACE_PERIOD);
        observeAgainAt(NOW.plusSeconds(24 * 3_600), "task-001", GRACE_PERIOD);

        assertThat(raisedAlerts()).hasSize(2);
        assertThat(episode("task-001").escalatedAt()).isEqualTo(escalatedAt);
    }

    @Test
    @DisplayName("the same person is never told twice about one activity")
    void escalationToTheAlreadyNotifiedRecipientRaisesNothing() {
        // No lead for BAKERY, so the store manager received the SLA_BREACH itself.
        when(userService.findById("user-900")).thenReturn(Optional.of(associate("user-900", "BAKERY")));
        final Instant afterGrace = NOW.plus(GRACE_PERIOD);
        service.observe(overdue("task-600", "HIGH", "user-900"));
        assertThat(episode("task-600").leadRecipientId()).isEqualTo("user-002");

        serviceAt(afterGrace, GRACE_PERIOD).observe(overdue("task-600", "HIGH", "user-900", afterGrace));

        assertThat(raisedAlerts()).hasSize(1);
        assertThat(alertsOfType(AlertType.ESCALATION)).isEmpty();
        // Marked escalated regardless, so the check is not repeated on every later sweep.
        assertThat(episode("task-600").escalatedAt()).isEqualTo(afterGrace);
        assertThat(episode("task-600").escalationRecipientId()).isEqualTo("user-002");
    }

    @Test
    @DisplayName("with no store manager, escalation is retried rather than lost")
    void escalationWithNoStoreManagerIsRetried() {
        when(userService.findByStoreIdAndRole(STORE, StaffRole.STORE_MANAGER)).thenReturn(List.of());
        service.observe(overdue("task-001", "HIGH", "user-004"));

        observeAgainAt(NOW.plus(GRACE_PERIOD), "task-001", GRACE_PERIOD);

        assertThat(alertsOfType(AlertType.ESCALATION)).isEmpty();
        assertThat(episode("task-001").escalatedAt()).isNull();

        when(userService.findByStoreIdAndRole(STORE, StaffRole.STORE_MANAGER))
                .thenReturn(List.of(manager("user-002", true)));
        final Instant laterStill = NOW.plusSeconds(3 * 3_600);
        observeAgainAt(laterStill, "task-001", GRACE_PERIOD);

        assertThat(alertsOfType(AlertType.ESCALATION)).singleElement()
                .satisfies(escalation -> assertThat(escalation.recipientId()).isEqualTo("user-002"));
        assertThat(episode("task-001").escalatedAt()).isEqualTo(laterStill);
    }

    @Test
    @DisplayName("the grace period is configurable, and decides the outcome for one clock")
    void gracePeriodIsConfigurable() {
        final Instant threeHoursLater = NOW.plusSeconds(3 * 3_600);
        service.observe(overdue("task-001", "HIGH", "user-004"));

        observeAgainAt(threeHoursLater, "task-001", Duration.ofHours(48));
        assertThat(alertsOfType(AlertType.ESCALATION)).isEmpty();

        observeAgainAt(threeHoursLater, "task-001", Duration.ofHours(1));
        assertThat(alertsOfType(AlertType.ESCALATION)).hasSize(1);
    }

    // ---------------------------------------------------------------- closure

    @Test
    @DisplayName("reaching DONE closes the episode, and a later breach starts a fresh one")
    void closingAnEpisodePreventsEscalationAndStartsOverOnTheNextBreach() {
        final Instant fiveHoursLater = NOW.plusSeconds(5 * 3_600);
        service.observe(overdue("task-001", "HIGH", "user-004"));

        service.closeEpisode("task-001");
        assertThat(breaches.findByTaskId("task-001")).isEmpty();

        observeAgainAt(fiveHoursLater, "task-001", GRACE_PERIOD);

        // Treated as a first observation: a new episode and a fresh lead alert, no escalation despite
        // five hours having passed since the original breach.
        assertThat(alertsOfType(AlertType.ESCALATION)).isEmpty();
        assertThat(alertsOfType(AlertType.SLA_BREACH)).hasSize(2);
        assertThat(episode("task-001").firstBreachAt()).isEqualTo(fiveHoursLater);
        assertThat(episode("task-001").leadRecipientId()).isEqualTo("user-003");
        assertThat(episode("task-001").escalatedAt()).isNull();
    }

    @Test
    @DisplayName("closing an episode that does not exist raises nothing and throws nothing")
    void closingAnUnknownEpisodeIsANoOp() {
        service.closeEpisode("task-never-breached");
        service.closeEpisode(null);

        assertThat(raisedAlerts()).isEmpty();
        assertThat(breaches.findAll()).isEmpty();
    }
}
