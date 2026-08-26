# Sprint 1: Template Endpoint And Department Resolution

## Goal

`POST /api/projects/{id}/templates` validates the programme and the template id, expands the
`PLANOGRAM_STANDARD` catalogue, resolves each line's department to a programme member, publishes
`ProgrammeTemplateRequestedEvent` carrying the resolved lines, and answers `202 Accepted` with the
assignment echo.

No `Task` is created in this sprint. The published event is the deliverable; the subscriber that
acts on it is Sprint 2.

## Scope

Build in `programmes`:

* `domain/PlanogramTemplateItem` — record `(String title, String description, String department, String priority)`.
  Priority travels as a `String` constant, not `TaskPriority`: importing `activities.domain` here
  would put an edge into a module that Sprint 2 makes depend on nothing, and the event carries a
  `String` anyway.
* `domain/PlanogramTemplate` — the catalogue. `PLANOGRAM_STANDARD` and its four ordered items, plus
  a lookup returning `Optional<PlanogramTemplate>` for an unknown id. No other template ids.
* `dto/ApplyTemplateRequest` — `templateId` (`@NotBlank`), `requestedBy` (optional).
* `dto/ApplyTemplateResponse` — `projectId`, `templateId`, `taskCount`, `assignments`; each
  assignment is `title`, `department`, `priority`, `assigneeId`. Defensive-copy the list in the
  compact constructor, as `BulkStatusUpdateResponse` does.
* `service/ProjectService.applyTemplate(String id, ApplyTemplateRequest request)` — `@Transactional`.
* `routes/ProjectRoutes` — the `@PostMapping("/{id}/templates")` handler. Mapping and status only.

Build in `shared`:

* `events/TemplateTaskDefinition` — record `(String title, String description, String category, String priority, String assigneeId)`.
* `events/ProgrammeTemplateRequestedEvent` — record
  `(String projectId, String storeId, String templateId, String requestedBy, List<TemplateTaskDefinition> items, Instant occurredAt)`
  implementing `DomainEvent`, `eventType()` returning `PROGRAMME_TEMPLATE_REQUESTED`. Defensive-copy
  `items`, as `Project` does with `members`.

The department resolution rule, in full:

1. Candidates are the programme's own members, resolved through `UserService.findById`.
2. Drop members whose staff record is missing or `active = false`.
3. Keep those whose `profile().department()` equals the item's department, ignoring case. A null
   department never matches.
4. Prefer a candidate holding `ProjectRole.DEPARTMENT_LEAD`; among equals, and when none holds it,
   take the lowest staff id.
5. No candidate → `assigneeId = null`. Not an error.

## Acceptance Criteria (GIVEN/WHEN/THEN)

*The Generator must implement JUnit 5 + MockMvc tests to prove these criteria.*

**Scenario 1: Template applied to a programme with partial department cover**
* **GIVEN** seed programme `project-002`, `PLANNED` in `store-002`, whose only member is `user-005`
  (`STORE_MANAGER`, department `OPERATIONS`)
* **WHEN** `POST /api/projects/project-002/templates` is called with
  `{"templateId":"PLANOGRAM_STANDARD","requestedBy":"user-005"}`
* **THEN** the response is `202 Accepted` with `projectId` `project-002`, `templateId`
  `PLANOGRAM_STANDARD` and `taskCount` `4`
* **AND** `assignments` has four entries in catalogue order, with `assigneeId` `user-005` for the
  two `OPERATIONS` titles and `null` for both `GROCERY` titles
* **AND** exactly one `ProgrammeTemplateRequestedEvent` is published, with `eventType()`
  `PROGRAMME_TEMPLATE_REQUESTED`, `projectId` `project-002`, `storeId` `store-002`, `requestedBy`
  `user-005`, and four `items`
* **AND** every item carries `category` `"PLANOGRAM"` and a `priority` string of `HIGH`, `HIGH`,
  `MEDIUM`, `LOW` in that order
* **AND** `GET /api/tasks?storeId=store-002` still returns only the seed activity `task-004`,
  proving this sprint creates nothing

**Scenario 2: DEPARTMENT_LEAD wins over an ASSOCIATE in the same department**
* **GIVEN** seed programme `project-001`, whose members are `user-002` (`STORE_MANAGER`,
  `OPERATIONS`), `user-003` (`DEPARTMENT_LEAD`, `GROCERY`) and `user-004` (`ASSOCIATE`, `GROCERY`)
* **WHEN** `PLANOGRAM_STANDARD` is applied to `project-001`
* **THEN** both `GROCERY` titles resolve to `user-003`, not to `user-004`, even though `user-004`
  sorts higher by id under the tiebreak
* **AND** both `OPERATIONS` titles resolve to `user-002`
* **AND** the published event carries the same four assignee ids

**Scenario 3: Lowest id breaks a tie when nobody holds DEPARTMENT_LEAD**
* **GIVEN** a programme saved in the test with two `ASSOCIATE` members, both department `GROCERY`,
  ids ordered so that the later-joined member sorts first by id
* **WHEN** `PLANOGRAM_STANDARD` is applied to it
* **THEN** both `GROCERY` titles resolve to the lower staff id
* **AND** the result is identical across two calls, proving the ordering is total rather than
  incidental to map iteration

**Scenario 4: An inactive member is not eligible**
* **GIVEN** a programme whose only `GROCERY` member has `active = false` on their staff record
* **WHEN** `PLANOGRAM_STANDARD` is applied to it
* **THEN** both `GROCERY` titles come back with `assigneeId` `null`
* **AND** the response is still `202`, because uncovered departments are not an error

**Scenario 5: Unknown programme**
* **GIVEN** no programme with id `project-999`
* **WHEN** `POST /api/projects/project-999/templates` is called with a valid body
* **THEN** the response is `404` with error code `PROJECT_NOT_FOUND`
* **AND** no event is published

**Scenario 6: Closed programme is rejected**
* **GIVEN** a programme saved in the test with status `CLOSED`
* **WHEN** the template is applied to it
* **THEN** the response is `409` with error code `PROGRAMME_CLOSED`
* **AND** the message names the programme id
* **AND** no event is published
* **AND** the code is `PROGRAMME_CLOSED`, not `PROGRAMME_ALREADY_CLOSED` — that existing code
  belongs to the close-twice rule and must keep its own meaning

**Scenario 7: Unknown template id**
* **GIVEN** seed programme `project-001`
* **WHEN** the request body is `{"templateId":"PLANOGRAM_DELUXE"}`
* **THEN** the response is `400` with error code `VALIDATION_FAILED`
* **AND** the details name `templateId` and the unknown value
* **AND** no event is published

**Scenario 8: Blank template id**
* **GIVEN** seed programme `project-001`
* **WHEN** the request body is `{"templateId":"  "}`
* **THEN** the response is `400` with error code `VALIDATION_FAILED`, produced by bean validation
  on the DTO rather than by a check in the service
* **AND** no event is published

**Scenario 9: Unknown requestedBy**
* **GIVEN** seed programme `project-001`
* **WHEN** the request body is `{"templateId":"PLANOGRAM_STANDARD","requestedBy":"user-999"}`
* **THEN** the response is `400` with error code `VALIDATION_FAILED`
* **AND** the details name `requestedBy` and the unknown id
* **AND** no event is published

**Scenario 10: Omitted requestedBy falls back to `api`**
* **GIVEN** seed programme `project-001`
* **WHEN** the request body is `{"templateId":"PLANOGRAM_STANDARD"}`
* **THEN** the response is `202`
* **AND** the published event carries `requestedBy` `"api"`, matching
  `RegionalRollupRequestedEvent`'s convention for a request that named nobody

**Scenario 11: The event reaches an after-commit subscriber**
* **GIVEN** the full Spring context and a test subscriber on `ProgrammeTemplateRequestedEvent`
  annotated `@TransactionalEventListener(phase = AFTER_COMMIT)`
* **WHEN** the endpoint is called against `project-002`
* **THEN** the subscriber receives exactly one event, carrying four items
* **AND** this proves `applyTemplate` is `@Transactional` — remove that annotation and the
  subscriber is never called, while every `RecordingEventBus` assertion above still passes

**Scenario 12: A failing subscriber does not fail the caller**
* **GIVEN** a test subscriber that throws on `ProgrammeTemplateRequestedEvent`
* **WHEN** the endpoint is called
* **THEN** the response is still `202`
* **AND** the programme is unchanged, because the endpoint writes nothing to it

## Architectural Guardrails

* **`programmes` must not import anything from `activities`.** Not `TaskPriority`, not
  `TaskCategory`, not `TaskService`. Sprint 2 makes `activities` a subscriber, and an import in this
  direction plus that one is the cycle `ModuleBoundaryTest` rule 2 fails on. The catalogue's
  priority and category are `String` constants for exactly this reason.
* **`applyTemplate` must be `@Transactional`.** Without it there is no commit, so the Sprint 2
  listener never runs and no activity is created. `RecordingEventBus` records at publish time and
  cannot see the difference — Scenario 11 is the test that can.
* **`shared` must not import any module.** `TemplateTaskDefinition` and
  `ProgrammeTemplateRequestedEvent` carry `String` category and priority. Typing either on
  `TaskCategory` trips `ModuleBoundaryTest` rule 3b.
* **Publish through `EventBus`.** `eventBus.publish(...)`, never `ApplicationEventPublisher`.
* **No logic in `ProjectRoutes`.** Catalogue expansion, department matching, the
  `DEPARTMENT_LEAD` preference and the tiebreak all belong in `ProjectService`. The handler maps the
  body, calls the service and sets `202`. Checking a status or picking an assignee in the controller
  is an automatic FAIL under `evaluation-criteria` §3.
* **Staff stays read-only.** Member departments come from `UserService`; never from
  `UserRepository`, which `ModuleBoundaryTest` rule 1 forbids outside `staff`.
* **Errors are `AppError` subtypes only.** `PROGRAMME_CLOSED` is a `ConflictError`; the two
  validation failures are `ValidationError`; the missing programme reuses
  `NotFoundError.of("Project", id)`. No raw exceptions anywhere, per rule 6.
* **The response must not claim rows exist.** `taskCount` is what the template resolved to, not a
  count of created activities. Say so in the record's javadoc so the next reader does not
  "fix" the status to `201`.
