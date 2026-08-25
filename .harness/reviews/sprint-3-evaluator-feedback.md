# Evaluator Feedback — Sprint 3: Scheduled Overdue Detection

## VERDICT: CONDITIONAL PASS

## SCORE

`A 40/40 · B 35/35 · C 21/25 = 96`

## GATE RESULTS

### Automated

| Dimension | Gate | Result |
| --- | --- | --- |
| A | JUnit | **pass** — `Tests run: 154, Failures: 0, Errors: 0, Skipped: 0`. 134 at Sprint 2 close, +20 |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **pass** — `Tests run: 12, Failures: 0, Errors: 0` |
| B | Checkstyle (`IllegalThrows`, `NoRawErrorThrows`, `IllegalCatch`, imports, formatting) | **pass** — `You have 0 Checkstyle violations` |
| B | SpotBugs, effort Max, threshold Medium | **pass** — no bug instances |
| C | `jacoco:check` bundle ≥ 85% line / 60% branch; per-class ≥ 70% / 50% on services and listeners | **pass** — `All coverage checks have been met` |

`mvn clean test` exit code **0**.

Per-class coverage on the classes this sprint introduced or touched, read from
`target/site/jacoco/jacoco.xml` rather than taken from the summary:

| Class | Line | Branch |
| --- | --- | --- |
| `SlaSweepScheduler` | 9/9 | 2/2 |
| `SlaSweepProperties` | 8/8 | 10/10 |
| `TaskService` | 59/61 | 25/28 |
| `AlertEventListener` (untouched) | 32/32 | 11/12 |

Both new classes are at 100% on both counters, comfortably clear of the 70/50 floor.

**Build command discrepancy.** `./mvnw clean test` — the command named in this agent file,
`evaluation-criteria` §1, `how-to-review` §2, `how-to-test` §3, `app-context` §1 and `CLAUDE.md` —
cannot be run: there is no Maven wrapper and no `.mvn/` directory in the repository. Gates were run
with `mvn clean test` (Maven 3.9.14 on `PATH`). Every gate is bound to a lifecycle phase in
`pom.xml`, so the substitution changes nothing about what executed. The Generator flagged this;
confirmed, and routed to the Planner below rather than scored.

### LLM-assessed (evaluator agent §2)

| # | Gate | Result |
| --- | --- | --- |
| 1 | A required event was never published | **pass** — `TaskService:177` publishes `TaskOverdueEvent` and `SlaSweepScheduler:59` is the caller the contract required. Asserted on payload, not on count, at `TaskServiceTest.publishOverdueBreachesPopulatesTheEventFromTheSeedData` |
| 2 | Event wiring that fails silently | **pass** — `@Transactional` verified still present on `TaskService:176`; `AlertEventListener:56-57` and `:76-77` both retain `@TransactionalEventListener(AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)`; `EventBusConfiguration` untouched. See Finding 1 for what this sprint does *not* prove |
| 3 | Business logic in a route | **pass** — no route touched. The breach filter stays in `TaskService`; `SlaSweepScheduler` holds no rules, verified by `verifyNoMoreInteractions` |
| 4 | Criteria covered only by a status-code assertion; absence assertions with no positive counterpart | **pass** — no status-code-only assertion exists in this sprint. AC 2 is an absence assertion but has its positive counterpart on the same fixture in AC 1 |
| 5 | A dropped acceptance criterion | **pass** — all 8 criteria implemented and proven. None declared as a known gap |

### Contract fulfilment, criterion by criterion

Every test method named in `generator-summary.md` was confirmed to exist and to assert the
criterion's THEN, not a proxy for it.

| AC | Verified | Note |
| --- | --- | --- |
| 1 | yes | All seven event fields asserted individually, plus `eventType()`. Not a count |
| 2 | yes | Three separate `doesNotContain` assertions, so a failure names the broken filter |
| 3 | yes | Returns 0 and the bus is empty |
| 4 | yes | Three calls, three events, `containsExactly` on the repeated id |
| 5 | yes | `verifyNoMoreInteractions` is the assertion that makes this criterion mean something |
| 6 | yes | See mutation probe below |
| 7 | yes | Defaults bound from the shipped yml with no override; five rejection cases; zero initial delay accepted |
| 8 | **qualified yes** | Suite green at 154/154, but one existing test was modified to get there. See Finding 2 |

### Mutation probe on AC 6

Deviation 2 replaced the contract's two `@SpringBootTest` classes with `ApplicationContextRunner`
cases. A changed test mechanism is not taken on trust, so the condition was deleted from
`SlaSweepScheduler` and the class re-run:

```
[ERROR] SlaSweepWiringTest.schedulerIsAbsentWhenDisabled:78->lambda$2:79
Expecting:
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0
```

Exactly one test failed, and it was the right one — the other five correctly still pass, since
removing the condition still registers the bean. The runner genuinely evaluates
`@ConditionalOnProperty`, which is the claim Deviation 2 rests on. The Generator's stated reason
holds too: registering the scheduler as a component class rather than via a `@Bean` method is what
makes the condition apply at all. Annotation restored and the full suite re-run to exit 0 afterwards.

Deviation 2 is **accepted**. It proves the criterion at least as strongly as the specified mechanism
and adds one cached Spring context instead of two.

## FINDINGS

Three items. None is a hard-gate failure; items 1 and 2 are the conditional-pass cleanups, item 3 is
routed to the Planner.

**1. `src/main/java/com/cognizant/storeops/activities/service/TaskService.java:176` — the
after-commit delivery of a sweep-published event is live but unasserted.** Dimension C, "Verify
Everything" (`evaluation-criteria` §4).

This sprint made `TaskOverdueEvent` reachable in production for the first time. Every test that
observes it uses `RecordingEventBus`, which records at publish time, so `@Transactional` could be
deleted from `publishOverdueBreaches()` today and all 154 tests would still pass while every real
subscriber silently stopped running. Sprint 2's run log recorded this exact trap on
`TaskService.update`, and settled it by mutation testing.

Not scored as a dropped criterion: the contract deliberately placed the proof in Sprint 4
Scenarios 10 and 11, per `sprint-decomposition` §2, and the Generator declared it as Known Gap 4
rather than implying the green build covered it. It is also not a `evaluation-criteria` §4 "new
events" hard gate, because no new event type was introduced — `TaskOverdueEvent` and its listener
both predate this sprint.

**Required:** nothing in this sprint. Sprint 4 Scenario 11 must be executed as written, and its
mutation check recorded in `generator-summary.md`. Carried forward, not closed.

**2. `src/test/java/com/cognizant/storeops/H2SchemaTest.java:67` — an existing test was rewritten to
make this sprint green, and the underlying leak is still there.**

The change itself is **accepted**. Verified independently rather than taken from the summary:
`ApiSmokeTest:94` performs a real `POST /api/projects` against the shared H2 instance and never
removes the row; the database is JVM-wide (`DB_CLOSE_DELAY=-1`) while Spring caches one context per
distinct test configuration, and `@DirtiesContext` on three classes rebuilds and reseeds it partway
through. Whether `seedDataLoaded` sees two programmes or three is therefore a function of context
build order, which adding any context perturbs. The same method already conceded this by using
`isGreaterThanOrEqualTo` for `tasks` and `notifications` while asserting exact equality for
`projects` — the hazard was known and half-mitigated.

Counting seed rows by id is order-independent and is what the method's own `@DisplayName` claims to
test. It also tightened `tasks` and `notifications` from `>=` back to `==`. This is a strengthening,
not the "Generator edits a test to go green" pattern it superficially resembles, and the Generator
declared it under its own heading rather than burying it in the file list. Credit for that.

**Outstanding cleanup:** the root cause is untouched. `ApiSmokeTest.postProject` inserts a programme
into a shared database and no test cleans it up, so the next assertion anywhere in the suite that
counts a mutable table will break the same way. Fix: give `ApiSmokeTest` the
`@DirtiesContext(BEFORE_EACH_TEST_METHOD)` its three `BulkStatus*` siblings already carry, or have
the test delete the programme it created. One line either way, test-only, no behavioural risk. Left
for Sprint 4 to collect.

**3. Routed to the Planner, not scored — two contract defects, neither the Generator's fault.**

* **`sprint-3-contract.md` Scenarios 1 and 3 specify a fixed clock of `2026-02-01T00:00:00Z`, which
  contradicts `how-to-test` §2**: "always use a fixed Clock pinned to `2026-02-01T10:00:00Z`". The
  Generator resolved it toward the project-wide convention that `TaskServiceTest` already used, and
  declared the deviation. Correct call, but the contract should not have created the conflict. Future
  contracts should cite the skill's instant rather than naming their own.
* **Every skill file and `CLAUDE.md` command `./mvnw clean test`, which does not exist in this
  repository.** Either check in the Maven wrapper or change the six documents that name it. Until
  then every agent in the harness must silently substitute a command, which is exactly the kind of
  guessing the contracts are written to prevent.

## What was not reviewed

* `PROMPT.md` is modified in the working tree and is not in the Generator's declared file list. It is
  the harness demonstration file listing the four candidate feature prompts, edited by the human
  before this run was invoked, and sits outside `src/` and `.harness/output/`. Not a Generator
  change; excluded from scope rather than treated as an undeclared edit.
* `git status --porcelain` otherwise matches the declared file list exactly: 11 modified and 5 new
  source or test files, all declared, plus the five `.harness/output/` artifacts.
* Baseline gaps in untouched legacy code, per `how-to-review` §1.
