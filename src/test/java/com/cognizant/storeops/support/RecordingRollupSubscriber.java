package com.cognizant.storeops.support;

import com.cognizant.storeops.shared.events.RegionalRollupRequestedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A test subscriber that records the rollup events actually delivered to it.
 *
 * <p>Distinct from {@code RecordingEventBus}, and the distinction is the whole point.
 * {@code RecordingEventBus} records at <em>publish</em> time, so it cannot tell whether the publisher
 * was transactional - it would record identically if the event were about to be dropped. This one
 * subscribes with {@code @TransactionalEventListener(AFTER_COMMIT)}, which Spring skips entirely when
 * no transaction is active. So an event arriving here proves two things a publish-time double cannot:
 * that the publishing method really was {@code @Transactional}, and that its transaction committed.
 *
 * <p>Deliberately writes nothing, so it needs no {@code @Transactional(REQUIRES_NEW)} of its own; the
 * list is the assertion surface.
 *
 * <p>Deliberately <strong>not</strong> a {@code @Component}, for the reason
 * {@link FailingStatusSubscriber} sets out: this package sits under {@code com.cognizant.storeops},
 * so a stereotype annotation here is picked up by the application's own component scan and the bean
 * lands in every {@code @SpringBootTest} context in the suite. For a recorder that is merely
 * untidy - it would accumulate events for tests that never asked for it - but the habit is what
 * matters, because the same mistake on {@link FailingRollupSubscriber} would have unrelated tests
 * absorbing exceptions. {@code @Import} registers a plain class just as well, and only where asked.
 */
public class RecordingRollupSubscriber {

    private final List<RegionalRollupRequestedEvent> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegionalRollupRequested(final RegionalRollupRequestedEvent event) {
        received.add(event);
    }

    /** Every rollup event delivered after commit, in order. */
    public List<RegionalRollupRequestedEvent> received() {
        return List.copyOf(received);
    }

    public void clear() {
        received.clear();
    }
}
