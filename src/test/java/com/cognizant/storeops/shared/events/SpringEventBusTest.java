package com.cognizant.storeops.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * What {@link SpringEventBus} adds on top of Spring: the null guard, and the guarantee that
 * publishing goes through Spring's publisher rather than a bespoke dispatcher.
 *
 * <p>Deliberately narrow. Fan-out to multiple subscribers, type-scoped routing and after-commit
 * timing are Spring's behaviour, not this project's - they are covered where they matter, in
 * {@code EventDeliveryIntegrationTest}, against the real container.
 */
class SpringEventBusTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private ApplicationEventPublisher delegate;
    private SpringEventBus eventBus;

    @BeforeEach
    void setUp() {
        delegate = mock(ApplicationEventPublisher.class);
        eventBus = new SpringEventBus(delegate);
    }

    private static TaskStatusChangedEvent statusChanged() {
        return new TaskStatusChangedEvent("task-001", "store-001", "TODO", "BLOCKED", "HIGH", "user-004", NOW);
    }

    @Test
    @DisplayName("publish hands the event to Spring's publisher unchanged")
    void publishDelegates() {
        final TaskStatusChangedEvent event = statusChanged();

        eventBus.publish(event);

        verify(delegate).publishEvent(event);
    }

    @Test
    @DisplayName("publishing null is ignored rather than propagated to Spring")
    void publishNullIsIgnored() {
        assertThatCode(() -> eventBus.publish(null)).doesNotThrowAnyException();

        verify(delegate, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("every event exposes a stable type name for logging and diagnostics")
    void eventsExposeStableTypeNames() {
        assertThat(statusChanged().eventType()).isEqualTo("TASK_STATUS_CHANGED");
        assertThat(new TaskOverdueEvent("task-001", "store-001", "HIGH", "user-004", NOW, NOW).eventType())
                .isEqualTo("TASK_OVERDUE");
        assertThat(new ProgrammeClosedEvent("project-001", "store-001", "user-002", NOW).eventType())
                .isEqualTo("PROGRAMME_CLOSED");
    }
}
