# StoreOps API

Retail store operations management REST API. Capstone reference codebase — **not for production**.
Storage is in-memory H2 and services are stubs; the architecture is what's complete and enforced.

Java 25 (LTS) · Spring Boot 3.5.16 · Spring Data JPA + H2 · JUnit 5 + MockMvc · Checkstyle +
SpotBugs + ArchUnit + JaCoCo

## Quick start

```bash
./mvnw clean test       # the single gate: lint + static analysis + coverage + 96 tests
./mvnw spring-boot:run  # http://localhost:8080
curl http://localhost:8080/api/tasks
```

Checkstyle binds to `validate`, SpotBugs to `test-compile` and JaCoCo's `check` to `test`, so
`mvn test` runs everything and a green build means all gates passed. All three fail the build rather
than warn. Coverage thresholds are a ratchet at the current baseline — bundle line 85% / branch 60%,
and line 70% / branch 50% per class on services and listeners, where the business rules live.
Suppressions live in
[checkstyle-suppressions.xml](checkstyle-suppressions.xml) and
[spotbugs-exclude.xml](spotbugs-exclude.xml), scoped per rule and package — never blanket.

## Endpoints

| # | Method | Path | Notes |
| --- | --- | --- | --- |
| 1 | GET | `/api/tasks` | filters: `status`, `priority`, `category`, `storeId` |
| 2 | POST | `/api/tasks` | 201 + `Location` |
| 3 | GET | `/api/tasks/{id}` | |
| 4 | PATCH | `/api/tasks/{id}` | status change publishes `TaskStatusChangedEvent` |
| 5 | GET | `/api/projects` | filters: `status`, `storeId` |
| 6 | POST | `/api/projects` | 201 + `Location` |
| 7 | GET | `/api/users/{id}` | |
| 8 | GET | `/api/notifications` | filters: `recipientId`, `status` |
| 9 | GET | `/api/reports/store/{storeId}` | on-demand aggregation |

## Data

H2 console at **http://localhost:8080/h2-console** — JDBC URL `jdbc:h2:mem:storeops`, user
`storeops`, blank password. The URL must be exact; the console defaults to `jdbc:h2:~/test`, which
silently opens a different empty database.

Tables: `TASKS`, `PROJECTS`, `PROJECT_MEMBERS`, `USERS`, `NOTIFICATIONS`, `REPORTS`. Schema comes
from the `@Entity` classes (`ddl-auto: create-drop`), seeded from
[data.sql](src/main/resources/data.sql) — so **every restart resets to seed state**
(`task-001`–`004`, `project-001`/`002`, `user-001`–`005`).
[H2SchemaTest](src/test/java/com/cognizant/storeops/H2SchemaTest.java) guards that hand-written SQL
against entity renames.

## Architecture

Five domain modules — `activities`, `programmes`, `staff`, `alerts`, `reports` — plus `shared`.

```
routes/       HTTP mapping, validation, response shaping. No business logic.
service/      Business rules. The only cross-module entry point.
repository/   Data access: XRepository interface + XEntity + JpaXRepository adapter.
domain/ dto/  Immutable records — no JPA annotations.
listener/     alerts and reports only: where a module subscribes to others' events.
```

Persistence stops at the repository boundary, so replacing H2 touches three files per module and
nothing above them.

| Rule | Enforced by |
| --- | --- |
| No cross-module repository imports | `ModuleBoundaryTest` 1, 1b |
| No circular module dependencies | 2 (ArchUnit slices) |
| Cross-module side effects via event bus only | 3, 3b |
| `reports` is read-only toward other modules | 4, 4b |
| Routes → Service → Repository, no skipping | 5, 5b, 5c |
| No raw `Error`/`RuntimeException` throws | 6, 6b + Checkstyle `IllegalThrows`, `NoRawErrorThrows` |

[ModuleBoundaryTest](src/test/java/com/cognizant/storeops/architecture/ModuleBoundaryTest.java) is
the automated dependency analyser the programme requires, and it runs in `mvn test`.

### Event bus

```
TaskService.update()                 → TaskStatusChangedEvent → AlertEventListener  → ESCALATION
TaskService.publishOverdueBreaches() → TaskOverdueEvent       → AlertEventListener  → SLA_BREACH
ProjectService.close()               → ProgrammeClosedEvent   → ReportEventListener → STORE_SUMMARY
```

[EventBus](src/main/java/com/cognizant/storeops/shared/events/EventBus.java) is a one-method
abstraction over Spring's `ApplicationEventPublisher`, kept as a named type so the boundary rule
stays greppable. Publishers import only `shared.events`; events carry enum values as `String` so a
payload can't drag `activities` into its subscribers. Subscribers use
`@TransactionalEventListener(AFTER_COMMIT)`.

**Three requirements that fail silently** — no exception, no log, and a test asserting only HTTP 200
still passes:

| Requirement | If missed |
| --- | --- |
| Publishing method `@Transactional` | listener never runs |
| Listener `@Transactional(REQUIRES_NEW)` | listener runs, its write is never flushed |
| `ErrorHandler` bean in [EventBusConfiguration](src/main/java/com/cognizant/storeops/shared/events/EventBusConfiguration.java) | an alerts bug fails the caller's `PATCH` |

[EventDeliveryIntegrationTest](src/test/java/com/cognizant/storeops/EventDeliveryIntegrationTest.java)
covers all three and asserts the side effect *happened*, not that the request succeeded.

### Error contract

Every error is an `AppError` subtype carrying `(code, message, statusCode)`, and every response has
one shape — framework failures like unmapped paths are normalised into it too.

```json
{"code":"TASK_NOT_FOUND","message":"Task 'x' was not found","statusCode":404,
 "path":"/api/tasks/x","timestamp":"2026-08-20T14:26:43.505Z"}
```

`NotFoundError` 404 · `ValidationError` 400 (+ `details[]`) · `ConflictError` 409 ·
`UnauthorizedError` 401 · `ForbiddenError` 403 · `InternalError` 500

## Tests

96 tests mirroring the module structure: 23 routes slices (`@WebMvcTest`), 25 service unit tests
against fakes in [support/](src/test/java/com/cognizant/storeops/support/), 8 listener unit tests,
12 ArchUnit rules, 25 integration tests (`@SpringBootTest`), 3 event bus tests.

Note that several event tests assert *absence* — those pass just as happily when dispatch is
entirely broken, which is what happened during the Spring migration. Pair any absence-assertion
with a presence-assertion.

## Deliberate gaps

Stubs, not oversights — the harness demonstration run implements these:

- `PATCH /api/activities/bulk-status` — shift handover bulk update
- `GET /api/reports/region/{id}` — regional rollup
- `POST /api/programmes/{id}/templates` — planogram task templates
- SLA escalation chain: `publishOverdueBreaches()` exists but nothing schedules it
- `AuthToken` is modelled; no authentication filter is wired
- `ReportService.markReady()` has no generation pipeline behind it
