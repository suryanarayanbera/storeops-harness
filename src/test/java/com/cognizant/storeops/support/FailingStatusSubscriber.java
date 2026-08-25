package com.cognizant.storeops.support;

import com.cognizant.storeops.shared.events.TaskStatusChangedEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.event.EventListener;

/**
 * A subscriber to {@link TaskStatusChangedEvent} that always fails.
 *
 * <p>Separate from {@link FailingSubscriber}, which only ever sees its own {@code ProbeEvent}.
 * Adding a status-change handler to that class instead would make it throw inside every existing
 * test that imports it, and would leave its {@code invocationCount()} shared between two unrelated
 * assertions.
 *
 * <p>A plain {@code @EventListener} rather than a transactional one, matching the deliberate
 * choice in {@code FailingSubscriber}: it fires at publish time, so the exception travels through
 * {@code SimpleApplicationEventMulticaster} and must be absorbed by the {@code ErrorHandler} bean
 * in {@code EventBusConfiguration}. That is the containment this fixture exists to prove; without
 * that bean the exception would surface as a failed {@code PATCH} for the caller.
 *
 * <p>Deliberately <strong>not</strong> a {@code @Component}. This package sits under
 * {@code com.cognizant.storeops}, so a stereotype annotation here is picked up by the
 * application's own component scan and the bean lands in every {@code @SpringBootTest} context in
 * the suite - which for a subscriber that throws on every status change means unrelated tests
 * start absorbing exceptions they never asked for. {@code @Import} registers a plain class just as
 * well, and only where it is asked for.
 */
public class FailingStatusSubscriber {

    private final AtomicInteger invocations = new AtomicInteger();

    /** How many status changes actually reached this subscriber, so a test cannot pass vacuously. */
    public int invocationCount() {
        return invocations.get();
    }

    @EventListener
    public void alwaysFails(final TaskStatusChangedEvent event) {
        invocations.incrementAndGet();
        throw new IllegalStateException(
                "subscriber failure on " + event.taskId() + ", expected to be contained");
    }
}
