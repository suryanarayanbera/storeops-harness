# Sprint 1 Run Log

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 1 |
| Goal | Ship `PATCH /api/tasks/bulk-status` end to end in the `activities` module: route, service, DTOs and every error mapping, with each activity updated in its own transaction |
| Modules touched | `activities` (routes, service, dto) |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~19.1k |

Feature: shift handover bulk update. Master spec: `.harness/output/spec.md` (retained — Sprint 2
outstanding).

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — all six automated gates and all eight manual hard gates passed on the first attempt | none required; two non-gate findings carried forward |

Gate detail for the trend record: `./mvnw clean test` exit 0. JUnit 125/125 (96 baseline + 29
added), ArchUnit 12/12, Checkstyle 0 violations, SpotBugs clean, JaCoCo bundle and per-class both
passed. `TaskBulkStatusService` landed at 100% line and 100% branch coverage against a 70%/50%
floor.

## Files Changed

### activities — routes
- `routes/TaskRoutes.java` — added the `PATCH /api/tasks/bulk-status` handler; constructor now also
  takes `TaskBulkStatusService`. No existing method touched.

### activities — service
- `service/TaskBulkStatusService.java` — **new.** Per-activity loop; deliberately not
  `@Transactional` and deliberately a separate bean from `TaskService`, so each activity gets its
  own transaction and its own after-commit dispatch.
- `service/TaskService.java` — **not modified** (verified against `git status`).

### activities — dto
- `dto/BulkStatusUpdateRequest.java` — **new.** `@NotEmpty`, `@Size(max = 50)`, `@Valid` cascade.
- `dto/BulkStatusUpdateItem.java` — **new.** `@NotBlank taskId`, `@NotNull status`.
- `dto/BulkStatusUpdateResponse.java` — **new.** `succeeded` / `failed`.
- `dto/BulkStatusFailure.java` — **new.** Per-item `(taskId, code, message, statusCode)`.

### tests
- `activities/service/TaskBulkStatusServiceTest.java` — **new**, 12 tests.
- `activities/routes/TaskRoutesTest.java` — added `@MockitoBean TaskBulkStatusService` plus 7
  bulk-route tests; existing 10 tests unchanged.
- `BulkStatusUpdateIntegrationTest.java` — **new**, 10 tests.

### untouched
`shared`, `alerts`, `programmes`, `staff`, `reports`. No new event, no new listener, no new
`AppError` subtype, no `EventBusConfiguration` change, no entity or `data.sql` change.

## Conditional Pass Cleanups

Two items. Both are required work, not optional: a CONDITIONAL PASS advances the harness to
Sprint 2 rather than triggering a retry, so neither will be picked up by a Generator retry loop.
They must be folded into the Sprint 2 generation pass.

1. **`BulkStatusUpdateRequest.java:27` — a null list element escapes as a raw
   `NullPointerException`.** `@Valid` cascades into a collection but skips null elements, so
   `{"updates":[null]}` clears bean validation, reaches `TaskBulkStatusService.java:96` and answers
   `500 INTERNAL_ERROR` instead of `400 VALIDATION_FAILED`. Breaks the `AppError` error contract
   (`architecture-principles` §5). Fix: constrain the container element to
   `List<@NotNull @Valid BulkStatusUpdateItem>` and add a route-slice test holding it in place.
   Behavioural, but on an edge no acceptance criterion exercises — which is why it cleared every
   gate.

2. **`BulkStatusUpdateRequest.java:30` — `MAX_BATCH_SIZE` is a dead constant.** Referenced only by
   its own javadoc, which claims it is mirrored in the `@Size` annotation; that annotation uses a
   literal `50`. Non-behavioural. Fix: reference the constant from the annotation, or delete it.
   Same declaration as item 1, so one pass covers both.

## Quality Trend Notes

First sprint of this feature and the first entry in `.harness/reviews/`, so there is no prior run
log to compare against. This log is the baseline. Observations worth carrying:

* **No iteration creep to report.** Sprint 1 closed on attempt 1 of 3, leaving the full escalation
  budget unused.
* **No repeated rule failures.** Nothing failed twice because nothing failed once — the finding
  count is 2, both on the same declaration in one DTO, neither caught by any automated gate.
* **The Evaluator's findings both came from manual review, not from the gates.** Worth watching: a
  green build was not sufficient this sprint, and the one real defect was found only by probing the
  running application with a payload no criterion named. If later sprints show the same shape —
  gates green, defects in unexercised error paths — the fix is contract coverage of malformed
  payloads, not more gates.
* **`activities` is the only module touched so far**, so no problem module has emerged. Sprint 2
  crosses into `alerts` for the first time in this feature, which is where the boundary rules and
  the after-commit wiring get their real exercise.
* **Carried risk into Sprint 2:** after-commit delivery on the new bulk publisher path was verified
  by the Evaluator from runtime log output, but no test asserts it — Sprint 1's contract scoped that
  to Sprint 2. Until Sprint 2 Scenarios 1, 2 and 6 land, a regression breaking Spring proxy
  traversal would leave the suite green. This is the single largest reason Sprint 2 must not be
  skipped.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts — `spec.md`, `sprint-1-contract.md`, `generator-summary.md`, `evaluator-feedback.md`, 4 agent files, 7 skill files, `CLAUDE.md` | 10,158 |
| Source and test files written or read — 4 new DTOs, `TaskBulkStatusService`, `TaskService`, `TaskRoutes`, 3 test classes | 4,523 |
| **Total words** | **14,681** |

`14,681 * 1.3 * 1 = 19,085` → **~19.1k tokens**

Excludes Maven build output, which was filtered rather than read in full.
