package com.cognizant.storeops.activities.routes;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cognizant.storeops.activities.domain.Task;
import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.dto.CreateTaskRequest;
import com.cognizant.storeops.activities.dto.UpdateTaskRequest;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Routes-layer slice test for the activities module.
 *
 * <p>The service is mocked, so what is under test is exactly the routes layer's remit: mapping,
 * status codes, bean validation and the error body shape. {@code GlobalExceptionHandler} is a
 * {@code @RestControllerAdvice} and so is picked up by the slice automatically.
 */
@WebMvcTest(TaskRoutes.class)
class TaskRoutesTest {

    private static final Instant NOW = Instant.parse("2026-02-01T10:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TaskService taskService;

    private static Task sampleTask() {
        return new Task("task-001", "Restock aisle 4 beverages", "Weekend promotion overflow",
                TaskStatus.TODO, TaskPriority.HIGH, TaskCategory.RESTOCKING,
                "store-001", "project-001", "user-004", NOW.plusSeconds(3_600), NOW, NOW);
    }

    @Test
    @DisplayName("GET /api/tasks returns 200 with the activity list")
    void listReturnsTasks() throws Exception {
        when(taskService.list(null, null, null, null)).thenReturn(List.of(sampleTask()));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("task-001"))
                .andExpect(jsonPath("$[0].status").value("TODO"))
                .andExpect(jsonPath("$[0].priority").value("HIGH"));
    }

    @Test
    @DisplayName("GET /api/tasks passes query filters through to the service")
    void listAppliesFilters() throws Exception {
        when(taskService.list(TaskStatus.BLOCKED, TaskPriority.CRITICAL, TaskCategory.AUDIT, "store-002"))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/tasks")
                        .param("status", "BLOCKED")
                        .param("priority", "CRITICAL")
                        .param("category", "AUDIT")
                        .param("storeId", "store-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("GET /api/tasks rejects an unknown enum value with a 400 ValidationError")
    void listRejectsUnknownEnum() throws Exception {
        mockMvc.perform(get("/api/tasks").param("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.statusCode").value(400));
    }

    @Test
    @DisplayName("GET /api/tasks/{id} returns 404 with the typed error body when missing")
    void getMissingTaskReturnsTypedNotFound() throws Exception {
        when(taskService.getById("nope")).thenThrow(NotFoundError.of("Task", "nope"));

        mockMvc.perform(get("/api/tasks/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.statusCode").value(404))
                .andExpect(jsonPath("$.path").value("/api/tasks/nope"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("POST /api/tasks returns 201 with a Location header")
    void createReturnsCreated() throws Exception {
        when(taskService.create(any(CreateTaskRequest.class))).thenReturn(sampleTask());

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Restock aisle 4 beverages","storeId":"store-001",
                                 "priority":"HIGH","category":"RESTOCKING","assigneeId":"user-004"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/tasks/task-001"))
                .andExpect(jsonPath("$.id").value("task-001"));
    }

    @Test
    @DisplayName("POST /api/tasks returns 400 with field details when the payload is invalid")
    void createRejectsInvalidPayload() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.details", hasSize(2)))
                .andExpect(jsonPath("$.details[0]").value("storeId: must not be blank"))
                .andExpect(jsonPath("$.details[1]").value("title: must not be blank"));
    }

    @Test
    @DisplayName("POST /api/tasks returns 400 when the body is missing")
    void createRejectsMissingBody() throws Exception {
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("PATCH /api/tasks/{id} returns 200 with the updated activity")
    void updateReturnsOk() throws Exception {
        when(taskService.update(eq("task-001"), any(UpdateTaskRequest.class)))
                .thenReturn(sampleTask().withStatus(TaskStatus.DONE, NOW.plusSeconds(60)));

        mockMvc.perform(patch("/api/tasks/task-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    @DisplayName("PATCH /api/tasks/{id} surfaces a ConflictError as 409")
    void updateSurfacesConflict() throws Exception {
        when(taskService.update(eq("task-003"), any(UpdateTaskRequest.class)))
                .thenThrow(new ConflictError("TASK_TRANSITION_NOT_ALLOWED", "A DONE activity cannot move to TODO"));

        mockMvc.perform(patch("/api/tasks/task-003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_TRANSITION_NOT_ALLOWED"))
                .andExpect(jsonPath("$.statusCode").value(409));
    }

    @Test
    @DisplayName("PATCH /api/tasks/{id} surfaces a ValidationError as 400")
    void updateSurfacesValidationError() throws Exception {
        when(taskService.update(eq("task-001"), any(UpdateTaskRequest.class)))
                .thenThrow(new ValidationError("Update must change at least one field"));

        mockMvc.perform(patch("/api/tasks/task-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
