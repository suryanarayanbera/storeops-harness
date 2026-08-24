package com.cognizant.storeops.activities.routes;

import com.cognizant.storeops.activities.domain.TaskCategory;
import com.cognizant.storeops.activities.domain.TaskPriority;
import com.cognizant.storeops.activities.domain.TaskStatus;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateRequest;
import com.cognizant.storeops.activities.dto.BulkStatusUpdateResponse;
import com.cognizant.storeops.activities.dto.CreateTaskRequest;
import com.cognizant.storeops.activities.dto.TaskResponse;
import com.cognizant.storeops.activities.dto.UpdateTaskRequest;
import com.cognizant.storeops.activities.service.TaskService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface of the activities module.
 *
 * <p>Routes layer: mapping, bean validation and response shaping. Every branch that needs to know a
 * rule - what a default priority is, whether a transition is legal, whether an assignee exists -
 * lives in {@link TaskService}.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskRoutes {

    private final TaskService taskService;

    public TaskRoutes(final TaskService taskService) {
        this.taskService = taskService;
    }

    /** Endpoint 1: {@code GET /api/tasks}. */
    @GetMapping
    public ResponseEntity<List<TaskResponse>> listTasks(
            @RequestParam(required = false) final TaskStatus status,
            @RequestParam(required = false) final TaskPriority priority,
            @RequestParam(required = false) final TaskCategory category,
            @RequestParam(required = false) final String storeId) {
        final List<TaskResponse> body = taskService.list(status, priority, category, storeId).stream()
                .map(TaskResponse::from)
                .toList();
        return ResponseEntity.ok(body);
    }

    /** Endpoint 2: {@code POST /api/tasks}. */
    @PostMapping
    public ResponseEntity<TaskResponse> createTask(@Valid @RequestBody final CreateTaskRequest request) {
        final TaskResponse created = TaskResponse.from(taskService.create(request));
        return ResponseEntity.created(URI.create("/api/tasks/" + created.id())).body(created);
    }

    /** Endpoint 3: {@code GET /api/tasks/{id}}. */
    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable final String id) {
        return ResponseEntity.ok(TaskResponse.from(taskService.getById(id)));
    }

    /** Endpoint 4: {@code PATCH /api/tasks/{id}}. A status change raises a domain event downstream. */
    @PatchMapping("/{id}")
    public ResponseEntity<TaskResponse> updateTask(
            @PathVariable final String id,
            @Valid @RequestBody final UpdateTaskRequest request) {
        return ResponseEntity.ok(TaskResponse.from(taskService.update(id, request)));
    }

    /**
     * Endpoint 5: {@code PATCH /api/tasks/bulk-status}. Shift handover - each activity in the batch
     * succeeds or fails alone.
     *
     * <p>Always {@code 207 Multi-Status}, including for an all-success or an all-failure batch: the
     * per-activity report is the answer either way, and one endpoint reporting one status is easier to
     * consume than a status that shifts with the contents of the body. A malformed batch is still
     * rejected whole, by bean validation, with the usual {@code 400}.
     *
     * <p>The literal {@code bulk-status} segment outranks the {@code /{id}} pattern in Spring's path
     * comparator, so this handler is reached and no ambiguous mapping is registered.
     */
    @PatchMapping("/bulk-status")
    public ResponseEntity<BulkStatusUpdateResponse> bulkUpdateStatus(
            @Valid @RequestBody final BulkStatusUpdateRequest request) {
        return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(taskService.bulkUpdateStatus(request));
    }
}
