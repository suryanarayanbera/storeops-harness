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
 * Both stages of SLA breach alerting, end to end, after commit, through the real bus and H2.
 *
 * <p>The grace period is set to zero so that the second sweep escalates. That is why the period is
 * configuration rather than a constant: the alternative would be advancing a clock inside a running
 * container, and the escalation window would be untestable at this level.
 *
 * <p>Sweeps happen only where a test asks for one - the scheduler is switched off - so each
 * invocation of {@code publishOverdueBreaches()} maps to exactly one observation of the breach, which
 * is what makes "first sweep raises, second sweep escalates, third does nothing" a meaningful
 * sequence rather than a race.
 *
 * <p>Assertions are on {@code Notification} rows read back through {@code GET /api/notifications},
 * never on the sweep's return value alone. The sweep returns 1 on every pass because the activity
 * stays overdue; what changes between passes is only what the alerts module decides to do about it.
 */
@SpringBootTest(properties = {
        "storeops.activities.sla.sweep.enabled=false",
        "storeops.alerts.sla.grace-period=PT0S"})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SlaEscalationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskService taskService;

    private ResultActions alertsFor(final String recipientId) throws Exception {
        return mockMvc.perform(get("/api/notifications").param("recipientId", recipientId))
                .andExpect(status().isOk());
    }

    private static String escalationFor(final String taskId) {
        return "$[?(@.alertType == 'ESCALATION' && @.sourceRef == '%s')]".formatted(taskId);
    }

    private static String breachFor(final String taskId) {
        return "$[?(@.alertType == 'SLA_BREACH' && @.sourceRef == '%s')]".formatted(taskId);
    }

    @Test
    @DisplayName("the first sweep tells the lead and nobody else")
    void theFirstSweepTellsTheLeadOnly() throws Exception {
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);

        // The seeded SHIFT_HANDOVER plus the new breach.
        alertsFor("user-003")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath(breachFor("task-001"), hasSize(1)));
        // Even with a zero grace period, the manager is not told on the first observation.
        alertsFor("user-002").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("the second sweep escalates to the store manager, once")
    void theSecondSweepEscalatesToTheStoreManager() throws Exception {
        taskService.publishOverdueBreaches();
        taskService.publishOverdueBreaches();

        alertsFor("user-002")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath(escalationFor("task-001"), hasSize(1)))
                .andExpect(jsonPath(escalationFor("task-001") + ".subject",
                        hasItem("SLA breach escalated")))
                .andExpect(jsonPath(escalationFor("task-001") + ".status", hasItem("PENDING")))
                .andExpect(jsonPath(escalationFor("task-001") + ".body",
                        hasItem(containsString("task-001"))));

        // The escalation goes to the manager, not also to the lead.
        alertsFor("user-003")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath(escalationFor("task-001"), hasSize(0)));
    }

    @Test
    @DisplayName("a third sweep changes nothing for either recipient")
    void aThirdSweepChangesNothing() throws Exception {
        taskService.publishOverdueBreaches();
        taskService.publishOverdueBreaches();
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);

        alertsFor("user-003")
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath(breachFor("task-001"), hasSize(1)));
        alertsFor("user-002")
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath(escalationFor("task-001"), hasSize(1)));
    }

    @Test
    @DisplayName("the sweep keeps reporting the breach even once both stages are done")
    void theSweepKeepsReportingTheUnresolvedActivity() {
        // The publisher is stateless by design: it restates that task-001 is still overdue on every
        // pass. All the de-duplication lives in the alerts module. If this ever returns 0, the
        // publisher has started tracking what it has already reported and both stages above become
        // untestable.
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);
        assertThat(taskService.publishOverdueBreaches()).isEqualTo(1);
    }
}
