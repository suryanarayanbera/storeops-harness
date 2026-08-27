# Generator Summary — Sprint 2: After-Commit Delivery of Bulk-Published Events

`./mvnw clean test` — BUILD SUCCESS. 134 tests, 0 failures, 0 errors (125 at Sprint 1 close, +9).
Checkstyle 0 violations, SpotBugs clean, ArchUnit 12/12, JaCoCo bundle and per-class gates passed.

As the contract predicted, this sprint added **no production code** beyond the two cleanups carried
over from the Sprint 1 evaluation. `AlertEventListener` needed no change.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Bulk block raises one ESCALATION per blocked activity, each to its own assignee | yes | `BulkStatusEventDeliveryIntegrationTest.bulkBlockRaisesOneAlertPerActivity` |
| 2 | Partial-failure batch still delivers for its successes; nothing for the failure | yes | `BulkStatusEventDeliveryIntegrationTest.partialFailureBatchStillDelivers` |
| 3 | Refused transition raises no alert; seeded `notification-001` untouched | yes | `BulkStatusEventDeliveryIntegrationTest.refusedTransitionRaisesNoAlert` |
| 4 | Bulk completion delivers but raises no alert | yes | `BulkStatusEventDeliveryIntegrationTest.bulkCompletionRaisesNoAlert` |
| 5 | Throwing subscriber does not break the batch; 2 invocations recorded | yes | `BulkStatusSubscriberIsolationTest.throwingSubscriberDoesNotBreakTheBatch` |
| 6 | Each activity commits and delivers on its own; exactly 2 alerts | yes | `BulkStatusEventDeliveryIntegrationTest.eachActivityCommitsAndDeliversOnItsOwn` |

Carried findings from `sprint-1-evaluator-feedback.md`:

| Finding | Fixed | Test proving it |
| --- | --- | --- |
| 1 — null list element escaped as a raw NPE / 500 | yes | `TaskRoutesTest.bulkStatusRejectsNullItem`, `TaskRoutesTest.bulkStatusRejectsNullItemAmongValidOnes` |
| 2 — `MAX_BATCH_SIZE` dead constant | yes | `TaskRoutesTest.bulkStatusEnforcesTheBatchSizeLimit` (unchanged and still green, so the `{max}` interpolation resolves to the same message) |

Extra test beyond the contract:
`BulkStatusSubscriberIsolationTest.theRealListenerIsUnaffectedByTheFailingOne` — the real listener
still raises its alert when another subscriber throws. Scenario 5 proves the batch survives; this
proves the surviving batch still alerts, which is the part that matters operationally.

## 2. Files changed

### activities — dto (carried findings, the only production change)
- `dto/BulkStatusUpdateRequest.java` — `@Valid` moved onto the type argument and joined by
  `@NotNull`, so a null list element is now rejected as `400 VALIDATION_FAILED` with detail
  `updates[0]: must not be null` instead of NPE-ing in the service. `@Size(max = ...)` now
  references `MAX_BATCH_SIZE` and its message interpolates `{max}`, so the constant drives
  behaviour rather than only documenting it.

### alerts, shared, activities service/routes
- Untouched. `AlertEventListener`, `NotificationService`, `EventBusConfiguration`,
  `TaskStatusChangedEvent`, `TaskService`, `TaskBulkStatusService` and `TaskRoutes` are all
  unmodified — confirmed against `git status`. No fourth event, no `fallbackExecution`, no
  `NotificationService` injected into `activities`.

### tests
- `BulkStatusEventDeliveryIntegrationTest.java` — **new**, 5 tests. `@SpringBootTest` +
  `@AutoConfigureMockMvc` + `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`. Every assertion reads a
  `Notification` back through `GET /api/notifications`.
- `BulkStatusSubscriberIsolationTest.java` — **new**, 2 tests. Same wiring plus
  `@Import(FailingStatusSubscriber.class)`.
- `support/FailingStatusSubscriber.java` — **new** fixture. Plain `@EventListener` on
  `TaskStatusChangedEvent` that throws and counts invocations.
- `activities/routes/TaskRoutesTest.java` — added 2 tests for the null-element fix. 19 tests total.

## 3. Verification that these tests can actually fail

The contract's premise is that Sprint 1's suite cannot catch a broken transaction boundary. That is
a claim about the tests, so I tested it: removed `@Transactional` from `TaskService.update` — the
canonical silent failure, where `AFTER_COMMIT` callbacks never run because no transaction is active
— and ran both suites.

| Suite | Result with the wiring broken |
| --- | --- |
| Sprint 1 bulk tests (`BulkStatusUpdateIntegrationTest`, `TaskBulkStatusServiceTest`, `TaskRoutesTest`) | **41/41 green.** Database rows still change, so nothing notices. |
| Sprint 2 tests | **4 of 7 failed**, each on a missing `ESCALATION` alert |

Failures were `bulkBlockRaisesOneAlertPerActivity:85`, `partialFailureBatchStillDelivers:109`,
`eachActivityCommitsAndDeliversOnItsOwn:172` and `theRealListenerIsUnaffectedByTheFailingOne:86`.
`TaskService.java` was then restored and byte-compared against its pre-mutation copy; `git status`
confirms it is unmodified.

## 4. Known gaps

Three of the seven Sprint 2 tests do **not** discriminate a broken transaction boundary, and it is
worth being explicit about which:

- `refusedTransitionRaisesNoAlert` and `bulkCompletionRaisesNoAlert` are absence assertions. They
  pass when nothing is delivered, which is also what a broken bus looks like. They are still worth
  having — they prove the alerts module's filtering, not the delivery — but AC3 and AC4 rest on
  their positive counterparts in the same class for evidence that dispatch works at all. The
  javadoc on `bulkCompletionRaisesNoAlert` names its counterpart for that reason.
- `throwingSubscriberDoesNotBreakTheBatch` stayed green under the mutation because
  `FailingStatusSubscriber` is a plain `@EventListener` and fires at publish time without needing a
  commit. That is the deliberate choice inherited from the existing `FailingSubscriber` fixture: it
  routes the exception through `SimpleApplicationEventMulticaster`, which is the path the
  `ErrorHandler` bean guards and the mechanism AC5 is about. Its companion
  `theRealListenerIsUnaffectedByTheFailingOne` covers the after-commit half and does fail under the
  mutation.

One deviation from the contract's wording. AC5 says "a `FailingSubscriber` registered for
`TaskStatusChangedEvent`"; I added a separate `FailingStatusSubscriber` rather than extending the
existing fixture, which only ever sees its own `ProbeEvent`. Extending it would make it throw inside
every test that imports it and would leave one `invocationCount()` serving two unrelated
assertions.

Also worth reporting, since it cost a debug cycle and points at existing code: **`@Component` on a
fixture in `com.cognizant.storeops.support` is picked up by the application's own component scan**,
because that package sits under the `@SpringBootApplication` root even on the test classpath. My
first version of `FailingStatusSubscriber` carried `@Component`, and it consequently threw inside
every `@SpringBootTest` context in the suite, including `ApiSmokeTest` and the Sprint 2 delivery
tests. Dropping the annotation and relying on `@Import` alone fixed it. The same applies to the
baseline `FailingSubscriber`, whose javadoc claims it is "not picked up by component scanning" — it
is, and the claim survives only because nothing publishes `ProbeEvent` outside its own test. Left
alone as out of scope; flagged for the Evaluator to route.
