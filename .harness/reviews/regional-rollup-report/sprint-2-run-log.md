# Sprint 2 Run Log — Regional Rollup Report

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 2 (second of three in the regional rollup report feature) |
| Goal | Publish `REGIONAL_ROLLUP_REQUESTED` from `ReportService.regionalRollup`: add the `shared/events` record, annotate the publishing method `@Transactional`, and publish exactly one event per successful rollup. No listener. |
| Modules touched | `reports` (service, dto); `shared` (events) |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~21.7k (feature running total ~50.4k) |

`./mvnw clean test` exit 0. JUnit 238/238 (230 at Sprint 1 close, +8), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo check passed.

**First 25/25 on Dimension C in the harness's history.**

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — five automated gates and ten LLM-assessed hard gates passed on the first attempt | none required; one cosmetic cleanup left outstanding |

## Files Changed

### shared — events
- `events/RegionalRollupRequestedEvent.java` — **new** record: `regionId`, `requestedBy`,
  `storeCount` (int), `occurredAt`; `eventType()` returns `REGIONAL_ROLLUP_REQUESTED`. Fourth event
  in the catalogue, authorised at planning time.

### reports — service
- `service/ReportService.java` — constructor gains `EventBus` (six parameters now); `regionalRollup`
  annotated `@Transactional` and publishes the event as its last act before returning. The response
  is bound to a local so the event's `storeCount` and the body's cannot drift apart.

### reports — dto
- `dto/RegionalRollupResponse.java` — compact constructor copies `overdueByCategory` into a
  `LinkedHashMap` wrapped with `Collections.unmodifiableMap` instead of `Map.copyOf`. Sprint 1
  cleanup 2, resolved by making the ordering real rather than deleting it.

### tests — new
- `support/RecordingRollupSubscriber.java` — **new**. Subscribes with
  `@TransactionalEventListener(AFTER_COMMIT)` and records what arrives. Writes nothing, so it needs
  no `REQUIRES_NEW`. This class is the sprint's most consequential file; see the trend notes.

### tests — modified
- `reports/service/ReportServiceTest.java` — 4 new tests (17→21); `RecordingEventBus` wired into
  `setUp`; `publishedRollupEvent()` helper added. Sprint 1 cleanup 3 applied.
- `RegionalRollupIntegrationTest.java` — 4 new tests (7→11).
- `reports/routes/ReportRoutesTest.java` — `regionalRollupBindsRequestedBy` rewritten. Sprint 1
  cleanup 1 applied.
- `reports/listener/ReportEventListenerTest.java` — `setUp` updated for the new constructor arity.
  **Not named in the contract's file list**; declared by the Generator with its reason. No assertion
  changed.

### untouched
All of `activities`, `programmes`, `staff` and `alerts`. `EventBusConfiguration` shows no diff.
`ReportEventListener` unchanged — the production handler is Sprint 3. No new entity, table, endpoint,
error code, enum value or schema change.

## Conditional Pass Cleanups

One item, **assigned to Sprint 3**, which opens `ReportService`'s neighbourhood anyway.

1. **`ReportService.java:66-71` — constructor assignment order.** Assignments run
   `reportRepository, taskService, projectService, eventBus, userService, clock` while both the field
   declarations and the parameter list read `… projectService, userService, eventBus, clock`.
   `eventBus` was inserted one line too early. Nothing is mis-wired — every assignment's names match
   on both sides — but a six-field constructor whose assignment order matches neither the fields nor
   the parameters is the shape a reader has to check twice. Move `this.eventBus = eventBus;` below
   `this.userService = userService;`.

**All three Sprint 1 cleanups are closed.** The `LinkedHashMap` one is worth noting for how it was
closed: the Evaluator asked for a decision either way, and the Generator chose to make the ordering
real and assert it on the raw JSON body rather than delete the ordering code. A cleanup that became a
tested guarantee is a better outcome than a cleanup that became a smaller diff.

## Quality Trend Notes

Seventh sprint logged. Scores: **92, 98, 96, 98, 99, 97, 99**. Iterations: **1, 1, 1, 1, 1, 1, 1**.

* **The Sprint 1 prediction is settled, and it settled the interesting way.** The prediction was that
  `@Transactional` would be deletable with Sprint 2's suite green, because `RecordingEventBus` records
  at publish time and is blind to transactions. The premise was confirmed exactly — with the
  annotation deleted, all 21 `ReportServiceTest` tests still passed — but the conclusion was
  falsified, because the Generator read the prediction, saw that the contract's own Scenario 6 would
  pass vacuously, and built an `AFTER_COMMIT` test subscriber instead. **Five predictions made, five
  settled.** This is the first one a downstream agent acted on to make itself wrong, which is the most
  useful possible outcome and an argument for keeping predictions in the run log where the next
  Generator reads them.
* **The single most transferable artefact of this feature is `RecordingRollupSubscriber`.** The
  harness has had `RecordingEventBus` since Sprint 1 of its life and has been quietly weaker for it:
  it proves *publication*, and every skill file and contract has treated that as proving delivery.
  The distinction is now a class with a javadoc explaining it. `how-to-test` §4 currently says "Did it
  publish? Check with `RecordingEventBus`. Did it arrive? Check in `EventDeliveryIntegrationTest`" —
  correct as far as it goes, but it does not say that a publish-time double cannot detect a missing
  `@Transactional`, which is the exact failure the whole skill exists to prevent. **Recommend adding
  that sentence and naming `RecordingRollupSubscriber` as the pattern.**
* **Third Planner defect of this feature, and the pattern has sharpened.** Sprint 1: a clock instant
  contradicting `how-to-test`. Sprint 2: a file list that omitted a file the change made unavoidable,
  and a Scenario 6 whose suggested method tested source text rather than behaviour. The first two are
  the same failure — the Planner specifying something it should have derived or deferred. The third is
  new and worse: **the Planner proposed a test that would pass vacuously.** A contract can now be a
  source of weak tests, not just of stale constants. Concrete fix for `sprint-decomposition`: when a
  criterion is about wiring that fails silently, the THEN must name an observable side effect, never
  the presence of an annotation.
* **Mutation probing was run unprompted, by the Generator, against its own contract.** Every previous
  probe in this harness was run by the Evaluator or suggested by a run log. This one was the Generator
  deciding a criterion was too weak, strengthening it, and pasting the failing output into its own
  summary as evidence. That is the behaviour the harness was built to produce and it took seven
  sprints to appear. Worth stating plainly in the capstone write-up.
* **Six consecutive sprints with zero behavioural defects in Generator-written production code.** This
  sprint's one finding is constructor assignment order.
* **Documentation drift: still five items, one closed, and the `app-context` §5 debt is now real
  rather than pending.** `REGIONAL_ROLLUP_REQUESTED` exists in the code as of this sprint, and
  `app-context` §5 still says "Three events exist. This is the whole list." Every agent is instructed
  to treat that file as authoritative, and a Planner reading it next feature will conclude this event
  does not exist. This is the second `app-context` section to drift and it is now the highest-priority
  documentation item, ahead of §3's endpoint count. `spec.md` flags it; nothing has actioned it.
* **Prediction for Sprint 3, to be settled in its run log.** `@Transactional(REQUIRES_NEW)` on the new
  listener is currently pinned by nothing, and unlike `@Transactional` on the publisher it will not be
  caught by an event-arrival assertion — the listener will still *run*, it just will not *persist*. So
  Sprint 3's Scenario 1 must assert the persisted row, and a probe deleting `REQUIRES_NEW` must be
  confirmed to fail it. **Settle by running that probe.** The Evaluator's §6 already carries this as an
  instruction, so the mechanism that worked this sprint — a prediction the next agent reads — is in
  place.
* **Sprint 3 is this feature's last, so its findings become human backlog.** Standing pattern across
  three features: cleanups assigned to a following sprint get closed (now 5 for 5), cleanups landing
  on a final sprint do not (2 still open from previous features). Sprint 1's log already flagged this.
  Two items are queued for the human regardless of Sprint 3's outcome: the missing `Store` entity, and
  the `app-context` drift above.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts — `spec.md`, `sprint-2-contract.md`, `generator-summary.md`, `evaluator-feedback.md`, `sprint-1-run-log.md`, 3 agent files, 7 skill files, `CLAUDE.md` | 11,260 |
| Source written — `RegionalRollupRequestedEvent`, `ReportService`, `RegionalRollupResponse` | 1,809 |
| Tests written — `RecordingRollupSubscriber` plus 4 modified test classes | 2,660 |
| Read for context — `FailingSubscriber`, `EventDeliveryIntegrationTest`, `TaskOverdueEvent`, `ReportEventListener` | 937 |
| **Total words** | **16,666** |

`16,666 * 1.3 * 1 = 21,666` → **~21.7k tokens**

Down from Sprint 1's 28.7k, as expected: the planning overhead was paid there, and this sprint read
four small files for context rather than fourteen. Cheapest sprint of the three features so far.
Excludes Maven output, which was filtered rather than read, and excludes the mutation probe and the
post-restore verification build.

**Feature running total: `28.7k + 21.7k` ≈ 50.4k tokens across two sprints.**

## Next Step

Sprint 3 of 3, the final sprint. `spec.md` and `sprint-3-contract.md` remain in `.harness/output/`;
`spec.md` is archived only at the final close.
