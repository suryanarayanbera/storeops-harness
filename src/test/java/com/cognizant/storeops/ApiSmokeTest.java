package com.cognizant.storeops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end pass over all nine endpoints against the real wiring.
 *
 * <p>The first test is the capstone document's own baseline check: {@code GET /api/tasks} must
 * answer 200. The last is the one that matters architecturally - a status change reaching the alerts
 * module through the event bus, with no import between the two modules.
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)} because {@code postProject} creates a programme and there
 * is no endpoint to remove it. The H2 database lives for the whole JVM
 * ({@code DB_CLOSE_DELAY=-1}) while Spring caches one context per test configuration, so without
 * this the extra row leaks into every later class sharing this context and any assertion counting a
 * mutable table becomes a function of context build order. {@code AFTER_CLASS} rather than
 * {@code BEFORE_EACH_TEST_METHOD}: one rebuild at the end cleans up for everyone downstream, where
 * per-method would cost eleven.
 */
@SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("1. GET /api/tasks -> 200")
    void listTasks() throws Exception {
        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)));
    }

    @Test
    @DisplayName("2. POST /api/tasks -> 201")
    void createTask() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Verify chilled cabinet temperatures","storeId":"store-001",
                                 "priority":"CRITICAL","category":"COMPLIANCE","assigneeId":"user-003"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").value("CRITICAL"));
    }

    @Test
    @DisplayName("3. GET /api/tasks/{id} -> 200, and 404 for an unknown id")
    void getTask() throws Exception {
        mockMvc.perform(get("/api/tasks/task-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("task-001"));

        mockMvc.perform(get("/api/tasks/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404));
    }

    @Test
    @DisplayName("4. PATCH /api/tasks/{id} -> 200")
    void updateTask() throws Exception {
        mockMvc.perform(patch("/api/tasks/task-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("5. GET /api/projects -> 200")
    void listProjects() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)));
    }

    @Test
    @DisplayName("6. POST /api/projects -> 201")
    void createProject() throws Exception {
        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Autumn refit","description":"Bay relayout",
                                 "storeId":"store-001","regionId":"region-north","ownerId":"user-002"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PLANNED"))
                .andExpect(jsonPath("$.members[0].userId").value("user-002"));
    }

    @Test
    @DisplayName("7. GET /api/users/{id} -> 200")
    void getUser() throws Exception {
        mockMvc.perform(get("/api/users/user-003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("user-003"))
                .andExpect(jsonPath("$.role").value("DEPARTMENT_LEAD"));
    }

    @Test
    @DisplayName("8. GET /api/notifications -> 200")
    void listNotifications() throws Exception {
        mockMvc.perform(get("/api/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", greaterThan(0)));
    }

    @Test
    @DisplayName("9. GET /api/reports/store/{storeId} -> 200 with aggregated metrics")
    void storeSummary() throws Exception {
        mockMvc.perform(get("/api/reports/store/store-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value("store-001"))
                .andExpect(jsonPath("$.totalActivities", greaterThan(0)))
                .andExpect(jsonPath("$.headcount", greaterThan(0)))
                .andExpect(jsonPath("$.activitiesByStatus").exists());
    }

    @Test
    @DisplayName("blocking an activity produces an alert without any activities-to-alerts import")
    void blockingATaskRaisesAnAlertViaTheEventBus() throws Exception {
        final String created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Planogram bay 7 reset","storeId":"store-001",
                                 "priority":"HIGH","category":"PLANOGRAM","assigneeId":"user-004"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        final String taskId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(patch("/api/tasks/{id}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BLOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"));

        final String alerts = mockMvc.perform(get("/api/notifications").param("recipientId", "user-004"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        final JsonNode raised = objectMapper.readTree(alerts);
        assertThat(raised).isNotEmpty();
        assertThat(raised.toString()).contains("ESCALATION").contains(taskId);
    }

    @Test
    @DisplayName("an unmapped path returns 404 in the StoreOps error shape, not a Spring default body")
    void unmappedPathReturnsTypedError() throws Exception {
        mockMvc.perform(get("/api/tasks/task-001/not-a-subresource"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.path").value("/api/tasks/task-001/not-a-subresource"));
    }
}
