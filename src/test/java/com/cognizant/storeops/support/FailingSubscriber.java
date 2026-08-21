package com.cognizant.storeops.support;

import com.cognizant.storeops.shared.events.DomainEvent;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * A subscriber that always fails, used to prove subscriber isolation is still configured.
 *
 * <p>Deliberately a plain {@code @EventListener} rather than a transactional one: it must fire on
 * publish without needing a committed transaction, so the test can assert that the exception is
 * absorbed by the {@code ErrorHandler} in {@code EventBusConfiguration} rather than reaching the
 * publisher.
 *
 * <p>Not picked up by component scanning - the test imports it explicitly, so no other test is
 * affected.
 */
@Component
public class FailingSubscriber {

    /** Event type nothing in production publishes, so only the isolation test triggers this. */
    public record ProbeEvent(Instant occurredAt) implements DomainEvent {
        @Override
        public String eventType() {
            return "PROBE";
        }
    }

    private final AtomicInteger invocations = new AtomicInteger();

    /** How many times this subscriber was actually reached, so the test cannot pass vacuously. */
    public int invocationCount() {
        return invocations.get();
    }

    @EventListener
    public void alwaysFails(final ProbeEvent event) {
        invocations.incrementAndGet();
        throw new IllegalStateException("subscriber failure, expected to be contained");
    }
}
