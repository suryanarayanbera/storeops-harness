# Evaluator Feedback — Sprint 1: The Overdue Sweep

## 1. VERDICT

**CONDITIONAL PASS**

Every hard gate passed, automated and assessed. Two cleanups are recorded below; neither blocks the
sprint, and both are small enough to fold into Sprint 2 rather than justify a retry.

## 2. SCORE

`A 38/40 · B 33/35 · C 22/25 = 93`

## 3. GATE RESULTS

Command: `./mvnw clean test` — exit code 0.

| Dimension | Gate | Result |
| --- | --- | --- |
| — | Undeclared changes (`git status --porcelain`) | **PASS.** Every modified and added source/test path appears in `generator-summary.md`. No stray edits. |
| A | JUnit | **PASS.** `Tests run: 120, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`. 96 baseline plus this sprint's additions. |
| A | Every AC has a test proving its THEN | **PASS.** 8 of 8 criteria mapped to named test methods that assert payloads and state, not status codes. See §4 on AC 4 and AC 8. |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **PASS.** `Tests run: 12, Failures: 0, Errors: 0` — boundaries, cycles, layering, read-only reports and the `AppError` hierarchy all settled deterministically. |
| B | Checkstyle (`validate`), incl. `IllegalThrows`, `NoRawErrorThrows`, `IllegalCatch` | **PASS.** No violations; the build passed `validate` to reach `test`. |
| B | SpotBugs (`test-compile`), effort Max / threshold Medium | **PASS.** No bug patterns reported. |
| C | `jacoco:check` — bundle line ≥ 85% / branch ≥ 60%; per-class line ≥ 70% / branch ≥ 50% on services and listeners | **PASS.** `--- jacoco:0.8.13:check (jacoco-check) @ storeops-api ---` completed with no rule violation, including the new `activities.listener.OverdueSweepScheduler`. |

### Assessed gates — the five the build cannot see

1. **A required event was never published** — **PASS.** `TaskService.publishOverdueBreaches`
   ([TaskService.java:266](src/main/java/com/cognizant/storeops/activities/service/TaskService.java#L266))
   publishes one `TaskOverdueEvent` per breach, asserted on payload — not merely on count — by
   `TaskServiceTest.publishOverdueBreachesFiltersByPriorityAndStatus`.
2. **Event wiring that fails silently** — **PASS.** The publisher keeps `@Transactional`
   ([TaskService.java:265](src/main/java/com/cognizant/storeops/activities/service/TaskService.java#L265)),
   and `EventDeliveryIntegrationTest.sweptOverdueBreachReachesAlertsModule` proves after-commit
   delivery through the real container — the only assertion that catches a dropped `@Transactional`.
   `AlertEventListener` retains `AFTER_COMMIT` + `REQUIRES_NEW`; `EventBusConfiguration` is untouched.
3. **Business logic in a route** — **PASS.** No route was modified. `OverdueSweepScheduler` performs no
   due-date arithmetic, no priority check and builds no event; `sweepDelegatesToTheService` pins that
   with `verifyNoMoreInteractions`.
4. **Criteria covered only by a status-code assertion** — **PASS.** No criterion rests on a status code.
   The absence assertions (`isEmpty`, `doesNotContain`) each sit beside a positive counterpart in the
   same test.
5. **A dropped acceptance criterion** — **PASS.** None dropped. AC 8's proof is the green suite, which
   is what the criterion itself specifies; see §4.

### Invented domain vocabulary

**PASS.** No new enum value, event type, `AppError` subtype, route or table. `TaskOverdueEvent` is
reused unchanged and carries its priority as a `String`. The two new property keys sit under the
module-owned `storeops.activities.sla` namespace the contract specified.

## 4. FINDINGS

### Accepted deviations — reviewed, not held against the sprint

**AC 4, `task-002` substituted.** The contract named seed `task-002`; `ApiSmokeTest.updateTask` moves
that row to `DONE` in the shared H2 database, so the literal assertion would have passed or failed on
class execution order. The substituted `finder-open-medium` row (MEDIUM, `IN_PROGRESS`, past due) proves
the same thing the contract wanted from `task-002` — that the query does not filter on priority band.
Accepted: the criterion's purpose is met and the alternative is deterministic. A flaky assertion would
have been the worse outcome.

**`H2SchemaTest.seedDataLoaded` edited outside the deliverable list.** Verified as a genuine
pre-existing fragility rather than a test bent to fit new code. The extra `projects` and
`project_members` rows originate in `EventDeliveryIntegrationTest.closedProgrammeReachesReportsModule`,
which predates this sprint; the sweep publishes `TaskOverdueEvent` only and cannot create a programme or
a membership row. Scoping both counts to the seed ids kept them exact rather than loosening them to a
floor, so the assertion is stronger than before, not weaker. Accepted.

**AC 8 has no dedicated test method.** The criterion is written as a property of the whole run, and its
mechanism — the `PT10M` initial delay and the `enabled` flag — is covered directly by AC 6. Accepted.

### F1 — `src/main/java/com/cognizant/storeops/activities/repository/TaskJpaRepository.java:52`

**Duplicated definition of "overdue", with nothing tying the two together.** The sweep now decides
overdue in SQL (`t.dueAt < :moment AND t.status <> :terminalStatus`), while
`reports.service.ReportService:70` still decides it in Java through `Task.isOverdueAt(moment)`. The two
agree today. Nothing fails if they stop agreeing: the store summary would report an overdue count that
the SLA sweep does not breach on, in either direction, with no error anywhere.

Not a gate — the contract explicitly specified this finder as a data predicate, so the Generator
followed instruction. Recorded as a cleanup, cheapest fix first: a test asserting that
`taskRepository.findOpenPastDue(moment)` and `findAll().filter(t -> t.isOverdueAt(moment))` return the
same ids for the seed data, which pins the two definitions together without moving either.

### F2 — `src/main/java/com/cognizant/storeops/activities/listener/OverdueSweepScheduler.java:41`

**An `INFO` line every five minutes, forever, including for zero breaches.** A quiet store logs
288 `Overdue sweep published 0 SLA breach event(s)` lines a day. Log at `DEBUG` when the count is zero
and keep `INFO` for a non-zero sweep — this is the pattern `AlertEventListener` already follows, where
the uninteresting branch logs at `DEBUG`.

## 5. Notes for the Monitor

* Sprint 1 closes the publisher half of the feature. The repeat `TaskOverdueEvent` is now reachable on
  a timer while `alerts` still has no de-duplication, so a HIGH or CRITICAL breach currently raises a
  fresh `SLA_BREACH` to the **assignee** on every sweep. That is the pre-existing stub listener, and it
  is Sprint 2's first job to replace. Not a regression introduced here, and declared in
  `generator-summary.md`.
* Test-suite cost rose by one Spring context (`OverdueSweepSchedulerDisabledTest`, ~11s) for the off
  switch. Worth it: it is the mechanism that keeps a background timer out of every other integration
  test.
* F1 and F2 carry into Sprint 2's scope. Sprint 2 touches `alerts` and one `staff` read and does not
  re-open `TaskJpaRepository`, so F1 in particular needs to be carried deliberately or it will be lost.
