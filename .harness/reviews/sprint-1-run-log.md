# Sprint 1 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 1 |
| Goal | `PATCH /api/tasks/bulk-status` in `activities`: per-entry independent failure, `207` reporting, one `TaskStatusChangedEvent` per changed activity, batch that commits despite partial failure |
| Modules touched | `activities` (routes, service, dto). No other module's source changed; one cross-module event-delivery test added at the root test package |
| Final Verdict | **CONDITIONAL PASS** |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated Token Cost | ~25,400 tokens |

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — all 5 automated gates and all 9 manual hard gates passed on the first attempt | none required |

Gate detail on the single attempt: `BUILD SUCCESS` in 42.570 s. JUnit `Tests run: 111, Failures: 0,
Errors: 0`; `ModuleBoundaryTest` 12/12; Checkstyle clean; SpotBugs `Error size is 0`; JaCoCo
`All coverage checks have been met.` Test count moved from the 96-test baseline to 111, so the sprint
added 15 tests.

## Files Changed

Copied from `sprint-1-generator-summary.md`, grouped by module and layer.

### activities — source
- `dto/BulkStatusUpdateRequest.java` — new; `@Size(min=1,max=50)` + `@Valid` on entries, null list normalised to empty
- `dto/BulkStatusUpdateItem.java` — new; `taskId` `@NotBlank`, `status` `@NotNull`, typed as `TaskStatus`
- `dto/BulkStatusUpdateSuccess.java` — new; `changed(previous,current)` / `unchanged(task)` factories
- `dto/BulkStatusUpdateFailure.java` — new; `from(taskId, AppError)` flattens the `(code,message,statusCode)` triple
- `dto/BulkStatusUpdateResponse.java` — new; `succeeded` + `failed`, both always present, both defensively copied
- `service/TaskService.java` — added `@Transactional bulkUpdateStatus()` and the private non-transactional `applyHandoverEntry()`; added `BULK_TARGET_STATUSES`; reuses the existing `requireTransitionAllowed`
- `routes/TaskRoutes.java` — added `@PatchMapping("/bulk-status")` returning `207 MULTI_STATUS`

### activities — test
- `service/TaskServiceTest.java` — 6 bulk tests (AC1–6); `seedTask` overload taking an assignee, plus `storedStatus`/`handover` helpers
- `routes/TaskRoutesTest.java` — 6 route tests: the 207 report, the empty-`failed` shape, four whole-request rejections
- `routes/TaskBulkStatusIntegrationTest.java` — new `@SpringBootTest @AutoConfigureMockMvc`; AC7 and AC8

### root — test
- `EventDeliveryIntegrationTest.java` — added `bulkHandoverReachesAlertsModule`

No change to `repository/`, `shared/`, `alerts/`, `reports/`, `data.sql` or the schema. Scope was
verified against `git diff --stat HEAD -- src/`: +392/−1 across 5 modified files, 6 new files, nothing
undeclared.

## Conditional Pass Cleanups

Non-behavioural debts carried forward from `sprint-1-evaluator-feedback.md`. None affects an
acceptance criterion or a gate.

1. `README.md:32` / `README.md:132` — endpoint table has no row for the new endpoint, and the
   "Deliberate gaps" list still advertises `PATCH /api/activities/bulk-status`, a path the approved
   spec rejected. Add the row, delete the gap line.
2. `activities/routes/TaskRoutes.java:78` — javadoc reads `Endpoint 5`; the README's global numbering
   makes it row 10.
3. `activities/service/TaskService.java:207` — the `applied` set is documented as "ids already settled"
   but is populated before the existence lookup and before the already-settled short-circuit. Two
   identical unknown-id entries return 404 then 400; an already-settled activity listed twice returns
   a success plus a 400 for a no-op. Rename to `seen` and reword, or move the `add` after the save.
4. Test gaps, one line each: no all-failure batch (so `207` with an empty `succeeded` is unproven), and
   no entry omitting `status` despite its `@NotNull`.

## Quality Trend Notes

First logged sprint — no `.harness/reviews/` history existed, so this run establishes the baseline
rather than extending a trend. Baselines for the next sprint to be measured against:

* **Iterations:** 1 of 3, no retry consumed. Any sprint needing 2+ is a regression against this.
* **Gate failures:** zero on first attempt across all five automated gates.
* **Test count:** 96 → 111.
* **Score:** `A 40/40 · B 33/35 · C 23/25 = 96`. Both deductions were LLM-assessed, none automated.

Three patterns worth watching, none yet a trend:

* **The contract, not the implementation, produced half the findings.** Findings 3 and 4a trace to
  edge cases `sprint-1-contract.md` never specified — the interaction of duplicate ids with
  already-settled activities, and the always-`207` decision having no criterion that observes an
  all-failure batch. If a second sprint shows the same shape, the fix belongs in the Planner's
  criteria-writing standard, not in Generator feedback.
* **Only `activities` was exercised.** Module-level quality signal for `programmes`, `staff`, `alerts`
  and `reports` is still baseline-only. No problem module identified.
* **Harness defect, flagged for the human, not fixed here.** `.harness/output/spec.md` still ends with
  `STATUS: AWAITING APPROVAL` even though the spec was approved and its only sprint is now closed.
  There is no state marker distinguishing "awaiting approval" from "approved and complete", so a
  re-run of the orchestrator against this `spec.md` would halt at State 1 for an already-approved
  spec. The Monitor's remit does not include editing `spec.md`, so it is left as-is and recorded here.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts read and written (`spec.md`, `sprint-1-contract.md`, `generator-summary.md`, `evaluator-feedback.md`, 4 agent definitions, 7 skill files, `CLAUDE.md`) | 8,117 |
| Declared source and test files written | 4,995 |
| Context-only files read, not changed (`shared/error/*`, `TaskStatusChangedEvent`, `AlertEventListener`, `activities/domain/*`, the three existing activity DTOs, `JpaTaskRepository`, `support/*`, `ModuleBoundaryTest`, `data.sql`, `pom.xml`, `checkstyle.xml`, `README.md`) | 6,426 |
| **Total** | **19,538** |

`19,538 * 1.3 * 1 = 25,399` → **~25,400 tokens**.

Excluded from the basis: Maven build output and surefire reports, which are machine-generated and not
files read as sprint context.
