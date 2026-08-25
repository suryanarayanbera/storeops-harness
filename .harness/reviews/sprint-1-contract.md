# Sprint 1: Bulk Status Endpoint with Per-Task Independence

## Goal

Ship `PATCH /api/tasks/bulk-status` end to end inside the `activities` module: route, service,
DTOs and every error mapping. Each task in the batch is updated in its own transaction, so one
task's failure never touches another's write. Prove at the service level that each successful
update publishes exactly one `TaskStatusChangedEvent` and each failure publishes none.

Cross-module delivery of those events is Sprint 2. This sprint stops at the publish call.

## Scope

**Build**
* `TaskRoutes.bulkUpdateStatus` — `@PatchMapping("/bulk-status")`, `@Valid @RequestBody`, returns
  `200 OK` with a `BulkStatusUpdateResponse`. Mapping and response shaping only.
* `TaskBulkStatusService` in `com.cognizant.storeops.activities.service` — injects `TaskService`,
  loops the items, partitions into succeeded / failed.
* `BulkStatusUpdateRequest`, `BulkStatusUpdateItem`, `BulkStatusUpdateResponse`,
  `BulkStatusFailure` in `com.cognizant.storeops.activities.dto`.

**Do not touch**
* `TaskService` — read it, call it, do not change its signature or behaviour.
* `TaskStatusChangedEvent`, `EventBus`, `EventBusConfiguration`, `AlertEventListener`.
* `data.sql`, `TaskEntity`, `TaskRepository`, `PATCH /api/tasks/{id}`.

**Per-item algorithm in `TaskBulkStatusService`** (for each item, in request order):

1. `taskService.getById(item.taskId())` — throws `NotFoundError` for an unknown id.
2. Reject a target status that is neither `DONE` nor `BLOCKED` with `ValidationError`.
3. Reject a target status equal to the task's current status with
   `ConflictError("TASK_STATUS_UNCHANGED", ...)`.
4. `taskService.update(item.taskId(), new UpdateTaskRequest(item.status(), null, null))` — this
   enforces the terminal-`DONE` rule and publishes the event.
5. Catch **`AppError` only**. Map it to `BulkStatusFailure(taskId, code, message, statusCode)`
   and carry on with the next item.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: A clean batch updates every task**
* **GIVEN** seeded `task-001` is `TODO` and `task-002` is `IN_PROGRESS`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with
  `{"updates":[{"taskId":"task-001","status":"DONE"},{"taskId":"task-002","status":"BLOCKED"}]}`
* **THEN** the response is `200 OK`, `succeeded` has two entries in request order with `status`
  `DONE` then `BLOCKED`, and `failed` is empty
* **AND** a follow-up `GET /api/tasks/task-001` reads `DONE` and `GET /api/tasks/task-002` reads
  `BLOCKED`

**Scenario 2: An unknown id fails alone**
* **GIVEN** seeded `task-001` is `TODO` and no activity has the id `task-999`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with
  `{"updates":[{"taskId":"task-001","status":"DONE"},{"taskId":"task-999","status":"DONE"}]}`
* **THEN** the response is `200 OK`, `succeeded` holds `task-001` only, and `failed` holds one
  entry with `taskId` `task-999`, `code` `TASK_NOT_FOUND` and `statusCode` `404`
* **AND** `GET /api/tasks/task-001` reads `DONE` — the successful write was committed and not
  rolled back by its neighbour's failure

**Scenario 3: A forbidden transition fails alone, and order does not protect it**
* **GIVEN** seeded `task-003` is `DONE` (terminal) and `task-002` is `IN_PROGRESS`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with
  `{"updates":[{"taskId":"task-003","status":"BLOCKED"},{"taskId":"task-002","status":"DONE"}]}`
  — the failing task deliberately comes first
* **THEN** the response is `200 OK`, `failed` holds one entry with `taskId` `task-003`, `code`
  `TASK_TRANSITION_NOT_ALLOWED` and `statusCode` `409`, and `succeeded` holds `task-002`
* **AND** `GET /api/tasks/task-003` still reads `DONE` and `GET /api/tasks/task-002` reads `DONE`

**Scenario 4: An unsupported target status fails that task only**
* **GIVEN** seeded `task-001` is `TODO` and `task-002` is `IN_PROGRESS`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with
  `{"updates":[{"taskId":"task-001","status":"IN_PROGRESS"},{"taskId":"task-002","status":"DONE"}]}`
* **THEN** the response is `200 OK`, `failed` holds one entry with `taskId` `task-001`, `code`
  `VALIDATION_FAILED` and `statusCode` `400`, and `succeeded` holds `task-002`
* **AND** `GET /api/tasks/task-001` still reads `TODO`

**Scenario 5: A no-op transition is a failure, not a silent success**
* **GIVEN** seeded `task-004` is already `BLOCKED`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with
  `{"updates":[{"taskId":"task-004","status":"BLOCKED"}]}`
* **THEN** the response is `200 OK`, `succeeded` is empty, and `failed` holds one entry with
  `code` `TASK_STATUS_UNCHANGED` and `statusCode` `409`
* **AND** the `updatedAt` of `task-004` read back through `GET /api/tasks/task-004` is unchanged
  from its seeded value

**Scenario 6: A batch in which everything fails is still `200 OK`**
* **GIVEN** seeded `task-003` is `DONE` and no activity has the id `task-999`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with
  `{"updates":[{"taskId":"task-003","status":"DONE"},{"taskId":"task-999","status":"BLOCKED"}]}`
* **THEN** the response is `200 OK` with an empty `succeeded` array and two entries in `failed`,
  in request order
* **AND** the response body is a `BulkStatusUpdateResponse`, not an `ErrorResponse`

**Scenario 7: An empty batch is rejected as a whole request**
* **GIVEN** any database state
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with `{"updates":[]}`
* **THEN** the response is `400` with a standard `ErrorResponse` whose `code` is
  `VALIDATION_FAILED`
* **AND** the same happens for a payload of `{}` with `updates` absent

**Scenario 8: Duplicate task ids are rejected as a whole request**
* **GIVEN** seeded `task-001` is `TODO`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with
  `{"updates":[{"taskId":"task-001","status":"DONE"},{"taskId":"task-001","status":"BLOCKED"}]}`
* **THEN** the response is `400` with `code` `VALIDATION_FAILED`
* **AND** `GET /api/tasks/task-001` still reads `TODO` — no partial write happened

**Scenario 9: The batch size limit is enforced**
* **GIVEN** a payload containing 51 items
* **WHEN** it is sent to `PATCH /api/tasks/bulk-status`
* **THEN** the response is `400` with `code` `VALIDATION_FAILED`
* **AND** a payload of exactly 50 items is accepted and returns `200 OK`

**Scenario 10: Each success publishes exactly one event, each failure publishes none**
* **GIVEN** a `TaskBulkStatusService` wired to a real `TaskService` backed by
  `FakeTaskRepository` and `RecordingEventBus`, with `task-001` `TODO`/`HIGH`/`user-004` present
  and no task `task-999`
* **WHEN** the service is asked to move `task-001` to `DONE` and `task-999` to `DONE`
* **THEN** `RecordingEventBus` holds exactly one event, a `TaskStatusChangedEvent` with `taskId`
  `task-001`, `previousStatus` `"TODO"`, `newStatus` `"DONE"`, `priority` `"HIGH"` and
  `assigneeId` `user-004`
* **AND** every payload field carrying an enum is a `String`, not a `TaskStatus` or
  `TaskPriority`
* **AND** a batch whose every item fails leaves `RecordingEventBus` empty

**Scenario 11: The new path does not shadow the existing single-task path**
* **GIVEN** seeded `task-001` is `TODO`
* **WHEN** `PATCH /api/tasks/task-001` is sent with `{"status":"DONE"}`
* **THEN** the response is `200 OK` carrying a single `TaskResponse` with `status` `DONE`,
  exactly as before this sprint
* **AND** `PATCH /api/tasks/bulk-status` is routed to the bulk handler and is never interpreted
  as a single-task update for an activity whose id is the literal string `bulk-status`

## Architectural Guardrails

* **`TaskBulkStatusService.bulkUpdateStatus` must not be annotated `@Transactional`.** With an
  outer transaction open, each `taskService.update` call joins it, and the first `AppError`
  marks it rollback-only. The catch swallows the exception but the commit then fails with
  `UnexpectedRollbackException` and the whole batch is lost. Per-task independence requires one
  transaction per task, which is what you get by calling a `@Transactional` method with no
  transaction already active.
* **The loop must not live inside `TaskService`.** A `this.update(...)` self-invocation bypasses
  the Spring proxy, so no transaction opens and the `@TransactionalEventListener(AFTER_COMMIT)`
  subscribers never fire. Nothing throws and nothing is logged — Scenarios 1 to 9 would all
  still pass. The loop belongs in `TaskBulkStatusService`, calling the injected `TaskService`
  bean.
* **`TaskBulkStatusService` must not inject `EventBus` or call `eventBus.publish`.** The event is
  published by `TaskService.update` and nowhere else; that is what makes the bulk path
  identical to the single-task path rather than a parallel implementation that can drift.
* **Catch `AppError`, never `Exception` or `RuntimeException`.** A genuine bug must surface as a
  500 through `GlobalExceptionHandler`, not be reported as a per-task business failure.
* **No new `AppError` subtype.** `TASK_STATUS_UNCHANGED` is a code string passed to the existing
  `ConflictError`. ArchUnit rule 6b requires every `AppError` subtype to live in
  `shared/error`.
* **`TaskRoutes` holds no per-item logic.** No loop, no try/catch, no status checking in the
  routes layer — mapping, `@Valid`, and handing the request to the service. ArchUnit rule 5.
* **No import of `alerts` or `reports` from `activities`.** The `ESCALATION` alert for a blocked
  activity is the alerts module's decision, reached over the event bus. ArchUnit rule 3.
* **Everything goes in `routes`, `service` or `dto` inside `activities`.** No `utils` package,
  no helper class outside the module's package set.
