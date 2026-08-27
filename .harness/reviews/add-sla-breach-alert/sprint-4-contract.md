# Sprint 4: SLA Breach Routed to the Department Lead

## Goal

Correct the recipient and stop the duplicates. `AlertEventListener.onTaskOverdue()` currently raises
`SLA_BREACH` to the activity's assignee and raises it again on every sweep. After this sprint it
raises exactly one `SLA_BREACH`, addressed to the Department Lead responsible for the assignee's
department, and suppresses every later observation of the same breach.

The grace-period escalation is **not** in this sprint. A repeat observation is simply suppressed here;
Sprint 5 gives the suppressed branch its second stage.

Deliverables:
* `staff/service/UserService` — add `List<User> findByStoreIdAndRole(String storeId, StaffRole role)`
  returning only staff where `active` is `true`, sorted by `id` ascending. Read-only; add no mutator.
  Implement by filtering `userRepository.findByStoreId(storeId)`; do **not** add a repository method,
  and do **not** widen `UserRepository`.
* `alerts/repository/NotificationRepository` — add
  `List<Notification> findBySourceRefAndAlertType(String sourceRef, AlertType alertType)`, ordered by
  `createdAt` ascending. Implement in `JpaNotificationRepository` (delegating to a derived query on
  `NotificationJpaRepository`) and in the test fake `support/FakeNotificationRepository`.
* `alerts/service/NotificationService` — expose the lookup above to the listener, ordered oldest
  first. The listener must not reach the repository directly.
* `alerts/listener/AlertEventListener` — rewrite `onTaskOverdue()` per the rules below; declare
  `SLA_BREACH_SUBJECT = "SLA breach"` as a constant. Leave `onTaskStatusChanged()` untouched.

`onTaskOverdue()` behaviour, in order:

1. `priority` not in `{HIGH, CRITICAL}` → log `DEBUG`, return. (Existing behaviour, keep it.)
2. An `SLA_BREACH` notification already exists with `sourceRef == event.taskId()` → log `DEBUG`,
   return. This is the suppression branch Sprint 5 will extend.
3. Resolve the Department Lead per `spec.md` § *Recipient resolution rules*: the assignee's department
   within `event.storeId()`, `role == DEPARTMENT_LEAD`, `active`, lowest `id` on a tie.
4. Nobody found — for any reason, including a null, blank or unknown `assigneeId` and a null
   department — → fall back to the store's `STORE_MANAGER`, log `WARN`.
5. Still nobody → log `WARN`, return. Raise nothing. Throw nothing.
6. Raise `SLA_BREACH` with subject exactly `SLA breach`, `sourceRef = event.taskId()`, and a body
   naming the activity id, the store id, the priority and the missed `dueAt`.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: A breach alerts the assignee's Department Lead, not the assignee**
* **GIVEN** an `AlertEventListener` on a `NotificationService` backed by an empty
  `FakeNotificationRepository`, and a `UserService` holding the five seed staff members
* **WHEN** it handles a `TaskOverdueEvent` for `task-001` in `store-001`, priority `HIGH`, assignee
  `user-004`, due `2026-01-07T08:00:00Z`
* **THEN** exactly one `Notification` is saved
* **AND** its `recipientId` is `user-003` — the `GROCERY` `DEPARTMENT_LEAD` at `store-001` — and
  explicitly **not** `user-004`
* **AND** its `alertType` is `SLA_BREACH`, `channel` is `IN_APP`, `status` is `PENDING`, `subject` is
  exactly `SLA breach`, and `sourceRef` is `task-001`
* **AND** its `body` contains `task-001`, `store-001`, `HIGH` and the `dueAt` value

**Scenario 2: A repeat sweep raises nothing**
* **GIVEN** the Scenario 1 fixture, already holding one `SLA_BREACH` for `sourceRef` `task-001`
* **WHEN** the identical `TaskOverdueEvent` is handled twice more
* **THEN** the repository still holds exactly one notification in total
* **AND** the surviving row is the original — same `id` and same `createdAt`, so suppression is
  proven to be suppression and not a silent overwrite

**Scenario 3: A tracked breach on a different activity is not suppressed by an existing one**
* **GIVEN** a fixture already holding an `SLA_BREACH` for `sourceRef` `task-001`
* **WHEN** a `TaskOverdueEvent` for a different activity `task-009` in `store-001`, priority
  `CRITICAL`, assignee `user-004`, is handled
* **THEN** a second notification is saved, with `sourceRef` `task-009` and recipient `user-003`
* **AND** de-duplication is proven to key on the activity, not on the alert type alone

**Scenario 4: An untracked priority raises nothing**
* **GIVEN** an empty fixture
* **WHEN** `TaskOverdueEvent`s are handled for priority `MEDIUM` and then `LOW`
* **THEN** no notification is saved for either

**Scenario 5: No matching Department Lead falls back to the Store Manager**
* **GIVEN** a fixture whose staff roster for `store-002` is `user-005` only (`STORE_MANAGER`, no
  `DEPARTMENT_LEAD` in that store)
* **WHEN** a `TaskOverdueEvent` for a `CRITICAL` activity in `store-002` with assignee `user-005` is
  handled
* **THEN** one `SLA_BREACH` is saved with `recipientId` `user-005`

**Scenario 6: An unassigned breach still reaches the Store Manager**
* **GIVEN** the seed roster
* **WHEN** a `TaskOverdueEvent` for a `HIGH` activity in `store-001` with a null `assigneeId` is
  handled, and again with a blank `assigneeId`, and again with `assigneeId` `user-999` who does not
  exist
* **THEN** each produces one `SLA_BREACH` with `recipientId` `user-002` — the `store-001`
  `STORE_MANAGER` — because no department can be determined
* **AND** no exception is thrown in any of the three cases

**Scenario 7: No resolvable recipient at all is silent, not an error**
* **GIVEN** a fixture whose staff roster for `store-003` is empty
* **WHEN** a `TaskOverdueEvent` for a `CRITICAL` activity in `store-003` is handled
* **THEN** no notification is saved
* **AND** no exception propagates out of the handler — in particular no `ValidationError` from
  `NotificationService.raise`, which must never be reached with a blank recipient

**Scenario 8: Inactive and wrong-department leads are skipped**
* **GIVEN** a roster for `store-001` containing an inactive `DEPARTMENT_LEAD` in `GROCERY`
  (`user-010`, `active` false) and an active `DEPARTMENT_LEAD` in `OPERATIONS` (`user-011`)
* **WHEN** a `TaskOverdueEvent` with assignee `user-004` (`GROCERY`) is handled
* **THEN** the recipient is neither `user-010` nor `user-011`; it is the active `GROCERY` lead
  `user-003`
* **AND** with `user-003` removed from the roster, the recipient falls back to `user-002`, not to
  `user-010` or `user-011`

**Scenario 9: Two candidate leads resolve deterministically**
* **GIVEN** a roster for `store-001` with two active `GROCERY` `DEPARTMENT_LEAD`s, `user-003` and
  `user-007`
* **WHEN** the `task-001` event is handled
* **THEN** the recipient is `user-003`, the lowest id
* **AND** the assertion is repeated with the roster supplied in the reverse order, proving the
  outcome does not depend on iteration order

**Scenario 10: End to end, after commit, through the real bus and the real database**
* **GIVEN** a `@SpringBootTest` with
  `properties = "storeops.activities.sla.sweep.enabled=false"` and
  `@DirtiesContext(BEFORE_EACH_TEST_METHOD)`, on the unmodified seed data
* **WHEN** `TaskService.publishOverdueBreaches()` is invoked directly
* **THEN** it returns `1`
* **AND** `GET /api/notifications?recipientId=user-003` returns two alerts: the seeded
  `SHIFT_HANDOVER` `notification-001` plus one `SLA_BREACH` whose `sourceRef` is `task-001`
* **AND** `GET /api/notifications?recipientId=user-004` returns an empty list, proving the assignee
  is not notified
* **AND** invoking `publishOverdueBreaches()` a second time in the same test leaves
  `GET /api/notifications?recipientId=user-003` at two alerts, proving after-commit de-duplication
  works against the real database and not only against the fake

**Scenario 11: Removing the publisher's transaction breaks a test**
* **GIVEN** Scenario 10
* **WHEN** the Generator verifies its own work by temporarily deleting `@Transactional` from
  `TaskService.publishOverdueBreaches()`
* **THEN** Scenario 10 fails
* **AND** the annotation is restored and the check is recorded in `generator-summary.md`. A suite that
  stays green without it is not testing the after-commit path at all.

## Architectural Guardrails

* **`alerts` reads staff through `UserService` only.** Importing `staff.repository.UserRepository`
  fails `ModuleBoundaryTest` rule 1. `alerts.listener` → `staff.service` is permitted by rule 5,
  which lists `Listener` among the layers allowed to reach `Service`, and creates no cycle because
  `staff` imports nothing from `alerts`.
* **Do not inject `TaskService` into `alerts`.** The event already carries every field the decision
  needs. A lookup back into `activities` would make `alerts` depend on activity state it does not own
  and would put the two modules one edit away from a cycle under rule 2.
* **Do not move recipient resolution into `activities`.** Having `TaskService` resolve the Department
  Lead and put a `recipientId` on the event would make `activities` decide who gets alerted — the
  precise boundary inversion the harness exists to catch, and it would force a `shared` event change.
* **The listener calls `NotificationService`, never `NotificationRepository`.** Rule 1b confines the
  repository layer to its own module's service layer; rule 5 forbids a listener reaching past a
  service.
* **`TaskOverdueEvent` does not change.** No new field, no new event, and enum values stay `String`.
  A `TaskPriority` on the payload would drag `activities.domain` into `alerts` and fail rule 3b.
* **The handler keeps both annotations:** `@TransactionalEventListener(phase = AFTER_COMMIT)` and
  `@Transactional(propagation = REQUIRES_NEW)`. Dropping `REQUIRES_NEW` discards the write with no
  error anywhere, which Scenario 10 is placed to catch.
* **`UserService` gains no mutator.** Staff is read-only for other modules and that is currently
  structural, not conventional — there is no write method to call. Keep it that way.
* **Throw nothing from the handler.** It runs after commit, so Spring swallows the exception and the
  operator sees nothing. Scenario 7 asserts this. An unresolvable recipient is a logged `WARN`, not
  an `AppError`.
