# Generator Summary: Sprint 1 — Bulk Status Update for Shift Handover

`./mvnw clean test` is green: 111 tests, 0 failures, 0 errors. Checkstyle, SpotBugs and both JaCoCo
gates (bundle 85%/60%, per-class service 70%/50%) passed.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Clean batch updates every activity, one event each | yes | `TaskServiceTest.bulkUpdateAppliesEveryEntry` |
| 2 | Unknown id fails only its own entry | yes | `TaskServiceTest.bulkUpdateIsolatesAnUnknownId` |
| 3 | Refused transition fails only its own entry | yes | `TaskServiceTest.bulkUpdateIsolatesARefusedTransition` |
| 4 | Target outside DONE/BLOCKED fails only its own entry | yes | `TaskServiceTest.bulkUpdateIsolatesAStatusOutsideTheHandoverPair` |
| 5 | Already in requested status succeeds with no event | yes | `TaskServiceTest.bulkUpdateReportsAnAlreadySettledActivity` |
| 6 | Repeated id applied once, repeat fails | yes | `TaskServiceTest.bulkUpdateAppliesARepeatedIdOnce` |
| 7 | Batch commits despite a failed item, no 500 | yes | `TaskBulkStatusIntegrationTest.batchCommitsDespiteARejectedEntry` |
| 8 | Events reach after-commit subscribers | yes | `TaskBulkStatusIntegrationTest.blockedEntryReachesAlertsModule` |
| 9 | Structurally invalid payload rejected whole | yes | `TaskRoutesTest.bulkUpdateRejectsAnEmptyBatch`, `.bulkUpdateRejectsAnOversizedBatch`, `.bulkUpdateRejectsABlankTaskId`, `.bulkUpdateRejectsAnUnknownStatusWord` |

Two tests beyond the contract:

* `TaskRoutesTest.bulkUpdateReportsAnEmptyFailedList` — `failed` is serialised as `[]` rather than
  omitted on an all-success batch. The spec makes both keys always present; without this the
  `@JsonInclude(NON_EMPTY)` habit elsewhere in the codebase could regress it silently.
* `EventDeliveryIntegrationTest.bulkHandoverReachesAlertsModule` — `how-to-test` §4 puts arrival
  proofs in this file. It also pins what AC8 alone does not: a three-entry batch of BLOCKED + unknown
  id + DONE raises exactly **one** alert, so neither the DONE entry nor the failed entry published.

## 2. Files changed

### activities (source)
- `dto/BulkStatusUpdateRequest.java` — new; `@Size(min=1,max=50)` + `@Valid` on the entries, null list normalised to empty
- `dto/BulkStatusUpdateItem.java` — new; `taskId` `@NotBlank`, `status` `@NotNull`, typed as `TaskStatus`
- `dto/BulkStatusUpdateSuccess.java` — new; `changed(previous,current)` / `unchanged(task)` factories
- `dto/BulkStatusUpdateFailure.java` — new; `from(taskId, AppError)` flattens the `(code,message,statusCode)` triple
- `dto/BulkStatusUpdateResponse.java` — new; `succeeded` + `failed`, both always present, both defensively copied
- `service/TaskService.java` — added `@Transactional bulkUpdateStatus()` and the private non-transactional `applyHandoverEntry()`; added the `BULK_TARGET_STATUSES` constant; reuses the existing `requireTransitionAllowed`
- `routes/TaskRoutes.java` — added `@PatchMapping("/bulk-status")` returning `207 MULTI_STATUS`

### activities (test)
- `service/TaskServiceTest.java` — 6 bulk tests (AC1–6); added a `seedTask` overload taking an assignee, plus `storedStatus`/`handover` helpers
- `routes/TaskRoutesTest.java` — 6 route tests: the 207 report, the empty-`failed` shape, and the four whole-request rejections
- `routes/TaskBulkStatusIntegrationTest.java` — new `@SpringBootTest @AutoConfigureMockMvc`; AC7 and AC8

### root (test)
- `EventDeliveryIntegrationTest.java` — added `bulkHandoverReachesAlertsModule`

No change to `repository/`, `shared/`, `alerts/`, `reports/`, `data.sql` or the schema.

## 3. Deviations from the contract's literal wording

Both are stricter or equivalent, not looser, but the Evaluator should see them rather than find them.

* **AC3 "its `updatedAt` is unchanged".** Asserted as full record equality against the seeded `Task`
  (`assertThat(taskRepository.findById("task-003")).contains(done)`) instead of comparing `updatedAt`
  alone. The clock is fixed at `2026-02-01T10:00:00Z`, so an accidental write would produce an
  identical `updatedAt` and a timestamp assertion would not catch it. Record equality catches any
  write at all.
* **AC7/AC8 "GIVEN seed activity `task-001`/`task-002`".** Both integration tests create their own
  activities via `TaskService.create` rather than mutating the seed rows. `@SpringBootTest` classes
  share one application context and one H2 database, and `ApiSmokeTest` already patches `task-002` to
  `DONE`; asserting on seed statuses from a second class makes both tests order-dependent. The GIVEN
  is a state precondition ("is `TODO`"), and a freshly created activity satisfies it — a created
  activity is `TODO` by `TaskService.create`. The service tests (AC1–6) do use the literal ids
  `task-001`–`task-003` against the fake repository, where isolation is per-test.

## 4. Known gaps

none

## 5. Notes for the Evaluator

* `applyHandoverEntry` is deliberately private and **not** `@Transactional`, and deliberately does not
  delegate to the public `update(...)`. Either would put a transaction boundary between the loop and
  the failure, marking the batch rollback-only and losing the whole handover to
  `UnexpectedRollbackException` after the report claimed success. AC7 is the test that would fail.
* Check ordering inside `applyHandoverEntry` is: target-status legality → duplicate-id → existence →
  already-settled short-circuit → transition legality → save + publish. Consequence worth knowing: an
  entry rejected for an illegal target status never registers its id, so a later valid entry for the
  same id still applies. Only entries that were actually applied count as duplicates.
* The `status` field is enum-typed, so an unparseable status word rejects the whole batch (AC9, last
  case). This was flagged in `spec.md` for human decision and approved as specified.
