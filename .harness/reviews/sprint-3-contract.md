# Sprint 3: Scheduled Overdue Detection

## Goal

Give `TaskService.publishOverdueBreaches()` a scheduled caller and a configuration surface, so that
every SLA-tracked activity past its due date produces a `TaskOverdueEvent` on every sweep cycle. This
sprint is `activities` only: it proves the event is published, not what anyone does with it.

Deliverables:
* `activities/service/SlaSweepProperties.java` — `@ConfigurationProperties("storeops.activities.sla.sweep")`
  record with `boolean enabled`, `Duration interval`, `Duration initialDelay`, validating in its
  compact constructor.
* `activities/service/SlaSweepScheduler.java` — `@Component` guarded by
  `@ConditionalOnProperty(name = "storeops.activities.sla.sweep.enabled", havingValue = "true", matchIfMissing = true)`,
  carrying
  `@Scheduled(fixedDelayString = "${storeops.activities.sla.sweep.interval}", initialDelayString = "${storeops.activities.sla.sweep.initial-delay}")`
  on a method that delegates to `TaskService.publishOverdueBreaches()` and logs the count returned.
* `StoreOpsApplication` — add `@EnableScheduling` and `@ConfigurationPropertiesScan`.
* `src/main/resources/application.yml` — add the three `storeops.activities.sla.sweep` keys with the
  defaults from `spec.md`.
* The seven existing `@SpringBootTest` classes — add
  `properties = "storeops.activities.sla.sweep.enabled=false"`.

`TaskService.publishOverdueBreaches()` keeps its current body and its `@Transactional` annotation.
Update its javadoc to remove the words "Stub: nothing schedules this yet"; do not change its logic or
its signature.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: The sweep publishes one event per SLA-tracked overdue activity**
* **GIVEN** a `TaskService` built on `FakeTaskRepository` and `RecordingEventBus` with a `Clock` fixed
  at `2026-02-01T00:00:00Z`, holding the four seed activities
* **WHEN** `publishOverdueBreaches()` is called
* **THEN** it returns `1`
* **AND** `RecordingEventBus` holds exactly one `TaskOverdueEvent`, whose `taskId` is `task-001`,
  `storeId` is `store-001`, `priority` is the string `HIGH`, `assigneeId` is `user-004`, `dueAt` is
  `2026-01-07T08:00:00Z` and `occurredAt` is the fixed clock instant

**Scenario 2: The sweep ignores every activity that is not a tracked breach**
* **GIVEN** the same fixture as Scenario 1
* **WHEN** `publishOverdueBreaches()` is called
* **THEN** no `TaskOverdueEvent` is published for `task-002` (`MEDIUM`, priority not tracked), for
  `task-003` (`CRITICAL` but `DONE`) or for `task-004` (no `dueAt`)
* **AND** each exclusion is asserted individually, so a test failure names which filter broke

**Scenario 3: An activity due in the future is not a breach**
* **GIVEN** a `CRITICAL` `TODO` activity with `dueAt` of `2026-03-01T00:00:00Z` and a `Clock` fixed at
  `2026-02-01T00:00:00Z`
* **WHEN** `publishOverdueBreaches()` is called
* **THEN** it returns `0` and no event is published

**Scenario 4: The sweep re-publishes on every cycle**
* **GIVEN** the Scenario 1 fixture
* **WHEN** `publishOverdueBreaches()` is called three times with no intervening repository change
* **THEN** it returns `1` each time and `RecordingEventBus` holds three `TaskOverdueEvent` instances
* **AND** a comment records why this is correct rather than a bug: re-publication is how the `alerts`
  module learns the activity is *still* unresolved, and de-duplication is Sprint 4's job

**Scenario 5: The scheduler delegates to the service**
* **GIVEN** a `SlaSweepScheduler` built on a Mockito mock of `TaskService`
* **WHEN** its sweep method is invoked directly
* **THEN** `publishOverdueBreaches()` is called exactly once
* **AND** no exception escapes when the mock returns `0`

**Scenario 6: The scheduled bean is wired and annotated in the running application**
* **GIVEN** a `@SpringBootTest` with `properties = "storeops.activities.sla.sweep.enabled=true"`
* **WHEN** the context starts
* **THEN** exactly one `SlaSweepScheduler` bean is present
* **AND** its sweep method carries `@Scheduled`, and reflection confirms `fixedDelayString` and
  `initialDelayString` resolve to the configured property placeholders rather than literals
* **AND** a second `@SpringBootTest` with `properties = "storeops.activities.sla.sweep.enabled=false"`
  starts successfully with **no** `SlaSweepScheduler` bean present

**Scenario 7: Configuration binds and rejects nonsense at startup**
* **GIVEN** the application defaults
* **WHEN** `SlaSweepProperties` is resolved from the context
* **THEN** `enabled` is `true`, `interval` is `PT15M` and `initialDelay` is `PT15M`
* **AND** constructing `SlaSweepProperties` directly with a zero or negative `interval` throws
  `ValidationError` carrying code `VALIDATION_FAILED`
* **AND** constructing it with a negative `initialDelay` throws `ValidationError` carrying code
  `VALIDATION_FAILED`
* **AND** a zero `initialDelay` is accepted

**Scenario 8: The existing suite is unaffected**
* **GIVEN** the seven `@SpringBootTest` classes now carrying
  `properties = "storeops.activities.sla.sweep.enabled=false"`
* **WHEN** `./mvnw clean test` runs
* **THEN** every previously passing test still passes, `GET /api/notifications` still returns exactly
  the one seeded `notification-001`, and no notification has appeared from a sweep

## Architectural Guardrails

* **No `alerts` import anywhere in `activities`.** `ModuleBoundaryTest` rule 3 fails the build on
  `activities` depending on `alerts`. The sweep publishes on the `EventBus` and stops caring who
  listens. If a test in this sprint needs to see a `Notification`, the test is in the wrong sprint.
* **`publishOverdueBreaches()` stays `@Transactional`.** Removing it is invisible in this sprint —
  every Sprint 3 test uses `RecordingEventBus`, which records at publish time — and silently breaks
  Sprint 4, because Spring skips `AFTER_COMMIT` callbacks when no transaction is active. Sprint 2's
  run log recorded exactly this trap.
* **New classes go in `service`, not a new package.** `activities` may contain only `routes`,
  `service`, `repository`, `domain`, `dto` and `listener`. There is no `scheduler`, `config` or
  `util` package and this sprint does not add one. `listener` is for event consumers; a clock-driven
  sweep is not one.
* **Validation throws `ValidationError`, never `IllegalArgumentException`.** `ModuleBoundaryTest`
  rules 6 and 6b require every thrown error to be an `AppError` subtype from `shared.error`. Do not
  add a new subtype — `ValidationError` with code `VALIDATION_FAILED` is the mapped answer.
* **`@ConfigurationPropertiesScan` on `StoreOpsApplication`, not `@EnableConfigurationProperties`.**
  The latter names `SlaSweepProperties` from the application root, creating a root-to-module type
  dependency for no gain.
* **`SlaSweepScheduler` calls `TaskService`, not `TaskRepository`.** Rule 1b restricts the repository
  layer to its own module's service layer, and rule 5 forbids skipping a layer. Duplicating the
  overdue filter in the scheduler would also put business logic outside the service layer.
