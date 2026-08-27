# Sprint 3 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 3 (first of three in the SLA breach alerting feature) |
| Goal | Give `TaskService.publishOverdueBreaches()` a scheduled caller and a configuration surface, so every SLA-tracked activity past its due date publishes a `TaskOverdueEvent` on every sweep |
| Modules touched | `activities` (service only); application root and `application.yml` for scheduling and property registration |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~29.6k |

`mvn clean test` exit 0. JUnit 154/154 (134 at Sprint 2 close, +20), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo bundle and per-class gates met. Both new classes landed at 100%
line and 100% branch coverage.

First sprint of a new feature. Sprint numbering continues from the shift handover feature rather than
restarting, so this archive does not overwrite `sprint-1-*` or `sprint-2-*`.

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none at review time — five automated gates and five LLM-assessed hard gates passed on the first attempt. One build failure occurred *during* generation (`H2SchemaTest.seedDataLoaded:71`, `expected: 2 but was: 3`) and was resolved before the Evaluator ran | Seed-row assertions in `H2SchemaTest` re-keyed from table totals to seed ids, removing a dependence on Spring context build order. One cleanup left outstanding |

## Files Changed

### activities — service
- `service/SlaSweepProperties.java` — **new**. `@ConfigurationProperties("storeops.activities.sla.sweep")`
  record: `enabled`, `interval`, `initialDelay`. Compact constructor rejects a null, zero or negative
  `interval` and a null or negative `initialDelay` with `ValidationError` / `VALIDATION_FAILED`.
- `service/SlaSweepScheduler.java` — **new**. `@Component` guarded by `@ConditionalOnProperty`
  (`matchIfMissing = true`), one `@Scheduled` method taking `fixedDelayString` and
  `initialDelayString` from the duration properties, delegating to `TaskService` and logging the count.
- `service/TaskService.java` — **javadoc only**. "Stub: nothing schedules this yet" removed; the
  method's deliberate statelessness and the cost of dropping `@Transactional` recorded. No logic or
  signature change.

### root and configuration
- `StoreOpsApplication.java` — `@EnableScheduling` and `@ConfigurationPropertiesScan` added. The scan
  rather than `@EnableConfigurationProperties(SlaSweepProperties.class)`, so the application root
  names no module type.
- `src/main/resources/application.yml` — three `storeops.activities.sla.sweep` keys: `enabled: true`,
  `interval: PT15M`, `initial-delay: PT15M`.

### tests — new
- `activities/service/SlaSweepPropertiesTest.java` — 7 tests, configuration validation.
- `activities/service/SlaSweepSchedulerTest.java` — 3 tests, delegation only.
- `activities/service/SlaSweepWiringTest.java` — 6 tests. Four `ApplicationContextRunner` cases for
  the conditional plus `@Scheduled` reflection; one `@Nested @SpringBootTest` for the shipped defaults.

### tests — modified
- `activities/service/TaskServiceTest.java` — 4 tests added (13 → 17), plus a
  `seedTheFourSeedActivities()` helper reproducing `data.sql` exactly.
- `ApiSmokeTest`, `BulkStatusEventDeliveryIntegrationTest`, `BulkStatusSubscriberIsolationTest`,
  `BulkStatusUpdateIntegrationTest`, `EventDeliveryIntegrationTest`, `H2SchemaTest`,
  `StoreOpsApplicationTests` — all seven given
  `@SpringBootTest(properties = "storeops.activities.sla.sweep.enabled=false")`, identical string in
  each so they still share one cached context.
- `H2SchemaTest.java` — `seedDataLoaded` re-keyed to seed ids. Not a contract deliverable; declared
  by the Generator and accepted by the Evaluator after independent verification.

### untouched
`TaskOverdueEvent`, `AlertEventListener`, `NotificationService`, `EventBusConfiguration`,
`TaskRepository`, `TaskRoutes`, `data.sql`, `pom.xml`. No new event, no new `AppError` subtype, no
schema change, no new endpoint, and no `alerts` import anywhere in `activities`.

## Conditional Pass Cleanups

One item, **outstanding**. Sprint 4 runs next and should collect it.

1. **`ApiSmokeTest:94` — a test inserts a programme into the shared database and never removes it.**
   This is the root cause behind the `H2SchemaTest` change above, and it is still present. The H2
   instance is JVM-wide (`DB_CLOSE_DELAY=-1`) while Spring caches one context per distinct test
   configuration, so the row survives into any class sharing that context. `H2SchemaTest` no longer
   counts table totals and is now immune, but the next assertion anywhere in the suite that counts a
   mutable table will break the same way. Fix: give `ApiSmokeTest` the
   `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` its three `BulkStatus*` siblings already carry, or have
   the test delete what it created. One line, test-only, no behavioural risk.

Carried scope, not a cleanup and not debt: the after-commit delivery of a sweep-published
`TaskOverdueEvent` is live in production but unasserted, because every test observing it uses
`RecordingEventBus`. The contract placed that proof in Sprint 4 Scenarios 10 and 11 deliberately, per
`sprint-decomposition` §2.

## Quality Trend Notes

Third entry, so a three-point trend is visible against `sprint-1-run-log.md` and
`sprint-2-run-log.md`. The first two belong to the shift handover feature; this is the first of a new
one, so a like-for-like comparison should be read with that break in mind.

* **Iteration count flat at 1 of 3 for the third consecutive sprint.** The escalation budget has
  never been touched across two features. No creep.
* **Score moved 92 → 98 → 96, and the dip is not a code-quality regression.** Of Sprint 3's three
  findings, one is a contract-versus-skill inconsistency routed to the Planner, one is carried scope
  the contract itself deferred, and only one is an outstanding cleanup. Finding *count* rose 1 → 3
  while behavioural defects found in production code fell to **zero** — the first sprint in the
  harness's history where the Evaluator found no defect in the code the Generator wrote.
* **A new defect species appeared: test-infrastructure fragility, not unexercised production paths.**
  Sprints 1 and 2 both found defects in paths no test reached. Sprint 3's only build failure came
  from an *existing* test whose assertion was a function of Spring context build order against a
  shared mutable database — nothing to do with the code under change. Adding one cached context was
  enough to flip it. Sprints 4 and 5 both add `@SpringBootTest` classes and Sprint 5 adds one with a
  distinct property set, so expect this to bite again; budget for it rather than treating it as a
  surprise.
* **The pattern from both prior sprints held a third time: gates green, findings only from judgement.**
  Every automated gate passed first time, again. Three sprints in, the harness's value has come
  entirely from the Evaluator's manual gates and probes, never from the build catching something.
  Three data points is a trend: budget review time accordingly and do not read a green build as a
  finished sprint.
* **Mutation probing is now established practice and earned its keep again.** Sprint 2's log
  recommended re-verifying a carried finding with the check that first caught it. Sprint 3 extended
  that to a *changed test mechanism*: the Generator substituted `ApplicationContextRunner` for the
  contract's `@SpringBootTest`, so the Evaluator deleted `@ConditionalOnProperty` and confirmed
  exactly one test failed, and the right one. Keep doing this whenever a Generator deviates on how a
  criterion is proven, not just on what.
* **`activities` remains the only module whose production code has ever been modified.** Two features,
  three sprints, and `alerts` has still never been edited — only exercised. Sprint 4 changes that: it
  is the first `alerts` production change in the harness's history, and the first time the
  cross-module read rules (`alerts` → `staff.service`, never `staff.repository`) will be genuinely
  exercised rather than trivially satisfied. Expect ArchUnit rules 1, 2 and 5 to matter for real.
* **Second data point on the harness's own prose drifting from the repo.** Sprint 2 flagged a
  `FailingSubscriber` javadoc claiming it was not component-scanned when it was. Sprint 3 found that
  `./mvnw clean test` — the command named in `CLAUDE.md` and five skill and agent files — cannot be
  run, because no Maven wrapper is checked in. Both are harmless in isolation and both cost an agent
  a beat of confusion. Two occurrences is enough to justify a documentation audit before the next
  feature, not another ticket.
* **Prediction for Sprint 4, stated so it can be settled rather than forgotten.** `@Transactional` can
  be deleted from `TaskService.publishOverdueBreaches()` today with all 154 tests still green. This is
  the same shape as Sprint 1's prediction about Sprint 2, which mutation testing later confirmed
  exactly. Sprint 4 Scenario 11 is written to settle it; if it is skipped or waved through, the
  feature ships with its entire alerting path one annotation away from silent failure.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts — `spec.md`, `sprint-3-contract.md`, `generator-summary.md`, `evaluator-feedback.md`, `sprint-2-run-log.md`, 4 agent files, 7 skill files, `CLAUDE.md` | 11,522 |
| Planning overhead — `sprint-4-contract.md` and `sprint-5-contract.md`, written in the same Planner pass and counted once, here | 2,820 |
| Source written — `SlaSweepProperties`, `SlaSweepScheduler`, `TaskService`, `StoreOpsApplication`, `application.yml` | 1,743 |
| Tests written — 3 new `SlaSweep*` classes, `TaskServiceTest`, `H2SchemaTest` | 2,305 |
| Source read for context — 12 production classes, `data.sql`, `pom.xml`, `checkstyle.xml`, `ModuleBoundaryTest`, 2 support fixtures | 4,415 |
| **Total words** | **22,805** |

`22,805 * 1.3 * 1 = 29,647` → **~29.6k tokens**

Higher than either shift handover sprint (19.1k, 20.4k) for two reasons, both one-off: this sprint
carries the whole feature's planning overhead, and it read more of `src/` than a mid-feature sprint
needs because the SLA seams had to be located before anything could be planned. Sprints 4 and 5
should come in below this. Excludes Maven output, which was filtered rather than read, and the two
verification runs (the mutation probe and the post-restore full build).
