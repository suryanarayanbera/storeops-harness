package com.cognizant.storeops.alerts.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationChannel;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import com.cognizant.storeops.support.FakeNotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Service-layer test for the alerts module.
 *
 * <p>Covers the de-duplication read the SLA breach listener depends on, and the recipient guard on
 * {@code raise}. That guard matters more than it looks: the listener resolves a recipient that can
 * legitimately come back null, and reaching {@code raise} with it would throw a
 * {@code ValidationError} out of an after-commit handler, where Spring swallows it and nobody hears.
 * The listener is written never to get there; this class pins what would happen if it did.
 */
class NotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private FakeNotificationRepository notificationRepository;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        notificationRepository = new FakeNotificationRepository();
        notificationService = new NotificationService(
                notificationRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Notification seedAlert(
            final String id, final String taskId, final AlertType alertType, final Instant createdAt) {
        return notificationRepository.save(new Notification(
                id, "user-003", alertType, NotificationChannel.IN_APP, NotificationStatus.PENDING,
                "Seeded", "Seeded body", taskId, createdAt, null));
    }

    @Test
    @DisplayName("raise stores a PENDING in-app alert stamped with the fixed clock")
    void raiseStoresAPendingAlert() {
        final Notification raised = notificationService.raise(
                "user-003", AlertType.SLA_BREACH, "SLA breach", "Body text", "task-001");

        assertThat(raised.recipientId()).isEqualTo("user-003");
        assertThat(raised.alertType()).isEqualTo(AlertType.SLA_BREACH);
        assertThat(raised.channel()).isEqualTo(NotificationChannel.IN_APP);
        assertThat(raised.status()).isEqualTo(NotificationStatus.PENDING);
        assertThat(raised.sourceRef()).isEqualTo("task-001");
        assertThat(raised.createdAt()).isEqualTo(NOW);
        assertThat(raised.sentAt()).isNull();
        assertThat(notificationRepository.findById(raised.id())).contains(raised);
    }

    @Test
    @DisplayName("raise rejects a null recipient as a typed ValidationError")
    void raiseRejectsANullRecipient() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> notificationService.raise(
                        null, AlertType.SLA_BREACH, "SLA breach", "Body", "task-001"))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED");
                    assertThat(error.getStatusCode()).isEqualTo(400);
                    assertThat(error.getDetails()).hasSize(1);
                });
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("raise rejects a blank recipient too, not just a null one")
    void raiseRejectsABlankRecipient() {
        assertThatExceptionOfType(ValidationError.class)
                .isThrownBy(() -> notificationService.raise(
                        "   ", AlertType.ESCALATION, "Subject", "Body", "task-001"))
                .satisfies(error -> assertThat(error.getCode()).isEqualTo("VALIDATION_FAILED"));
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("findBySourceRefAndAlertType returns matching alerts oldest first")
    void findBySourceRefAndAlertTypeReturnsOldestFirst() {
        // Saved newest first, so insertion order is the wrong answer.
        seedAlert("notification-late", "task-001", AlertType.SLA_BREACH, NOW);
        seedAlert("notification-early", "task-001", AlertType.SLA_BREACH, NOW.minusSeconds(3_600));

        assertThat(notificationService.findBySourceRefAndAlertType("task-001", AlertType.SLA_BREACH))
                .extracting(Notification::id)
                .containsExactly("notification-early", "notification-late");
    }

    @Test
    @DisplayName("findBySourceRefAndAlertType keys on both the source and the type")
    void findBySourceRefAndAlertTypeFiltersOnBothCriteria() {
        seedAlert("notification-breach", "task-001", AlertType.SLA_BREACH, NOW);
        seedAlert("notification-escalation", "task-001", AlertType.ESCALATION, NOW);
        seedAlert("notification-other-task", "task-009", AlertType.SLA_BREACH, NOW);

        assertThat(notificationService.findBySourceRefAndAlertType("task-001", AlertType.SLA_BREACH))
                .extracting(Notification::id).containsExactly("notification-breach");
        assertThat(notificationService.findBySourceRefAndAlertType("task-001", AlertType.ESCALATION))
                .extracting(Notification::id).containsExactly("notification-escalation");
        assertThat(notificationService.findBySourceRefAndAlertType("task-002", AlertType.SLA_BREACH))
                .isEmpty();
    }

    @Test
    @DisplayName("findBySourceRefAndAlertType tolerates null criteria by returning empty")
    void findBySourceRefAndAlertTypeToleratesNulls() {
        seedAlert("notification-breach", "task-001", AlertType.SLA_BREACH, NOW);

        assertThat(notificationService.findBySourceRefAndAlertType(null, AlertType.SLA_BREACH)).isEmpty();
        assertThat(notificationService.findBySourceRefAndAlertType("task-001", null)).isEmpty();
    }

    @Test
    @DisplayName("getById raises a typed NotFoundError for an unknown id")
    void getByIdRaisesTypedNotFound() {
        assertThatExceptionOfType(NotFoundError.class)
                .isThrownBy(() -> notificationService.getById("notification-999"))
                .satisfies(error -> {
                    assertThat(error.getCode()).isEqualTo("NOTIFICATION_NOT_FOUND");
                    assertThat(error.getStatusCode()).isEqualTo(404);
                });
    }

    @Test
    @DisplayName("markSent moves an alert to SENT and stamps sentAt")
    void markSentStampsDelivery() {
        final Notification pending = seedAlert("notification-001", "task-001", AlertType.SLA_BREACH, NOW);

        final Notification sent = notificationService.markSent(pending.id());

        assertThat(sent.status()).isEqualTo(NotificationStatus.SENT);
        assertThat(sent.sentAt()).isEqualTo(NOW);
        assertThat(notificationRepository.findById("notification-001"))
                .get()
                .extracting(Notification::status)
                .isEqualTo(NotificationStatus.SENT);
    }
}
