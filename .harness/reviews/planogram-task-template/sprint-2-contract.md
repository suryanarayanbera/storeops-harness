# Sprint 2: The Activities Listener Creates The Cloned Tasks

## Goal

`activities` subscribes to `PROGRAMME_TEMPLATE_REQUESTED` and turns each carried item into a `Task`
row: `status = TODO`, `category = PLANOGRAM`, the template's default priority, the resolved
assignee, and the programme's `projectId` and `storeId`. Items whose title already exists on that
programme are skipped, so a repeat call creates nothing.

This closes the feature. Sprint 1's endpoint already publishes the event; nothing in `programmes`
changes here.

## Scope

Build in `activities`:

* `listener/TaskTemplateEventListener` — new `listener` package for this module. One handler,
  `onProgrammeTemplateRequested(ProgrammeTemplateRequestedEvent)`, annotated
  `@TransactionalEventListener(phase = AFTER_COMMIT)` **and**
  `@Transactional(propagation = REQUIRES_NEW)`. It delegates to the service and logs how many
  activities it created and how many it skipped. No business logic in the listener beyond the call.
* `service/TaskService.createFromTemplate(String projectId, String storeId, List<TemplateTaskDefinition> items)`
  — the write, returning the created activities so the listener can log a count.

The rules `createFromTemplate` applies, per item, in order:

1. **Skip by title.** If `taskRepository.findByProjectId(projectId)` already holds an activity whose
   title equals the item's title, ignoring case and surrounding whitespace, skip the item. This is
   what makes a repeat call a no-op. It compares against the programme's activities of every
   category, not just `PLANOGRAM` — a title clash is a clash.
2. **Parse the priority.** `TemplateTaskDefinition.priority()` is a `String`. Map it to
   `TaskPriority`; anything unrecognised, blank or null falls back to `MEDIUM`, matching
   `TaskService.create`'s existing fallback for an absent priority. A bad value must not throw: the
   event has already been published and the publisher's transaction has committed, so throwing
   loses the whole batch to a log line.
3. **Parse the category** the same way, falling back to `GENERAL`.
4. **Drop an unknown assignee.** If `assigneeId` is non-null and `UserService.exists` says no, create
   the activity unassigned rather than rejecting it. The work still needs raising, and a
   `ValidationError` thrown in an after-commit listener reaches nobody but the log.
5. **Create.** `status = TODO`, `dueAt = null`, `createdAt = updatedAt = clock.instant()`, id from
   `UUID.randomUUID()`.

No `TaskStatusChangedEvent` is published. These activities are born in `TODO`; nothing transitioned.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: End to end through the endpoint**
* **GIVEN** the full Spring context and seed programme `project-002`, `PLANNED` in `store-002`,
  whose only activity is `task-004`
* **WHEN** `POST /api/projects/project-002/templates` is called with
  `{"templateId":"PLANOGRAM_STANDARD","requestedBy":"user-005"}`
* **THEN** the response is `202 Accepted`
* **AND** `GET /api/tasks?storeId=store-002` afterwards returns five activities — `task-004` plus
  the four clones
* **AND** all four clones have `status` `TODO`, `category` `PLANOGRAM`, `projectId` `project-002`,
  `storeId` `store-002` and a null `dueAt`
* **AND** their priorities are `HIGH`, `HIGH`, `MEDIUM`, `LOW` against the catalogue titles
* **AND** the two `OPERATIONS` titles are assigned to `user-005` and the two `GROCERY` titles have
  a null `assigneeId`

**Scenario 2: A repeat call creates nothing**
* **GIVEN** Scenario 1 has already run, so `project-002` holds the four clones
* **WHEN** the identical request is sent a second time
* **THEN** the response is again `202` with `taskCount` `4` — the count is the template expansion,
  not a count of rows written
* **AND** `GET /api/tasks?storeId=store-002` still returns exactly five activities
* **AND** no clone is duplicated, proving the skip-by-title rule

**Scenario 3: Partial skip when one title already exists**
* **GIVEN** an activity is saved on a programme with the title `Verify shelf-edge labelling` and
  category `GENERAL`
* **WHEN** the template is applied to that programme
* **THEN** three activities are created, not four
* **AND** the pre-existing activity is untouched — its category is still `GENERAL` and its
  `updatedAt` is unchanged
* **AND** the skip matched across categories, not only against `PLANOGRAM` activities

**Scenario 4: Titles are compared ignoring case and surrounding whitespace**
* **GIVEN** an activity saved on a programme titled `  reset grocery aisle planograms  `
* **WHEN** the template is applied to that programme
* **THEN** the `Reset grocery aisle planograms` line is skipped and three activities are created

**Scenario 5: The listener is called and it writes**
* **GIVEN** a `ProgrammeTemplateRequestedEvent` published directly onto the `EventBus` inside a
  transaction, carrying two items for `project-001` in `store-001`
* **WHEN** the transaction commits
* **THEN** two activities exist on `project-001` with the carried titles
* **AND** removing `@Transactional(propagation = REQUIRES_NEW)` from the listener makes this
  scenario fail while the log line still prints — that is the failure mode being guarded

**Scenario 6: An unrecognised priority string falls back to MEDIUM**
* **GIVEN** an event published directly, carrying one item with `priority` `"URGENT"`
* **WHEN** the listener runs
* **THEN** the created activity has priority `MEDIUM`
* **AND** no exception escapes the listener

**Scenario 7: A null priority falls back to MEDIUM and a null category to GENERAL**
* **GIVEN** an event published directly, carrying one item with null `priority` and null `category`
* **WHEN** the listener runs
* **THEN** the created activity has priority `MEDIUM` and category `GENERAL`

**Scenario 8: An unknown assignee is dropped, not rejected**
* **GIVEN** an event published directly, carrying one item with `assigneeId` `user-999`
* **WHEN** the listener runs
* **THEN** the activity is created with a null `assigneeId`
* **AND** it is created — the unknown staff id does not cost the caller the activity

**Scenario 9: An empty item list writes nothing**
* **GIVEN** an event published directly, carrying an empty `items` list
* **WHEN** the listener runs
* **THEN** no activity is created and no exception is thrown

**Scenario 10: A rolled-back request creates no activity**
* **GIVEN** the full Spring context
* **WHEN** a transaction publishes `ProgrammeTemplateRequestedEvent` and then rolls back
* **THEN** no activity is created, because delivery is `AFTER_COMMIT`

**Scenario 11: The listener does not publish a status-change event**
* **GIVEN** a test subscriber on `TaskStatusChangedEvent`
* **WHEN** the template is applied and four activities are created
* **THEN** the subscriber receives nothing — the activities are born `TODO` and nothing
  transitioned
* **AND** `GET /api/notifications?recipientId=user-005` shows no new alert

**Scenario 12: A failing peer subscriber does not stop the clone**
* **GIVEN** a second test subscriber on `ProgrammeTemplateRequestedEvent` that throws
* **WHEN** the endpoint is called against `project-002`
* **THEN** the four activities are still created, proving subscriber isolation through the
  `ErrorHandler` in `EventBusConfiguration`
* **AND** the response is still `202`

## Architectural Guardrails

* **`activities` must not import anything from `programmes`.** Not `ProjectService`, not
  `ProjectMember`, not `ProjectRole`. Everything the listener needs is on the event, which is the
  reason Sprint 1 resolves the assignees before publishing. An import here plus Sprint 1's
  publication is the cycle `ModuleBoundaryTest` rule 2 fails on.
* **Both listener annotations are required.** `@TransactionalEventListener(phase = AFTER_COMMIT)`
  without `@Transactional(propagation = REQUIRES_NEW)` runs the handler and writes nothing: the
  publishing transaction has already committed, so a write joining it is never flushed. Missing
  either one is an automatic FAIL under `evaluation-criteria` §3.
* **The listener must not throw.** Every unparseable or unresolvable field degrades to a default
  (rules 2 to 4 above). An exception at after-commit time is swallowed by the `ErrorHandler` and the
  batch is lost silently — worse than an activity with a `MEDIUM` priority.
* **The write goes through `TaskService`.** The listener may reach the service layer; it must not
  touch `TaskRepository`. `ModuleBoundaryTest` rule 5 permits `Listener → Service` and nothing past
  it.
* **The skip-by-title read stays inside `activities`.** It reads `tasks` through this module's own
  repository, from the service. Never a join against `project_members`, which
  `architecture-principles` §3 bans outright.
* **No new endpoint.** The caller reads the result back with the existing
  `GET /api/tasks?storeId={storeId}`. This sprint adds a listener and a service method, nothing in
  `routes`.
* **`data.sql` is not touched.** The clones are created by the feature at runtime. Seeding them
  would make Scenario 2's repeat-call assertion meaningless.
