package com.cognizant.storeops.support;

import com.cognizant.storeops.shared.events.ProgrammeTemplateRequestedEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A subscriber to {@link ProgrammeTemplateRequestedEvent} that always fails.
 *
 * <p>Proves that a broken consumer of the template event breaks neither the caller's {@code POST} nor
 * the sibling subscriber on the same event. The second half is what earns this fixture its keep once
 * Sprint 2 lands: the activities listener subscribes to this event too, so a test can assert the
 * cloned activities still appear while this class throws on every delivery.
 *
 * <p>An {@code AFTER_COMMIT} listener rather than a plain {@code @EventListener}, for the reason
 * {@link FailingRollupSubscriber} gives: the failure has to land in the same dispatch phase as the
 * handler under test, or it says nothing about whether an after-commit sibling still ran.
 *
 * <p>{@link #invocationCount()} exists so a containment test cannot pass vacuously - without it, a
 * dispatch that silently delivered nothing would look exactly like successful containment.
 *
 * <p>Deliberately <strong>not</strong> a {@code @Component}: this package is under
 * {@code com.cognizant.storeops}, so a stereotype annotation would be picked up by the application's
 * component scan and every {@code @SpringBootTest} in the suite would inherit a listener that throws.
 */
public class FailingTemplateSubscriber {

    private final AtomicInteger invocations = new AtomicInteger();

    /** How many template events actually reached this subscriber. */
    public int invocationCount() {
        return invocations.get();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void alwaysFails(final ProgrammeTemplateRequestedEvent event) {
        invocations.incrementAndGet();
        throw new IllegalStateException(
                "subscriber failure on " + event.projectId() + ", expected to be contained");
    }
}
