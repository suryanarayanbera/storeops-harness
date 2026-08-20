package com.cognizant.storeops.shared.events;

import java.time.Instant;

/**
 * Marker for anything publishable on the {@link EventBus}.
 *
 * <p>Events are the only sanctioned channel for side effects that cross a module boundary. A module
 * that needs something to happen elsewhere publishes an event; it must not import the consuming
 * module's service.
 */
public interface DomainEvent {

    /** Stable event name, used for logging and for subscriber diagnostics. */
    String eventType();

    /** When the state change that produced this event happened. */
    Instant occurredAt();
}
