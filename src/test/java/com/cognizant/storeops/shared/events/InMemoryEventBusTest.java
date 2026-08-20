package com.cognizant.storeops.shared.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Behaviour of the cross-module side-effect channel. */
class InMemoryEventBusTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    private InMemoryEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new InMemoryEventBus();
    }

    private static TaskStatusChangedEvent statusChanged() {
        return new TaskStatusChangedEvent("task-001", "store-001", "TODO", "BLOCKED", "HIGH", "user-004", NOW);
    }

    @Test
    @DisplayName("publish delivers to every subscriber of that event type")
    void publishDeliversToAllSubscribers() {
        final List<String> first = new ArrayList<>();
        final List<String> second = new ArrayList<>();
        eventBus.subscribe(TaskStatusChangedEvent.class, event -> first.add(event.taskId()));
        eventBus.subscribe(TaskStatusChangedEvent.class, event -> second.add(event.newStatus()));

        eventBus.publish(statusChanged());

        assertThat(first).containsExactly("task-001");
        assertThat(second).containsExactly("BLOCKED");
        assertThat(eventBus.subscriberCount(TaskStatusChangedEvent.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("publish does not deliver to subscribers of a different event type")
    void publishIsTypeScoped() {
        final List<DomainEvent> overdue = new ArrayList<>();
        eventBus.subscribe(TaskOverdueEvent.class, overdue::add);

        eventBus.publish(statusChanged());

        assertThat(overdue).isEmpty();
    }

    @Test
    @DisplayName("a failing subscriber is contained so the publisher and other subscribers survive")
    void failingSubscriberIsContained() {
        final List<String> survivor = new ArrayList<>();
        eventBus.subscribe(TaskStatusChangedEvent.class, event -> {
            throw new IllegalStateException("subscriber blew up");
        });
        eventBus.subscribe(TaskStatusChangedEvent.class, event -> survivor.add(event.taskId()));

        assertThatCode(() -> eventBus.publish(statusChanged())).doesNotThrowAnyException();
        assertThat(survivor).containsExactly("task-001");
    }

    @Test
    @DisplayName("publishing with no subscribers is a no-op, not an error")
    void publishWithoutSubscribersIsSafe() {
        assertThatCode(() -> eventBus.publish(statusChanged())).doesNotThrowAnyException();
        assertThat(eventBus.subscriberCount(TaskStatusChangedEvent.class)).isZero();
    }

    @Test
    @DisplayName("publishing null is ignored rather than propagated")
    void publishNullIsIgnored() {
        assertThatCode(() -> eventBus.publish(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every event exposes a stable type name")
    void eventsExposeStableTypeNames() {
        assertThat(statusChanged().eventType()).isEqualTo("TASK_STATUS_CHANGED");
        assertThat(new TaskOverdueEvent("task-001", "store-001", "HIGH", "user-004", NOW, NOW).eventType())
                .isEqualTo("TASK_OVERDUE");
        assertThat(new ProgrammeClosedEvent("project-001", "store-001", "user-002", NOW).eventType())
                .isEqualTo("PROGRAMME_CLOSED");
    }
}
