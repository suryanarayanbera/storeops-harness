package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.activities.service.TaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The SLA breach sweep, followed all the way into the alerts module and back out through the API.
 *
 * <p>These are the tests the sprint's unit tests cannot be: everything in
 * {@code TaskServiceTest} observes the event through {@code RecordingEventBus}, which records at
 * publish time, and everything in {@code AlertEventListenerTest} calls the handler directly. Neither
 * touches the join between them, and that join is where this feature fails silently.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} does nothing at all when no transaction is
 * active, and {@code @Transactional(REQUIRES_NEW)} is what makes the listener's write survive being
 * run after the publisher already committed. Drop either and no alert is raised, with no exception
 * and nothing in the log, while every other test in the suite stays green.
 *
 * <p>So every assertion here is on the side effect - a {@code Notification} row read back through
 * the alerts module's own endpoint - never on a return value alone.
 *
 * <p>The sweep is disabled so that the only sweeps are the ones these tests invoke; a live scheduler
 * would insert rows between the act and the assert. The seeded {@code notification-001} is a
 * {@code SHIFT_HANDOVER} alert to {@code user-003}, so assertions about that recipient filter on
 * {@code alertType} and {@code sourceRef} together rather than counting rows.
 */
@SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SlaBreachAlertingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskService taskService;

    /** Alerts for one recipient. */
    private ResultActions alertsFor(final String recipientId) throws Exception {
        return mockMvc.perform(get("/api/notifications").param("recipientId", recipientId))
                .andExpect(status().isOk());
    }

    /** JSONPath selecting SLA_BREACH alerts raised by one activity. */
    private static String breachFor(final String taskId) {
        return "$[?(@.alertType == 'SLA_BREACH' && @.sourceRef == '%s')]".formatted(taskId);
    }

    @Test
    @DisplayName("the sweep delivers one SLA_BREACH to the department lead, after commit, in H2")
    void sweepDeliversBreachToTheDepartmentLead() throws Exception {
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);

        // user-003 is the GROCERY department lead at store-001; task-001's assignee user-004 is a
        // GROCERY associate. Two alerts: the seeded SHIFT_HANDOVER plus this breach.
        alertsFor("user-003")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath(breachFor("task-001"), hasSize(1)))
                .andExpect(jsonPath(breachFor("task-001") + ".subject", hasItem("SLA breach")))
                .andExpect(jsonPath(breachFor("task-001") + ".status", hasItem("PENDING")))
                .andExpect(jsonPath(breachFor("task-001") + ".channel", hasItem("IN_APP")))
                .andExpect(jsonPath(breachFor("task-001") + ".body", hasItem(containsString("HIGH"))))
                .andExpect(jsonPath(breachFor("task-001") + ".body", hasItem(containsString("store-001"))));
    }

    @Test
    @DisplayName("the assignee is not notified - an SLA breach is the lead's to act on")
    void theAssigneeIsNotNotified() throws Exception {
        taskService.publishOverdueBreaches();

        alertsFor("user-004").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("a second sweep raises no duplicate against the real database")
    void aSecondSweepRaisesNoDuplicate() throws Exception {
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);
        alertsFor("user-003").andExpect(jsonPath("$", hasSize(2)));

        // The sweep republishes because task-001 is still overdue; de-duplication happens in the
        // listener, against rows already committed by the first pass.
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);

        alertsFor("user-003")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath(breachFor("task-001"), hasSize(1)));
    }

    @Test
    @DisplayName("only the one seed activity breaches - MEDIUM, DONE and undated are untouched")
    void onlyTheTrackedBreachRaisesAnAlert() throws Exception {
        taskService.publishOverdueBreaches();

        // task-002 is MEDIUM, task-003 is DONE, task-004 has no due date. None may produce an alert,
        // and task-002's assignee is user-003 - the same recipient as the real breach - so counting
        // that recipient's rows alone would not catch a leak.
        alertsFor("user-003")
                .andExpect(jsonPath(breachFor("task-002"), hasSize(0)))
                .andExpect(jsonPath(breachFor("task-003"), hasSize(0)));
        alertsFor("user-005").andExpect(jsonPath(breachFor("task-004"), hasSize(0)));
    }
}
