package com.cognizant.storeops.shared.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Synchronous in-process {@link EventBus}.
 *
 * <p>Dispatch is synchronous so that a stub deployment behaves predictably in tests. A subscriber
 * failure is contained and logged rather than propagated: a publishing module must not be broken by
 * a consuming module, which is the whole point of routing side effects through the bus.
 */
@Component
public class InMemoryEventBus implements EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final Map<Class<? extends DomainEvent>, List<Consumer<? extends DomainEvent>>> handlers =
            new ConcurrentHashMap<>();

    @Override
    public void publish(final DomainEvent event) {
        if (event == null) {
            LOG.warn("Ignoring null event");
            return;
        }
        final List<Consumer<? extends DomainEvent>> registered = handlers.get(event.getClass());
        if (registered == null || registered.isEmpty()) {
            LOG.debug("No subscribers for {}", event.eventType());
            return;
        }
        LOG.debug("Publishing {} to {} subscriber(s)", event.eventType(), registered.size());
        for (final Consumer<? extends DomainEvent> handler : registered) {
            dispatch(handler, event);
        }
    }

    @Override
    public <E extends DomainEvent> void subscribe(final Class<E> eventType, final Consumer<E> handler) {
        handlers.computeIfAbsent(eventType, key -> new CopyOnWriteArrayList<>()).add(handler);
        LOG.debug("Subscribed handler to {}", eventType.getSimpleName());
    }

    /** Number of handlers registered for an event type. Exposed for tests and diagnostics. */
    public int subscriberCount(final Class<? extends DomainEvent> eventType) {
        return handlers.getOrDefault(eventType, List.of()).size();
    }

    @SuppressWarnings({"unchecked", "checkstyle:IllegalCatch"})
    private static void dispatch(final Consumer<? extends DomainEvent> handler, final DomainEvent event) {
        try {
            ((Consumer<DomainEvent>) handler).accept(event);
        } catch (RuntimeException failure) {
            // Deliberately broad: subscriber isolation. One failing consumer must not abort the
            // publisher's transaction or starve the remaining subscribers.
            LOG.error("Subscriber failed handling {}", event.eventType(), failure);
        }
    }
}
