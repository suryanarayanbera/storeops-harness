# Evaluator Feedback — Sprint 1: Template Endpoint And Department Resolution

## 1. VERDICT

**CONDITIONAL PASS**

Every hard gate passed, automated and assessed. All 12 acceptance criteria are implemented and proved
by named tests that assert payloads and database state rather than status codes. Four cleanups below,
all cosmetic or documentary; none blocks Sprint 2.

## 2. SCORE

`A 40/40 · B 34/35 · C 24/25 = 98`

* **A — Contract fulfilment 40/40.** 12 of 12 criteria met, each with a test whose THEN is actually
  asserted. No criterion rests on a status code alone.
* **B — Architecture 34/35.** All 12 ArchUnit rules pass. One mark off for the domain vocabulary
  record not being brought in step with the code (finding 2) — the new event and endpoint exist in
  `src/` but not in the file the Planner and Generator are told is the source of truth.
* **C — Test quality 24/25.** Strong: payload-level event assertions, an after-commit delivery test
  that cannot be faked by a publish-time double, and a vacuity guard on the containment test. One
  mark off for finding 4.

## 3. GATE RESULTS

| Dimension | Gate | Result |
| --- | --- | --- |
| — | `./mvnw clean test` exit code | **PASS** — 0 |
| A | JUnit | **PASS** — `Tests run: 275, Failures: 0, Errors: 0, Skipped: 0` |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **PASS** — `Tests run: 12, Failures: 0, Errors: 0` |
| B | Checkstyle (`IllegalThrows`, `NoRawErrorThrows`, `IllegalCatch`) | **PASS** — `You have 0 Checkstyle violations.` |
| B | SpotBugs | **PASS** — `BugInstance size is 0` |
| C | `jacoco:check` | **PASS** — bundle and per-class rules satisfied, no violation reported |
| — | `git status --porcelain` vs declared file list | **PASS** — 18 paths, all declared. No undeclared change |

### LLM-assessed hard gates

| # | Gate | Result | Evidence |
| --- | --- | --- | --- |
| 1 | A required event was never published | **PASS** | `ProjectService.java:163` publishes `ProgrammeTemplateRequestedEvent` via `eventBus.publish`. Not an `ApplicationEventPublisher` |
| 2 | Event wiring that fails silently | **PASS** | `@Transactional` on `applyTemplate` at `ProjectService.java:144`. `EventBusConfiguration` untouched. `PlanogramTemplateIntegrationTest.theTemplateEventIsDeliveredAfterCommit` proves delivery to an `AFTER_COMMIT` subscriber, which is skipped outright without a live transaction — so the annotation is asserted, not assumed |
| 3 | Business logic in a route | **PASS** | `ProjectRoutes.java:66-70` binds the path variable and body, calls the service once, sets `202`. No enum conditional, no arithmetic, no status inspection |
| 4 | Criteria proved only by a status code | **PASS** | AC1 asserts four `(title, department, priority, assigneeId)` tuples plus the full event payload; AC5–AC9 assert `code`, `statusCode` and detail text. Every `templateEvents()).isEmpty()` has its positive counterpart in `applyTemplatePublishesResolvedLines` |
| 5 | A dropped acceptance criterion | **PASS** | All 12 named tests exist and run. Test counts reconcile: `ProjectServiceTest` 19 = 6 pre-existing + 13 new, `ProjectRoutesTest` 11 = 5 + 6, `PlanogramTemplateIntegrationTest` 9 |
| — | Invented domain vocabulary | **PASS** | Route nested under the real `/api/projects` base, not the `/api/programmes` the request asked for. `PROGRAMME_CLOSED` was named by the Planner in `spec.md` §Error Mapping, so it is not a code invented at the keyboard. `PLANOGRAM` and the four `TaskPriority` values are all from `app-context` §4 |

Two boundary claims worth recording, since they are the design's whole point and ArchUnit settled
them rather than a reviewer's eye: `programmes` imports nothing from `activities`, and `activities`
imports nothing from `programmes`. Rule 2 (`modulesAreFreeOfCycles`) and rule 3b
(`eventsDoNotLeakModuleTypes`) both pass, so the resolved-items payload did what it was designed to do.

## 4. FINDINGS

All four are cleanups. None is a gate failure.

**1. `src/main/java/com/cognizant/storeops/programmes/routes/ProjectRoutes.java:51` — duplicate
endpoint number in the javadoc.** The new handler is documented as `Endpoint 10`, but
`reports/routes/ReportRoutes.java:31` already holds `Endpoint 10` for
`GET /api/reports/region/{regionId}`. Counting the nine in `app-context` §3 plus
`PATCH /api/tasks/bulk-status` and the regional rollup, this endpoint is the twelfth. Change the tag
to `Endpoint 12`.

*Note, not scored:* the baseline already carries a collision of its own —
`activities/routes/TaskRoutes.java:80` labels bulk-status `Endpoint 5`, which
`programmes/routes/ProjectRoutes.java:32` also claims. That is pre-existing and out of scope under
`how-to-review` §1; it is recorded here only so the next reader does not treat the numbering as
trustworthy.

**2. `.harness/skills/app-context/SKILL.md:35` and `:76` — the domain vocabulary record is stale.**
Line 35 states "Nine endpoints exist today" and line 76 states "Three events exist. This is the whole
list", while `src/` now holds twelve endpoints and five events. This sprint added the fifth event and
the twelfth endpoint; the drift predates it (`RegionalRollupRequestedEvent` is also absent), so only
this sprint's share is scored.

Worth fixing before the next Planner runs rather than after. The file tells its readers "If a name you
want to write is not in this file, it does not exist" — a Planner that believes line 76 will design
around three events and a Generator that believes it will hesitate to subscribe to a fifth. Add
`REGIONAL_ROLLUP_REQUESTED` and `PROGRAMME_TEMPLATE_REQUESTED` to the §5 catalogue, the two missing
routes to §3, and `PROGRAMME_CLOSED` to §6. The sprint contract did not ask for this, so it is not
held against the Generator.

**3. `.harness/output/generator-summary.md:63` — declared test count is wrong.** It says "12 new
tests for `applyTemplate`"; there are 13. `applyTemplateConsidersProgrammeMembersOnly` is listed lower
down under tests-beyond-contract but not counted in the total. `evaluation-criteria` §2 makes the
summary a handoff contract, so the number should reconcile with the 19 the build reports.

**4. `src/test/java/com/cognizant/storeops/programmes/service/ProjectServiceTest.java:276` — the
inactive-member assertion is weaker than it reads.** `containsOnlyNulls()` covers all four lines, but
the programme's only member is `user-003`, who works in `GROCERY`. The two `OPERATIONS` lines would be
null whether or not the `active` filter existed, so half the assertion passes for a reason unrelated
to the rule under test.

It does still catch the regression — removing `.filter(User::active)` at `ProjectService.java:211`
would put `user-003` on both `GROCERY` lines and fail the test — so this is clarity, not a coverage
hole. Assert the two `GROCERY` positions explicitly, so the test names the behaviour it guards.

## 5. NOTES FOR THE MONITOR

The deviation declared in `generator-summary.md` §3 — swapping the `UserService` Mockito mock in
`ProjectServiceTest` for a real service over `FakeUserRepository` — was reviewed and is accepted.
`how-to-test` §2 states a preference for fakes and permits Mockito for cross-module reads, so both
forms comply; the six pre-existing tests keep their original assertions and pass unchanged. Declaring
it was the right call.

Sprint 1 deliberately creates no activity. `PlanogramTemplateIntegrationTest.applyingTheTemplateCreatesNoActivityYet`
asserts `store-002` still holds only `task-004`, and that expectation must flip to five rows in
Sprint 2. It is the single most useful signal in the suite for whether the Sprint 2 listener is wired,
so the Sprint 2 Generator must update it rather than delete it.
