# Sprint 3: Queue the REGIONAL_ROLLUP report record

## Goal

Consume `RegionalRollupRequestedEvent` in the reports module's own listener and turn it into a
persisted `Report` row: `reportType` = `REGIONAL_ROLLUP`, `status` = `PENDING`, `scopeId` = the region
id, `requestedBy` = the event's `requestedBy`. This closes the feature — the endpoint now both answers
the request and records that it happened, with the record written on a separate transaction so a
failure to record cannot fail the caller's `GET`.

Files in scope:
* `reports/listener/ReportEventListener.java` — add `onRegionalRollupRequested`

`ReportService.queue(...)` already does the write and needs no change.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: The GET produces a persisted PENDING report record**
* **GIVEN** a `@SpringBootTest` against the seeded H2 database, where the reports table starts empty
* **WHEN** a `GET /api/reports/region/region-north?requestedBy=user-001` request is made and its
  transaction commits
* **THEN** the response is HTTP 200
* **AND** `reportService.findByScopeId("region-north")` returns exactly one `Report` with
  `reportType` = `REGIONAL_ROLLUP`, `status` = `PENDING`, `scopeId` = `region-north`,
  `requestedBy` = `user-001`, a non-null `requestedAt` and a `null` `readyAt`
* **AND** the assertion is on the persisted row, not on the HTTP status alone — a 200 is returned
  whether or not the listener fires

**Scenario 2: The listener queues a report from the event directly**
* **GIVEN** a `ReportEventListener` built over a `ReportService` backed by a `FakeReportRepository`
* **WHEN** `onRegionalRollupRequested(new RegionalRollupRequestedEvent("region-north", "user-001", 2,
  someInstant))` is called
* **THEN** one `Report` is saved with `reportType` = `REGIONAL_ROLLUP` and `scopeId` = `region-north`
* **AND** `ReportType.STORE_SUMMARY` is not used — the two handlers must not be confused

**Scenario 3: Two calls produce two records**
* **GIVEN** the seeded database
* **WHEN** `GET /api/reports/region/region-north` is called twice
* **THEN** `reportService.findByScopeId("region-north")` returns two `Report` rows with distinct ids
* **AND** each is `PENDING`, confirming one record per request rather than an upsert

**Scenario 4: A rejected request records nothing**
* **GIVEN** the seeded database
* **WHEN** `GET /api/reports/region/region-atlantis` returns HTTP 404 `REGION_NOT_FOUND`
* **THEN** `reportService.findByScopeId("region-atlantis")` is empty
* **AND** the same holds after a 404 `USER_NOT_FOUND` from
  `GET /api/reports/region/region-north?requestedBy=user-999` — `findByScopeId("region-north")`
  gains no row

**Scenario 5: Delivery happens after commit, so a rolled-back read records nothing**
* **GIVEN** the seeded database
* **WHEN** `regionalRollup` is invoked inside a caller-managed transaction that is then rolled back —
  for example a `TransactionTemplate` whose callback calls the service and then sets rollback-only
* **THEN** no `Report` row exists for that region
* **AND** the test asserts the row count rather than the absence of an exception

**Scenario 6: A failing listener does not fail the caller's request**
* **GIVEN** a `@SpringBootTest` with an additional test-scoped
  `@TransactionalEventListener(phase = AFTER_COMMIT)` subscriber on
  `RegionalRollupRequestedEvent` that counts its invocations and then throws — follow the shape of the
  existing `FailingSubscriber` / `FailingStatusSubscriber` doubles
* **WHEN** a `GET /api/reports/region/region-north` request is made
* **THEN** the response is HTTP 200
* **AND** the failing subscriber's invocation count is 1, so the test cannot pass vacuously by never
  dispatching
* **AND** the `REGIONAL_ROLLUP` row from `ReportEventListener` still exists, proving one broken
  subscriber does not suppress another

**Scenario 7: The PROGRAMME_CLOSED flow is untouched**
* **GIVEN** the seeded database with `project-001` `ACTIVE` in `store-001`
* **WHEN** `project-001` is closed
* **THEN** a `STORE_SUMMARY` report is queued for `store-001` exactly as before
* **AND** no `REGIONAL_ROLLUP` row appears — the two handlers respond to different events

**Scenario 8: The whole gate passes**
* **GIVEN** the completed feature
* **WHEN** `./mvnw clean test` runs
* **THEN** Checkstyle, SpotBugs, all twelve `ModuleBoundaryTest` rules, every JUnit test and the
  JaCoCo coverage gate pass
* **AND** no previously passing test was modified to accommodate this feature

## Architectural Guardrails

* **The listener needs both annotations, and each failure is silent.**
  `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` **and**
  `@Transactional(propagation = Propagation.REQUIRES_NEW)`. Without `REQUIRES_NEW` the publishing
  transaction has already committed by dispatch time, so a write that joins it is never flushed:
  the listener runs, the log line prints, and no row appears. `onProgrammeClosed` in the same class is
  the reference implementation — match it.
* **Leave the `ErrorHandler` bean in `EventBusConfiguration` alone.** Spring's
  `SimpleApplicationEventMulticaster` propagates listener exceptions to the publisher by default.
  Remove or bypass that bean and Scenario 6 turns a reporting bug into a failed `GET` — precisely the
  coupling the bus exists to prevent.
* **The listener writes only to `ReportRepository`, through `ReportService`.** It must not reach into
  `TaskService`, `ProjectService` or `UserService` to enrich the row. `Report` stores scope and status,
  not computed figures; the aggregate is the response body, not persisted state.
  `architecture-principles` §4 keeps `reports` read-only towards other modules.
* **Never inject `ReportService` into `ReportRoutes`' path to write the row inline.** The record is
  created by the listener, not by the service method that answers the request. Doing it inline would
  make a reports-table failure fail the caller's read and would leave the event with no subscriber.
* **One handler method per event type.** Do not overload `onProgrammeClosed` to accept both events or
  branch on an event supertype. Rule 3 and the existing tests both read more clearly with a dedicated
  handler, and Scenario 7 checks the separation.
* **Assert the side effect, never just the status code.** Every one of the wiring mistakes above
  returns HTTP 200. A test that stops at `status().isOk()` passes through all of them; Scenarios 1,
  3, 5 and 6 must read the persisted rows or the invocation counter.
