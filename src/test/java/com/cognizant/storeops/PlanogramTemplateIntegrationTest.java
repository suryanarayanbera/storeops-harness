package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.programmes.service.ProjectService;
import com.cognizant.storeops.shared.events.TemplateTaskDefinition;
import com.cognizant.storeops.support.FailingTemplateSubscriber;
import com.cognizant.storeops.support.RecordingTemplateSubscriber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/projects/{id}/templates} against the real wiring and the real seed.
 *
 * <p>{@code ProjectServiceTest} proves the department resolution over fakes and
 * {@code ProjectRoutesTest} proves the binding over a mocked service. Neither can prove the two
 * things that matter most about this endpoint: that the event actually reaches an after-commit
 * subscriber, and that Sprint 1 creates no activity of its own. The first needs a real transaction,
 * the second needs the real {@code tasks} table.
 *
 * <p>The SLA sweep is disabled so a background cycle cannot publish events mid-test.
 * {@code @DirtiesContext(BEFORE_EACH_TEST_METHOD)} re-runs {@code data.sql} before each method,
 * because the assertions here count rows in {@code tasks} and other classes in the suite create
 * activities.
 */
@SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")
@AutoConfigureMockMvc
@Import({RecordingTemplateSubscriber.class, FailingTemplateSubscriber.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class PlanogramTemplateIntegrationTest {

    private static final String STANDARD_BODY = "{\"templateId\":\"PLANOGRAM_STANDARD\",\"requestedBy\":\"user-005\"}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private RecordingTemplateSubscriber subscriber;

    @Autowired
    private FailingTemplateSubscriber failingSubscriber;

    @Test
    @DisplayName("applying the standard template to project-002 answers 202 with the resolved assignments")
    void applyingTheTemplateAnswersAcceptedWithAssignments() throws Exception {
        mockMvc.perform(post("/api/projects/project-002/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STANDARD_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.projectId").value("project-002"))
                .andExpect(jsonPath("$.templateId").value("PLANOGRAM_STANDARD"))
                .andExpect(jsonPath("$.taskCount").value(4))
                .andExpect(jsonPath("$.assignments", hasSize(4)))
                // user-005 is project-002's only member and works in OPERATIONS, so the two GROCERY
                // lines come back unassigned rather than failing the request.
                .andExpect(jsonPath("$.assignments[0].title").value("Reset entrance promotional bay"))
                .andExpect(jsonPath("$.assignments[0].assigneeId").value("user-005"))
                .andExpect(jsonPath("$.assignments[1].department").value("GROCERY"))
                .andExpect(jsonPath("$.assignments[1].assigneeId").doesNotExist())
                .andExpect(jsonPath("$.assignments[2].assigneeId").doesNotExist())
                .andExpect(jsonPath("$.assignments[3].assigneeId").value("user-005"))
                .andExpect(jsonPath("$.assignments[3].priority").value("LOW"));
    }

    @Test
    @DisplayName("the endpoint writes no activity itself; the four clones arrive from the activities module")
    void theEndpointItselfWritesNoActivity() throws Exception {
        mockMvc.perform(post("/api/projects/project-002/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STANDARD_BODY))
                .andExpect(status().isAccepted());

        // Five, not one: seeded task-004 plus four clones. This expectation read `hasSize(1)` while
        // Sprint 1 stood alone and nothing consumed the event; the flip to five is the proof that
        // TaskTemplateEventListener is wired. Do not "fix" it back.
        //
        // What the programmes module still owns is none of it. The rows below were written by
        // activities, on its own transaction, after this request committed - which is why this class
        // asserts the 202 body and the delivered event, and
        // PlanogramTemplateDeliveryIntegrationTest asserts what the rows contain.
        mockMvc.perform(get("/api/tasks").param("storeId", "store-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)));
        mockMvc.perform(get("/api/tasks").param("storeId", "store-002").param("category", "AUDIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("task-004"));
    }

    @Test
    @DisplayName("the POST delivers PROGRAMME_TEMPLATE_REQUESTED after commit, so the publisher is transactional")
    void theTemplateEventIsDeliveredAfterCommit() throws Exception {
        subscriber.clear();

        mockMvc.perform(post("/api/projects/project-002/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STANDARD_BODY))
                .andExpect(status().isAccepted());

        // This is the assertion that pins @Transactional on ProjectService.applyTemplate. Spring
        // skips an AFTER_COMMIT subscriber outright when no transaction is active, so an event
        // arriving here proves one existed and committed. RecordingEventBus cannot prove it: it
        // records at publish time and looks identical either way.
        assertThat(subscriber.received()).singleElement().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo("PROGRAMME_TEMPLATE_REQUESTED");
            assertThat(event.projectId()).isEqualTo("project-002");
            assertThat(event.storeId()).isEqualTo("store-002");
            assertThat(event.templateId()).isEqualTo("PLANOGRAM_STANDARD");
            assertThat(event.requestedBy()).isEqualTo("user-005");
            assertThat(event.items()).hasSize(4);
            assertThat(event.items()).allSatisfy(item -> assertThat(item.category()).isEqualTo("PLANOGRAM"));
            assertThat(event.items()).extracting(TemplateTaskDefinition::priority)
                    .containsExactly("HIGH", "HIGH", "MEDIUM", "LOW");
            assertThat(event.items()).extracting(TemplateTaskDefinition::assigneeId)
                    .containsExactly("user-005", null, null, "user-005");
        });
    }

    @Test
    @DisplayName("a rejected request delivers no template event")
    void aRejectedRequestDeliversNoEvent() throws Exception {
        subscriber.clear();

        mockMvc.perform(post("/api/projects/project-999/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));

        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_DELUXE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\",\"requestedBy\":\"user-999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(subscriber.received()).isEmpty();
    }

    @Test
    @DisplayName("a closed programme is refused with PROGRAMME_CLOSED and delivers no event")
    void aClosedProgrammeIsRefused() throws Exception {
        // Closed through the real service, so the state under test is one the application can reach.
        projectService.close("project-001", "user-002");
        subscriber.clear();

        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROGRAMME_CLOSED"))
                .andExpect(jsonPath("$.statusCode").value(409));

        assertThat(subscriber.received()).isEmpty();
    }

    @Test
    @DisplayName("applying the template to project-001 puts the GROCERY lines on its department lead")
    void theDepartmentLeadOnProjectOneTakesTheGroceryLines() throws Exception {
        // project-001's members are user-002 (OPERATIONS), user-003 (GROCERY, DEPARTMENT_LEAD) and
        // user-004 (GROCERY, ASSOCIATE). The lead wins despite user-004 sorting later by id.
        mockMvc.perform(post("/api/projects/project-001/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\",\"requestedBy\":\"user-002\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.assignments[0].assigneeId").value("user-002"))
                .andExpect(jsonPath("$.assignments[1].assigneeId").value("user-003"))
                .andExpect(jsonPath("$.assignments[2].assigneeId").value("user-003"))
                .andExpect(jsonPath("$.assignments[3].assigneeId").value("user-002"));
    }

    @Test
    @DisplayName("an omitted requestedBy is recorded as api on the delivered event")
    void anOmittedRequesterIsRecordedAsApi() throws Exception {
        subscriber.clear();

        mockMvc.perform(post("/api/projects/project-002/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"PLANOGRAM_STANDARD\"}"))
                .andExpect(status().isAccepted());

        assertThat(subscriber.received()).singleElement()
                .satisfies(event -> assertThat(event.requestedBy()).isEqualTo("api"));
    }

    @Test
    @DisplayName("a failing subscriber breaks neither the caller's POST nor the other subscriber")
    void aFailingSubscriberIsContained() throws Exception {
        final int failuresBefore = failingSubscriber.invocationCount();
        subscriber.clear();

        mockMvc.perform(post("/api/projects/project-002/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(STANDARD_BODY))
                .andExpect(status().isAccepted());

        // Counted, so this cannot pass merely because dispatch was broken and nothing ran.
        assertThat(failingSubscriber.invocationCount()).isEqualTo(failuresBefore + 1);
        // And the sibling still received its copy: one broken subscriber does not suppress a peer on
        // the same event. Remove the ErrorHandler from EventBusConfiguration and the POST 500s.
        assertThat(subscriber.received()).hasSize(1);
    }

    @Test
    @DisplayName("a blank templateId is a 400 from bean validation, before any programme lookup")
    void aBlankTemplateIdIsRejected() throws Exception {
        subscriber.clear();

        // project-999 does not exist, yet the answer is 400 rather than 404: the body is rejected on
        // binding, so the service is never entered.
        mockMvc.perform(post("/api/projects/project-999/templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templateId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("templateId: must not be blank"));

        assertThat(subscriber.received()).isEmpty();
    }
}
