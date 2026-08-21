# StoreOps Coding Conventions & Architecture Rules

**Purpose:** This document defines the strict coding standards, error-handling contracts, and architectural layer boundaries for the StoreOps Java/Spring Boot codebase. You must adhere to these rules in every generated file.

## 1. The Error Handling Contract (AppError)
The StoreOps API uses a centralized, typed error hierarchy to ensure consistent API responses.
* **The Rule:** You must never throw raw `RuntimeException`, `Exception`, `Error`, or Spring `DataAccessException` in any service or REST controller.
* **The Standard:** All domain errors must extend the `AppError` base class, which encapsulates a `code` (String), `message` (String), and `statusCode` (int).

## 2. Layer Separation & H2 Database Rules
Every StoreOps module consists of exactly three layers: Routes (Controllers) → Service → Repository. You must not skip layers.

### A. Routes (Spring `@RestController`)
Controllers exist solely to handle HTTP machinery.
* **Allowed:** `@GetMapping`, `@PostMapping`, input validation (via `@Valid`), and delegating DTOs to the Service layer.
* **Prohibited:** Controllers must not contain any domain business logic, `@Transactional` annotations, or direct calls to Repositories.

### B. Service Layer (`@Service`)
The Service layer owns the business logic and transaction boundaries.
* **Allowed:** Managing transactions via `@Transactional`, calling its own module's Repository, calling *other* modules' Services for read-only lookups, and raising domain events.
* **Prohibited:** Services must not deal with HTTP concepts (no `HttpServletRequest`, no `ResponseEntity` returns).

### C. Repository & JPA Entities (`@Repository`, `@Entity`)
The Repository layer handles persistence via Spring Data JPA and the H2 database.
* **Allowed:** Spring Data JPA interfaces extending `JpaRepository`.
* **Prohibited:** JPA Entities must not use relational mappings (e.g., `@OneToMany`, `@ManyToOne`, `@JoinColumn`) that cross module boundaries. Cross-module data relationships must be mapped loosely using scalar IDs (e.g., storing a `staffId` as a `String` or `UUID` in the `Task` entity, rather than mapping to a `StaffMember` entity). 

## 3. Cross-Module Communication & Event Bus
StoreOps enforces strict boundaries between its 5 modules (activities, programmes, staff, alerts, reports).
* **Reads:** A service may inject another module's service for read-only lookups (e.g., `TaskService` calling `StaffService.getUser()`).
* **Writes/Side Effects:** Side effects that cross module boundaries must be raised via the event bus.
* **Hard Rule:** You must never inject a service to trigger a state change in another module (e.g., do not inject `AlertService` to send a notification). Emit an event instead.

### A. Publishing
Inject the project's own `EventBus` interface (`shared.events.EventBus`) and call `eventBus.publish(...)`. Do **not** inject Spring's `ApplicationEventPublisher` directly — `SpringEventBus` is the single adapter that wraps it, so the framework stays an implementation detail and the boundary rule can be stated in terms of a name the Evaluator can grep for.

Events are records in `shared.events` extending `DomainEvent`. **They carry enum values as `String`, never as the owning module's enum type** — a payload typed `TaskStatus` would drag `activities` types into every subscriber and defeat the boundary. The listener compares against a string literal (`"BLOCKED"`).

### B. Three wiring requirements that fail silently
Delivery is `AFTER_COMMIT`, so a side effect is never raised for a state change that rolled back. Get any of these wrong and the event vanishes with **no error anywhere** — nothing logs, nothing throws, and a test that only asserts the HTTP status still passes:

1. **The publishing service method must be `@Transactional`.** Spring skips after-commit callbacks when no transaction is active, so the handler never runs at all.
2. **Each listener handler needs both annotations:**
   ```java
   @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
   @Transactional(propagation = Propagation.REQUIRES_NEW)
   public void onTaskStatusChanged(final TaskStatusChangedEvent event) { ... }
   ```
   Without `REQUIRES_NEW` the original transaction has already committed, so a write that joined it is never flushed and the alert is discarded.
3. **Do not touch `EventBusConfiguration`.** Its `applicationEventMulticaster` bean carries the `eventSubscriberErrorHandler` that stops a failing consumer from breaking the publisher. The bean name must match `AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME` exactly, or Spring silently uses the default and the handler is ignored.

Handlers live in the **reacting** module's `listener/` package (`alerts`, `reports`), import only `shared.events` types, and are the place the decision "does this fact deserve an alert?" is made. The publishing module states a fact and stops caring.

Each of these is an Evaluator hard gate. Static analysis cannot see them: a missing annotation is an absence, and there is nothing to grep for.

## 4. The Reports Module Exception
The `reports` module is an aggregator. It is strictly read-only.
* **Rule:** The `reports` module must never execute write operations (`save`, `update`, `delete`) against the `activities`, `programmes`, or `staff` domains.