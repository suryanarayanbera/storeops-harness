# Skill: Coding Conventions

**Goal:** Keep our code clean, consistent, and strictly layered. 

## 1. Error Handling
* Never throw raw Java exceptions (like `RuntimeException` or `DataAccessException`) in your services or controllers. 
* Always throw a subclass of `AppError`, which guarantees every error has a `code`, `message`, and `statusCode`.

## 2. The 3-Layer Rule
You must strictly separate your logic and never skip layers:
* **Routes (Controllers):** Handle HTTP only (mapping, validation). Never include business logic or `@Transactional` annotations here.
* **Services:** Handle all business rules and transaction boundaries (`@Transactional`). Never include HTTP concepts like `HttpServletRequest` here.
* **Repositories:** Handle pure database access. Never use JPA mappings (like `@OneToMany`) across modules; just store the cross-module reference as a simple `String` or `UUID`.

## 3. Cross-Module Events
Modules must stay isolated. 
* **Reads:** You can inject another module's Service strictly for read-only lookups.
* **Writes:** Never inject a service to trigger a state change in a different module. Instead, publish a `DomainEvent` using `shared.events.EventBus`. 
* **Payloads:** Event records must send enums as simple Strings so modules don't accidentally import each other's custom types.

## 4. Critical Event Wiring
If you mess these up, events will fail silently without throwing any errors:
1. The service method publishing the event **must** be `@Transactional`. 
2. The listener method receiving the event **must** have both `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` and `@Transactional(propagation = Propagation.REQUIRES_NEW)`.
3. Never touch `EventBusConfiguration`—it handles error isolation automatically.

## 5. The Reports Exception
* The `reports` module is strictly a read-only aggregator. It must never run write operations (`save`, `update`, `delete`) against other domains.