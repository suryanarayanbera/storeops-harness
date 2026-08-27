# Sprint 4 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 4 (second of three in the SLA breach alerting feature) |
| Goal | Raise exactly one `SLA_BREACH`, addressed to the Department Lead responsible for the assignee's department, and suppress every later observation of the same breach |
| Modules touched | `alerts` (listener, service, repository), `staff` (service) |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~26.7k |

`mvn clean test` exit 0. JUnit 181/181 (154 at Sprint 3 close, +27), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo bundle and per-class gates met.

**The first sprint in this harness's history to modify the `alerts` module.** Two features and four
sprints in, `alerts` had been exercised end to end many times and never edited. It also added the
first new cross-module edge (`alerts` → `staff.service`), so the ArchUnit boundary rules governed real
new work rather than trivially passing over unchanged code.

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — five automated gates and five LLM-assessed hard gates passed on the first attempt | none required; one test gap left outstanding |

## Files Changed

### alerts — listener
- `listener/AlertEventListener.java` — `onTaskOverdue()` rewritten: `SLA_BREACH_SUBJECT` constant,
  `UserService` injected, three private helpers (`resolveBreachRecipient`, `departmentOf`,
  `storeManagerId`). De-duplication reads existing `SLA_BREACH` rows by `sourceRef` **and**
  `alertType`. `onTaskStatusChanged()` untouched. Class javadoc corrected — it claimed only
  `shared.events` types were imported, which stopped being true when `staff` was injected.

### alerts — service
- `service/NotificationService.java` — added `findBySourceRefAndAlertType(String, AlertType)`, oldest
  first. Keeps the listener off the repository, which ArchUnit rule 1b forbids.

### alerts — repository
- `repository/NotificationRepository.java` — added `findBySourceRefAndAlertType`.
- `repository/NotificationJpaRepository.java` — added `OLDEST_FIRST` sort and a derived query.
- `repository/JpaNotificationRepository.java` — implemented it; empty for a null argument rather than
  a query that cannot match.

### staff — service
- `service/UserService.java` — added `findByStoreIdAndRole(String, StaffRole)`: active only, exact
  role, sorted by id, empty for nulls. Implemented by filtering `findByStoreId`. `UserRepository` was
  not widened and no mutator was added — staff remains structurally read-only, verified member by
  member at review.

### tests — new
- `SlaBreachAlertingIntegrationTest.java` — **new**, 4 tests. Sweep disabled,
  `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`, every assertion a `Notification` read back through
  `GET /api/notifications`.
- `alerts/service/NotificationServiceTest.java` — **new**, 8 tests. Beyond contract scope; see
  cleanups note below.
- `staff/service/UserServiceTest.java` — **new**, 7 tests. Active filter, exact role, id ordering,
  null handling.
- `support/FakeUserRepository.java` — **new** fixture with a `withSeedRoster()` builder. Deliberately
  does not filter inactive staff, mirroring `JpaUserRepository`, so a missing `active` filter in the
  service would surface rather than hide.

### tests — modified
- `alerts/listener/AlertEventListenerTest.java` — 6 → 14 tests. Two asserted the old stub behaviour
  and were rewritten: `criticalOverdueRaisesSlaBreach` (expected the assignee as recipient) and
  `overdueUnassignedRaisesNothing` (expected silence where a Store Manager fallback is now required).
- `support/FakeNotificationRepository.java` — implemented the new method, sorted oldest first to match
  the real query rather than relying on insertion order.
- `ApiSmokeTest.java` — `@DirtiesContext(AFTER_CLASS)` added. Sprint 3's cleanup; see below.
- `H2SchemaTest.java` — javadoc updated to record that the leak is now fixed at source while the
  id-keyed assertion is kept anyway.

### untouched
`TaskOverdueEvent` and all of `shared`, `EventBusConfiguration`, `NotificationEntity`,
`UserRepository`, `UserEntity`, `data.sql`, `pom.xml`, every `routes` class. No new event, no new
`AppError` subtype, no new enum value, no schema change, no new endpoint. `TaskService` unchanged
apart from the mutation probe, which was reverted.

## Conditional Pass Cleanups

One item, **outstanding**, assigned to Sprint 5.

1. **`AlertEventListener:115` — the stage-one de-duplication read discriminates by alert type, but no
   listener-level test proves it.** `AlertType.ESCALATION` is already written with
   `sourceRef = taskId` by the blocked-activity handler, so an activity that was `BLOCKED` and then
   breaches already has a notification carrying its `taskId`. If the suppression check ever narrowed
   to `sourceRef` alone, that activity would never receive an `SLA_BREACH` — silently, and only for
   previously-blocked activities. The code is correct today and
   `NotificationServiceTest.findBySourceRefAndAlertTypeFiltersOnBothCriteria` proves the underlying
   read discriminates, so this is a test gap one layer away from where the mistake would be made, not
   a defect. Fix: one listener test pre-seeding an `ESCALATION` with subject `Activity blocked` for
   `task-001`, asserting the `SLA_BREACH` is still raised. Sprint 5's Scenario 6 specifies the mirror
   case for stage two, so both arms land on one fixture.

**Sprint 3's cleanup is closed.** The `ApiSmokeTest` programme leak was fixed with
`@DirtiesContext(AFTER_CLASS)` — one context rebuild rather than the eleven the suggested per-method
fix would have cost — and verified at source by temporarily restoring the original table-total
assertion and running the suite green.

## Quality Trend Notes

Fourth entry. Scores across the four sprints: 92, 98, 96, 98.

* **Iteration count flat at 1 of 3 for the fourth consecutive sprint.** The 3-attempt escalation
  budget has never been touched across two features and four sprints. At this point that is a
  property of the harness, not luck: contracts specific enough to name file paths, guardrails and
  exact string constants leave the Generator little room to guess.
* **Three consecutive sprints with zero behavioural defects in Generator-written production code.**
  Sprint 1 produced one (a 500 on a malformed payload); Sprints 2, 3 and 4 produced none. Every
  finding since has been a test gap, a contract defect, or test-infrastructure fragility. The harness
  is no longer catching bad code — it is catching incomplete proof, which is a different and cheaper
  class of problem.
* **Run-log predictions are now 2 for 2, both settled by mutation testing.** Sprint 1 predicted the
  unasserted after-commit path would matter in Sprint 2; mutation testing confirmed it. Sprint 3
  predicted `@Transactional` could be deleted from `publishOverdueBreaches()` with the suite green;
  Sprint 4's probe confirmed it exactly — 31 unit tests survived, only the 2 new positive integration
  assertions bit. Writing a falsifiable prediction into the run log and settling it in the next sprint
  is now established practice and should continue.
* **New insight, from the Sprint 4 probe: absence assertions cannot detect a dropped transaction.**
  Two of the four new integration tests stayed green through the mutation because both assert an
  absence — with no alert raised, everything is absent. Only the two asserting a positive row failed.
  This is the most concrete case yet for the rubric's absence-assertion rule, and it was volunteered
  by the Generator rather than found at review. Worth quoting in `how-to-test` when the docs are next
  audited.
* **Cleanups assigned to a following sprint get closed; cleanups left at feature end do not.**
  Sprint 3's single cleanup was closed in Sprint 4, with a better fix than proposed and verified at
  source. Sprint 2's single cleanup was the final sprint's and is *still* outstanding, needing a
  human. Two data points, but the mechanism is obvious and the lesson is actionable: never let a
  feature's last sprint be the one carrying the cleanups. Where possible, plan a feature so the
  final sprint's findings can be absorbed, or accept that they become human backlog.
* **The Generator now fixes classes sitting *on* a coverage threshold, not just under it.**
  `NotificationService` was at exactly 50% branch — passing, but one added branch from failing the
  build — with no test class at all. It was lifted to 100% unprompted. Worth adopting as explicit
  practice: read the per-class JaCoCo numbers, not just the pass/fail line.
* **The boundary rules earned their keep for the first time.** Four sprints in, `activities` had been
  the only module ever modified, so ArchUnit had never had to arbitrate a genuinely new dependency.
  Sprint 4 added `alerts` → `staff.service` and the rules held: no `staff.repository` import, no
  `TaskService` in `alerts`, no cycle, listener-to-service permitted by rule 5. The Evaluator also
  checked all four by hand rather than inferring them from the green run, which is the right instinct
  the first time a rule does real work.
* **Third data point on stale prose, and this time the Generator caught it itself.**
  `AlertEventListener`'s javadoc claimed only `shared.events` types were imported; injecting
  `UserService` falsified it and the Generator corrected it in the same edit. Sprint 2 found a stale
  `FailingSubscriber` javadoc and Sprint 3 found the non-existent `./mvnw`. The docs audit flagged in
  Sprint 3 is still owed, but the trend inside the code is now improving rather than accumulating.
* **`./mvnw` still does not exist.** Second sprint reporting it. Six documents name a command that
  cannot be run. This is now the oldest open item in the harness.
* **Prediction for Sprint 5, two parts, both falsifiable.** First: Sprint 5 introduces
  `SLA_ESCALATION_SUBJECT` and a stage-two check that must key on subject *and* alert type; if it
  keys on `alertType` alone, every previously-blocked activity silently never escalates, and Scenario
  6 is written to bite. Second: Sprint 5 adds a `@SpringBootTest` with a distinct property set
  (`grace-period=PT0S`), which means a new cached context — precisely what broke `H2SchemaTest` in
  Sprint 3. Both defences are now in place (the `ApiSmokeTest` annotation and the id-keyed
  assertions), so Sprint 5 is the test of whether those fixes actually generalised or merely fixed
  one symptom. If a seed-count assertion breaks again, the conclusion is that per-class
  `@DirtiesContext` is not enough and the shared-database design itself needs revisiting.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts — `spec.md`, `sprint-4-contract.md`, `generator-summary.md`, `evaluator-feedback.md`, `sprint-3-run-log.md`, 4 agent files, 7 skill files, `CLAUDE.md` | 12,638 |
| Source written — `AlertEventListener`, `NotificationService`, 3 `alerts` repository files, `UserService` | 2,180 |
| Tests written — 3 new test classes, 1 new fixture, `AlertEventListenerTest`, `FakeNotificationRepository`, `ApiSmokeTest`, `H2SchemaTest` | 3,611 |
| Source read for context — `NotificationEntity`, `NotificationResponse`, `JpaUserRepository`, `StaffRole`, `FakeRepository`, `BulkStatusEventDeliveryIntegrationTest`, `TaskService` | 2,120 |
| **Total words** | **20,549** |

`20,549 * 1.3 * 1 = 26,714` → **~26.7k tokens**

Down from Sprint 3's 29.6k as predicted, because this sprint carried no planning overhead and needed
less of `src/` read to orient. Higher than either shift handover sprint (19.1k, 20.4k) because it
wrote four new test classes and touched two modules rather than one. Excludes Maven output, which was
filtered rather than read, and the three verification runs (the transaction mutation probe, the leak
probe, and the post-restore full build).
