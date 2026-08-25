# Sprint 5: Grace-Period Escalation to the Store Manager

## Goal

Give the suppression branch from Sprint 4 a second stage. When a sweep observes a breach whose
`SLA_BREACH` alert is older than the configurable grace period, and the activity is still unresolved,
raise one `ESCALATION` alert to the store's `STORE_MANAGER`. Escalate once, never again.

Deliverables:
* `alerts/service/SlaAlertProperties.java` — `@ConfigurationProperties("storeops.alerts.sla")` record
  with `Duration gracePeriod`, rejecting a negative value in its compact constructor. Zero is valid
  and means "escalate on the next sweep".
* `src/main/resources/application.yml` — add `storeops.alerts.sla.grace-period: PT4H`.
* `alerts/listener/AlertEventListener` — extend the branch that currently returns when an
  `SLA_BREACH` already exists; declare `SLA_ESCALATION_SUBJECT = "SLA breach escalated"`. Inject
  `SlaAlertProperties` and the existing `Clock`.

The replacement for step 2 of the Sprint 4 flow:

1. Load the `SLA_BREACH` notifications for `sourceRef == event.taskId()`, oldest first. If none, fall
   through to the Sprint 4 stage-one path unchanged.
2. Take the oldest one's `createdAt` as the breach instant. If
   `clock.instant()` is before `breachInstant.plus(gracePeriod)`, the grace period has not elapsed →
   log `DEBUG`, return.
3. An SLA escalation already exists for this activity — an `ESCALATION` notification with
   `sourceRef == event.taskId()` **and** `subject` equal to `SLA breach escalated` — → log `DEBUG`,
   return.
4. Resolve the `STORE_MANAGER` for `event.storeId()`: active, `role == STORE_MANAGER`, lowest `id` on
   a tie. Nobody found → log `WARN`, return, raise nothing, throw nothing.
5. Raise `ESCALATION` with subject exactly `SLA breach escalated`, `sourceRef = event.taskId()`, and a
   body naming the activity id, the store id, the priority, the missed `dueAt` and the grace period
   that elapsed.

The `activities` module is not touched in this sprint.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: A breach older than the grace period escalates to the Store Manager**
* **GIVEN** an `AlertEventListener` with `gracePeriod` of `PT4H` and a `Clock` fixed at
  `2026-02-01T12:00:00Z`, on a `FakeNotificationRepository` already holding one `SLA_BREACH` for
  `sourceRef` `task-001` with `createdAt` `2026-02-01T07:00:00Z` — five hours earlier
* **WHEN** a `TaskOverdueEvent` for `task-001` in `store-001`, priority `HIGH`, assignee `user-004`,
  is handled
* **THEN** a second notification is saved, bringing the total to two
* **AND** its `alertType` is `ESCALATION`, `recipientId` is `user-002` — the `store-001`
  `STORE_MANAGER` — `subject` is exactly `SLA breach escalated`, `sourceRef` is `task-001`,
  `status` is `PENDING`
* **AND** its `body` contains `task-001`, `store-001` and `HIGH`
* **AND** the original `SLA_BREACH` row is unchanged: same `id`, same `recipientId` `user-003`, same
  `createdAt`

**Scenario 2: A breach inside the grace period does not escalate**
* **GIVEN** the same fixture but with the `SLA_BREACH` `createdAt` at `2026-02-01T09:00:00Z` — three
  hours before the fixed clock, inside the four-hour grace period
* **WHEN** the `task-001` event is handled
* **THEN** the repository still holds exactly one notification, the original `SLA_BREACH`
* **AND** no `ESCALATION` exists for `sourceRef` `task-001`

**Scenario 3: The grace boundary is exclusive of "not yet", inclusive of "exactly now"**
* **GIVEN** `gracePeriod` `PT4H` and a clock fixed at `2026-02-01T12:00:00Z`
* **WHEN** the `task-001` event is handled against an `SLA_BREACH` created at exactly
  `2026-02-01T08:00:00Z` — precisely four hours earlier
* **THEN** an `ESCALATION` is raised
* **AND** repeated with a `createdAt` one second later, `2026-02-01T08:00:01Z`, no `ESCALATION` is
  raised, pinning the boundary rather than leaving it to the Evaluator's reading

**Scenario 4: A zero grace period escalates on the next observation**
* **GIVEN** `gracePeriod` of `PT0S` and a fixture holding an `SLA_BREACH` for `task-001` created at
  the fixed clock instant itself
* **WHEN** the `task-001` event is handled
* **THEN** an `ESCALATION` is raised to `user-002`
* **AND** zero is confirmed a legal configuration value, not a validation failure

**Scenario 5: Escalation happens once, no matter how many sweeps follow**
* **GIVEN** the Scenario 1 fixture after the escalation has been raised
* **WHEN** the identical event is handled three more times
* **THEN** the repository still holds exactly two notifications
* **AND** both surviving rows keep their original `id` and `createdAt`

**Scenario 6: A pre-existing blocked-activity `ESCALATION` does not suppress the SLA escalation**
* **GIVEN** the Scenario 1 fixture with one extra row: an `ESCALATION` for `sourceRef` `task-001`
  with subject `Activity blocked`, as `onTaskStatusChanged` would have written when the activity was
  blocked
* **WHEN** the `task-001` event is handled
* **THEN** an SLA `ESCALATION` with subject `SLA breach escalated` is still raised, bringing the total
  to three
* **AND** the test comment states the defect it guards: a stage-two check keyed on `alertType`
  alone would suppress this escalation permanently

**Scenario 7: A first observation still raises stage one, not stage two**
* **GIVEN** an empty `FakeNotificationRepository`, `gracePeriod` `PT0S`, and the seed roster
* **WHEN** the `task-001` event is handled once
* **THEN** exactly one notification is saved, an `SLA_BREACH` to `user-003`
* **AND** no `ESCALATION` exists — a zero grace period must not collapse both stages into one sweep

**Scenario 8: No Store Manager is silent, not an error**
* **GIVEN** a fixture holding a five-hour-old `SLA_BREACH` for an activity in `store-003`, whose
  staff roster is empty
* **WHEN** the event is handled
* **THEN** no `ESCALATION` is saved and no exception propagates out of the handler
* **AND** the stage-one `SLA_BREACH` row is left untouched

**Scenario 9: The grace period is configurable and validated**
* **GIVEN** a `@SpringBootTest` with `properties = "storeops.activities.sla.sweep.enabled=false"`
* **WHEN** `SlaAlertProperties` is resolved from the context
* **THEN** `gracePeriod` is `PT4H`
* **AND** with `properties = {"storeops.activities.sla.sweep.enabled=false", "storeops.alerts.sla.grace-period=PT1S"}`
  the resolved value is `PT1S`, proving the key is genuinely bound and not a hard-coded constant
* **AND** constructing `SlaAlertProperties` directly with a negative `Duration` throws
  `ValidationError` carrying code `VALIDATION_FAILED`

**Scenario 10: Both stages end to end, after commit, through the real bus and database**
* **GIVEN** a `@SpringBootTest` with
  `properties = {"storeops.activities.sla.sweep.enabled=false", "storeops.alerts.sla.grace-period=PT0S"}`
  and `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`, on the unmodified seed data
* **WHEN** `TaskService.publishOverdueBreaches()` is invoked, and then invoked a second time
* **THEN** after the first invocation `GET /api/notifications?recipientId=user-003` returns the seeded
  `SHIFT_HANDOVER` plus one `SLA_BREACH` for `task-001`, and
  `GET /api/notifications?recipientId=user-002` returns an empty list
* **AND** after the second invocation `GET /api/notifications?recipientId=user-002` returns exactly
  one alert, an `ESCALATION` with `sourceRef` `task-001` and subject `SLA breach escalated`
* **AND** `GET /api/notifications?recipientId=user-003` is still at two alerts — the escalation goes
  to the manager, not also to the lead
* **AND** a third invocation changes neither list

**Scenario 11: The whole suite is green and the API surface is unchanged**
* **GIVEN** the completed feature
* **WHEN** `./mvnw clean test` runs
* **THEN** it exits `0`, with Checkstyle at zero violations, SpotBugs clean, all 12 ArchUnit rules
  passing and the JaCoCo gates met
* **AND** the API still exposes the same nine endpoints — this feature adds none — and
  `ApiSmokeTest` passes unmodified apart from the sweep-disabling property added in Sprint 3

## Architectural Guardrails

* **Do not add an `SlaBreach` entity, table or tracking field.** The `SLA_BREACH` notification row is
  already the record of when the breach was first alerted; `source_ref`, `alert_type` and `created_at`
  carry the whole state machine. A second store of the same fact is a consistency bug waiting to
  happen, and it would need a `data.sql` and schema change this feature does not otherwise require.
* **Do not inject `TaskService` into `alerts` to re-check whether the activity is `DONE`.** The
  arrival of the event is the proof: `activities` only publishes for activities that are still
  SLA-tracked, still past due and still not `DONE`. Reaching back into `activities` would make
  `alerts` depend on state it does not own and put the two modules one edit from a rule 2 cycle.
* **Do not add a fourth event.** An `SlaEscalatedEvent` would be `alerts` publishing to itself. The
  event catalogue stays at three.
* **Stage two is keyed on `alertType` *and* `subject`.** `AlertType.ESCALATION` is shared with the
  blocked-activity path, which also writes `sourceRef = taskId`. Scenario 6 exists to fail if this is
  reduced to an `alertType` check.
* **Use the injected `Clock`, never `Instant.now()`.** Every grace-period scenario pins time with
  `Clock.fixed(...)`; a direct call to `Instant.now()` makes Scenarios 2, 3 and 4 untestable and is a
  Checkstyle-invisible defect.
* **The escalation recipient comes from `UserService`, resolved in `alerts`.** Same reasoning as
  Sprint 4: `alerts.listener` → `staff.service` is permitted by rule 5 and creates no cycle;
  `staff.repository` is off limits under rule 1.
* **Both handler annotations stay.** `@TransactionalEventListener(phase = AFTER_COMMIT)` plus
  `@Transactional(propagation = REQUIRES_NEW)`. Scenario 10 is the test that bites if either is
  dropped.
* **A negative grace period is a `ValidationError`, never an `IllegalArgumentException`.** Rules 6
  and 6b require an `AppError` subtype from `shared.error`, and no new subtype is warranted.
* **No new endpoint.** The feature is fully automatic; every assertion above reads state back through
  the existing `GET /api/notifications`. Adding a manual trigger route would change the documented
  API surface for test convenience.
