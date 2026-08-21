# StoreOps Architecture Principles

**Purpose:** This document defines the core structural constraints and domain boundaries for the StoreOps API. All agents (Planner, Generator, and Evaluator) must treat these principles as absolute, non-negotiable hard rules. 

## 1. Domain Organization (The 5 Modules)
The system is strictly partitioned into five distinct domain modules. Code must belong to one of these modules—no generic "utils" or "helpers" at the root level.
* **`activities`**: Manages operational tasks (restocking, planograms, compliance).
* **`programmes`**: Manages store-wide initiatives and staff membership.
* **`staff`**: Manages user registration, profiles, and roles (read-only for other modules).
* **`alerts`**: Delivers notifications (in-app, email) triggered by operational events.
* **`reports`**: Aggregates performance metrics across stores and regions.

## 2. The 3-Layer Strict Architecture
Every module must implement a strict 3-tier architecture. Bypassing layers is prohibited.
1. **Routes (Controllers):** Handles HTTP, payload validation, and HTTP status codes. Contains zero business logic.
2. **Service:** Contains all business rules, orchestration, transaction management (`@Transactional`), and event emission. Contains zero HTTP logic.
3. **Repository (`@Repository`):** Manages data access via Spring Data JPA connected to the H2 database. Never calls external services, other modules, or contains business logic.

## 3. Module Boundary & Database Rules (Hard Constraints)
Coupling between modules and their underlying data is tightly controlled to prevent monolithic database drift.
* **Logically Partitioned Data:** Even though H2 is a single database instance, each module must own its own JPA Entities and tables. 
* **No Database Sharing (No Cross-Joins):** A module may NEVER execute SQL queries against or import another module's JPA Entities or Repositories. 
* **Cross-Module Reads:** If Module A needs data from Module B, it must call Module B's Service layer via method invocation (e.g., `TaskService` calling `StaffService.getUser()`).
* **Cross-Module Writes (Event Bus Only):** Direct service-to-service calls for state changes are prohibited. If a state change in Module A requires an action in Module B (e.g., a critical task becomes overdue, triggering an alert), Module A must publish a domain event via the Event Bus. Module B listens for the event and acts.
* **No Circular Dependencies:** Two modules cannot import each other's services.

## 4. The Aggregator Rule
* The `reports` module is an aggregator and is strictly read-only. It compiles data from `activities`, `programmes`, and `staff` but is prohibited from executing any write, update, or delete operations affecting those domains. 

## 5. Predictable Failure (Error Contract)
* Raw errors (`throw new RuntimeException()`) and database-specific exceptions (like Spring's `DataAccessException`) represent untyped, unpredictable system states and are forbidden from leaking out of the Service and Routes layers.
* All deliberate business rule violations, entity-not-found states, and persistence failures must be mapped and thrown as a subclass of the `AppError` typed hierarchy.