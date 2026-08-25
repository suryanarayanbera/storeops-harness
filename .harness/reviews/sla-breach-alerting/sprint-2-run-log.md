# SLA Breach Alerting — Sprint 2 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 2 (feature: SLA breach alerting — 3 sprints planned) |
| Goal | The lead alert: a durable breach episode in `alerts`, recipient resolution from assignee to Department Lead with a Store Manager fallback, and exactly one `SLA_BREACH` notification however many times the breach is observed |
| Modules touched | `alerts` (domain, repository, service, listener), `staff` (one read, repository + service). No `activities`, `programmes`, `reports` or `shared` source changed |
| Final Verdict | **CONDITIONAL PASS** |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated Token Cost | ~11,800 tokens |

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — every automated and assessed gate passed on the first attempt | none required |

Gate detail: `BUILD SUCCESS`. JUnit `Tests run: 134, Failures: 0, Errors: 0, Skipped: 0`;
`ModuleBoundaryTest` 12/12 with the new `alerts` → `staff` edge; Checkstyle and SpotBugs clean;
`jacoco:check` passed including the new `alerts.service.SlaBreachService`. Test count 120 → 134, so the
sprint added 14 tests. No build failures during generation either — the first `./mvnw clean test` of the
sprint was green, unlike Sprint 1.

## Files Changed

Copied from `sprint-2-generator-summary.md`, grouped by module and layer.

### alerts — source
- `domain/SlaBreach.java` — new record (`taskId`, `storeId`, `priority`, `firstBreachAt`,
  `leadRecipientId`, `leadNotifiedAt`, `lastSeenAt`) with an `opened(...)` factory and `withLastSeen`.
  Escalation fields deliberately deferred to Sprint 3 so nothing lands unused
- `repository/SlaBreachEntity.java` — new `@Entity @Table("sla_breaches")`, `@Id` on `task_id`;
  `priority` a plain string column because the value arrives as a string on the event
- `repository/SlaBreachJpaRepository.java` — new, package-private, oldest-breach-first `DEFAULT_SORT`
- `repository/SlaBreachRepository.java` — new port: `save`, `findByTaskId`, `findAll`
- `repository/JpaSlaBreachRepository.java` — new adapter
- `service/SlaBreachService.java` — new; the whole policy: priority filter, episode lookup and dedup,
  the lead → store-manager fallback chain, and the `SLA_BREACH` alert
- `listener/AlertEventListener.java` — `onTaskOverdue` reduced to one delegating call, both transaction
  annotations intact; `SLA_TRACKED_PRIORITIES` moved into the service; constructor gained
  `SlaBreachService`

### staff — source
- `repository/UserRepository.java`, `repository/UserJpaRepository.java`,
  `repository/JpaUserRepository.java` — new `findByStoreIdAndRole(String, StaffRole)` read
- `service/UserService.java` — the same read, delegating; still no mutator of any kind

### resources
- `src/main/resources/data.sql` — comment recording why `sla_breaches` is intentionally unseeded

### tests
- `alerts/service/SlaBreachServiceTest.java` — new, 10 tests, fakes plus a mocked `UserService`; time
  advances by rebuilding the service over the same repositories
- `staff/service/UserServiceIntegrationTest.java` — new `@SpringBootTest`, 2 tests
- `alerts/listener/AlertEventListenerTest.java` — new constructor, episode assertion, added repeat-alert
  test; the `BLOCKED`, `DONE`, unassigned and LOW-priority tests unchanged
- `EventDeliveryIntegrationTest.java` — Sprint 1's sweep test replaced by the two-sweep, one-alert
  version, reading `sla_breaches` through `JdbcTemplate` so no repository is imported
- `H2SchemaTest.java` — `SLA_BREACHES` in the table list; new `slaBreachesTableShape` asserting the seven
  columns and the sole primary key
- `support/FakeSlaBreachRepository.java` — new test double

Scope verified with `git status --porcelain`: nothing undeclared. `support/` holds no `UserRepository`
double, so the contract's instruction to update one had nothing to act on.

## Conditional Pass Cleanups

Non-behavioural debts from `sprint-2-evaluator-feedback.md`.

1. `alerts/repository/SlaBreachRepository.java:20` — `findAll()` has no production caller; only tests
   reach it, and the fake would answer it regardless. Decide in Sprint 3: drop it, or let the
   out-of-scope breach-tracker endpoint be its caller. **Sprint 3 opens this file**, so it is free there.
2. `alerts/service/SlaBreachService.java:88` — `observe` raises the notification and writes the episode
   as two calls with no transaction of its own, correct only because its sole caller is a
   `REQUIRES_NEW` listener. Add `@Transactional`; `REQUIRED` joins the listener's transaction so nothing
   changes today. **Sprint 3 opens this file too.**

Carried, unresolved, from Sprint 1 — both in `activities`, which neither Sprint 2 nor Sprint 3 opens:

3. `activities/repository/TaskJpaRepository.java:52` — "overdue" defined in SQL for the sweep and in Java
   for `reports`, with no test tying them together.
4. `activities/listener/OverdueSweepScheduler.java:41` — `INFO` on every empty sweep.

## Quality Trend Notes

Third logged sprint, second of this feature.

| Metric | Bulk update | SLA sprint 1 | SLA sprint 2 | Direction |
| --- | --- | --- | --- | --- |
| Iterations | 1 of 3 | 1 of 3 | 1 of 3 | flat, three for three |
| Gate failures at evaluation | 0 | 0 | 0 | flat |
| Test count | 96 → 111 | 111 → 120 | 120 → 134 | +14, the largest of the three |
| Score | 96 | 93 | 97 | recovered |
| Findings | 4 | 2 | 2 | flat |

Patterns:

* **The Planner-defect trend did not continue.** Sprint 1's log called out that the contract, not the
  implementation, was producing the findings, and named two fixes to the criteria-writing standard. This
  sprint's contract named no seed id that another test mutates, and both of its findings are
  implementation-side (an unused port method, a missing `@Transactional`). One sprint is not a
  reversal, but the specific defect did not recur. Keep the standard.
* **Debt is accumulating faster than it is being paid: 0 of 4 cleanups closed.** Two new this sprint,
  two carried from Sprint 1 and untouched. The reason is structural rather than negligent — cleanups
  live in modules that later contracts do not open, so nothing ever reaches them. Sprint 3 opens the
  files holding cleanups 1 and 2 and should be told to fold them in; cleanups 3 and 4 have no sprint
  left in this feature that touches `activities` and need either a closing sprint or an explicit
  write-off. **This is the trend to watch: a harness that gates quality per sprint but has no mechanism
  for retiring the debt it records.**
* **Module coverage broadened for the first time.** Three sprints in, `alerts` and `staff` finally carry
  real tests rather than baseline ones. `programmes` and `reports` remain baseline-only.
* **Shared-H2 order fragility did not bite this sprint**, despite adding a table and two integration
  tests — because both new integration tests assert either id-scoped counts or seed rows that nothing
  mutates, and the rewritten delivery test filters by `sourceRef`. That is the discipline Sprint 1's log
  asked for, applied. Sprint 3 adds more integration tests and must keep it.
* **Archive naming defect, unchanged.** This feature's artifacts continue under
  `.harness/reviews/sla-breach-alerting/` so the bulk-update audit trail survives. Sprint 3 must use the
  same subdirectory. The Monitor's specified scheme still has no feature dimension.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts read and written this sprint (`sprint-2-contract.md` re-read, `generator-summary.md`, `evaluator-feedback.md`) | 2,892 |
| Declared source and test files written | 6,164 |
| Context-only files read, not changed (`UserJpaRepository`, `JpaUserRepository`, `NotificationService`, `FakeRepository`, `FakeNotificationRepository`, `UserProfile`) — already counted in Sprint 1's basis, so excluded here | 0 |
| **Total** | **9,056** |

`9,056 * 1.3 * 1 = 11,773` → **~11,800 tokens**.

Lower than Sprint 1 because the agent definitions, skill files and the three sprint contracts were
counted once, in Sprint 1's basis, and are not double-counted here. Excluded as before: Maven output and
surefire reports.
