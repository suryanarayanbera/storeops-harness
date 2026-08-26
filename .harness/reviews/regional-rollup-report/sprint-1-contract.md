# Sprint 1: Region membership and the rollup endpoint

## Goal

Deliver the complete read path for `GET /api/reports/region/{regionId}`: resolve a region to its set
of stores via the `staff` module's service layer, aggregate activities per store in the `reports`
module, and return `RegionalRollupResponse`. No event is published in this sprint — the endpoint is
purely a read.

Files in scope:
* `staff/repository/UserRepository.java`, `staff/repository/JpaUserRepository.java`,
  `staff/repository/UserJpaRepository.java` — add `findByRegionId`
* `staff/service/UserService.java` — add `findByRegionId`
* `reports/dto/RegionalRollupResponse.java`, `reports/dto/StoreRollupEntry.java`,
  `reports/dto/BlockedActivitySummary.java` — new
* `reports/service/ReportService.java` — add `regionalRollup`
* `reports/routes/ReportRoutes.java` — add the route

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: Region rollup returns aggregated totals across both seeded stores**
* **GIVEN** the seeded H2 database, where `region-north` contains `store-001` (with `task-001` `TODO`,
  `task-002` `IN_PROGRESS`, `task-003` `DONE`) and `store-002` (with `task-004` `BLOCKED`)
* **WHEN** a `GET /api/reports/region/region-north` request is made
* **THEN** the response is HTTP 200 with `regionId` = `region-north`, `storeCount` = 2,
  `totalActivities` = 4, `completedActivities` = 1, `completionRate` = 0.25, `overdueCount` = 2 and
  `blockedCount` = 1
* **AND** `generatedAt` is non-null

**Scenario 2: Overdue counts are broken down by TaskCategory with zeros present**
* **GIVEN** the seeded database and a `Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"), UTC)`
  injected into `ReportService`
* **WHEN** `reportService.regionalRollup("region-north", "api")` is called
* **THEN** `overdueByCategory` equals exactly `{RESTOCKING=1, PLANOGRAM=1, AUDIT=0, COMPLIANCE=0,
  GENERAL=0}` — all five `TaskCategory` names present, zero-valued keys included
* **AND** `COMPLIANCE` is 0 even though `task-003` has a past `dueAt`, because `isOverdueAt` excludes
  `DONE`
* **AND** `AUDIT` is 0 even though `task-004` is not `DONE`, because it has a `null` `dueAt`

**Scenario 3: Blocked activities are listed in full, not just counted**
* **GIVEN** the seeded database
* **WHEN** a `GET /api/reports/region/region-north` request is made
* **THEN** `blockedActivities` has exactly one element with `taskId` = `task-004`, `storeId` =
  `store-002`, `category` = `"AUDIT"`, `priority` = `"LOW"` and `assigneeId` = `user-005`
* **AND** `category` and `priority` are JSON strings, not objects

**Scenario 4: Per-store breakdown is present and sorted by storeId**
* **GIVEN** the seeded database
* **WHEN** a `GET /api/reports/region/region-north` request is made
* **THEN** `storeBreakdown` has two entries, `storeBreakdown[0].storeId` = `store-001` and
  `storeBreakdown[1].storeId` = `store-002`
* **AND** `storeBreakdown[0]` reads `totalActivities` = 3, `completedActivities` = 1,
  `completionRate` = 0.3333, `overdueCount` = 2, `blockedCount` = 0
* **AND** `storeBreakdown[1]` reads `totalActivities` = 1, `completedActivities` = 0,
  `completionRate` = 0.0, `overdueCount` = 0, `blockedCount` = 1

**Scenario 5: A store in the region with no activities still appears with a zero completion rate**
* **GIVEN** a `FakeUserRepository` holding one user in `region-south` at `store-009`, and a
  `FakeTaskRepository` holding no activities for `store-009`
* **WHEN** `reportService.regionalRollup("region-south", "api")` is called
* **THEN** `storeCount` = 1 and `storeBreakdown` has one entry for `store-009` with
  `totalActivities` = 0 and `completionRate` = 0.0
* **AND** no division-by-zero occurs and `completionRate` is not `NaN`

**Scenario 6: An unknown region is a 404, not an all-zero report**
* **GIVEN** the seeded database, which has no staff in `region-atlantis`
* **WHEN** a `GET /api/reports/region/region-atlantis` request is made
* **THEN** the response is HTTP 404 with `ErrorResponse.code` = `REGION_NOT_FOUND`
* **AND** the thrown error is `NotFoundError.of("Region", regionId)`, not a hand-built code string

**Scenario 7: A blank region id is a validation failure**
* **GIVEN** the seeded database
* **WHEN** `reportService.regionalRollup("   ", "api")` is called
* **THEN** a `ValidationError` is thrown carrying code `VALIDATION_FAILED`, which
  `GlobalExceptionHandler` renders as HTTP 400
* **AND** the same holds for `null`

**Scenario 8: An unknown requestedBy is rejected before any aggregation runs**
* **GIVEN** the seeded database
* **WHEN** a `GET /api/reports/region/region-north?requestedBy=user-999` request is made
* **THEN** the response is HTTP 404 with `ErrorResponse.code` = `USER_NOT_FOUND`
* **AND** when the parameter is omitted the request succeeds with HTTP 200, `requestedBy` defaulting
  to the literal `"api"` and no staff lookup performed

**Scenario 9: Staff region lookup is exposed on the service, not the repository**
* **GIVEN** the seeded database
* **WHEN** `userService.findByRegionId("region-north")` is called
* **THEN** all five seeded users are returned
* **AND** `userService.findByRegionId("region-atlantis")` returns an empty list rather than throwing
* **AND** a blank or null region id returns an empty list, consistent with the existing
  `findByStoreId` guard

## Architectural Guardrails

* **`reports` must not import `staff.repository` or `activities.repository`.** Store discovery goes
  through `UserService.findByRegionId` and activity reads through `TaskService.findByStoreId`.
  `ModuleBoundaryTest` rules 1, 4 and 4b fail the build on a repository import, and rule 1b fails if
  anything outside a module's own `service` package touches its repository.
* **No cross-module SQL join, and no region column on `tasks`.** The set of stores comes from
  `staff`; the reports module then loops `taskService.findByStoreId(storeId)` once per store and
  aggregates in Java. Adding `region_id` to `TaskEntity` so a single query could group by region
  would give `activities` a copy of data `staff` owns and put a join across module tables —
  `architecture-principles` §3 bans both.
* **`ReportService` writes nothing outside its own module.** No `save`, no setter, no status
  transition on a `Task`, `Project` or `User` appears in this sprint. The rollup is a pure read.
* **The route holds no aggregation logic.** `ReportRoutes` binds the path variable and the optional
  query parameter and returns `ResponseEntity.ok(...)`. Counting, rate arithmetic and sorting live in
  `ReportService`. Rule 5 checks the layering; Checkstyle and the review will catch logic that drifts
  into the controller.
* **Do not publish an event in this sprint.** `eventBus` must not be injected into `ReportService`
  yet — that is Sprint 2, and adding it early leaves an unused field that Checkstyle and SpotBugs
  will flag.
* **Do not annotate `regionalRollup` with `@Transactional` yet.** It is required in Sprint 2 for
  after-commit delivery; with no publish call it is noise on a read-only method.
* **Every failure path is an `AppError` subtype.** No `IllegalArgumentException`, no
  `ResponseStatusException`. Rule 6 fails the build on generic throws and rule 6b requires the error
  vocabulary to stay in `shared/error`.
* **Sorting is part of the contract.** `storeBreakdown` ascending by `storeId`, `blockedActivities`
  ascending by `storeId` then `taskId`. Repository iteration order must not leak into the JSON, or
  Scenarios 3 and 4 become flaky.
