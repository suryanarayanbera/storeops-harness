package com.cognizant.storeops.reports.listener;

import com.cognizant.storeops.reports.domain.ReportType;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.ProgrammeClosedEvent;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The reports module's inbound edge.
 *
 * <p>A closing programme should produce a store summary. The programmes module does not know that,
 * and must not: it publishes {@code PROGRAMME_CLOSED} and this listener decides what reporting
 * follows. The resulting write lands in the reports module's own store, keeping the read-only rule
 * intact.
 */
@Component
public class ReportEventListener {

    private static final Logger LOG = LoggerFactory.getLogger(ReportEventListener.class);

    private final EventBus eventBus;
    private final ReportService reportService;

    public ReportEventListener(final EventBus eventBus, final ReportService reportService) {
        this.eventBus = eventBus;
        this.reportService = reportService;
    }

    @PostConstruct
    void register() {
        eventBus.subscribe(ProgrammeClosedEvent.class, this::onProgrammeClosed);
        LOG.info("Reports module subscribed to PROGRAMME_CLOSED");
    }

    void onProgrammeClosed(final ProgrammeClosedEvent event) {
        reportService.queue(ReportType.STORE_SUMMARY, event.storeId(), event.closedByUserId());
        LOG.info("Queued STORE_SUMMARY for store {} after programme {} closed", event.storeId(), event.projectId());
    }
}
