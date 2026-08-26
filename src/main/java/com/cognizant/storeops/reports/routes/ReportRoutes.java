package com.cognizant.storeops.reports.routes;

import com.cognizant.storeops.reports.dto.RegionalRollupResponse;
import com.cognizant.storeops.reports.dto.StoreSummaryResponse;
import com.cognizant.storeops.reports.service.ReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** HTTP surface of the reports module. Read-only by design. */
@RestController
@RequestMapping("/api/reports")
public class ReportRoutes {

    private final ReportService reportService;

    public ReportRoutes(final ReportService reportService) {
        this.reportService = reportService;
    }

    /** Endpoint 9: {@code GET /api/reports/store/{storeId}}. */
    @GetMapping("/store/{storeId}")
    public ResponseEntity<StoreSummaryResponse> storeSummary(@PathVariable final String storeId) {
        return ResponseEntity.ok(reportService.storeSummary(storeId));
    }

    /**
     * Endpoint 10: {@code GET /api/reports/region/{regionId}}.
     *
     * <p>{@code requestedBy} is optional and is attributed to {@code api} when omitted. Defaulting
     * happens in the service, not here, because who a report is attributed to is a reporting rule
     * rather than a binding concern.
     */
    @GetMapping("/region/{regionId}")
    public ResponseEntity<RegionalRollupResponse> regionalRollup(
            @PathVariable final String regionId,
            @RequestParam(name = "requestedBy", required = false) final String requestedBy) {
        return ResponseEntity.ok(reportService.regionalRollup(regionId, requestedBy));
    }
}
