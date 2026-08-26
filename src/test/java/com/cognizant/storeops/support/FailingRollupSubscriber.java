package com.cognizant.storeops.support;

import com.cognizant.storeops.shared.events.RegionalRollupRequestedEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A subscriber to {@link RegionalRollupRequestedEvent} that always fails.
 *
 * <p>Proves that a broken reporting subscriber cannot break either the caller's {@code GET} or the
 * other subscriber to the same event. That second half is what makes this fixture worth having:
 * {@code ReportEventListener} subscribes to this event too, so the test can assert the
 * {@code REGIONAL_ROLLUP} row still lands while this class is throwing on every delivery.
 *
 * <p>An {@code AFTER_COMMIT} listener rather than a plain {@code @EventListener}, unlike
 * {@link FailingSubscriber} and {@link FailingStatusSubscriber}. Those two fire at publish time
 * because they exist to prove containment at that point. Here the failure has to happen in the same
 * dispatch phase as the handler under test, or it would not be testing the containment that matters:
 * an exception raised at publish time cannot tell you anything about whether an after-commit sibling
 * still ran.
 *
 * <p>Deliberately <strong>not</strong> a {@code @Component}: this package is under
 * {@code com.cognizant.storeops} and would be picked up by the application's component scan, which
 * for a subscriber that throws on every rollup means every {@code @SpringBootTest} in the suite
 * inherits a failing listener. {@code @Import} registers it only where it is wanted.
 */
public class FailingRollupSubscriber {

    private final AtomicInteger invocations = new AtomicInteger();

    /** How many rollup events actually reached this subscriber, so a test cannot pass vacuously. */
    public int invocationCount() {
        return invocations.get();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void alwaysFails(final RegionalRollupRequestedEvent event) {
        invocations.incrementAndGet();
        throw new IllegalStateException(
                "subscriber failure on " + event.regionId() + ", expected to be contained");
    }
}
