# Sprint 2: After-Commit Delivery of Bulk-Published Events

## Goal

Prove that the events published by the bulk path in Sprint 1 actually reach the `alerts` module
after commit, and that they do so per task. A bulk-blocked activity must produce an `ESCALATION`
alert; a task that failed in the batch must produce nothing; and a subscriber that throws must
not break the batch.

This sprint is expected to add **no production code**. `AlertEventListener.onTaskStatusChanged`
already handles `TaskStatusChangedEvent` and needs no change. The sprint exists because the
after-commit wiring is the part of this feature that fails silently: every Sprint 1 criterion
still passes when the transaction never opens and no event is ever delivered. If a criterion
below fails, the fix belongs in the Sprint 1 transaction boundary, not in a new listener.

## Scope

**Build**
* Integration tests against a real Spring context with the seeded H2 database, in the style of
  `EventDeliveryIntegrationTest`. Assert on the *side effect* — a `Notification` row reachable
  through `GET /api/notifications` — never on the HTTP status alone.

**Do not touch**
* `AlertEventListener`, `NotificationService`, `EventBusConfiguration`, `TaskStatusChangedEvent`.
* Anything delivered in Sprint 1, unless a criterion here fails and points at the transaction
  boundary.

**Seed facts these scenarios rely on**

| Task | Status | Store | Assignee |
| --- | --- | --- | --- |
| `task-001` | `TODO` | `store-001` | `user-004` |
| `task-002` | `IN_PROGRESS` | `store-001` | `user-003` |
| `task-003` | `DONE` (terminal) | `store-001` | `user-003` |

`user-003` already owns the seeded `SHIFT_HANDOVER` alert `notification-001`, so assertions on
that recipient must filter by `type` `ESCALATION` and by the referenced activity, not just count
rows.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: A bulk block raises one alert per blocked activity**
* **GIVEN** the seeded database, with `task-001` `TODO` assigned to `user-004` and `task-002`
  `IN_PROGRESS` assigned to `user-003`
* **WHEN** `PATCH /api/tasks/bulk-status` moves both `task-001` and `task-002` to `BLOCKED` and
  the request completes
* **THEN** `GET /api/notifications?recipientId=user-004` contains exactly one `ESCALATION` alert
  referencing `task-001`
* **AND** `GET /api/notifications?recipientId=user-003` contains exactly one `ESCALATION` alert
  referencing `task-002`
* **AND** the response body already reported both tasks in `succeeded` — the alert and the
  reported success agree

**Scenario 2: A partial-failure batch still delivers for its successes**
* **GIVEN** the seeded database and no activity with the id `task-999`
* **WHEN** `PATCH /api/tasks/bulk-status` is sent with `task-999` to `BLOCKED` first and
  `task-002` to `BLOCKED` second
* **THEN** `task-002` appears in `succeeded`, `task-999` appears in `failed` with `code`
  `TASK_NOT_FOUND`
* **AND** an `ESCALATION` alert referencing `task-002` exists for `user-003`
* **AND** no alert referencing `task-999` exists for any recipient — the failing item aborted
  its own transaction and published nothing

**Scenario 3: A rejected transition raises no alert**
* **GIVEN** the seeded database, with `task-003` already `DONE`
* **WHEN** `PATCH /api/tasks/bulk-status` tries to move `task-003` to `BLOCKED`
* **THEN** `failed` holds `task-003` with `code` `TASK_TRANSITION_NOT_ALLOWED`
* **AND** `user-003` has no `ESCALATION` alert referencing `task-003`, and still holds exactly
  the seeded `notification-001`

**Scenario 4: A bulk completion raises no alert**
* **GIVEN** the seeded database, with `task-001` `TODO` assigned to `user-004`
* **WHEN** `PATCH /api/tasks/bulk-status` moves `task-001` to `DONE`
* **THEN** `task-001` appears in `succeeded` and `GET /api/tasks/task-001` reads `DONE`
* **AND** `user-004` has no notification at all — the event was published and delivered, and the
  alerts module chose not to alert on a `DONE` transition

**Scenario 5: A throwing subscriber does not break the batch**
* **GIVEN** a `FailingSubscriber` registered for `TaskStatusChangedEvent` alongside the real
  listener, and `task-001` `TODO`
* **WHEN** `PATCH /api/tasks/bulk-status` moves `task-001` to `BLOCKED` and `task-002` to
  `BLOCKED`
* **THEN** the response is `200 OK` with both tasks in `succeeded`
* **AND** `GET /api/tasks/task-001` reads `BLOCKED` and `GET /api/tasks/task-002` reads `BLOCKED`
* **AND** `FailingSubscriber` records two invocations, so the test cannot pass vacuously by
  never dispatching at all

**Scenario 6: Each task commits on its own, not at the end of the batch**
* **GIVEN** the seeded database and no activity with the id `task-999`
* **WHEN** `PATCH /api/tasks/bulk-status` moves `task-001` to `BLOCKED` first, then `task-999`
  to `BLOCKED`, then `task-002` to `BLOCKED`
* **THEN** `succeeded` holds `task-001` and `task-002`, and `failed` holds `task-999`
* **AND** reading the activities back in a fresh request shows both `task-001` and `task-002`
  as `BLOCKED` — a failure in the middle of the batch neither rolled back the write before it
  nor prevented the write after it
* **AND** exactly two `ESCALATION` alerts exist across `user-004` and `user-003`, one per
  committed transition

## Architectural Guardrails

* **Assert the side effect, never just the status code.** A test that checks only for `200 OK`
  passes through every one of the silent after-commit failures this sprint exists to catch. Each
  scenario must read the `Notification` back, or count `FailingSubscriber` invocations.
* **Do not inject `NotificationService` into anything under `activities` to "make the test
  easier".** ArchUnit rule 3 fails the build, and it would invert the boundary this feature is
  built on.
* **Do not add a fourth event.** The catalogue has three, and `TaskStatusChangedEvent` already
  carries every field these scenarios need.
* **Do not add `fallbackExecution = true` to the listener.** If an event is not being delivered,
  the cause is that no transaction was open at publish time — a Sprint 1 defect in the bulk
  service's transaction boundary. Making the listener fire without a transaction hides the bug
  and would fire alerts for rolled-back writes.
* **Leave the `ErrorHandler` bean in `EventBusConfiguration` alone.** Scenario 5 depends on it;
  without it, Spring's multicaster propagates a subscriber exception straight back into the
  caller's request.
