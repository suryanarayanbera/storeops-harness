# Specification: Shift Handover Bulk Status Update

## 1. Feature Summary

Outgoing shift staff currently have to send one `PATCH /api/tasks/{id}` per activity when
closing out a shift. This feature adds a single batched endpoint,
`PATCH /api/tasks/bulk-status`, that moves several operational activities to `DONE` or
`BLOCKED` in one request.

The defining property is **per-task independence**. An unknown id, a forbidden transition or an
unsupported target status fails that one task and nothing else; every other task in the batch is
still written and committed. The response reports each task's outcome individually.

Every task that actually changes status publishes `TaskStatusChangedEvent`, on exactly the same
code path as the single-task update, so the alerts module keeps raising its `ESCALATION` alert
without knowing a bulk endpoint exists.

### What this feature is not
* Not a new event. `TaskStatusChangedEvent` already exists and is reused unchanged.
* Not a new entity or table. It writes the existing `tasks` rows.
* Not a change to `PATCH /api/tasks/{id}`. That path keeps its current behaviour byte for byte.
* Not a way to bulk-edit priority or assignee. Status only.

## 2. Module & Database Impact

### Modules touched

| Module | Layer | Change |
| --- | --- | --- |
| `activities` | `routes` | New method on `TaskRoutes` for `PATCH /api/tasks/bulk-status` |
| `activities` | `service` | New `TaskBulkStatusService` that orchestrates the per-task loop |
| `activities` | `service` | `TaskService` is **read and reused, not modified** |
| `activities` | `dto` | Four new records (below) |
| `alerts` | `listener` | **No change.** `AlertEventListener` already consumes the event |
| `programmes`, `staff`, `reports` | — | Untouched |

### JPA entities

**None.** No new entity, no new table, no schema migration, no `data.sql` change. The feature
writes existing `tasks` rows through the existing `TaskRepository` / `TaskEntity`, reached only
through `TaskService`, which owns them.

### New DTO records (all in `com.cognizant.storeops.activities.dto`)

| Record | Fields | Notes |
| --- | --- | --- |
| `BulkStatusUpdateRequest` | `List<BulkStatusUpdateItem> updates` | `@NotEmpty`, `@Size(max = 50)`, `@Valid` |
| `BulkStatusUpdateItem` | `String taskId`, `TaskStatus status` | both `@NotNull`; `taskId` `@NotBlank` |
| `BulkStatusUpdateResponse` | `List<TaskResponse> succeeded`, `List<BulkStatusFailure> failed` | both lists always present, possibly empty |
| `BulkStatusFailure` | `String taskId`, `String code`, `String message`, `int statusCode` | per-task error, mirrors the fields of `ErrorResponse` that make sense per item |

`BulkStatusFailure` is a DTO, **not** an `AppError` subtype. The `AppError` hierarchy stays
closed and lives entirely in `shared/error` (ArchUnit rule 6b).

### The transaction boundary — the load-bearing design decision

`TaskService.update` is `@Transactional` and publishes on the `EventBus`; delivery is
`AFTER_COMMIT`. Two consequences drive the whole design:

1. **The bulk orchestration method must not be `@Transactional`.** If it were, each
   `taskService.update` call would join the outer transaction. The first `AppError` thrown by a
   failing task marks that outer transaction rollback-only. Catching the exception does not
   unmark it, so the eventual commit blows up with `UnexpectedRollbackException` and the entire
   batch is lost — the exact opposite of the required behaviour. Per-task independence means
   **one transaction per task**.
2. **The loop must live in a different bean from `update`.** With no outer transaction active,
   each `taskService.update(...)` call opens, commits and fires its own after-commit callbacks —
   but only if the call goes through the Spring proxy. A `this.update(...)` self-invocation from
   inside `TaskService` bypasses the proxy, so no transaction opens and the
   `@TransactionalEventListener(AFTER_COMMIT)` handlers never run. That failure is completely
   silent: no exception, no log, and a test that asserts only HTTP 200 still passes.

Hence a separate `TaskBulkStatusService` in `activities.service` that injects `TaskService` and
delegates one call per item. Service-to-service inside a module is permitted by the layering
rule, and reusing `update` is what makes "exactly as the existing single-task path does" true by
construction rather than by copy-paste.

## 3. API Contract

### Request

```
PATCH /api/tasks/bulk-status
Content-Type: application/json

{
  "updates": [
    { "taskId": "task-001", "status": "DONE" },
    { "taskId": "task-002", "status": "BLOCKED" }
  ]
}
```

Only `DONE` and `BLOCKED` are accepted as target statuses. `TODO` and `IN_PROGRESS` are valid
`TaskStatus` values but are not valid bulk-handover targets, and are rejected per task.

### Response — always `200 OK` when the payload itself is well formed

```json
{
  "succeeded": [ { "id": "task-001", "status": "DONE", "...": "full TaskResponse" } ],
  "failed": [
    { "taskId": "task-999", "code": "TASK_NOT_FOUND", "message": "...", "statusCode": 404 }
  ]
}
```

**The batch returns `200 OK` even when every task in it failed.** The HTTP status describes the
bulk request, which succeeded in being processed; per-task outcomes are data in the body, not
transport status. `207 Multi-Status` was considered and rejected: a status code that varies with
the contents of the body gives clients two places to look for the same answer. One rule, no
branching, and the Evaluator can settle it by inspection.

Both lists preserve request order. A task appears in exactly one of them.

### Whole-request rejections (`400 VALIDATION_FAILED`, standard `ErrorResponse` body)

These are malformed payloads, not per-task business failures, so nothing is written at all:

| Condition | Why it is not a per-task failure |
| --- | --- |
| `updates` absent or empty | There is no task to attribute the failure to |
| More than 50 items | Batch-level limit protecting the request |
| The same `taskId` appears twice | Ambiguous intent; the outcome would depend on ordering |
| `taskId` blank, or `status` null / not a `TaskStatus` value | Item is not parseable as a task instruction |

## 4. Error Mapping

Every per-task failure is an `AppError` subtype thrown inside the loop and caught by
`TaskBulkStatusService`, which flattens it into a `BulkStatusFailure`. No code is invented at
generation time.

| Business rule that can fail | Thrown as | `code` | `statusCode` | Scope |
| --- | --- | --- | --- | --- |
| Task id is not in the store | `NotFoundError.of("Task", id)` | `TASK_NOT_FOUND` | 404 | per task |
| Task is already `DONE` (terminal) | `ConflictError` | `TASK_TRANSITION_NOT_ALLOWED` | 409 | per task |
| Task is already in the requested status (`BLOCKED` → `BLOCKED`) | `ConflictError` | `TASK_STATUS_UNCHANGED` | 409 | per task |
| Target status is neither `DONE` nor `BLOCKED` | `ValidationError` | `VALIDATION_FAILED` | 400 | per task |
| `updates` empty, oversized, or containing duplicate ids | `ValidationError` | `VALIDATION_FAILED` | 400 | whole request |

`TASK_STATUS_UNCHANGED` is the one new code string in this feature. It is carried by the
existing `ConflictError`, which takes a caller-supplied code, so no new error type is added.

It exists to keep an invariant the Evaluator can check mechanically: **every entry in
`succeeded` corresponds to a real status transition, and therefore to exactly one published
`TaskStatusChangedEvent`.** Without it, a `BLOCKED` → `BLOCKED` no-op would land in `succeeded`
having published nothing, and "every successful update raises the event" would quietly stop
being true. Note that `DONE` → `DONE` never reaches this rule; it is caught earlier by the
terminal-status rule as `TASK_TRANSITION_NOT_ALLOWED`.

## 5. Event Bus Triggers

**No new event.** The catalogue stays at three.

| | |
| --- | --- |
| **Event** | `TaskStatusChangedEvent` (`eventType()` = `TASK_STATUS_CHANGED`) |
| **Payload** | `taskId`, `storeId`, `previousStatus`, `newStatus`, `priority`, `assigneeId`, `occurredAt` — all enum values carried as `String`, never as `TaskStatus` / `TaskPriority` |
| **Published by** | `activities` → `TaskService.update`, unchanged. `TaskBulkStatusService` never calls `eventBus.publish` itself; it publishes only by delegating to `update` |
| **Subscribed by** | `alerts` → `AlertEventListener.onTaskStatusChanged`, unchanged |
| **What the subscriber does** | Raises an `ESCALATION` alert when `newStatus` is `BLOCKED` and the activity has an assignee. A `DONE` transition produces no alert |

Publication rules for the bulk path:
* One event per task that actually changed status. A task in `failed` publishes nothing.
* Delivery is after that task's own transaction commits, so a batch of five with two failures
  delivers exactly three events, each after its own commit.
* A throwing subscriber must not fail the caller — the `ErrorHandler` bean in
  `EventBusConfiguration` already guarantees this and must stay.

## 6. Sprint Breakdown

**Sprint 1 — The bulk endpoint and per-task independence.**
Route, `TaskBulkStatusService`, the four DTOs, all five error mappings, whole-request
validation, and event publication asserted at the service level with `RecordingEventBus`. This
sprint is entirely within the `activities` module.

**Sprint 2 — Cross-module delivery of the bulk-published events.**
Proves the events raised by the bulk path actually reach `alerts` after commit: `ESCALATION`
alerts appear for bulk-blocked activities, partial-failure batches still deliver for their
successes, and a failing subscriber does not break the batch. This sprint is expected to add no
production code — `AlertEventListener` already handles `TaskStatusChangedEvent`. It is a
separate sprint because the after-commit wiring is the part of this feature that fails silently:
a Sprint 1 test asserting HTTP 200 and a changed database row passes even when no event is ever
delivered. That gap is worth its own gate.

STATUS: AWAITING APPROVAL
