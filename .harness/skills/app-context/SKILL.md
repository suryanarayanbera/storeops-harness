# StoreOps App Context

**Purpose:** Shared orientation for every agent (Planner, Generator, Evaluator, Monitor). It is the
map — what exists, what it is called, where it lives. It carries no rules: the constraints are in
[architecture-principles](../architecture-principles/SKILL.md) and
[coding-conventions](../coding-conventions/SKILL.md). Read this first so you never invent a module,
enum value, or package that the codebase does not already have.

## 1. The application

StoreOps is a REST API for retail store operations: store teams open programmes, assign and track
operational activities across departments, coordinate staff, and pull performance reports by store
and region. Capstone reference codebase — **not production**. Services are deliberately thin; the
architecture and its enforcement are what is complete.

Stack: **Java 25 · Spring Boot 3.5.16 · Spring Data JPA + H2 (in-memory) · JUnit 5 + MockMvc ·
Checkstyle + SpotBugs + ArchUnit**.

The single gate — lint, static analysis and all tests in one command, exit code `0` or nothing:

```bash
./mvnw clean test
```

Checkstyle binds to `validate`, SpotBugs to `test-compile`, so `mvn test` runs everything. Run the
app with `./mvnw spring-boot:run` on `http://localhost:8080`.

## 2. The five modules

Base package `com.cognizant.storeops`. Every module owns its own JPA entities and tables.

| Module | Owns | Key types | Endpoints |
| --- | --- | --- | --- |
| `activities` | Operational tasks — restocking, planogram resets, audits, compliance | `Task`, `TaskStatus`, `TaskPriority`, `TaskCategory` | `GET/POST /api/tasks`, `GET/PATCH /api/tasks/{id}` |
| `programmes` | Store programmes and their staff membership | `Project`, `ProjectMember`, `ProjectRole`, `ProjectStatus` | `GET/POST /api/projects` |
| `staff` | Store staff, profiles, roles. Read-only to other modules | `User`, `UserProfile`, `StaffRole`, `AuthToken` | `GET /api/users/{id}` |
| `alerts` | In-app alerts raised by operational events | `Notification`, `AlertType`, `NotificationChannel`, `NotificationStatus` | `GET /api/notifications` |
| `reports` | Store and regional aggregation. Writes nothing outside itself | `Report`, `ReportType`, `ReportStatus` | `GET /api/reports/store/{storeId}` |

Plus `shared` — `shared.error` (the `AppError` hierarchy, `GlobalExceptionHandler`) and
`shared.events` (`EventBus`, `DomainEvent` and the event records).

## 3. Package layout inside a module

```
routes/       @RestController — HTTP mapping, @Valid, response shaping
service/      business rules; the only cross-module entry point
repository/   XRepository interface + XEntity + JpaXRepository adapter
domain/ dto/  immutable records, no JPA annotations
listener/     alerts and reports only — where a module subscribes to others' events
```

Naming is positional, not decorative: `TaskRepository` (interface), `TaskEntity` (JPA),
`JpaTaskRepository` (adapter). Adding persistence to a module means those three files, nothing above
them. Tests mirror the same tree under `src/test/java`, with in-memory fakes in
`com.cognizant.storeops.support`.

## 4. Domain vocabulary — exact values

Use these literally; do not extend an enum without a sprint contract that says to.

| Enum | Values |
| --- | --- |
| `TaskStatus` | `TODO`, `IN_PROGRESS`, `DONE`, `BLOCKED` |
| `TaskPriority` | `LOW`, `MEDIUM`, `HIGH`, `CRITICAL` (`HIGH`/`CRITICAL` are SLA-tracked) |
| `TaskCategory` | `RESTOCKING`, `PLANOGRAM`, `AUDIT`, `COMPLIANCE`, `GENERAL` |
| `ProjectStatus` | `PLANNED`, `ACTIVE`, `CLOSED` |
| `ProjectRole` | `STORE_MANAGER`, `DEPARTMENT_LEAD`, `ASSOCIATE` |
| `StaffRole` | `REGIONAL_MANAGER`, `STORE_MANAGER`, `DEPARTMENT_LEAD`, `ASSOCIATE` |
| `AlertType` | `INVENTORY`, `SLA_BREACH`, `SHIFT_HANDOVER`, `ESCALATION` |
| `NotificationChannel` / `NotificationStatus` | `IN_APP`, `EMAIL` / `PENDING`, `SENT`, `READ`, `FAILED` |
| `ReportType` / `ReportStatus` | `STORE_SUMMARY`, `REGIONAL_ROLLUP`, `DEPARTMENT_PERFORMANCE` / `PENDING`, `READY`, `FAILED` |

Errors: `NotFoundError` 404 · `ValidationError` 400 (+ `details[]`) · `ConflictError` 409 ·
`UnauthorizedError` 401 · `ForbiddenError` 403 · `InternalError` 500 — all carrying
`(code, message, statusCode)` and rendered in one response shape.

## 5. Event catalogue

Three events exist today, all records in `shared.events` carrying enum values as `String` so a
payload cannot drag one module's types into another's subscribers:

| Publisher | Event | Subscriber → effect |
| --- | --- | --- |
| `TaskService.update()` | `TaskStatusChangedEvent` | `AlertEventListener` → `ESCALATION` notification |
| `TaskService.publishOverdueBreaches()` | `TaskOverdueEvent` | `AlertEventListener` → `SLA_BREACH` notification |
| `ProjectService.close()` | `ProgrammeClosedEvent` | `ReportEventListener` → `STORE_SUMMARY` report |

New cross-module side effects follow the same shape: a new record in `shared.events`, published via
`EventBus.publish(...)`, consumed by a `listener/` class in the reacting module.

## 6. Runtime facts

H2 console at `http://localhost:8080/h2-console`, JDBC URL exactly `jdbc:h2:mem:storeops`, user
`storeops`, blank password. Schema is generated from the `@Entity` classes (`ddl-auto: create-drop`)
and seeded from `src/main/resources/data.sql`, so **every restart resets to seed state**: users
`user-001`–`005`, projects `project-001`/`002`, tasks `task-001`–`004`, fixed timestamps in
January 2026. Cross-module references are ids without foreign keys — renaming a seed id means
updating every row that names it.

## 7. Known gaps — candidate feature work

Stubs, not oversights. A harness sprint may be asked to fill any of these:

- `PATCH /api/activities/bulk-status` — shift handover bulk update
- `GET /api/reports/region/{id}` — regional rollup
- `POST /api/programmes/{id}/templates` — planogram task templates
- SLA escalation chain — `publishOverdueBreaches()` exists but nothing schedules it
- `AuthToken` is modelled; no authentication filter is wired
- `ReportService.markReady()` has no generation pipeline behind it

Note the naming split: the module is `activities` but its existing route base is `/api/tasks`.
Confirm the intended path against the sprint contract rather than guessing.
