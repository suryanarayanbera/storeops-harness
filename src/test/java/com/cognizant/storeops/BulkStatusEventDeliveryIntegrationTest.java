package com.cognizant.storeops;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The events published by the bulk handover path, followed all the way into the alerts module.
 *
 * <p>Sprint 1 proved the publish call happens, using a recording bus in a plain JUnit test. That
 * cannot see the thing most likely to be wrong. {@code @TransactionalEventListener(AFTER_COMMIT)}
 * does nothing at all when no transaction is active, and the bulk service reaches
 * {@code TaskService.update} through an injected bean precisely so that a transaction opens per
 * activity. Break that - move the loop into {@code TaskService} and self-invoke, or wrap the batch
 * in one outer transaction - and no alert is ever raised, with no exception and nothing in the log.
 * Every Sprint 1 test still passes. These are the tests that do not.
 *
 * <p>So each assertion here is on the side effect: a {@code Notification} row, read back through
 * the alerts module's own endpoint. Never on the HTTP status alone.
 *
 * <p>The seeded {@code notification-001} is a {@code SHIFT_HANDOVER} alert to {@code user-003}
 * whose {@code sourceRef} is {@code task-003}, so every assertion about that recipient filters on
 * {@code alertType} and {@code sourceRef} together. Counting rows would be satisfied by the seed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class BulkStatusEventDeliveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private ResultActions bulkStatus(final String body) throws Exception {
        return mockMvc.perform(patch("/api/tasks/bulk-status")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Alerts for one recipient. */
    private ResultActions alertsFor(final String recipientId) throws Exception {
        return mockMvc.perform(get("/api/notifications").param("recipientId", recipientId))
                .andExpect(status().isOk());
    }

    /** Every alert in the system, however it was raised. */
    private ResultActions allAlerts() throws Exception {
        return mockMvc.perform(get("/api/notifications")).andExpect(status().isOk());
    }

    /** JSONPath selecting ESCALATION alerts raised by one activity. */
    private static String escalationFor(final String taskId) {
        return "$[?(@.alertType == 'ESCALATION' && @.sourceRef == '%s')]".formatted(taskId);
    }

    @Test
    @DisplayName("a bulk block raises one ESCALATION alert per blocked activity, each to its own assignee")
    void bulkBlockRaisesOneAlertPerActivity() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-001","status":"BLOCKED"},
                            {"taskId":"task-002","status":"BLOCKED"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(2)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-001"))
                .andExpect(jsonPath("$.succeeded[1].id").value("task-002"))
                .andExpect(jsonPath("$.failed", hasSize(0)));

        // task-001 is assigned to user-004, task-002 to user-003: the alert follows the assignee
        // carried in the event payload, not the caller.
        alertsFor("user-004")
                .andExpect(jsonPath(escalationFor("task-001"), hasSize(1)))
                // The event carried the previous status, so the alert body can name the transition.
                .andExpect(jsonPath(escalationFor("task-001") + ".body",
                        hasItem(containsString("moved from TODO to BLOCKED"))));

        alertsFor("user-003")
                .andExpect(jsonPath(escalationFor("task-002"), hasSize(1)));
    }

    @Test
    @DisplayName("a partial-failure batch still delivers for its successes and publishes nothing for the failure")
    void partialFailureBatchStillDelivers() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-999","status":"BLOCKED"},
                            {"taskId":"task-002","status":"BLOCKED"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(1)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-002"))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-999"))
                .andExpect(jsonPath("$.failed[0].code").value("TASK_NOT_FOUND"));

        alertsFor("user-003")
                .andExpect(jsonPath(escalationFor("task-002"), hasSize(1)));

        // The failing item aborted its own transaction, so nothing anywhere references it.
        allAlerts().andExpect(jsonPath("$[?(@.sourceRef == 'task-999')]", hasSize(0)));
    }

    @Test
    @DisplayName("a transition the service refused raises no alert at all")
    void refusedTransitionRaisesNoAlert() throws Exception {
        bulkStatus("{\"updates\":[{\"taskId\":\"task-003\",\"status\":\"BLOCKED\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(0)))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-003"))
                .andExpect(jsonPath("$.failed[0].code").value("TASK_TRANSITION_NOT_ALLOWED"));

        // Exactly the seeded alert, and no ESCALATION: notification-001 also references task-003,
        // which is why the filter is on alertType too.
        alertsFor("user-003")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("notification-001"))
                .andExpect(jsonPath("$[0].alertType").value("SHIFT_HANDOVER"))
                .andExpect(jsonPath(escalationFor("task-003"), hasSize(0)));
    }

    @Test
    @DisplayName("a bulk completion is delivered but the alerts module chooses not to alert on it")
    void bulkCompletionRaisesNoAlert() throws Exception {
        bulkStatus("{\"updates\":[{\"taskId\":\"task-001\",\"status\":\"DONE\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(1)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-001"))
                .andExpect(jsonPath("$.succeeded[0].status").value("DONE"));

        mockMvc.perform(get("/api/tasks/task-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // Nothing for user-004. The companion assertion that dispatch is working at all is
        // bulkBlockRaisesOneAlertPerActivity, which raises an alert for this same recipient.
        alertsFor("user-004").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("a failure mid-batch neither rolls back the alert before it nor stops the one after")
    void eachActivityCommitsAndDeliversOnItsOwn() throws Exception {
        bulkStatus("""
                {"updates":[{"taskId":"task-001","status":"BLOCKED"},
                            {"taskId":"task-999","status":"BLOCKED"},
                            {"taskId":"task-002","status":"BLOCKED"}]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(2)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-001"))
                .andExpect(jsonPath("$.succeeded[1].id").value("task-002"))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-999"));

        // Fresh requests, so these reads see committed state only.
        mockMvc.perform(get("/api/tasks/task-001")).andExpect(jsonPath("$.status").value("BLOCKED"));
        mockMvc.perform(get("/api/tasks/task-002")).andExpect(jsonPath("$.status").value("BLOCKED"));

        // Two committed transitions, two alerts - one before the failure and one after it.
        allAlerts().andExpect(jsonPath("$[?(@.alertType == 'ESCALATION')]", hasSize(2)));
        alertsFor("user-004").andExpect(jsonPath(escalationFor("task-001"), hasSize(1)));
        alertsFor("user-003").andExpect(jsonPath(escalationFor("task-002"), hasSize(1)));
    }
}
