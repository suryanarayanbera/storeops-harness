# Skill: App Context

**Goal:** This is your map of the StoreOps application. It tells you what already exists and what
things are called, so you do not invent a module, an enum value, a route or an event that isn't here.

If a name you want to write is not in this file, it does not exist. Ask for it in the spec, or use
the name that is here.

## 1. The Basics
* StoreOps is a retail store operations REST API. Java 25, Spring Boot, Maven.
* One command runs everything: `./mvnw clean test`. That covers Checkstyle, SpotBugs, the ArchUnit
  boundary tests, JUnit and the JaCoCo coverage gate.
* Storage is an in-memory H2 database. It is rebuilt from `src/main/resources/data.sql` on every
  restart, so seed data is always the same and tests can rely on it.
* All packages sit under `com.cognizant.storeops`.

## 2. The Five Modules
Every piece of feature code belongs to exactly one of these. `shared` is not a module, it is the
plumbing they all use.

| Module | What it looks after | Main entity |
| --- | --- | --- |
| `activities` | Operational activities: restocking, planogram resets, audits, compliance checks | `Task` |
| `programmes` | Store programmes and who is on them | `Project`, `ProjectMember` |
| `staff` | Store staff and their profiles. Read-only for everyone else | `User` |
| `alerts` | In-app alerts raised by operational events | `Notification` |
| `reports` | Store and regional summaries. Reads from the others, never writes to them | `Report` |
| `shared` | `AppError` hierarchy, `EventBus`, the event records | no entity |

Inside a module, stick to these packages: `routes`, `service`, `repository`, `domain`, `dto`, and
`listener` where the module consumes events. Nothing else. There is no `utils` package and we are not
adding one.

## 3. The API
Nine endpoints exist today. Route bases matter, so use the real ones.

| Method | Path | Module | What it does |
| --- | --- | --- | --- |
| GET | `/api/tasks` | activities | List activities, filterable by store and status |
| POST | `/api/tasks` | activities | Create an activity |
| GET | `/api/tasks/{id}` | activities | Fetch one activity |
| PATCH | `/api/tasks/{id}` | activities | Partial update. A status change publishes an event |
| GET | `/api/projects` | programmes | List programmes |
| POST | `/api/projects` | programmes | Create a programme |
| GET | `/api/users/{id}` | staff | Fetch one staff member |
| GET | `/api/notifications` | alerts | List alerts for a recipient |
| GET | `/api/reports/store/{storeId}` | reports | Store summary, built on demand |

Note the paths use `/api/tasks`, `/api/projects` and `/api/users`, not `/api/activities`,
`/api/programmes` or `/api/staff`. The module names and the URL names differ. Don't "fix" it.

## 4. The Words We Use
These are the only valid values. Copy them exactly, including the underscores.

| Enum | Values |
| --- | --- |
| `TaskStatus` | `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED` |
| `TaskPriority` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` |
| `TaskCategory` | `RESTOCKING`, `PLANOGRAM`, `AUDIT`, `COMPLIANCE`, `GENERAL` |
| `ProjectStatus` | `PLANNED`, `ACTIVE`, `CLOSED` |
| `ProjectRole` | `STORE_MANAGER`, `DEPARTMENT_LEAD`, `ASSOCIATE` |
| `StaffRole` | `REGIONAL_MANAGER`, `STORE_MANAGER`, `DEPARTMENT_LEAD`, `ASSOCIATE` |
| `AlertType` | `INVENTORY`, `SLA_BREACH`, `SHIFT_HANDOVER`, `ESCALATION` |
| `NotificationChannel` | `IN_APP`, `EMAIL` |
| `NotificationStatus` | `PENDING`, `SENT`, `READ`, `FAILED` |
| `ReportType` | `STORE_SUMMARY`, `REGIONAL_ROLLUP`, `DEPARTMENT_PERFORMANCE` |
| `ReportStatus` | `PENDING`, `READY`, `FAILED` |

Two domain rules that go with them:
* `DONE` is the end of the line for a task. Any transition out of `DONE` is rejected with
  `TASK_TRANSITION_NOT_ALLOWED` (409).
* A programme that is already `CLOSED` cannot be closed again. That is
  `PROGRAMME_ALREADY_CLOSED` (409).

## 5. The Event Catalogue
Three events exist. This is the whole list. Modules talk to each other through these, not by
importing each other's services.

| Event | `eventType()` | Published by | Payload | Listened to by | What the listener does |
| --- | --- | --- | --- | --- | --- |
| `TaskStatusChangedEvent` | `TASK_STATUS_CHANGED` | `activities` → `TaskService` | `taskId`, `storeId`, `previousStatus`, `newStatus`, `priority`, `assigneeId`, `occurredAt` | `alerts` → `AlertEventListener` | Raises an `ESCALATION` alert when a task becomes `BLOCKED` |
| `TaskOverdueEvent` | `TASK_OVERDUE` | `activities` | `taskId`, `storeId`, `priority`, `assigneeId`, `dueAt`, `occurredAt` | `alerts` → `AlertEventListener` | Raises an `SLA_BREACH` alert, but only for `HIGH` and `CRITICAL` |
| `ProgrammeClosedEvent` | `PROGRAMME_CLOSED` | `programmes` → `ProjectService` | `projectId`, `storeId`, `closedByUserId`, `occurredAt` | `reports` → `ReportEventListener` | Queues a `STORE_SUMMARY` report |

Things to know about events:
* Publish with `eventBus.publish(...)`. The `EventBus` interface lives in `shared/events`. Never
  inject `ApplicationEventPublisher` directly.
* Enum values travel as `String` in the payload, not as the enum type. If an event carried
  `TaskStatus`, the alerts module would have to import `activities.domain`, and the boundary would be
  broken by the payload itself.
* There is no `subscribe` method. A listener opts in with
  `@TransactionalEventListener(phase = AFTER_COMMIT)` plus
  `@Transactional(propagation = REQUIRES_NEW)`. The publisher never knows who is listening.
* Delivery is after commit, so a rolled back change never fires a side effect.

## 6. Error Codes
Everything thrown from a service is an `AppError` subtype from `shared/error`. Never a raw
`RuntimeException`, never `IllegalArgumentException`.

| Subtype | HTTP | Code |
| --- | --- | --- |
| `ValidationError` | 400 | `VALIDATION_FAILED` |
| `UnauthorizedError` | 401 | `UNAUTHORIZED` |
| `ForbiddenError` | 403 | `FORBIDDEN` |
| `NotFoundError` | 404 | `<RESOURCE>_NOT_FOUND`, built by `NotFoundError.of("task", id)` |
| `ConflictError` | 409 | Caller supplies it, e.g. `TASK_TRANSITION_NOT_ALLOWED` |
| `InternalError` | 500 | `INTERNAL_ERROR` |

`GlobalExceptionHandler` turns any of them into an `ErrorResponse`. A new business rule needs a code
string; if the Planner did not name one, that is a gap in the contract, not a licence to invent one
quietly.

## 7. Seed Data
Use these ids in test scenarios. Do not make up new ones unless the test inserts them itself.

**Staff** (`user-001` to `user-005`)

| Id | Name | Role | Store |
| --- | --- | --- | --- |
| `user-001` | Rita Shaw | `REGIONAL_MANAGER` | `store-001` |
| `user-002` | Sam Okafor | `STORE_MANAGER` | `store-001` |
| `user-003` | Lena Brandt | `DEPARTMENT_LEAD` | `store-001` |
| `user-004` | Tom Reilly | `ASSOCIATE` | `store-001` |
| `user-005` | Ana Silva | `STORE_MANAGER` | `store-002` |

**Activities**

| Id | Status | Priority | Category | Store | Notes |
| --- | --- | --- | --- | --- | --- |
| `task-001` | `TODO` | `HIGH` | `RESTOCKING` | `store-001` | past due, so it counts as overdue |
| `task-002` | `IN_PROGRESS` | `MEDIUM` | `PLANOGRAM` | `store-001` | past due, so it counts as overdue |
| `task-003` | `DONE` | `CRITICAL` | `COMPLIANCE` | `store-001` | terminal, good for rejection tests |
| `task-004` | `BLOCKED` | `LOW` | `AUDIT` | `store-002` | no due date |

**Programmes:** `project-001` is `ACTIVE` in `store-001` with three members. `project-002` is
`PLANNED` in `store-002`.

**Alerts:** `notification-001`, a `SHIFT_HANDOVER` alert to `user-003`.

**Reports:** empty on purpose. Rows only appear once a report is asked for or a programme closes.

Stores are `store-001` and `store-002`, both in `region-north`. Timestamps in the seed are fixed
dates in January 2026, not relative to now, so the store summary and the curl examples give the same
answer every run.

## 8. Quick Sanity Checks
Before you hand work over, ask yourself:
* Is every enum value, route path and id I wrote listed above?
* Did anything cross a module boundary? If so, is it an event from section 5, and did I use an
  existing one rather than adding a fourth?
* Does every failure path throw an `AppError` with a code from section 6?
