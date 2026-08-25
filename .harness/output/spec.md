# Specification: SLA Breach Alerting and Escalation

## Feature Summary

A HIGH or CRITICAL activity that passes its `dueAt` without reaching `DONE` raises an `SLA_BREACH`
notification for the Department Lead responsible for the assignee. If the activity is still not `DONE`
after a configurable grace period, the breach escalates: an `ESCALATION` notification goes to the
store's `STORE_MANAGER`. Each of those fires **once** per breach episode, no matter how many times the
breach is observed.

Nothing here is triggered by an HTTP request. The activities module sweeps for overdue work on a timer
and publishes `TaskOverdueEvent`; the alerts module decides who to tell. The existing
`GET /api/notifications?recipientId=...` endpoint is the read surface — **no new route, in any module.**

## What already exists, and what is actually missing

The codebase carries a deliberate stub of this feature. Naming it precisely is what keeps this build
small:

| Already present | State |
| --- | --- |
| `shared.events.TaskOverdueEvent` | Complete. Carries `taskId`, `storeId`, `priority`, `assigneeId`, `dueAt`, `occurredAt`. **Unchanged by this feature.** |
| `alerts.domain.AlertType.SLA_BREACH` / `.ESCALATION` | Both values exist. No enum change. |
| `TaskService.publishOverdueBreaches()` | Publishes for every SLA-tracked overdue activity, but **nothing schedules it** and it scans via `findAll()`. |
| `AlertEventListener.onTaskOverdue` | Notifies the **assignee**, with no dedup, no lead resolution and no escalation. |
| `Task.isOverdueAt(Instant)` / `Task.isSlaTracked()` | Complete. The overdue and priority-band rules already live on the domain record; reuse them, do not restate them. |

Three things are missing: a **clock** to drive the sweep, a **memory** of which breaches have already
been alerted, and the **recipient resolution** that turns an assignee into their Department Lead and
then into a Store Manager.

## The repeat-event design decision

The most consequential decision in this spec, and the one most likely to be mistaken for a bug on
review:

**The sweep republishes `TaskOverdueEvent` on every pass while an activity remains overdue.** The
repeat event is the liveness signal — it is how the alerts module learns the breach is *still*
unresolved without reading the activities module. `TaskOverdueEvent` is therefore an observation
("this is overdue right now"), not a transition ("this just became overdue").

The consequence is that **de-duplication is the alerts module's job, and must be durable.** Alerts owns
a breach-episode record, keyed by `taskId`; the tracker, not the event, decides whether a notification
is a first alert, an escalation, or nothing at all.

The alternative — activities remembering which breaches it has already announced — was rejected: it
puts alerting state in the module that is supposed to know nothing about alerting, and it gives the
alerts module no way to time a grace period.

## Module & Database Impact

Three modules. `activities` gains the sweep trigger, `staff` gains one read, `alerts` gains all of the
policy and the only new table.

| Module / Layer | Change |
| --- | --- |
| `activities/listener/OverdueSweepScheduler` | **New.** `@Scheduled` trigger calling `TaskService.publishOverdueBreaches()`. Inbound adapter, holds no rules. |
| `activities/service/TaskService` | `publishOverdueBreaches()` reworked to query rather than `findAll()`. Behaviour unchanged. |
| `activities/repository/TaskRepository` (+ `JpaTaskRepository`, `TaskJpaRepository`) | **New finder** `findOpenPastDue(Instant)` — `status <> DONE AND due_at < :moment`. Data predicate only; the HIGH/CRITICAL rule stays in `Task.isSlaTracked()`. |
| `staff/service/UserService` + `staff/repository/UserRepository` (+ `JpaUserRepository`, `UserJpaRepository`) | **New read** `findByStoreIdAndRole(String, StaffRole)`. Read-only, consistent with the existing `findByStoreId`. |
| `alerts/domain/SlaBreach` | **New record.** One breach episode. |
| `alerts/repository/SlaBreachEntity`, `SlaBreachJpaRepository`, `SlaBreachRepository`, `JpaSlaBreachRepository` | **New.** The `sla_breaches` table, owned by alerts. Follows the `Notification*` four-file pattern exactly. |
| `alerts/service/SlaBreachService` | **New.** All of the policy: priority filter, dedup, recipient resolution, grace-period arithmetic. |
| `alerts/service/SlaEscalationProperties` | **New.** `@ConfigurationProperties("storeops.alerts.sla")`, binds the grace period. |
| `alerts/listener/AlertEventListener` | `onTaskOverdue` delegates to `SlaBreachService`; `onTaskStatusChanged` additionally closes a breach episode on `DONE`. |
| `StoreOpsApplication` | `@EnableScheduling` + `@ConfigurationPropertiesScan`. |
| `shared/` | **No change.** No new event, no new `AppError` subtype. |

### The new entity

One new JPA entity, owned by `alerts`: **`SlaBreachEntity`**, table `sla_breaches`, primary key
`task_id`. One row per open breach episode.

| Column | Meaning |
| --- | --- |
| `task_id` (PK) | The breached activity. Not a foreign key — cross-module FKs are banned. |
| `store_id`, `priority` | Copied off the event; the alerts module never reads them back from activities. |
| `first_breach_at` | When the breach was **first observed**. The grace period is measured from here. |
| `lead_recipient_id`, `lead_notified_at` | Who got the `SLA_BREACH` alert, and when. Non-null for every persisted row. |
| `escalation_recipient_id`, `escalated_at` | Who got the `ESCALATION` alert, and when. Null until escalation. |
| `last_seen_at` | Most recent observation. Diagnostic; not used in any decision. |

`data.sql` seeds no `sla_breaches` rows, for the same reason `reports` is empty: rows appear only when
the behaviour runs.

**`H2SchemaTest.schemaHasExpectedTables` asserts `containsExactly` over the table list and will fail
until `SLA_BREACHES` is added to it.** That is an expected, required test edit in Sprint 2, not a
regression.

## Event Bus Triggers

**No new event type, and no change to `TaskOverdueEvent`.** The activities module already carries
everything alerts needs; `assigneeId` plus `storeId` are enough to resolve both recipients through the
staff module.

* **Event:** `shared.events.TaskOverdueEvent` (`eventType()` = `TASK_OVERDUE`)
* **Payload:** `taskId`, `storeId`, `priority` (`TaskPriority` name as `String`), `assigneeId`
  (nullable), `dueAt`, `occurredAt`
* **Publisher:** `activities.service.TaskService.publishOverdueBreaches()`, driven by
  `activities.listener.OverdueSweepScheduler`. One event per SLA-tracked overdue activity **per
  sweep** — repeats are intentional, see above.
* **Subscriber:** `alerts.listener.AlertEventListener.onTaskOverdue` → `SlaBreachService`. No other
  module subscribes.

Second, existing event, used for one new purpose:

* **Event:** `shared.events.TaskStatusChangedEvent` (`TASK_STATUS_CHANGED`), unchanged
* **Publisher:** `TaskService.update` and `TaskService.bulkUpdateStatus`, unchanged
* **New subscriber behaviour:** `AlertEventListener.onTaskStatusChanged` closes the breach episode
  when `newStatus` is `DONE` — the tracker row is deleted, so a later reopening starts a fresh
  episode instead of escalating instantly against a stale `first_breach_at`. Its existing
  `BLOCKED` → `ESCALATION` behaviour is untouched.

### Cross-module reads this feature introduces

`alerts.service.SlaBreachService` → `staff.service.UserService`. Sanctioned: a read through the
target module's service layer. No cycle is created — `staff` imports nothing but `shared`.

## Recipient Resolution

Given an event carrying `assigneeId` and `storeId`, in order:

1. Load the assignee via `UserService.findById` (the `Optional` form — **not** `getById`, which throws
   `NotFoundError`; a listener must not throw).
2. **Department Lead** = an `active` `DEPARTMENT_LEAD` in `storeId` whose `profile().department()`
   equals the assignee's `profile().department()`. Ties broken by lowest `id`, so the choice is
   deterministic.
3. If the assignee **is** an active `DEPARTMENT_LEAD`, they are their own lead.
4. **Fallback:** no match, or the assignee has no department → the store's `active` `STORE_MANAGER`
   (lowest `id`).
5. Nobody resolvable → **no notification and no tracker row.** Logged at `WARN`. The next sweep retries,
   so correcting the staff record heals it.

Escalation resolves the `active` `STORE_MANAGER` for `storeId` by the same tie-break. If the escalation
recipient is the same person who already received the `SLA_BREACH` alert (the step-4 fallback case),
**the episode is marked escalated and no second notification is raised** — nobody is told twice about
one activity.

Against the seed data this resolves to exactly one chain, which is what makes the criteria assertable:
`task-001` (HIGH, TODO, overdue) → assignee `user-004` (ASSOCIATE, GROCERY, store-001) → lead
`user-003` (DEPARTMENT_LEAD, GROCERY) → escalation `user-002` (STORE_MANAGER, store-001).

## Notification Contract

| | Lead alert | Escalation alert |
| --- | --- | --- |
| `alertType` | `SLA_BREACH` | `ESCALATION` |
| `recipientId` | resolved Department Lead | store's `STORE_MANAGER` |
| `subject` | `SLA breach on <PRIORITY> activity` | `Escalated: SLA breach unresolved on <PRIORITY> activity` |
| `sourceRef` | `taskId` | `taskId` |
| `channel` / `status` | `IN_APP` / `PENDING` | `IN_APP` / `PENDING` |
| Raised at most | once per episode | once per episode |

Both go through the existing `NotificationService.raise(...)`. No change to that method.

**Flagged for the human.** `ESCALATION` is already the type raised when an activity moves to `BLOCKED`,
so the enum value now covers two situations. The pair is still distinguishable — the BLOCKED alert goes
to the assignee, this one to the store manager, and the subjects differ — and adding an
`SLA_ESCALATION` value would change a vocabulary the seed data and existing tests already assert on.
Overrule at approval if you want a distinct enum value; it becomes a one-line change in Sprint 3 plus a
`data.sql` comment.

## Configuration

Two knobs, each owned by the module that reads it — neither module configures the other's timing.

| Property | Default | Owner | Meaning |
| --- | --- | --- | --- |
| `storeops.alerts.sla.grace-period` | `PT2H` | `alerts` | Time from `first_breach_at` before escalation is due |
| `storeops.activities.sla.sweep-interval` | `PT5M` | `activities` | Delay between sweeps |
| `storeops.activities.sla.initial-delay` | `PT10M` | `activities` | Delay before the **first** sweep |
| `storeops.activities.sla.sweep.enabled` | `true` | `activities` | Whether the scheduler bean exists at all |

`grace-period` binds through a `@ConfigurationProperties` record. A missing or negative value falls back
to `PT2H` in the record's compact constructor rather than failing startup; an unparseable value is a
Spring binding failure at boot, which is the correct outcome for malformed config and is **not** an
`AppError` case.

**`initial-delay` exists to keep the test suite deterministic.** With `@EnableScheduling` active, a
short first delay would let a sweep fire in the middle of an unrelated `@SpringBootTest` and raise
notifications that other tests count. Ten minutes is longer than any test run. Tests drive
`publishOverdueBreaches()` explicitly instead of waiting for the timer, and the `enabled` flag lets a
test remove the bean entirely.

## Error Mapping

This flow has no HTTP entry point, so the governing rule is inverted from a normal endpoint: **an
after-commit listener must not throw.** The `EventBus` `ErrorHandler` absorbs whatever escapes, so a
thrown error would not surface to any caller — it would silently lose the alert. Every foreseeable
failure is therefore a logged non-event that the next sweep retries.

| Rule | Handling | Error / code |
| --- | --- | --- |
| `assigneeId` null or blank | No notification, no tracker row. `WARN`. | none |
| Assignee id names no staff member | No notification, no tracker row. `WARN`. | none — must use `findById`, never `getById` (`USER_NOT_FOUND`) |
| No lead and no store manager resolvable | No notification, no tracker row; retried next sweep. `WARN`. | none |
| Priority is not `HIGH` or `CRITICAL` | Ignored at `DEBUG`. Belt-and-braces: the sweep already filtered. | none |
| Breach already alerted, grace not elapsed | `last_seen_at` updated, nothing raised. | none |
| Already escalated | Nothing raised. Idempotent. | none |
| Escalation due but no active `STORE_MANAGER` | `escalated_at` left null so a later sweep retries. `WARN`. | none |
| `NotificationService.raise` with a blank recipient | **Must be unreachable** — resolution completes before `raise` is called. | `ValidationError` / `VALIDATION_FAILED` (400) |
| `grace-period` unparseable | Startup failure, fail fast. | none — not an API error |

**No new `AppError` subtype.** The ArchUnit rule `everyAppErrorSubtypeLivesInSharedError` forbids
growing the hierarchy inside a module, and this feature raises no HTTP error at all.

Checkstyle `IllegalCatch` forbids `catch (Exception ...)`, so the "log and carry on" behaviour above
must be written as explicit `Optional`/null checks, not as a broad try/catch.

## Sprint Breakdown

Split on the module boundary, as `sprint-decomposition` requires: the publisher first, the subscriber
second, and the timed half of the subscriber third.

| Sprint | Module | Delivers |
| --- | --- | --- |
| 1 | `activities` | The sweep. `OverdueSweepScheduler`, `findOpenPastDue` finder, `publishOverdueBreaches` publishing repeat events for still-overdue work. Observable as published events. |
| 2 | `alerts` + one `staff` read | The lead alert. `sla_breaches` table, `SlaBreachService`, recipient resolution with fallback, one `SLA_BREACH` per episode however many events arrive. Observable through `GET /api/notifications?recipientId=user-003`. |
| 3 | `alerts` | The escalation. Configurable grace period, one `ESCALATION` to the `STORE_MANAGER`, and episode closure when the activity reaches `DONE`. Observable through `GET /api/notifications?recipientId=user-002`. |

Sprint 3 is separated from Sprint 2 because it is the only part that depends on elapsed time and on
configuration binding. Merging them would mean no sprint could be failed for getting the dedup wrong
independently of getting the timer wrong — and dedup is the harder of the two to prove.

None of the three sprints adds a route. That is a deliberate departure from "slice vertically": the
feature has no HTTP entry point, and its read surface already exists. Each sprint still ends with an
outcome observable from outside its own module — published events for Sprint 1, notifications visible
through the existing endpoint for Sprints 2 and 3 — which is the property the vertical-slice rule is
protecting.

**Out of scope, flagged for a future sprint.** No endpoint exposes the breach tracker, so open episodes
are only visible in the database or by their notifications; no digest or coalescing, so a store with
thirty breached activities produces thirty notifications; and reopening a `DONE` activity starts a
fresh episode rather than resuming the old one.

STATUS: AWAITING APPROVAL
