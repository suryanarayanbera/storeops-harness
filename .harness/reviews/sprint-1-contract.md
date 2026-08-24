# Sprint 1: Bulk Status Update for Shift Handover

## Goal

Add `PATCH /api/tasks/bulk-status` to the `activities` module. It applies each `(taskId, status)` pair
independently, always responds `207 Multi-Status` with a per-task report, and publishes one
`TaskStatusChangedEvent` for each activity whose status actually changed. A failing item must not
affect any other item, and must not prevent the batch from committing.

Deliverables:

* `activities/dto/BulkStatusUpdateRequest` — `List<BulkStatusUpdateItem> updates`, `@NotNull`,
  `@Size(min = 1, max = 50)`, `@Valid` on the elements
* `activities/dto/BulkStatusUpdateItem` — `String taskId` (`@NotBlank`), `TaskStatus status` (`@NotNull`)
* `activities/dto/BulkStatusUpdateSuccess` — `taskId`, `previousStatus`, `newStatus` (enum names as
  `String`), `boolean changed`
* `activities/dto/BulkStatusUpdateFailure` — `taskId`, `code`, `message`, `int statusCode`
* `activities/dto/BulkStatusUpdateResponse` — `List<BulkStatusUpdateSuccess> succeeded`,
  `List<BulkStatusUpdateFailure> failed`
* `TaskService.bulkUpdateStatus(BulkStatusUpdateRequest)` — `@Transactional`, returns the response DTO
* `TaskRoutes.bulkUpdateStatus(...)` — `@PatchMapping("/bulk-status")`, `@Valid @RequestBody`,
  `ResponseEntity.status(HttpStatus.MULTI_STATUS)`

Both lists preserve request order. Timestamps come from the injected `Clock`, never `Instant.now()`.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: A clean handover batch updates every activity**
* **GIVEN** seed activity `task-001` is `TODO` and `task-002` is `IN_PROGRESS`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with
  `{"updates":[{"taskId":"task-001","status":"DONE"},{"taskId":"task-002","status":"BLOCKED"}]}`
* **THEN** the response is `207` with `failed` empty and `succeeded` holding two entries in request
  order: `("task-001","TODO","DONE",changed=true)` and `("task-002","IN_PROGRESS","BLOCKED",changed=true)`
* **AND** the database reads `task-001` as `DONE` and `task-002` as `BLOCKED`
* **AND** exactly two `TaskStatusChangedEvent`s are published, carrying
  `(taskId="task-001", previousStatus="TODO", newStatus="DONE", storeId="store-001", priority="HIGH", assigneeId="user-004")`
  and
  `(taskId="task-002", previousStatus="IN_PROGRESS", newStatus="BLOCKED", storeId="store-001", priority="MEDIUM", assigneeId="user-003")`

**Scenario 2: An unknown id fails only its own entry**
* **GIVEN** seed activity `task-001` is `TODO` and no activity has id `task-999`
* **WHEN** the batch is `[{"taskId":"task-999","status":"DONE"},{"taskId":"task-001","status":"DONE"}]`
* **THEN** the response is `207`; `failed` holds one entry
  `("task-999", code="TASK_NOT_FOUND", statusCode=404)` and `succeeded` holds one entry
  `("task-001","TODO","DONE",changed=true)`
* **AND** the database reads `task-001` as `DONE`
* **AND** exactly one `TaskStatusChangedEvent` is published, for `task-001`

**Scenario 3: A forbidden transition fails only its own entry**
* **GIVEN** seed activity `task-003` is `DONE` and `task-002` is `IN_PROGRESS`
* **WHEN** the batch is `[{"taskId":"task-003","status":"BLOCKED"},{"taskId":"task-002","status":"DONE"}]`
* **THEN** the response is `207`; `failed` holds
  `("task-003", code="TASK_TRANSITION_NOT_ALLOWED", statusCode=409)` and `succeeded` holds
  `("task-002","IN_PROGRESS","DONE",changed=true)`
* **AND** the database still reads `task-003` as `DONE` and its `updatedAt` is unchanged
* **AND** exactly one `TaskStatusChangedEvent` is published, for `task-002`

**Scenario 4: A target status outside DONE and BLOCKED fails only its own entry**
* **GIVEN** seed activity `task-001` is `TODO` and `task-002` is `IN_PROGRESS`
* **WHEN** the batch is `[{"taskId":"task-001","status":"IN_PROGRESS"},{"taskId":"task-002","status":"DONE"}]`
* **THEN** the response is `207`; `failed` holds `("task-001", code="VALIDATION_FAILED", statusCode=400)`
  and `succeeded` holds `("task-002","IN_PROGRESS","DONE",changed=true)`
* **AND** the database still reads `task-001` as `TODO`
* **AND** exactly one `TaskStatusChangedEvent` is published, for `task-002`

**Scenario 5: An activity already in the requested status succeeds without an event**
* **GIVEN** seed activity `task-003` is `DONE`
* **WHEN** the batch is `[{"taskId":"task-003","status":"DONE"}]`
* **THEN** the response is `207` with `failed` empty and `succeeded` holding
  `("task-003","DONE","DONE",changed=false)`
* **AND** no `TaskStatusChangedEvent` is published

**Scenario 6: A repeated id fails on its second occurrence**
* **GIVEN** seed activity `task-001` is `TODO`
* **WHEN** the batch is `[{"taskId":"task-001","status":"DONE"},{"taskId":"task-001","status":"BLOCKED"}]`
* **THEN** the response is `207`; `succeeded` holds `("task-001","TODO","DONE",changed=true)` and
  `failed` holds `("task-001", code="VALIDATION_FAILED", statusCode=400)`
* **AND** the database reads `task-001` as `DONE`
* **AND** exactly one `TaskStatusChangedEvent` is published

**Scenario 7: The batch commits even though one item failed**
* **GIVEN** the real transactional `TaskService` against H2 (a `@SpringBootTest` end-to-end request,
  not a mocked repository), with `task-001` `TODO` and `task-002` `IN_PROGRESS`
* **WHEN** the batch is
  `[{"taskId":"task-999","status":"DONE"},{"taskId":"task-001","status":"DONE"},{"taskId":"task-002","status":"BLOCKED"}]`
* **THEN** the response is `207` — not `500`, and no `UnexpectedRollbackException` is raised
* **AND** a subsequent `GET /api/tasks/task-001` returns `DONE` and `GET /api/tasks/task-002` returns
  `BLOCKED`, proving the transaction committed rather than being marked rollback-only by the failed item

**Scenario 8: Published events reach their after-commit subscribers**
* **GIVEN** a `@SpringBootTest` context with the real `EventBus` and `AlertEventListener`, and seed
  activity `task-002` is `IN_PROGRESS` assigned to `user-003`
* **WHEN** the batch is `[{"taskId":"task-002","status":"BLOCKED"}]`
* **THEN** the response is `207` and `task-002` reads `BLOCKED`
* **AND** `GET /api/notifications?recipientId=user-003` includes a notification of `alertType`
  `ESCALATION` whose `sourceRef` is `task-002`, proving the event was delivered after commit and not
  swallowed

**Scenario 9: A structurally invalid payload is rejected whole**
* **GIVEN** the seed data unchanged
* **WHEN** any of these is sent: `{"updates":[]}`; a payload of 51 items; an item with
  `"taskId":"   "`; an item with `"status":"SHIPPED"`
* **THEN** each returns `400` with the standard `ErrorResponse` body and `code` `VALIDATION_FAILED`
* **AND** no activity's status changes and no `TaskStatusChangedEvent` is published

## Architectural Guardrails

* **`bulkUpdateStatus` must be a single `@Transactional` method that loops over a plain private helper,
  catching `AppError` per item.** The transaction is what makes `@TransactionalEventListener`
  after-commit subscribers run at all; without it every event is silently dropped.
* **The helper must not be annotated `@Transactional`, and must not delegate to the public
  `update(...)`.** Calling `update(...)` joins the same transaction through the proxy, so an `AppError`
  escaping it marks the transaction rollback-only — the whole batch is then lost to
  `UnexpectedRollbackException` while the response claims partial success. This is Scenario 7's target.
* **`TaskRoutes` decides no rules.** Which statuses are legal bulk targets, what counts as a duplicate,
  and which items failed are all `TaskService` decisions. The route maps, validates the payload shape
  and sets `207`. ArchUnit `layersAreRespected` and `routesDoNotReachRepositories` enforce this.
* **No new `AppError` subtype.** Reuse `NotFoundError`, `ConflictError` and `ValidationError` from
  `shared.error`; `everyAppErrorSubtypeLivesInSharedError` fails the build otherwise.
* **No new event type and no listener change.** Reuse `TaskStatusChangedEvent` unchanged, and do not
  touch `alerts` or `reports`. The activities module must not import either — cross-module side effects
  travel only on the `EventBus`.
* **Reuse the existing transition rule.** The `DONE` → anything-else prohibition already lives in
  `TaskService.requireTransitionAllowed` with code `TASK_TRANSITION_NOT_ALLOWED`; call it rather than
  writing a second copy that could drift from the single-task path.
* **No repository change.** `findById` and `save` are sufficient; adding a bulk SQL update would move
  business logic into the repository layer and bypass event publication.
