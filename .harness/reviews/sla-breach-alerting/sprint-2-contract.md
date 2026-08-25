# Sprint 2: The Lead Alert and the Breach Episode

## Goal

Turn the repeat `TaskOverdueEvent`s from Sprint 1 into **exactly one** `SLA_BREACH` notification for the
Department Lead responsible for the assignee. The alerts module gains a durable record of the breach
episode — the thing that makes "exactly one" true across sweeps, restarts and multiple observations —
and the staff module gains the single read that recipient resolution needs.

No escalation and no grace period in this sprint: that is Sprint 3. No route, in any module.

Deliverables:

* `staff/repository/UserRepository` — new read `List<User> findByStoreIdAndRole(String storeId,
  StaffRole role)`; the query on `UserJpaRepository` and the mapping in `JpaUserRepository`
* `staff/service/UserService` — same method, delegating. Read-only, as the class contract requires
* `src/test/java/.../support` — any `UserRepository` test double must implement the new read
* `alerts/domain/SlaBreach` — record `(String taskId, String storeId, String priority,
  Instant firstBreachAt, String leadRecipientId, Instant leadNotifiedAt, Instant lastSeenAt)` with
  `withLastSeen(Instant)`. **The escalation fields arrive in Sprint 3** — do not add unused fields now
* `alerts/repository/SlaBreachEntity` — `@Entity @Table(name = "sla_breaches")`, `@Id` on `task_id`,
  `fromDomain` / `toDomain` in the style of `NotificationEntity`
* `alerts/repository/SlaBreachJpaRepository`, `SlaBreachRepository` (port: `save`,
  `findByTaskId(String)`, `findAll()`), `JpaSlaBreachRepository` (adapter)
* `alerts/service/SlaBreachService` — `@Service`, injected `NotificationService`, `UserService`,
  `SlaBreachRepository`, `Clock`. Public entry point `void observe(TaskOverdueEvent event)` holding
  the priority filter, the dedup decision and recipient resolution
* `alerts/listener/AlertEventListener` — `onTaskOverdue` becomes a delegation to
  `slaBreachService.observe(event)`; keeps `@TransactionalEventListener(phase = AFTER_COMMIT)` and
  `@Transactional(propagation = REQUIRES_NEW)`. The `SLA_TRACKED_PRIORITIES` constant moves to the
  service. `onTaskStatusChanged` is untouched
* `src/main/resources/data.sql` — a comment recording that `sla_breaches` is intentionally unseeded
* `src/test/java/com/cognizant/storeops/H2SchemaTest` — add `SLA_BREACHES` to the
  `containsExactly` table list (between `REPORTS` and `TASKS`) and assert the new table's columns
* `src/test/java/.../alerts/listener/AlertEventListenerTest` — update the three `onTaskOverdue` tests
  for the new constructor and the new recipient

Recipient resolution, in order — the assignee is looked up via `UserService.findById`, never `getById`:

1. Active `DEPARTMENT_LEAD` in the assignee's `storeId` whose `profile().department()` equals the
   assignee's, lowest `id` first
2. The assignee themselves, if they are an active `DEPARTMENT_LEAD`
3. Fallback: the store's active `STORE_MANAGER`, lowest `id` first
4. Nobody resolvable → no notification **and no tracker row**, logged at `WARN`

Timestamps come from the injected `Clock`, never `Instant.now()`.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

Scenarios 1–8 are service-level tests over in-memory fakes with `Clock.fixed` at
`2026-02-01T10:00:00Z`; `NOW` below means that instant. The staff fixture mirrors the seed data:
`user-002` STORE_MANAGER/store-001, `user-003` DEPARTMENT_LEAD/GROCERY/store-001, `user-004`
ASSOCIATE/GROCERY/store-001, all active.

**Scenario 1: A first observation notifies the Department Lead, not the assignee**
* **GIVEN** no `sla_breaches` row for `task-001`
* **WHEN** `observe(new TaskOverdueEvent("task-001", "store-001", "HIGH", "user-004",
  2026-01-07T08:00:00Z, NOW))` is called
* **THEN** exactly one notification is raised, with `recipientId="user-003"`,
  `alertType=SLA_BREACH`, `channel=IN_APP`, `status=PENDING`, `sourceRef="task-001"`, and a subject
  naming the priority — `"SLA breach on HIGH activity"`
* **AND** no notification is raised for `user-004`, the assignee
* **AND** a `SlaBreach` row exists for `task-001` with `firstBreachAt=NOW`,
  `leadRecipientId="user-003"`, `leadNotifiedAt=NOW`, `storeId="store-001"`, `priority="HIGH"`

**Scenario 2: A repeat observation raises nothing and moves no timestamp but `lastSeenAt`**
* **GIVEN** the Scenario 1 state, and a clock advanced to `2026-02-01T10:05:00Z`
* **WHEN** `observe(...)` is called again for `task-001`
* **THEN** the total notification count is still `1`
* **AND** the `SlaBreach` row still reads `firstBreachAt=2026-02-01T10:00:00Z` and
  `leadNotifiedAt=2026-02-01T10:00:00Z`, with `lastSeenAt=2026-02-01T10:05:00Z`
* **AND** a third and fourth observation still raise nothing

**Scenario 3: An assignee who is themselves a Department Lead is notified directly**
* **GIVEN** an event for a CRITICAL activity `task-500` at `store-001` assigned to `user-003`
* **WHEN** `observe(...)` is called
* **THEN** one notification is raised with `recipientId="user-003"` and `alertType=SLA_BREACH`
* **AND** the `SlaBreach` row records `leadRecipientId="user-003"`

**Scenario 4: With no Department Lead for the department, the Store Manager is notified**
* **GIVEN** an assignee `user-900` at `store-001` whose department is `BAKERY`, and no
  `DEPARTMENT_LEAD` at `store-001` in `BAKERY`
* **WHEN** `observe(...)` is called for their HIGH overdue activity
* **THEN** one notification is raised with `recipientId="user-002"` and `alertType=SLA_BREACH` — the
  fallback keeps the type `SLA_BREACH`, it is not an escalation
* **AND** the `SlaBreach` row records `leadRecipientId="user-002"`
* **AND** the same holds when the assignee has no department at all (`profile().department()` is null)

**Scenario 5: An inactive Department Lead is skipped**
* **GIVEN** `user-003` is `active = false`, `user-002` STORE_MANAGER is active
* **WHEN** `observe(...)` is called for `task-001` (assignee `user-004`, GROCERY)
* **THEN** the single notification goes to `user-002`, never to the inactive `user-003`

**Scenario 6: An unresolvable assignee alerts nobody and records nothing**
* **GIVEN** an event whose `assigneeId` is `"user-does-not-exist"`
* **WHEN** `observe(...)` is called
* **THEN** no notification is raised, no `SlaBreach` row is written, and no exception escapes
* **AND** the same holds for an event with `assigneeId` null and for one with `assigneeId` blank

**Scenario 7: A store with no lead and no manager is retried, not swallowed**
* **GIVEN** an event for an activity at `store-999`, where the staff fixture has no active
  `DEPARTMENT_LEAD` and no active `STORE_MANAGER`
* **WHEN** `observe(...)` is called
* **THEN** no notification is raised and **no `SlaBreach` row is written** — the absence of the row is
  what allows the next sweep to alert once the staff record is corrected
* **AND** when an active `STORE_MANAGER` is then added to `store-999` and `observe(...)` is called
  again, one `SLA_BREACH` notification is raised for them

**Scenario 8: A priority outside the SLA bands is ignored**
* **GIVEN** any staff fixture
* **WHEN** `observe(...)` is called with `priority="MEDIUM"`, and again with `"LOW"`
* **THEN** no notification is raised and no `SlaBreach` row is written in either case
* **AND** `"CRITICAL"` and `"HIGH"` both do raise one

**Scenario 9: The staff read returns only the matching store and role**
* **GIVEN** a context against H2 with the seed data
* **WHEN** `userService.findByStoreIdAndRole("store-001", StaffRole.STORE_MANAGER)` is called
* **THEN** the result is exactly `[user-002]` — not `user-005`, who is a STORE_MANAGER at `store-002`
* **AND** `findByStoreIdAndRole("store-001", StaffRole.DEPARTMENT_LEAD)` returns exactly `[user-003]`
* **AND** `findByStoreIdAndRole("store-404", StaffRole.STORE_MANAGER)` returns an empty list

**Scenario 10: The sweep reaches the lead end to end, once**
* **GIVEN** a `@SpringBootTest` context with the real `EventBus`, `AlertEventListener`,
  `SlaBreachService` and H2 seed data, where `task-001` is HIGH, `TODO`, assigned to `user-004` and past
  due against the real clock
* **WHEN** `taskService.publishOverdueBreaches()` is called **twice**
* **THEN** `GET /api/notifications?recipientId=user-003` includes exactly one notification whose
  `alertType` is `SLA_BREACH` and whose `sourceRef` is `task-001` — proving the dedup survives a real
  transaction boundary and a real database, not just a fake
* **AND** `GET /api/notifications?recipientId=user-004` gains no `SLA_BREACH` for `task-001`
* **AND** a `sla_breaches` row exists for `task-001`
* **NOTE** filter by `sourceRef` rather than asserting a total count; other tests in the shared context
  may create overdue activities of their own

**Scenario 11: The schema and the seed script still agree**
* **GIVEN** the updated `H2SchemaTest`
* **WHEN** `./mvnw clean test` runs
* **THEN** the table list reads exactly `NOTIFICATIONS, PROJECTS, PROJECT_MEMBERS, REPORTS,
  SLA_BREACHES, TASKS, USERS`
* **AND** `SLA_BREACHES` has exactly the columns `TASK_ID, STORE_ID, PRIORITY, FIRST_BREACH_AT,
  LEAD_RECIPIENT_ID, LEAD_NOTIFIED_AT, LAST_SEEN_AT`
* **AND** `seedDataLoaded` still passes — `data.sql` inserts no `sla_breaches` rows

## Architectural Guardrails

* **The dedup must be persisted, never held in the service.** A `Set<String>` field or a cache in
  `SlaBreachService` would satisfy every unit test here and then re-alert on the next restart, and would
  give Sprint 3 nothing to measure a grace period from. The `sla_breaches` row is the only permitted
  memory. Scenario 10, which crosses a real transaction, is the criterion that distinguishes them.
* **`alerts` must not import `activities`.** The event's strings are all it gets. Reading `TaskService`
  to re-check whether the activity is still open is forbidden — the arrival of a repeat event *is* that
  information. Only `shared.events.TaskOverdueEvent` may cross, which is why `SlaBreachService` may take
  the event record as its parameter: `shared` depends on no module, enforced by
  `eventsDoNotLeakModuleTypes`.
* **`alerts` reads `staff` through `UserService` only.** Importing `staff.repository` fails ArchUnit
  `noCrossModuleRepositoryImports` and `repositoriesAreReachedOnlyFromServices`.
* **`UserService` stays free of mutators.** Add a read; do not add a write, and do not let recipient
  policy leak into it. "Which lead covers this assignee" is an alerts decision composed from staff
  reads — `UserService` answers "who holds this role at this store", nothing more.
* **The listener keeps `AFTER_COMMIT` and `REQUIRES_NEW`.** Dropping the phase alerts on rolled-back
  work; dropping `REQUIRES_NEW` means the write joins an already-committed transaction and is discarded
  with no error anywhere — the failure mode the class comment exists to warn about. The evaluator treats
  a missing `REQUIRES_NEW` as a hard gate.
* **Nothing throws out of this path.** No HTTP caller exists, and the `EventBus` `ErrorHandler` absorbs
  what escapes, so a thrown error loses the alert silently. Use `Optional` and explicit null checks —
  Checkstyle `IllegalCatch` forbids `catch (Exception ...)` as a way of achieving the same thing, and
  `NoRawErrorThrows` forbids raw throws.
* **`NotificationService.raise` is never called with an unresolved recipient.** Resolve first; a blank
  recipient makes it throw `ValidationError`, inside a listener, where nobody sees it.
* **The decision lives in the service, the translation in the listener.** The priority filter, the
  fallback chain and the dedup are `SlaBreachService`'s; `onTaskOverdue` is one delegating call.
* **No new `AppError` subtype, no new `AlertType` value, no change to `shared`.**
* **Resolution must be deterministic.** Order candidates by `id` and take the first, so a store with two
  leads in one department cannot make the test suite flaky.
