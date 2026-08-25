# Evaluator Feedback — Sprint 3: Grace Period Escalation and Episode Closure

## 1. VERDICT

**CONDITIONAL PASS**

Every hard gate passed, and both cleanups the previous review left open were closed. Two new cleanups
recorded, both small and both traceable to the contract rather than to the implementation.

## 2. SCORE

`A 40/40 · B 33/35 · C 24/25 = 97`

## 3. GATE RESULTS

Command: `./mvnw clean test` — exit code 0.

| Dimension | Gate | Result |
| --- | --- | --- |
| — | Undeclared changes (`git status --porcelain`) | **PASS.** Every Sprint 3 path appears in `generator-summary.md`. |
| A | JUnit | **PASS.** `Tests run: 152, Failures: 0, Errors: 0, Skipped: 0` → `BUILD SUCCESS`. 134 → 152, so the sprint added 18 tests. |
| A | Every AC has a test proving its THEN | **PASS.** 12 of 12, several with more than one test where the criterion had more than one claim. |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **PASS.** The properties record sits in `alerts/service`, so no `config` package appeared and `shared` gained no dependency on a module. |
| B | Checkstyle, SpotBugs | **PASS.** |
| C | `jacoco:check` | **PASS.** `SlaBreachService` grew two branches-heavy methods and stays above the 70% line / 50% branch per-class floor. |

### Assessed gates

1. **A required event was never published** — **N/A.** This sprint publishes nothing; it consumes
   `TaskOverdueEvent` and `TaskStatusChangedEvent`.
2. **Event wiring that fails silently** — **PASS**, and this was the sprint's riskiest edit.
   `onTaskStatusChanged` gained a second branch without losing
   `@TransactionalEventListener(AFTER_COMMIT)` or `@Transactional(REQUIRES_NEW)`; a deletion that joined
   an already-committed transaction would be discarded with no error, and
   `SlaEscalationIntegrationTest.resolvingAnActivityThroughTheApiClosesItsEpisode` is what would catch
   it — it asserts the row is gone after a real `PATCH`, not merely that the handler was called.
   `AlertEventListenerTest.blockedTransitionLeavesTheBreachEpisodeAlone` guards the older behaviour
   against being replaced rather than extended, which is exactly what the contract asked for.
3. **Business logic in a route** — **PASS.** No route touched.
4. **Criteria covered only by a status-code assertion** — **PASS.** AC 11 checks the `200` and the `DONE`
   body and the vanished episode row and the absent escalation.
5. **A dropped acceptance criterion** — **PASS.** None dropped.

### The two gates this sprint was designed around

**Escalation is gated on a second observation, not on elapsed time.** Confirmed in
`SlaBreachService.reobserve` — the escalation branch is only reachable from the existing-episode path, so
`PT0S` cannot fire both alerts in one sweep.
`SlaBreachServiceTest.firstObservationNeverEscalates` proves it at the unit level and
`SlaEscalationIntegrationTest` proves it through the container with `PT0S` actually bound. This was the
easiest thing in the contract to get wrong by writing one obvious-looking comparison.

**The grace period is configuration, not a constant.** `SlaEscalationProperties` is injected, and the
binding is asserted at three levels: the record's defaulting rules in isolation, the shipped
`application.yml` value, and an override reaching the bean. A hard-coded `PT2H` would have satisfied
AC 1 and AC 2 and failed the feature; it would not survive any of those three.

### Cleanups carried in from Sprint 2 — both closed

* **F1 closed.** `SlaBreachRepository.findAll()` removed, and the `DEFAULT_SORT` constant it was the only
  reader of removed with it. `SlaBreachJpaRepository` is now a bare `JpaRepository`, which is honest: the
  port is reached by activity id only.
* **F2 closed.** `@Transactional` on `observe` and `closeEpisode`. Behaviour is unchanged today —
  `REQUIRED` joins the listener's transaction — which is the point: the class no longer depends on its
  caller for the alert and the episode row to stay together.

### Invented domain vocabulary

**PASS.** `ESCALATION` reused rather than a new `SLA_ESCALATION` value, as `spec.md` flagged and the
human left unchallenged at approval. No new event, route, `AppError` subtype or enum value anywhere in
the feature.

## 4. FINDINGS

### Accepted deviations — reviewed, not held against the sprint

**AC 10 uses its own activity rather than seed `task-001`.** Verified as necessary, not convenient:
`publishOverdueBreaches()` sweeps globally, so the sibling test in the same class escalates `task-001`
before this test runs, making its "no escalation yet" assertion false on ordering alone. The Generator
found this by running the test rather than by reasoning about it, and fixed the test rather than
weakening the assertion. This is the third sprint in a row where shared-state fragility surfaced, and
the second where the contract named a seed row it should not have.

**AC 7's override binds `PT0S` instead of `PT30M`.** Same assertion, one fewer Spring context. Accepted.

### F1 — `src/main/java/com/cognizant/storeops/alerts/service/SlaBreachService.java:141`

**A suppressed escalation still records a recipient who was never told.** When the store manager is the
person who already received the `SLA_BREACH` as the fallback lead, no `ESCALATION` notification is
raised — correctly, nobody should hear about one activity twice — but the episode is saved with
`escalationRecipientId` set to them. The row then asserts an escalation recipient for which no
notification exists, which is a wrong answer to "who was escalated to?" for anyone reading the table or
building the tracker endpoint `spec.md` defers.

Contract-mandated: Scenario 5 asserts exactly this value, so the Generator was right to implement it.
The fix is a Planner decision, not a Generator one — either leave `escalationRecipientId` null when the
alert is suppressed (`escalatedAt` alone carries "this was considered and settled"), or keep the
recipient and add a flag distinguishing "notified" from "suppressed as duplicate".

### F2 — `src/main/java/com/cognizant/storeops/alerts/service/SlaBreachService.java:151`

**The escalation body renders the grace period as `PT2H`.** `Duration.toString()` produces ISO-8601,
so the store manager's notification reads "after a grace period of PT2H". Every other string in that
body is plain English. Format it as hours and minutes, or drop it from the body and keep it in the log —
the operator needs to know the breach is unresolved, not the configuration value that decided it.

### Observation, not a finding

The escalation decision compares `event.occurredAt()` against `firstBreachAt + gracePeriod`, while
`escalatedAt` and `lastSeenAt` are written from `clock.instant()`. Both trace to the same `Clock` bean
moments apart, so they cannot meaningfully disagree — but nothing asserts that, and the Generator
declared it. Left as an observation because closing it would mean either threading one timestamp through
or testing a distinction that does not currently exist.

Also unanswered, carried from Sprint 2: `lastSeenAt` is now written on the escalated path too and still
settles no decision.

## 5. Notes for the Monitor

* **The feature is complete.** A HIGH or CRITICAL activity that passes its due date without reaching
  `DONE` alerts the responsible Department Lead once, escalates to the store's `STORE_MANAGER` once the
  configured grace period has passed, and stops doing either the moment the activity is resolved. All
  three sprints closed on their first attempt.
* **Debt ledger for the human, since this feature has no sprint left to spend it in:** Sprint 1's two
  cleanups (duplicated overdue definition in `activities`; `INFO` on empty sweeps) are still open and no
  remaining contract reopens that module. Sprint 3 adds two more, both in `alerts` and both traceable to
  contract wording. Sprint 2's `lastSeenAt` question is unanswered. Six findings raised across the
  feature, two closed.
* **The strongest trend across all three sprints is that the contract, not the implementation, produces
  the findings** — four of the six. The Planner's criteria-writing standard is where that gets fixed.
