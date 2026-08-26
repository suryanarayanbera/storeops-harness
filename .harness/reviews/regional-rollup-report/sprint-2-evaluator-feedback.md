# Evaluator Feedback: Sprint 2 — Publish REGIONAL_ROLLUP_REQUESTED

## 1. VERDICT

**CONDITIONAL PASS**

All automated and manual hard gates pass. One cosmetic cleanup remains, in §4. Per
`evaluation-criteria` §5.4 that is a CONDITIONAL PASS, not a PASS — but it should be read as the
strongest sprint the harness has produced on Dimension C, for the reason set out below.

## 2. SCORE

**A 40/40 · B 34/35 · C 25/25 = 99**

* **A — Contract fulfilment (40/40).** All seven acceptance criteria have a test asserting their
  THEN. The AC 6 deviation is scored at full credit and is discussed in §3.
* **B — Architectural compliance (34/35).** Every gate green; the event payload is decoupled, the
  publisher is transactional, `EventBusConfiguration` untouched, no listener added early. Minus one
  for finding 1.
* **C — Test quality (25/25).** First full marks awarded on this dimension. The Generator did not
  merely satisfy AC 6 — it identified that the contract's own suggested method would pass vacuously,
  implemented a behavioural proof instead, ran a mutation probe, and pasted the failing output into
  the summary. That is the practice the run logs have been asking for since Sprint 3, executed
  unprompted and against the contract's letter.

## 3. GATE RESULTS

`./mvnw clean test` → **BUILD SUCCESS**, exit code 0.

| Dimension | Gate | Result |
| --- | --- | --- |
| A | JUnit | **PASS** — `Tests run: 238, Failures: 0, Errors: 0, Skipped: 0`. 230 at Sprint 1 close, +8 (ReportServiceTest 17→21, RegionalRollupIntegrationTest 7→11). |
| B | `ModuleBoundaryTest` | **PASS** — `Tests run: 12, Failures: 0, Errors: 0`. Rule 3b matters most here and passed: the new event lives in `shared` and depends on no module. |
| B | Checkstyle | **PASS** — `You have 0 Checkstyle violations.` |
| B | SpotBugs | **PASS** — no reported bugs, including on the new `Collections.unmodifiableMap(new LinkedHashMap<>(…))` copy in a record component. |
| C | `jacoco:check` | **PASS** — no rule violations. |

### Scope check

Every Sprint 2 path in `git status --porcelain` appears in `generator-summary.md` §2. **No undeclared
changes.**

One file was modified that the contract's file list did not name:
`reports/listener/ReportEventListenerTest.java`. The Generator declared it explicitly and gave the
reason — it constructs `ReportService` directly, so the constructor gaining a sixth parameter broke
its compilation, and the build could not have gone green without it. No assertion was changed. **This
is a contract defect, not a Generator defect:** a sprint that changes a constructor necessarily
touches every direct caller, and the Planner's file list should have said so.

### Manual hard gates

| Gate | Result | Evidence |
| --- | --- | --- |
| Missing events | **PASS** | The contract requires `REGIONAL_ROLLUP_REQUESTED` and it is published. `ReportService.regionalRollup` ends with `eventBus.publish(new RegionalRollupRequestedEvent(regionId, requester, storeIds.size(), now))`, and `ReportServiceTest.regionalRollupPublishesOneEvent` asserts exactly one event with the full payload. |
| Silent event wiring | **PASS, and proved rather than inspected** | `@Transactional` is present on `regionalRollup` (`ReportService.java:132`). Its *effect* is pinned by `RegionalRollupIntegrationTest.theRollupEventIsDeliveredAfterCommit`, and the Generator's probe run confirms the test fails with `Expected size: 1 but was: 0` when the annotation is removed. `EventBusConfiguration` shows no diff. No listener exists yet, so the `REQUIRES_NEW` half of the rule is Sprint 3's. |
| Publishing through `EventBus` | **PASS** | Constructor takes the project's `EventBus` interface; no `ApplicationEventPublisher` anywhere in `reports`. |
| Payload carries no module type | **PASS** | `ReportServiceTest.rollupEventTypeAndPayloadAreDecoupled` asserts the record components are exactly `String, String, int, Instant` via `getRecordComponents()` — an assertion that survives someone later "improving" a field to `TaskCategory`. |
| Logic in routes | **PASS** | `ReportRoutes` unchanged this sprint apart from Sprint 1's binding. |
| Criteria proved only by a status code | **PASS** | AC 5 asserts `findByScopeId("region-north")` is empty, AC 6 asserts a delivered event, AC 4 asserts an empty bus. No criterion rests on a status. |
| Negative tests | **PASS** | `rejectedRollupPublishesNothing` covers all three rejection paths at publish level; `aRejectedRequestDeliversNoEvent` covers two of them at delivery level. |
| Absence assertions with no positive counterpart | **PASS** | This was the risk in AC 4 and AC 5, both of which are pure absence assertions — and both are paired. `rejectedRollupPublishesNothing` sits beside `regionalRollupPublishesOneEvent` on the same bus, and `noReportRecordIsQueuedYet` sits beside `theRollupEventIsDeliveredAfterCommit`, which proves dispatch is working while the table stays empty. Neither can pass because delivery is broken. |
| New event has a delivery test | **PASS, with an instruction for Sprint 3** | `evaluation-criteria` §4 names `EventDeliveryIntegrationTest` specifically. Delivery is proved this sprint, but in `RegionalRollupIntegrationTest` and against a test subscriber, because no production subscriber exists until Sprint 3. That is the correct reading of the gate for this sprint. **Sprint 3 must add the production-subscriber proof, and `EventDeliveryIntegrationTest` is where the skill says it belongs** — that file already holds the `PROGRAMME_CLOSED` → `STORE_SUMMARY` equivalent (`closedProgrammeReachesReportsModule`). |
| Invented domain vocabulary | **PASS** | `REGIONAL_ROLLUP_REQUESTED` is the fourth event and was authorised at planning time in `spec.md`, with the `app-context` §5 update flagged as a human action. No new enum value, error code or route. |

## 4. FINDINGS

One, cosmetic. Fold it into Sprint 3.

1. `src/main/java/com/cognizant/storeops/reports/service/ReportService.java:66-71` — Readability.
   The constructor now assigns in the order `reportRepository, taskService, projectService, eventBus,
   userService, clock`, while both the field declarations and the parameter list read
   `… projectService, userService, eventBus, clock`. `eventBus` was inserted one line too early. No
   defect follows — the names match on both sides of every assignment, so nothing is mis-wired — but
   a six-field constructor whose assignment order matches neither the fields nor the parameters is
   exactly the shape a reader has to check twice for a copy-paste bug. Move `this.eventBus = eventBus;`
   below `this.userService = userService;`.

### Noted, not required

`RegionalRollupIntegrationTest.overdueCategoryKeysKeepTheirOrder` asserts an exact serialised
substring, so it is coupled to Jackson emitting no spaces and to the map never gaining the
`UNCATEGORISED` key. Both hold — the key only appears when an overdue activity has a null category,
which no seed row does — and the Generator's own comment explains why a parsed map would hide the
guarantee being tested. Accepted as written. If a future sprint seeds a null-category overdue
activity, this is the test that will break, and it should be updated rather than loosened.

## 5. ON THE AC 6 DEVIATION

Recorded here because the Evaluator scopes to the contract, and the contract was the weaker document.

`sprint-2-contract.md` Scenario 6 offered reflection on the method's annotations or an
`isActualTransactionActive()` check from a stub, with the THEN "`@Transactional` is present on the
method (or the class)". A reflection assertion tests the source text and passes whether or not the
annotation does anything. `sprint-1-run-log.md` had already predicted the consequence: the annotation
would be deletable with Sprint 2's suite green.

The Generator implemented neither option, built `RecordingRollupSubscriber` on
`@TransactionalEventListener(AFTER_COMMIT)` — which Spring skips outright without an active
transaction — and asserted the event arrives. It then deleted the annotation, ran the suite, and
reported the failure verbatim, noting that all 21 `ReportServiceTest` tests still passed, which
confirms the prediction's premise about `RecordingEventBus` directly.

**The intent of AC 6 is met more strongly than its letter, and the Sprint 1 prediction is settled in
Sprint 2 rather than deferred to Sprint 3.** Full credit on Dimension A, and the contract's Scenario
6 is logged as the third Planner defect of this feature.

## 6. READY FOR

Sprint 3 — the production listener and the `REGIONAL_ROLLUP` report record. Two specific carries:
finding 1 above, and the `EventDeliveryIntegrationTest` placement noted in the delivery gate. The
`REQUIRES_NEW` half of the event-wiring rule is untested until Sprint 3 exists, and unlike
`@Transactional` it is not pinned by anything currently in the suite — Sprint 3's Scenario 1 must
assert the persisted row, and a probe deleting `REQUIRES_NEW` should be run to confirm that assertion
bites.
