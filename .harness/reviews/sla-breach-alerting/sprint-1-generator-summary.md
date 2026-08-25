# Generator Summary — Sprint 1: The Overdue Sweep

`./mvnw clean test` — **BUILD SUCCESS**, 120 tests, 0 failures, 0 errors. Checkstyle (validate),
SpotBugs (test-compile) and `jacoco:check` all ran and passed.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Sweep publishes only for SLA-tracked, overdue, unfinished activities, with the full payload | yes | `TaskServiceTest.publishOverdueBreachesFiltersByPriorityAndStatus` |
| 2 | An activity not yet past due publishes nothing | yes | `TaskServiceTest.publishOverdueBreachesIgnoresActivitiesNotYetDue` |
| 3 | A still-overdue activity is republished on every sweep, with no dedup state in activities | yes | `TaskServiceTest.publishOverdueBreachesRepublishesWhileStillOverdue` |
| 4 | `findOpenPastDue` applies the predicate in the database, returning domain records | yes | `TaskRepositoryIntegrationTest.findOpenPastDueAppliesThePredicateInTheDatabase`, `.findOpenPastDueRejectsANullMoment` |
| 5 | The scheduler delegates once and decides nothing | yes | `OverdueSweepSchedulerTest.sweepDelegatesToTheService`, `.sweepWithNothingOverdueIsSilent` |
| 6 | The sweep can be switched off, and is on by default | yes | `OverdueSweepSchedulerDisabledTest.schedulerIsAbsentWhenDisabled`, `OverdueSweepSchedulerWiringTest.schedulerIsPresentByDefault` |
| 7 | A swept event reaches its after-commit subscriber | yes | `EventDeliveryIntegrationTest.sweptOverdueBreachReachesAlertsModule` |
| 8 | The timer does not fire during the test suite; no notification counts disturbed | yes | The full `./mvnw clean test` run: `ApiSmokeTest` (11), `NotificationRoutesTest` (3), `EventDeliveryIntegrationTest` (8), `ReportRoutesTest` (2) all pass unmodified except for AC7's added test |

### Two declared deviations from the contract's literal wording

**AC 4 — `task-002` is not asserted.** The contract asked for `findOpenPastDue` to return seed
`task-001` and `task-002`. `ApiSmokeTest.updateTask` moves seed `task-002` to `DONE` in the shared H2
database, so asserting it would make the test pass or fail on class execution order. The criterion's
purpose — proving the query does not filter on priority band — is met instead by a self-created
`finder-open-medium` row (MEDIUM, `IN_PROGRESS`, past due) asserted present alongside seed `task-001`,
which no integration test mutates. `finder-done`, `finder-no-due-date` and `finder-not-yet-due` cover
the exclusions deterministically.

**`H2SchemaTest.seedDataLoaded` was edited, and was not in the deliverable list.** Adding a
default-context `@SpringBootTest` class changed which context is built when, and the `projects` and
`project_members` counts in that test were reached after `EventDeliveryIntegrationTest` had created a
programme and its owner membership row — 3 and 5 instead of 2 and 4. Every `@SpringBootTest` shares
`jdbc:h2:mem:storeops` and the seed reloads only on context creation, so those two wholesale counts were
order-dependent already; `tasks` and `notifications` in the same test use `isGreaterThanOrEqualTo` for
exactly this reason. Both assertions are now scoped to the seed ids
(`WHERE id IN ('project-001', 'project-002')`), which keeps them exact rather than loosening them to a
floor. `users` stays an exact count — `UserService` exposes no mutator, so nothing can add one.

## 2. Files changed

### activities
- `listener/OverdueSweepScheduler.java` — **new.** `@Component`, gated on
  `storeops.activities.sla.sweep.enabled` (`matchIfMissing = true`); one `@Scheduled` method calling
  `taskService.publishOverdueBreaches()` and logging the count. `fixedDelayString` `PT5M`,
  `initialDelayString` `PT10M`, both property-overridable
- `service/TaskService.java` — `publishOverdueBreaches()` now calls `findOpenPastDue(now)` instead of
  `findAll()`, still filters `Task::isSlaTracked`, still `@Transactional`; javadoc rewritten to record
  that the non-idempotent republication is the contract
- `repository/TaskRepository.java` — new `List<Task> findOpenPastDue(Instant moment)`, documented as a
  data predicate that deliberately returns every priority band
- `repository/TaskJpaRepository.java` — new `@Query` on `dueAt < :moment AND status <> :terminalStatus`;
  the terminal status is a parameter, not an enum literal in the query text
- `repository/JpaTaskRepository.java` — adapter for the finder, passing `TaskStatus.DONE` and
  `DEFAULT_SORT`; null `moment` returns an empty list, matching the null handling of its siblings

### root / configuration
- `StoreOpsApplication.java` — `@EnableScheduling`, with a javadoc note that the sweep is switchable
  per environment
- `src/main/resources/application.yml` — new `storeops.activities.sla` block: `sweep-interval: PT5M`,
  `initial-delay: PT10M`, `sweep.enabled: true`, each commented, including why the initial delay is long

### tests
- `activities/service/TaskServiceTest.java` — the single `publishOverdueBreachesFiltersByPriority` test
  replaced by three: full-payload filtering, not-yet-due, and repeat publication over three sweeps
- `activities/repository/TaskRepositoryIntegrationTest.java` — **new.** `@SpringBootTest`; the finder
  against the real H2 schema, including the null-`dueAt` and null-`moment` cases
- `activities/listener/OverdueSweepSchedulerTest.java` — **new.** Plain JUnit over a mocked
  `TaskService`; delegation plus `verifyNoMoreInteractions`
- `activities/listener/OverdueSweepSchedulerWiringTest.java` — **new.** `@SpringBootTest`; the bean is
  registered by default
- `activities/listener/OverdueSweepSchedulerDisabledTest.java` — **new.** `@SpringBootTest` with
  `@TestPropertySource`; the bean is absent when the property is `false`
- `EventDeliveryIntegrationTest.java` — added `sweptOverdueBreachReachesAlertsModule`, asserting by
  `sourceRef` rather than by count because the H2 database is shared
- `support/FakeTaskRepository.java` — implements `findOpenPastDue` with the same predicate as the query
- `H2SchemaTest.java` — see the declared deviation above

No file in `alerts`, `reports`, `programmes`, `staff` or `shared` was touched. No new event type, no new
`AppError` subtype, no new table, no route.

## 3. Known gaps

- **AC 8 is proved by the suite passing, not by a dedicated test method.** The criterion is written as a
  property of the whole run ("WHEN the full `./mvnw clean test` runs"), and a test that asserted no
  timer had fired would have to sleep to mean anything. The mechanism it depends on — the `PT10M`
  initial delay and the `enabled` flag — is covered directly by AC 6.
- **The scheduler's `@Scheduled` metadata is not itself asserted.** Nothing verifies that Spring parses
  `PT5M`/`PT10M` into the intended delays; a test proving it would have to wait out a real interval. The
  values are exercised only in that context startup fails if either string is unparseable, which every
  `@SpringBootTest` here would catch.
- **`storeops.activities.sla.sweep-interval` is never overridden in a test**, so only its default path
  is covered.
- Sprint 2 scope, deliberately absent: no de-duplication, so the repeat events currently produce a
  repeat `SLA_BREACH` alert to the **assignee** on every sweep via the existing stub
  `AlertEventListener.onTaskOverdue`. That is Sprint 2's subject and is not a regression introduced
  here — it is the pre-existing listener behaviour, now reachable on a timer.
