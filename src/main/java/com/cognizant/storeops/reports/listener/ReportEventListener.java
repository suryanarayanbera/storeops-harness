package com.cognizant.storeops.reports.listener;

import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import com.cognizant.storeops.shared.events.RegionalRollupRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The reports module's inbound edge.
 *
 * <p>A closing programme should produce a store summary. The programmes module does not know that,
 * and must not: it publishes {@code PROGRAMME_CLOSED} and this listener decides what reporting
 * follows. The resulting write lands in the reports module's own store, keeping the read-only rule
 * intact.
 *
 * <p>Runs {@link TransactionPhase#AFTER_COMMIT}, so no report is queued for a programme whose close
 * was rolled back. {@link Propagation#REQUIRES_NEW} is required, not optional: the publishing
 * transaction has already committed by the time this runs, so a write joining it would never be
 * flushed.
 */
@Component
public class ReportEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReportEventListener.class);

    private final ReportService reportService;

    public ReportEventListener(final ReportService reportService) {
        this.reportService = reportService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onProgrammeClosed(final ProgrammeClosedEvent event) {
        reportService.queue(ReportType.STORE_SUMMARY, event.storeId(), event.closedByUserId());
        LOG.info("Queued STORE_SUMMARY for store {} after programme {} closed", event.storeId(), event.projectId());
    }

    /**
     * Records that a regional rollup was asked for.
     *
     * <p>The rollup itself is a read, answered synchronously in the response body. This is the write
     * that says it happened, and it is deliberately on the far side of the bus: a failure to record
     * must not fail the caller's {@code GET}, and the {@code ErrorHandler} in
     * {@code EventBusConfiguration} is what guarantees that.
     *
     * <p>A separate handler rather than a branch inside {@link #onProgrammeClosed}: the two events
     * carry different payloads and produce different {@link ReportType}s, and one method dispatching
     * on event type would be the first place a future third report type went wrong.
     *
     * <p>Same two annotations as its sibling, for the same reason.
     * {@link Propagation#REQUIRES_NEW} is what makes the row appear at all - at after-commit time the
     * publishing transaction has already committed, so a write joining it is never flushed. The
     * listener would run, this log line would print, and no report would exist.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRegionalRollupRequested(final RegionalRollupRequestedEvent event) {
        reportService.queue(ReportType.REGIONAL_ROLLUP, event.regionId(), event.requestedBy());
        LOG.info("Queued REGIONAL_ROLLUP for region {} across {} store(s), requested by {}",
                event.regionId(), event.storeCount(), event.requestedBy());
    }
}
