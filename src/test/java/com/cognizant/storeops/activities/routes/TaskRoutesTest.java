package com.cognizant.storeops.activities.routes;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
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
import com.cognizant.storeops.activities.dto.BulkStatusFailure;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateRequest;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateResponse;
import com.cognizant.storeops.activities.dto.CreateTaskRequest;
import com.cognizant.storeops.activities.dto.TaskResponse;
import com.cognizant.storeops.activities.dto.UpdateTaskRequest;
import com.cognizant.storeops.activities.service.TaskBulkStatusService;
import com.cognizant.storeops.activities.service.TaskService;
import com.cognizant.storeops.shared.error.ConflictError;
import com.cognizant.storeops.shared.error.NotFoundError;
import com.cognizant.storeops.shared.error.ValidationError;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

    @MockitoBean
    private TaskBulkStatusService taskBulkStatusService;

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

    // ------------------------------------------------------ PATCH /api/tasks/bulk-status

    /** A batch of {@code size} well-formed items, all targeting DONE. */
    private static String batchOf(final int size) {
        return IntStream.rangeClosed(1, size)
                .mapToObj(index -> "{\"taskId\":\"task-%03d\",\"status\":\"DONE\"}".formatted(index))
                .collect(Collectors.joining(",", "{\"updates\":[", "]}"));
    }

    @Test
    @DisplayName("PATCH /api/tasks/bulk-status returns 200 with the per-activity outcomes")
    void bulkStatusReturnsPerActivityOutcomes() throws Exception {
        when(taskBulkStatusService.bulkUpdateStatus(any(BulkStatusUpdateRequest.class)))
                .thenReturn(new BulkStatusUpdateResponse(
                        List.of(TaskResponse.from(sampleTask().withStatus(TaskStatus.DONE, NOW))),
                        List.of(new BulkStatusFailure("task-999", "TASK_NOT_FOUND", "not found", 404))));

        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"updates":[{"taskId":"task-001","status":"DONE"},
                                            {"taskId":"task-999","status":"DONE"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(1)))
                .andExpect(jsonPath("$.succeeded[0].id").value("task-001"))
                .andExpect(jsonPath("$.succeeded[0].status").value("DONE"))
                .andExpect(jsonPath("$.failed", hasSize(1)))
                .andExpect(jsonPath("$.failed[0].taskId").value("task-999"))
                .andExpect(jsonPath("$.failed[0].code").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.failed[0].statusCode").value(404));
    }

    @Test
    @DisplayName("PATCH /api/tasks/bulk-status rejects an empty batch with 400 VALIDATION_FAILED")
    void bulkStatusRejectsEmptyBatch() throws Exception {
        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.details[0]").value("updates: must contain at least one update"));

        verifyNoInteractions(taskBulkStatusService);
    }

    @Test
    @DisplayName("PATCH /api/tasks/bulk-status rejects a payload with no updates field at all")
    void bulkStatusRejectsMissingUpdatesField() throws Exception {
        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(taskBulkStatusService);
    }

    @Test
    @DisplayName("PATCH /api/tasks/bulk-status rejects an item with a blank id or a missing status")
    void bulkStatusRejectsMalformedItem() throws Exception {
        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":[{\"taskId\":\"  \"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details", hasSize(2)));

        verifyNoInteractions(taskBulkStatusService);
    }

    @Test
    @DisplayName("PATCH /api/tasks/bulk-status rejects a null item rather than letting it reach the service")
    void bulkStatusRejectsNullItem() throws Exception {
        // @Valid cascades into a collection but skips null elements, so without @NotNull on the
        // type argument this payload reached the service and threw a raw NullPointerException,
        // answering 500 INTERNAL_ERROR instead of 400.
        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.details[0]").value("updates[0]: must not be null"));

        verifyNoInteractions(taskBulkStatusService);
    }

    @Test
    @DisplayName("PATCH /api/tasks/bulk-status rejects a null item mixed in with valid ones")
    void bulkStatusRejectsNullItemAmongValidOnes() throws Exception {
        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":[{\"taskId\":\"task-001\",\"status\":\"DONE\"},null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(taskBulkStatusService);
    }

    @Test
    @DisplayName("PATCH /api/tasks/bulk-status rejects a 51st item but accepts a batch of exactly 50")
    void bulkStatusEnforcesTheBatchSizeLimit() throws Exception {
        when(taskBulkStatusService.bulkUpdateStatus(any(BulkStatusUpdateRequest.class)))
                .thenReturn(new BulkStatusUpdateResponse(List.of(), List.of()));

        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchOf(51)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("updates: must contain at most 50 updates"));

        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchOf(50)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded", hasSize(0)))
                .andExpect(jsonPath("$.failed", hasSize(0)));
    }

    @Test
    @DisplayName("PATCH /api/tasks/bulk-status surfaces a whole-batch ValidationError as 400")
    void bulkStatusSurfacesWholeBatchValidationError() throws Exception {
        when(taskBulkStatusService.bulkUpdateStatus(any(BulkStatusUpdateRequest.class)))
                .thenThrow(new ValidationError("A shift handover must not name the same activity twice",
                        List.of("updates: duplicate taskId 'task-001'")));

        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"updates":[{"taskId":"task-001","status":"DONE"},
                                            {"taskId":"task-001","status":"BLOCKED"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details[0]").value("updates: duplicate taskId 'task-001'"));
    }

    @Test
    @DisplayName("the bulk path is routed to the bulk handler, not read as an activity id")
    void bulkPathIsNotReadAsAnActivityId() throws Exception {
        when(taskBulkStatusService.bulkUpdateStatus(any(BulkStatusUpdateRequest.class)))
                .thenReturn(new BulkStatusUpdateResponse(List.of(), List.of()));

        mockMvc.perform(patch("/api/tasks/bulk-status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":[{\"taskId\":\"task-001\",\"status\":\"DONE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").exists())
                .andExpect(jsonPath("$.failed").exists());

        // The single-activity handler must not have been reached with "bulk-status" as the id.
        verifyNoInteractions(taskService);
    }
}
