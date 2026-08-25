# Evaluator Feedback — Sprint 4: SLA Breach Routed to the Department Lead

## VERDICT: CONDITIONAL PASS

## SCORE

`A 40/40 · B 35/35 · C 23/25 = 98`

## GATE RESULTS

### Automated

| Dimension | Gate | Result |
| --- | --- | --- |
| A | JUnit | **pass** — `Tests run: 181, Failures: 0, Errors: 0, Skipped: 0`. 154 at Sprint 3 close, +27 |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **pass** — `Tests run: 12, Failures: 0`. This is the first sprint in which these rules governed a real new cross-module edge |
| B | Checkstyle | **pass** — `You have 0 Checkstyle violations` |
| B | SpotBugs, effort Max, threshold Medium | **pass** |
| C | `jacoco:check` | **pass** — `All coverage checks have been met` |

`mvn clean test` exit code **0**. (`./mvnw` still absent; see Sprint 3's finding, unchanged.)

Per-class coverage on the classes this sprint changed, read from `jacoco.xml`:

| Class | Line | Branch |
| --- | --- | --- |
| `AlertEventListener` | 59/59 (100%) | 21/22 (95%) |
| `NotificationService` | 18/18 (100%) | 4/4 (100%) |
| `UserService` | 15/15 (100%) | 6/6 (100%) |

### LLM-assessed (evaluator agent §2)

| # | Gate | Result |
| --- | --- | --- |
| 1 | A required event was never published | **pass** — this sprint consumes rather than publishes; no criterion required a new publication, and no new event was added |
| 2 | Event wiring that fails silently | **pass, and proven rather than inspected** — see below |
| 3 | Business logic in a route | **pass** — no `routes` class was touched. Recipient resolution sits in the listener, the roster read in the staff service |
| 4 | Criteria covered only by a status-code assertion; absence assertions with no positive counterpart | **pass** — every integration assertion is on a `Notification` row read back through `GET /api/notifications`. Both absence-only tests have positive counterparts in the same class |
| 5 | A dropped acceptance criterion | **pass** — all 11 criteria implemented and proven |

### Gate 2 in detail — the sprint's central risk, settled

Sprint 3's run log predicted that `@Transactional` could be deleted from
`TaskService.publishOverdueBreaches()` with the whole suite still green, and named Scenario 11 as the
test that would settle it. The Generator ran the probe and reported this:

```
[INFO] Tests run: 17, Failures: 0 -- TaskServiceTest
[INFO] Tests run: 14, Failures: 0 -- AlertEventListenerTest
[ERROR] Tests run: 4, Failures: 2 -- SlaBreachAlertingIntegrationTest
[ERROR]   SlaBreachAlertingIntegrationTest.sweepDeliversBreachToTheDepartmentLead:71
[ERROR]   SlaBreachAlertingIntegrationTest.aSecondSweepRaisesNoDuplicate:92
```

The prediction was exact: all 31 unit tests survive the mutation, and only the new integration tests
bite. The carried gap from Sprint 3 is now closed. Final-state wiring re-verified by inspection as
well: `@Transactional` present at `TaskService:176`, and both
`@TransactionalEventListener(AFTER_COMMIT)` and `@Transactional(REQUIRES_NEW)` present on both
handlers at `AlertEventListener:56-57` and `:76-77`.

The Generator also volunteered a finding the probe surfaced: `theAssigneeIsNotNotified` and
`onlyTheTrackedBreachRaisesAnAlert` **stayed green** through the mutation, because an absence
assertion cannot detect a dropped transaction — with no alert raised, everything is absent. That is
the clearest concrete case for the rubric's absence-assertion rule seen in four sprints, and it was
reported rather than left for review to find.

### Architectural guardrails — verified independently, not read from the summary

This is the first sprint to add a cross-module edge (`alerts` → `staff`), so each guardrail was
checked directly rather than inferred from the green ArchUnit run:

| Guardrail | Check | Result |
| --- | --- | --- |
| No `activities` or `staff.repository` import in `alerts` | grep over `alerts/` | clean, no matches |
| No `TaskService` injected into `alerts` | grep over `alerts/` | only match is a javadoc line at `AlertEventListener:32` explaining why it is *not* injected |
| `UserService` gains no mutator | every `public` member listed | eight members, all reads: `getById`, `findById`, `exists`, `findByStoreId`, `findByStoreIdAndRole`, `findAll`, plus the constructor. Staff remains structurally read-only |
| De-duplication keys on activity *and* alert type | `AlertEventListener:115` | `findBySourceRefAndAlertType(event.taskId(), AlertType.SLA_BREACH)` — both criteria |
| `TaskOverdueEvent` unchanged | `shared/` diff | untouched. Catalogue still at three events |
| Listener does not reach the repository | call path | goes through `NotificationService.findBySourceRefAndAlertType` |

A note in the Generator's favour: `AlertEventListener`'s class javadoc previously claimed "only
`shared.events` types are imported". Injecting `UserService` made that false, and the Generator
corrected the javadoc rather than leaving a stale claim for review to trip over. Sprint 2's run log
flagged a stale-javadoc defect of exactly this kind; that lesson was applied.

### Deviations — all three accepted

1. **`ApiSmokeTest` cleanup collected with `AFTER_CLASS` instead of the suggested
   `BEFORE_EACH_TEST_METHOD`.** Accepted, and it is the better fix: identical effect downstream at one
   context rebuild rather than eleven. The Generator verified it at source by temporarily restoring
   the original `SELECT count(*) FROM projects` table-total assertion and running the full suite green
   — that is the right way to prove a leak is closed, rather than asserting it is. It then kept the
   id-keyed assertion anyway, on the grounds that the annotation fix depends on one line staying put
   while the id-keyed assertion survives any number of new contexts. Both defences now hold. Sprint
   3's one outstanding cleanup is **closed**.
2. **`NotificationServiceTest` added beyond contract scope.** Accepted. `NotificationService` was
   sitting at 50% branch — *exactly* on the JaCoCo floor — with no test class and its
   `ValidationError` guard unexercised, which `how-to-test` §3 requires. Now 100%/100%. A class on a
   threshold fails the build on the next branch anyone adds, and Sprint 5 extends this very listener.
3. **Real `UserService` over `FakeUserRepository` rather than a Mockito mock in the listener test.**
   Accepted, and the Generator's reasoning is correct: ACs 8 and 9 test roster-dependent selection, so
   stubbing `findByStoreIdAndRole` would assert the stub's ordering instead of the system's. The
   contract's own "GIVEN a roster for `store-001` containing…" phrasing points the same way, as does
   `how-to-test` §2's own "Use Fakes, Not Mocks" heading. `FakeUserRepository` deliberately declining
   to filter inactive staff — mirroring `JpaUserRepository` — is the detail that makes this fixture
   honest rather than convenient: a missing `active` filter in the service would surface, not hide.

## FINDINGS

One item. Not a hard-gate failure.

**1. `src/main/java/com/cognizant/storeops/alerts/listener/AlertEventListener.java:115` — the
de-duplication read discriminates by alert type, but no listener-level test proves it.** Dimension C,
"Verify Everything".

`AlertType.ESCALATION` is already written with `sourceRef = taskId` by `onTaskStatusChanged` for
blocked activities. So an activity that was `BLOCKED` and then breaches its SLA already has a
notification carrying its `taskId`. If the stage-one suppression check ever narrowed to `sourceRef`
alone, that activity would never receive an `SLA_BREACH` at all — silently, and only for activities
that had previously been blocked.

The code is correct today: the call passes `AlertType.SLA_BREACH` explicitly, and
`NotificationServiceTest.findBySourceRefAndAlertTypeFiltersOnBothCriteria` proves the underlying read
genuinely discriminates by type. So this is a **test gap, not a defect** — the hazard is covered one
layer down but not at the layer where the mistake would be made. AC 3 is adjacent but tests a
different *activity*, not a different *alert type on the same activity*.

**Required in Sprint 5:** one listener test — pre-seed an `ESCALATION` with subject `Activity blocked`
and `sourceRef` `task-001`, handle the `task-001` overdue event, assert the `SLA_BREACH` is still
raised. Sprint 5's Scenario 6 already specifies the mirror case for stage two, so both arms of the
collision end up covered by the same fixture. Cheap to add there and it belongs with its sibling.

## What was not reviewed

* `PROMPT.md` remains modified in the working tree — the harness demonstration file listing candidate
  feature prompts, edited by the human before this run. Outside `src/` and `.harness/output/`; not a
  Generator change.
* `git status --porcelain` otherwise matches the declared file list exactly.
* Baseline gaps in untouched legacy code, per `how-to-review` §1. The one uncovered branch in
  `AlertEventListener` (21/22) is the blank-assignee arm of the pre-existing `isUnassigned` helper on
  the untouched `onTaskStatusChanged` path; the Generator declared it and it is not scored.
