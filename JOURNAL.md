# Harness Execution Journal

## Run summary

| # | Feature | Sprint | Verdict | Score | Attempt | Tests after | Est. tokens |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | Shift handover bulk update | 1 | CONDITIONAL PASS | 92 | 1 of 3 | 125 | ~19.1k |
| 2 | Shift handover bulk update | 2 (final) | CONDITIONAL PASS | 98 | 1 of 3 | 134 | ~20.4k |

Totals: 2 sprints, 0 FAILs, 0 escalations, 4 of 6 available attempts unused, ~39.5k estimated
tokens. `activities` was the only module modified across the whole feature; `alerts` was exercised
end to end but never edited.

---

## Feature: Add shift handover bulk update | Sprint 1

* **Date/Timestamp:** 2026-08-25 14:26
* **Attempt:** 1 of 3
* **Final Verdict:** CONDITIONAL PASS (Score: 92/100 — A 37/40 · B 32/35 · C 23/25)
* **Goal:** Ship `PATCH /api/tasks/bulk-status` end to end in `activities` — route, service, DTOs and
  every error mapping, with each activity updated in its own transaction.

### 1. Agent Artifacts
* **Planner:** Created `spec.md` and `sprint-1-contract.md`, 11 acceptance criteria
* **Generator:** 11 files touched, 7 new (summary in `sprint-1-generator-summary.md`)
* **Evaluator:** Completed evaluation (`sprint-1-evaluator-feedback.md`)
* **Monitor:** Archived the sprint and wrote `sprint-1-run-log.md`. `spec.md` retained — Sprint 2 still
  outstanding

### 2. Static Analysis & Build Gates
* **Compilation & Unit Tests (`./mvnw clean test`):** PASSED — exit 0, `Tests run: 125, Failures: 0,
  Errors: 0` (96 baseline + 29 added, matching the declared count)
* **Architecture Compliance (`ModuleBoundaryTest`):** PASSED — 12 of 12 ArchUnit rules
* **Code Style (`Checkstyle`):** PASSED — 0 violations
* **Static Analysis (`SpotBugs`):** PASSED — no bugs reported
* **Test Coverage (`JaCoCo`):** PASSED — bundle and per-class rules met. `TaskBulkStatusService` at
  100% line (38/38) and 100% branch (14/14) against a 70%/50% floor

### 3. Evaluator Findings
* **Hard Gates:** 0 violations — six automated gates and eight manual hard gates all passed
* **Extra check the Evaluator ran itself:** confirmed after-commit delivery from DEBUG log output,
  because a test asserting only the HTTP status would have passed either way
* **Non-Blocking Cleanups (CONDITIONAL PASS):**
  * `dto/BulkStatusUpdateRequest.java:27` — a null list element escapes as a raw
    `NullPointerException`. `@Valid` cascades into a collection but skips null elements, so
    `{"updates":[null]}` clears bean validation and answers `500` instead of `400 VALIDATION_FAILED`,
    breaking the `AppError` contract. Behavioural, but on an edge no criterion exercises, which is why
    it cleared every gate.
  * `dto/BulkStatusUpdateRequest.java:30` — `MAX_BATCH_SIZE` is a dead constant; the `@Size`
    annotation uses a literal `50`. Same declaration as the item above, so one pass covers both.
* **Assigned to:** Sprint 2 (both closed there)

### 4. Risk Carried Into Sprint 2
After-commit delivery on the new bulk publisher path was verified by the Evaluator from runtime log
output, but no test asserted it — Sprint 1's contract scoped that to Sprint 2. Until then, a
regression breaking Spring proxy traversal would have left the suite green. The run log named this
"the single largest reason Sprint 2 must not be skipped."

---

## Feature: Add shift handover bulk update | Sprint 2

* **Date/Timestamp:** 2026-08-25 (commit `a49d948`, 16:28)
* **Attempt:** 1 of 3
* **Final Verdict:** CONDITIONAL PASS (Score: 98/100 — A 40/40 · B 35/35 · C 23/25)
* **Goal:** Prove the events published by the bulk handover path really reach `alerts` after commit,
  and do so per activity.

### 1. Agent Artifacts
* **Planner:** `sprint-2-contract.md`
* **Generator:** 5 files touched, 3 new — one production change (the carried DTO fix), the rest tests
* **Evaluator:** `sprint-2-evaluator-feedback.md`
* **Monitor:** Final sprint of the feature, so `spec.md` was archived too and `.harness/output/` left
  empty

### 2. Static Analysis & Build Gates
* **Compilation & Unit Tests:** PASSED — exit 0, `Tests run: 134, Failures: 0` (+9 as declared)
* **Architecture Compliance:** PASSED — 12 of 12
* **Code Style (`Checkstyle`):** PASSED — 0 violations
* **Static Analysis (`SpotBugs`):** PASSED
* **Test Coverage (`JaCoCo`):** PASSED — `TaskBulkStatusService` byte-identical to Sprint 1 (38/38,
  14/14), which corroborated that it was not edited

### 3. Evaluator Findings
* **Hard Gates:** 0 violations. Sprint 1's error-contract breach confirmed closed — and confirmed by
  re-running the Evaluator's *own original probe* rather than by trusting the Generator's summary:
  `500 INTERNAL_ERROR` became `400 VALIDATION_FAILED`
* **How the carried risk was settled:** mutation testing, not assertion counting. With
  `@Transactional` removed from `TaskService.update`, Sprint 1's 41 bulk tests stay fully green while
  4 of Sprint 2's 7 fail. The gap Sprint 1 flagged was real, and the tests that close it demonstrably
  bite
* **Non-Blocking Cleanups:**
  * `BulkStatusSubscriberIsolationTest.java:89` — mixed invocation-count idiom. One assertion uses
    `before + 2`, its sibling an absolute `isEqualTo(1)`. Both pass only because
    `@DirtiesContext(BEFORE_EACH_TEST_METHOD)` rebuilds the context — an annotation that exists for
    seed-data freshness, not subscriber-count freshness. Relax it for build time and the absolute
    assertion breaks while the relative one survives. Test-only, one line.
* **Routed to the Planner, not scored:** the baseline `FailingSubscriber` javadoc claims it is not
  component-scanned; it is, because `com.cognizant.storeops.support` sits under the
  `@SpringBootApplication` root even on the test classpath. Harmless as it stands, but the same trap
  cost this sprint a debug cycle when a new fixture carried `@Component` and began throwing inside
  every `@SpringBootTest` context
* **Assigned to:** nobody — final sprint of the feature, so it needs a human. Still open.

### 4. Scope Deliberately Left Open
Publish-time subscriber isolation is now covered; **after-commit** isolation is not, and both real
subscribers are after-commit. Out of scope for this contract, recorded as future scope rather than
debt.

---

## What the run showed

**Both sprints passed on the first attempt.** Two for two, scores 92 then 98, so 4 of the 6 available
attempts went unused, no `escalation.md` was written, and the Generator never had to act on a FAIL.
The contract is the reason: criteria that name the event, its payload fields, the publisher and the
subscriber leave very little room to get it wrong.

**Both sprints got the same verdict, which is a design flaw.** PASS is effectively unreachable,
because a careful Evaluator always finds something worth listing and `evaluation-criteria` §5.4 sends
any non-empty findings list to CONDITIONAL PASS. FAIL never fired. A three-value verdict carried one
value for both sprints, which left the Monitor's trend notes a flat line to trend against.

**Mid-feature cleanups get fixed; end-of-feature cleanups do not.** Sprint 1's two findings were both
assigned to Sprint 2 and both genuinely closed there. Sprint 2's single finding —
`BulkStatusSubscriberIsolationTest.java:89` — landed on the *final* sprint, so it has no downstream
Generator to collect it and is still open. This feature shows the gap as clearly as it can be shown:
the Evaluator wrote *"Finding 1 must be carried into Sprint 2 as required work"*, and nothing in
`CLAUDE.md` §3 can make that happen, because CONDITIONAL PASS routes exactly like PASS. The fix is in
[REFLECTION.md](REFLECTION.md): have the orchestrator copy open cleanups into the next sprint contract
as required work.

**The automated gates never caught anything.** Zero gate failures across both sprints. Every finding
in this journal came from the Evaluator's manual work — probing the running application with a payload
no acceptance criterion named, reading DEBUG log output, re-running an old probe against a claimed
fix, mutation-testing a `@Transactional` annotation, and reading a test's assertion against its own
name. That is the intended split: gates catch what is present and wrong, manual review catches what is
missing, mistimed, or only apparently tested.

**Cost, and a surprise in it.** ~39.5k estimated tokens for the feature, 19.1k then 20.4k. Sprint 2
cost *more* than Sprint 1 despite writing one line of production code, because its context included
Sprint 1's run log and evaluator feedback on top of everything Sprint 1 had already read. Within a
feature, the planning pass is not what dominates — accumulated review history is.

