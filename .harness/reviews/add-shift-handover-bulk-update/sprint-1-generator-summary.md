# Generator Summary — Sprint 1: Bulk Status Endpoint with Per-Task Independence

`./mvnw clean test` — BUILD SUCCESS. 125 tests, 0 failures, 0 errors. Checkstyle, SpotBugs,
ArchUnit (12 rules), JaCoCo bundle and per-class gates all executed and passed.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Clean batch updates every task | yes | `BulkStatusUpdateIntegrationTest.cleanBatchUpdatesEveryActivity` |
| 2 | Unknown id fails alone, neighbour still committed | yes | `BulkStatusUpdateIntegrationTest.unknownIdFailsAloneAndNeighbourCommits` |
| 3 | Terminal DONE fails alone even when listed first | yes | `BulkStatusUpdateIntegrationTest.terminalActivityFailsAloneEvenWhenListedFirst` |
| 4 | Unsupported target status fails that task only | yes | `BulkStatusUpdateIntegrationTest.unsupportedTargetStatusFailsThatActivityOnly` |
| 5 | No-op BLOCKED→BLOCKED is a failure, `updatedAt` untouched | yes | `BulkStatusUpdateIntegrationTest.noOpTransitionIsReportedAsUnchanged` |
| 6 | All-fail batch is still 200 with a result body | yes | `BulkStatusUpdateIntegrationTest.batchWhereEverythingFailsIsStillOk` |
| 7 | Empty batch rejected as a whole request | yes | `TaskRoutesTest.bulkStatusRejectsEmptyBatch` |
| 8 | Duplicate task ids rejected, no partial write | yes | `BulkStatusUpdateIntegrationTest.duplicateTaskIdsAreRejectedWholesale` |
| 9 | 51 items rejected, exactly 50 accepted | yes | `TaskRoutesTest.bulkStatusEnforcesTheBatchSizeLimit` |
| 10 | One event per success, none per failure | yes | `TaskBulkStatusServiceTest.publishesOneEventPerSuccessAndNoneForFailures` |
| 11 | Bulk path does not shadow `PATCH /api/tasks/{id}` | yes | `BulkStatusUpdateIntegrationTest.singleActivityPathIsUnaffected` |

Supporting tests for the secondary ANDs inside those criteria:

| Criterion clause | Test |
| --- | --- |
| AC7 — `updates` field absent behaves as empty | `TaskRoutesTest.bulkStatusRejectsMissingUpdatesField` |
| AC10 — all-fail batch publishes nothing | `TaskBulkStatusServiceTest.batchWhereEverythingFailsStillReturnsAResult` |
| AC10 — payload enums travel as `String` | `TaskBulkStatusServiceTest.eventPayloadCarriesEnumsAsStrings` |
| AC11 — bulk handler reached, `TaskService` never called with id `bulk-status` | `TaskRoutesTest.bulkPathIsNotReadAsAnActivityId` |

## 2. Files changed

### activities — routes
- `routes/TaskRoutes.java` — added `PATCH /api/tasks/bulk-status` handler returning
  `BulkStatusUpdateResponse`; constructor now also takes `TaskBulkStatusService`. No other method
  touched.

### activities — service
- `service/TaskBulkStatusService.java` — **new.** Per-activity loop; validates distinct task ids,
  then for each item: `taskService.getById` → handover-target check → real-transition check →
  `taskService.update`. Catches `AppError` only. Deliberately **not** `@Transactional`, and
  deliberately in its own bean rather than inside `TaskService`; both reasons are documented in the
  class javadoc.
- `service/TaskService.java` — **not modified.**

### activities — dto
- `dto/BulkStatusUpdateRequest.java` — **new.** `@NotEmpty`, `@Size(max = 50)`, `@Valid` cascade;
  null list normalised to empty in the compact constructor.
- `dto/BulkStatusUpdateItem.java` — **new.** `@NotBlank taskId`, `@NotNull status`.
- `dto/BulkStatusUpdateResponse.java` — **new.** `succeeded` / `failed`, both defensively copied.
- `dto/BulkStatusFailure.java` — **new.** `(taskId, code, message, statusCode)`, built from an
  `AppError`. A wire record, not an `AppError` subtype.

### shared, alerts, programmes, staff, reports
- Untouched. No new event, no new listener, no `AppError` subtype, no `EventBusConfiguration`
  change, no `data.sql` or entity change.

### tests
- `activities/service/TaskBulkStatusServiceTest.java` — **new**, 12 tests. Real `TaskService` over
  `FakeTaskRepository` + `RecordingEventBus` + fixed clock at `2026-02-01T10:00:00Z`.
- `activities/routes/TaskRoutesTest.java` — added `@MockitoBean TaskBulkStatusService` (required
  for the slice to start) and 7 bulk-route tests. Existing 10 tests unchanged.
- `BulkStatusUpdateIntegrationTest.java` — **new**, 10 tests. `@SpringBootTest` +
  `@AutoConfigureMockMvc` against the seeded H2 rows.

## 3. Known gaps

Sprint 2 is not attempted — it is a separate contract and out of this sprint's scope.

Three implementation choices differ from the literal wording of the contract. None drops a
criterion; each is declared so the Evaluator scopes to it rather than discovering it.

1. **Duplicate task ids are rejected in `TaskBulkStatusService`, not by bean validation.** The
   contract lists duplicates in the same table as the `@NotEmpty` / `@Size` payload rules. Detecting
   them needs a cross-item scan, which as bean validation means an `@AssertTrue` getter on a record
   — a shape whose property-path handling in Hibernate Validator I did not want to depend on for a
   hard gate. It is a `ValidationError` thrown before the loop, so the observable outcome is
   identical: `400`, `VALIDATION_FAILED`, `details` naming the duplicated id, nothing written.
   Consequence: AC8 is proved by the integration test and `TaskBulkStatusServiceTest`, not by the
   routes slice.

2. **`BulkStatusUpdateIntegrationTest` carries
   `@DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)`.** The contract's GIVENs name the seeded
   activities by id and status, but the H2 database is shared across every `@SpringBootTest` in the
   run and `ApiSmokeTest.updateTask` already moves `task-002` to `DONE` permanently. Without the
   rebuild, these criteria would pass or fail on surefire's class ordering. Cost is ~10 context
   restarts; the class runs in 5.9 s.

3. **`TaskBulkStatusService` takes and returns DTOs** (`BulkStatusUpdateRequest` in,
   `BulkStatusUpdateResponse` out), so `TaskResponse` mapping happens in the service rather than in
   the route. This follows the existing `TaskService.create(CreateTaskRequest)` precedent, and it is
   what keeps the route to a single delegating line — the alternative puts the partition loop in
   `TaskRoutes`, which the contract's layering guardrail forbids.

One behaviour is worth flagging because the contract states it but does not test it directly:
**`DONE` → `DONE` reports `TASK_TRANSITION_NOT_ALLOWED`, not `TASK_STATUS_UNCHANGED`.**
`TaskService.update` treats a request for the status a task already holds as a no-op and never
reaches its terminal-status rule, so `TaskBulkStatusService.requireRealTransition` resolves that one
corner itself. Covered by `TaskBulkStatusServiceTest.repeatingDoneIsRefusedAsAForbiddenTransition`.
