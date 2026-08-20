package com.cognizant.storeops.shared.events;

import java.util.function.Consumer;

/**
 * Cross-module side-effect channel.
 *
 * <p>StoreOps architecture rule: side effects that cross a module boundary must be raised here,
 * never by a direct service-to-service import. Producers depend on this interface and on the event
 * records in this package - never on the consuming module.
 */
public interface EventBus {

    /** Publishes to every subscriber registered for the event's runtime type. */
    void publish(DomainEvent event);

    /** Registers a handler for one event type. Handlers are invoked in registration order. */
    <E extends DomainEvent> void subscribe(Class<E> eventType, Consumer<E> handler);
}
