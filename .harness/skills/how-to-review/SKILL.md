# Skill: How to Review

**Goal:** A step-by-step guide for reviewing a sprint efficiently and providing actionable feedback.

## 1. Scope Your Review
* Review only the files listed in `generator-summary.md` and their corresponding test files.
* Check `git status --porcelain` to ensure there are no undeclared file changes.
* Leave untouched legacy code alone—do not review or score existing baseline gaps.

## 2. Check Build Failures First
Run `./mvnw clean test` and identify where any failure occurs:
* **Checkstyle / SpotBugs:** Style or bug pattern violations.
* **ArchUnit (`ModuleBoundaryTest`):** Architecture and boundary rule violations.
* **JaCoCo:** Test coverage drops below required thresholds.
* **JUnit / javac:** Broken assertions, unmet acceptance criteria, or compilation errors.
* *Quote the exact `file:line` error in your findings so the Generator can fix it in one pass.*

## 3. Trust What ArchUnit Covered
If the build is green, ArchUnit has already verified:
* No cross-module repository imports or circular dependencies.
* The `reports` module is strictly read-only.
* Controllers do not reach Repositories directly, and `AppError` subtypes are used.

## 4. What to Check Manually
Automated checks cannot catch these five silent failure modes:
* **Missing Events:** A state change occurred, but no `DomainEvent` was published.
* **Silent Event Wiring:** The publisher is missing `@Transactional`, or the listener is missing `@TransactionalEventListener(AFTER_COMMIT)` or `@Transactional(REQUIRES_NEW)`.
* **Weak Tests:** Tests only assert `status().isOk()` or use standalone `isEmpty()` checks without asserting real database state or event payloads.
* **Logic in Routes:** Business rules (SLA math, enum checks, status transitions) exist inside a `@RestController`.
* **Invented Domain:** Custom enums, route paths, or unmapped raw errors were created outside the defined domain vocabulary.

## 5. How to Format Findings
State the exact file, line number, violated rule, and concrete fix: