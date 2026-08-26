# Generator Summary: Sprint 3 — Queue the REGIONAL_ROLLUP report record

`./mvnw clean test` → **BUILD SUCCESS**. 247 tests, 0 failures, 0 errors (238 at Sprint 2 close, +9).
Checkstyle 0 violations, SpotBugs clean, all 12 `ModuleBoundaryTest` rules pass, JaCoCo check passes.

**Feature complete.** `GET /api/reports/region/{regionId}` answers the rollup and records a
`REGIONAL_ROLLUP` report via the event bus.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | The GET produces a persisted PENDING report record | yes | `RegionalRollupIntegrationTest.theGetProducesAPendingReportRecord` |
| 2 | The listener queues a report from the event directly, never `STORE_SUMMARY` | yes | `ReportEventListenerTest.requestedRollupQueuesRegionalRollup`, `.rollupReportIsScopedToRegionAndCorrectlyTyped` |
| 3 | Two calls produce two records with distinct ids | yes | `RegionalRollupIntegrationTest.twoRequestsRecordTwoReports` |
| 4 | A rejected request records nothing | yes | `RegionalRollupIntegrationTest.aRejectedRequestRecordsNothing` |
| 5 | A rolled-back read records nothing | yes | `RegionalRollupIntegrationTest.aRolledBackRollupRecordsNothing` |
| 6 | A failing listener fails neither the request nor the sibling subscriber | yes | `RegionalRollupIntegrationTest.aFailingSubscriberIsContained` |
| 7 | The `PROGRAMME_CLOSED` flow is untouched | yes | `EventDeliveryIntegrationTest.programmeCloseIsUnaffectedByTheRollupHandler`, `ReportEventListenerTest.theTwoHandlersDoNotCrossOver` |
| 8 | The whole gate passes | yes | `./mvnw clean test` → 247/247, five gates green |

Tests written beyond the contract:

* `EventDeliveryIntegrationTest.requestedRollupReachesReportsListener` — the production-subscriber
  delivery proof the Sprint 2 Evaluator instructed be placed here, next to the existing
  `closedProgrammeReachesReportsModule`. This is the test the probe below hits hardest.
* `ReportEventListenerTest.theTwoHandlersDoNotCrossOver` — drives both handlers from one fixture and
  asserts each event produces only its own report type.

## 2. Files changed

### reports — listener
- `listener/ReportEventListener.java` — added `onRegionalRollupRequested`, carrying
  `@TransactionalEventListener(phase = AFTER_COMMIT)` and
  `@Transactional(propagation = REQUIRES_NEW)`, matching `onProgrammeClosed`. Calls
  `reportService.queue(ReportType.REGIONAL_ROLLUP, event.regionId(), event.requestedBy())`. A separate
  method, not a branch.

### reports — service
- `service/ReportService.java` — Sprint 2 cleanup only: `this.eventBus = eventBus;` moved below
  `this.userService = userService;` so the assignment order matches the fields and the parameter list.
  No behavioural change.

### tests — new
- `support/FailingRollupSubscriber.java` — **new**. Counts invocations then throws. An
  `AFTER_COMMIT` listener rather than a plain `@EventListener`, unlike its two siblings: the failure
  has to land in the same dispatch phase as the handler under test, or it proves nothing about whether
  an after-commit sibling still ran. Not a `@Component`.

### tests — modified
- `RegionalRollupIntegrationTest.java` — 4 new tests (11→15); imports `FailingRollupSubscriber` and
  autowires `TransactionTemplate`. One Sprint 2 test replaced, see §4.
- `reports/listener/ReportEventListenerTest.java` — 3 new tests (2→5).
- `EventDeliveryIntegrationTest.java` — 2 new tests (6→8).
- `support/RecordingRollupSubscriber.java` — `@Component` removed. See §5.

### untouched
All of `activities`, `programmes`, `staff` and `alerts`. `EventBusConfiguration` shows no diff.
`ReportRoutes` unchanged — the record is written by the listener, never inline. No new entity, table,
endpoint, error code, enum value or schema change in this sprint.

## 3. The REQUIRES_NEW probe, as instructed

`sprint-2-run-log.md` predicted that `REQUIRES_NEW` would be pinned by nothing, and the Sprint 2
Evaluator carried it forward as an instruction. Probe run: `@Transactional(propagation =
REQUIRES_NEW)` deleted from `onRegionalRollupRequested` only, `onProgrammeClosed` left intact,
nothing else changed.

```
[ERROR] Tests run: 8, Failures: 1 -- in com.cognizant.storeops.EventDeliveryIntegrationTest
[ERROR]   EventDeliveryIntegrationTest.requestedRollupReachesReportsListener:126
Expected size: 1 but was: 0
[ERROR] Tests run: 15, Failures: 3 -- in com.cognizant.storeops.RegionalRollupIntegrationTest
[ERROR]   RegionalRollupIntegrationTest.theGetProducesAPendingReportRecord:192
Expected size: 1 but was: 0
[ERROR]   RegionalRollupIntegrationTest.twoRequestsRecordTwoReports:209
Expected size: 2 but was: 0
[ERROR]   RegionalRollupIntegrationTest.aFailingSubscriberIsContained:260
Expected size: 1 but was: 0
```

Four failures across two classes, every one reading "expected 1 but was 0" — the exact signature the
guardrail describes: the listener runs, the log line prints, and no row exists.

**And the part worth recording: `ReportEventListenerTest` passed all 5 tests throughout the probe.**
It calls the handler directly, so it can never see an annotation failure. That is the honest limit of
a direct-invocation listener test, it is exactly what the Sprint 2 prediction reasoned about, and it
is why AC 1's THEN insists on the persisted row.

Annotation restored; full build re-run green (247/247) before this summary was written.

## 4. One Sprint 2 test was replaced, deliberately

`RegionalRollupIntegrationTest.noReportRecordIsQueuedYet` asserted that a `GET` produced **no**
`REGIONAL_ROLLUP` row, because Sprint 2 published the event with nothing consuming it. Sprint 3's
entire purpose is to make that false. It is replaced by `theGetProducesAPendingReportRecord`, which
asserts the row that now must exist.

Flagging it against AC 8's "no previously passing test was modified to accommodate this feature".
This is not a test bent to fit new code — it is a test whose premise the sprint was contracted to
invalidate, and Sprint 2's contract said so explicitly (its Scenario 5: "this sprint publishes the
event and deliberately does not consume it"). No other existing assertion was touched.

## 5. A Sprint 2 defect found and fixed while reading the fixture conventions

`RecordingRollupSubscriber`, which I added in Sprint 2, was annotated `@Component`. The `support`
package sits under `com.cognizant.storeops`, so the application's own component scan picks it up and
the bean lands in **every** `@SpringBootTest` context in the suite, not only the one that imports it.
`FailingStatusSubscriber` carries a detailed javadoc explaining precisely this hazard; I had not read
that file in Sprint 2 and reproduced the mistake it warns against.

For a recorder the consequence was untidiness rather than breakage, which is why nothing failed. But
`FailingRollupSubscriber` throws on every rollup event, and had I copied the same annotation onto it,
every `@SpringBootTest` in the suite would have inherited a failing listener. `@Component` removed
from the recorder, both new fixtures registered by `@Import` only, and the reasoning written into both
javadocs.

Note for the record: `FailingSubscriber` is still `@Component` while its javadoc claims it is "not
picked up by component scanning". That contradiction is pre-existing, harmless because its
`ProbeEvent` is never published in production, and outside this sprint's scope — but it is what made
the wrong pattern look sanctioned.

## 6. Known gaps

none for this sprint. Two items carry to the human, both pre-declared in `spec.md` rather than
discovered here:

1. **No `Store` entity.** Region membership lives only on `users.region_id`, so a store with
   activities but no staff roster is invisible to the rollup.
2. **`app-context` §5 is now wrong.** It states "Three events exist. This is the whole list."
   `REGIONAL_ROLLUP_REQUESTED` is the fourth and is live in the code as of Sprint 2.
