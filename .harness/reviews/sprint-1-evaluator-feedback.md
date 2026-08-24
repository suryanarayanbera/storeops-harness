# Evaluator Feedback: Sprint 1 — Bulk Status Update for Shift Handover

## 1. VERDICT

**CONDITIONAL PASS**

Every automated gate and every manual hard gate passed. Four findings remain, none of them blocking:
one service-logic wart that contradicts its own javadoc, two documentation inconsistencies, and two
one-line test gaps. Per `evaluation-criteria` §5.4 these are cleanups, not gate failures.

## 2. SCORE

`A 40/40 · B 33/35 · C 23/25 = 96`

* **A — Contract fulfilment 40/40.** All nine acceptance criteria have a test that asserts their THEN.
  Every test method named in `generator-summary.md` was verified to exist at the declared path. The two
  declared deviations are both stricter than or equivalent to the literal wording (see Gate A4).
* **B — Architecture 33/35.** ArchUnit 12/12, Checkstyle and SpotBugs clean, transaction seam correct.
  −2 for Finding 3: the duplicate-entry set does not do what its javadoc says it does.
* **C — Test quality 23/25.** Database state, exact event-payload equality, error codes and absence
  assertions all present and paired with positive counterparts. −2 for Findings 4a and 4b.

## 3. GATE RESULTS

Command: `./mvnw clean test` → **BUILD SUCCESS**, total time 42.570 s.

| Dimension | Gate | Result |
| --- | --- | --- |
| A | JUnit — 96 baseline + sprint additions | **PASS** — `Tests run: 111, Failures: 0, Errors: 0, Skipped: 0` |
| B | `ModuleBoundaryTest` — 12 ArchUnit rules | **PASS** — `Tests run: 12, Failures: 0, Errors: 0` |
| B | Checkstyle (`IllegalThrows`, `NoRawErrorThrows`, `IllegalCatch`) | **PASS** — no violations; build reached `test-compile` |
| B | SpotBugs `effort=Max threshold=Medium` | **PASS** — `Error size is 0 / No errors/warnings found` |
| C | `jacoco:check` bundle 85%/60%, per-class service 70%/50% | **PASS** — `All coverage checks have been met.` |

New test classes confirmed executed: `TaskBulkStatusIntegrationTest` (2), `TaskRoutesTest` 10 → 16,
`TaskServiceTest` 13 → 19, `EventDeliveryIntegrationTest` 6 → 7.

### Manual hard gates (`evaluation-criteria` §2–§4)

| # | Gate | Result |
| --- | --- | --- |
| B1 | A required event was never published | **PASS** — `TaskService.java:230` publishes `TaskStatusChangedEvent` per changed entry. Payload asserted field-for-field by record equality in `TaskServiceTest.bulkUpdateAppliesEveryEntry`, not merely counted. |
| B2 | Event wiring that fails silently | **PASS** — `bulkUpdateStatus` is `@Transactional` (`TaskService.java:181`). `AlertEventListener`, `ReportEventListener` and `EventBusConfiguration` are untouched, confirmed by `git diff --stat HEAD`. Delivery proven live, not assumed, by `TaskBulkStatusIntegrationTest.blockedEntryReachesAlertsModule` and `EventDeliveryIntegrationTest.bulkHandoverReachesAlertsModule`. |
| B3 | Business logic in a route | **PASS** — `TaskRoutes.java:88-92` maps, validates and sets `207`. No enum conditional, no transition check, no partial-failure aggregation. Every rule is in the service. |
| C1 | Criteria covered only by a status-code assertion | **PASS** — no criterion rests on a status code alone. AC1–6 assert stored status, the report contents and event payloads; AC7 reads back through fresh requests; AC9 pairs the 400 with `verify(taskService, never())`. |
| C2 | Absence assertions with no positive counterpart | **PASS** — the `statusEvents()).isEmpty()` in AC5 is backed by the positive payload assertions in AC1/AC2, and the one-alert assertion in `bulkHandoverReachesAlertsModule` is a presence assertion over a batch containing two non-alerting entries. This is exactly the trap `README.md:124-126` warns about, and it is avoided. |
| C3 | Negative test per new service method | **PASS** — four of the six service tests are negative paths (unknown id, refused transition, illegal target, duplicate). |
| C4 | New events need an `EventDeliveryIntegrationTest` | **PASS** — no new event type was introduced, and a delivery test was added regardless. |
| A4 | A dropped acceptance criterion | **PASS** — none dropped. `generator-summary.md` §4 declares `none`, and the claim holds: all 14 named tests exist. |
| B4 | Invented domain vocabulary | **PASS** — no new enum value, no new `AppError` subtype, no new event. The path `/api/tasks/bulk-status` differs from the `/api/activities/bulk-status` named at `README.md:132`, but `spec.md` reasoned about that explicitly and the human approved it, so it is a decision on record rather than an invention. It does leave the README stale — Finding 1. |

### Scope check

`git status --porcelain` and `git diff --stat HEAD -- src/` agree with the declared file list: 5 modified
files (+392/−1), 6 new files, nothing in `src/` that `generator-summary.md` does not name.

`Dockerfile` and `.dockerignore` are untracked and undeclared, but both predate this sprint — they are
in the working tree from before the run and contain no sprint code. Out of scope, not a finding.

## 4. FINDINGS

**1. `README.md:32` and `README.md:132` — endpoint inventory not updated (documentation drift).**
The endpoint table stops at row 9 and has no row for the new endpoint, while the "Deliberate gaps"
list still advertises `PATCH /api/activities/bulk-status` as unimplemented — a path that, per the
approved `spec.md`, will never exist. A reader now finds the feature documented only as a gap, at the
wrong path. Add a row for `PATCH /api/tasks/bulk-status` (`207 Multi-Status`, one
`TaskStatusChangedEvent` per changed activity) and delete line 132.

**2. `src/main/java/com/cognizant/storeops/activities/routes/TaskRoutes.java:78` — endpoint number
collides with the README's scheme.** The javadoc reads `Endpoint 5`, but the numbering in the
surrounding javadocs tracks the README's global table, where 5 is `GET /api/projects`. The new
endpoint is row 10. Renumber to `Endpoint 10`, or drop the number.

**3. `src/main/java/com/cognizant/storeops/activities/service/TaskService.java:207` — the `applied`
set does not hold what its javadoc claims, and two edge cases read oddly as a result.** The parameter
is documented as "ids already settled by this batch", but `applied.add` at line 216 runs *before* the
existence lookup at line 222 and before the already-settled short-circuit at line 223. So an id is
registered even when nothing was settled for it. Two consequences, neither covered by an AC and
neither wrong per the contract, but both worth a decision rather than an accident:

* Two identical entries for an unknown id return `TASK_NOT_FOUND` (404) then `VALIDATION_FAILED` (400)
  — two different codes for byte-identical input.
* An activity already in the requested status, listed twice, returns one success with `changed=false`
  and one 400 "appears more than once" — a batch that changes nothing reports a failure.

Fix either way, but make it deliberate: rename the parameter to `seen` and reword the javadoc to "ids
already seen in this batch", or move `applied.add` to after the successful `save` at line 229 and let
repeated no-ops report as idempotent successes. `TaskServiceTest.bulkUpdateAppliesARepeatedIdOnce`
passes under both.

**4. Test gaps, one line each.**

* **4a. `src/test/java/com/cognizant/storeops/activities/service/TaskServiceTest.java`** — no case in
  which *every* entry fails. `succeeded` is never asserted empty at either layer, so "an all-failure
  batch is still `207` with an empty `succeeded` list" — a deliberate decision in `spec.md` — is
  unproven. The mirror case, an all-success batch with an empty `failed`, *is* covered by
  `TaskRoutesTest.bulkUpdateReportsAnEmptyFailedList`.
* **4b. `src/test/java/com/cognizant/storeops/activities/routes/TaskRoutesTest.java`** — no case for
  an entry that omits `status`. `BulkStatusUpdateItem.status` carries `@NotNull` and `spec.md`'s error
  table maps it to a whole-request 400, but nothing exercises it. AC9's WHEN list does not name it, so
  this is a gap in the spec's coverage rather than a dropped criterion.

## 5. Note for the Monitor

This is the first attempt of Sprint 1. No retry was consumed. The four findings are cleanups that do
not affect the acceptance criteria or the gate results; under the routing rules a CONDITIONAL PASS
closes the sprint, so they should be carried forward as follow-up work rather than sent back as a
Generator retry.
