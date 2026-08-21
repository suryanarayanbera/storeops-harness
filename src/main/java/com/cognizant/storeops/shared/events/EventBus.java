package com.cognizant.storeops.shared.events;

/**
 * Cross-module side-effect channel.
 *
 * <p>StoreOps architecture rule: side effects that cross a module boundary must be raised here,
 * never by a direct service-to-service import. Producers depend on this interface and on the event
 * records in this package - never on the consuming module.
 *
 * <p>There is deliberately no {@code subscribe} method. Consumers register declaratively with
 * {@code @TransactionalEventListener} on a handler method, so a module opts in to an event without
 * the publishing module or this interface knowing it exists.
 */
public interface EventBus {

    /**
     * Publishes to every subscriber registered for the event's runtime type.
     *
     * <p>Delivery happens after the publishing transaction commits. A side effect is therefore never
     * raised for a state change that was rolled back.
     */
    void publish(DomainEvent event);
}
