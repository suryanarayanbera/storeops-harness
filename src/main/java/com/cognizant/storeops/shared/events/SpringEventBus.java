package com.cognizant.storeops.shared.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The {@link EventBus} implementation, delegating dispatch to Spring.
 *
 * <p>This type exists rather than injecting {@code ApplicationEventPublisher} directly into every
 * service, because the StoreOps boundary rule is stated in terms of a named bus: an Evaluator can
 * grep for {@code eventBus.publish(} and a skill file can name it. Producers keep depending on the
 * project's own abstraction, and the framework stays an implementation detail of this one class.
 *
 * <p>Subscriber isolation - a failing consumer must not break a publisher - is configured centrally
 * in {@link EventBusConfiguration}, not here.
 */
@Component
public class SpringEventBus implements EventBus {

    private static final Logger LOG = LoggerFactory.getLogger(SpringEventBus.class);

    private final ApplicationEventPublisher delegate;

    public SpringEventBus(final ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(final DomainEvent event) {
        if (event == null) {
            LOG.warn("Ignoring null event");
            return;
        }
        LOG.debug("Publishing {} for delivery after commit", event.eventType());
        delegate.publishEvent(event);
    }
}
