# Specification: Planogram Task Template

## Feature Summary

Clone a standard set of `PLANOGRAM` activities into an existing store programme with one call.
The template definition supplies each activity's title, description and default priority, and names
the **department** the activity belongs to. The department is resolved to a concrete assignee from
the programme's own membership crossed with the staff department on each member's profile.

The caller names a template id. The programme is validated, the template is expanded, department
assignments are resolved, and the resulting activity definitions are handed to the `activities`
module over the event bus. `activities` creates the `Task` rows.

---

## Two Decisions The Request Left Open

Both are called out here because they change the shape of the API. Say so at approval time if
either should go the other way.

### 1. The route is `POST /api/projects/{id}/templates`, not `/api/programmes/{id}/templates`

The request asked for `/api/programmes/:id/templates`. `app-context` §3 is explicit that the
programmes module is served under `/api/projects` and that the module/URL mismatch is deliberate:
*"The module names and the URL names differ. Don't 'fix' it."* Adding a second route base for the
same module would be the fix it warns against, so the endpoint is nested under the existing base:

```
POST /api/projects/{id}/templates
```

### 2. The endpoint answers `202 Accepted`, and the body does not list the created activities

Tasks are owned by `activities`. Programmes creating them directly is a cross-module write, banned
by `architecture-principles` §3 — *"Changing Data: Publish an event to the Event Bus. Direct
cross-module writes are forbidden."* So `programmes` publishes and `activities` writes, which means
the rows do not exist until the request's transaction has committed and the after-commit listener
has run. There are no task ids to put in the response body.

The response therefore acknowledges the request and reports how many activities the expansion
resolved to, and the caller reads the result back with the existing
`GET /api/tasks?storeId={storeId}`.

The synchronous alternative — hanging the route off `activities` as
`POST /api/tasks/from-template` so one module both validates and writes — would return `201` with
the full list, at the cost of `activities` reading programme membership. It is a legitimate design;
it is just not the one the boundary rules point at.

---

## Module & Database Impact

| Module | Layer | Change |
| --- | --- | --- |
| `programmes` | `routes` | `ProjectRoutes` gains `POST /api/projects/{id}/templates` |
| `programmes` | `service` | `ProjectService.applyTemplate(String, ApplyTemplateRequest)` — validates, expands, resolves, publishes |
| `programmes` | `domain` | `PlanogramTemplate` (the catalogue), `PlanogramTemplateItem` (one line of it) |
| `programmes` | `dto` | `ApplyTemplateRequest`, `ApplyTemplateResponse` |
| `programmes` | `repository` | none |
| `activities` | `listener` | **new package.** `TaskTemplateEventListener` creates the activities |
| `activities` | `service` | `TaskService.createFromTemplate(...)` — the write the listener calls |
| `shared` | `events` | `ProgrammeTemplateRequestedEvent`, `TemplateTaskDefinition` |
| `staff` | — | read-only, through the existing `UserService`. No change |
| `alerts`, `reports` | — | untouched |

**New JPA entities: none.** The template catalogue is code, not data — a fixed set of constants in
`programmes.domain`, not a `template_definitions` table. It is versioned with the code that reads
it, it needs no migration to change, and there is no endpoint to manage it. The cloned activities
land in the existing `tasks` table, owned by `activities`.

### Where the template definition lives, and why it is `programmes`

The catalogue names a department and a default priority per line. Resolving a department to a person
needs programme membership (`ProjectMember`) crossed with staff department
(`User.profile().department()`). `programmes` already holds the first and already reads the second
through `UserService`. Putting the catalogue in `activities` instead would force `activities` to
read programme membership, and since `programmes` would still need to know a template id was valid,
the two modules would end up importing each other — the cycle `ModuleBoundaryTest` rule 2 exists to
catch.

So `programmes` owns the definition and does all the resolving. `activities` receives a list of
fully-resolved activity definitions and knows nothing about templates, departments or programme
membership. Neither module imports the other, in either direction.

### The catalogue: `PLANOGRAM_STANDARD`

One template id ships. It expands, in this order, to four activities:

| # | Title | Department | Default priority |
| --- | --- | --- | --- |
| 1 | `Reset entrance promotional bay` | `OPERATIONS` | `HIGH` |
| 2 | `Reset grocery aisle planograms` | `GROCERY` | `HIGH` |
| 3 | `Verify shelf-edge labelling` | `GROCERY` | `MEDIUM` |
| 4 | `Photograph completed bays for compliance` | `OPERATIONS` | `LOW` |

Every line is `category = PLANOGRAM` and is created `status = TODO` with `dueAt = null`.
`OPERATIONS` and `GROCERY` are the two department values present in `data.sql`; priorities are
`TaskPriority` values from `app-context` §4.

### Department assignment rule

For each template line, the assignee is chosen from the programme's members only:

1. Members of this programme whose staff profile department equals the line's department,
   case-insensitively.
2. Of those, prefer the member holding `ProjectRole.DEPARTMENT_LEAD`; otherwise take the lowest
   staff id, so the choice is total and not a coin toss.
3. If no member matches, the line is created **unassigned** (`assigneeId = null`).

Step 3 is not an error. `Task.assigneeId` is nullable by design and a programme with no cover for a
department still needs the work raising. It is why `project-002` — a single `STORE_MANAGER` in
`OPERATIONS` — is the interesting fixture: two lines resolve to `user-005` and two, the `GROCERY`
ones, come out unassigned.

Staff who have left (`active = false`) are not eligible. `UserService.findById` returns leavers, so
the filter is the caller's job.

---

## Event Bus Triggers

One new event. The catalogue in `app-context` §5 grows to five, following the precedent
`RegionalRollupRequestedEvent` set.

| | |
| --- | --- |
| **Event** | `ProgrammeTemplateRequestedEvent` |
| **`eventType()`** | `PROGRAMME_TEMPLATE_REQUESTED` |
| **Published by** | `programmes` → `ProjectService.applyTemplate` |
| **Payload** | `projectId`, `storeId`, `templateId`, `requestedBy`, `items` (`List<TemplateTaskDefinition>`), `occurredAt` |
| **Listened to by** | `activities` → `TaskTemplateEventListener` |
| **Listener does** | Creates one `Task` per item, `status = TODO`, skipping titles already on the programme |

`TemplateTaskDefinition` is a `shared/events` record of plain `String` fields:

```
TemplateTaskDefinition(String title, String description, String category, String priority, String assigneeId)
```

`category` and `priority` travel as `String`, per `app-context` §5 — a record typed on
`TaskCategory` would drag `activities.domain` into `shared` and trip `ModuleBoundaryTest` rule 3b.
`assigneeId` is nullable, carrying the unassigned outcome of the department rule.

Carrying resolved items rather than the template id is the point of the design. It is what lets
`activities` create the activities without reading programme membership, and what keeps the
dependency graph free of the `programmes`↔`activities` cycle.

### Wiring requirements

Three, each of which fails silently if missed, and each of which is a hard gate in
`evaluation-criteria` §3:

* `ProjectService.applyTemplate` must be `@Transactional`. Without a transaction to commit, Spring
  skips the after-commit callback and no activity is ever created. A `RecordingEventBus` test cannot
  see this, because it records at publish time.
* `TaskTemplateEventListener` must carry `@TransactionalEventListener(phase = AFTER_COMMIT)`.
* The same method must carry `@Transactional(propagation = REQUIRES_NEW)`. The publishing
  transaction has already committed by the time the listener runs, so a write joining it is never
  flushed — the listener would run, the log line would print, and no rows would appear.

---

## Error Mapping

| # | Rule | Error | HTTP | Code |
| --- | --- | --- | --- | --- |
| 1 | No programme has that id | `NotFoundError.of("Project", id)` | 404 | `PROJECT_NOT_FOUND` |
| 2 | Programme is `CLOSED` | `ConflictError` | 409 | `PROGRAMME_CLOSED` |
| 3 | `templateId` blank or missing | `ValidationError` (bean validation, `@NotBlank`) | 400 | `VALIDATION_FAILED` |
| 4 | `templateId` names no known template | `ValidationError` | 400 | `VALIDATION_FAILED` |
| 5 | `requestedBy` given but not a known staff member | `ValidationError` | 400 | `VALIDATION_FAILED` |

`PROGRAMME_CLOSED` (rule 2) is the one new code. It is deliberately not the existing
`PROGRAMME_ALREADY_CLOSED`, which means "you tried to close a closed programme" — a different rule
with a different fix, and reusing it would tell the caller the wrong thing.

Two outcomes that are explicitly **not** errors:

* No member covers a template line's department → that line is created unassigned (rule 3 of the
  assignment rule).
* A repeat call → already-present titles are skipped. See below.

### Repeat calls

The listener skips any item whose title already exists on that programme, comparing against
`taskRepository.findByProjectId(projectId)`. A second identical call creates nothing.

The check has to live in `activities` because that is the module that can see the `tasks` table;
`programmes` cannot know what has already been cloned without reading another module's store. So
the endpoint answers `202` with the full expansion count on both calls — the count is what the
template resolved to, not a promise about what was written. That is stated on
`ApplyTemplateResponse.taskCount`.

---

## Request and Response

```
POST /api/projects/project-002/templates
Content-Type: application/json

{ "templateId": "PLANOGRAM_STANDARD", "requestedBy": "user-005" }
```

```
202 Accepted

{
  "projectId": "project-002",
  "templateId": "PLANOGRAM_STANDARD",
  "taskCount": 4,
  "assignments": [
    { "title": "Reset entrance promotional bay",              "department": "OPERATIONS", "priority": "HIGH",   "assigneeId": "user-005" },
    { "title": "Reset grocery aisle planograms",              "department": "GROCERY",    "priority": "HIGH",   "assigneeId": null },
    { "title": "Verify shelf-edge labelling",                 "department": "GROCERY",    "priority": "MEDIUM", "assigneeId": null },
    { "title": "Photograph completed bays for compliance",    "department": "OPERATIONS", "priority": "LOW",    "assigneeId": null }
  ]
}
```

`requestedBy` is optional and falls back to `api`, matching `RegionalRollupRequestedEvent`.

The `assignments` echo is what makes a `202` usable: the caller learns immediately which lines
found an owner and which did not, without waiting for the rows to appear. Note line 4 — `user-005`
is `OPERATIONS` but the preference rule picks one member per department, and the lowest-id tiebreak
gives the same person both `OPERATIONS` lines. That is intended; the echo makes it visible.

---

## Sprint Breakdown

Split on the module boundary, per `sprint-decomposition` §2 — module A firing the event is one
sprint, module B's listener is the next.

| Sprint | Module | Goal |
| --- | --- | --- |
| 1 | `programmes` + `shared` | The endpoint: validate, expand the catalogue, resolve department assignments, publish `PROGRAMME_TEMPLATE_REQUESTED`, answer `202`. No activity is created yet. |
| 2 | `activities` | `TaskTemplateEventListener` turns the event into `Task` rows, skipping titles already on the programme. Includes the end-to-end delivery test. |

Sprint 1 is independently testable: it asserts the published event with `RecordingEventBus`, which
is exactly the seam between the two sprints. Sprint 2 is independently testable by publishing the
event directly, without going through the endpoint at all.

STATUS: AWAITING APPROVAL
