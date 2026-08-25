# Generator Summary — Sprint 3: Scheduled Overdue Detection

`mvn clean test` exit 0. JUnit 154/154 (134 at Sprint 2 close, +20), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo bundle and per-class gates passed.

## 1. AC Self-Check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Sweep publishes one fully populated event per SLA-tracked overdue activity | yes | `TaskServiceTest.publishOverdueBreachesPopulatesTheEventFromTheSeedData` |
| 2 | Sweep ignores MEDIUM, DONE and no-due-date activities, asserted individually | yes | `TaskServiceTest.publishOverdueBreachesSkipsEveryNonBreach` |
| 3 | An activity due in the future is not a breach | yes | `TaskServiceTest.publishOverdueBreachesIgnoresActivitiesNotYetDue` |
| 4 | Sweep re-publishes on every cycle | yes | `TaskServiceTest.publishOverdueBreachesRepublishesOnEveryCycle` |
| 5 | Scheduler delegates to the service exactly once, tolerates a zero result | yes | `SlaSweepSchedulerTest.sweepDelegatesToTheService`, `SlaSweepSchedulerTest.sweepToleratesAnEmptyResult` |
| 6 | Bean present when enabled, absent when disabled, `@Scheduled` reads placeholders | yes | `SlaSweepWiringTest.schedulerIsRegisteredWhenEnabled`, `SlaSweepWiringTest.schedulerIsAbsentWhenDisabled`, `SlaSweepWiringTest.sweepMethodIsScheduledFromProperties`, `SlaSweepWiringTest.ShippedDefaults.schedulerBeanIsRegistered` |
| 7 | Defaults bind to `true`/`PT15M`/`PT15M`; zero or negative interval and negative initial delay rejected as `VALIDATION_FAILED`; zero initial delay accepted | yes | `SlaSweepWiringTest.ShippedDefaults.configurationBindsTheShippedDefaults`, `SlaSweepPropertiesTest.rejectsAZeroInterval`, `SlaSweepPropertiesTest.rejectsANegativeInterval`, `SlaSweepPropertiesTest.rejectsANegativeInitialDelay`, `SlaSweepPropertiesTest.acceptsAZeroInitialDelay` |
| 8 | Existing suite unaffected; no notification appears from a sweep | yes | full-suite run (154/154); `ApiSmokeTest`, `NotificationRoutesTest` and `H2SchemaTest.seedDataLoaded` all pass — **but see Deviation 3, one existing test was modified** |

Three assertions in AC 7 were not in the contract and were added because the record accepts them:
`SlaSweepPropertiesTest.rejectsAMissingInterval` and `rejectsAMissingInitialDelay` cover null binding
(an absent yml key binds null, not a default), and `acceptsAValidConfiguration` covers the accessors.

## 2. Files Changed

### activities — service
- `service/SlaSweepProperties.java` — **new**. `@ConfigurationProperties("storeops.activities.sla.sweep")`
  record: `enabled`, `interval`, `initialDelay`. Compact constructor rejects a null/zero/negative
  `interval` and a null/negative `initialDelay` with `ValidationError` / `VALIDATION_FAILED`.
- `service/SlaSweepScheduler.java` — **new**. `@Component` guarded by `@ConditionalOnProperty`
  (`matchIfMissing = true`), one `@Scheduled` method reading `fixedDelayString` and
  `initialDelayString` from the two duration properties, delegating to
  `TaskService.publishOverdueBreaches()` and logging the count.
- `service/TaskService.java` — **javadoc only**. Removed "Stub: nothing schedules this yet"; recorded
  why the method is deliberately stateless and what removing `@Transactional` costs. No logic or
  signature change.

### root and configuration
- `StoreOpsApplication.java` — added `@EnableScheduling` and `@ConfigurationPropertiesScan`.
  Used the scan, not `@EnableConfigurationProperties(SlaSweepProperties.class)`, so the application
  root names no module type.
- `src/main/resources/application.yml` — added `storeops.activities.sla.sweep.enabled: true`,
  `interval: PT15M`, `initial-delay: PT15M`.

### tests — new
- `activities/service/SlaSweepPropertiesTest.java` — 7 tests. Configuration validation.
- `activities/service/SlaSweepSchedulerTest.java` — 3 tests. Delegation only; `TaskService` mocked.
- `activities/service/SlaSweepWiringTest.java` — 6 tests. Four `ApplicationContextRunner` cases for
  the conditional plus `@Scheduled` reflection; one `@Nested @SpringBootTest` (`ShippedDefaults`) for
  the real application.

### tests — modified
- `activities/service/TaskServiceTest.java` — 4 tests added (13 → 17) plus a
  `seedTheFourSeedActivities()` helper reproducing `data.sql` exactly.
- `ApiSmokeTest`, `BulkStatusEventDeliveryIntegrationTest`, `BulkStatusSubscriberIsolationTest`,
  `BulkStatusUpdateIntegrationTest`, `EventDeliveryIntegrationTest`, `H2SchemaTest`,
  `StoreOpsApplicationTests` — all seven given
  `@SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")`. Identical property
  string in all seven, so they still share one cached context rather than building seven.
- `H2SchemaTest.java` — `seedDataLoaded` rewritten. **Not a contract deliverable; see Deviation 3.**

### untouched
`TaskOverdueEvent`, `AlertEventListener`, `NotificationService`, `EventBusConfiguration`,
`TaskRepository`, `TaskRoutes`, `data.sql`, `pom.xml`. No new event, no new `AppError` subtype, no
schema change, no new endpoint, no `alerts` import anywhere in `activities`.

## 3. Deviations From The Contract

**1. Fixed-clock instant.** The contract specifies `2026-02-01T00:00:00Z` in Scenarios 1 and 3;
`how-to-test` §2 says "always use a fixed Clock pinned to `2026-02-01T10:00:00Z`", which is what
`TaskServiceTest` already uses. Used `10:00:00Z`. Both are past every seed due date, so no assertion
changes meaning, and `occurredAt` is asserted against the same constant.

**2. Scenario 6 test mechanism.** The contract asks for two `@SpringBootTest` classes, one per value
of the enabled flag. Implemented as four `ApplicationContextRunner` cases plus a single
`@SpringBootTest` for the shipped defaults. Reason: each distinct `@SpringBootTest` property set adds
a cached Spring context against a JVM-wide H2 database, and context build order is exactly what
broke `H2SchemaTest` below. The runner evaluates `@ConditionalOnProperty` for real —
`SlaSweepScheduler` is registered as a component class, not via a `@Bean` method, because a `@Bean`
method would bypass the class-level condition and assert nothing. Coverage of the criterion is the
same or better; the context count is one instead of two.

**3. `H2SchemaTest.seedDataLoaded` was modified, and Scenario 8 said not to need to.** Declaring this
plainly: the first full run failed on `H2SchemaTest.seedDataLoaded:71`, `expected: 2 but was: 3`.

The cause is not the sweep. `ApiSmokeTest:94` performs a real `POST /api/projects` and never removes
the row; the H2 database is JVM-wide (`DB_CLOSE_DELAY=-1`) while Spring caches one context per
distinct test configuration. A class whose context was built before that insert sees three
programmes. Which classes are affected is a function of how many contexts exist and in what order
they are built — adding one context re-ordered it. The same method already used
`isGreaterThanOrEqualTo` for `tasks` and `notifications` and exact equality for `projects` and
`project_members`, so the hazard was known and half-mitigated.

The fix counts the seed rows by id (`WHERE id IN ('project-001', 'project-002')`) instead of totalling
the table, in all five assertions. That is what the method's own `@DisplayName` claims to test —
"data.sql populated every table it names" — and it is order-independent, so it also tightened `tasks`
and `notifications` from `>=` back to exact equality.

I did not weaken an assertion to make my change pass: the pre-existing assertion was order-dependent
and would have failed on any future change that added a context. Flagging it for the Evaluator rather
than burying it, because "the Generator edited an existing test to go green" is the right thing to be
suspicious of.

**4. `./mvnw` does not exist in this repository.** `CLAUDE.md`, `app-context` §1, `how-to-test` §3 and
the generator agent file all specify `./mvnw clean test`. There is no Maven wrapper checked in and no
`.mvn/` directory. Used `mvn clean test` against Maven 3.9.14 on `PATH`. Every gate is bound to a
lifecycle phase in `pom.xml`, so the single command still runs Checkstyle, SpotBugs, ArchUnit, JUnit
and JaCoCo. Routing to the Planner as a harness/repo discrepancy, not a code defect.

## 4. Known Gaps

**In scope and complete.** No contract criterion was dropped.

Deliberately out of scope, carried to the named sprint:

1. **The `SLA_BREACH` alert still goes to the assignee, not the Department Lead.** `AlertEventListener`
   is untouched, so a sweep raises the stub's alert to `event.assigneeId()`. Sprint 4.
2. **No de-duplication anywhere yet.** The sweep re-publishes every cycle by design and nothing
   suppresses the repeats, so a long-running instance accumulates one notification per 15 minutes for
   `task-001`. Sprint 4 closes this; it is the reason Sprint 4 must not be skipped.
3. **No grace period, no escalation to `STORE_MANAGER`.** Sprint 5.
4. **The after-commit path is unproven for the sweep.** Every Sprint 3 test uses `RecordingEventBus`,
   which records at publish time, so `@Transactional` on `publishOverdueBreaches()` could be deleted
   today with the suite still green. Sprint 4 Scenario 11 requires exactly that mutation check.
   Untested here, and stated so the Evaluator does not read the green build as covering it.
