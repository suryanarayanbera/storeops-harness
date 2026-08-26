# Generator Summary — Sprint 2: The Activities Listener Creates The Cloned Tasks

`./mvnw clean test` — **BUILD SUCCESS**. 306 tests, 0 failures, 0 errors (275 at Sprint 1 close, +31).
Checkstyle 0 violations, SpotBugs `BugInstance size is 0`, `jacoco:check` passed, all 12
`ModuleBoundaryTest` rules passed. `TaskTemplateEventListener` 100% line, `TaskService` 98.9% line /
91.7% branch, bundle 94.0% line / 76.6% branch.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | End to end: 202, then five activities at store-002 with the right status, category, ids, priorities and assignees | yes | `PlanogramTemplateDeliveryIntegrationTest.thePostClonesTheStandardTemplate`, `.theClonesCarryPrioritiesAndAssignments`, `.theClonesAreVisibleThroughTheApi` |
| 2 | Repeat call answers 202 with taskCount 4 and creates nothing | yes | `PlanogramTemplateDeliveryIntegrationTest.aRepeatPostCreatesNothing`, `TaskServiceTest.createFromTemplateIsIdempotent` |
| 3 | One existing title → three created, pre-existing activity untouched, skip not category-scoped | yes | `PlanogramTemplateDeliveryIntegrationTest.anExistingTitleIsSkipped`, `TaskServiceTest.createFromTemplateSkipsTitlesAlreadyPresent` |
| 4 | Titles compared ignoring case and surrounding whitespace | yes | `PlanogramTemplateDeliveryIntegrationTest.aLooselyMatchingTitleIsSkipped`, `TaskServiceTest.createFromTemplateComparesTitlesLoosely` |
| 5 | Event published in a transaction is delivered on commit and writes | yes | `PlanogramTemplateDeliveryIntegrationTest.anEventPublishedInATransactionIsDelivered` |
| 6 | Unrecognised priority string → MEDIUM, no exception escapes | yes | `TaskTemplateEventListenerTest.anUnrecognisedPriorityBecomesMedium`, `TaskServiceTest.createFromTemplateFallsBackToMediumPriority` |
| 7 | Null priority → MEDIUM, null category → GENERAL | yes | `TaskTemplateEventListenerTest.nullPriorityAndCategoryFallBack`, `TaskServiceTest.createFromTemplateFallsBackToGeneralCategory` |
| 8 | Unknown assignee dropped, activity still created | yes | `TaskTemplateEventListenerTest.anUnknownAssigneeIsDropped`, `TaskServiceTest.createFromTemplateDropsUnknownAssignee` |
| 9 | Empty item list writes nothing, throws nothing | yes | `TaskTemplateEventListenerTest.anEmptyEventIsANoOp`, `TaskServiceTest.createFromTemplateHandlesNoItems` |
| 10 | Rolled-back publish creates no activity | yes | `PlanogramTemplateDeliveryIntegrationTest.aRolledBackTransactionCreatesNothing` |
| 11 | No TaskStatusChangedEvent, no new alert | yes | `PlanogramTemplateDeliveryIntegrationTest.cloningRaisesNoAlert`, `TaskServiceTest.createFromTemplatePublishesNothing`, `TaskTemplateEventListenerTest.theListenerPublishesNothing` |
| 12 | Failing peer subscriber does not stop the clone | yes | `PlanogramTemplateDeliveryIntegrationTest.aFailingPeerSubscriberDoesNotStopTheClone` |

AC 11 is asserted three ways because the negative on its own is weak. The two unit-level tests assert
`eventBus.published()).isEmpty()` — the whole bus, not just `TaskStatusChangedEvent`, so a stray event
of any type fails. The integration test then asserts the alert table is exactly as `data.sql` left it,
which is the observable consequence a caller would see.

AC 5 deserves a note on what it actually pins. Reaching the assertion at all proves the handler ran on
its own `REQUIRES_NEW` transaction: the publishing transaction had already committed by dispatch time,
so a write that joined it would never be flushed, the log line would still print, and the row would not
exist. Together with AC 10 that brackets both annotations from either side.

Tests written beyond the contract:
`TaskServiceTest.createFromTemplateScopesTheSkipToTheProgramme` (same title on a different programme is
not a clash), `.createFromTemplateDeduplicatesWithinTheBatch`, `.createFromTemplateMatchesPriorityIgnoringCase`,
`.createFromTemplateTrimsTheStoredTitle`, `.createFromTemplateSkipsUntitledItems` (see §3), and
`TaskTemplateEventListenerTest.aRepeatDeliveryIsANoOp` (at-least-once redelivery must not double the
work).

## 2. Files changed

### activities — listener
- `listener/TaskTemplateEventListener.java` — **new**, and a new `listener` package for this module.
  One handler, `onProgrammeTemplateRequested`, carrying
  `@TransactionalEventListener(phase = AFTER_COMMIT)` **and**
  `@Transactional(propagation = REQUIRES_NEW)`. Delegates to the service and logs created-of-carried.
  Imports nothing from `programmes`.

### activities — service
- `service/TaskService.java` — added
  `createFromTemplate(String projectId, String storeId, List<TemplateTaskDefinition> items)` returning
  the activities actually created, plus private helpers `titleKey`, `priorityOrDefault`,
  `categoryOrDefault`, `trimmed` and `knownAssigneeOrNull`. Added imports only; every existing method
  is untouched. Not `@Transactional` — the listener owns the boundary, and this path publishes nothing,
  so there is no after-commit delivery depending on a commit here.

### tests
- `activities/listener/TaskTemplateEventListenerTest.java` — **new**, 7 tests. Handler constructed and
  invoked directly per `how-to-test` §1, over a real `TaskService` on `FakeTaskRepository` so the
  assertions land on rows rather than on a mock interaction.
- `PlanogramTemplateDeliveryIntegrationTest.java` — **new**, 10 tests. `@SpringBootTest` with the SLA
  sweep disabled and `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`.
- `activities/service/TaskServiceTest.java` — 14 new tests for `createFromTemplate`; the 17
  pre-existing tests are unchanged.
- `PlanogramTemplateIntegrationTest.java` — `applyingTheTemplateCreatesNoActivityYet` renamed to
  `theEndpointItselfWritesNoActivity` and its expectation flipped from one activity at store-002 to
  five. **This is the change the Sprint 1 evaluator asked for** rather than a deletion; the comment
  records why the number moved so a later reader does not revert it.

No new JPA entity, no schema change, no `data.sql` change, nothing in `routes`, and
`EventBusConfiguration` untouched.

## 3. Behaviour added beyond the contract's stated rules

The contract lists five per-item rules. I implemented a sixth: **an item whose title is null or blank
is skipped.**

It is not in the contract, and it is required by the contract's own guardrail that the listener must
not throw. `createFromTemplate` stores `item.title().trim()`, so a null title is a
`NullPointerException` raised inside an after-commit listener — swallowed by the `ErrorHandler`, losing
the entire batch to a log line, which is the precise failure the no-throw rule exists to prevent. A
blank title is the same case one step later: it would persist an untitled activity and, because the
skip key normalises to the empty string, collide with the next blank one.

Covered by `TaskServiceTest.createFromTemplateSkipsUntitledItems`. Nothing in the shipped
`PLANOGRAM_STANDARD` catalogue can produce it — the titles are code constants — so this is defence
against a future template or a hand-published event, not a live path.

## 4. Known gaps

None. All 12 criteria are implemented and asserted.

One design point restated so it is not read as an omission: `createFromTemplate` deliberately does not
raise `ValidationError` for an unknown assignee, though `create` does for the same input. `create` has
an HTTP caller to tell; this path does not, and refusing the activity would lose the work as well as
the assignment. The divergence is documented on the method and asserted by
`createFromTemplateDropsUnknownAssignee`.
