# Evaluator Feedback: Sprint 3 — Queue the REGIONAL_ROLLUP report record

## 1. VERDICT

**CONDITIONAL PASS**

All automated and manual hard gates pass. One cosmetic cleanup remains, in §4. This is the feature's
final sprint, so per the standing pattern in the run logs that cleanup becomes human backlog rather
than being collected downstream — it is listed accordingly.

## 2. SCORE

**A 40/40 · B 35/35 · C 24/25 = 99**

* **A — Contract fulfilment (40/40).** All eight acceptance criteria have a test asserting their THEN.
  AC 8's "no previously passing test was modified" clause was breached by exactly one test; §5 explains
  why that is a contract defect rather than a Generator miss, and it is scored at full credit.
* **B — Architectural compliance (35/35).** First full marks on this dimension. Both annotations
  present on the new handler and both proved load-bearing by probe; `EventBusConfiguration` untouched;
  the row is written by the listener and never inline; one handler per event type. The unprompted
  `@Component` fix in §6 raised fixture hygiene above where this sprint found it.
* **C — Test quality (24/25).** The instructed probe was run, scoped to the new handler only, and its
  output pasted verbatim. Minus one for finding 1.

## 3. GATE RESULTS

`./mvnw clean test` → **BUILD SUCCESS**, exit code 0.

| Dimension | Gate | Result |
| --- | --- | --- |
| A | JUnit | **PASS** — `Tests run: 247, Failures: 0, Errors: 0, Skipped: 0`. 238 at Sprint 2 close, +9 (ReportEventListenerTest 2→5, RegionalRollupIntegrationTest 11→15, EventDeliveryIntegrationTest 6→8). |
| B | `ModuleBoundaryTest` | **PASS** — `Tests run: 12, Failures: 0, Errors: 0`. |
| B | Checkstyle | **PASS** — `You have 0 Checkstyle violations.` |
| B | SpotBugs | **PASS** — no reported bugs. |
| C | `jacoco:check` | **PASS** — no rule violations. |

### Scope check

Every path in `git status --porcelain` appears in `generator-summary.md` §2. **No undeclared changes.**
Two files were touched beyond the contract's one-file list, both declared with reasons:
`EventDeliveryIntegrationTest` (this Evaluator's own Sprint 2 instruction) and
`RecordingRollupSubscriber` (§6 below).

### Manual hard gates

| Gate | Result | Evidence |
| --- | --- | --- |
| Silent event wiring — both annotations | **PASS, probe-verified** | `ReportEventListener.java:63-65` carries `@TransactionalEventListener(phase = AFTER_COMMIT)` and `@Transactional(propagation = REQUIRES_NEW)`, matching `onProgrammeClosed` at lines 39-41. The probe deleted `REQUIRES_NEW` from the new handler only and produced four failures across two integration classes, all reading `Expected size: 1 but was: 0`. |
| `ErrorHandler` bean intact | **PASS** | `git diff --stat` on `EventBusConfiguration.java` is empty. Independently exercised: `aFailingSubscriberIsContained` returns 200 while `FailingRollupSubscriber` throws, which is only possible with that bean in place. |
| The listener writes only through `ReportService` | **PASS** | The handler body is one `reportService.queue(...)` call and one log line. No `TaskService`, `ProjectService` or `UserService` reference; no figures persisted. |
| No inline write from the route | **PASS** | `grep -cE "queue\|save"` on `ReportRoutes.java` returns 0. The record exists only because the event was delivered. |
| One handler per event type | **PASS** | Two distinct methods, no supertype branch. `ReportEventListenerTest.theTwoHandlersDoNotCrossOver` drives both from one fixture and asserts `findAll()` holds exactly two rows, each with the right type at the right scope. |
| Criteria proved only by a status code | **PASS** | Every one of ACs 1, 3, 4, 5 and 6 asserts persisted rows or an invocation counter. This is the gate the whole sprint turns on and it is met without exception. |
| Absence assertions with a positive counterpart | **PASS** | AC 4 and AC 5 are absence assertions; both sit in a class where `theGetProducesAPendingReportRecord` proves the positive path on the same wiring. `aRolledBackRollupRecordsNothing` is the strongest of these — it can only pass because dispatch works and the rollback suppressed it. |
| New event has a delivery test in `EventDeliveryIntegrationTest` | **PASS** | `requestedRollupReachesReportsListener` added there as instructed in Sprint 2's §6, beside the existing `closedProgrammeReachesReportsModule`. It asserts the production listener's row, not a test double's. |
| Invented domain vocabulary | **PASS** | `ReportType.REGIONAL_ROLLUP` and `ReportStatus.PENDING` both pre-exist in `app-context` §4. No new enum value, error code or route in this sprint. |

## 4. FINDINGS

One, cosmetic. **No sprint follows, so this needs a human.**

1. `src/test/java/com/cognizant/storeops/EventDeliveryIntegrationTest.java` in
   `programmeCloseIsUnaffectedByTheRollupHandler` — Test strength. The assertion reads "every report
   at scope `store-001` is a `STORE_SUMMARY`", which cannot detect the specific cross-over it is named
   for: a rollup report is scoped to a *region* id, so if the programme-close path also queued a
   `REGIONAL_ROLLUP` for `region-north`, this test would still pass. AC 7 is nonetheless fully
   covered — `ReportEventListenerTest.theTwoHandlersDoNotCrossOver` asserts the total row count and
   both scopes together, which does catch it — so this is a redundant test that is weaker than its
   name promises, not a coverage gap. Fix: capture `findByScopeId("region-north").size()` before the
   close and assert it is unchanged after. One line.

## 5. ON AC 8's "NO PREVIOUSLY PASSING TEST WAS MODIFIED"

`RegionalRollupIntegrationTest.noReportRecordIsQueuedYet` was replaced. It asserted that a `GET`
produced **no** `REGIONAL_ROLLUP` row — an assertion Sprint 3 was contracted to make false.

This is a defect in AC 8, not a Generator failure. `sprint-2-contract.md` Scenario 5 created that test
deliberately and said so in terms: "this sprint publishes the event and deliberately does not consume
it". A final-sprint criterion forbidding any test modification is incompatible with a middle-sprint
criterion that asserts a temporary absence. The Generator declared the replacement, quoted the Sprint
2 clause that authorised it, and confirmed no other existing assertion was touched. Scored at full
credit.

**Fourth Planner defect of this feature**, and the second of the "the contract asked for the wrong
test" kind rather than the "the contract named a stale value" kind.

## 6. ON THE @Component FIX

Unprompted, and worth recording as the sprint's best judgement call. `RecordingRollupSubscriber` — the
Generator's own Sprint 2 file — was annotated `@Component`. The `support` package sits under
`com.cognizant.storeops`, so the application's component scan registers it into every
`@SpringBootTest` context in the suite, not only the importing one. `FailingStatusSubscriber` carries a
javadoc explaining exactly this hazard; it had not been read in Sprint 2.

Nothing failed, because a recorder that only appends to a list is merely untidy when over-registered.
The consequence would have been severe one sprint later: `FailingRollupSubscriber` throws on every
rollup event, and the same annotation on it would have handed a failing listener to every
`@SpringBootTest` in the suite. The Generator caught this while reading the fixture conventions for the
new class, fixed the old file, kept both new fixtures on `@Import`, and wrote the reasoning into both
javadocs.

It also correctly identified that `FailingSubscriber` is still `@Component` while its javadoc claims
otherwise, and correctly left it alone as out of scope. Verified: `grep -n "^@Component"` over
`support/` returns that one pre-existing file and nothing else. **That contradiction is what made the
wrong pattern look sanctioned, and it is now human backlog.**

## 7. FEATURE COMPLETE — HUMAN BACKLOG

No sprint remains to collect these. Four items, in priority order:

1. **`app-context` §5 is wrong.** It states "Three events exist. This is the whole list."
   `REGIONAL_ROLLUP_REQUESTED` is the fourth and has been live since Sprint 2. Every agent is
   instructed to treat that file as authoritative, so the next Planner will conclude this event does
   not exist. `app-context` §3's endpoint count is also stale — it says nine, there are now eleven.
2. **No `Store` entity.** Region membership lives only on `users.region_id`, so a store with
   activities but no staff roster is invisible to the rollup. Declared at planning time in `spec.md`,
   never resolved. This is the one item that could change the feature's behaviour.
3. **`FailingSubscriber`'s `@Component`/javadoc contradiction** (§6).
4. **Finding 1** above.

### A governance observation for the human

All eight sprints this harness has run have closed **CONDITIONAL PASS**; it has never issued a clean
PASS. `evaluation-criteria` §5.4 routes any "tiny issue" to CONDITIONAL, and §5.5 gives PASS only when
there are none at all — so on a rubric where a reviewer is always able to find one nit, PASS is
effectively unreachable and the two verdicts carry the same routing consequence. That has cost nothing
operationally, because the Monitor treats both identically. But it means the verdict field has been
carrying no information for eight sprints, and a scoring trend of 92→99 is doing all the actual work.
Worth deciding deliberately: either raise the bar for what counts as a CONDITIONAL-worthy issue, or
drop the distinction.
