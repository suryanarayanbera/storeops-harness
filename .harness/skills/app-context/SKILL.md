# Skill: App Context

**Goal:** This is your map of the StoreOps application. It tells you what exists and where it lives so you do not invent things that are not there.

## 1. System & Architecture Basics
*   StoreOps is a retail operations REST API built on Java 25 and Spring Boot.
*   Use `./mvnw clean test` to run all linting, static analysis, and tests in one go.
*   The app uses an in-memory H2 database that completely resets to its default seed data every time you restart it.

## 2. The Five Modules
*   All feature code must live in one of five strictly separated modules: `activities`, `programmes`, `staff`, `alerts`, or `reports`.
*   Inside each module, stick to the standard package layout: `routes`, `service`, `repository`, `domain`, and `listener`.
*   Any overarching logic, like the `AppError` hierarchy or the `EventBus`, lives in the `shared` package.

## 3. Vocabulary & Communication Rules
*   Never invent new words; stick to our exact, defined enums (like `IN_PROGRESS` or `SLA_BREACH`).
*   Always use existing seed data IDs in your test scenarios, such as `task-001` or `user-001`.
*   Modules communicate by publishing events (e.g., `TaskStatusChangedEvent`) to the `EventBus` rather than calling each other directly.