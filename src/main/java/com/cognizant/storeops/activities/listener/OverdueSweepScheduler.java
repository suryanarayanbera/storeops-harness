package com.cognizant.storeops.activities.listener;

import com.cognizant.storeops.activities.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The activities module's clock: the inbound edge that turns the passage of time into an event.
 *
 * <p>An inbound adapter, in the same sense as a listener - it translates something arriving from
 * outside into one service call, and decides nothing. No due-date arithmetic, no priority check and
 * no event construction belong here; they are {@code TaskService}'s, which is why this class holds a
 * single delegating line.
 *
 * <p>The first sweep is deliberately far out. {@code @EnableScheduling} is active in every
 * {@code @SpringBootTest}, so a short initial delay would let a sweep fire in the middle of an
 * unrelated test and raise alerts that test never asked for. Ten minutes outlasts any test run, and
 * tests that want a sweep call {@code TaskService.publishOverdueBreaches()} directly. A test that must
 * not have the bean at all sets {@code storeops.activities.sla.sweep.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "storeops.activities.sla.sweep.enabled", matchIfMissing = true)
public class OverdueSweepScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OverdueSweepScheduler.class);

    private final TaskService taskService;

    OverdueSweepScheduler(final TaskService taskService) {
        this.taskService = taskService;
    }

    /** One pass over the open activities, raising {@code TaskOverdueEvent} for each SLA breach. */
    @Scheduled(
            fixedDelayString = "${storeops.activities.sla.sweep-interval:PT5M}",
            initialDelayString = "${storeops.activities.sla.initial-delay:PT10M}")
    public void sweepForOverdueBreaches() {
        final int published = taskService.publishOverdueBreaches();
        LOG.info("Overdue sweep published {} SLA breach event(s)", published);
    }
}
