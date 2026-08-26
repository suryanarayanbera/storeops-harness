# Generator Summary: Sprint 2 — Publish REGIONAL_ROLLUP_REQUESTED

`./mvnw clean test` → **BUILD SUCCESS**. 238 tests, 0 failures, 0 errors (230 at Sprint 1 close,
+8). Checkstyle 0 violations, SpotBugs clean, all 12 `ModuleBoundaryTest` rules pass, JaCoCo check
passes.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Event record reports its type and carries only primitives | yes | `ReportServiceTest.rollupEventTypeAndPayloadAreDecoupled` |
| 2 | A successful rollup publishes exactly one event with the resolved store count | yes | `ReportServiceTest.regionalRollupPublishesOneEvent` |
| 3 | The default `requestedBy` travels on the event | yes | `ReportServiceTest.regionalRollupPublishesTheDefaultRequester` |
| 4 | A failed rollup publishes nothing | yes | `ReportServiceTest.rejectedRollupPublishesNothing` |
| 5 | No report row is created yet, because nothing listens | yes | `RegionalRollupIntegrationTest.noReportRecordIsQueuedYet` |
| 6 | The publishing method runs inside a transaction | yes | `RegionalRollupIntegrationTest.theRollupEventIsDeliveredAfterCommit` |
| 7 | Every Sprint 1 behaviour still holds | yes | `RegionalRollupIntegrationTest.rollupAggregatesTheSeededRegion` (plus the other 6 Sprint 1 tests and all 17 pre-existing `ReportServiceTest` tests, unmodified) |

Tests written beyond the contract:

* `RegionalRollupIntegrationTest.aRejectedRequestDeliversNoEvent` — the after-commit counterpart to
  AC 4. AC 4 proves nothing was *published*; this proves nothing was *delivered*, through the real
  container, for both 404 paths.
* `RegionalRollupIntegrationTest.overdueCategoryKeysKeepTheirOrder` — asserts the serialised key
  order, turning cleanup 2 below into a tested guarantee rather than a deleted line.

## 2. Files changed

### shared
- `events/RegionalRollupRequestedEvent.java` — **new** record: `regionId`, `requestedBy`,
  `storeCount` (int), `occurredAt`. `eventType()` returns `REGIONAL_ROLLUP_REQUESTED`. No module type
  in the signature.

### reports
- `service/ReportService.java` — constructor gains `EventBus` (now 6 parameters); `regionalRollup` is
  annotated `@Transactional` and publishes `RegionalRollupRequestedEvent` as its last act before
  returning. The response is bound to a local so the event's `storeCount` and the body's cannot drift.
- `dto/RegionalRollupResponse.java` — compact constructor now copies `overdueByCategory` into a
  `LinkedHashMap` wrapped with `Collections.unmodifiableMap` instead of `Map.copyOf`. See cleanup 2.

### tests — new
- `support/RecordingRollupSubscriber.java` — **new** test-scoped subscriber using
  `@TransactionalEventListener(AFTER_COMMIT)`. Writes nothing, so it needs no `REQUIRES_NEW`.

### tests — modified
- `reports/service/ReportServiceTest.java` — 4 new tests (17→21); `setUp` wires a
  `RecordingEventBus`; added a `publishedRollupEvent()` helper. One Sprint 1 cleanup applied.
- `RegionalRollupIntegrationTest.java` — 4 new tests (7→11); imports `RecordingRollupSubscriber` and
  autowires `ReportService`.
- `reports/routes/ReportRoutesTest.java` — `regionalRollupBindsRequestedBy` rewritten. One Sprint 1
  cleanup applied.
- `reports/listener/ReportEventListenerTest.java` — `setUp` updated for the new `ReportService`
  constructor arity. **Not in the contract's file list**; it constructs `ReportService` directly, so
  the constructor change broke its compilation. No assertion changed, still 2 tests.

Nothing under `activities`, `programmes`, `staff` or `alerts` was touched, and
`EventBusConfiguration` was not opened.

## 3. Sprint 1 cleanups — all three closed

1. **`ReportRoutesTest` comment/assertion mismatch — fixed.** The test now performs two requests: one
   with the parameter, verifying `regionalRollup("region-north", "user-001")`, and one without,
   verifying `regionalRollup("region-north", null)`. The comment now sits on the assertion that
   proves it.
2. **`ReportService:203` dead `LinkedHashMap` — fixed by making the ordering real, not by deleting
   it.** The Evaluator asked for a decision either way. I kept the ordering and made
   `RegionalRollupResponse` preserve it, because a report response reads better with categories in
   enum order than in hash order, and it is now asserted on the raw JSON body by
   `overdueCategoryKeysKeepTheirOrder`. `Map.copyOf` gives no iteration-order guarantee;
   `Collections.unmodifiableMap(new LinkedHashMap<>(…))` is an independent snapshot that keeps it.
   `StoreSummaryResponse` was deliberately left on `Map.copyOf` — no criterion covers its key order
   and changing it is outside this sprint.
3. **Redundant `.isNotNaN()` — fixed.** Both occurrences dropped from
   `regionalRollupKeepsEmptyStores`, with a comment recording that `isZero()` is what rules out the
   division-by-zero outcome.

## 4. Deviation from the contract: AC 6 is proved behaviourally, not by reflection

The contract offered two ways to satisfy AC 6 — reflection on the method's annotations, or asserting
`isActualTransactionActive()` from inside a stubbed collaborator — and its stated THEN was
"`@Transactional` is present on the method". **I implemented neither.** Instead
`RecordingRollupSubscriber` subscribes with `@TransactionalEventListener(AFTER_COMMIT)` and
`theRollupEventIsDeliveredAfterCommit` asserts the event arrives.

The reason is the prediction `sprint-1-run-log.md` recorded against this sprint: that
`@Transactional` would be deletable with Sprint 2's suite green, because `RecordingEventBus` records
at publish time and is blind to whether a transaction exists. That prediction was correct about
`RecordingEventBus` and would have held under either method the contract proposed — a reflection
assertion passes whether or not the annotation does anything, so it tests the source text, not the
behaviour.

**Probe run, and the result is recorded rather than asserted.** With `@Transactional` deleted from
`regionalRollup` and nothing else changed:

```
[ERROR] Tests run: 11, Failures: 1 -- in com.cognizant.storeops.RegionalRollupIntegrationTest
[ERROR]   RegionalRollupIntegrationTest.theRollupEventIsDeliveredAfterCommit:150
Expected size: 1 but was: 0
```

All 21 `ReportServiceTest` tests still passed, including every `RecordingEventBus` assertion — which
is the prediction's premise confirmed directly. The annotation was restored and the full build re-run
green (238/238) before this summary was written.

So the wiring is pinned in Sprint 2 rather than waiting for Sprint 3's listener, and AC 6's intent —
that the annotation is present *and load-bearing* — is met more strongly than its letter. Flagging it
as a deviation because the contract named a weaker method and the Evaluator scopes to the contract.

## 5. Known gaps

none
