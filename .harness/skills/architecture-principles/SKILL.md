# Skill: Architecture Principles

**Goal:** Keep our codebase clean, decoupled, and strictly organized.

## 1. The 5 Modules
Everything belongs in exactly one of these modules: `activities`, `programmes`, `staff`, `alerts`, or `reports`. No generic "utils" folders allowed.

## 2. The 3-Layer Rule
Don't mix responsibilities.
* **Routes:** HTTP only. No business logic.
* **Services:** Business rules and events live here. No HTTP.
* **Repositories:** Pure database access. No business logic.

## 3. Strict Boundaries
* **No Database Sharing:** Each module owns its tables. Cross-module SQL joins are completely banned.
* **Reading Data:** Call another module's Service layer.
* **Changing Data:** Publish an event to the Event Bus. Direct cross-module writes are forbidden.
* **No Circular Dependencies:** Modules cannot import each other back and forth.

## 4. The Reports Module
`reports` is strictly a read-only aggregator. It can never modify data in other modules.

## 5. Error Handling
Never throw raw Java exceptions. Always use our custom `AppError` hierarchy so the API always returns a predictable error.