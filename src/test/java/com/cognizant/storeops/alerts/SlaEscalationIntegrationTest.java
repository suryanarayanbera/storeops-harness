package com.cognizant.storeops.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.alerts.domain.AlertType;
import com.cognizant.storeops.alerts.domain.Notification;
import com.cognizant.storeops.alerts.service.NotificationService;
import com.cognizant.storeops.alerts.service.SlaEscalationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The whole escalation chain through the container: sweep, lead alert, escalation, and closure.
 *
 * <p>The grace period is set to zero so that two sweeps in the same millisecond reach the escalation
 * branch - the alternative would be a test that waits two hours. Zero is also the setting that would
 * expose the mistake this design avoids: escalation is gated on a second observation, not on elapsed
 * time alone, so even at zero the first sweep raises only the lead alert.
 *
 * <p>The override doubles as the binding assertion for {@code storeops.alerts.sla.grace-period}: if
 * {@code @ConfigurationPropertiesScan} were missing, the injected record below would not exist and this
 * context would not start.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "storeops.alerts.sla.grace-period=PT0S")
class SlaEscalationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskService taskService;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private SlaEscalationProperties escalationProperties;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private List<Notification> alertsFor(final String recipientId, final AlertType type, final String source) {
        return notificationService.list(recipientId, null).stream()
                .filter(notification -> notification.alertType() == type)
                .filter(notification -> source.equals(notification.sourceRef()))
                .toList();
    }

    private int episodeCount(final String taskId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM sla_breaches WHERE task_id = ?", Integer.class, taskId);
    }

    @Test
    @DisplayName("the configured grace period is bound from properties, not hard-coded")
    void gracePeriodIsBoundFromConfiguration() {
        assertThat(escalationProperties.gracePeriod()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("an unresolved breach alerts the lead, then escalates to the store manager, once each")
    void unresolvedBreachEscalatesToTheStoreManagerExactlyOnce() throws Exception {
        // Its own activity, not seed task-001: the sweep is global, so every test in this class
        // observes every other test's breaches. Assigned to user-004 in GROCERY at store-001, whose
        // lead is user-003 and whose store manager is user-002.
        final String taskId = createOverdueActivity();

        taskService.publishOverdueBreaches();

        assertThat(alertsFor("user-003", AlertType.SLA_BREACH, taskId)).hasSize(1);
        // Even with a zero grace period, one sighting is not persistence.
        assertThat(alertsFor("user-002", AlertType.ESCALATION, taskId)).isEmpty();

        taskService.publishOverdueBreaches();

        assertThat(alertsFor("user-002", AlertType.ESCALATION, taskId)).singleElement()
                .satisfies(escalation -> assertThat(escalation.subject())
                        .isEqualTo("Escalated: SLA breach unresolved on HIGH activity"));

        taskService.publishOverdueBreaches();

        assertThat(alertsFor("user-003", AlertType.SLA_BREACH, taskId)).hasSize(1);
        assertThat(alertsFor("user-002", AlertType.ESCALATION, taskId)).hasSize(1);
        assertThat(episodeCount(taskId)).isEqualTo(1);
    }

    @Test
    @DisplayName("resolving an activity through the API closes its episode before it can escalate")
    void resolvingAnActivityThroughTheApiClosesItsEpisode() throws Exception {
        final String taskId = createOverdueActivity();

        taskService.publishOverdueBreaches();
        assertThat(alertsFor("user-003", AlertType.SLA_BREACH, taskId)).hasSize(1);
        assertThat(episodeCount(taskId)).isEqualTo(1);

        mockMvc.perform(patch("/api/tasks/" + taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        // The episode is gone, so the sweep that follows has nothing to escalate - and the activity is
        // DONE, so it raises no event either.
        assertThat(episodeCount(taskId)).isZero();
        taskService.publishOverdueBreaches();

        assertThat(alertsFor("user-002", AlertType.ESCALATION, taskId)).isEmpty();
        assertThat(episodeCount(taskId)).isZero();
    }

    /** Creates a HIGH activity that is already past due, through the real endpoint. */
    private String createOverdueActivity() throws Exception {
        final String body = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Chilled aisle recovery","priority":"HIGH","category":"RESTOCKING",
                                 "storeId":"store-001","assigneeId":"user-004",
                                 "dueAt":"2026-01-07T08:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        final JsonNode created = objectMapper.readTree(body);
        return created.get("id").asText();
    }
}
