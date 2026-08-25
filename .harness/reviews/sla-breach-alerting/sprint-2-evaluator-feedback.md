# Evaluator Feedback — Sprint 2: The Lead Alert and the Breach Episode

## 1. VERDICT

**CONDITIONAL PASS**

Every hard gate passed. Two cleanups recorded; neither touches an acceptance criterion, and neither is
worth spending a retry on.

## 2. SCORE

`A 40/40 · B 33/35 · C 24/25 = 97`

## 3. GATE RESULTS

Command: `./mvnw clean test` — exit code 0.

| Dimension | Gate | Result |
| --- | --- | --- |
| — | Undeclared changes (`git status --porcelain`) | **PASS.** Every Sprint 2 path appears in `generator-summary.md`. The `activities` entries still in the working tree belong to Sprint 1, already evaluated and archived — this harness gates before commit, so they stay uncommitted. |
| A | JUnit | **PASS.** `Tests run: 134, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`. 120 → 134, so the sprint added 14 tests. |
| A | Every AC has a test proving its THEN | **PASS.** 11 of 11 mapped to named methods, each asserting recipient, alert type and recorded episode state rather than a bare outcome. |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **PASS.** The new `alerts` → `staff` edge is the sanctioned service-layer read; no cycle, since `staff` imports nothing but `shared`. |
| B | Checkstyle (`validate`), incl. `IllegalThrows`, `NoRawErrorThrows`, `IllegalCatch` | **PASS.** In particular no `catch (Exception ...)` was used to reach the "log and carry on" behaviour — it is written as explicit `Optional` and null checks, which is what the contract required. |
| B | SpotBugs (`test-compile`) | **PASS.** |
| C | `jacoco:check` | **PASS.** Includes the new `alerts.service.SlaBreachService` against the 70% line / 50% branch per-class floor. |

### Assessed gates — the five the build cannot see

1. **A required event was never published** — **N/A.** This sprint consumes `TaskOverdueEvent` and
   publishes nothing. Correct: the alert is a consequence, not a new fact.
2. **Event wiring that fails silently** — **PASS.** `AlertEventListener.onTaskOverdue` keeps both
   `@TransactionalEventListener(phase = AFTER_COMMIT)` and `@Transactional(propagation = REQUIRES_NEW)`
   through the rewrite
   ([AlertEventListener.java:76](src/main/java/com/cognizant/storeops/alerts/listener/AlertEventListener.java#L76)).
   `EventDeliveryIntegrationTest.sweptOverdueBreachReachesTheDepartmentLeadExactlyOnce` proves delivery
   and de-duplication through the real container — dropping `REQUIRES_NEW` would leave the episode row
   unwritten and that test would fail on its second sweep.
3. **Business logic in a route** — **PASS.** No route touched, and none needed: the read surface is the
   existing `GET /api/notifications`.
4. **Criteria covered only by a status-code assertion** — **PASS.** Every absence assertion has a
   positive counterpart in the same test; AC 7 in particular asserts the retry succeeds after staff data
   is corrected, rather than only that nothing happened.
5. **A dropped acceptance criterion** — **PASS.** None dropped.

### The gate this sprint existed to satisfy

**De-duplication is durable, not in-process.** This was the contract's headline guardrail and it holds:
the decision reads `SlaBreachRepository.findByTaskId` and the episode is a row keyed by `task_id`
([SlaBreachEntity.java:23](src/main/java/com/cognizant/storeops/alerts/repository/SlaBreachEntity.java#L23)),
so "one open episode per activity" is a primary-key guarantee rather than a convention. `SlaBreachService`
holds no collection field. Two tests separate the claims that a fake alone could not:
`SlaBreachServiceTest.repeatObservationRaisesNothingAndOnlyMovesLastSeen` advances time by rebuilding the
service over the same repositories, and the integration test crosses a real transaction and a real
database. `H2SchemaTest.slaBreachesTableShape` asserts `TASK_ID` is the sole primary key.

### Invented domain vocabulary

**PASS.** No new enum value, event, `AppError` subtype, route or `AlertType`. `SLA_BREACH` is reused for
the fallback-to-manager case too, which is right — the fallback changes who hears about the breach, not
what happened.

## 4. FINDINGS

### F1 — `src/main/java/com/cognizant/storeops/alerts/repository/SlaBreachRepository.java:20`

**`findAll()` has no production caller.** Declared in `generator-summary.md`, and confirmed: only tests
reach it, and the fake would answer `findAll` from `FakeRepository` whether the port declared it or not.
Sprint 3 needs `deleteByTaskId`, not this. Unused API surface on a port is the same category as a dead
private method.

Not a gate, and the Generator was right to flag rather than silently deviate — the contract's deliverable
list specified it. Fix belongs with whoever opens the file next: drop it in Sprint 3, or keep it and let
the breach-tracker endpoint `spec.md` lists as out of scope be the caller. Decide, don't leave it.

### F2 — `src/main/java/com/cognizant/storeops/alerts/service/SlaBreachService.java:88`

**`observe` depends on its caller for atomicity, and says so nowhere.** `openEpisode` raises the
notification and then writes the episode row as two repository calls with no transaction of its own.
Correct today only because the sole caller is a listener annotated
`@Transactional(REQUIRES_NEW)`, which wraps both. Any second caller — a future admin endpoint, a manual
replay, a test calling the service directly outside a transaction — gets a raised alert with no episode
recorded, and the next sweep alerts the same lead again.

Cheapest fix: `@Transactional` on `observe`. Default `REQUIRED` propagation joins the listener's
transaction, so nothing changes today, and the two writes stop being separable tomorrow.

### Observation, not a finding

`lastSeenAt` costs one write per open breach per sweep and, by its own javadoc, "settles no decision" —
a 5-minute write cycle for a diagnostic column. Contract-mandated (AC 2 asserts it) and correctly
implemented, so it is not scored here. If Sprint 3 finds it still settles nothing, the Planner should be
asked whether it earns its writes.

## 5. Notes for the Monitor

* Feature state after this sprint: a breach alerts the right person exactly once. It never escalates and
  never closes — an unresolved breach is now observed indefinitely with only `lastSeenAt` moving. Both
  halves of "if unresolved after a configurable grace period" are Sprint 3.
* Sprint 1's two cleanups are still open and are still in `activities`, which this sprint did not open.
  Sprint 3 is `alerts`-only, so they will not be reached there either. They need to be scheduled
  deliberately or dropped deliberately.
* F1 and F2 both live in files Sprint 3 does open (`SlaBreachRepository`, `SlaBreachService`), so they
  can be folded in at no extra cost. That is the cheapest they will ever be.
