package com.cognizant.storeops.activities.routes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.dto.CreateTaskRequest;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.service.NotificationService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Bulk handover over the real container: real routes, real transaction, real H2, real event dispatch.
 *
 * <p>The slice and service tests can both pass while the endpoint is broken in the one way that
 * matters. A batch runs in a single transaction, so if a rejected entry is allowed to escape a
 * transactional boundary the transaction is marked rollback-only and the whole handover is lost to an
 * {@code UnexpectedRollbackException} at commit - a 500, after a report that claimed success. Only a
 * test that commits and then reads back can see that, which is what
 * {@link #batchCommitsDespiteARejectedEntry} does.
 *
 * <p>Activities are created here rather than taken from the seed rows: these tests mutate what they
 * touch, and the seed rows are shared with every other {@code @SpringBootTest} in the suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TaskBulkStatusIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskService taskService;

    @Autowired
    private NotificationService notificationService;

    private String createTask(final String title, final String assigneeId) {
        return taskService.create(new CreateTaskRequest(
                title, null, TaskPriority.HIGH, null, "store-001", null, assigneeId, null)).id();
    }

    private static String handoverBody(final String... entries) {
        return "{\"updates\":[" + String.join(",", entries) + "]}";
    }

    private static String entry(final String taskId, final String status) {
        return "{\"taskId\":\"" + taskId + "\",\"status\":\"" + status + "\"}";
    }

    @Test
    @DisplayName("a batch holding one rejected entry still commits every entry that succeeded")
    void batchCommitsDespiteARejectedEntry() throws Exception {
        final String closing = createTask("Handover - close down aisle 4", "user-004");
        final String blocking = createTask("Handover - cage audit stalled", "user-004");

        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoverBody(
                                entry("task-does-not-exist", "DONE"),
                                entry(closing, "DONE"),
                                entry(blocking, "BLOCKED"))))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.succeeded", hasSize(2)))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-does-not-exist"))
                .andExpect(jsonPath("$.failed[0].code").value("TASK_NOT_FOUND"));

        // Read back in fresh transactions: the batch committed rather than being rolled back whole.
        mockMvc.perform(get("/api/tasks/" + closing))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
        mockMvc.perform(get("/api/tasks/" + blocking))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));
    }

    @Test
    @DisplayName("a blocked entry in a batch reaches the alerts module after the batch commits")
    void blockedEntryReachesAlertsModule() throws Exception {
        final String taskId = createTask("Handover - chilled check stalled", "user-003");
        final int before = notificationService.list("user-003", null).size();

        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(handoverBody(entry(taskId, "BLOCKED"))))
                .andExpect(status().isMultiStatus())
                .andExpect(jsonPath("$.succeeded", hasSize(1)))
                .andExpect(jsonPath("$.succeeded[0].newStatus").value("BLOCKED"))
                .andExpect(jsonPath("$.succeeded[0].changed").value(true));

        final List<Notification> raised = notificationService.list("user-003", null);
        assertThat(raised).hasSize(before + 1);
        assertThat(raised).anySatisfy(notification -> {
            assertThat(notification.sourceRef()).isEqualTo(taskId);
            assertThat(notification.alertType()).isEqualTo(AlertType.ESCALATION);
        });

        mockMvc.perform(get("/api/notifications").param("recipientId", "user-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(before + 1)));
    }
}
