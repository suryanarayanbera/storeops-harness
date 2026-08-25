# Evaluator Feedback — Sprint 1: Bulk Status Endpoint with Per-Task Independence

## 1. VERDICT

**CONDITIONAL PASS**

No automated gate failed. No manual hard gate failed. Two findings remain, one of them a real
defect with a one-line fix. Per `evaluation-criteria` §5.4 that is a CONDITIONAL PASS, not a FAIL:
every acceptance criterion is met by a test that asserts more than a status code, the event is
published, the transaction boundary is correct, and the route holds no business logic.

Because a CONDITIONAL PASS advances the harness to Sprint 2 rather than triggering a retry,
**Finding 1 must be carried into Sprint 2 as required work, not filed as a nice-to-have.**

## 2. SCORE

`A 37/40 · B 32/35 · C 23/25 = 92`

| Dimension | Score | Reasoning |
| --- | --- | --- |
| A. Contract fulfilment | 37/40 | All 11 ACs met with substantive assertions. −3: an error path the contract's own rejection table implies (an unparseable item → 400) is unmapped and answers 500. |
| B. Architectural compliance | 32/35 | 12 ArchUnit rules, 0 Checkstyle violations, SpotBugs clean; transaction boundary correct and empirically verified. −3: a raw `NullPointerException` escapes the service layer, violating the `AppError` contract. |
| C. Test quality | 23/25 | New service class at 100% line / 100% branch, six negative tests, assertions on event payload fields, database state, error codes and `updatedAt`. −2: no test for the null-element payload; no assertion of after-commit delivery on the new publisher path (contract-deferred, see §5). |

## 3. GATE RESULTS

`./mvnw clean test` — exit code 0, **BUILD SUCCESS**.

| Dim | Gate | Result | Evidence |
| --- | --- | --- | --- |
| A | JUnit | **PASS** | `Tests run: 125, Failures: 0, Errors: 0, Skipped: 0`. 96 baseline + 29 added, matching the declared count. |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **PASS** | `Tests run: 12, Failures: 0, Errors: 0` |
| B | Checkstyle (`IllegalThrows`, `NoRawErrorThrows`, `IllegalCatch`, +) | **PASS** | `You have 0 Checkstyle violations.` |
| B | SpotBugs | **PASS** | `spotbugs:4.10.3.0:check` completed, no bugs reported |
| C | `jacoco:check` bundle line ≥ 85%, branch ≥ 60% | **PASS** | `jacoco:0.8.13:check` passed |
| C | `jacoco:check` per-class line ≥ 70%, branch ≥ 50% (services, listeners) | **PASS** | `TaskBulkStatusService`: line 38/38 (100%), branch 14/14 (100%) |

**Scope check.** `git status --porcelain` matches `generator-summary.md` exactly — 3 modified,
10 untracked, no undeclared source changes. `TaskService.java` is confirmed unmodified.

### Manual hard gates

| Gate (`evaluation-criteria` §2–§4) | Result | Evidence |
| --- | --- | --- |
| A required event was never published | **PASS** | `TaskBulkStatusServiceTest.publishesOneEventPerSuccessAndNoneForFailures` asserts one `TaskStatusChangedEvent` with all seven payload fields plus `eventType()` |
| Publisher not `@Transactional` | **PASS** | `TaskService.java:113`. `TaskBulkStatusService` reaches it through the injected bean, not by self-invocation — see verification below |
| Listener missing `AFTER_COMMIT` / `REQUIRES_NEW` | **PASS** | `AlertEventListener.java:56-57` and `:76-77` both carry the pair; file unmodified |
| `ErrorHandler` bean dropped from `EventBusConfiguration` | **PASS** | File unmodified |
| Business logic in a route | **PASS** | `TaskRoutes.java:87-89` is a single delegating call. No loop, no enum conditional, no partial-failure aggregation |
| Criterion covered only by a status-code assertion | **PASS** | Every AC asserts response body content and, where the criterion says so, database state read back in a separate request |
| A dropped acceptance criterion | **PASS** | 11 of 11 implemented; none declared as a gap |
| Negative test per new service method | **PASS** | Six for `bulkUpdateStatus`: unknown id, terminal status, unsupported target, no-op, all-fail, duplicate ids |

### Verification of the transaction boundary

This is the claim the sprint rests on and the one no gate can see, so it was checked against
runtime behaviour rather than accepted from the design rationale. Running
`BulkStatusUpdateIntegrationTest` with the module at DEBUG shows the bulk path reaching the alerts
module after commit:

```
DEBUG c.c.s.shared.events.SpringEventBus  : Publishing TASK_STATUS_CHANGED for delivery after commit
INFO  c.c.s.a.service.NotificationService : Raised ESCALATION alert for user-004 from source task-001
DEBUG c.c.s.shared.events.SpringEventBus  : Publishing TASK_STATUS_CHANGED for delivery after commit
INFO  c.c.s.a.service.NotificationService : Raised ESCALATION alert for user-003 from source task-002
```

Those two alerts come from `eachActivityCommitsOnItsOwn`, the batch whose middle item fails. The
proxy is traversed, a transaction opens per activity, each commits on its own and each fires its
own after-commit callback. Had the loop been placed inside `TaskService` and self-invoked, this
output would be absent while every test still passed. The design decision holds up.

## 4. FINDINGS

### Finding 1 — required. A null element in `updates` escapes as a raw `NullPointerException`

`src/main/java/com/cognizant/storeops/activities/dto/BulkStatusUpdateRequest.java:27` — Error
contract: every error surfaced by a service must be an `AppError` subtype
(`architecture-principles` §5, `coding-conventions` §1). Also breaks the contract's own
whole-request rejection table, which classes an item that is not parseable as a task instruction
as `400 VALIDATION_FAILED`.

`@Valid` cascades into a collection's elements but skips the null ones, so `{"updates":[null]}`
passes bean validation, reaches
`src/main/java/com/cognizant/storeops/activities/service/TaskBulkStatusService.java:96` and throws.
Confirmed against the running application:

```
java.lang.NullPointerException: Cannot invoke
  "com.cognizant.storeops.activities.dto.BulkStatusUpdateItem.taskId()" because "item" is null

PROBE_STATUS=500
PROBE_BODY={"code":"INTERNAL_ERROR","message":"An unexpected error occurred","statusCode":500,
            "path":"/api/tasks/bulk-status","timestamp":"..."}
```

The class javadoc at `BulkStatusUpdateRequest.java:16-19` shows this payload was anticipated —
`List.copyOf` was rejected precisely so a `[null]` element would not throw before validation ran —
but the element was then never constrained, so the throw simply moved from the constructor into the
service.

**Required change.** Constrain the container element and cover it:

```java
@NotEmpty(message = "must contain at least one update")
@Size(max = MAX_BATCH_SIZE, message = "must contain at most 50 updates")
List<@NotNull(message = "must not be null") @Valid BulkStatusUpdateItem> updates
```

Add a route-slice test asserting `400` / `VALIDATION_FAILED` for `{"updates":[null]}`, so the fix
is held in place. Note that `@Valid` moves onto the type argument; leaving it on the component as
well is redundant.

### Finding 2 — required. `MAX_BATCH_SIZE` is a dead constant that documents a lie

`src/main/java/com/cognizant/storeops/activities/dto/BulkStatusUpdateRequest.java:30` — Its own
javadoc says "mirrored in the `@Size` message above", but `@Size(max = 50)` on line 25 uses a
literal and nothing else in `src/` references the constant. Changing `MAX_BATCH_SIZE` would alter
the endpoint's documented limit while changing no behaviour at all.

**Required change.** Reference it from the annotation — `@Size(max = MAX_BATCH_SIZE, ...)`, which
is legal since the constant is a compile-time literal — or delete the constant. The fix for
Finding 1 touches the same declaration, so do both in one pass.

## 5. OBSERVATIONS — not findings, no change required

* **After-commit delivery on the bulk path is correct but unasserted.** Section 3 verifies it from
  log output; no test asserts it. The Sprint 1 contract deliberately scoped this to Sprint 2
  ("This sprint stops at the publish call"), so it is not a dropped criterion — but until Sprint 2
  Scenarios 1, 2 and 6 land, a regression that breaks proxy traversal would leave this suite green.
  This is the sprint's largest carried risk and the reason Sprint 2 should not be skipped.
* **The three deviations declared in `generator-summary.md` §3 are all accepted.** Duplicate
  detection in the service produces the specified 400 / `VALIDATION_FAILED` / details outcome and
  avoids depending on `@AssertTrue` property-path resolution on a record; `@DirtiesContext` is
  justified, since `ApiSmokeTest.updateTask` does permanently move `task-002` to `DONE` in the
  shared database; and the service returning DTOs follows the existing
  `TaskService.create(CreateTaskRequest)` precedent and is what keeps the route to one line.
* **`DONE` → `DONE` reporting `TASK_TRANSITION_NOT_ALLOWED`** is correct per the approved spec and
  is covered by `TaskBulkStatusServiceTest.repeatingDoneIsRefusedAsAForbiddenTransition`. The
  Generator was right to flag it: `TaskService.update` treats it as a no-op and never reaches its
  own terminal rule, so without `requireRealTransition` handling that corner the activity would
  have been reported as a success having published nothing.
* **The null-coalescing branches in `BulkStatusUpdateResponse`'s compact constructor are
  unreachable** (branch coverage 2/4). Left alone deliberately: it matches the established
  `ErrorResponse` idiom in `shared/error`, and `how-to-review` §1 says not to score baseline
  patterns.
