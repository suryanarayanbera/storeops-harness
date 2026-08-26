# Generator Summary: Sprint 1 — Region membership and the rollup endpoint

`./mvnw clean test` → **BUILD SUCCESS**. 230 tests, 0 failures, 0 errors. Checkstyle 0 violations,
SpotBugs clean, all 12 `ModuleBoundaryTest` rules pass, JaCoCo check passes.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Region rollup returns aggregated totals across both seeded stores | yes | `RegionalRollupIntegrationTest.rollupAggregatesTheSeededRegion` |
| 2 | Overdue counts broken down by `TaskCategory`, zeroes present | yes | `RegionalRollupIntegrationTest.rollupBreaksOverdueDownByCategory` |
| 3 | Blocked activities listed in full, not just counted | yes | `RegionalRollupIntegrationTest.rollupListsTheBlockedActivity` |
| 4 | Per-store breakdown present and sorted by `storeId` | yes | `RegionalRollupIntegrationTest.rollupBreaksDownPerStore` |
| 5 | A store with no activities still appears at a zero rate | yes | `ReportServiceTest.regionalRollupKeepsEmptyStores` |
| 6 | Unknown region is a 404 `REGION_NOT_FOUND`, not an all-zero report | yes | `RegionalRollupIntegrationTest.unknownRegionIsNotFound` |
| 7 | Blank or null region id is a `ValidationError` | yes | `ReportServiceTest.regionalRollupRejectsBlankRegionId` |
| 8 | Unknown `requestedBy` rejected before aggregation; omitted defaults to `api` | yes | `RegionalRollupIntegrationTest.requestedByIsValidatedAgainstTheStaffRoster` |
| 9 | Staff region lookup exposed on the service, not the repository | yes | `UserServiceTest.findByRegionIdSpansStores` |

Second and third proofs, where a criterion has both a service-level and an end-to-end assertion:

| AC | Additional test |
| --- | --- |
| 1 | `ReportServiceTest.regionalRollupAggregatesAcrossStores` |
| 2 | `ReportServiceTest.regionalRollupBreaksOverdueDownByCategory` (asserts `containsOnly` on all five keys) |
| 3 | `ReportServiceTest.regionalRollupListsBlockedActivities` |
| 4 | `ReportServiceTest.regionalRollupBreaksDownByStore` |
| 6 | `ReportServiceTest.regionalRollupRaisesNotFoundForAnUnknownRegion` (asserts code and 404) |
| 8 | `ReportServiceTest.regionalRollupRejectsAnUnknownRequester` (`verifyNoInteractions(taskService)` proves "before any aggregation runs"); `ReportServiceTest.regionalRollupDefaultsTheRequester` (`verify(userService, never()).exists("api")`) |
| 9 | `UserServiceTest.findByRegionIdReturnsEmptyForAnUnknownRegion`, `.findByRegionIdRejectsBlankCriteriaByReturningEmpty`, `.findByRegionIdSeparatesRegions` |

Tests written beyond the contract:

* `ReportServiceTest.regionalRollupSortsBlockedActivitiesDeterministically` — the guardrail says
  sorting is part of the contract, so it is asserted against a roster and a repository that both
  return the wrong order.
* `ReportServiceTest.regionalRollupDeduplicatesStores` — two staff at one store is one store, and a
  staff member with a null `storeId` adds none.
* `RegionalRollupIntegrationTest.storeSummaryIsUnaffected` — regression cover for the shared helpers
  extracted below.

## 2. Files changed

### staff
- `repository/UserRepository.java` — added `findByRegionId(String)` to the interface
- `repository/UserJpaRepository.java` — added the derived query `findByRegionId(String, Sort)`
- `repository/JpaUserRepository.java` — implemented `findByRegionId`, reusing `DEFAULT_SORT`
- `service/UserService.java` — added `findByRegionId(String)`; null/blank returns empty, leavers
  included to match `findByStoreId`
- **test** `staff/service/UserServiceTest.java` — 4 new tests for `findByRegionId`

### reports
- `dto/RegionalRollupResponse.java` — **new** record; compact constructor defensively copies the map
  and both lists
- `dto/StoreRollupEntry.java` — **new** record, per-store row
- `dto/BlockedActivitySummary.java` — **new** record; `category` and `priority` carried as enum names
- `service/ReportService.java` — added `regionalRollup(String, String)`, the `API_REQUESTER`
  constant, and private helpers `storeEntry`, `blockedActivities`, `countWithStatus`,
  `completionRate`, `countOverdueByCategoryWithZeroes`
- `routes/ReportRoutes.java` — added `GET /region/{regionId}` with the optional `requestedBy` query
  parameter
- **test** `reports/service/ReportServiceTest.java` — 10 new tests, plus a `task(...)` overload
  taking a store id and a `userAt(...)` helper
- **test** `reports/routes/ReportRoutesTest.java` — 3 new `@WebMvcTest` tests

### root / support
- **test** `RegionalRollupIntegrationTest.java` — **new** `@SpringBootTest`, 7 tests against the real
  seed and the real cross-module reads
- **test** `support/FakeUserRepository.java` — implemented `findByRegionId`; added
  `withRegion(id, role, storeId, regionId)`; existing `with(...)` now delegates to it and still
  defaults to `region-north`

Nothing under `shared`, `activities`, `programmes` or `alerts` was touched.

## 3. Deviations from the contract text

Three, all declared rather than quiet:

1. **Fixed clock instant.** The contract's Scenario 2 names
   `Clock.fixed(Instant.parse("2026-02-01T00:00:00Z"))`. `how-to-test` §2 says to always pin to
   `2026-02-01T10:00:00Z`, which is also the existing `ReportServiceTest.NOW`. I used the skill's
   instant. Both are after the January seed due dates, so every asserted figure is identical; using
   two different instants in one test class would not have been.
2. **Where AC 3's seed values are proved.** The contract asserts `priority` = `LOW` and `assigneeId`
   = `user-005` for `task-004`. Those are `data.sql` values, and the service unit test's fake builds
   activities with `HIGH`/`user-004`, so a fake-based test asserting `LOW` would only be asserting
   its own fixture. The seed values are asserted in
   `RegionalRollupIntegrationTest.rollupListsTheBlockedActivity` against real H2;
   `ReportServiceTest.regionalRollupListsBlockedActivities` asserts the same mapping over its own
   fixture.
3. **Two behaviour-preserving extractions in `ReportService`.** `countWithStatus(tasks, status)` and
   `completionRate(completed, total)` are new private helpers, and the pre-existing `storeSummary`
   and `countByStatus` now call them instead of repeating the same stream and the same
   `total == 0 ? 0.0 : round(...)` expression a second and third time. No existing test was modified;
   `ReportServiceTest.storeSummaryAggregates`, `.storeSummaryHandlesEmptyStore` and the new
   `RegionalRollupIntegrationTest.storeSummaryIsUnaffected` cover the refactor.
   `countOverdueByCategory` was left exactly as it was — the zero-padded variant is a separate
   method, because `storeSummaryHandlesEmptyStore` asserts the store summary's map is empty.

## 4. Guardrails observed

* `reports` imports `staff.service`, `staff.domain`, `activities.service` and `activities.domain`
  only. No repository import; `ModuleBoundaryTest` rules 1, 1b, 4 and 4b pass.
* No `region_id` was added to `TaskEntity` and no cross-module query was written. The store set comes
  from `UserService.findByRegionId`, then one `TaskService.findByStoreId` per store, aggregated in
  Java.
* `ReportService.regionalRollup` performs no write of any kind.
* `EventBus` is **not** injected into `ReportService`, and `regionalRollup` is **not** `@Transactional`
  — both deferred to Sprint 2 as the contract requires.
* Every failure path throws an `AppError` subtype: `ValidationError` for a blank region,
  `NotFoundError.of("Region", …)` and `NotFoundError.of("User", …)` for the two 404s.
* `ReportRoutes.regionalRollup` binds and delegates; the defaulting of `requestedBy` lives in the
  service.

## 5. Known gaps

none
