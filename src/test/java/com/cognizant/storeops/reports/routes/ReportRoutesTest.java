package com.cognizant.storeops.reports.routes;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.reports.dto.StoreSummaryResponse;
import com.cognizant.storeops.reports.service.ReportService;
import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Routes-layer slice test for the reports module. */
@WebMvcTest(ReportRoutes.class)
class ReportRoutesTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReportService reportService;

    @Test
    @DisplayName("GET /api/reports/store/{storeId} returns 200 with the aggregated summary")
    void storeSummaryReturnsMetrics() throws Exception {
        when(reportService.storeSummary("store-001")).thenReturn(new StoreSummaryResponse(
                "store-001", NOW, 4, 1, 0.25, 2, 1,
                Map.of("TODO", 1, "IN_PROGRESS", 1, "DONE", 1, "BLOCKED", 1),
                Map.of("RESTOCKING", 1, "COMPLIANCE", 1),
                1, 4));

        mockMvc.perform(get("/api/reports/store/store-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value("store-001"))
                .andExpect(jsonPath("$.totalActivities").value(4))
                .andExpect(jsonPath("$.completedActivities").value(1))
                .andExpect(jsonPath("$.completionRate").value(0.25))
                .andExpect(jsonPath("$.overdueCount").value(2))
                .andExpect(jsonPath("$.blockedCount").value(1))
                .andExpect(jsonPath("$.activitiesByStatus.BLOCKED").value(1))
                .andExpect(jsonPath("$.overdueByCategory.RESTOCKING").value(1))
                .andExpect(jsonPath("$.activeProgrammes").value(1))
                .andExpect(jsonPath("$.headcount").value(4));
    }

    @Test
    @DisplayName("GET /api/reports/store/{storeId} surfaces a ValidationError as 400")
    void storeSummarySurfacesValidationError() throws Exception {
        when(reportService.storeSummary("  ")).thenThrow(new ValidationError("A store id is required"));

        mockMvc.perform(get("/api/reports/store/{storeId}", "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
