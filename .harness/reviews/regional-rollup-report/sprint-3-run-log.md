# Sprint 3 Run Log — Regional Rollup Report

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 3 (final — third of three in the regional rollup report feature) |
| Goal | Consume `RegionalRollupRequestedEvent` in `ReportEventListener` and turn it into a persisted `REGIONAL_ROLLUP` `Report` row, on its own transaction, so a failure to record cannot fail the caller's `GET` |
| Modules touched | `reports` (listener, service) |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~18.6k (feature total ~69.0k) |

`./mvnw clean test` exit 0. JUnit 247/247 (238 at Sprint 2 close, +9), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo check passed.

**Feature complete: regional rollup report, all three sprints closed. First 35/35 on Dimension B.**

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — five automated gates and nine LLM-assessed hard gates passed on the first attempt | none required; one cosmetic cleanup left outstanding |

## Files Changed

### reports — listener
- `listener/ReportEventListener.java` — added `onRegionalRollupRequested`, carrying
  `@TransactionalEventListener(phase = AFTER_COMMIT)` and
  `@Transactional(propagation = REQUIRES_NEW)`. Body is one
  `reportService.queue(ReportType.REGIONAL_ROLLUP, event.regionId(), event.requestedBy())` and one log
  line. A separate method, not a branch on event type.

### reports — service
- `service/ReportService.java` — Sprint 2 cleanup only: `this.eventBus = eventBus;` moved below
  `this.userService = userService;`. No behavioural change.

### tests — new
- `support/FailingRollupSubscriber.java` — **new**. Counts invocations then throws. An
  `AFTER_COMMIT` listener rather than a plain `@EventListener`, unlike its two siblings, because the
  failure has to land in the same dispatch phase as the handler under test. Not a `@Component`.

### tests — modified
- `RegionalRollupIntegrationTest.java` — 4 new tests (11→15). One Sprint 2 test replaced; see the
  trend notes.
- `reports/listener/ReportEventListenerTest.java` — 3 new tests (2→5).
- `EventDeliveryIntegrationTest.java` — 2 new tests (6→8), placed there on the Sprint 2 Evaluator's
  instruction.
- `support/RecordingRollupSubscriber.java` — `@Component` removed.

### untouched
All of `activities`, `programmes`, `staff` and `alerts`. `EventBusConfiguration` shows no diff.
`ReportRoutes`, `ReportService.queue` and every DTO unchanged. No new entity, table, endpoint, error
code, enum value or schema change in this sprint.

## Conditional Pass Cleanups

One item. **This was the final sprint, so no downstream Generator will collect it — it needs a human.**

1. **`EventDeliveryIntegrationTest.programmeCloseIsUnaffectedByTheRollupHandler` — a test weaker than
   its name.** It asserts every report at scope `store-001` is a `STORE_SUMMARY`, which cannot detect
   the cross-over it is named for: a rollup report is scoped to a *region* id, so a stray
   `REGIONAL_ROLLUP` for `region-north` would slip past. AC 7 is nonetheless fully covered by
   `ReportEventListenerTest.theTwoHandlersDoNotCrossOver`, which asserts the total row count and both
   scopes together. Fix: capture `findByScopeId("region-north").size()` before the close and assert it
   unchanged. One line.

**Sprint 2's cleanup is closed** — the constructor assignment order in `ReportService`. That makes the
standing pattern 6 for 6: every cleanup assigned to a following sprint has been closed by it.

## Quality Trend Notes

Eighth sprint logged, third feature complete. Scores: **92, 98, 96, 98, 99, 97, 99, 99**. Iterations:
**1, 1, 1, 1, 1, 1, 1, 1**.

* **The Sprint 2 prediction is settled, and this one settled straight.** `REQUIRES_NEW` was pinned by
  nothing at Sprint 2's close, and the prediction was that Sprint 3's persisted-row assertion would be
  what pinned it. Probe run, scoped to the new handler only: four failures across two integration
  classes, every one `Expected size: 1 but was: 0`. **Six predictions made, six settled.** The
  mechanism is now proven twice over — a falsifiable prediction written into a run log, read by the
  next agent, settled by a probe.
* **The probe's most instructive result is the class that *passed*.** `ReportEventListenerTest` stayed
  green through the entire probe, all five tests, because it invokes the handler directly and can
  never observe an annotation failure. Sprint 5 of the previous feature established that an end-to-end
  test is not automatically the stronger test; this is the clean converse, and the pair now forms a
  complete rule worth writing into `how-to-test`: **direct-invocation listener tests prove the handler
  body, integration tests prove the annotations, and neither substitutes for the other.** Every
  silent-failure mode this harness has hit lives in the gap between them.
* **Four Planner defects across one feature, and the shape has changed twice.** Sprint 1: a clock
  instant contradicting `how-to-test`. Sprint 2: a file list omitting an unavoidable file, and a
  Scenario 6 proposing a test that would pass vacuously. Sprint 3: an AC 8 clause ("no previously
  passing test was modified") incompatible with Sprint 2's own Scenario 5, which deliberately created
  a test asserting a temporary absence. The first is stale-value drift; the last two are the Planner
  **specifying the wrong test**. Previous features produced only the first kind. Concrete carries for
  `sprint-decomposition`: defer fixture constants to `how-to-test`; when a criterion covers silent
  wiring, the THEN must name an observable side effect; and a blanket "no test modified" clause cannot
  sit in the final sprint of a feature whose middle sprint asserts an absence on purpose.
* **The Generator corrected its own earlier sprint's defect, unprompted.**
  `RecordingRollupSubscriber` was written `@Component` in Sprint 2; the `support` package is under the
  app's scan root, so it landed in every `@SpringBootTest` context in the suite.
  `FailingStatusSubscriber`'s javadoc documents exactly that hazard and had not been read. Nothing
  failed — a recorder that only appends is untidy, not broken — but the same annotation on
  `FailingRollupSubscriber`, which throws on every rollup, would have handed a failing listener to
  every integration test in the suite. Caught while reading fixture conventions for the new class,
  fixed in the old file, reasoning written into both javadocs. **Two sprints running, the Generator has
  found something the Evaluator did not.**
* **Seven consecutive sprints with zero behavioural defects in Generator-written production code.**
  This feature contributed three findings total: a misleading comment, a dead `LinkedHashMap`, a
  redundant assertion, a constructor assignment order, and one weak test. Not one was a behavioural
  bug.
* **A verdict field carrying no information for eight sprints.** Every sprint has closed CONDITIONAL
  PASS; the harness has never issued a clean PASS. `evaluation-criteria` §5.4 routes any "tiny issue"
  to CONDITIONAL and §5.5 grants PASS only when none exists, so for a reviewer who can always find one
  nit, PASS is unreachable. Operationally free — the Monitor routes both identically — but it means
  the score trend has been doing all the signalling for eight sprints while the verdict repeated
  itself. The Evaluator raised this in its own §7 rather than waiting to be asked, which is the right
  instinct. **A decision for the human: raise the CONDITIONAL bar, or drop the distinction.**
* **Documentation drift is now the harness's dominant debt and it has crossed into causing defects.**
  Five items, one closed. `app-context` §5 says "Three events exist. This is the whole list." while a
  fourth is live in the code; §3 says nine endpoints where there are eleven. Sprint 5 of the previous
  feature already showed §3's staleness propagating a wrong assertion into a contract. Every agent is
  told to treat this file as authoritative, and the next Planner reading it will conclude
  `REGIONAL_ROLLUP_REQUESTED` does not exist. **This is the single highest-value maintenance action
  available and it has been deferred across two features.**
* **No prediction for a following sprint — this is the last.** The standing pattern that final-sprint
  cleanups become human backlog held again: this feature queues four items where the previous two
  queued one each. Sprint 1's log recommended planning a buffer sprint to absorb final-sprint findings;
  that recommendation is now supported by three features of evidence and remains unimplemented.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts — `spec.md`, `sprint-3-contract.md`, `generator-summary.md`, `evaluator-feedback.md`, `sprint-2-run-log.md`, 3 agent files, 7 skill files, `CLAUDE.md` | 11,233 |
| Source written — `ReportEventListener` | 381 |
| Tests written — `FailingRollupSubscriber` plus 4 modified test/support classes | 2,449 |
| Read for context — `FailingStatusSubscriber` | 276 |
| **Total words** | **14,339** |

`14,339 * 1.3 * 1 = 18,641` → **~18.6k tokens**

Third consecutive decline within the feature (28.7k → 21.7k → 18.6k) and the cheapest sprint across
all three features. The curve has the same shape as the previous feature's (29.6k → 26.7k → 25.2k) but
steeper, for an identifiable reason: this sprint wrote 381 words of production code and read one file
for context. Excludes Maven output, the `REQUIRES_NEW` probe and the post-restore verification build.

**Feature total: `28.7k + 21.7k + 18.6k` ≈ 69.0k tokens across three sprints.** Against SLA breach
alerting at ~81.5k for three and shift handover at ~39.5k for two. Per sprint: ~23k here, ~27k for SLA
breach, ~20k for shift handover.

## Feature Retrospective — Regional Rollup Report

Delivered against the original request in full. `GET /api/reports/region/{regionId}` returns activity
completion rates region-wide and per store, overdue counts broken down by `TaskCategory` with the
zeroes kept, and the complete list of blocked activities — and each successful call raises
`REGIONAL_ROLLUP_REQUESTED`, which the reports module's own listener turns into a `PENDING`
`REGIONAL_ROLLUP` `Report` row on a separate transaction.

Built with **one new event, no new entity, no schema change, no new enum value and two new error
codes**, both produced by the existing `NotFoundError.of(...)` factory. The `Report` entity already
carried `reportType`, `scopeId` and `status`, and `ReportType.REGIONAL_ROLLUP` already existed in the
enum — so a regional report is an existing row shape with a region id in `scopeId`.

Two design decisions carried the feature, both taken at planning time and both recorded in `spec.md`
rather than discovered later:

* **Staff is the authority for region membership.** StoreOps has no `Store` entity, so
  `users.region_id` is the only record of which stores a region contains. The rollup resolves the store
  set through `UserService`, then reads activities one store at a time through `TaskService`. A single
  query grouping activities by region would need a join across `users` and `tasks`, or a copy of the
  region id on `TaskEntity` — and the boundary rules forbid both. **The N+1-shaped read is the correct
  answer here**, which is worth stating plainly: a reviewer optimising for performance rather than
  architecture would call it a defect.
* **The event bus separates answering from recording.** Publisher and subscriber are both `reports`,
  which is unusual for a domain event and deliberate: the rollup is a read, the report record is a
  write, and a failure to record must not fail the caller's `GET`. That property is not asserted by
  argument — `aFailingSubscriberIsContained` returns 200 while a subscriber throws, and the row still
  lands.

Two behaviours a reader of the original request might expect are **not** present, both declared rather
than quietly omitted: reports are created `PENDING` and nothing generates them, so "triggers a Report
record" means recorded and readable, not rendered; and the endpoint has no authorisation, so
`requestedBy` is an unverified query parameter defaulting to `api`. Neither was requested. Restricting
the rollup to `REGIONAL_MANAGER` is the obvious follow-up and would be a sprint of its own.

**Four items now need a human**, since no sprint follows — listed in priority order in
`sprint-3-evaluator-feedback.md` §7. The first two matter most: `app-context` §5 and §3 are both stale
in ways that demonstrably propagate into contracts, and the missing `Store` entity is the one open item
that can change this feature's behaviour rather than merely its tidiness.
