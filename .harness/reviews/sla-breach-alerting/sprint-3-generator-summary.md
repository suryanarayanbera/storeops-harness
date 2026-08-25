# Generator Summary — Sprint 3: Grace Period Escalation and Episode Closure

`./mvnw clean test` — **BUILD SUCCESS**, 152 tests, 0 failures, 0 errors. Checkstyle (validate),
SpotBugs (test-compile) and `jacoco:check` all ran and passed. Test count 134 → 152.

## 1. AC self-check

| AC | Criterion (short) | Met | Test proving it |
| --- | --- | --- | --- |
| 1 | A breach still observed after the grace period escalates to the Store Manager | yes | `SlaBreachServiceTest.breachStillObservedAfterTheGracePeriodEscalates` |
| 2 | Before the grace period elapses, nothing is raised | yes | `SlaBreachServiceTest.beforeTheGracePeriodElapsesNothingIsRaised` |
| 3 | A first observation never escalates, even with `PT0S` | yes | `SlaBreachServiceTest.firstObservationNeverEscalates` |
| 4 | Escalation happens once, however many observations follow | yes | `SlaBreachServiceTest.escalationHappensOnlyOnce` |
| 5 | The same person is never told twice about one activity | yes | `SlaBreachServiceTest.escalationToTheAlreadyNotifiedRecipientRaisesNothing` |
| 6 | With no Store Manager, escalation is retried rather than lost | yes | `SlaBreachServiceTest.escalationWithNoStoreManagerIsRetried` |
| 7 | The grace period is configurable and defaults safely | yes | `SlaBreachServiceTest.gracePeriodIsConfigurable` (PT48H vs PT1H), `SlaEscalationPropertiesTest` (null, negative, as-given), `SlaEscalationPropertiesBindingTest.shippedConfigurationBindsTwoHours`, `SlaEscalationIntegrationTest.gracePeriodIsBoundFromConfiguration` |
| 8 | Reaching DONE closes the episode; a later breach is a first observation again | yes | `SlaBreachServiceTest.closingAnEpisodePreventsEscalationAndStartsOverOnTheNextBreach`, `.closingAnUnknownEpisodeIsANoOp` |
| 9 | A DONE transition closes through the listener; BLOCKED still alerts the assignee | yes | `AlertEventListenerTest.doneTransitionClosesTheBreachEpisode`, `.blockedTransitionLeavesTheBreachEpisodeAlone` |
| 10 | The whole chain works end to end through the container | yes | `SlaEscalationIntegrationTest.unresolvedBreachEscalatesToTheStoreManagerExactlyOnce` |
| 11 | Resolution through the real API closes the episode end to end | yes | `SlaEscalationIntegrationTest.resolvingAnActivityThroughTheApiClosesItsEpisode` |
| 12 | The schema still matches | yes | `H2SchemaTest.slaBreachesTableShape` |

### Three declared deviations

**AC 10 uses its own activity, not seed `task-001`.** Written against `task-001` first, and it failed:
`publishOverdueBreaches()` sweeps every overdue activity, so the sibling test in the same class opened and
then escalated `task-001`'s episode before this test ran, and "no escalation after one sweep" was already
false. The test now creates its own overdue activity through `POST /api/tasks`. Same criterion, no
dependence on which test in the class runs first.

**AC 7's override case binds `PT0S`, not `PT30M`.** The contract asked for a `@TestPropertySource`
proving an override reaches the bean; `SlaEscalationIntegrationTest` already sets `PT0S` for its own
reasons and asserts the injected record reads it. Using `PT30M` would have meant a fourth Spring context
for the same assertion.

**`SlaBreachJpaRepository.DEFAULT_SORT` removed.** Dropping `findAll()` for cleanup F1 left the sort
constant with no reader. Removing it is the rest of that cleanup, not a separate decision.

### Evaluator cleanups from Sprint 2, folded in

* **F1 — `SlaBreachRepository.findAll()` dropped**, along with the now-unused `DEFAULT_SORT`. Every
  episode is reached by activity id; `SlaBreachServiceTest` still calls `findAll()` on the fake, which
  inherits it from `FakeRepository` independently of the port. The out-of-scope breach-tracker endpoint
  can reintroduce it when it has a caller.
* **F2 — `@Transactional` added to `SlaBreachService.observe`**, and to the new `closeEpisode`. Default
  `REQUIRED` propagation joins the listener's transaction, so runtime behaviour is unchanged; the alert
  and the episode row it is recorded in can no longer come apart if a second caller appears.

## 2. Files changed

### alerts — source
- `service/SlaEscalationProperties.java` — **new** `@ConfigurationProperties("storeops.alerts.sla")`
  record; compact constructor substitutes `PT2H` for a null or negative `gracePeriod`
- `service/SlaBreachService.java` — `SlaEscalationProperties` injected; `observe` now routes an existing
  episode through a new `reobserve`, which escalates once the grace period has elapsed, notes the
  sighting otherwise, and never escalates a first observation; new `escalate` resolving the store
  manager, skipping the alert when it would go to the lead who was already told, and leaving
  `escalatedAt` null when no manager exists; new `closeEpisode`. `@Transactional` on both public methods
- `domain/SlaBreach.java` — added `escalationRecipientId` and `escalatedAt`, plus `withEscalation(...)`,
  `isEscalated()` and `escalationDueAt(Duration)` so the grace arithmetic sits on the record
- `repository/SlaBreachEntity.java` — `escalation_recipient_id` and `escalated_at`, both nullable
- `repository/SlaBreachRepository.java` — `deleteByTaskId` added, `findAll` removed
- `repository/JpaSlaBreachRepository.java` — `deleteByTaskId`; `findAll` removed
- `repository/SlaBreachJpaRepository.java` — `DEFAULT_SORT` removed; now a bare `JpaRepository`
- `listener/AlertEventListener.java` — `onTaskStatusChanged` gained a `DONE` branch calling
  `closeEpisode`; the `BLOCKED` branch and both transaction annotations are unchanged

### root — configuration
- `StoreOpsApplication.java` — `@ConfigurationPropertiesScan`
- `src/main/resources/application.yml` — `storeops.alerts.sla.grace-period: PT2H`, commented

### tests
- `alerts/service/SlaBreachServiceTest.java` — 9 tests added (7 escalation, 2 closure); the service
  factory takes a grace period, and `observeAgainAt` expresses a later sweep
- `alerts/service/SlaEscalationPropertiesTest.java` — **new**, 3 tests on the defaulting rules
- `alerts/service/SlaEscalationPropertiesBindingTest.java` — **new** `@SpringBootTest`; the shipped
  configuration binds `PT2H`
- `alerts/SlaEscalationIntegrationTest.java` — **new** `@SpringBootTest @AutoConfigureMockMvc` with
  `grace-period=PT0S`; 3 tests covering AC 7's override, AC 10 and AC 11
- `alerts/listener/AlertEventListenerTest.java` — 2 tests added for the `DONE` and `BLOCKED` branches;
  constructor updated for the properties record
- `H2SchemaTest.java` — the two new columns
- `support/FakeSlaBreachRepository.java` — `deleteByTaskId`

Nothing in `activities`, `programmes`, `staff`, `reports` or `shared` was touched. No new event type, no
new `AppError` subtype, no new `AlertType` value, no route.

## 3. Known gaps

- **Sprint 1's two cleanups are still open**, and no sprint in this feature reopens `activities`:
  "overdue" is still defined in SQL for the sweep and in Java for `reports` with no test tying them
  together, and `OverdueSweepScheduler` still logs at `INFO` on empty sweeps. They need scheduling or an
  explicit write-off.
- **`lastSeenAt` still settles no decision.** Sprint 2's review asked whether it earns a write per open
  breach per sweep. It is now written on the escalated path too. Nothing here answers the question.
- **Escalation stops at the store manager.** `StaffRole` has `REGIONAL_MANAGER`; no further rung exists,
  and an escalation nobody acts on is never re-raised.
- **A reopened activity restarts the grace period** — deliberate, and recorded as out of scope in
  `spec.md`, but it does mean a repeatedly-reopened activity can never escalate.
- **No endpoint exposes the breach tracker.** Open episodes are visible only through their notifications
  or in the database, as `spec.md` flagged.
- **The escalation decision reads `event.occurredAt()` while `lastSeenAt` reads the clock.** Both come
  from the same sweep and agree in practice; nothing asserts they cannot drift apart.
