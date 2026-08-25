# SLA Breach Alerting — Sprint 3 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 3 of 3 — **final sprint of the feature** |
| Goal | Grace period escalation and episode closure: a configurable grace period, one `ESCALATION` to the store's `STORE_MANAGER` when a breach persists, and closure of the episode when the activity reaches `DONE` |
| Modules touched | `alerts` (domain, repository, service, listener). Root-package config (`StoreOpsApplication`, `application.yml`). No `activities`, `programmes`, `staff` or `reports` source changed |
| Final Verdict | **CONDITIONAL PASS** |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated Token Cost | ~9,900 tokens |

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none at evaluation — every automated and assessed gate passed on the first attempt | none required |

Gate detail: `BUILD SUCCESS`. JUnit `Tests run: 152, Failures: 0, Errors: 0, Skipped: 0`;
`ModuleBoundaryTest` 12/12; Checkstyle and SpotBugs clean; `jacoco:check` passed with `SlaBreachService`
carrying two new branch-heavy methods. Test count 134 → 152, so the sprint added 18 tests.

One build failure during generation, fixed before evaluation:
`SlaEscalationIntegrationTest.unresolvedBreachEscalatesToTheStoreManagerExactlyOnce` was written against
seed `task-001` and failed because `publishOverdueBreaches()` sweeps globally — the sibling test in the
same class had already escalated that activity. Fixed by giving the test its own activity, not by
weakening the assertion.

## Files Changed

Copied from `sprint-3-generator-summary.md`, grouped by module and layer.

### alerts — source
- `service/SlaEscalationProperties.java` — new `@ConfigurationProperties("storeops.alerts.sla")` record;
  compact constructor substitutes `PT2H` for a null or negative grace period
- `service/SlaBreachService.java` — properties injected; new `reobserve` (escalate once the grace period
  has elapsed, note the sighting otherwise, never escalate a first observation), new `escalate` (resolve
  the store manager, suppress a duplicate alert to the lead, leave `escalatedAt` null when no manager
  exists), new `closeEpisode`; `@Transactional` on both public methods
- `domain/SlaBreach.java` — `escalationRecipientId` and `escalatedAt` added, plus `withEscalation`,
  `isEscalated` and `escalationDueAt(Duration)` so the grace arithmetic sits on the record
- `repository/SlaBreachEntity.java` — the two new nullable columns
- `repository/SlaBreachRepository.java` — `deleteByTaskId` added, `findAll` removed
- `repository/JpaSlaBreachRepository.java` — `deleteByTaskId` added, `findAll` removed
- `repository/SlaBreachJpaRepository.java` — `DEFAULT_SORT` removed; now a bare `JpaRepository`
- `listener/AlertEventListener.java` — `onTaskStatusChanged` gained a `DONE` branch calling
  `closeEpisode`; `BLOCKED` branch and both transaction annotations unchanged

### root — configuration
- `StoreOpsApplication.java` — `@ConfigurationPropertiesScan`
- `src/main/resources/application.yml` — `storeops.alerts.sla.grace-period: PT2H`, commented

### tests
- `alerts/service/SlaBreachServiceTest.java` — 9 tests added (7 escalation, 2 closure)
- `alerts/service/SlaEscalationPropertiesTest.java` — new, 3 tests on the defaulting rules
- `alerts/service/SlaEscalationPropertiesBindingTest.java` — new `@SpringBootTest`; shipped config binds `PT2H`
- `alerts/SlaEscalationIntegrationTest.java` — new `@SpringBootTest @AutoConfigureMockMvc` with
  `grace-period=PT0S`; the override binding, the full escalation chain, and closure through `PATCH`
- `alerts/listener/AlertEventListenerTest.java` — 2 tests for the `DONE` and `BLOCKED` branches
- `H2SchemaTest.java` — the two new columns
- `support/FakeSlaBreachRepository.java` — `deleteByTaskId`

Scope verified with `git status --porcelain`: nothing undeclared.

## Conditional Pass Cleanups

**Closed this sprint** — both carried from `sprint-2-evaluator-feedback.md`:

* ~~`SlaBreachRepository.findAll()` unused~~ — removed, along with the `DEFAULT_SORT` constant it was the
  only reader of.
* ~~`SlaBreachService.observe` depended on its caller for atomicity~~ — `@Transactional` added to
  `observe` and `closeEpisode`.

**Opened this sprint**, from `sprint-3-evaluator-feedback.md`:

1. `alerts/service/SlaBreachService.java:141` — a suppressed escalation still writes
   `escalationRecipientId`, so the row names a recipient who received no notification. Contract-mandated
   (Sprint 3 Scenario 5 asserts the value), so the fix is a Planner decision: null the recipient when the
   alert is suppressed, or add a flag separating "notified" from "suppressed as duplicate".
2. `alerts/service/SlaBreachService.java:151` — the escalation body renders the grace period as `PT2H`
   via `Duration.toString()`, in an operator-facing message that is otherwise plain English.

**Still open, with no sprint left in this feature to spend on them:**

3. `activities/repository/TaskJpaRepository.java:52` — "overdue" defined in SQL for the sweep and in Java
   for `reports`, with no test tying them together. *(Sprint 1)*
4. `activities/listener/OverdueSweepScheduler.java:41` — `INFO` on every empty sweep. *(Sprint 1)*
5. `lastSeenAt` settles no decision but costs a write per open breach per sweep, now including the
   escalated path. *(Sprint 2, unanswered)*

## Quality Trend Notes

Fourth logged sprint, third and last of this feature.

| Metric | Bulk update | SLA 1 | SLA 2 | SLA 3 | Direction |
| --- | --- | --- | --- | --- | --- |
| Iterations | 1 of 3 | 1 of 3 | 1 of 3 | 1 of 3 | flat — four for four, no retry ever consumed |
| Gate failures at evaluation | 0 | 0 | 0 | 0 | flat |
| Test count | 96 → 111 | → 120 | → 134 | → 152 | +56 across the feature |
| Score | 96 | 93 | 97 | 97 | stable |
| Findings raised | 4 | 2 | 2 | 2 | flat |
| Findings closed | 0 | 0 | 0 | 2 | first closures |

Patterns:

* **The contract, not the implementation, produces most findings — four of the six across this feature,
  and the trend is now three sprints old.** Sprint 3's two both trace to contract wording: Scenario 5
  mandated the misleading `escalationRecipientId`, and Scenario 10 named a seed activity that a sibling
  test mutates. The Generator implemented both exactly as written and was right to. **The single highest
  leverage change available to this harness is to the Planner's criteria-writing standard, not to
  Generator feedback.** Two rules earned their place this feature: never name a seed id in a criterion
  without checking no test mutates it, and when a criterion asserts a stored value, state what a reader
  of that value is entitled to conclude.
* **Debt closure finally happened, and only because the Evaluator said where.** Sprint 2's review named
  the two files Sprint 3 would open and told the next Generator to fold the cleanups in; both closed at
  no measurable cost. Sprint 1's cleanups, which named files no later contract opens, are still open four
  sprints later. **The mechanism that works is routing a cleanup to a sprint that already opens the
  file** — nothing else has retired a single finding.
* **Shared-H2 and global-sweep fragility bit once per sprint, and was caught by tests every time.**
  Sprint 1 in `H2SchemaTest`, Sprint 3 in the escalation integration test. Both were fixed by making the
  test self-contained rather than by loosening assertions. The pattern is stable enough to state as a
  rule: any test that calls `publishOverdueBreaches()` must own the activity it asserts on.
* **Module coverage is now real for `activities`, `alerts` and `staff`.** `programmes` and `reports`
  remain baseline-only across four sprints.
* **Harness defects, unresolved, for the human:** the Monitor's archive naming still has no feature
  dimension (this feature's artifacts live under `.harness/reviews/sla-breach-alerting/` to avoid
  overwriting the bulk-update trail); `.harness/output/spec.md` still carries
  `STATUS: AWAITING APPROVAL` with no marker distinguishing approved-and-complete, and it is not archived
  with the sprint artifacts, so the bulk-update spec survives only in git history.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts read and written this sprint (`sprint-3-contract.md` re-read, `generator-summary.md`, `evaluator-feedback.md`) | 2,181 |
| Declared source and test files written | 5,480 |
| Context-only files read, not changed (`TaskRoutes`, `CreateTaskRequest`) — small, and already in the feature's earlier bases | 0 |
| **Total** | **7,661** |

`7,661 * 1.3 * 1 = 9,959` → **~9,900 tokens**.

Feature total across the three sprints: `30,800 + 11,800 + 9,900` ≈ **52,500 tokens**, against 56 new
tests and 21 new or modified source files. Excluded as before: Maven output and surefire reports.
