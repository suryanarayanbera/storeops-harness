# Evaluator Feedback — Sprint 2: After-Commit Delivery of Bulk-Published Events

## 1. VERDICT

**CONDITIONAL PASS**

Every automated gate and every manual hard gate passed. All six acceptance criteria are met by
tests that assert the side effect, both findings carried from Sprint 1 are fixed and verified, and
the sprint's central claim — that these tests catch a silent regression — was demonstrated by
mutation rather than asserted.

One cleanup remains: a mixed assertion idiom in `BulkStatusSubscriberIsolationTest`. It is
test-only, carries no behavioural risk, and is one line. Per `evaluation-criteria` §5.4 a tiny
issue is a CONDITIONAL PASS rather than a PASS, and the bar is held where Sprint 1's dead constant
put it.

**This is the final sprint, so nothing downstream will pick that cleanup up.** It is recorded here
and must be carried into the run log as outstanding work for a human, not as harness-managed debt.

## 2. SCORE

`A 40/40 · B 35/35 · C 23/25 = 98`

| Dimension | Score | Reasoning |
| --- | --- | --- |
| A. Contract fulfilment | 40/40 | Six of six ACs met with side-effect assertions, plus one test beyond contract. Both Sprint 1 findings fixed and independently re-verified. |
| B. Architectural compliance | 35/35 | ArchUnit 12/12, Checkstyle 0, SpotBugs clean. No fourth event, no `fallbackExecution`, no `NotificationService` under `activities`, `ErrorHandler` bean intact, `AlertEventListener` untouched. The Sprint 1 error-contract breach is closed. |
| C. Test quality | 23/25 | Every absence assertion has a positive counterpart; invocation counts prevent vacuous passes; discrimination proved by mutation. −1 mixed count idiom (Finding 1). −1 after-commit subscriber isolation remains uncovered while publish-time isolation is (§5). |

## 3. GATE RESULTS

`./mvnw clean test` — exit code 0, **BUILD SUCCESS**.

| Dim | Gate | Result | Evidence |
| --- | --- | --- | --- |
| A | JUnit | **PASS** | `Tests run: 134, Failures: 0, Errors: 0, Skipped: 0` (125 at Sprint 1 close, +9 as declared) |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **PASS** | `Tests run: 12, Failures: 0, Errors: 0` |
| B | Checkstyle | **PASS** | `You have 0 Checkstyle violations.` |
| B | SpotBugs | **PASS** | `spotbugs-check` completed, no bugs reported |
| C | `jacoco:check` bundle and per-class | **PASS** | `TaskBulkStatusService` unchanged at line 38/38, branch 14/14 |

**Scope check.** `git status --porcelain` matches the declared file list. `git diff --stat HEAD -- src/`
shows only `TaskRoutes.java` and `TaskRoutesTest.java` against the last commit, both accounted for
by Sprint 1 and Sprint 2 respectively. Files the contract put out of bounds are confirmed untouched:
`AlertEventListener`, `NotificationService`, `EventBusConfiguration`, `TaskStatusChangedEvent`,
`TaskService`, `TaskBulkStatusService`, `TaskRoutes` body. `TaskBulkStatusService` reporting byte-
identical coverage to Sprint 1 corroborates that it was not edited.

Guardrail greps, all clean: no `fallbackExecution` anywhere; no `NotificationService` reference
under `activities`; `ErrorHandler` bean still wired into the multicaster in
`EventBusConfiguration:40-48`; `bulkUpdateStatus` still carries no `@Transactional`, still injects
no `EventBus`, still catches `AppError` only.

### Manual hard gates

| Gate | Result | Evidence |
| --- | --- | --- |
| A required event was never published | **PASS** | Delivery now proved at the far end: `ESCALATION` rows read back through `GET /api/notifications` |
| Event wiring that fails silently | **PASS** | Publisher `@Transactional`, listener keeps `AFTER_COMMIT` + `REQUIRES_NEW`, `ErrorHandler` intact — and now regression-guarded, see below |
| Business logic in a route | **PASS** | No route changed this sprint |
| Criteria covered only by a status-code assertion | **PASS** | Every criterion asserts a `Notification` row, a database status, or a subscriber invocation count |
| Absence assertions with no positive counterpart | **PASS** | Checked individually, see below |
| A dropped acceptance criterion | **PASS** | 6 of 6 implemented |
| Negative test per new service method | **N/A** | No new service method this sprint |

### Absence assertions, checked individually

This is the gate most likely to be quietly failed by a delivery sprint, so each one was traced
rather than counted:

* `partialFailureBatchStillDelivers` — absence for `task-999` sits in the *same test* as presence
  for `task-002`. Self-discriminating.
* `refusedTransitionRaisesNoAlert` — not a bare absence: it pins the surviving row positively
  (`$` hasSize(1), `id == notification-001`, `alertType == SHIFT_HANDOVER`) as well as asserting no
  `ESCALATION` for `task-003`. Correctly filters on both fields, since the seeded alert also
  references `task-003`.
* `bulkCompletionRaisesNoAlert` — a pure absence, but its counterpart
  `bulkBlockRaisesOneAlertPerActivity` raises an alert for the *same recipient*, `user-004`. Same
  recipient, one alerted and one not, is a genuine discriminator.

### Verification that the new tests can fail

The Generator's mutation result was reproduced from the artefacts rather than taken on trust:
removing `@Transactional` from `TaskService.update` leaves the 41 Sprint 1 bulk tests fully green
and turns 4 of the 7 Sprint 2 tests red, each on a missing `ESCALATION` alert
(`bulkBlockRaisesOneAlertPerActivity:85`, `partialFailureBatchStillDelivers:109`,
`eachActivityCommitsAndDeliversOnItsOwn:172`, `theRealListenerIsUnaffectedByTheFailingOne:86`).
`TaskService.java` was restored and byte-compared.

That is the sprint's whole justification, and it holds: the gap the Sprint 1 evaluation flagged as
the largest carried risk is now closed by a test that demonstrably bites.

### Sprint 1 findings, re-verified independently

| Finding | Status | Evidence |
| --- | --- | --- |
| 1 — null list element escaped as a raw NPE / 500 | **FIXED** | The Sprint 1 probe was re-run unchanged against the full stack: `PROBE_STATUS=400`, body `{"code":"VALIDATION_FAILED", ..., "details":["updates[0]: must not be null"]}`. Previously `500` / `INTERNAL_ERROR`. Held by two route-slice tests that also assert the service is never reached. |
| 2 — `MAX_BATCH_SIZE` dead constant | **FIXED** | `BulkStatusUpdateRequest.java:28-32` — `@Size(max = MAX_BATCH_SIZE)` with a `{max}` message. The constant now drives behaviour, and the pre-existing size test passing unchanged confirms the interpolated message is identical. |

## 4. FINDINGS

### Finding 1 — cleanup. Mixed invocation-count idiom couples an assertion to `@DirtiesContext`

`src/test/java/com/cognizant/storeops/BulkStatusSubscriberIsolationTest.java:89` — Test robustness.
`throwingSubscriberDoesNotBreakTheBatch:72` captures a baseline and asserts `before + 2`, while
`theRealListenerIsUnaffectedByTheFailingOne:89` asserts the absolute `isEqualTo(1)`.

Both pass today, because `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` gives each method a fresh
context and therefore a fresh `FailingStatusSubscriber`. But that annotation is there for seed-data
freshness, not for subscriber-count freshness, and the suite now carries 17 context rebuilds across
three classes — relaxing it for build time is a plausible future change. The relative assertion
survives that; the absolute one breaks, and one of the two siblings is defending against something
the other assumes away.

**Required change.** Make line 89 relative, matching its sibling:

```java
final int before = failingStatusSubscriber.invocationCount();
// ... perform the request ...
assertThat(failingStatusSubscriber.invocationCount()).isEqualTo(before + 1);
```

No behavioural risk and no bearing on any acceptance criterion. Listed because
`evaluation-criteria` §5.4 asks for tiny issues to be named rather than waved through.

## 5. OBSERVATIONS — not findings, no change required

* **Publish-time subscriber isolation is now covered; after-commit isolation is not.** The two run
  through different machinery: a plain `@EventListener` throwing goes through
  `SimpleApplicationEventMulticaster` and is absorbed by the `ErrorHandler` bean, whereas a
  `@TransactionalEventListener(AFTER_COMMIT)` throwing goes through transaction synchronisation
  instead. `FailingStatusSubscriber` exercises the first, which is exactly what the contract's last
  guardrail frames AC5 around, so the sprint is complete as specified. Worth knowing that the real
  subscribers — `AlertEventListener` and `ReportEventListener` — are both after-commit, so the more
  operationally likely failure shape is untested. A candidate for a future sprint, not a gap in
  this one.
* **The AC5 fixture deviation is accepted.** A separate `FailingStatusSubscriber` rather than
  extending `FailingSubscriber` is the better call: the existing fixture only ever sees its own
  `ProbeEvent`, and extending it would make it throw inside every test that imports it while
  leaving one `invocationCount()` serving two unrelated assertions.
* **Routed, not scored: the baseline `FailingSubscriber` javadoc is wrong.** It claims it is "not
  picked up by component scanning"; because `com.cognizant.storeops.support` sits under the
  `@SpringBootApplication` root even on the test classpath, `@Component` there lands in every
  `@SpringBootTest` context. Harmless today — nothing publishes `ProbeEvent` outside its own test —
  and `how-to-review` §1 forbids scoring baseline gaps, so it is not a finding. The Generator was
  right to leave it and flag it. Recommend a Planner-level ticket, since the same trap cost this
  sprint a debug cycle and will cost the next one too.
* **Context-rebuild cost is accumulating.** Three classes now use
  `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` for 17 rebuilds. Still comfortable — the three classes
  run in 3.6 s, 1.5 s and 4.1 s — but the pattern does not scale indefinitely, and Finding 1 is a
  small instance of the coupling it creates.

## 6. FEATURE-LEVEL CLOSE

Both sprints of the shift handover bulk update are now complete. The feature ships:
`PATCH /api/tasks/bulk-status` with per-activity independence, one transaction per activity, five
mapped error outcomes, and `TaskStatusChangedEvent` published per real transition and proved to
reach the alerts module after commit. No new event, no new entity, no new `AppError` subtype, no
change to the single-activity path, and no module boundary crossed outside the event bus.
