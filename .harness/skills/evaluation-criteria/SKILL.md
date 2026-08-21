# StoreOps Evaluation Criteria & Definition of Done

**Purpose:** This document defines the strict rubric for the Evaluator agent. You must use these criteria to assess the Generator's output and determine whether a sprint contract passes or fails. Leniency is prohibited.

## 1. The Definition of Done
A feature or sprint is only considered "Done" when it meets ALL of the following criteria:
1. It perfectly satisfies the GIVEN/WHEN/THEN acceptance criteria defined in the sprint contract.
2. The Maven build succeeds with a `0` exit code (compilation, tests, and static analysis).
3. It incurs zero violations of the StoreOps Architecture Principles.

## 2. Dimension A: Automated Hard Gates (Execution)
The Evaluator must run the following command to verify the codebase:
`./mvnw clean test checkstyle:check spotbugs:check`

* **Binary Rule:** If the exit code is not `0`, the evaluation is an automatic **FAIL**.
* You must not attempt to fix or interpret the build failure. Pass the raw error output back to the Generator.

## 3. Dimension B: Architectural Hard Gates (Static Review)
You must statically analyze the files modified by the Generator against the following criteria. A violation of any of these is an automatic **FAIL**.

### A. Layer & Database Isolation
* **Controllers (`@RestController`):** Must contain exactly zero calls to any `@Repository` or JPA EntityManager. They must only delegate to `@Service` classes.
* **Repositories (`@Repository`):** Must only contain Spring Data JPA interfaces or implementations. They must never import classes from outside their own module's package tree (e.g., `com.storeops.activities.repository` cannot import `com.storeops.staff.entity.StaffMember`). Cross-module SQL joins are strictly forbidden.
* **Services (`@Service`):** Must be annotated with `@Transactional` where state mutations occur. 

### B. Event-Driven Boundaries
* **Verification:** If a Service in Module A mutates state and requires Module B to act, Module A *must not* have Module B's Service injected.
* **Requirement:** Look for `ApplicationEventPublisher`. Module A must publish an event, and Module B must use `@EventListener` to react.

### C. The Error Handling Contract
* **Verification:** Scan modified files for the `throw` keyword.
* **Requirement:** Throwing `RuntimeException`, `Exception`, `Error`, or Spring's raw `DataAccessException` is strictly forbidden. All thrown exceptions must extend the `AppError` base class.

## 4. Contract Verification (GIVEN/WHEN/THEN)
The sprint contract provides acceptance criteria in the GIVEN/WHEN/THEN format. 
* You must verify that the corresponding JUnit 5 test classes actively mock or simulate the `GIVEN` state, execute the `WHEN` trigger via `MockMvc` or direct service call, and assert the `THEN` outcome.
* If tests were not written or updated to cover the new contract, issue a **FAIL**.

## 5. Verdict Matrix & Output Rules
Your final output must declare one of the following verdicts:

* **PASS:** All automated checks pass, all architectural hard gates are respected, and tests cover the contract.
* **CONDITIONAL PASS:** Code is functionally and structurally correct, but requires minor non-breaking cleanups (e.g., removing an unused private method). You must list the required cleanups.
* **FAIL:** Any automated check fails, any architectural gate is violated, or tests are missing/failing.

**On FAIL:** You must provide exact file paths, line numbers, and the specific violated rule from this document or the `architecture-principles` document so the Generator can apply a precise fix.