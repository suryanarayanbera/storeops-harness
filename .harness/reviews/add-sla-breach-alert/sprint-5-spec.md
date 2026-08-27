# Specification: SLA Breach Alerting

## Feature Summary

When a `HIGH` or `CRITICAL` activity passes its `dueAt` without reaching `DONE`, StoreOps must raise an
`SLA_BREACH` alert to the Department Lead responsible for the assignee's department. If the activity is
still not `DONE` after a configurable grace period, the breach must be escalated to the store's
`STORE_MANAGER` as an `ESCALATION` alert. Both alerts must fire exactly once per activity per stage,
without human action.

### What already exists (and is a stub)

This feature is not greenfield. Three seams were built for it and are deliberately incomplete:

| Existing artefact | State today | Gap this feature closes |
| --- | --- | --- |
| `shared/events/TaskOverdueEvent` | complete, carries `taskId`, `storeId`, `priority`, `assigneeId`, `dueAt`, `occurredAt` | nothing — **no change required** |
| `activities/service/TaskService.publishOverdueBreaches()` | correct logic, nothing invokes it | needs a schedule and configuration |
| `alerts/listener/AlertEventListener.onTaskOverdue()` | raises `SLA_BREACH` to `event.assigneeId()` | wrong recipient (must be the Department Lead), no de-duplication, no escalation stage |
| `AlertType.SLA_BREACH`, `AlertType.ESCALATION` | both exist | nothing — **no new enum values** |

### The one design decision that shapes every sprint

The escalation stage needs to know "is this activity *still* unresolved". The `alerts` module must not
answer that question itself — it does not own activity state.

Instead, the `activities` sweep **re-publishes** `TaskOverdueEvent` on every cycle for every activity
that is still SLA-tracked, still past due and still not `DONE`. The arrival of the event *is* the proof
that the activity is unresolved. `alerts` then decides which stage the event represents by looking at
the notifications it has already raised:

* no prior `SLA_BREACH` for this activity → raise `SLA_BREACH` to the Department Lead
* prior `SLA_BREACH` younger than the grace period → suppress, do nothing
* prior `SLA_BREACH` older than the grace period, no prior SLA escalation → raise `ESCALATION` to the `STORE_MANAGER`
* both already raised → suppress

Consequences that the contracts below enforce:

* **No fourth event.** The catalogue stays at three.
* **`alerts` never injects `TaskService`.** The event carries everything it needs.
* **`activities` holds no alerting state.** It restates a fact; it does not track what has been alerted.
* **The sweep must be idempotent from the publisher's side**, which means de-duplication is a hard
  requirement in `alerts`, not a nicety. `task-001` in the seed data breaches on every single sweep
  cycle; without de-duplication the store would accumulate one notification every 15 minutes forever.

## Module & Database Impact

| Module | Layer | Change |
| --- | --- | --- |
| `activities` | `service` | `TaskService.publishOverdueBreaches()` gains a caller; new `SlaSweepScheduler` and `SlaSweepProperties` |
| `alerts` | `listener` | `AlertEventListener.onTaskOverdue()` rewritten: recipient resolution, staged de-duplication |
| `alerts` | `service` | new `SlaAlertProperties`; `NotificationService` gains a lookup used by the listener |
| `alerts` | `repository` | `NotificationRepository` + JPA implementation gain `findBySourceRefAndAlertType` |
| `staff` | `service` | `UserService` gains role-filtered read helpers. **Read-only, no mutator** |
| `shared` | — | **no change.** No new event, no new `AppError` subtype |
| `reports`, `programmes` | — | untouched |

**New JPA entities: none.** The breach and escalation states are derived from `NotificationEntity`
rows (`source_ref` + `alert_type` + `created_at`), which already persist everything needed. No schema
change, no `data.sql` change.

**Root class:** `StoreOpsApplication` gains `@EnableScheduling` and `@ConfigurationPropertiesScan`.
Use `@ConfigurationPropertiesScan`, **not** `@EnableConfigurationProperties(SlaAlertProperties.class)`
— the latter names a module type from the application root and creates a root-to-module dependency for
no benefit.

### Configuration surface

All four keys go in `src/main/resources/application.yml` with these exact names and defaults:

| Key | Type | Default | Owner |
| --- | --- | --- | --- |
| `storeops.activities.sla.sweep.enabled` | boolean | `true` | `activities` |
| `storeops.activities.sla.sweep.interval` | `Duration` | `PT15M` | `activities` |
| `storeops.activities.sla.sweep.initial-delay` | `Duration` | `PT15M` | `activities` |
| `storeops.alerts.sla.grace-period` | `Duration` | `PT4H` | `alerts` |

`grace-period` is the "configurable grace period" the feature request asks for.

## Event Bus Triggers

No event is added, changed or removed. `TaskOverdueEvent` gains a publisher and its subscriber gains
real behaviour.

| Field | Value |
| --- | --- |
| **Event** | `TaskOverdueEvent`, `eventType()` = `TASK_OVERDUE` |
| **Payload** | `taskId`, `storeId`, `priority` (`TaskPriority` name as `String`), `assigneeId` (nullable), `dueAt`, `occurredAt` |
| **Publisher** | `activities` → `TaskService.publishOverdueBreaches()`, driven by `activities` → `SlaSweepScheduler` |
| **Subscriber** | `alerts` → `AlertEventListener.onTaskOverdue()` |
| **Subscriber effect** | Raises `SLA_BREACH` to the Department Lead on first observation; raises `ESCALATION` to the `STORE_MANAGER` once the grace period has elapsed; suppresses every other observation |

Wiring requirements, all three of which fail silently if missed:

1. `publishOverdueBreaches()` must stay `@Transactional` — Spring skips after-commit callbacks when no
   transaction is active, so the listener would never run.
2. Each handler keeps `@TransactionalEventListener(phase = AFTER_COMMIT)` **and**
   `@Transactional(propagation = REQUIRES_NEW)` — at after-commit time the original transaction has
   already committed, so a write that joined it is discarded with no error anywhere.
3. Enum values stay `String` in the payload. An `activities` import inside `alerts` breaks
   `ModuleBoundaryTest` rule 3b.

### Recipient resolution rules

`alerts` reads staff through `UserService` (a sanctioned cross-module service-layer read) and never
through `UserRepository`. Both lookups consider only staff where `active` is `true`.

**Department Lead**, for the `SLA_BREACH` stage:

1. Resolve the assignee via `UserService.findById(event.assigneeId())`.
2. Find active staff in `event.storeId()` with `role == DEPARTMENT_LEAD` and
   `profile().department()` equal to the assignee's `profile().department()`.
3. Ties broken by lowest `id`, so the outcome is deterministic.
4. If step 2 finds nobody — including when the assignee is unknown, unassigned or has a null
   department — fall back to the store's `STORE_MANAGER` and log at `WARN`.
5. If that also finds nobody, log at `WARN` and raise nothing.

**Store Manager**, for the `ESCALATION` stage and for the fallback above: active staff in
`event.storeId()` with `role == STORE_MANAGER`, ties broken by lowest `id`.

An unresolvable recipient is **not** an error. The handler runs after commit, so a throw is swallowed
by Spring rather than surfaced to any caller; it must log and return.

### The `ESCALATION` collision

`AlertType.ESCALATION` is already used by `onTaskStatusChanged` for blocked activities, with
`sourceRef` set to the task id. An activity that was `BLOCKED` and then breached its SLA therefore
already has an `ESCALATION` row for its `sourceRef`, and a naive "has this task been escalated"
check would suppress the SLA escalation forever.

Stage identity is therefore carried by exact `subject` strings, declared as constants:

| Constant | Value | Raised by |
| --- | --- | --- |
| `SLA_BREACH_SUBJECT` | `SLA breach` | `onTaskOverdue`, first stage |
| `SLA_ESCALATION_SUBJECT` | `SLA breach escalated` | `onTaskOverdue`, second stage |
| (existing, unchanged) | `Activity blocked` | `onTaskStatusChanged` |

The stage-two suppression check is `alertType == ESCALATION && SLA_ESCALATION_SUBJECT.equals(subject)`.
Matching on `alertType` alone is the defect this paragraph exists to prevent.

## Error Mapping

| Failure | Subtype | Code | HTTP | Where |
| --- | --- | --- | --- | --- |
| `storeops.alerts.sla.grace-period` is negative | `ValidationError` | `VALIDATION_FAILED` | 400 | `SlaAlertProperties` compact constructor, at startup |
| `storeops.activities.sla.sweep.interval` is zero or negative | `ValidationError` | `VALIDATION_FAILED` | 400 | `SlaSweepProperties` compact constructor, at startup |
| `storeops.activities.sla.sweep.initial-delay` is negative | `ValidationError` | `VALIDATION_FAILED` | 400 | `SlaSweepProperties` compact constructor, at startup |
| Blank recipient reaches `NotificationService.raise` | `ValidationError` | `VALIDATION_FAILED` | 400 | existing behaviour; the listener must never reach it |
| No Department Lead and no Store Manager for the store | **none by design** | — | — | `AlertEventListener`, logs `WARN` and returns |

A zero grace period is **valid** and means "escalate on the next sweep". Tests rely on it.

No new `AppError` subtype and no new code string. Every failure above resolves to an existing entry in
the shared error vocabulary.

## Test Isolation Requirement

A live `@Scheduled` sweep inside `@SpringBootTest` would insert notifications mid-test and make the
existing suite non-deterministic — `ApiSmokeTest` and `NotificationRoutesTest` both assert on
notification contents. Two defences are required together, because the initial delay alone is a
latent flake rather than a guarantee:

1. `initial-delay` defaults to `PT15M`, so the first sweep cannot fire inside a test run.
2. Every existing `@SpringBootTest` class is given
   `properties = "storeops.activities.sla.sweep.enabled=false"`. The seven classes are
   `ApiSmokeTest`, `BulkStatusEventDeliveryIntegrationTest`, `BulkStatusSubscriberIsolationTest`,
   `BulkStatusUpdateIntegrationTest`, `EventDeliveryIntegrationTest`, `H2SchemaTest` and
   `StoreOpsApplicationTests`.

Do **not** add `src/test/resources/application.yml`. A test-classpath file of that name replaces the
main one wholesale rather than merging with it, taking the datasource and JPA configuration with it.

## Relevant Seed Data

The fixed January 2026 timestamps mean the seed produces exactly one SLA breach, which makes the
integration assertions determinate:

| Activity | Priority | Status | Due | SLA-tracked breach? |
| --- | --- | --- | --- | --- |
| `task-001` | `HIGH` | `TODO` | 2026-01-07 | **yes** — assignee `user-004`, department `GROCERY`, `store-001` |
| `task-002` | `MEDIUM` | `IN_PROGRESS` | 2026-01-08 | no — priority not tracked |
| `task-003` | `CRITICAL` | `DONE` | 2026-01-06 | no — terminal |
| `task-004` | `LOW` | `BLOCKED` | null | no — no due date, priority not tracked |

So the one breach routes to `user-003` (Lena Brandt, `DEPARTMENT_LEAD`, `GROCERY`, `store-001`) and
escalates to `user-002` (Sam Okafor, `STORE_MANAGER`, `store-001`).

## Sprint Breakdown

Sprint numbering continues from the shift-handover feature, whose Sprints 1 and 2 are archived in
`.harness/reviews/`. Restarting at 1 would overwrite that archive.

| Sprint | Title | Module | Why it is a separate sprint |
| --- | --- | --- | --- |
| 3 | Scheduled overdue detection | `activities` only | The publisher side. Per `sprint-decomposition` §2, the module that fires the event ships before the module that consumes it. Provable with `RecordingEventBus`, no `alerts` code involved |
| 4 | SLA breach routed to the Department Lead | `alerts`, `staff` | The consumer side, stage one: recipient resolution and de-duplication. Provable with a fixed clock and a fake repository |
| 5 | Grace-period escalation to the Store Manager | `alerts` | Stage two is the only time-dependent behaviour in the feature and the only part that needs the grace-period configuration. Bundling it into Sprint 4 would put recipient routing and time-window logic behind one verdict |

Sprint 3 leaves the system in a coherent state: the sweep publishes, and the existing stub listener
keeps raising its (wrongly addressed) alert. Sprint 4 corrects the recipient and stops the duplicates.
Sprint 5 adds the second stage. No sprint depends on a later one to compile or pass.

STATUS: AWAITING APPROVAL
