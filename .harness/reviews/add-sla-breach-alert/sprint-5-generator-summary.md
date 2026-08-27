# Generator Summary — Sprint 5: Grace-Period Escalation to the Store Manager

`mvn clean test` exit 0. JUnit 205/205 (181 at Sprint 4 close, +24), ArchUnit 12/12, Checkstyle 0
violations, SpotBugs clean, JaCoCo bundle (92.3% line, 73.3% branch) and per-class gates met.

## 1. AC Self-Check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | Breach older than the grace period escalates to the Store Manager; stage-one row untouched | yes | `SlaEscalationListenerTest.agedBreachEscalatesToTheStoreManager` |
| 2 | Breach inside the grace period does not escalate | yes | `SlaEscalationListenerTest.breachInsideTheGracePeriodDoesNotEscalate` |
| 3 | Boundary inclusive at exactly one grace period, exclusive one second short | yes | `SlaEscalationListenerTest.theGraceBoundaryIsInclusive`, `SlaEscalationListenerTest.oneSecondShortOfTheGraceBoundaryDoesNotEscalate` |
| 4 | Zero grace period escalates on the next observation; zero is legal | yes | `SlaEscalationListenerTest.aZeroGracePeriodEscalatesImmediately`, `SlaAlertPropertiesTest.acceptsAZeroGracePeriod` |
| 5 | Escalation happens once however many sweeps follow | yes | `SlaEscalationListenerTest.escalationHappensOnce` |
| 6 | A blocked-activity `ESCALATION` does not suppress the SLA escalation | yes | `SlaEscalationListenerTest.aBlockedActivityEscalationDoesNotSuppressTheSlaEscalation` |
| 7 | First observation raises stage one only, even at zero grace | yes | `SlaEscalationListenerTest.aFirstObservationRaisesStageOneOnly` |
| 8 | No Store Manager is silent, not an error; stage-one row untouched | yes | `SlaEscalationListenerTest.noStoreManagerIsSilent` |
| 9 | Grace period binds to `PT4H` by default, binds an override, rejects negative | yes | `SlaAlertPropertiesTest.ShippedDefault.shippedGracePeriodIsFourHours`, `SlaAlertPropertiesTest.gracePeriodIsBoundFromConfiguration`, `SlaAlertPropertiesTest.rejectsANegativeGracePeriod` |
| 10 | Both stages end to end after commit; escalation to the manager only; third sweep changes nothing | yes | `SlaEscalationIntegrationTest.theFirstSweepTellsTheLeadOnly`, `SlaEscalationIntegrationTest.theSecondSweepEscalatesToTheStoreManager`, `SlaEscalationIntegrationTest.aThirdSweepChangesNothing` |
| 11 | Whole suite green, API surface unchanged | yes | full-suite run, exit 0. **See Deviation 3 on the endpoint count** |

Four tests beyond the criteria: `SlaAlertPropertiesTest.aNegativeConfiguredValueFailsTheContext`
(a negative value in configuration fails the context rather than binding),
`SlaEscalationListenerTest.escalationPicksTheLowestIdActiveStoreManager`,
`SlaEscalationListenerTest.untrackedPriorityNeverEscalates`, and
`SlaEscalationIntegrationTest.theSweepKeepsReportingTheUnresolvedActivity` (guards the publisher
staying stateless, which every staged assertion depends on).

## 2. Mutation Probes

Two probes, both required by the contract's guardrails. Neither was assumed.

**Probe 1 — stage two keyed on `alertType` alone.** `alreadyEscalated` was reduced to
`!findBySourceRefAndAlertType(taskId, ESCALATION).isEmpty()`:

```
[INFO] Tests run: 14, Failures: 0 -- AlertEventListenerTest
[ERROR] Tests run: 12, Failures: 1 -- SlaEscalationListenerTest
[ERROR]   SlaEscalationListenerTest.aBlockedActivityEscalationDoesNotSuppressTheSlaEscalation:212
[INFO] Tests run: 4, Failures: 0 -- SlaEscalationIntegrationTest
```

Exactly one test failed and it was Scenario 6's. This settles the prediction Sprint 4's run log wrote
down: the `ESCALATION` subject collision is real, and an activity blocked before it breached would
silently never reach its store manager.

Worth recording: **the integration test does not catch this.** `task-001` is `TODO` in the seed and
was never blocked, so no colliding `ESCALATION` row exists to trip over. Only the unit fixture can
construct the collision. An end-to-end test is not automatically the stronger test.

**Probe 2 — grace boundary made exclusive.** `clock.instant().isBefore(escalateFrom)` was changed to
`!clock.instant().isAfter(escalateFrom)`, moving the boundary by one instant:

```
[ERROR] Tests run: 12, Failures: 2 -- SlaEscalationListenerTest
[ERROR]   SlaEscalationListenerTest.theGraceBoundaryIsInclusive:146
[ERROR]   SlaEscalationListenerTest.aZeroGracePeriodEscalatesImmediately:169
```

Two tests pin it, and the zero-grace case is one of them — a zero grace period is exactly the
configuration where an off-by-one-instant boundary stops working entirely. Both mutations were
reverted and the full suite re-run to 205/205, exit 0.

## 3. Files Changed

### alerts — service
- `service/SlaAlertProperties.java` — **new**. `@ConfigurationProperties("storeops.alerts.sla")`
  record with `Duration gracePeriod`; compact constructor rejects null and negative with
  `ValidationError` / `VALIDATION_FAILED`. Zero accepted. Picked up by the existing
  `@ConfigurationPropertiesScan`, so no change to `StoreOpsApplication`.

### alerts — listener
- `listener/AlertEventListener.java` — `onTaskOverdue()` now dispatches on what has already been
  raised: empty → `raiseBreach`, otherwise → `escalateIfGraceElapsed`. Added
  `SLA_ESCALATION_SUBJECT` constant and three methods (`raiseBreach` extracted from the old inline
  body, plus `escalateIfGraceElapsed` and `alreadyEscalated`). `SlaAlertProperties` and `Clock`
  injected. `onTaskStatusChanged()` untouched. Both handler annotations unchanged.

### configuration
- `src/main/resources/application.yml` — added `storeops.alerts.sla.grace-period: PT4H`.

### tests — new
- `alerts/listener/SlaEscalationListenerTest.java` — **new**, 12 tests. Stage two, split from
  `AlertEventListenerTest` because the fixtures differ: stage one starts empty, stage two only exists
  once a breach alert does, and every assertion turns on that alert's age against a pinned clock.
- `alerts/service/SlaAlertPropertiesTest.java` — **new**, 8 tests. Validation, `ApplicationContextRunner`
  binding, and one `@Nested @SpringBootTest` for the shipped default.
- `SlaEscalationIntegrationTest.java` — **new**, 4 tests. `grace-period=PT0S` so the second sweep
  escalates; every assertion reads a `Notification` back through `GET /api/notifications`.

### tests — modified
- `alerts/listener/AlertEventListenerTest.java` — constructor updated via a `listenerOn` helper; a
  `GRACE` of four hours keeps every stage-one fixture inside the grace window so none of them trips
  into stage two. Still 14 tests, all unchanged in substance.

### untouched
`TaskOverdueEvent` and all of `shared`, `TaskService`, `SlaSweepScheduler`, `EventBusConfiguration`,
`NotificationEntity`, `NotificationRepository` and its two implementations, `UserService`,
`data.sql`, `pom.xml`, every `routes` class. **No new event** — the catalogue stays at three. No new
entity, table, or tracking field: `source_ref`, `alert_type` and `created_at` on the existing
notification rows carry the whole state machine. No new `AppError` subtype, no new enum value, no
schema change, no new endpoint, no `TaskService` in `alerts`. The `activities` module was not touched.

## 4. Deviations From The Contract

**1. Scenario 1 and 3 instants expressed relative to the canonical clock.** The contract names a clock
at `2026-02-01T12:00:00Z` with breach times of `07:00` and `08:00`. `how-to-test` §2 requires the
fixed clock to be `2026-02-01T10:00:00Z`, which is what every other test class in the suite uses. I
kept `10:00:00Z` and expressed breach ages relative to it — `Duration.ofHours(5)`, `GRACE`,
`GRACE.minusSeconds(1)`. The relationships each scenario tests are identical; only the absolute
labels differ. Same conflict, same resolution, as Sprint 3's contract; still a Planner-side issue.

**2. Scenario 9's override case uses `ApplicationContextRunner`, not a second `@SpringBootTest`.**
Same reasoning the Evaluator accepted in Sprint 3: a distinct `@SpringBootTest` property set means
another cached context against the JVM-wide H2 database. The runner binds the real property key
through the real relaxed-binding machinery, which is the entire claim. The shipped-default case does
use a full `@SpringBootTest`, and deliberately declares the property set already used by seven other
classes so it **reuses a cached context rather than adding one**. Net new contexts this sprint: one,
for `SlaEscalationIntegrationTest`, which genuinely needs the real bus and database.

**3. Scenario 11 says "the same nine endpoints"; the real number is ten, and was ten before this
sprint.** The count in my contract was wrong, not the code. The routes are five on `TaskRoutes`
(including `PATCH /api/tasks/bulk-status`, added by the shift handover feature in Sprints 1–2) plus
one each on `NotificationRoutes`, `ReportRoutes`, `UserRoutes` and two on `ProjectRoutes`. The
substance of the criterion holds: **this feature added none**, and no `Routes` file was modified in
Sprint 3, 4 or 5. Related documentation drift for the Planner: `app-context` §3 still says "Nine
endpoints exist today", which stopped being true two sprints before this feature began.

**4. `./mvnw` still does not exist.** Third sprint reporting it. Used `mvn clean test`.

## 5. Known Gaps

**In scope and complete.** No contract criterion was dropped. Sprint 4's carried finding is closed:
`SlaEscalationListenerTest.aBlockedActivityEscalationDoesNotSuppressTheBreach` is the listener-level
test that stage one discriminates by alert type, and it sits on the same fixture as its stage-two
sibling exactly as the finding suggested.

The feature is complete. Remaining observations, none of them in-scope work:

1. **`AlertEventListener` is at 27/28 branches (96%).** The uncovered branch is the blank-assignee arm
   of the pre-existing `isUnassigned` helper on the untouched `onTaskStatusChanged` path — baseline
   code, carried unchanged since Sprint 4's summary noted it.
2. **Delivery is still `PENDING`-only.** `NotificationService.raise` creates every alert `PENDING` and
   nothing calls `markSent`, so an `SLA_BREACH` and its escalation are both visible through
   `GET /api/notifications` but never "delivered". That is the pre-existing stub boundary described in
   `NotificationService`'s own javadoc and was never in this feature's scope; worth naming because
   "fire a notification" could be read as including delivery.
3. **Nothing resolves a breach.** If an activity later reaches `DONE`, the sweep stops publishing and
   no further alerts are raised, but the existing `SLA_BREACH` and `ESCALATION` rows stay `PENDING`
   forever. No criterion asked for retraction or acknowledgement, and adding one would need a new
   `TaskStatusChangedEvent` consumer in `alerts`. A candidate for a future feature, not a gap in this
   one.
