# Sprint 2: Publish REGIONAL_ROLLUP_REQUESTED

## Goal

Make a successful regional rollup announce itself on the event bus. Add the
`RegionalRollupRequestedEvent` record to `shared/events`, inject `EventBus` into `ReportService`,
publish exactly one event per successful `regionalRollup` call, and annotate the method
`@Transactional` so the after-commit dispatch in Sprint 3 has a transaction to hang from.

No listener is built in this sprint. Nothing consumes the event yet, so no `Report` row appears —
that is the point of the split, and Scenario 5 pins it down.

Files in scope:
* `shared/events/RegionalRollupRequestedEvent.java` — new
* `reports/service/ReportService.java` — constructor gains `EventBus`, `regionalRollup` gains
  `@Transactional` and the publish call

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: The event record reports its type and carries only primitives**
* **GIVEN** a `new RegionalRollupRequestedEvent("region-north", "user-001", 2, someInstant)`
* **WHEN** `eventType()` is called
* **THEN** it returns exactly the string `REGIONAL_ROLLUP_REQUESTED`
* **AND** the record implements `DomainEvent` and its components are `String regionId`,
  `String requestedBy`, `int storeCount`, `Instant occurredAt` — no `TaskStatus`, `TaskCategory`,
  `ReportType` or any other module enum appears in the signature

**Scenario 2: A successful rollup publishes exactly one event carrying the resolved store count**
* **GIVEN** a `ReportService` built with a `RecordingEventBus`, a `FakeUserRepository`-backed
  `UserService` holding staff in `store-001` and `store-002` of `region-north`, and a
  `Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), UTC)`
* **WHEN** `reportService.regionalRollup("region-north", "user-001")` is called
* **THEN** `recordingEventBus.published(RegionalRollupRequestedEvent.class)` has exactly one element
* **AND** that event reads `regionId` = `region-north`, `requestedBy` = `user-001`, `storeCount` = 2
  and `occurredAt` = `2026-02-01T00:00:00Z`
* **AND** `storeCount` matches `response.storeCount()`, so the event and the response cannot disagree

**Scenario 3: The default requestedBy travels on the event**
* **GIVEN** the same service wiring as Scenario 2
* **WHEN** a `GET /api/reports/region/region-north` request is made with no `requestedBy` parameter
* **THEN** the published event carries `requestedBy` = `"api"`
* **AND** the response is still HTTP 200

**Scenario 4: A failed rollup publishes nothing**
* **GIVEN** a `ReportService` built with a `RecordingEventBus` and no staff in `region-atlantis`
* **WHEN** `reportService.regionalRollup("region-atlantis", "api")` throws `NotFoundError`
* **THEN** `recordingEventBus.published()` is empty
* **AND** the same holds for a blank region id throwing `ValidationError`, and for an unknown
  `requestedBy` throwing `NotFoundError`

**Scenario 5: No report row is created yet, because nothing listens**
* **GIVEN** the seeded database and no listener method for `RegionalRollupRequestedEvent`
* **WHEN** a `GET /api/reports/region/region-north` request is made in a `@SpringBootTest`
* **THEN** the response is HTTP 200
* **AND** `reportService.findByScopeId("region-north")` is empty — this sprint publishes the event and
  deliberately does not consume it

**Scenario 6: The publishing method runs inside a transaction**
* **GIVEN** the `ReportService` class after this sprint's change
* **WHEN** `regionalRollup` is inspected — by reflection on the method's annotations, or by asserting
  `TransactionSynchronizationManager.isActualTransactionActive()` is `true` from inside a stubbed
  collaborator called during the aggregation
* **THEN** `@Transactional` is present on the method (or the class)
* **AND** the test states in a comment why: after-commit listeners are skipped outright when no
  transaction is active, so without this the Sprint 3 listener never fires and no test that asserts
  only on HTTP 200 would notice

**Scenario 7: Every existing behaviour from Sprint 1 still holds**
* **GIVEN** the seeded database
* **WHEN** a `GET /api/reports/region/region-north` request is made
* **THEN** `totalActivities` = 4, `completedActivities` = 1, `completionRate` = 0.25,
  `overdueCount` = 2, `blockedCount` = 1, `overdueByCategory` and `storeBreakdown` unchanged from
  Sprint 1
* **AND** `mvn test` passes with no modification to any Sprint 1 assertion

## Architectural Guardrails

* **The event record lives in `shared/events` and imports no module package.** `ModuleBoundaryTest`
  rule 3b fails the build if anything under `shared` depends on `activities`, `programmes`, `staff`,
  `alerts` or `reports`. That is why `storeCount` is an `int` and the ids are `String`.
* **Publish through `EventBus`, never `ApplicationEventPublisher`.** `ReportService` takes the
  project's `EventBus` interface in its constructor and calls `eventBus.publish(...)`.
  `SpringEventBus` is the only class in the codebase that knows about Spring's publisher, and the
  Evaluator greps for `eventBus.publish(`.
* **`@Transactional` on `regionalRollup` is mandatory, not stylistic.** A `GET` runs with no
  transaction by default, and Spring skips `@TransactionalEventListener` callbacks entirely when none
  is active (`fallbackExecution` defaults to `false`). Publishing from a non-transactional method
  loses the event with no exception and no log line. Use plain `@Transactional`, not
  `readOnly = true` — the intent of the method is read-plus-publish, and `readOnly` invites a later
  reader to assume nothing downstream writes.
* **Publish after the response object is built, never before validation.** The event must not escape
  for a request that goes on to throw. Since dispatch is after commit a thrown `AppError` would roll
  back anyway, but ordering the call last keeps Scenario 4 true at the service level too, where the
  `RecordingEventBus` has no transaction semantics to save it.
* **Do not add the listener in this sprint.** No handler method for
  `RegionalRollupRequestedEvent` in `ReportEventListener`. Scenario 5 asserts the absence, and
  building it here would collapse the publisher/subscriber split that
  `sprint-decomposition` §2 requires.
* **Do not touch `ReportEventListener.onProgrammeClosed`.** The `PROGRAMME_CLOSED` →
  `STORE_SUMMARY` flow and its tests stay exactly as they are.
