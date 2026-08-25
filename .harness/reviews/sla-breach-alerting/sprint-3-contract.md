# Sprint 3: Grace Period Escalation and Episode Closure

## Goal

Complete the feature: a breach that is still being observed once a configurable grace period has
elapsed escalates to the store's `STORE_MANAGER`, exactly once; and a breach that is resolved — the
activity reaches `DONE` — closes its episode so it never escalates and a later reopening starts fresh.

This is the only sprint that depends on elapsed time and on configuration binding. `alerts` only. No
route.

Deliverables:

* `alerts/service/SlaEscalationProperties` — record with `@ConfigurationProperties("storeops.alerts.sla")`
  holding `Duration gracePeriod`. Compact constructor substitutes `PT2H` when the bound value is null or
  negative, via a `private static final Duration DEFAULT_GRACE_PERIOD`
* `StoreOpsApplication` — add `@ConfigurationPropertiesScan`
* `src/main/resources/application.yml` — `storeops.alerts.sla.grace-period: PT2H`, commented
* `alerts/domain/SlaBreach` — two new components, `String escalationRecipientId` and
  `Instant escalatedAt`, plus `withEscalation(String recipientId, Instant escalatedAt)`
* `alerts/repository/SlaBreachEntity` — the matching `escalation_recipient_id` and `escalated_at`
  columns, both nullable
* `alerts/repository/SlaBreachRepository` + `JpaSlaBreachRepository` — new
  `boolean deleteByTaskId(String taskId)`
* `alerts/service/SlaBreachService` — the escalation branch inside `observe(...)`, plus a new
  `void closeEpisode(String taskId)`
* `alerts/listener/AlertEventListener.onTaskStatusChanged` — when `newStatus` is `DONE`, call
  `slaBreachService.closeEpisode(event.taskId())`. The existing `BLOCKED` → `ESCALATION` behaviour is
  unchanged and must stay ahead of or beside this, not be replaced by it
* `src/test/java/com/cognizant/storeops/H2SchemaTest` — extend the `SLA_BREACHES` column assertion

The escalation rule, evaluated inside `observe(...)`:

1. **Only for an event that finds an existing episode.** A first observation notifies the lead and stops,
   whatever the grace period is — including `PT0S`. Escalation is a statement about persistence, so it
   needs at least two observations.
2. Already escalated (`escalatedAt != null`) → nothing.
3. `event.occurredAt()` is at or after `firstBreachAt + gracePeriod` → resolve the store's active
   `STORE_MANAGER` (lowest `id`), raise one `ESCALATION` notification, and write `escalatedAt` and
   `escalationRecipientId`.
4. Grace not yet elapsed → update `lastSeenAt` only.
5. Escalation recipient equals `leadRecipientId` (the Sprint 2 fallback case) → **mark the episode
   escalated and raise nothing.** One activity, one person, one alert.
6. No active `STORE_MANAGER` → leave `escalatedAt` null so a later observation retries. `WARN`.

The escalation notification: `alertType=ESCALATION`, `sourceRef=taskId`, subject
`"Escalated: SLA breach unresolved on <PRIORITY> activity"`, body naming the activity, the store, the
elapsed grace period and the lead who was notified first.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

Scenarios 1–8 are service-level tests over in-memory fakes with a movable `Clock`. The staff fixture is
Sprint 2's: `user-002` STORE_MANAGER/store-001, `user-003` DEPARTMENT_LEAD/GROCERY/store-001, `user-004`
ASSOCIATE/GROCERY/store-001. `T0` is `2026-02-01T10:00:00Z`; the grace period is `PT2H` unless stated.

**Scenario 1: A breach still observed after the grace period escalates to the Store Manager**
* **GIVEN** `observe(...)` has already run once at `T0` for HIGH `task-001` (assignee `user-004`),
  raising the `SLA_BREACH` for `user-003`
* **WHEN** `observe(...)` runs again with `occurredAt = T0 + 2h`
* **THEN** exactly one further notification is raised: `recipientId="user-002"`,
  `alertType=ESCALATION`, `sourceRef="task-001"`, `status=PENDING`, subject
  `"Escalated: SLA breach unresolved on HIGH activity"`
* **AND** the `SlaBreach` row reads `escalationRecipientId="user-002"` and `escalatedAt=T0 + 2h`
* **AND** `firstBreachAt`, `leadRecipientId` and `leadNotifiedAt` are unchanged
* **AND** the lead receives no second notification

**Scenario 2: Before the grace period elapses, nothing is raised**
* **GIVEN** the same episode opened at `T0`
* **WHEN** `observe(...)` runs at `T0 + 1h59m`
* **THEN** the notification total is still `1` — the lead alert only
* **AND** `escalatedAt` is null and `lastSeenAt` is `T0 + 1h59m`

**Scenario 3: A first observation never escalates, even with a zero grace period**
* **GIVEN** `storeops.alerts.sla.grace-period` bound to `PT0S` and no episode for `task-001`
* **WHEN** `observe(...)` runs once at `T0`
* **THEN** exactly one notification is raised, the `SLA_BREACH` for `user-003`, and `escalatedAt` is null
* **AND** when `observe(...)` runs a second time at `T0` — same instant, grace zero — the `ESCALATION`
  for `user-002` is raised

**Scenario 4: Escalation happens once, however many observations follow**
* **GIVEN** the Scenario 1 end state, already escalated
* **WHEN** `observe(...)` runs at `T0 + 3h`, `T0 + 4h` and `T0 + 24h`
* **THEN** no further notification of any type is raised
* **AND** `escalatedAt` still reads `T0 + 2h`

**Scenario 5: The same person is never told twice about one activity**
* **GIVEN** an assignee with no matching lead, so the Sprint 2 fallback sent the `SLA_BREACH` to
  `user-002`, the store manager, at `T0`
* **WHEN** `observe(...)` runs at `T0 + 2h`
* **THEN** no `ESCALATION` notification is raised — the total stays at `1`
* **AND** the episode is nonetheless marked escalated: `escalatedAt = T0 + 2h` and
  `escalationRecipientId="user-002"`, so the check is not repeated on every later sweep

**Scenario 6: With no Store Manager, escalation is retried rather than lost**
* **GIVEN** an episode at `store-999` opened at `T0` (its lead resolvable, its manager not) and a grace
  period of `PT2H`
* **WHEN** `observe(...)` runs at `T0 + 2h`
* **THEN** no `ESCALATION` is raised and `escalatedAt` stays null
* **AND** when an active `STORE_MANAGER` is added to `store-999` and `observe(...)` runs at `T0 + 3h`,
  one `ESCALATION` is raised for them and `escalatedAt` reads `T0 + 3h`

**Scenario 7: The grace period is genuinely configurable, and defaults safely**
* **GIVEN** an episode opened at `T0` and a second observation at `T0 + 3h`
* **WHEN** the service is built with `gracePeriod = PT48H`
* **THEN** no `ESCALATION` is raised; built with `PT1H` against the same clock, one is
* **AND** `new SlaEscalationProperties(null)` yields `PT2H`, and
  `new SlaEscalationProperties(Duration.ofHours(-1))` also yields `PT2H`
* **AND** a `@SpringBootTest` with the property absent binds `PT2H`, and one with
  `@TestPropertySource(properties = "storeops.alerts.sla.grace-period=PT30M")` binds `PT30M` — proving
  `@ConfigurationPropertiesScan` actually took effect

**Scenario 8: Reaching DONE closes the episode**
* **GIVEN** an episode for `task-001` opened at `T0`, not yet escalated
* **WHEN** `closeEpisode("task-001")` is called — as `onTaskStatusChanged` does for a `DONE`
  transition — and then `observe(...)` runs at `T0 + 5h`
* **THEN** no `ESCALATION` is raised at any point
* **AND** the `T0 + 5h` observation is treated as a **first** observation: a new episode with
  `firstBreachAt = T0 + 5h` and a fresh `SLA_BREACH` notification for `user-003`
* **AND** `closeEpisode` on a `taskId` with no episode is a no-op that raises nothing and throws nothing

**Scenario 9: A DONE transition closes the episode through the listener; BLOCKED still escalates the
assignee**
* **GIVEN** an `AlertEventListener` over the real `SlaBreachService` with an open episode for `task-001`
* **WHEN** `onTaskStatusChanged(new TaskStatusChangedEvent("task-001", "store-001", "TODO", "DONE",
  "HIGH", "user-004", NOW))` is handled
* **THEN** the episode for `task-001` no longer exists and no notification is raised for the transition
* **AND** when `onTaskStatusChanged(... "IN_PROGRESS" → "BLOCKED" ...)` is handled instead, the existing
  behaviour is intact: one `ESCALATION` notification for the assignee `user-004` with
  `sourceRef="task-001"`, and no episode is closed

**Scenario 10: The whole chain works end to end through the container**
* **GIVEN** a `@SpringBootTest` context with the real `EventBus`, listener, service and H2 seed data,
  `@TestPropertySource(properties = "storeops.alerts.sla.grace-period=PT0S")`, and `task-001` HIGH,
  `TODO`, assigned to `user-004`, past due against the real clock
* **WHEN** `taskService.publishOverdueBreaches()` is called twice
* **THEN** `GET /api/notifications?recipientId=user-003` includes exactly one `SLA_BREACH` with
  `sourceRef="task-001"`
* **AND** `GET /api/notifications?recipientId=user-002` includes exactly one `ESCALATION` with
  `sourceRef="task-001"`
* **AND** a third sweep adds neither
* **AND** filter by `sourceRef` rather than asserting totals — the H2 context is shared

**Scenario 11: Resolution through the real API closes the episode end to end**
* **GIVEN** the same context, with an episode already open for a HIGH overdue activity created through
  `POST /api/tasks` and swept once
* **WHEN** `PATCH /api/tasks/{id}` sets it to `DONE`, and `publishOverdueBreaches()` is then called
* **THEN** no `ESCALATION` notification exists for that activity's `sourceRef`
* **AND** the `sla_breaches` row for it is gone — proving the `DONE` transition reached
  `closeEpisode` after commit

**Scenario 12: The schema still matches**
* **GIVEN** the updated `H2SchemaTest`
* **WHEN** `./mvnw clean test` runs
* **THEN** `SLA_BREACHES` has exactly the columns `TASK_ID, STORE_ID, PRIORITY, FIRST_BREACH_AT,
  LEAD_RECIPIENT_ID, LEAD_NOTIFIED_AT, LAST_SEEN_AT, ESCALATION_RECIPIENT_ID, ESCALATED_AT`

## Architectural Guardrails

* **Escalation is decided from the episode, never by asking the activities module.** No call into
  `TaskService` to check whether the activity is still open: the arrival of a repeat `TaskOverdueEvent`
  is that fact, and `closeEpisode` is how resolution arrives. Importing `activities` fails ArchUnit
  `noCrossModuleRepositoryImports` at the repository level and breaks the event-bus rule at the service
  level.
* **The grace period is read from bound configuration, not from a constant.** A hard-coded `PT2H`
  satisfies Scenarios 1 and 2 and fails the request. `SlaEscalationProperties` must be injected into
  `SlaBreachService`; Scenario 7's `@SpringBootTest` half is what proves the binding is wired rather
  than merely declared.
* **The properties record lives in `alerts/service`.** Modules keep to `routes`, `service`,
  `repository`, `domain`, `listener` — there is no `config` package, and putting the record in `shared`
  would make shared code depend on a module and fail `eventsDoNotLeakModuleTypes`.
* **`escalatedAt` is the idempotency key, and it is in the database.** Sprint 2's guardrail applies with
  more force here: a flag held in the service escalates again after every restart. Scenario 4 over
  fakes and Scenario 10 through H2 must both hold.
* **A first observation must not escalate.** Guarding only on `occurredAt >= firstBreachAt +
  gracePeriod` makes a zero or misconfigured grace period fire both notifications in the same sweep,
  which reads to the store manager as an escalation that was never given a chance to be resolved.
  Require an existing episode. Scenario 3 is the test.
* **`onTaskStatusChanged` keeps both jobs, and both annotations.** The `BLOCKED` → `ESCALATION` path and
  the new `DONE` → `closeEpisode` path are independent branches of one handler; it stays
  `@TransactionalEventListener(AFTER_COMMIT)` with `@Transactional(REQUIRES_NEW)`, or the deletion joins
  a committed transaction and is silently discarded. Scenario 9 exists to catch a rewrite that drops the
  older behaviour.
* **Nothing throws out of this path**, for the same reason as Sprint 2: there is no caller to receive
  it. A missing store manager is a logged retry, not an error. `IllegalCatch` forbids reaching that
  outcome with `catch (Exception ...)`.
* **No new `AlertType` value, no new `AppError` subtype, no change to `shared`.** `ESCALATION` is reused
  deliberately — see the flagged note in `spec.md`. If the human overruled it at approval, add
  `SLA_ESCALATION` to `AlertType` and update this contract's expected values; do not decide it here.
* **No route.** The breach tracker stays unexposed in this sprint; `GET /api/notifications` is the read
  surface. An endpoint for open episodes is a separate sprint with its own contract.
