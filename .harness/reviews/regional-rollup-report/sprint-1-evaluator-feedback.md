# Evaluator Feedback: Sprint 1 — Region membership and the rollup endpoint

## 1. VERDICT

**CONDITIONAL PASS**

All automated and manual hard gates pass. Three cosmetic issues remain, listed in §4 — a test comment
that contradicts its own assertion, a redundant assertion, and a `LinkedHashMap` whose ordering is
discarded downstream. None changes behaviour and none blocks the next sprint, which is
`evaluation-criteria` §5.4.

## 2. SCORE

**A 40/40 · B 34/35 · C 23/25 = 97**

* **A — Contract fulfilment (40/40).** All nine acceptance criteria have a test that asserts the
  criterion's THEN. Six carry a second proof at a different level. Three deviations from the contract
  text were declared in `generator-summary.md` §3 and all three are correct calls, in particular
  proving AC 3's seed values (`priority` = `LOW`, `assigneeId` = `user-005`) in a `@SpringBootTest`
  rather than against a fake that sets its own priority — a fake-based assertion there would have
  been testing its own fixture.
* **B — Architectural compliance (34/35).** Every gate green; guardrails observed, including the two
  negative ones. Minus one for finding 2.
* **C — Test quality (23/25).** Strong negative coverage and no criterion resting on a status code
  alone. Minus two for findings 1 and 3, both in test code.

## 3. GATE RESULTS

`./mvnw clean test` → **BUILD SUCCESS**, exit code 0.

| Dimension | Gate | Result |
| --- | --- | --- |
| A | JUnit | **PASS** — `Tests run: 230, Failures: 0, Errors: 0, Skipped: 0`. Baseline was 206; this sprint added 24 (UserServiceTest 7→11, ReportServiceTest 7→17, ReportRoutesTest 2→5, RegionalRollupIntegrationTest 7 new). |
| B | `ModuleBoundaryTest` | **PASS** — `Tests run: 12, Failures: 0, Errors: 0` |
| B | Checkstyle (`IllegalThrows`, `NoRawErrorThrows`, `IllegalCatch`) | **PASS** — `You have 0 Checkstyle violations.` |
| B | SpotBugs | **PASS** — `spotbugs:check` completed with no reported bugs |
| C | `jacoco:check` | **PASS** — `jacoco-check` completed with no rule violations |

### Scope check

`git status --porcelain` lists exactly 14 source and test paths, and every one appears in
`generator-summary.md` §2. Three new DTOs, one new integration test, ten modified files. **No
undeclared changes.** The four `.harness/output/` files are harness artefacts, not code.

### Manual hard gates

| Gate | Result | Evidence |
| --- | --- | --- |
| Missing events | **PASS (N/A)** | The contract forbids publishing this sprint. `grep -nE "EventBus\|eventBus"` over `reports/service` and `reports/routes` returns nothing, so `EventBus` was not injected early either. |
| Silent event wiring | **PASS (N/A)** | No event is published and no listener was added. `regionalRollup` correctly carries no `@Transactional`, as the contract requires; adding it in Sprint 2 alongside the publish call is the right order, since the annotation is what makes after-commit dispatch possible at all. |
| Logic in routes | **PASS** | `ReportRoutes.regionalRollup` (`routes/ReportRoutes.java:36-45`) binds `@PathVariable` and one optional `@RequestParam` and returns `ResponseEntity.ok(...)`. No arithmetic, no enum conditional, no defaulting — `requestedBy` defaults to `api` inside the service, which is where an attribution rule belongs. |
| Criteria proved only by a status code | **PASS** | Every `RegionalRollupIntegrationTest` method asserts JSON body values after `status().isOk()`, and both 404 tests assert `$.code` (`REGION_NOT_FOUND`, `USER_NOT_FOUND`) rather than the status alone. |
| Negative tests per new service method | **PASS** | `regionalRollup`: blank and null region (`regionalRollupRejectsBlankRegionId`), unknown region (`regionalRollupRaisesNotFoundForAnUnknownRegion`), unknown requester (`regionalRollupRejectsAnUnknownRequester`). `findByRegionId`: unknown region, null, empty and blank (`findByRegionIdReturnsEmptyForAnUnknownRegion`, `findByRegionIdRejectsBlankCriteriaByReturningEmpty`). |
| Absence assertions with no positive counterpart | **PASS** | `verifyNoInteractions(taskService)` in `regionalRollupRejectsAnUnknownRequester` is paired with the positive path in `regionalRollupDefaultsTheRequester`; `blockedActivities().isEmpty()` in `regionalRollupKeepsEmptyStores` is paired with the populated list in `regionalRollupListsBlockedActivities`. |
| Dropped acceptance criteria | **PASS** | None. `generator-summary.md` §5 declares `none`, and the AC table checks out against the contract. |
| Invented domain vocabulary | **PASS** | `region-north`, `store-001`/`store-002`, `task-001`–`task-004`, `user-001`/`user-005`, all five `TaskCategory` values and both error codes trace to `app-context`. The route base is `/api/reports`, matching §3. `REGION_NOT_FOUND` and `USER_NOT_FOUND` both come from `NotFoundError.of(...)` rather than hand-built strings. |

### Notes on two decisions the contract flagged for review

Both were pre-declared in `spec.md` and are implemented as specified, so neither is scored as a
finding:

* **404 on an unknown region**, diverging from `storeSummary`'s zero-filled response for an unknown
  store. `RegionalRollupIntegrationTest.storeSummaryIsUnaffected` confirms the store endpoint's own
  behaviour is unchanged.
* **Staff as the authority for region membership**, leaving a store with activities but no roster
  invisible to the rollup. Correctly implemented against `UserService`, and the limitation is
  recorded in `spec.md` rather than hidden. It stays an open modelling gap for the human, not a
  sprint defect.

## 4. FINDINGS

Three, all cosmetic. Fold them into Sprint 2 rather than opening a retry.

1. `src/test/java/com/cognizant/storeops/reports/routes/ReportRoutesTest.java:108` — Test quality
   (`how-to-review` §4, weak tests). The comment states "Omitting the parameter must reach the
   service as null, not as an empty string", but the `verify` on the next line asserts the
   `"user-001"` call and proves nothing about omission. The null-binding claim is in fact covered by
   `regionalRollupReturnsMetrics`, which stubs `regionalRollup("region-north", null)`. Either move
   the comment to that test or add `verify(reportService).regionalRollup("region-north", null)` to a
   request that omits the parameter.

2. `src/main/java/com/cognizant/storeops/reports/service/ReportService.java:203` — Reuse and intent.
   `countOverdueByCategoryWithZeroes` builds a `LinkedHashMap` so the five categories land in
   `TaskCategory` declaration order, then `RegionalRollupResponse`'s compact constructor applies
   `Map.copyOf`, which makes no iteration-order guarantee. The ordering work is discarded before any
   caller sees it. Not a defect — no acceptance criterion or javadoc claims a key order, and this
   mirrors the pre-existing `countByStatus` / `StoreSummaryResponse` pair — but either the
   `LinkedHashMap` should become a plain `HashMap`, or the DTO should preserve order if the order is
   meant to be part of the contract. Decide which, rather than leaving both.

3. `src/test/java/com/cognizant/storeops/reports/service/ReportServiceTest.java` in
   `regionalRollupKeepsEmptyStores` — Redundant assertion. `assertThat(...).isZero().isNotNaN()`
   appears twice; `isZero()` already excludes `NaN`, so the second call adds nothing. Drop
   `.isNotNaN()`, or keep only the `isNotNaN()` on a value that is not also asserted zero. The
   division-by-zero risk the pair was aiming at is genuinely covered by `isZero()` alone.

## 5. READY FOR

Sprint 2 — publishing `REGIONAL_ROLLUP_REQUESTED`. The two deferred guardrails are confirmed
outstanding and correctly so: `ReportService` has no `EventBus` dependency and `regionalRollup` has
no `@Transactional`. Sprint 2 must add both together, and Sprint 3's listener is what finally proves
the wiring, since neither annotation fails loudly on its own.
