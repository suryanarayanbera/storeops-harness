package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.shared.events.EventBus;
import com.cognizant.storeops.shared.events.ProgrammeTemplateRequestedEvent;
import com.cognizant.storeops.shared.events.TemplateTaskDefinition;
import com.cognizant.storeops.support.FailingTemplateSubscriber;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The cloned activities, end to end, against the real wiring and the real seed.
 *
 * <p>{@code TaskServiceTest} proves the skip and fallback rules over fakes and
 * {@code TaskTemplateEventListenerTest} drives the handler directly. Neither can prove the one thing
 * that decides whether this feature works at all: that the handler is reached, on its own transaction,
 * only after the publisher commits. That needs the real container, and it is what this class is for.
 *
 * <p>The SLA sweep is disabled so a background cycle cannot publish events mid-test, and
 * {@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} re-runs {@code data.sql} before each method because
 * every assertion here counts rows in {@code tasks}.
 */
@SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")
@AutoConfigureMockMvc
@Import(FailingTemplateSubscriber.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PlanogramTemplateDeliveryIntegrationTest {

    private static final String STANDARD_BODY = "{\"templateId\":\"PLANOGRAM_STANDARD\",\"requestedBy\":\"user-005\"}";

    private static final String ENTRANCE_BAY = "Reset entrance promotional bay";
    private static final String GROCERY_AISLES = "Reset grocery aisle planograms";
    private static final String SHELF_LABELS = "Verify shelf-edge labelling";
    private static final String PHOTOGRAPH_BAYS = "Photograph completed bays for compliance";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskService taskService;

    @Autowired
    private EventBus eventBus;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private FailingTemplateSubscriber failingSubscriber;

    private void applyStandardTemplateToProjectTwo() throws Exception {
        mockMvc.perform(post("/api/projects/project-002/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STANDARD_BODY))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("the POST clones all four template activities into project-002")
    void thePostClonesTheStandardTemplate() throws Exception {
        applyStandardTemplateToProjectTwo();

        // Asserted on the persisted rows, never on the 202: that status comes back whether or not the
        // listener ran, whether or not it was transactional, and whether or not it could write.
        final List<Task> cloned = taskService.findByProjectId("project-002").stream()
                .filter(task -> !"task-004".equals(task.id()))
                .toList();

        assertThat(cloned).hasSize(4);
        assertThat(cloned).extracting(Task::title)
                .containsExactlyInAnyOrder(ENTRANCE_BAY, GROCERY_AISLES, SHELF_LABELS, PHOTOGRAPH_BAYS);
        assertThat(cloned).allSatisfy(task -> {
            assertThat(task.status()).isEqualTo(TaskStatus.TODO);
            assertThat(task.category()).isEqualTo(TaskCategory.PLANOGRAM);
            assertThat(task.projectId()).isEqualTo("project-002");
            assertThat(task.storeId()).isEqualTo("store-002");
            assertThat(task.dueAt()).isNull();
            assertThat(task.createdAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("the clones carry the template priorities and the resolved department assignments")
    void theClonesCarryPrioritiesAndAssignments() throws Exception {
        applyStandardTemplateToProjectTwo();

        final List<Task> cloned = taskService.findByProjectId("project-002");

        // user-005 is project-002's only member and works in OPERATIONS, so the two GROCERY lines
        // land unassigned - the same outcome the 202 body reported.
        assertThat(cloned).filteredOn(task -> ENTRANCE_BAY.equals(task.title())).singleElement()
                .satisfies(task -> {
                    assertThat(task.priority()).isEqualTo(TaskPriority.HIGH);
                    assertThat(task.assigneeId()).isEqualTo("user-005");
                });
        assertThat(cloned).filteredOn(task -> GROCERY_AISLES.equals(task.title())).singleElement()
                .satisfies(task -> {
                    assertThat(task.priority()).isEqualTo(TaskPriority.HIGH);
                    assertThat(task.assigneeId()).isNull();
                });
        assertThat(cloned).filteredOn(task -> SHELF_LABELS.equals(task.title())).singleElement()
                .satisfies(task -> {
                    assertThat(task.priority()).isEqualTo(TaskPriority.MEDIUM);
                    assertThat(task.assigneeId()).isNull();
                });
        assertThat(cloned).filteredOn(task -> PHOTOGRAPH_BAYS.equals(task.title())).singleElement()
                .satisfies(task -> {
                    assertThat(task.priority()).isEqualTo(TaskPriority.LOW);
                    assertThat(task.assigneeId()).isEqualTo("user-005");
                });
    }

    @Test
    @DisplayName("GET /api/tasks shows five activities at store-002: the seeded one plus four clones")
    void theClonesAreVisibleThroughTheApi() throws Exception {
        applyStandardTemplateToProjectTwo();

        mockMvc.perform(get("/api/tasks").param("storeId", "store-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));

        mockMvc.perform(get("/api/tasks").param("storeId", "store-002").param("category", "PLANOGRAM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    @DisplayName("a repeat POST answers 202 with the same count and creates nothing")
    void aRepeatPostCreatesNothing() throws Exception {
        applyStandardTemplateToProjectTwo();

        mockMvc.perform(post("/api/projects/project-002/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STANDARD_BODY))
                .andExpect(status().isAccepted())
                // taskCount is the template expansion, not a count of rows written.
                .andExpect(jsonPath("$.taskCount").value(4));

        assertThat(taskService.findByProjectId("project-002")).hasSize(5);
        assertThat(taskService.findByStoreId("store-002")).hasSize(5);
    }

    @Test
    @DisplayName("a title already on the programme is skipped, and the pre-existing activity is untouched")
    void anExistingTitleIsSkipped() throws Exception {
        // Raised by hand as GENERAL, so the skip cannot be category-scoped and still pass.
        final String createdId = objectIdFrom(mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + SHELF_LABELS + "\",\"storeId\":\"store-001\","
                                + "\"projectId\":\"project-001\",\"category\":\"GENERAL\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\",\"requestedBy\":\"user-002\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskCount").value(4));

        // project-001 seeds task-001 and task-002, plus the hand-raised one, plus three clones.
        assertThat(taskService.findByProjectId("project-001")).hasSize(6);
        assertThat(taskService.findByProjectId("project-001"))
                .filteredOn(task -> SHELF_LABELS.equals(task.title()))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.id()).isEqualTo(createdId);
                    assertThat(task.category()).isEqualTo(TaskCategory.GENERAL);
                });
    }

    @Test
    @DisplayName("a title differing only in case and padding is still treated as present")
    void aLooselyMatchingTitleIsSkipped() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  reset GROCERY aisle planograms  \",\"storeId\":\"store-001\","
                                + "\"projectId\":\"project-001\",\"category\":\"PLANOGRAM\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\"}"))
                .andExpect(status().isAccepted());

        assertThat(taskService.findByProjectId("project-001"))
                .filteredOn(task -> GROCERY_AISLES.equalsIgnoreCase(task.title().trim()))
                .hasSize(1);
        // Two seeds + the hand-raised one + three clones.
        assertThat(taskService.findByProjectId("project-001")).hasSize(6);
    }

    @Test
    @DisplayName("an event published inside a transaction is delivered on commit and writes")
    void anEventPublishedInATransactionIsDelivered() {
        transactionTemplate.executeWithoutResult(status -> eventBus.publish(templateEvent()));

        // Reaching here at all proves the handler ran on its own REQUIRES_NEW transaction: the
        // publishing transaction had already committed, so a write joining it would never flush.
        assertThat(taskService.findByProjectId("project-001"))
                .filteredOn(task -> "Bay 7 gondola reset".equals(task.title()))
                .singleElement()
                .satisfies(task -> {
                    assertThat(task.status()).isEqualTo(TaskStatus.TODO);
                    assertThat(task.priority()).isEqualTo(TaskPriority.CRITICAL);
                    assertThat(task.storeId()).isEqualTo("store-001");
                });
    }

    @Test
    @DisplayName("a rolled-back transaction creates no activity, which is the point of after-commit dispatch")
    void aRolledBackTransactionCreatesNothing() {
        final int before = taskService.findByProjectId("project-001").size();

        transactionTemplate.executeWithoutResult(status -> {
            eventBus.publish(templateEvent());
            status.setRollbackOnly();
        });

        assertThat(taskService.findByProjectId("project-001")).hasSize(before);
    }

    @Test
    @DisplayName("cloning raises no alert, because the activities are born TODO")
    void cloningRaisesNoAlert() throws Exception {
        applyStandardTemplateToProjectTwo();

        // A TaskStatusChangedEvent would reach the alerts module and, for a BLOCKED activity, raise an
        // ESCALATION. Nothing transitioned here, so the alert table must be exactly as data.sql left
        // it: one SHIFT_HANDOVER to user-003 and nothing else.
        mockMvc.perform(get("/api/notifications").param("recipientId", "user-005"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("notification-001"));
    }

    @Test
    @DisplayName("a failing peer subscriber does not stop the clone")
    void aFailingPeerSubscriberDoesNotStopTheClone() throws Exception {
        final int failuresBefore = failingSubscriber.invocationCount();

        applyStandardTemplateToProjectTwo();

        // Counted, so this cannot pass because dispatch was broken and nothing ran at all.
        assertThat(failingSubscriber.invocationCount()).isEqualTo(failuresBefore + 1);
        // And the real listener's rows are still there: one broken subscriber does not suppress a
        // sibling on the same event. That containment is the ErrorHandler in EventBusConfiguration.
        assertThat(taskService.findByProjectId("project-002")).hasSize(5);
    }

    private static ProgrammeTemplateRequestedEvent templateEvent() {
        return new ProgrammeTemplateRequestedEvent(
                "project-001", "store-001", "PLANOGRAM_STANDARD", "user-002",
                List.of(new TemplateTaskDefinition("Bay 7 gondola reset", "ad-hoc reset",
                        "PLANOGRAM", "CRITICAL", "user-003")),
                Instant.parse("2026-02-01T10:00:00Z"));
    }

    /** Pulls the {@code id} out of a created-activity response body without a JSON parser. */
    private static String objectIdFrom(final String body) {
        final int start = body.indexOf("\"id\":\"") + 6;
        return body.substring(start, body.indexOf('"', start));
    }
}
