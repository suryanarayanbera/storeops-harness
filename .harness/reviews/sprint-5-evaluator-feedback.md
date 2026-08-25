# Evaluator Feedback — Sprint 5: Grace-Period Escalation to the Store Manager

## VERDICT: CONDITIONAL PASS

## SCORE

`A 39/40 · B 35/35 · C 25/25 = 99`

## GATE RESULTS

### Automated

| Dimension | Gate | Result |
| --- | --- | --- |
| A | JUnit | **pass** — `Tests run: 205, Failures: 0, Errors: 0, Skipped: 0`. 181 at Sprint 4 close, +24 |
| B | `ModuleBoundaryTest` (12 ArchUnit rules) | **pass** — `Tests run: 12, Failures: 0` |
| B | Checkstyle | **pass** — `You have 0 Checkstyle violations` |
| B | SpotBugs, effort Max, threshold Medium | **pass** |
| C | `jacoco:check` | **pass** — `All coverage checks have been met`. Bundle 92.3% line, 73.3% branch, against floors of 85% and 60% |

`mvn clean test` exit code **0**, confirmed by capturing `$LASTEXITCODE` directly rather than reading
it off a truncated pipeline. (`./mvnw` still absent — third sprint reporting it.)

| Class | Line | Branch |
| --- | --- | --- |
| `AlertEventListener` | 87/87 (100%) | 27/28 (96%) |
| `SlaAlertProperties` | 5/5 (100%) | 4/4 (100%) |
| `NotificationService` | 18/18 (100%) | 4/4 (100%) |

### LLM-assessed (evaluator agent §2)

| # | Gate | Result |
| --- | --- | --- |
| 1 | A required event was never published | **pass** — no criterion required a publication; the sprint consumes only |
| 2 | Event wiring that fails silently | **pass** — both `@TransactionalEventListener(AFTER_COMMIT)` and `@Transactional(REQUIRES_NEW)` present on both handlers (verified: 2 occurrences each). `SlaEscalationIntegrationTest` exercises the after-commit path for both stages |
| 3 | Business logic in a route | **pass** — no `routes` file modified in Sprint 3, 4 or 5, confirmed against `git status` |
| 4 | Criteria covered only by a status-code assertion; absence assertions with no positive counterpart | **pass** — every integration assertion reads a `Notification` row. Each absence assertion has a positive counterpart in the same class |
| 5 | A dropped acceptance criterion | **pass** — all 11 implemented and proven |

### Architectural guardrails — verified independently

| Guardrail | Check | Result |
| --- | --- | --- |
| No new entity, table or tracking field | `@Entity` count | 5, unchanged: `Task`, `Notification`, `Project`, `Report`, `User`. `data.sql` untouched |
| No fourth event | `shared/events` listing | `DomainEvent` plus exactly three events. Catalogue unchanged |
| No `TaskService` in `alerts` | grep, javadoc excluded | no code reference |
| Use the injected `Clock`, never `Instant.now()` | grep over `src/main/java` | the only two occurrences are in `GlobalExceptionHandler`, stamping error timestamps — baseline code, untouched, and outside this feature's paths |
| Stage two keyed on `alertType` **and** `subject` | mutation probe | confirmed, see below |
| No new endpoint | route mapping census | 10 mappings, none added by this feature. See Finding 2 on the contract's own arithmetic |

### Mutation probes — both run, both bit

The contract made two claims that only a probe can settle, and the Generator ran both.

**Probe 1, the collision.** `alreadyEscalated` reduced to an `alertType`-only check:

```
[INFO] Tests run: 14, Failures: 0 -- AlertEventListenerTest
[ERROR] Tests run: 12, Failures: 1 -- SlaEscalationListenerTest
[ERROR]   SlaEscalationListenerTest.aBlockedActivityEscalationDoesNotSuppressTheSlaEscalation:212
[INFO] Tests run: 4, Failures: 0 -- SlaEscalationIntegrationTest
```

One failure, and the right one. This settles the first of Sprint 4's two predictions: the
`AlertType.ESCALATION` subject collision was a genuine trap, and an activity blocked before it
breached would have silently never reached its store manager.

The Generator volunteered the more interesting half: **the integration test does not catch this.**
`task-001` is `TODO` in the seed and was never blocked, so no colliding row exists end to end. Only
the unit fixture can construct the collision. That is a useful correction to the instinct that an
end-to-end test is automatically the stronger one — it is stronger about wiring and weaker about state
it cannot easily arrange.

**Probe 2, the grace boundary.** `isBefore(escalateFrom)` changed to `!isAfter(escalateFrom)`, moving
the boundary a single instant:

```
[ERROR] Tests run: 12, Failures: 2 -- SlaEscalationListenerTest
[ERROR]   SlaEscalationListenerTest.theGraceBoundaryIsInclusive:146
[ERROR]   SlaEscalationListenerTest.aZeroGracePeriodEscalatesImmediately:169
```

Two tests pin it, and one of them is the zero-grace case — the configuration where an
off-by-one-instant boundary stops working altogether rather than merely being a second late. A
one-token change to a comparison is caught, which is the standard a time-dependent rule should be
held to.

### Sprint 4's second prediction — settled, and the fixes generalised

Sprint 4's run log predicted that this sprint's new `@SpringBootTest` property set would add a cached
context and asked whether Sprint 3 and 4's context-isolation fixes were real or symptom-specific. The
answer is that they held: `SlaEscalationIntegrationTest` added exactly one new context and
`H2SchemaTest` passed 6/6 untouched. No seed-count assertion broke. The conclusion recorded in the
Sprint 4 log — that a further break would indict the shared-database design itself — does not need
following up.

The Generator also actively minimised the cost: `SlaAlertPropertiesTest.ShippedDefault` deliberately
declares the property set seven other classes already use, so it reuses a cached context instead of
adding a second one, and the override case went to `ApplicationContextRunner`. Net new contexts for a
sprint that needed three distinct property configurations: one.

### Deviations — all four accepted

1. **Relative instants instead of the contract's absolute `12:00:00Z`.** Accepted, same as Sprint 3.
   `how-to-test` §2 mandates `10:00:00Z` and the rest of the suite uses it; expressing breach ages as
   `Duration.ofHours(5)` and `GRACE.minusSeconds(1)` tests identical relationships and reads better.
   The contract should not have named its own instant — a Planner-side issue for the third time.
2. **`ApplicationContextRunner` for the override case.** Accepted, consistent with the Sprint 3
   ruling, and the reasoning is now empirically supported rather than merely plausible.
3. **The endpoint count.** See Finding 2 — the contract was wrong, not the code.
4. **`./mvnw`.** Unchanged, still routed to the Planner.

## FINDINGS

Two items. Neither is a hard-gate failure.

**1. `src/main/java/com/cognizant/storeops/alerts/listener/AlertEventListener.java:236` — the
escalation body renders the grace period as a raw ISO-8601 token in operator-facing text.**
Dimension A, contract fulfilment quality.

The body reads:

```
Activity task-001 at store store-001 is HIGH priority, passed its due date of
2026-01-07T08:00:00Z, and is still not DONE PT4H after the breach was raised.
```

`slaAlertProperties.gracePeriod()` is interpolated via `Duration.toString()`, so a store manager
reads "still not DONE PT4H after the breach was raised". AC 1 requires the body to name the grace
period and it does, literally — which is why this is scored as a one-point quality deduction and not
a missed criterion. But `Notification.body` is display text for a retail store manager, not a log
line, and `PT4H` is not a thing anyone says. The `dueAt` instant in the same sentence has the same
character, though that idiom is at least consistent with the stage-one body and with the API's
ISO-8601 responses.

**Fix:** format the duration for humans (`4h`, or hours and minutes when not whole) in the body only.
One line, cosmetic, no behavioural risk. Test-visible: `agedBreachEscalatesToTheStoreManager` asserts
`body` contains `task-001`, `store-001` and `HIGH`, none of which this touches.

**2. Routed to the Planner — `sprint-5-contract.md` Scenario 11 asserts "the same nine endpoints",
and the real number was ten before this feature started.** The route census is five mappings on
`TaskRoutes` (including `PATCH /api/tasks/bulk-status`, added by the shift handover feature in
Sprints 1–2), two on `ProjectRoutes`, and one each on `NotificationRoutes`, `ReportRoutes` and
`UserRoutes`.

The criterion's substance is met — this feature added no endpoint, and no `Routes` file was modified
across all three sprints. But the contract asserted a stale number, and it was stale because
`app-context` §3 still says "Nine endpoints exist today", which stopped being true two sprints before
this feature began. This is the **fourth** documentation-drift item in this harness (after the
`FailingSubscriber` javadoc, the missing `./mvnw`, and the `AlertEventListener` javadoc the Generator
fixed itself). The docs audit flagged in Sprint 3 is now overdue: `app-context` is the file every
agent is told to treat as authoritative, so drift there is the most expensive kind.

## Feature-Level Assessment

This is the final sprint, so the original request is worth checking against directly rather than only
the contracts derived from it.

> when a HIGH or CRITICAL task passes its due date without reaching DONE, automatically fire a
> SLA_BREACH notification to the assigned Department Lead and escalate to STORE_MANAGER if unresolved
> after a configurable grace period

| Element | Delivered by | Status |
| --- | --- | --- |
| HIGH or CRITICAL only | `Task.isSlaTracked`, `SLA_TRACKED_PRIORITIES` | yes |
| passes its due date without reaching DONE | `Task.isOverdueAt` | yes |
| automatically | `SlaSweepScheduler`, every 15 minutes | yes |
| fire a `SLA_BREACH` notification | `AlertEventListener.raiseBreach` | yes |
| to the assigned Department Lead | assignee's department lead at the store, active, lowest id, falling back to the store manager | yes |
| escalate to `STORE_MANAGER` | `AlertEventListener.escalateIfGraceElapsed` | yes |
| if unresolved | the sweep republishes only while the activity is still overdue and not `DONE`; the event's arrival is the proof | yes |
| after a configurable grace period | `storeops.alerts.sla.grace-period`, default `PT4H`, zero legal, negative rejected | yes |

All eight elements delivered. Built without a new event, a new entity, a schema change, a new
endpoint, a new error code or a new enum value — the whole feature rests on configuration plus the
existing notification rows as its state machine.

Two behaviours a reader of the original request might expect and should know are **not** present,
both correctly declared by the Generator as out of scope rather than quietly omitted: alerts are
created `PENDING` and nothing calls `markSent`, so "fire" means "raised and readable", not
"delivered"; and nothing retracts or acknowledges an alert once the activity reaches `DONE`, so the
rows persist. Neither was asked for. Both are legitimate candidates for a future feature and are
recorded in the run log rather than left implicit.

## What was not reviewed

* `PROMPT.md` remains modified — the harness demonstration file, edited by the human before this run.
* `git status --porcelain` otherwise matches the declared file list exactly.
* Baseline gaps in untouched legacy code. The one uncovered branch in `AlertEventListener` (27/28) is
  the blank-assignee arm of the pre-existing `isUnassigned` helper on the untouched
  `onTaskStatusChanged` path, declared by the Generator and not scored.
