package com.cognizant.storeops.support;

import com.cognizant.storeops.shared.events.ProgrammeTemplateRequestedEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * A test subscriber that records the template events actually delivered to it.
 *
 * <p>The reason this exists alongside {@code RecordingEventBus} is the reason
 * {@link RecordingRollupSubscriber} exists: the bus double records at <em>publish</em> time and would
 * look identical whether or not {@code ProjectService.applyTemplate} is {@code @Transactional}. This
 * one subscribes {@code AFTER_COMMIT}, a phase Spring skips outright when no transaction is active,
 * so an event landing here proves both that a transaction existed and that it committed. Drop the
 * annotation on the service and Sprint 2's listener stops creating activities with nothing logged -
 * this is the fixture that catches it.
 *
 * <p>Writes nothing, so it needs no {@code @Transactional(REQUIRES_NEW)} of its own.
 *
 * <p>Deliberately <strong>not</strong> a {@code @Component}: this package sits under
 * {@code com.cognizant.storeops} and a stereotype here is swept up by the application's own component
 * scan, landing the bean in every {@code @SpringBootTest} context in the suite. {@code @Import} puts
 * it only where it is asked for.
 */
public class RecordingTemplateSubscriber {

    private final List<ProgrammeTemplateRequestedEvent> received = new CopyOnWriteArrayList<>();

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProgrammeTemplateRequested(final ProgrammeTemplateRequestedEvent event) {
        received.add(event);
    }

    /** Every template event delivered after commit, in order. */
    public List<ProgrammeTemplateRequestedEvent> received() {
        return List.copyOf(received);
    }

    public void clear() {
        received.clear();
    }
}
