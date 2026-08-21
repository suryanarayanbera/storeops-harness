package com.cognizant.storeops.support;

import com.cognizant.storeops.shared.events.DomainEvent;
import com.cognizant.storeops.shared.events.EventBus;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for {@link EventBus} that records what a service published.
 *
 * <p>Service-layer tests assert the publishing contract - that a status change raises exactly one
 * {@code TaskStatusChangedEvent} carrying both statuses, and that a priority-only change raises
 * none. That contract is this project's, so it is worth testing directly rather than through
 * Spring's dispatch. Whether a published event then reaches the alerts module is a separate
 * question, answered by the {@code @SpringBootTest} integration tests.
 */
public class RecordingEventBus implements EventBus {

    private final List<DomainEvent> published = new ArrayList<>();

    @Override
    public void publish(final DomainEvent event) {
        published.add(event);
    }

    /** Every event published, in order, whatever its type. */
    public List<DomainEvent> published() {
        return List.copyOf(published);
    }

    /** Only the published events of one type, in order. */
    public <E extends DomainEvent> List<E> published(final Class<E> eventType) {
        return published.stream().filter(eventType::isInstance).map(eventType::cast).toList();
    }

    public void clear() {
        published.clear();
    }
}
