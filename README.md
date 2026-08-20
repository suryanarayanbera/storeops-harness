# StoreOps API

Retail store operations management REST API. Capstone reference codebase for the AI-Native Tech
Architect programme — **not for production**. Storage is an in-memory H2 database and services are
stubs; what is complete and enforced is the architecture.

Java 25 (LTS) · Spring Boot 3.5.16 · Spring Data JPA + H2 (in-memory) · JUnit 5 + MockMvc ·
Checkstyle + SpotBugs + ArchUnit

## Quick start

```bash
mvn clean test          # the full gate: lint + static analysis + 93 tests
./mvnw spring-boot:run  # http://localhost:8080
curl http://localhost:8080/api/tasks
```

## Browsing the data

The H2 console is enabled at **http://localhost:8080/h2-console** while the app is running:

| Field | Value |
| --- | --- |
| JDBC URL | `jdbc:h2:mem:storeops` |
| User | `storeops` |
| Password | *(blank)* |

Then `SELECT * FROM TASKS;` and so on. Six tables: `TASKS`, `PROJECTS`, `PROJECT_MEMBERS`,
`USERS`, `NOTIFICATIONS`, `REPORTS`.

The database lives in the JVM, so **every restart resets to the seed state** in
[src/main/resources/data.sql](src/main/resources/data.sql). Schema is generated from the
`@Entity` classes (`ddl-auto: create-drop`); `data.sql` runs after it thanks to
`defer-datasource-initialization: true`.

`mvn test` is the single auto-check command. Checkstyle binds to `validate` and SpotBugs to
`test-compile`, so lint and static analysis run inside it — a green `mvn test` means everything
passed.

Compiled and run on Java 25 (`maven-compiler-plugin <release>25</release>`, class file major
version 69). Java 25 is an LTS release and satisfies the programme's "Java 17+" requirement.

## Endpoints

| # | Method | Path | Module |
| --- | --- | --- | --- |
| 1 | GET | `/api/tasks` | activities — filters: `status`, `priority`, `category`, `storeId` |
| 2 | POST | `/api/tasks` | activities — 201 + `Location` |
| 3 | GET | `/api/tasks/{id}` | activities |
| 4 | PATCH | `/api/tasks/{id}` | activities — publishes `TaskStatusChangedEvent` |
| 5 | GET | `/api/projects` | programmes — filters: `status`, `storeId` |
| 6 | POST | `/api/projects` | programmes — 201 + `Location` |
| 7 | GET | `/api/users/{id}` | staff |
| 8 | GET | `/api/notifications` | alerts — filters: `recipientId`, `status` |
| 9 | GET | `/api/reports/store/{storeId}` | reports |

Seed data is loaded at startup: `store-001`/`store-002`, `user-001`..`user-005`,
`task-001`..`task-004`, `project-001`/`project-002`.

## Architecture

Five domain modules — `activities`, `programmes`, `staff`, `alerts`, `reports` — plus `shared`.
Each module owns four layers:

```
routes/       HTTP mapping, bean validation, response shaping. No business logic.
service/      Business rules. The only cross-module entry point.
repository/   Data access. No HTTP, no events, no other module.
domain/ dto/  Immutable records.
```

Each module's `repository/` package holds three things: the `XRepository` interface everything else
depends on, an `XEntity` JPA mapping, and a `JpaXRepository` adapter that converts between them.
The domain records carry **no JPA annotations** — persistence concerns stop at the repository
boundary, so swapping H2 for anything else touches only those three files per module.

### The five rules, and what enforces them

| Rule | Enforced by |
| --- | --- |
| No module imports another module's repository | `ModuleBoundaryTest` rules 1, 1b |
| No circular module dependencies | rule 2 (ArchUnit slices) |
| Cross-module side effects via event bus only | rule 3, 3b |
| `reports` is read-only toward other modules | rules 4, 4b |
| Routes → Service → Repository, no skipping | rules 5, 5b, 5c |
| No raw `Error`/`RuntimeException` throws | rule 6, 6b + Checkstyle `IllegalThrows`, `NoRawErrorThrows` |

`src/test/java/.../architecture/ModuleBoundaryTest.java` is the automated dependency analyser the
programme requires ("depcruiser or equivalent"). It runs in `mvn test`, so a boundary violation
fails the build.

### Event bus

`activities` and `programmes` publish; `alerts` and `reports` subscribe. Publishers import only
`shared.events` — never a consuming module. Events carry enum values as `String` for exactly that
reason: an event holding `TaskStatus` would drag `activities` into every subscriber.

```
TaskService.update()      → TaskStatusChangedEvent → AlertEventListener  → ESCALATION alert
TaskService.publishOverdueBreaches() → TaskOverdueEvent → AlertEventListener → SLA_BREACH alert
ProjectService.close()    → ProgrammeClosedEvent  → ReportEventListener → PENDING STORE_SUMMARY
```

Observable end to end:

```bash
ID=$(curl -s -X POST localhost:8080/api/tasks -H 'Content-Type: application/json' \
  -d '{"title":"Planogram bay 7 reset","storeId":"store-001","priority":"HIGH","assigneeId":"user-004"}' \
  | sed -E 's/.*"id":"([^"]+)".*/\1/')
curl -s -X PATCH localhost:8080/api/tasks/$ID -H 'Content-Type: application/json' -d '{"status":"BLOCKED"}'
curl -s 'localhost:8080/api/notifications?recipientId=user-004'   # ESCALATION alert appeared
```

### Error contract

Every error is an `AppError` subtype carrying `(code, message, statusCode)`; every error response
has one shape. Framework-level failures (unmapped path, wrong method) are normalised into it too,
so a client never sees a Spring default body.

```json
{"code":"TASK_NOT_FOUND","message":"Task 'x' was not found","statusCode":404,
 "path":"/api/tasks/x","timestamp":"2026-08-20T14:26:43.505Z"}
```

`NotFoundError` 404 · `ValidationError` 400 (+ `details[]`) · `ConflictError` 409 ·
`UnauthorizedError` 401 · `ForbiddenError` 403 · `InternalError` 500

## Deliberate gaps

Stubs, not oversights — the harness demonstration run implements these:

- `PATCH /api/activities/bulk-status` — shift handover bulk update
- `GET /api/reports/region/{id}` — regional rollup
- `POST /api/programmes/{id}/templates` — planogram task templates
- SLA escalation chain: `publishOverdueBreaches()` exists but nothing schedules it
- `AuthToken` is modelled; no authentication filter is wired
- `ReportService.markReady()` has no generation pipeline behind it
