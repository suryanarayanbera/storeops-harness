# Sprint 1 Run Log — Regional Rollup Report

## Summary

| Field | Value |
| --- | --- |
| Sprint ID | 1 (first of three in the regional rollup report feature) |
| Goal | Deliver the read path for `GET /api/reports/region/{regionId}`: resolve a region to its stores through the staff service, aggregate activity completion rates, overdue counts by `TaskCategory` and the blocked activity list, and return `RegionalRollupResponse`. No event published. |
| Modules touched | `reports` (routes, service, dto); `staff` (service, repository) |
| Final verdict | CONDITIONAL PASS |
| Iterations used | 1 of 3 |
| Escalated | no |
| Estimated token cost | ~28.7k (includes this feature's whole planning pass) |

`./mvnw clean test` exit 0. JUnit 230/230 (206 at the previous close, +24), ArchUnit 12/12,
Checkstyle 0 violations, SpotBugs clean, JaCoCo check passed against floors of 85% line / 65% branch
bundle and 70% / 50% per class.

**New feature run. Archived under `.harness/reviews/regional-rollup-report/` rather than flat, per
`monitor.agent.md` Output B.6 — this feature restarts sprint numbering at 1 and would otherwise
overwrite the shift-handover feature's `sprint-1-*` files.**

## Iterations

| # | Verdict | Gate or rule that failed | Fix applied |
| --- | --- | --- | --- |
| 1 | CONDITIONAL PASS | none — five automated gates and eight LLM-assessed hard gates passed on the first attempt | none required; three cosmetic cleanups left outstanding |

## Files Changed

### staff — repository
- `repository/UserRepository.java` — added `findByRegionId(String)` to the interface.
- `repository/UserJpaRepository.java` — added the derived query `findByRegionId(String, Sort)`.
- `repository/JpaUserRepository.java` — implemented it, reusing the existing `DEFAULT_SORT`.

### staff — service
- `service/UserService.java` — added `findByRegionId(String)`. Null or blank returns empty rather
  than every region; leavers are included, matching `findByStoreId`, because reports counts them.
  This is the read that makes the whole feature possible: StoreOps has no `Store` entity, so
  `users.region_id` is the only record of which stores a region contains.

### reports — dto
- `dto/RegionalRollupResponse.java` — **new** record. Compact constructor defensively copies the map
  and both lists.
- `dto/StoreRollupEntry.java` — **new** record, one row per store.
- `dto/BlockedActivitySummary.java` — **new** record. `category` and `priority` carried as enum
  names rather than enum types.

### reports — service
- `service/ReportService.java` — added `regionalRollup(String, String)`, the `API_REQUESTER`
  constant, and private helpers `storeEntry`, `blockedActivities`, `countWithStatus`,
  `completionRate`, `countOverdueByCategoryWithZeroes`. `storeSummary` and `countByStatus` now call
  the two extracted helpers instead of repeating the same stream and the same
  `total == 0 ? 0.0 : round(...)` expression; behaviour unchanged and no existing test modified.

### reports — routes
- `routes/ReportRoutes.java` — added `GET /region/{regionId}` with an optional `requestedBy` query
  parameter. Binds and delegates only; the defaulting to `api` lives in the service.

### tests — new
- `RegionalRollupIntegrationTest.java` — **new**, 7 tests, `@SpringBootTest` with the SLA sweep
  disabled and `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`. Asserts the figures that fall out of
  `data.sql` itself and exercises both cross-module reads for real.

### tests — modified
- `reports/service/ReportServiceTest.java` — 10 new tests (7→17), plus a `task(...)` overload taking
  a store id and a `userAt(...)` helper.
- `reports/routes/ReportRoutesTest.java` — 3 new `@WebMvcTest` tests (2→5).
- `staff/service/UserServiceTest.java` — 4 new tests for `findByRegionId` (7→11).
- `support/FakeUserRepository.java` — implemented `findByRegionId`; added
  `withRegion(id, role, storeId, regionId)`. The existing `with(...)` now delegates to it and still
  defaults to `region-north`.

### untouched
All of `shared`, `activities`, `programmes` and `alerts`. No new event, entity, table, enum value or
schema change; no change to `data.sql`, `application.yml`, `pom.xml` or `EventBusConfiguration`. One
new endpoint and two new error codes, both produced by `NotFoundError.of(...)` rather than
hand-built.

## Conditional Pass Cleanups

Three items, all cosmetic, all **assigned to Sprint 2**. Two sit in files Sprint 2 already has to
open, so collecting them costs nothing.

1. **`ReportRoutesTest.java:108` — a comment that contradicts its own assertion.** The comment claims
   to prove that omitting `requestedBy` reaches the service as `null` rather than `""`, but the
   `verify` on the next line asserts the `"user-001"` call. The null-binding is in fact covered
   incidentally by `regionalRollupReturnsMetrics`, which stubs `regionalRollup("region-north", null)`.
   Fix: move the comment, or add `verify(reportService).regionalRollup("region-north", null)` to a
   request that omits the parameter. Sprint 2 touches this file anyway.
2. **`ReportService.java:203` — a `LinkedHashMap` whose ordering is discarded.**
   `countOverdueByCategoryWithZeroes` builds a `LinkedHashMap` so the five categories land in
   `TaskCategory` declaration order, then `RegionalRollupResponse`'s compact constructor applies
   `Map.copyOf`, which makes no iteration-order guarantee. No criterion or javadoc claims a key
   order, and this mirrors the pre-existing `countByStatus` / `StoreSummaryResponse` pair, so it is
   not a defect — but the ordering work is dead. Decide: plain `HashMap`, or preserve order in the
   DTO and say so.
3. **`ReportServiceTest.regionalRollupKeepsEmptyStores` — redundant assertion.**
   `assertThat(...).isZero().isNotNaN()` appears twice; `isZero()` already excludes `NaN`.

## Quality Trend Notes

Sixth sprint logged, third feature, first entry under its own folder. Scores: **92, 98, 96, 98, 99,
97**. Iterations: **1, 1, 1, 1, 1, 1**.

* **The 3-attempt escalation budget is still untouched after six sprints and three features.** The
  mechanism is unchanged and worth restating because this sprint tested it hardest: the contract named
  file paths, the exact response field list, both error codes, the sort order of two lists, and the
  precise figures the seed produces. The Generator had almost nothing to invent, and the one thing it
  did invent — where to prove a seed value — it declared.
* **First feature where planning found a genuine modelling gap rather than a naming one.** The two
  contract defects in the previous feature were over-specification (a clock instant contradicting a
  skill, a stale endpoint count). This time planning surfaced something structural: there is no
  `Store` entity, so region membership exists only on `users.region_id` and `projects.region_id`, and
  a regional feature has to pick one. That is a design decision the Planner correctly escalated to the
  spec rather than resolving quietly, and it is the kind of finding the harness has not produced
  before. **It remains open and needs a human**: a store with activities but no staff roster is
  invisible to the rollup.
* **The clock-instant defect from the previous feature recurred, and the Generator handled it
  correctly this time.** `sprint-1-contract.md` Scenario 2 named `2026-02-01T00:00:00Z`; `how-to-test`
  §2 mandates `2026-02-01T10:00:00Z`. Sprint 5's log called this out as the Planner's characteristic
  failure mode — naming a value it should have deferred to the skill — and it happened again in the
  first contract of the next feature. The Generator used the skill's instant and declared the
  deviation, so it cost nothing, but **the Planner instruction set is what should change**: it is now
  a repeat offence, not a one-off. Candidate fix: `sprint-decomposition` should tell the Planner to
  reference `how-to-test` for fixture constants instead of naming them.
* **Five consecutive sprints with zero behavioural defects in Generator-written production code.**
  Sprint 1 of the harness produced one; nothing since. This sprint's three findings are a misleading
  test comment, a dead `LinkedHashMap`, and a redundant assertion. The pattern from Sprint 5's log
  holds exactly: the harness stopped catching bad code early and now catches incomplete proof,
  cosmetic drift and stale documentation.
* **An architectural rule was load-bearing for the first time in a *read* feature.** Every previous
  sprint exercised the boundary rules on writes and events. This one had a genuine temptation to
  violate rule 1 — a single query grouping activities by region would be one join away — and the
  design instead loops `taskService.findByStoreId` per store and aggregates in Java. Worth noting for
  the capstone: the N+1-shaped read is the *correct* answer under these rules, and someone reviewing
  for performance rather than architecture would call it a defect. The rules and the instinct
  disagree, and the rules won because the contract's guardrail named the exact temptation.
* **The "end-to-end is not automatically stronger" finding from Sprint 5 got its complement.** Sprint
  5 found a case only the unit fixture could catch. This sprint found the reverse: AC 3 asserts
  `task-004` is `LOW` priority and assigned to `user-005`, which are `data.sql` values, and the
  service unit test's fake builds activities with `HIGH`/`user-004`. A fake-based assertion there
  would have been testing its own fixture. The Generator split it correctly — mapping proved over the
  fake, seed values proved in H2. Both halves of this lesson now belong in `how-to-test`.
* **Documentation drift: five items, one closed, and `app-context` has now drifted twice.** The
  `FailingSubscriber` javadoc (open since Sprint 2), the non-existent `./mvnw` named in six documents
  — **now demonstrably wrong the other way**, since `./mvnw` is what actually ran green this sprint,
  so the oldest drift item may be stale rather than real and should be re-checked before being fixed
  — `app-context` §3's endpoint count (open since Sprint 5, and this sprint adds an eleventh
  endpoint), and now **`app-context` §5's event catalogue**, which will be wrong the moment Sprint 2
  lands `REGIONAL_ROLLUP_REQUESTED`. `spec.md` already flags this as an action for the human.
  `app-context` is the file every agent treats as authoritative; it has now drifted in two separate
  sections.
* **Prediction for Sprint 2, to be settled in its run log.** Sprint 2 adds `@Transactional` to
  `regionalRollup` and publishes the event, and its only delivery proof is `RecordingEventBus`, which
  records at publish time and is blind to whether a transaction exists. So `@Transactional` will be
  deletable with Sprint 2's suite green, and only Sprint 3's listener test will pin it. This is the
  same shape as the Sprint 3 → Sprint 4 prediction that was settled by a mutation probe. **Settle it
  by deleting the annotation and running Sprint 2's tests, then Sprint 3's.** The contract already
  anticipates this — Sprint 2 Scenario 6 asserts the annotation's presence directly rather than its
  effect, which is an honest admission that the effect cannot be observed until Sprint 3.
* **Cleanup assignment held.** All three of this sprint's cleanups land on files Sprint 2 or a later
  sprint opens, so none should become human backlog. The standing pattern — cleanups assigned to a
  following sprint get closed, cleanups landing on a final sprint do not — predicts these three close
  and any Sprint 3 findings do not. Sprint 3 is this feature's last, so **plan for its findings to
  need a human**, as Sprint 5's log recommended.

## Token Cost Basis

`(total word count across read/written files) * 1.3 * iteration count`

| Group | Words |
| --- | --- |
| Harness artifacts — `spec.md`, all three sprint contracts, `generator-summary.md`, `evaluator-feedback.md`, `sprint-5-run-log.md`, 4 agent files, 7 skill files, `CLAUDE.md` | 13,408 |
| Source written — 3 new DTOs, `ReportService`, `ReportRoutes`, `UserService`, 3 staff repository files | 2,558 |
| Tests written — `RegionalRollupIntegrationTest` plus 4 modified test/support classes | 2,654 |
| Read for context — `Task`, `Report`, `ReportEventListener`, `NotFoundError`, `SpringEventBus`, `ProgrammeClosedEvent`, `StoreSummaryResponse`, `ModuleBoundaryTest`, `FakeTaskRepository`, `FakeRepository`, `RecordingEventBus`, `application.yml`, `data.sql`, `checkstyle.xml` | 3,495 |
| **Total words** | **22,115** |

`22,115 * 1.3 * 1 = 28,750` → **~28.7k tokens**

Up from the previous feature's declining run (29.6k → 26.7k → 25.2k), and for the same reason Sprint
3 was the previous feature's most expensive: **this sprint carries the whole feature's planning
overhead** — one spec plus three contracts, 4,600 words of harness artefact that Sprints 2 and 3 will
read but not write. Net of that, the sprint itself is the cheapest yet. Excludes Maven output, which
was filtered rather than read.

## Next Step

Sprint 2 of 3. `spec.md` and `sprint-2-contract.md` / `sprint-3-contract.md` remain in
`.harness/output/`; `spec.md` is deliberately **not** archived, since it is the only record of the
sprints still to run.
