# Sprint 2 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 2 (final) |
| Goal | Prove the events published by the bulk handover path actually reach the `alerts` module after commit, and do so per activity |
| Modules touched | `activities` (dto only, carried findings); `alerts` exercised end to end but not modified |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~20.4k (feature total ~39.5k) |

Feature complete: shift handover bulk update, both sprints closed.

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — six automated gates and seven applicable manual hard gates passed on the first attempt | none required; one test-only cleanup left outstanding |

`./mvnw clean test` exit 0. JUnit 134/134 (125 at Sprint 1 close, +9), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo bundle and per-class passed. `TaskBulkStatusService` reported
byte-identical coverage to Sprint 1 (38/38 line, 14/14 branch), corroborating that it was not
edited.

## Files Changed

### activities — dto (the only production change, both carried from the Sprint 1 evaluation)
- `dto/BulkStatusUpdateRequest.java` — `@Valid` moved onto the type argument and joined by
  `@NotNull`, closing the raw-NPE/500 path; `@Size(max = MAX_BATCH_SIZE)` with a `{max}` message,
  so the constant drives behaviour instead of only documenting it.

### tests
- `BulkStatusEventDeliveryIntegrationTest.java` — **new**, 5 tests. Every assertion reads a
  `Notification` back through `GET /api/notifications`.
- `BulkStatusSubscriberIsolationTest.java` — **new**, 2 tests, with `@Import(FailingStatusSubscriber)`.
- `support/FailingStatusSubscriber.java` — **new** fixture, plain `@EventListener` that throws and
  counts invocations.
- `activities/routes/TaskRoutesTest.java` — 2 tests added for the null-element fix (19 total).

### untouched
`AlertEventListener`, `NotificationService`, `EventBusConfiguration`, `TaskStatusChangedEvent`,
`TaskService`, `TaskBulkStatusService`, `TaskRoutes`. As the contract predicted, the sprint needed
no production code of its own. No fourth event, no `fallbackExecution`, no `NotificationService`
under `activities`.

## Conditional Pass Cleanups

One item, **outstanding**. This was the final sprint, so no downstream Generator pass will collect
it: it needs a human.

1. **`BulkStatusSubscriberIsolationTest.java:89` — mixed invocation-count idiom.**
   `throwingSubscriberDoesNotBreakTheBatch:72` asserts `before + 2`; its sibling asserts the
   absolute `isEqualTo(1)`. Both pass today because `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`
   gives each method a fresh subscriber, but that annotation exists for seed-data freshness, not
   subscriber-count freshness. Relax it for build time — plausible, given 17 context rebuilds now
   across three classes — and the absolute assertion breaks while the relative one survives.
   Test-only, no behavioural risk, one line. Fix: capture a baseline and assert `before + 1`.

## Quality Trend Notes

Second and final entry, so a two-point trend is now visible against `sprint-1-run-log.md`.

* **Iteration count flat at 1 of 3, both sprints.** No creep; the escalation budget was never
  touched. Both sprints closed first time.
* **Finding count fell 2 → 1, and severity fell with it.** Sprint 1 produced a behavioural defect
  (a 500 on a malformed payload) plus a dead constant; Sprint 2 produced only a test-robustness nit.
  Score moved 92 → 98.
* **Sprint 1's carried findings were genuinely closed, not just marked closed.** The Evaluator
  re-ran its own original probe rather than trusting the summary: `500 INTERNAL_ERROR` became
  `400 VALIDATION_FAILED`. Worth keeping as harness practice — a carried finding should be
  re-verified by the check that first caught it, not by the fix's own test.
* **The Sprint 1 prediction was correct and is now retired.** That log flagged the unasserted
  after-commit path as "the single largest reason Sprint 2 must not be skipped". Mutation testing
  settled it: with `@Transactional` removed from `TaskService.update`, Sprint 1's 41 bulk tests
  stay fully green while 4 of Sprint 2's 7 fail. The gap was real and is now closed by tests that
  demonstrably bite.
* **The pattern named in Sprint 1 held again: gates green, defects only in unexercised paths.**
  Both sprints found everything worth finding through manual review and probing, nothing through
  the automated gates. Two data points is not a law, but the harness's value in this feature came
  from the Evaluator's judgement, not from the build. If a third sprint ever runs, expect the same
  and budget review time accordingly.
* **`activities` remained the only module modified across the whole feature.** `alerts` was
  exercised end to end but never edited — the event-bus boundary held exactly as designed, and no
  ArchUnit rule was violated once in either sprint.
* **Routed to the Planner, not scored:** the baseline `FailingSubscriber` javadoc claims it is not
  component-scanned; it is, because `com.cognizant.storeops.support` sits under the
  `@SpringBootApplication` root even on the test classpath. Harmless today, but the same trap cost
  this sprint a debug cycle when a new fixture carried `@Component` and began throwing inside every
  `@SpringBootTest` context. Worth a ticket before the next feature.
* **Carried forward as future scope, not debt:** publish-time subscriber isolation is now covered;
  after-commit isolation is not, and both real subscribers are after-commit. Out of scope for this
  contract, worth a sprint of its own.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts — `spec.md`, `sprint-2-contract.md`, `generator-summary.md`, `evaluator-feedback.md`, `sprint-1-run-log.md`, `sprint-1-evaluator-feedback.md`, 4 agent files, 7 skill files, `CLAUDE.md` | 12,301 |
| Source and test files written or read — `BulkStatusUpdateRequest`, `TaskService`, 2 new integration classes, 2 subscriber fixtures, `TaskRoutesTest`, 2 alerts files | 3,357 |
| **Total words** | **15,658** |

`15,658 * 1.3 * 1 = 20,355` → **~20.4k tokens**

Feature total across both sprints: `19.1k + 20.4k` ≈ **39.5k**. Excludes Maven output, which was
filtered rather than read, and the two mutation/probe verification runs.
