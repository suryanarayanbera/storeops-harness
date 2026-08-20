package com.cognizant.storeops.alerts.routes;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.domain.NotificationChannel;
import com.cognizant.storeops.alerts.domain.NotificationStatus;
import com.cognizant.storeops.alerts.service.NotificationService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Routes-layer slice test for the alerts module. */
@WebMvcTest(NotificationRoutes.class)
class NotificationRoutesTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    @Test
    @DisplayName("GET /api/notifications returns 200 with the alert list")
    void listReturnsNotifications() throws Exception {
        when(notificationService.list(null, null)).thenReturn(List.of(new Notification(
                "notification-001", "user-003", AlertType.SLA_BREACH, NotificationChannel.IN_APP,
                NotificationStatus.SENT, "SLA breach", "Activity task-001 is overdue.", "task-001",
                NOW, NOW.plusSeconds(5))));

        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].alertType").value("SLA_BREACH"))
                .andExpect(jsonPath("$[0].channel").value("IN_APP"))
                .andExpect(jsonPath("$[0].status").value("SENT"))
                .andExpect(jsonPath("$[0].sourceRef").value("task-001"));
    }

    @Test
    @DisplayName("GET /api/notifications passes recipient and status filters through to the service")
    void listAppliesFilters() throws Exception {
        when(notificationService.list("user-003", NotificationStatus.PENDING)).thenReturn(List.of());

        mockMvc.perform(get("/api/notifications")
                        .param("recipientId", "user-003")
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/notifications rejects an unknown status with a 400 ValidationError")
    void listRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/api/notifications").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
