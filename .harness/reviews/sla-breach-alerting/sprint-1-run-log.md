# SLA Breach Alerting — Sprint 1 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 1 (feature: SLA breach alerting — 3 sprints planned) |
| Goal | The overdue sweep: a scheduled trigger in `activities` publishing `TaskOverdueEvent` for every HIGH or CRITICAL activity past its due date and not `DONE`, republished on every pass while it stays that way |
| Modules touched | `activities` (listener, service, repository). Root-package config (`StoreOpsApplication`, `application.yml`) and two root-package tests. No `alerts`, `reports`, `programmes`, `staff` or `shared` source changed |
| Final Verdict | **CONDITIONAL PASS** |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated Token Cost | ~30,800 tokens |

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none at evaluation — every automated and assessed gate passed on the first attempt | none required |

Gate detail: `BUILD SUCCESS`. JUnit `Tests run: 120, Failures: 0, Errors: 0, Skipped: 0`;
`ModuleBoundaryTest` 12/12; Checkstyle clean at `validate`; SpotBugs clean at `test-compile`;
`jacoco:check` passed including the new `activities.listener.OverdueSweepScheduler`. Test count moved
111 → 120, so the sprint added 9 tests.

Worth recording for the trend, though it is not an evaluation iteration: the Generator's own first two
build attempts failed inside `H2SchemaTest.seedDataLoaded` — `projects` 3 vs 2, then `project_members`
5 vs 4 — and were fixed before the Evaluator ran. Cause was pre-existing order fragility, not new code;
see Cleanups and Quality Trend Notes.

## Files Changed

Copied from `sprint-1-generator-summary.md`, grouped by module and layer.

### activities — source
- `listener/OverdueSweepScheduler.java` — new; `@Component` gated on
  `storeops.activities.sla.sweep.enabled` (`matchIfMissing = true`), one `@Scheduled` method
  (`fixedDelayString` `PT5M`, `initialDelayString` `PT10M`) delegating to the service and logging the count
- `service/TaskService.java` — `publishOverdueBreaches()` switched from `findAll()` to
  `findOpenPastDue(now)`, still `@Transactional`, still filtering `Task::isSlaTracked`; javadoc now
  records that the non-idempotent republication is the contract
- `repository/TaskRepository.java` — new `findOpenPastDue(Instant)`, documented as a data predicate that
  deliberately returns every priority band
- `repository/TaskJpaRepository.java` — new `@Query` on `dueAt < :moment AND status <> :terminalStatus`,
  terminal status passed as a parameter rather than written as an enum literal
- `repository/JpaTaskRepository.java` — adapter for the finder; null `moment` returns an empty list

### root — source and configuration
- `StoreOpsApplication.java` — `@EnableScheduling`
- `src/main/resources/application.yml` — new `storeops.activities.sla` block: `sweep-interval: PT5M`,
  `initial-delay: PT10M`, `sweep.enabled: true`, each commented

### activities — test
- `service/TaskServiceTest.java` — one overdue test replaced by three: full-payload filtering,
  not-yet-due, and repeat publication across three sweeps
- `repository/TaskRepositoryIntegrationTest.java` — new `@SpringBootTest`; the finder against the real
  H2 schema, including null `dueAt` and null `moment`
- `listener/OverdueSweepSchedulerTest.java` — new; plain JUnit over a mocked `TaskService`, delegation
  pinned with `verifyNoMoreInteractions`
- `listener/OverdueSweepSchedulerWiringTest.java` — new `@SpringBootTest`; bean present by default
- `listener/OverdueSweepSchedulerDisabledTest.java` — new `@SpringBootTest` + `@TestPropertySource`;
  bean absent when the property is `false`

### root — test
- `EventDeliveryIntegrationTest.java` — added `sweptOverdueBreachReachesAlertsModule`, asserting by
  `sourceRef` because the H2 database is shared
- `support/FakeTaskRepository.java` — implements the new finder with the same predicate as the query
- `H2SchemaTest.java` — `seedDataLoaded` seed counts scoped to seed ids; see Cleanups

Scope verified with `git status --porcelain`: every modified and added path appears in
`sprint-1-generator-summary.md`, nothing undeclared.

## Conditional Pass Cleanups

Non-behavioural debts carried from `sprint-1-evaluator-feedback.md`. Neither affects an acceptance
criterion or a gate.

1. `activities/repository/TaskJpaRepository.java:52` — "overdue" is now defined twice: in SQL for the
   sweep, and in Java via `Task.isOverdueAt(moment)` for `reports.service.ReportService:70`. They agree
   today and nothing fails if they stop agreeing. Cheapest fix: a test asserting both return the same
   ids for the seed data. **Carry deliberately** — Sprint 2 does not re-open this file, so it will be
   lost otherwise.
2. `activities/listener/OverdueSweepScheduler.java:41` — logs at `INFO` on every sweep including empty
   ones (288 lines a day on a quiet store). Log `DEBUG` when the count is zero, matching
   `AlertEventListener`'s existing pattern for its uninteresting branch.

Reviewed and accepted rather than logged as debt: `task-002` substituted in AC 4's assertion because
`ApiSmokeTest` mutates that seed row; AC 8 proved by the green suite rather than a dedicated method.

## Quality Trend Notes

Second logged sprint overall, first of this feature. Comparison is against the bulk-update sprint
recorded in `.harness/reviews/sprint-1-run-log.md`.

| Metric | Bulk update | This sprint | Direction |
| --- | --- | --- | --- |
| Iterations | 1 of 3 | 1 of 3 | flat, baseline held |
| Automated gate failures at evaluation | 0 | 0 | flat |
| Test count | 96 → 111 (+15) | 111 → 120 (+9) | growing |
| Score | 96 (`A 40 · B 33 · C 23`) | 93 (`A 38 · B 33 · C 22`) | −3 |
| Verdict | CONDITIONAL PASS | CONDITIONAL PASS | flat |

Patterns:

* **The contract keeps producing the findings, not the implementation — now twice.** The previous log
  flagged this as "watch for a second occurrence"; this is it. Cleanup 1 exists because
  `sprint-1-contract.md` mandated a SQL predicate duplicating a domain method without asking for a test
  tying them together, and AC 4 named a seed row that another test mutates. Both are Planner-side
  defects: criteria that a Generator can satisfy literally while leaving a gap. **This is now a trend
  and belongs in the Planner's criteria-writing standard**, specifically: before naming a seed id in a
  criterion, check no integration test mutates it; and when a rule is expressed in two places, require
  a criterion that pins them together.
* **Shared-H2 order fragility is a live hazard for every future sprint.** Adding one
  default-context `@SpringBootTest` class was enough to break `H2SchemaTest.seedDataLoaded` twice, in a
  test that predates this sprint and asserts nothing about it. Two of the five counts there were already
  written as floors for exactly this reason. Any sprint that adds a `@SpringBootTest` should expect to
  pay this tax; Sprints 2 and 3 both add integration tests and Sprint 2 adds a table.
* **`activities` remains the only module with real quality signal.** Two sprints, both in it.
  `alerts`, `staff`, `programmes` and `reports` are still baseline-only. Sprint 2 finally exercises
  `alerts` and `staff`.
* **Harness defect, flagged for the human, not fixed here — archive naming has no feature dimension.**
  The Monitor's specified scheme (`.harness/reviews/sprint-[N]-*.md`) collides across features: this
  feature's Sprint 1 would have overwritten all four bulk-update artifacts, destroying that audit trail.
  This sprint's artifacts are therefore archived under `.harness/reviews/sla-breach-alerting/` and the
  bulk-update set left untouched. Sprints 2 and 3 of this feature must use the same subdirectory. The
  scheme needs a feature dimension before a third feature runs.
* **The `spec.md` state-marker defect from the previous log is unresolved and now confirmed harmful.**
  `.harness/output/spec.md` was overwritten by this feature's spec, so the approved bulk-update spec
  survives only in git history — its contract was archived but its spec was not. Archiving `spec.md`
  alongside the sprint artifacts would fix it; the Monitor's remit does not currently include that.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts read and written (`CLAUDE.md`, 4 agent definitions, 7 skill files, `spec.md`, the three sprint contracts, `generator-summary.md`, `evaluator-feedback.md`) | 12,443 |
| Declared source and test files written | 5,523 |
| Context-only files read, not changed (`activities/domain/*`, `alerts/*`, `staff/*`, `TaskOverdueEvent`, `ValidationError`, `data.sql`, `ModuleBoundaryTest`, `support/*`, `AlertEventListenerTest`, `ApiSmokeTest`, `pom.xml`, `checkstyle.xml`) | 5,762 |
| **Total** | **23,728** |

`23,728 * 1.3 * 1 = 30,846` → **~30,800 tokens**.

The harness group carries all three sprint contracts because planning produced them in this session;
Sprints 2 and 3 should not count them again. Excluded from the basis: Maven build output and surefire
reports, which are machine-generated rather than files read as sprint context.
