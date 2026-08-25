# Sprint 1: The Overdue Sweep

## Goal

Give the activities module a clock. A scheduled sweep publishes `TaskOverdueEvent` for every HIGH or
CRITICAL activity that is past its `dueAt` and not `DONE`, and **republishes on every pass** while the
activity stays that way — the repeat is the signal the alerts module will later use to time an
escalation.

This sprint touches `activities` only. No alerts behaviour changes, no new event type, no new table, no
route.

Deliverables:

* `activities/repository/TaskRepository` — new finder `List<Task> findOpenPastDue(Instant moment)`,
  returning activities whose `dueAt` is before `moment` and whose status is not `DONE`
* `activities/repository/TaskJpaRepository` — the backing query (derived or `@Query`);
  `JpaTaskRepository` — the adapter, mapping entities to domain records like its siblings
* `src/test/java/com/cognizant/storeops/support/FakeTaskRepository` — implements the new finder in
  memory, applying the same predicate
* `activities/service/TaskService.publishOverdueBreaches()` — reworked to call `findOpenPastDue(now)`
  instead of `findAll()`, still filtering with `Task::isSlaTracked`, still `@Transactional`, still
  returning the number of events published
* `activities/listener/OverdueSweepScheduler` — `@Component`, gated
  `@ConditionalOnProperty(name = "storeops.activities.sla.sweep.enabled", matchIfMissing = true)`, one
  `@Scheduled(fixedDelayString = "${storeops.activities.sla.sweep-interval:PT5M}",
  initialDelayString = "${storeops.activities.sla.initial-delay:PT10M}")` method that calls
  `taskService.publishOverdueBreaches()` and logs the count
* `StoreOpsApplication` — add `@EnableScheduling`
* `src/main/resources/application.yml` — declare `storeops.activities.sla.sweep-interval: PT5M`,
  `initial-delay: PT10M`, `sweep.enabled: true`, each with a comment saying why the initial delay is
  long

Timestamps come from the injected `Clock`, never `Instant.now()`.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: The sweep publishes only for SLA-tracked, overdue, unfinished activities**
* **GIVEN** a `TaskService` on `FakeTaskRepository` with `Clock.fixed` at `2026-02-01T10:00:00Z` and
  four activities: `task-001` HIGH `TODO` due `2026-01-07T08:00:00Z`; `task-002` MEDIUM `IN_PROGRESS`
  due `2026-01-08T08:00:00Z`; `task-003` CRITICAL `DONE` due `2026-01-06T09:00:00Z`; `task-004` LOW
  `BLOCKED` with `dueAt` null
* **WHEN** `publishOverdueBreaches()` is called
* **THEN** it returns `1`
* **AND** exactly one `TaskOverdueEvent` is published, carrying
  `(taskId="task-001", storeId="store-001", priority="HIGH", assigneeId="user-004",
  dueAt=2026-01-07T08:00:00Z, occurredAt=2026-02-01T10:00:00Z)`
* **AND** no event names `task-002` (not SLA-tracked), `task-003` (`DONE`) or `task-004` (no due date)

**Scenario 2: An activity not yet past due publishes nothing**
* **GIVEN** the same fixture with a single CRITICAL `TODO` activity due `2026-02-01T18:00:00Z` and the
  clock at `2026-02-01T10:00:00Z`
* **WHEN** `publishOverdueBreaches()` is called
* **THEN** it returns `0` and no `TaskOverdueEvent` is published

**Scenario 3: A still-overdue activity is republished on every sweep**
* **GIVEN** the Scenario 1 fixture, unchanged between calls
* **WHEN** `publishOverdueBreaches()` is called three times
* **THEN** each call returns `1` and **three** `TaskOverdueEvent`s for `task-001` have been published
* **AND** no de-duplication state of any kind is held in the activities module — this repetition is the
  contract, not a defect

**Scenario 4: The finder applies the predicate in the database, not in memory**
* **GIVEN** a `@SpringBootTest` (or `@DataJpaTest`) context against H2 with the seed data, and a clock
  reading later than `2026-01-08T08:00:00Z`
* **WHEN** `taskRepository.findOpenPastDue(that moment)` is called
* **THEN** the result contains `task-001` and `task-002`
* **AND** it excludes `task-003` (`DONE` despite being past due) and `task-004` (`dueAt` is null)
* **AND** the returned objects are `Task` domain records, not `TaskEntity`

**Scenario 5: The scheduler delegates and decides nothing**
* **GIVEN** an `OverdueSweepScheduler` built over a stubbed or mocked `TaskService`
* **WHEN** its scheduled method is invoked directly
* **THEN** `publishOverdueBreaches()` is called exactly once
* **AND** the scheduler itself performs no priority check, no due-date arithmetic and no event
  publication of its own

**Scenario 6: The sweep can be switched off, and is on by default**
* **GIVEN** a `@SpringBootTest` context with
  `@TestPropertySource(properties = "storeops.activities.sla.sweep.enabled=false")`
* **WHEN** the context asks for an `OverdueSweepScheduler` bean
* **THEN** no such bean exists
* **AND** a context with the property absent does contain exactly one `OverdueSweepScheduler`

**Scenario 7: A swept event reaches its after-commit subscriber**
* **GIVEN** a `@SpringBootTest` context with the real `EventBus`, `AlertEventListener` and H2 seed data,
  where `task-001` is HIGH, `TODO`, assigned to `user-004`, and past due against the real clock
* **WHEN** `taskService.publishOverdueBreaches()` is called
* **THEN** `GET /api/notifications?recipientId=user-004` includes a notification whose `alertType` is
  `SLA_BREACH` and whose `sourceRef` is `task-001`
* **AND** this proves `publishOverdueBreaches()` is still transactional: without `@Transactional` the
  after-commit handler never runs and this assertion is the only thing that catches it
* **NOTE** assert with `anySatisfy` on `sourceRef`, not on a total count — the H2 context is shared with
  other tests in the same class and activities created there may also be overdue

**Scenario 8: The timer does not fire during the test suite**
* **GIVEN** `@EnableScheduling` is now active for every `@SpringBootTest`
* **WHEN** the full `./mvnw clean test` runs
* **THEN** it passes with no notification counts disturbed in
  `ApiSmokeTest`, `NotificationRoutesTest`, `EventDeliveryIntegrationTest` or `ReportRoutesTest`
* **AND** `storeops.activities.sla.initial-delay` is long enough (`PT10M`) that no autonomous sweep can
  fire inside a test run; tests trigger the sweep by calling the service directly

## Architectural Guardrails

* **The priority rule must not move into SQL.** `findOpenPastDue` filters on `dueAt` and `status` only;
  the HIGH/CRITICAL band stays in `Task.isSlaTracked()` where the domain already owns it. A repository
  query naming `HIGH` or `CRITICAL` puts a business rule in the data layer and splits one rule across
  two files.
* **`publishOverdueBreaches()` must keep `@Transactional`.** Every subscriber is
  `@TransactionalEventListener(AFTER_COMMIT)`; with no transaction to commit Spring silently skips them
  all and the whole feature does nothing while every unit test still passes. Scenario 7 is the only
  guard against this.
* **The scheduler holds no business logic.** It lives in `activities/listener` because it is an inbound
  adapter — Rule 5 permits Listener → Service. It must not touch `TaskRepository` (Rule 1b), must not
  inspect priorities or due dates, and must not build events.
* **No de-duplication in activities.** Remembering which breaches were already announced would put
  alerting state in the module that must know nothing about alerting, and would deny the alerts module
  the repeat signal it needs to time a grace period. Scenario 3 exists to fail any attempt.
* **`activities` must not import `alerts` or `reports`.** ArchUnit
  `sideEffectsCrossBoundariesOnlyViaTheEventBus` fails the build; the sweep's only outbound edge is
  `EventBus.publish`.
* **No change to `shared`.** `TaskOverdueEvent` is already the right shape — `assigneeId` and `storeId`
  are enough for the alerts module to resolve recipients later. `eventsDoNotLeakModuleTypes` forbids
  giving it a module type, and nothing needs one.
* **No new `AppError` subtype and no throwing from the sweep.** An empty result set is an ordinary
  outcome that returns `0`.
* **Update the existing test double rather than working around it.** `FakeTaskRepository` implements
  `TaskRepository`; adding the finder to the interface breaks compilation until the fake implements it
  too, with the same predicate. Do not weaken the interface to avoid the edit.
