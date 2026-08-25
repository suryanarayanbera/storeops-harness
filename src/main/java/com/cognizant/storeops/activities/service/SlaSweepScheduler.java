package com.cognizant.storeops.activities.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clock-driven trigger for SLA breach detection.
 *
 * <p>This class is the caller {@link TaskService#publishOverdueBreaches()} was written for and never
 * had. It holds no business rules of its own: deciding which activities have breached belongs in the
 * service layer, and duplicating the filter here would put business logic outside it and reach past
 * {@code TaskService} into {@code TaskRepository}, which {@code ModuleBoundaryTest} rules 1b and 5
 * both forbid.
 *
 * <p>It lives in {@code service} rather than a new {@code scheduler} package because the activities
 * module may contain only {@code routes}, {@code service}, {@code repository}, {@code domain},
 * {@code dto} and {@code listener}. {@code listener} is for event consumers; a sweep driven by the
 * clock is not one.
 *
 * <p>The sweep restates a fact rather than tracking what it has already reported: an activity that
 * is still overdue produces a {@code TaskOverdueEvent} on every cycle. That re-publication is how
 * the alerts module learns an activity is <em>still</em> unresolved, which is what its grace-period
 * escalation is built on. De-duplication is the subscriber's job, not this class's.
 *
 * <p>Absent from the context entirely when {@code storeops.activities.sla.sweep.enabled} is false,
 * which is how the integration tests keep a live sweep from inserting notifications mid-test.
 */
@Component
@ConditionalOnProperty(
        name = "storeops.activities.sla.sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SlaSweepScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SlaSweepScheduler.class);

    private final TaskService taskService;

    public SlaSweepScheduler(final TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * Publishes a {@code TaskOverdueEvent} for every SLA-tracked activity currently past its due
     * date.
     *
     * <p>{@code fixedDelayString} rather than {@code fixedRateString}: a sweep that overruns its
     * interval should not have the next one queued up behind it.
     */
    @Scheduled(
            fixedDelayString = "${storeops.activities.sla.sweep.interval}",
            initialDelayString = "${storeops.activities.sla.sweep.initial-delay}")
    public void sweepOverdueActivities() {
        final int published = taskService.publishOverdueBreaches();
        if (published > 0) {
            LOG.info("SLA sweep published {} overdue breach event(s)", published);
        } else {
            LOG.debug("SLA sweep found no overdue activities");
        }
    }
}
