# Specification: Shift Handover Bulk Status Update

## Feature Summary

Outgoing shift staff close a handover in one call instead of one `PATCH` per activity.
`PATCH /api/tasks/bulk-status` accepts a list of `(taskId, status)` pairs, applies each pair
independently, and returns a per-task report. An unknown id or an illegal transition fails only its
own entry; every entry whose status actually changes raises `TaskStatusChangedEvent` — the same event
`PATCH /api/tasks/{id}` already publishes.

## Route decision

The path is **`PATCH /api/tasks/bulk-status`**, as requested. The module is `activities` and its route
base is already `/api/tasks`, so one endpoint family stays under one base.

`/api/tasks/bulk-status` and the existing `/api/tasks/{id}` both match the incoming path. Spring
resolves this in favour of the literal segment, so there is no ambiguous-mapping failure at startup
and `bulk-status` never reaches `getTask`/`updateTask`. The consequence: `bulk-status` is no longer
usable as a task id. Acceptable — ids are UUIDs (`create`) or seed slugs (`task-001`).

## Module & Database Impact

Single module: **`activities`**. No cross-module code, no new entity, no schema change.

| Layer | Change |
| --- | --- |
| `activities/routes/TaskRoutes` | New `@PatchMapping("/bulk-status")`, `@Valid` body, always responds `207 Multi-Status` |
| `activities/service/TaskService` | New `@Transactional bulkUpdateStatus(...)`; per-item rule in a private helper so a rejected item cannot abort the batch |
| `activities/dto/` | New records: `BulkStatusUpdateRequest`, `BulkStatusUpdateItem`, `BulkStatusUpdateResponse`, `BulkStatusUpdateSuccess`, `BulkStatusUpdateFailure` |
| `activities/repository/` | **No change.** `findById` and `save` already cover it |
| JPA entities | **None added.** `TaskEntity` unchanged |
| `shared/` | **No change.** No new error subtype, no new event |

No new `TaskStatus` values. Only the four that exist — `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED` — and
only `DONE` and `BLOCKED` are accepted as bulk targets.

## Wire contract

Request:

```json
{ "updates": [ { "taskId": "task-001", "status": "DONE" },
               { "taskId": "task-002", "status": "BLOCKED" } ] }
```

Response, always `207 Multi-Status`:

```json
{ "succeeded": [ { "taskId": "task-001", "previousStatus": "TODO", "newStatus": "DONE", "changed": true } ],
  "failed":    [ { "taskId": "task-999", "code": "TASK_NOT_FOUND",
                   "message": "Task 'task-999' was not found", "statusCode": 404 } ] }
```

`changed` is `false` when the activity already held the requested status. It maps one-to-one to
"an event was published", which is what makes the event criteria assertable.

`failed` entries carry the `(code, message, statusCode)` triple of the existing `ErrorResponse` shape,
so clients switch on the same codes they already know. `ErrorResponse` itself is not reused — its
`path` and `timestamp` are request-scoped, not item-scoped.

`207` is returned for every accepted payload, including all-success and all-failure batches. One
status for one endpoint; the body is the report either way.

## Event Bus Triggers

**No new event.** The feature reuses `shared.events.TaskStatusChangedEvent` exactly as
`TaskService.update()` publishes it.

* **Event:** `TaskStatusChangedEvent` (`eventType()` = `TASK_STATUS_CHANGED`)
* **Payload:** `taskId`, `storeId`, `previousStatus`, `newStatus`, `priority` (all enum values as
  `String`), `assigneeId` (nullable), `occurredAt`
* **Publisher:** `activities.service.TaskService.bulkUpdateStatus` — one event per activity whose
  status actually changed. Items that fail, and items already in the requested status, publish nothing.
* **Subscribers:** unchanged. `alerts.listener.AlertEventListener.onTaskStatusChanged` and
  `reports.listener.ReportEventListener` already consume it; neither is modified by this feature.

**Flagged for the human.** `AlertEventListener` raises one `ESCALATION` notification per assigned
activity moving to `BLOCKED`. A 50-item `BLOCKED` handover therefore raises up to 50 notifications.
That is existing behaviour reached at new volume. The `max 50` batch cap below bounds it; digesting or
coalescing handover alerts would be an `alerts` sprint of its own and is out of scope here.

## Error Mapping

Per-item failures — reported in `failed`, batch continues:

| Rule | Error | `code` | `statusCode` |
| --- | --- | --- | --- |
| No activity has that id | `NotFoundError.of("Task", id)` | `TASK_NOT_FOUND` | 404 |
| Activity is `DONE` and target is not `DONE` | `ConflictError` | `TASK_TRANSITION_NOT_ALLOWED` | 409 |
| Target status is not `DONE` or `BLOCKED` | `ValidationError` | `VALIDATION_FAILED` | 400 |
| `taskId` repeated in the same batch (second and later occurrences) | `ValidationError` | `VALIDATION_FAILED` | 400 |

Whole-request failures — nothing is updated, the standard `ErrorResponse` body is returned:

| Rule | Error | `code` | HTTP |
| --- | --- | --- | --- |
| `updates` null, empty, or over 50 items | bean validation → `ValidationError` | `VALIDATION_FAILED` | 400 |
| `taskId` null or blank, `status` null | bean validation → `ValidationError` | `VALIDATION_FAILED` | 400 |
| `status` is not a `TaskStatus` name (e.g. `"SHIPPED"`) | Jackson → `HttpMessageNotReadableException` | `VALIDATION_FAILED` | 400 |

No new `AppError` subtype: the ArchUnit rule `everyAppErrorSubtypeLivesInSharedError` forbids growing
the hierarchy inside a module, and the three existing subtypes cover every case.

**Flagged for the human — the one asymmetry.** `status` is typed as the `TaskStatus` enum, matching
`UpdateTaskRequest`. A misspelled status word therefore fails deserialization and rejects the *whole*
batch with 400, rather than failing one item. Typing the field as `String` and parsing it per item
would make a typo a per-item failure, at the cost of a hand-rolled parser and divergence from the
existing DTO style. The enum is specified here because the request enumerates unknown ids and
forbidden transitions as the independent-failure cases, not unparseable input. Overrule at approval if
shift staff hand-type these payloads.

## Transaction and event-delivery constraint

The one place this feature can break quietly. Two facts must hold at once: a failed item must not roll
back its neighbours, and a successful item's event must still be delivered after commit.

`bulkUpdateStatus` is a single `@Transactional` method — required for after-commit delivery, without
which no subscriber runs. The per-item rule must therefore be a **plain private helper** whose
`AppError` is caught inside the loop, and the helper must **not** be annotated `@Transactional`.

It must **not** be a call out to the existing public `@Transactional update(...)`. Through the proxy
that joins the same transaction, and an `AppError` escaping it marks the shared transaction
rollback-only — so the whole batch is then lost to `UnexpectedRollbackException` at commit and the
caller gets a 500, even though the response said some items succeeded.

Failed items never reach `taskRepository.save`, and `Task` is an immutable record detached from
Hibernate, so a rejected item leaves nothing dirty to flush.

## Sprint Breakdown

**One sprint.** One endpoint, one module, no new event, no listener change, no schema change. The seam
that would normally force a second sprint — a subscriber in another module — does not exist here,
because the consumers of `TaskStatusChangedEvent` are already built. Splitting the route from the
service would leave the first half with no observable acceptance criterion, which
`sprint-decomposition` forbids.

| Sprint | Delivers |
| --- | --- |
| 1 | `PATCH /api/tasks/bulk-status` in `activities`: request/response DTOs, per-item rule with independent failure, `207` reporting, one `TaskStatusChangedEvent` per changed activity, and a batch that commits despite partial failure |

STATUS: AWAITING APPROVAL
