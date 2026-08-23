# Specification: Shift Handover Bulk Status Update

## Feature Summary

Outgoing shift staff need to close down a handover in one call rather than one PATCH per activity.
`PATCH /api/tasks/bulk-status` accepts a list of `(taskId, status)` pairs, applies each one
independently, and returns a per-task report. A bad id or an illegal transition fails only its own
entry; every entry that actually changes status raises `TaskStatusChangedEvent`, the same event the
single-task path already publishes.

This fills the `activities` gap listed in `app-context` §7.

## Path decision

The route is **`PATCH /api/tasks/bulk-status`**, as the request states — not
`/api/activities/bulk-status` as the gap list names it. The module is `activities`; its existing route
base is `/api/tasks` and one endpoint family must not straddle two bases.

`/api/tasks/bulk-status` and the existing `/api/tasks/{id}` both match the incoming path. Spring
resolves this in favour of the literal segment, so there is no ambiguous-mapping failure at startup
and `bulk-status` never reaches the single-task handler. The consequence is that `bulk-status` is no
longer usable as a task id — acceptable, ids are UUIDs and seed-style slugs.

## Module & Database Impact

Single module: **`activities`**. No cross-module code, no new entity, no schema change.

| Layer | Change |
| --- | --- |
| `activities/routes/TaskRoutes` | New `@PatchMapping("/bulk-status")`, `@Valid` body, always responds `207 Multi-Status` |
| `activities/service/TaskService` | New `@Transactional bulkUpdateStatus(...)`; per-item rule extracted so a rejected item cannot abort the batch |
| `activities/dto/` | New records: `BulkStatusUpdateRequest`, `BulkStatusUpdateItem`, `BulkStatusUpdateResponse`, `BulkStatusUpdateResult` |
| `activities/repository/` | **No change.** `findById` and `save` already cover it |
| JPA entities | **None added.** `TaskEntity` unchanged |

No new `TaskStatus` values. Only the four in `app-context` §4 are used, and only `DONE` and `BLOCKED`
are accepted as bulk targets.

## Event Bus Triggers

**No new event record.** The feature reuses `shared.events.TaskStatusChangedEvent` exactly as
`TaskService.update()` publishes it: one event per activity whose status actually changed, carrying
`taskId`, `storeId`, `previousStatus`, `newStatus`, `priority`, `assigneeId`, `occurredAt`.

Items that fail, and items already in the requested status, publish nothing.

Consumers are untouched. `AlertEventListener.onTaskStatusChanged` already ignores anything but
`newStatus == BLOCKED` with an assignee, so a handover batch of `DONE`s raises no notifications and a
batch of `BLOCKED`s raises one `ESCALATION` per assigned activity. **Flagged for the human:** that is
existing behaviour reached at a new volume — a 50-item BLOCKED batch means 50 notifications. Changing
it is out of scope here; it would be an `alerts` sprint of its own.

## Transaction and event-delivery constraint

The one place this feature can break quietly. Both facts have to hold at once: a failed item must not
roll back its neighbours, and a successful item's event must still be delivered after commit.

`bulkUpdateStatus` is a single `@Transactional` method — needed for after-commit delivery, without
which subscribers never run. The per-item rule must therefore be a plain private helper whose
`AppError` is caught inside the loop. It must **not** be a call out to the existing public
`@Transactional update(...)`: through the proxy that joins the same transaction, and an exception
escaping it marks the shared transaction rollback-only, so the whole batch is lost to
`UnexpectedRollbackException` even though the response says some items succeeded.

## Sprint Breakdown

**One sprint.** One endpoint, one module, no new event, no schema change — the seam that would
normally justify a second sprint (`SHIFT_HANDOVER` alerts) is not in this request, and the endpoint's
route, rule and result shape have nothing an acceptance criterion could observe if split apart.

| Sprint | Delivers |
| --- | --- |
| 1 | `PATCH /api/tasks/bulk-status` in `activities`: request/response DTOs, per-item rule with independent failure, `207` reporting, one `TaskStatusChangedEvent` per changed activity, batch commit that survives partial failure |

STATUS: AWAITING APPROVAL
